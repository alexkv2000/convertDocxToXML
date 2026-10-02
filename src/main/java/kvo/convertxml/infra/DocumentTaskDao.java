package kvo.convertxml.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class DocumentTaskDao {

    private static final String SQL_INGEST = """
                INSERT INTO dbo.doc_documents (file_id, file_name)
                SELECT files.FileID, files.Name
                FROM dbo.[dvtable_{315cf3c8-1ee1-4f25-b843-e867345adb09}] link_cards WITH (NOLOCK)
                     INNER JOIN dbo.[dvtable_{30eb9b87-822b-4753-9a50-a1825dca1b74}] card_main_info WITH (NOLOCK)
                          ON card_main_info.InstanceID = link_cards.DiadocCard
                     INNER JOIN dbo.[dvtable_{91b2c5f7-9324-4cef-9afe-a457c8310f06}] doc_sys WITH (NOLOCK)
                          ON doc_sys.InstanceID = link_cards.DiadocCard
                     INNER JOIN dbo.[dvtable_{a6fa8baf-2ea4-4071-aa3e-5c4e71646a90}] doc_files WITH (NOLOCK)
                          ON doc_files.InstanceID = card_main_info.InstanceID
                     INNER JOIN dbo.[dvtable_{f831372e-8a76-4abc-af15-d86dc5ffbe12}] v_files WITH (NOLOCK)
                          ON v_files.InstanceID = doc_files.FileId
                     INNER JOIN dbo.dvsys_files files WITH (NOLOCK)
                          ON files.FileID = v_files.FileID
                     WHERE doc_sys.Kind = 'FE65DE1F-14C7-4857-AC05-F9C3CC8D90C8'
                          AND card_main_info.CourtCaseUploaded = 0
                          AND card_main_info.CreationDate >= DATEADD(DAY, -?, GETDATE())
                          AND files.Name NOT LIKE '%Печатная форма%'
                          AND files.Name NOT LIKE '%Архив передачи%'
                          AND NOT EXISTS (SELECT 1
                                FROM dbo.doc_documents d
                                WHERE d.file_id = files.FileID
                                  AND d.created_at >= DATEADD(DAY, -?, SYSUTCDATETIME()))
                          AND (
                             CHARINDEX('.', REVERSE(files.Name)) > 0
                             AND LOWER(RIGHT(files.Name, CHARINDEX('.', REVERSE(files.Name)) - 1)) IN ('doc', 'docx', 'pdf')
                          )
            """;

    private static final String SQL_CLAIM_BATCH = """
            WITH picked AS (
                SELECT TOP (?) id
                FROM dbo.doc_documents WITH (UPDLOCK, ROWLOCK, READPAST)
                WHERE (status = 0 AND (next_attempt_at IS NULL OR next_attempt_at <= SYSUTCDATETIME()))
                   OR (status = 1 AND locked_at IS NOT NULL
                           AND locked_at < DATEADD(SECOND, -?, SYSUTCDATETIME()))
                   OR (status = 3 AND retry_count < ?
                           AND updated_at IS NOT NULL
                           AND updated_at < DATEADD(SECOND, -?, SYSUTCDATETIME()))
                ORDER BY CASE WHEN status = 1 THEN 0 WHEN status = 0 THEN 1 ELSE 2 END, id
            )
            UPDATE d
               SET d.status = 1, d.locked_by = ?, d.locked_at = SYSUTCDATETIME(),
                   d.claim_token = ?
            OUTPUT INSERTED.id, INSERTED.claim_token
            FROM dbo.doc_documents AS d
            JOIN picked ON picked.id = d.id
            """;

    private static final String SQL_HEADERS_BATCH = """
            SELECT d.id, d.file_id, d.file_name,
                   ISNULL(DATALENGTH(b.Data), 0) AS size_bytes
            FROM dbo.doc_documents d WITH (NOLOCK)
                 INNER JOIN dbo.dvsys_files f WITH (NOLOCK) ON f.FileID = d.file_id
                 INNER JOIN dbo.dvsys_binaries b WITH (NOLOCK) ON b.ID = f.BinaryID
            WHERE d.id IN (%s)
            """;

    private static final String SQL_LOAD_BINARY = """
            SELECT b.Data
            FROM dbo.dvsys_files f WITH (NOLOCK)
                 INNER JOIN dbo.dvsys_binaries b WITH (NOLOCK) ON b.ID = f.BinaryID
            WHERE f.FileID = ?
            """;

    private static final String SQL_DONE_RELEASE = """
            UPDATE dbo.doc_documents
               SET status = 2, error_message = NULL,
                   locked_by = NULL, locked_at = NULL, claim_token = NULL,
                   updated_at = SYSUTCDATETIME()
             WHERE id = ? AND locked_by = ? AND claim_token = ?
            """;

    private static final String SQL_DONE_INSERT_RESULT = """
            INSERT INTO dbo.doc_results (doc_id, file_id, file_name, xml_data, created_by)
            SELECT id, file_id, file_name, ?, ?
            FROM dbo.doc_documents
            WHERE id = ?
            """;
    private static final String SQL_DONE_DELETE_RESULT = """ 
            DELETE FROM dbo.doc_results WHERE doc_id = ?
            """;
    private static final String SQL_MARK_FAILED = """
            UPDATE dbo.doc_documents
               SET status = 3, error_message = ?,
                   retry_count = retry_count + 1,
                   locked_by = NULL, locked_at = NULL, claim_token = NULL, updated_at = SYSUTCDATETIME()
             WHERE id = ? AND locked_by = ? AND claim_token = ?
            """;
    private static final String SQL_EMPTY_RESULT = """
            UPDATE dbo.doc_documents
               SET status = 3,
                   error_message = N'Извлечённый текст пуст',
                   retry_count = retry_count + 1,   -- иначе claimBatch перезахватит по retry-ветке
                   locked_by = NULL,
                   locked_at = NULL,
                   claim_token = NULL,
                   updated_at = SYSUTCDATETIME()    -- пауза retry-delay перед повтором
             WHERE id = ? AND locked_by = ? AND claim_token = ?
            """;
    private static final String SQL_MARK_TRANSIENT = """
            UPDATE dbo.doc_documents
               SET status = 0,
                   next_attempt_at = DATEADD(SECOND, ?, SYSUTCDATETIME()),
                   locked_by = NULL, locked_at = NULL, claim_token = NULL,
                   updated_at = SYSUTCDATETIME()
             WHERE id = ? AND locked_by = ? AND claim_token = ?
            """;
    private static final String SQL_HEARTBEAT = """
            UPDATE d SET d.locked_at = SYSUTCDATETIME(), d.updated_at = SYSUTCDATETIME()
            FROM dbo.doc_documents AS d
            JOIN (VALUES %s) AS v(id, tok) ON v.id = d.id AND v.tok = d.claim_token
            WHERE d.locked_by = ? AND d.status = 1
            """;
    private static final String SQL_MARK_REJECTED = """
            UPDATE dbo.doc_documents
               SET status = 3, error_message = ?,
                   retry_count = 9999,               -- выше любого max-retries: повторов не будет
                   locked_by = NULL, locked_at = NULL, claim_token = NULL,
                   updated_at = SYSUTCDATETIME()
             WHERE id = ? AND locked_by = ? AND claim_token = ?
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    /** Неустранимая ошибка: ERROR навсегда, claimBatch больше не возьмёт эту задачу. */
    public boolean markRejected(long id, String instanceId, UUID token, String error) {
        return jdbc.update(SQL_MARK_REJECTED, error, id, instanceId, token.toString()) > 0;
    }

    public DocumentTaskDao(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public record Claim(long id, UUID token) {
    }

    /**
     * Ингест: добавление новых задач из действующих таблиц DocsVision.
     * Идемпотентно: NOT EXISTS + уникальный индекс по file_id.
     */
    public int ingestNewTasks(int lookbackDays) {
        return jdbc.update(SQL_INGEST, lookbackDays, lookbackDays);
    }

    /**
     * Атомарный захват пачки задач (UPDLOCK+ROWLOCK+READPAST).
     * Забираются новые, зависшие PROCESSING и ERROR с правом повтора.
     */
    public List<Claim> claimBatch(String instanceId, int batchSize,
                                  int staleTimeoutSec, int maxRetries, int retryDelaySec) {
        String token = UUID.randomUUID().toString();
        return jdbc.query(SQL_CLAIM_BATCH,
                (rs, i) -> new Claim(rs.getLong("id"), UUID.fromString(rs.getString("claim_token"))),
                batchSize, staleTimeoutSec, maxRetries, retryDelaySec,
                instanceId, token);
    }

    /**
     * Транзитный сбой: в очередь с паузой, БЕЗ роста retry_count.
     */
    public boolean markTransient(long id, String instanceId, UUID token, int delaySec) {
        return jdbc.update(SQL_MARK_TRANSIENT, delaySec, id, instanceId, token.toString()) > 0;
    }

    /**
     * Пустой результат: ERROR без записи в doc_results.
     */
    public boolean markEmptyResult(long id, String instanceId, UUID token) {
        return jdbc.update(SQL_EMPTY_RESULT, id, instanceId, token.toString()) > 0;
    }

    public boolean markFailed(long id, String instanceId, UUID token, String error) {
        String msg = error.length() > 2000 ? error.substring(0, 2000) : error;
        return jdbc.update(SQL_MARK_FAILED, msg, id, instanceId, token.toString()) > 0;
    }

    public void heartbeat(String instanceId, Map<Long, UUID> claims) {
        List<Map.Entry<Long, UUID>> all = List.copyOf(claims.entrySet());
        for (int from = 0; from < all.size(); from += 200) {
            var part = all.subList(from, Math.min(from + 200, all.size()));
            String vals = part.stream().map(e -> "(?,?)").collect(Collectors.joining(","));
            Object[] args = new Object[part.size() * 2 + 1];
            int i = 0;
            for (var e : part) {
                args[i++] = e.getKey();
                args[i++] = e.getValue().toString();
            }
            args[i] = instanceId;
            jdbc.update(String.format(SQL_HEARTBEAT, vals), args);
        }
    }

    public record TaskHeader(long id, String fileId, String fileName, long sizeBytes) {
    }

    /**
     * Заголовки + размеры бинарников захваченных задач ОДНИМ запросом на партию.
     */
    public List<TaskHeader> headersFor(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        return jdbc.query(String.format(SQL_HEADERS_BATCH, placeholders),
                (rs, i) -> new TaskHeader(rs.getLong("id"),
                        rs.getString("file_id"),
                        rs.getString("file_name"),
                        rs.getLong("size_bytes")),
                ids.toArray());
    }

    /**
     * Загрузка бинарника напрямую из действующих таблиц (только чтение).
     */
    public byte[] loadBinary(String fileId) {
        return jdbc.queryForObject(SQL_LOAD_BINARY, byte[].class, fileId);
    }

    /**
     * Фиксация результата в ОДНОЙ транзакции: статус DONE в очереди +
     * строка в doc_results. WHERE locked_by = ? — fencing.
     */
    public boolean markDone(long id, String instanceId, UUID token, String text) {
        Boolean ok = tx.execute(status -> {
            int updated = jdbc.update(SQL_DONE_RELEASE, id, instanceId, token.toString());
            if (updated == 0) {
                return false;                          // владение потеряно — результат отбрасываем
            }
            jdbc.update(SQL_DONE_DELETE_RESULT, id);
            jdbc.update(SQL_DONE_INSERT_RESULT, text, instanceId, id);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    private static final String SQL_FAIL_MISSING = """
            UPDATE dbo.doc_documents
               SET status = 3,
                   error_message = N'Исходный файл недоступен (удалён из DocsVision)',
                   retry_count = retry_count + 1,
                   locked_by = NULL, locked_at = NULL, claim_token = NULL,
                   updated_at = SYSUTCDATETIME()
             WHERE id = ? AND locked_by = ? AND claim_token = ?
            """;

    public void failMissing(String instanceId, List<Claim> ids) {
        if (ids.isEmpty()) return;
        jdbc.batchUpdate(SQL_FAIL_MISSING,
                ids.stream().map(c -> new Object[]{c.id(), instanceId, c.token().toString()}).toList());
    }
}