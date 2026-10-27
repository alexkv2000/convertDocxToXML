package kvo.convertxml.processing;

import kvo.convertxml.client.ImanDocumentEnricher;
import kvo.convertxml.client.LlmAvailability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import kvo.convertxml.infra.DocumentTaskDao;
import kvo.convertxml.infra.DocumentTaskDao.TaskHeader;
import kvo.convertxml.infra.InstanceId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Component
public class DocumentPoller {

    private static final Logger log = LoggerFactory.getLogger(DocumentPoller.class);

    private final DocumentTaskDao dao;
    private final DocumentProcessingService service;
    private final LlmAvailability llm;
    private final ImanDocumentEnricher enricher;
    private final InstanceId instanceId;
    private final ThreadPoolTaskExecutor workers;
    private final int batchSize;
    private final int staleTimeoutSec;
    private final int maxRetries;
    private final int retryDelaySec;
    @Value("${app.ingest-lookback-days:14}")
    int ingestLookbackDays;
    /**
     * Слоты задач "в полёте", размер = числу потоков лёгкого пула.
     * Гарантирует: задач в работе не больше, чем потоков; workers.execute()
     * не уходит в CallerRuns в поток шедулера; соединений нужно не больше, чем слотов.
     */
    private final Semaphore inFlight;

    public DocumentPoller(DocumentTaskDao dao,
                          DocumentProcessingService service,
                          InstanceId instanceId,
                          @Qualifier("docWorkers") ThreadPoolTaskExecutor docWorkers,
                          @Value("${app.batch-size:5}") int batchSize,
                          @Value("${app.stale-timeout-sec:900}") int staleTimeoutSec,
                          @Value("${app.max-retries:3}") int maxRetries,
                          @Value("${app.retry-delay-sec:60}") int retryDelaySec,
                          LlmAvailability llm,
                          ImanDocumentEnricher enricher) {
        this.dao = dao;
        this.service = service;
        this.instanceId = instanceId;
        this.workers = docWorkers;
        this.batchSize = batchSize;
        this.staleTimeoutSec = staleTimeoutSec;
        this.maxRetries = maxRetries;
        this.retryDelaySec = retryDelaySec;
        // Размер семафора — из фактического максимума пула, а не из дублирующей
        // проперти app.exec.light.core (дефолт 4 при пуле на 25 → 21 поток простаивал).
        this.inFlight = new Semaphore(Math.max(1, docWorkers.getMaxPoolSize()));
        this.llm = llm;
        this.enricher = enricher;
    }

    /** Ингест: перенос новых файлов из таблиц DocsVision в очередь (только чтение источников). */
    @Scheduled(fixedDelayString = "${app.ingest-interval-ms:30000}")
    public void ingest() {
        try {
            long t0 = System.currentTimeMillis();
            int added = dao.ingestNewTasks(ingestLookbackDays);
            long ms = System.currentTimeMillis() - t0;
            if (added > 0) {
                log.info("[{}] новых задач в очереди: {} за {} мс", instanceId.get(), added, ms);
            } else {
                log.debug("[{}] ингест: 0 новых за {} мс", instanceId.get(), ms);
            }
        } catch (DuplicateKeyException e) {
            // гонка ингеста между инстансами: строки уже вставил другой — не ошибка
            log.debug("[{}] ингест: файлы уже добавлены другим инстансом", instanceId.get());
        } catch (Exception e) {
            log.error("Ошибка загрузки новых задач", e);
        }
    }

    /**
     * Оркестратор опроса: только порядок фаз, вся логика — в методах ниже.
     * Guard-клаузы вместо вложенных веток.
     */
    @Scheduled(fixedDelayString = "${app.poll-interval-ms:5000}")
    public void poll() {
        if (llm.isPaused()) {
            probeLlm();
            return;
        }
        int available = inFlight.availablePermits();
        if (available <= 0) {
            return;                              // все воркеры заняты
        }
        List<DocumentTaskDao.Claim> claims = claimUpTo(available);
        if (claims.isEmpty()) {
            return;                              // ошибка БД (залогирована) или брать нечего
        }
        Map<Long, UUID> tokens = tokensOf(claims);
        List<TaskHeader> headers;
        try {
            headers = dao.headersFor(idsOf(claims));
        } catch (Exception e) {
            // Чтение упало: вся пачка — в очередь с паузой, terminator-ветка
            // (failMissing) не должна выполняться для транзитного сбоя БД
            log.error("Ошибка чтения заголовков для {} задач — возвращаем в очередь",
                    claims.size(), e);
            claims.forEach(c -> requeueQuietly(c.id(), c.token(), 60));
            return;
        }
        markMissingSources(claims, headers);     // пустой headers = «источники удалены», это ок
        log.info("[{}] захвачено задач: {}", instanceId.get(), headers.size());
        dispatch(headers, tokens);
    }

