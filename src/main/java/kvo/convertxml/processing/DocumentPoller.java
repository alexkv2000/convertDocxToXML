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
                          @Value("${app.exec.light.core:4}") int workerThreads,
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
        this.inFlight = new Semaphore(workerThreads);
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
    private void probeLlm() {
        if (!llm.shouldProbe()) return;            // не чаще раза в минуту
        try {
            enricher.ping();                       // таймаут 10 с, не 180
            llm.success();                         // внутри отлогируется «возобновляем»
        } catch (Exception e) {
            log.warn("LLM не отвечает на пробу ({}), продолжаем ждать", e.getMessage());
            llm.failure();                         // продлевает паузу
        }
    }
    @Scheduled(fixedDelayString = "${app.poll-interval-ms:5000}")
    @Scheduled(fixedDelayString = "${app.poll-interval-ms:5000}")
    public void poll() {
        if (llm.isPaused()) {
            probeLlm();
            return;
        }
        int available = inFlight.availablePermits();
        if (available <= 0) {
            return;
        }
        List<DocumentTaskDao.Claim> claims;
        try {
            claims = dao.claimBatch(instanceId.get(), Math.min(batchSize, available),
                    staleTimeoutSec, maxRetries, retryDelaySec);
        } catch (Exception e) {
            log.error("Ошибка захвата задач", e);
            return;
        }
        if (claims.isEmpty()) {
            return;
        }
        Map<Long, UUID> tokens = claims.stream()
                .collect(Collectors.toMap(DocumentTaskDao.Claim::id, DocumentTaskDao.Claim::token));
        List<TaskHeader> headers;
        try {
            headers = dao.headersFor(claims.stream().map(DocumentTaskDao.Claim::id).toList());
        } catch (Exception e) {
            log.error("Ошибка чтения заголовков для {} задач — возвращаем в очередь", claims.size(), e);
            for (DocumentTaskDao.Claim c : claims) {
                requeueQuietly(c.id(), c.token(), 60);
            }
            return;
        }
        if (headers.size() != claims.size()) {
            Set<Long> found = headers.stream().map(TaskHeader::id).collect(Collectors.toSet());
            List<DocumentTaskDao.Claim> missing = claims.stream()
                    .filter(c -> !found.contains(c.id())).toList();
            dao.failMissing(instanceId.get(), missing);
            log.warn("Захвачено {}, заголовков {} — {} задач без исходника помечены (status=3)",
                    claims.size(), headers.size(), missing.size());
        }
        log.info("[{}] захвачено задач: {}", instanceId.get(), headers.size());
        for (TaskHeader header : headers) {
            UUID token = tokens.get(header.id());
            try {
                inFlight.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                requeueQuietly(header.id(), token, 10);
                return;
            }
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
    }
    private void requeueQuietly(long id, UUID token, int delaySec) {
        try {
            dao.markTransient(id, instanceId.get(), token, delaySec);
        } catch (Exception e) {
            log.error("Не удалось вернуть задачу {} в очередь — подберёт stale-таймаут", id, e);
        }
    }
}