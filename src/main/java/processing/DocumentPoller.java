package processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import infra.DocumentTaskDao;
import infra.InstanceId;
import java.util.List;

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
    public DocumentPoller(DocumentTaskDao dao,
                          DocumentProcessingService service,
                          InstanceId instanceId,
                          @Qualifier("docWorkers") ThreadPoolTaskExecutor docWorkers,
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
    }
    /** Ингест: перенос новых файлов из таблиц Directum в очередь (только чтение источников). */
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
        try {
            // Захватываем не больше, чем можем обработать прямо сейчас
            int free = workers.getThreadPoolExecutor().getMaximumPoolSize()
                    - workers.getThreadPoolExecutor().getActiveCount();
            if (free <= 0) {
                return; // все потоки заняты — новые задачи не берём
            }
            List<Long> ids = dao.claimBatch(instanceId.get(),
                    Math.min(batchSize, free),
                    staleTimeoutSec, maxRetries, retryDelaySec);
            if (!ids.isEmpty()) {
                log.info("[{}] захвачено задач: {}", instanceId.get(), ids);
                ids.forEach(id -> workers.execute(() -> service.process(id)));
            }
        } catch (Exception e) {
            log.error("Ошибка опроса очереди", e);
        }
    }
}