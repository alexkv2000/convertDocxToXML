package kvo.convertxml.processing;

import kvo.convertxml.client.ImanDocumentEnricher;
import kvo.convertxml.client.ImanUnavailableException;
import kvo.convertxml.client.LlmBadOutputException;
import kvo.convertxml.client.OcrUnavailableException;
import kvo.convertxml.config.ImanProperties;
import kvo.convertxml.infra.ClaimHeartbeat;
import kvo.convertxml.infra.DocumentTaskDao;
import kvo.convertxml.infra.DocumentTaskDao.TaskHeader;
import kvo.convertxml.infra.InstanceId;
import kvo.convertxml.parser.OcrSizeLimitException;
import kvo.convertxml.parser.ParserDispatcher;
import kvo.convertxml.client.LlmPermanentException;
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

    /** Ограничение текста ошибки для записи в БД — защита колонки error от переполнения. */
    private static final int MAX_ERROR_LEN = 500;

    // +++ Порог подряд идущих ДОКУМЕНТ-специфичных сбоев OCR до rejects.
    // +++ Кейс задачи 31487: битый PDF бесконечно гонялся по ретраям
    // +++ (markTransient не растит retry_count), каждые ~2 мин штрафуя серверы.
    private final int ocrPoisonRetries;

    // ### ЗОМБИ-КАП: порог подряд ТРАНЗИТНЫХ сбоев ЛЮБОГО рода (poison-счётчик
    // ### инфра-сбои игнорирует, зомби-счётчик — считает). Кейсы 29927, 85696,
    // ### 112458…: недели циклов «OCR → завис → transient → повтор», каждый цикл
    // ### жжёт до max-servers-per-request × ocr.read-timeout серверного времени.
    private final int zombieTransientRetries;                                    // ###

    // +++ Счётчики подряд документ-специфичных транзитных сбоев по задачам.
    // +++ POISON-счётчик (count) растёт ТОЛЬКО на документ-специфичных отказах;
    // +++ инфраструктурные его обнуляют — иначе часовой простой кластера
    // +++ отправил бы в rejects все задачи.
    // ### ЗОМБИ-счётчик (anyCount) растёт на любом транзитном сбое, НО обнуляется
    // ### во время глобального простоя (см. isOutageNow) — защита та же.
    private final ConcurrentHashMap<Long, TransientFail> transientFails
            = new ConcurrentHashMap<>();

    /** Через сколько «тишины» по задаче забыть счётчик (защита от утечки памяти). */
    private static final long POISON_EVICT_AFTER_MS = 60L * 60 * 1000;

    // ### Если за это окно НИ ОДНА задача не дошла до терминального успеха —
    // ### считаем кластер в простое: зомби-счётчики не растут (массовых rejects
    // ### при полном отказе OCR/LLM не будет — задачи просто ждут, как раньше).
    private static final long OUTAGE_WINDOW_MS = 10L * 60 * 1000;                // ###
    /** ### Момент последнего терминального успеха (markDone/markEmpty); 0 — не было. */
    private volatile long lastSuccessAtMs;                                       // ###

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
                                     @Value("${app.ocr-poison-retries:5}") int ocrPoisonRetries,
                                     @Value("${app.zombie-transient-retries:20}") int zombieTransientRetries) { // ###
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
        this.zombieTransientRetries = Math.max(10, zombieTransientRetries);      // ###
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

    private void execute(TaskHeader row, UUID token, String me) {
        long t0 = System.nanoTime();
        Path txtFile = null;
        try {
            long t = System.nanoTime();
            byte[] source = dao.loadBinary(row.fileId());
            long loadMs = elapsed(t);
            t = System.nanoTime();
            txtFile = parser.parseToText(row.fileName(), source);
            source = null;                       // отпустить исходник до вызова LLM/записи в БД
            long parseMs = elapsed(t);
            // Пустота проверяется по сырому тексту, ДО вызова iman — не тратим LLM-вызов
            String text = Files.readString(txtFile, StandardCharsets.UTF_8);
            if (text.isBlank() || text.length() < minTextLength) {
                boolean marked = dao.markEmptyResult(row.id(), me, token);
                transientFails.remove(row.id());                                           // терминальный исход
                clusterAlive();                                                           // ### OCR отработал — кластер жив
                log.warn("Задача {}: извлечённый текст пуст/короче {} симв. (marked={})",
                        row.id(), minTextLength, marked);
                return;
            }
            t = System.nanoTime();
            String header = imanEnabled ? iman.extractHeader(text) : "";
            long imanMs = elapsed(t);
            String enriched = header.isEmpty() ? text : header + "\n" + text;
            boolean saved = dao.markDone(row.id(), me, token, enriched);
            if (saved) {
                transientFails.remove(row.id());                                           // терминальный исход
                clusterAlive();                                                           // ### конвейер жив
                log.info("Задача {} завершена: load={} мс, parse={} мс, iman={} мс, итог {} мс",
                        row.id(), loadMs, parseMs, imanMs, elapsed(t0));
                if (parseMs > 10_000) {
                    log.warn("Задача {}: медленный parse {} мс: {}",
                            row.id(), parseMs, row.fileName());
                }
            } else {
                log.warn("Задача {} перехвачена другим инстансом, результат отброшен", row.id());
            }
        } catch (OcrSizeLimitException e) {
            transientFails.remove(row.id());                                               // терминальный исход
            boolean ok = dao.markRejected(row.id(), me, token, truncate(e.getMessage()));
            log.warn("Задача {}: отклонена без OCR — {} (rejected={})", row.id(), e.getMessage(), ok);
        } catch (LlmBadOutputException e) {
            // +++ Ответ LLM детерминированно неисправим (битый XML / обрезан / нет <Document>):
            // +++ повтор с тем же входом даст то же самое. Засчитываем неудачную попытку
            // +++ (markFailed растит retry_count) — после app.max-retries задача
            // +++ остановится в ERROR вместо бесконечного цикла с повторным OCR.
            // ВАЖНО: transientFails НЕ чистим — иначе чередование «OCR-poison /
            // LLM-бад» сбрасывало бы счётчик и задача крутилась бы вечно.
            safeMarkFailed(row.id(), me, token, e);
            log.warn("Задача {}: неисправимый ответ LLM, засчитана неудачная попытка: {}",
                    row.id(), truncate(e.getMessage()));
        } catch (LlmPermanentException e) {
            // Конфигурация LLM неверна — повтор бессмыслен. Засчитываем попытку:
            // после app.max-retries задача остановится в ERROR, а не будет
            // бесконечно перемалывать OCR. После починки iman.model — вернуть в очередь.
            safeMarkFailed(row.id(), me, token, e);
            log.error("Задача {}: ПОСТОЯННАЯ ошибка LLM — {}. Проверьте iman.model / iman.base-url",
                    row.id(), truncate(e.getMessage()));
        } catch (ImanUnavailableException | OcrUnavailableException e) {
            // +++ Poison-детектор: живой OCR-сервис ОТВЕТИЛ, но логически отказал на
            // +++ этом же файле (IsSuccess=false / InvalidPdfException) N раз подряд —
            // +++ документ битый, ретраи бессмысленны. Инфра-сбои не считаются:
            // +++ «нет свободных серверов» — это про кластер, не про документ.
            TransientFail f = registerTransientFail(row.id(), e.getMessage());            // ### (был int)
            if (f.count >= ocrPoisonRetries) {
                transientFails.remove(row.id());
                boolean ok = dao.markRejected(row.id(), me, token,
                        truncate("poison-документ: " + e.getMessage()));
                log.warn("Задача {}: {} подряд документ-специфичных отказов OCR — "
                                + "документ битый, отклоняем (rejected={}): {}",
                        row.id(), f.count, ok, truncate(e.getMessage()));
                return;
            }
            // ### ЗОМБИ-КАП: слишком много подряд транзитных сбоев ЛЮБОГО рода без
            // ### единого успеха, при том что кластер жив (у других задач успехи есть).
            // ### Каждый такой цикл сжигает слот и до 2 × 360 с серверного времени.
            // ### REJECTED (retry_count=9999). Реанимация, если ошиблись:
            // ### UPDATE … SET status=0, retry_count=0 WHERE id=…
            if (f.anyCount >= zombieTransientRetries) {                                   // ###
                transientFails.remove(row.id());                                          // ###
                boolean ok = dao.markRejected(row.id(), me, token, truncate(              // ###
                        "ZOMBIE: " + f.anyCount + " подряд транзитных сбоев, "           // ###
                                + "последний: " + e.getMessage()));                       // ###
                log.warn("Задача {}: ЗОМБИ — {} подряд транзитных сбоев → "               // ###
                                + "REJECTED (rejected={}): {}",                           // ###
                        row.id(), f.anyCount, ok, truncate(e.getMessage()));              // ###
                return;                                                                   // ###
            }                                                                             // ###
            int delay = 60 + ThreadLocalRandom.current().nextInt(60);   // 60–120 с
            requeue(row.id(), token, me, delay, e.getMessage());
        } catch (Exception e) {
            log.error("Ошибка обработки задачи {} за {} мс", row.id(), elapsed(t0), e);
            // transientFails НЕ чистим: сбой мог случиться после успешного OCR,
            // счётчик должен дожить до следующей попытки.
            safeMarkFailed(row.id(), me, token, e);
        } finally {
            if (txtFile != null) {
                try {
                    Files.deleteIfExists(txtFile);
                } catch (IOException e) {
                    log.warn("Не удалось удалить временный файл {}", txtFile, e);
                }
            }
            heartbeat.release(row.id());          // ВСЕГДА — последняя строка, вне if
        }
    }

    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
    // +++ Poison-детектор + зомби-кап
    // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

    /**
     * Зафиксировать транзитный сбой и вернуть текущие счётчики по задаче.
     * POISON-счётчик (count): растёт только на документ-специфичных отказах,
     * инфраструктурный сбой его обнуляет (как и раньше).
     * ЗОМБИ-счётчик (anyCount): растёт на любом транзитном сбое, но обнуляется,
     * если кластер в глобальном простое (за окно OUTAGE_WINDOW_MS ни одного
     * терминального успеха) — полный отказ инфраструктуры не должен рассылать
     * задачи в rejects.
     * Точное сравнение текста ошибки не подходит: имя сервера в сообщении меняется
     * от попытки к попытке (round-robin), а суть — нет.
     */
    private TransientFail registerTransientFail(long id, String error) {                  // ### (был int)
        boolean docSpecific = looksDocumentSpecific(error);                               // ###
        boolean outage = System.currentTimeMillis() - lastSuccessAtMs > OUTAGE_WINDOW_MS; // ###
        TransientFail f = transientFails.compute(id, (k, prev) -> {
            if (prev == null) {
                return new TransientFail(docSpecific, outage);                            // ###
            }
            if (docSpecific) {
                prev.count++;               // poison: подряд документ-специфичных
            } else {
                prev.count = 0;             // poison: инфра-сбой рвёт серию
            }
            prev.anyCount = outage ? 0 : prev.anyCount + 1;                               // ###
            prev.lastAt = System.currentTimeMillis();
            return prev;
        });
        return f;
    }

    /** ### Конвейер довёл задачу до терминального успеха — кластер жив. */
    private void clusterAlive() {                                                         // ###
        lastSuccessAtMs = System.currentTimeMillis();                                     // ###
    }                                                                                     // ###

    /**
     * Ошибка зависит от ДОКУМЕНТА, а не от инфраструктуры: живой OCR-сервис
     * ответил логическим отказом на этот файл (конверт IsSuccess=false
     * с деталями неудачного парсинга PDF).
     */
    private static boolean looksDocumentSpecific(String error) {
        return error != null
                && (error.contains("IsSuccess=false")
                || error.contains("InvalidPdfException")
                || error.contains("Rebuild failed"));
    }

    /** Гигиена карты: раз в 10 мин выбрасывать счётчики, «тихие» более часа. */
    @Scheduled(fixedDelayString = "${app.poison-evict-ms:600000}")
    void evictStaleTransientFails() {
        long cutoff = System.currentTimeMillis() - POISON_EVICT_AFTER_MS;
        transientFails.values().removeIf(f -> f.lastAt < cutoff);
    }

    /** Счётчик подряд идущих транзитных сбоев по задаче. */
    private static final class TransientFail {
        int count = 1;              // +++ подряд ДОКУМЕНТ-специфичных (poison)
        int anyCount = 1;           // ### подряд транзитных ЛЮБОГО рода (зомби-кап)
        volatile long lastAt = System.currentTimeMillis();

        TransientFail(boolean docSpecific, boolean outage) {                             // ###
            this.count = docSpecific ? 1 : 0;                                            // ###
            this.anyCount = outage ? 0 : 1;                                              // ###
        }                                                                                 // ###
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

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN) + "…";
    }
}