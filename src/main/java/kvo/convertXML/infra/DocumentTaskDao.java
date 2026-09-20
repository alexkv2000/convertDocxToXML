package kvo.convertXML.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

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
          AND files.Name NOT LIKE '%Печатная форма%'
          AND files.Name NOT LIKE '%Архив передачи%'
          -- если нужно парсить только docx, раскомментируйте:
          -- AND files.Name LIKE '%.docx'
          AND NOT EXISTS (SELECT 1
                          FROM dbo.doc_documents d
                          WHERE d.file_id = files.FileID)
          AND (
              CHARINDEX('.', REVERSE(files.Name)) > 0\s
              AND RIGHT(files.Name, CHARINDEX('.', REVERSE(files.Name)) - 1) IN ('docx', 'Docx', 'DOCX', 'pdf')
          )
        """;

    private static final String SQL_CLAIM_BATCH = """
        UPDATE TOP (?) d
           SET d.status    = 1,
               d.locked_by = ?,
               d.locked_at = SYSUTCDATETIME()
        OUTPUT INSERTED.id
        FROM dbo.doc_documents AS d WITH (UPDLOCK, ROWLOCK, READPAST)
        WHERE d.status = 0
           OR (d.status = 1 AND d.locked_at IS NOT NULL
               AND d.locked_at < DATEADD(SECOND, -?, SYSUTCDATETIME()))
           OR (d.status = 3 AND d.retry_count < ?
               AND d.updated_at IS NOT NULL
               AND d.updated_at < DATEADD(SECOND, -?, SYSUTCDATETIME()))
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
               locked_by = NULL, locked_at = NULL, updated_at = SYSUTCDATETIME()
         WHERE id = ? AND locked_by = ?
        """;

    private static final String SQL_DONE_INSERT_RESULT = """
        INSERT INTO dbo.doc_results (doc_id, file_id, file_name, xml_data, created_by)
        SELECT id, file_id, file_name, ?, ?
        FROM dbo.doc_documents
        WHERE id = ?
        """;

    private static final String SQL_MARK_FAILED = """
        UPDATE dbo.doc_documents
           SET status = 3, error_message = ?,
               retry_count = retry_count + 1,
               locked_by = NULL, locked_at = NULL, updated_at = SYSUTCDATETIME()
         WHERE id = ? AND locked_by = ?
        """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DocumentTaskDao(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Ингест: добавление новых задач из действующих таблиц DocsVision.
     * Идемпотентно: NOT EXISTS + уникальный индекс по file_id.
     */
    public int ingestNewTasks() {
        return jdbc.update(SQL_INGEST);
    }

    /**
     * Атомарный захват пачки задач (UPDLOCK+ROWLOCK+READPAST).
     * Забираются новые, зависшие PROCESSING и ERROR с правом повтора.
     */
    public List<Long> claimBatch(String instanceId, int batchSize,
                                 int staleTimeoutSec, int maxRetries, int retryDelaySec) {
        return jdbc.queryForList(SQL_CLAIM_BATCH, Long.class,
                batchSize, instanceId, staleTimeoutSec, maxRetries, retryDelaySec);
    }

    public record TaskHeader(long id, String fileId, String fileName, long sizeBytes) {}

    /** Заголовки + размеры бинарников захваченных задач ОДНИМ запросом на партию. */
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

    /** Загрузка бинарника напрямую из действующих таблиц (только чтение). */
    public byte[] loadBinary(String fileId) {
        return jdbc.queryForObject(SQL_LOAD_BINARY, byte[].class, fileId);
    }

    /**
     * Фиксация результата в ОДНОЙ транзакции: статус DONE в очереди +
     * строка в doc_results. WHERE locked_by = ? — fencing.
     */
    public boolean markDone(long id, String instanceId, Path xmlFile) throws IOException {
        long xmlSize = Files.size(xmlFile);
        Boolean ok = tx.execute(status -> {
            int updated = jdbc.update(SQL_DONE_RELEASE, id, instanceId);
            if (updated == 0) {
                return false; // владение потеряно — результат отбрасываем
            }
            jdbc.update(SQL_DONE_INSERT_RESULT, ps -> {
                try {
                    ps.setBinaryStream(1, Files.newInputStream(xmlFile), xmlSize);
                } catch (IOException e) {
                    throw new SQLException("Не удалось открыть xml-файл результата", e);
                }
                ps.setString(2, instanceId);
                ps.setLong(3, id);
            });
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** Ошибка: счётчик попыток +1, повтор после retry-delay. */
    public boolean markFailed(long id, String instanceId, String error) {
        String msg = error.length() > 2000 ? error.substring(0, 2000) : error;
        return jdbc.update(SQL_MARK_FAILED, msg, id, instanceId) > 0;
    }
    private static final String SQL_FAIL_MISSING = """
    UPDATE dbo.doc_documents
       SET status = 3,
           error_message = N'Исходный файл недоступен (удалён из DocsVision)',
           retry_count = retry_count + 1,
           locked_by = NULL,
           locked_at = NULL,
           updated_at = SYSUTCDATETIME()
     WHERE id = ? AND locked_by = ?
    """;
    public void failMissing(String instanceId, List<Long> ids) {
        if (ids.isEmpty()) return;
        jdbc.batchUpdate(SQL_FAIL_MISSING,
                ids.stream().map(id -> new Object[]{id, instanceId}).toList());
    }
}