    private void probeLlm() {
        if (!llm.shouldProbe()) {
            return;                              // не чаще раза в минуту
        }
        try {
            enricher.ping();                     // таймаут 10 с, не 180
            llm.success();                       // внутри отлогируется «возобновляем»
        } catch (Exception e) {
            log.warn("LLM не отвечает на пробу ({}), продолжаем ждать", e.getMessage());
            llm.failure();                       // продлевает паузу
        }
    }

    /** Захват пачки; при ошибке БД возвращает пустой список (ошибка уже залогирована). */
    private List<DocumentTaskDao.Claim> claimUpTo(int available) {
        try {
            return dao.claimBatch(instanceId.get(), Math.min(batchSize, available),
                    staleTimeoutSec, maxRetries, retryDelaySec);
        } catch (Exception e) {
            log.error("Ошибка захвата задач", e);
            return List.of();
        }
    }

    /** Захвачено больше, чем найдено заголовков — исходники удалены из DocsVision. */
    private void markMissingSources(List<DocumentTaskDao.Claim> claims, List<TaskHeader> headers) {
        if (headers.size() == claims.size()) {
            return;
        }
        Set<Long> found = headers.stream().map(TaskHeader::id).collect(Collectors.toSet());
        List<DocumentTaskDao.Claim> missing = claims.stream()
                .filter(c -> !found.contains(c.id())).toList();
        dao.failMissing(instanceId.get(), missing);
        log.warn("Захвачено {}, заголовков {} — {} задач без исходника помечены (status=3)",
                claims.size(), headers.size(), missing.size());
    }

    /**
     * Постановка в пул по одной задаче на слот семафора.
     * Прерывание потока шедулера: текущая задача и весь хвост пачки возвращаются
     * в очередь (раньше хвост числился status=1 и ждал stale-таймаут).
     */
    private void dispatch(List<TaskHeader> headers, Map<Long, UUID> tokens) {
        for (int i = 0; i < headers.size(); i++) {
            TaskHeader header = headers.get(i);
            UUID token = tokens.get(header.id());
            if (!acquireSlot()) {
                requeueQuietly(header.id(), token, 10);
                requeueTail(headers, tokens, i + 1);
                return;
            }
            submit(header, token);
        }
    }

    /** false — ожидание слота прервано (флаг interrupt восстановлен). */
    private boolean acquireSlot() {
        try {
            inFlight.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Задача в пул; отказ пула не смертелен — задача возвращается в очередь. */
    private void submit(TaskHeader header, UUID token) {
        try {
            workers.execute(() -> {
                try {
                    service.process(header, token);
                } finally {
                    inFlight.release();
                }
            });
        } catch (RuntimeException e) {
            inFlight.release();
            requeueQuietly(header.id(), token, 10);
            log.error("Задача {} не поставлена в пул — возвращена в очередь", header.id(), e);
        }
    }

    /** Хвост незадиспетченной пачки — в очередь с короткой паузой. */
    private void requeueTail(List<TaskHeader> headers, Map<Long, UUID> tokens, int from) {
        for (TaskHeader h : headers.subList(from, headers.size())) {
            requeueQuietly(h.id(), tokens.get(h.id()), 10);
        }
    }

    private void requeueQuietly(long id, UUID token, int delaySec) {
        try {
            dao.markTransient(id, instanceId.get(), token, delaySec);
        } catch (Exception e) {
            log.error("Не удалось вернуть задачу {} в очередь — подберёт stale-таймаут", id, e);
        }
    }

    private static List<Long> idsOf(List<DocumentTaskDao.Claim> claims) {
        return claims.stream().map(DocumentTaskDao.Claim::id).toList();
    }

    private static Map<Long, UUID> tokensOf(List<DocumentTaskDao.Claim> claims) {
        return claims.stream()
                .collect(Collectors.toMap(DocumentTaskDao.Claim::id, DocumentTaskDao.Claim::token));
    }
}