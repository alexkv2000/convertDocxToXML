package kvo.convertxml.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Фоновое обновление gauge-метрик очереди doc_documents:
 * глубина по статусам + возраст самого старого ЗАБИРАЕМОГО задания.
 * NOLOCK + отдельный шедулер-поток — мониторинг не мешает конвейеру.
 */
@Component
public class QueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(QueueMetrics.class);

    private static final String SQL_DEPTH = """
            SELECT status, COUNT(*) FROM dbo.doc_documents WITH (NOLOCK) GROUP BY status
            """;

    /**
     * Возраст самого старого задания, которое claimBatch ЕЩЁ МОЖЕТ забрать:
     * новые (status=0) и error с неисчерпанными попытками (retry_count < max-retries).
     * Терминальные error (retry_count=9999 у rejected/empty и исчерпавшие ретраи)
     * исключены: claimBatch их никогда не возьмёт, а раньше они старели в метрике
     * бесконечно (наблюдалось 40+ суток при new=0) и вечно держали алерт зажжённым.
     */
    private static final String SQL_OLDEST_PENDING = """
            SELECT ISNULL(DATEDIFF(SECOND, MIN(created_at), SYSUTCDATETIME()), 0)
            FROM dbo.doc_documents WITH (NOLOCK)
            WHERE status = 0
               OR (status = 3 AND retry_count < ?)
            """;

    private final JdbcTemplate jdbc;
    private final ServiceMetrics metrics;
    private final int maxRetries;

    public QueueMetrics(JdbcTemplate jdbc, ServiceMetrics metrics,
                        @Value("${app.max-retries:3}") int maxRetries) {
        this.jdbc = jdbc;
        this.metrics = metrics;
        this.maxRetries = maxRetries;
    }

    @Scheduled(fixedDelayString = "${app.metrics-queue-interval-ms:15000}")
    public void refresh() {
        try {
            Map<Integer, Long> depth = new HashMap<>();
            jdbc.query(SQL_DEPTH, (RowCallbackHandler) rs -> depth.put(rs.getInt("status"), rs.getLong(2)));
            for (int status = 0; status <= 3; status++) {
                metrics.queueDepth(status, depth.getOrDefault(status, 0L));
            }
            Long oldest = jdbc.queryForObject(SQL_OLDEST_PENDING, Long.class, maxRetries);
            metrics.queueOldestPendingSec(oldest == null ? 0 : oldest);
        } catch (Exception e) {
            log.debug("Метрики очереди не обновлены: {}", e.getMessage());
        }
    }
}