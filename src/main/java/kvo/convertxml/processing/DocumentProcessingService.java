package kvo.convertxml.processing;

import kvo.convertxml.client.ImanDocumentEnricher;
import kvo.convertxml.client.ImanUnavailableException;
import kvo.convertxml.client.LlmBadOutputException;
import kvo.convertxml.client.LlmPermanentException;
import kvo.convertxml.client.OcrUnavailableException;
import kvo.convertxml.config.ImanProperties;
import kvo.convertxml.infra.ClaimHeartbeat;
import kvo.convertxml.infra.DocumentTaskDao;
import kvo.convertxml.infra.DocumentTaskDao.TaskHeader;
import kvo.convertxml.infra.InstanceId;
import kvo.convertxml.metrics.ServiceMetrics;
import kvo.convertxml.parser.OcrEmptyTextException;
import kvo.convertxml.parser.OcrSizeLimitException;
import kvo.convertxml.parser.ParserDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);
    private final ClaimHeartbeat heartbeat;
    private final ServiceMetrics metrics;             // +++ метрики для Grafana

    /** Ограничение текста ошибки для записи в БД — защита колонки error от переполнения. */
    private static final int MAX_ERROR_LEN = 500;

    /**
     * Порог подряд идущих сбоев OCR, специфичных для конкретного документа.
     * Кейс задачи 31487: битый PDF бесконечно гонялся по ретраям
     * (markTransient не растит retry_count), каждые ~2 мин штрафуя серверы.
     */
    private final int ocrPoisonRetries;

    /**
     * Порог подряд транзитных сбоев любого рода (poison-счётчик инфра-сбои
     * игнорирует, зомби-счётчик считает). Кейсы 29927, 85696, 112458 и далее:
     * недели циклов «OCR, завис, transient, повтор», каждый цикл жжёт
     * server-время, измеряемое произведением числа задействованных серверов
     * на read-timeout OCR.
     */
    private final int zombieTransientRetries;

    /**
     * Счётчики подряд транзитных сбоев по задачам.
     * <p>
     * Poison-счётчик наращивается только на отказах, специфичных для конкретного
     * документа. Инфраструктурный сбой обнуляет серию — иначе часовой простой
     * кластера отправил бы в rejects все задачи.
     * <p>
     * Зомби-счётчик наращивается на любом транзитном сбое, но тоже обнуляется
     * во время глобального простоя (см. registerTransientFail) — защита та же.
     */
    private final ConcurrentHashMap<Long, TransientFail> transientFails
            = new ConcurrentHashMap<>();

    /** Через сколько тишины по задаче забыть счётчик (защита от утечки памяти). */
    private static final long POISON_EVICT_AFTER_MS = 60L * 60 * 1000;

    /**
     * Если за это окно ни одна задача не дошла до терминального успеха — считаем
     * кластер в простое: зомби-счётчики не растут, массовых rejects при полном
     * отказе OCR/LLM не будет — задачи просто ждут, как раньше.
     */
    private static final long OUTAGE_WINDOW_MS = 10L * 60 * 1000;

    /** Момент последнего терминального успеха (markDone или markEmpty); ноль — не было. */
    private volatile long lastSuccessAtMs;

    private final DocumentTaskDao dao;
    private final ParserDispatcher parser;
    private final InstanceId instanceId;
    private final ThreadPoolTaskExecutor heavyWorkers;
    private final long heavyThresholdBytes;
    private final ImanDocumentEnricher iman;
    private final boolean imanEnabled;
    private final int minTextLength;

    public DocumentProcessingService(DocumentTaskDao dao,
                                     ParserDispatcher parser,
                                     InstanceId instanceId,
                                     @Qualifier("docHeavyWorkers") ThreadPoolTaskExecutor heavyWorkers,
                                     @Value("${app.heavy-threshold-mb:10}") int heavyThresholdMb,
                                     ImanDocumentEnricher iman,
                                     ImanProperties imanProps,
                                     @Value("${app.min-length-xml:20}") int minTextLength,
                                     ClaimHeartbeat heartbeat,
                                     @Value("${app.ocr-poison-retries:3}") int ocrPoisonRetries,
                                     @Value("${app.zombie-transient-retries:20}") int zombieTransientRetries,
                                     ServiceMetrics metrics) {
        this.dao = dao;
        this.parser = parser;
        this.instanceId = instanceId;
        this.heavyWorkers = heavyWorkers;
        this.heavyThresholdBytes = heavyThresholdMb * 1024L * 1024L;
        this.iman = iman;
        this.imanEnabled = imanProps.enabled();
        this.minTextLength = minTextLength;
        this.heartbeat = heartbeat;
        this.ocrPoisonRetries = Math.max(2, ocrPoisonRetries);
        this.zombieTransientRetries = Math.max(10, zombieTransientRetries);
        this.metrics = metrics;
    }

    public void process(TaskHeader row, UUID token) {
        String me = instanceId.get();
        heartbeat.register(row.id(), token);
        if (row.sizeBytes() >= heavyThresholdBytes) {
            try {
                heavyWorkers.execute(() -> execute(row, token, me));
            } catch (TaskRejectedException e) {
                requeue(row.id(), token, me, 10, "heavy queue full");
                heartbeat.release(row.id());
            }
        } else {
            execute(row, token, me);
        }
    }

    /** Результат фазы загрузки и парсинга: путь к temp-файлу и тайминги фаз. */
    private record Parsed(Path txtFile, long loadMs, long parseMs) {}

    /** Тайминги фаз одной задачи — без этого finishTask разрастался до 8–9 параметров. */
    private record TaskTiming(long loadMs, long parseMs, long imanMs, long started) {}

    /**
     * Только диспетчеризация: счастливый путь плюс по одному обработчику на класс сбоя.
     * Вся содержательная логика вынесена в методы ниже.
     */
    private void execute(TaskHeader row, UUID token, String me) {
        final long started = System.nanoTime();
        Path txtFile = null;
        try {
            txtFile = runPipeline(row, token, me, started);
        } catch (OcrSizeLimitException e) {
            rejectOversized(row, token, me, e);
        } catch (LlmBadOutputException e) {
            countFailedLlmAttempt(row, token, me, e);
        } catch (LlmPermanentException e) {
            countFailedLlmConfig(row, token, me, e);
        } catch (ImanUnavailableException | OcrUnavailableException e) {
            handleTransientFailure(row, token, me, e);
        } catch (OcrEmptyTextException e) {
            rejectEmptyOcr(row, token, me, e);
        } catch (IllegalArgumentException e) {
            rejectBadFormat(row, token, me, e);
        } catch (Exception e) {
            long totalMs = elapsed(started);
            log.error("Ошибка обработки задачи {} за {} мс", row.id(), totalMs, e);
            metrics.taskFailed("error");                                   // +++
            // Счётчики транзитных сбоев не чистим: сбой мог случиться после
            // успешного OCR, счётчик должен дожить до следующей попытки.
            safeMarkFailed(row.id(), me, token, e);
        } finally {
            metrics.recordTaskTotal(elapsed(started));                     // +++ p95 цикла любой попытки
            deleteTempFile(txtFile);
            heartbeat.release(row.id());          // всегда, при любом исходе
        }
    }

    /** Формат исходника не опознан — детерминированная ошибка документа.
     *  Сразу REJECTED, без двух ERROR-итераций со стектрейсом. */
    private void rejectBadFormat(TaskHeader row, UUID token, String me,
                                 IllegalArgumentException e) {
        transientFails.remove(row.id());
        metrics.taskRejected("bad_format");                                // +++
        boolean ok = dao.markRejected(row.id(), me, token, truncate(e.getMessage()));
        log.warn("Задача {}: неподдерживаемый/повреждённый исходник — {} "
                + "(rejected={})", row.id(), e.getMessage(), ok);
    }

    private void rejectEmptyOcr(TaskHeader row, UUID token, String me,
                                OcrEmptyTextException e) {
        transientFails.remove(row.id());
        metrics.taskRejected("empty_ocr");                                 // +++
        boolean ok = dao.markRejected(row.id(), me, token, truncate(e.getMessage()));
        log.warn("Задача {}: {} (rejected={})", row.id(), e.getMessage(), ok);
    }

    /** Счастливый путь: загрузка, парсинг/OCR, опциональная LLM-шапка, DONE.
     *  Возвращает temp-файл — execute удалит его в finally при любом исходе.
     *  Неспецифичные сбои (в том числе IOException) пробрасываются в catch (Exception). */
    private Path runPipeline(TaskHeader row, UUID token, String me, long started) throws IOException {
        Parsed parsed = loadAndParse(row);
        // Пустота проверяется по сырому тексту, до вызова iman — не тратим LLM-вызов
        String text = Files.readString(parsed.txtFile(), StandardCharsets.UTF_8);
        if (isTextTooShort(text)) {
            markTooShort(row, token, me);
            return parsed.txtFile();
        }
        long t = System.nanoTime();
        String header = imanEnabled ? iman.extractHeader(text) : "";
        long imanMs = elapsed(t);
        finishTask(row, token, me, header, text,
                new TaskTiming(parsed.loadMs(), parsed.parseMs(), imanMs, started));
        return parsed.txtFile();
    }

    /**
     * Загрузка исходника и парсинг в текстовый temp-файл — С КЭШЕМ по задаче.
     * <p>
     * После успешного parse (для сканов внутри — OCR на 5–17 минут серверного
     * времени) текст сохраняется в doc_documents.parsed_text. Повтор задачи —
     * невалидный XML от LLM (markFailed), таймаут LLM (markTransient) — берёт
     * текст из кэша и идёт сразу в LLM, БЕЗ повторного OCR.
     * <p>
     * Кэш чистится только в терминальных исходах (DONE / пустой текст /
     * REJECTED — см. SQL в DocumentTaskDao), поэтому таблица не распухает.
     * <p>
     * Байты исходника не нужны после parseToText: локальная переменная источника
     * умирает на выходе из метода, ссылка недостижима и GC освободит память
     * ещё до вызова LLM — отдельное присваивание null не требуется.
     */
    private Parsed loadAndParse(TaskHeader row) {
        long t = System.nanoTime();
        String cached = dao.loadParsedText(row.id());
        if (cached != null) {
            Path fromCache = writeTempText(cached);
            long loadMs = elapsed(t);
            log.info("Задача {}: текст из кэша ({} симв., {} мс) — "
                    + "парсинг/OCR пропущены", row.id(), cached.length(), loadMs);
            return new Parsed(fromCache, loadMs, 0);
        }
        byte[] source = dao.loadBinary(row.fileId());
        long loadMs = elapsed(t);
        t = System.nanoTime();
        Path txtFile = parser.parseToText(row.fileName(), source);
        long parseMs = elapsed(t);
        saveParsedTextQuietly(row.id(), txtFile);
        return new Parsed(txtFile, loadMs, parseMs);
    }

    /**
     * Кэш → temp-файл: дальнейший конвейер (LLM) работает с файлом, как раньше.
     */
    private Path writeTempText(String text) {
        try {
            Path tmp = Files.createTempFile("doctext-", ".txt");
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            return tmp;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Не удалось создать temp-файл из кэша текста", e);
        }
    }

    /**
     * Кэширование — best effort: сбой записи кэша не должен валить
     * уже сделанную работу; худший случай — повтор честно перепарсит.
     */
    private void saveParsedTextQuietly(long id, Path txtFile) {
        try {
            dao.saveParsedText(id, Files.readString(txtFile, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("Задача {}: кэш текста не сохранён — повтор повторит "
                    + "парсинг ({})", id, e.getMessage());
        }
    }

    private boolean isTextTooShort(String text) {
        return text.isBlank() || text.length() < minTextLength;
    }

    private void markTooShort(TaskHeader row, UUID token, String me) {
        boolean marked = dao.markEmptyResult(row.id(), me, token);
        transientFails.remove(row.id());       // терминальный исход
        metrics.taskEmpty();                                                   // +++
        clusterAlive();                        // OCR отработал — кластер жив
        log.warn("Задача {}: извлечённый текст пуст/короче {} симв. (marked={})",
                row.id(), minTextLength, marked);
    }

    private void finishTask(TaskHeader row, UUID token, String me,
                            String header, String text, TaskTiming tm) {
        String enriched = header.isEmpty() ? text : header + "\n" + text;
        boolean saved = dao.markDone(row.id(), me, token, enriched);
        if (saved) {
            transientFails.remove(row.id());   // терминальный исход
            clusterAlive();                    // конвейер жив
            metrics.taskDone();                                                // +++
            metrics.recordTaskLoad(tm.loadMs());                               // +++
            metrics.recordTaskParse(tm.parseMs());                             // +++
            metrics.recordTaskIman(tm.imanMs());                               // +++
            log.info("Задача {} завершена: load={} мс, parse={} мс, iman={} мс, итог {} мс",
                    row.id(), tm.loadMs(), tm.parseMs(), tm.imanMs(), elapsed(tm.started()));
            warnSlowParse(row, tm.parseMs());
        } else {
            log.warn("Задача {} перехвачена другим инстансом, результат отброшен", row.id());
        }
    }

    private static void warnSlowParse(TaskHeader row, long parseMs) {
        if (parseMs > 10_000) {
            log.warn("Задача {}: медленный parse {} мс: {}",
                    row.id(), parseMs, row.fileName());
        }
    }

    /** Файл больше лимита OCR — терминальный REJECTED без распознавания. */
    private void rejectOversized(TaskHeader row, UUID token, String me, OcrSizeLimitException e) {
        transientFails.remove(row.id());       // терминальный исход
        metrics.taskRejected("oversized");                                     // +++
        boolean ok = dao.markRejected(row.id(), me, token, truncate(e.getMessage()));
        log.warn("Задача {}: отклонена без OCR — {} (rejected={})", row.id(), e.getMessage(), ok);
    }

    /**
     * Ответ LLM детерминированно неисправим (битый XML, обрезан, нет Document):
     * повтор с тем же входом даст то же самое. Засчитываем неудачную попытку
     * (markFailed растит retry_count) — после app.max-retries задача остановится
     * в ERROR вместо бесконечного цикла с повторным OCR.
     * <p>
     * Счётчики транзитных сбоев намеренно не чистим — иначе чередование
     * «OCR-poison и LLM-бад» сбрасывало бы счётчик, и задача крутилась бы вечно.
     */
    private void countFailedLlmAttempt(TaskHeader row, UUID token, String me, LlmBadOutputException e) {
        metrics.taskFailed("llm_bad_output");                                  // +++
        metrics.llmBadOutput();                                                // +++
        safeMarkFailed(row.id(), me, token, e);
        log.warn("Задача {}: неисправимый ответ LLM, засчитана неудачная попытка: {}",
                row.id(), e.getMessage());
    }

    /** Конфигурация LLM неверна — повтор бессмыслен. Засчитываем попытку:
     *  после app.max-retries задача остановится в ERROR. После починки
     *  iman.model — вернуть в очередь (status=0, retry_count=0). */
    private void countFailedLlmConfig(TaskHeader row, UUID token, String me, LlmPermanentException e) {
        metrics.taskFailed("llm_config");                                      // +++
        safeMarkFailed(row.id(), me, token, e);
        log.error("Задача {}: ПОСТОЯННАЯ ошибка LLM — {}. Проверьте iman.model и iman.base-url",
                row.id(), e.getMessage());
    }

    /** Poison-детектор, зомби-кап и обычный транзитный возврат в очередь. */
    private void handleTransientFailure(TaskHeader row, UUID token, String me, RuntimeException e) {
        TransientFail f = registerTransientFail(row.id(), e.getMessage());
        if (f.count >= ocrPoisonRetries) {
            rejectPoisonDocument(row, token, me, e, f.count);
            return;
        }
        if (f.anyCount >= zombieTransientRetries) {
            rejectZombieTask(row, token, me, e, f.anyCount);
            return;
        }
        metrics.taskTransient();                                               // +++
        requeue(row.id(), token, me, backoffDelaySec(f.anyCount), e.getMessage());
    }

    /** Живой OCR-сервис ответил, но логически отказал на этом же файле N раз
     *  подряд — документ битый, ретраи бессмысленны. Инфраструктурные сбои
     *  не считаются: «нет свободных серверов» — это про кластер, не про документ. */
    private void rejectPoisonDocument(TaskHeader row, UUID token, String me,
                                      RuntimeException e, int streak) {
        transientFails.remove(row.id());
        metrics.taskRejected("poison");                                        // +++
        boolean ok = dao.markRejected(row.id(), me, token,
                truncate("poison-документ: " + e.getMessage()));
        log.warn("Задача {}: {} подряд документ-специфичных отказов OCR — "
                        + "документ битый, отклоняем (rejected={}): {}",
                row.id(), streak, ok, e.getMessage());
    }

    /**
     * Зомби-кап: слишком много подряд транзитных сбоев любого рода без единого
     * успеха, при том что кластер жив (у других задач успехи есть). Каждый такой
     * цикл сжигает слот и серверное время, поэтому задача переводится в REJECTED
     * с максимальным retry_count. Реанимация, если ошиблись:
     * вернуть status и счётчик попыток в исходные значения командой UPDATE по id.
     */
    private void rejectZombieTask(TaskHeader row, UUID token, String me,
                                  RuntimeException e, int streak) {
        transientFails.remove(row.id());
        metrics.taskRejected("zombie");                                        // +++
        boolean ok = dao.markRejected(row.id(), me, token, truncate(
                "ZOMBIE: " + streak + " подряд транзитных сбоев, последний: " + e.getMessage()));
        log.warn("Задача {}: ЗОМБИ — {} подряд транзитных сбоев → REJECTED (rejected={}): {}",
                row.id(), streak, ok, e.getMessage());
    }

    /** Экспоненциальный backoff с джиттером (50–100 % расчётной задержки):
     *  серия 1 → 30–60 с, 3 → 76–153 с, 6 → 5–10 мин, 9+ → 7.5–15 мин (потолок 15 мин).
     *  Фиксированные 60–120 с при 20 воркерах давали синхронный ретрай-шторм:
     *  все разом возвращались в очередь и одним залпом били в уцелевшие серверы
     *  (в логе — три одновременных сбоя doc-app7 от воркеров 16/14/13 в одну мс). */
    private static int backoffDelaySec(int streak) {
        int exp = (int) Math.min(900, 60 * Math.pow(1.6, Math.max(0, streak - 1)));
        return exp / 2 + ThreadLocalRandom.current().nextInt(exp / 2 + 1);
    }

    private static void deleteTempFile(Path txtFile) {
        if (txtFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(txtFile);
        } catch (IOException e) {
            log.warn("Не удалось удалить временный файл {}", txtFile, e);
        }
    }

    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // Poison-детектор и зомби-кап
    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    /**
     * Зафиксировать транзитный сбой и вернуть текущие счётчики по задаче.
     * Poison-счётчик наращивается только на документ-специфичных отказах,
     * инфраструктурный сбой обнуляет серию. Зомби-счётчик наращивается на любом
     * транзитном сбое, но обнуляется, если кластер в глобальном простое (за окно
     * OUTAGE_WINDOW_MS ни одного терминального успеха) — полный отказ
     * инфраструктуры не должен рассылать задачи в rejects.
     * <p>
     * Точное сравнение текста ошибки не подходит: имя сервера в сообщении
     * меняется от попытки к попытке (round-robin), а суть — нет.
     */
    private TransientFail registerTransientFail(long id, String error) {
        boolean docSpecific = looksDocumentSpecific(error);
        boolean outage = System.currentTimeMillis() - lastSuccessAtMs > OUTAGE_WINDOW_MS;
        return transientFails.compute(id, (k, prev) -> {
            if (prev == null) {
                return new TransientFail(docSpecific, outage);
            }
            if (docSpecific) {
                prev.count++;               // poison: подряд документ-специфичных
            } else {
                prev.count = 0;             // poison: инфра-сбой рвёт серию
            }
            prev.anyCount = outage ? 0 : prev.anyCount + 1;
            prev.lastAt = System.currentTimeMillis();
            return prev;
        });
    }

    /** Конвейер довёл задачу до терминального успеха — кластер жив. */
    private void clusterAlive() {
        lastSuccessAtMs = System.currentTimeMillis();
    }

    /**
     * Ошибка зависит от документа, а не от инфраструктуры: живой OCR-сервис
     * ответил логическим отказом на этот файл (конверт вернул неуспех
     * с деталями неудачного парсинга PDF).
     */
    private static boolean looksDocumentSpecific(String error) {
        return error != null
                && (error.contains("IsSuccess=false")
                || error.contains("InvalidPdfException")
                || error.contains("Rebuild failed"));
    }

    /** Гигиена карты: раз в 10 минут выбрасывать счётчики, тихие более часа. */
    @Scheduled(fixedDelayString = "${app.poison-evict-ms:600000}")
    void evictStaleTransientFails() {
        long cutoff = System.currentTimeMillis() - POISON_EVICT_AFTER_MS;
        transientFails.values().removeIf(f -> f.lastAt < cutoff);
    }

    /** Счётчик подряд идущих транзитных сбоев по задаче. */
    private static final class TransientFail {
        int count = 1;              // подряд документ-специфичных (poison)
        int anyCount = 1;           // подряд транзитных любого рода (зомби-кап)
        volatile long lastAt = System.currentTimeMillis();

        TransientFail(boolean docSpecific, boolean outage) {
            this.count = docSpecific ? 1 : 0;
            this.anyCount = outage ? 0 : 1;
        }
    }

    /** Транзитный сбой: вернуть задачу в очередь с паузой delaySec, без роста retry_count. */
    private void requeue(long id, UUID token, String me, int delaySec, String why) {
        boolean ok = dao.markTransient(id, me, token, delaySec);
        log.warn("Задача {}: транзитный сбой — повтор через {} с (queued={}): {}",
                id, delaySec, ok, why);
    }

    private static long elapsed(long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000;
    }

    private void safeMarkFailed(long taskId, String me, UUID token, Exception e) {
        safeMarkFailed(taskId, me, token, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private void safeMarkFailed(long taskId, String me, UUID token, String error) {
        try {
            dao.markFailed(taskId, me, token, truncate(error));
        } catch (Exception markError) {
            log.error("Не удалось записать FAILED для задачи {} — останется для перехвата "
                    + "по stale-таймауту. Причина: {}", taskId, markError.getMessage());
        }
    }

    /** Обрезка для записи в БД (колонка error ограничена). В логах не используется. */
    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "…";
    }
}