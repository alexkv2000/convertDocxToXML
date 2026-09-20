package kvo.convertXML.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import kvo.convertXML.infra.DocumentTaskDao;
import kvo.convertXML.infra.DocumentTaskDao.TaskHeader;
import kvo.convertXML.infra.InstanceId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Component
public class DocumentPoller {

    private static final Logger log = LoggerFactory.getLogger(DocumentPoller.class);

    private final DocumentTaskDao dao;
    private final DocumentProcessingService service;
    private final InstanceId instanceId;
    private final ThreadPoolTaskExecutor workers;
    private final int batchSize;
    private final int staleTimeoutSec;
    private final int maxRetries;
    private final int retryDelaySec;

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
                          @Value("${app.worker-threads:4}") int workerThreads,
                          @Value("${app.batch-size:5}") int batchSize,
                          @Value("${app.stale-timeout-sec:900}") int staleTimeoutSec,
                          @Value("${app.max-retries:3}") int maxRetries,
                          @Value("${app.retry-delay-sec:60}") int retryDelaySec) {
        this.dao = dao;
        this.service = service;
        this.instanceId = instanceId;
        this.workers = docWorkers;
        this.batchSize = batchSize;
        this.staleTimeoutSec = staleTimeoutSec;
        this.maxRetries = maxRetries;
        this.retryDelaySec = retryDelaySec;
        this.inFlight = new Semaphore(workerThreads);
    }

    /** Ингест: перенос новых файлов из таблиц DocsVision в очередь (только чтение источников). */
    @Scheduled(fixedDelayString = "${app.ingest-interval-ms:30000}")
    public void ingest() {
        try {
            int added = dao.ingestNewTasks();
            if (added > 0) {
                log.info("[{}] новых задач в очереди: {}", instanceId.get(), added);
            }
        } catch (DuplicateKeyException e) {
            // гонка ингеста между инстансами: строки уже вставил другой — не ошибка
            log.debug("[{}] ингест: файлы уже добавлены другим инстансом", instanceId.get());
        } catch (Exception e) {
            log.error("Ошибка загрузки новых задач", e);
        }
    }

    @Scheduled(fixedDelayString = "${app.poll-interval-ms:5000}")
    public void poll() {
        int available = inFlight.availablePermits();
        if (available <= 0) {
            return; // все слоты заняты — новые задачи не берём
        }
        List<Long> ids;
        try {
            ids = dao.claimBatch(instanceId.get(), Math.min(batchSize, available),
                    staleTimeoutSec, maxRetries, retryDelaySec);
        } catch (Exception e) {
            log.error("Ошибка захвата задач", e);
            return;
        }
        if (ids.isEmpty()) {
            return;
        }
        // Заголовки + размеры бинарников одним запросом на всю партию —
        // вместо отдельных запросов на каждую задачу
        List<TaskHeader> headers;
        try {
            headers = dao.headersFor(ids);
        } catch (Exception e) {
            log.error("Ошибка чтения заголовков для {} задач — stale-таймаут подберёт", ids.size(), e);
            return;
        }
        if (headers.size() != ids.size()) {
            Set<Long> found = headers.stream().map(TaskHeader::id).collect(Collectors.toSet());
            List<Long> missing = ids.stream().filter(id -> !found.contains(id)).toList();
            dao.failMissing(instanceId.get(), missing);
            log.warn("Захвачено {}, заголовков {} — {} задач без исходника помечены ошибкой (status=3)",
                    ids.size(), headers.size(), missing);
        }
        log.info("[{}] захвачено задач: {}", instanceId.get(), headers.size());
        for (TaskHeader header : headers) {
            try {
                // acquire не заблокируется: available проверен, а acquire-ов кроме нас нет
                inFlight.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // незапущенные задачи подберёт stale-таймаут
            }
            try {
                workers.execute(() -> {
                    try {
                        service.process(header);
                    } finally {
                        inFlight.release();
                    }
                });
            } catch (RuntimeException e) {
                inFlight.release();
                log.error("Задача {} не поставлена в пул — повтор по stale-таймауту", header.id(), e);
            }
        }
    }
}