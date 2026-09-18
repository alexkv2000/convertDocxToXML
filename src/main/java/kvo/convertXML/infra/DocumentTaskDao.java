package kvo.convertXML.infra;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

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

    private static final String SQL_HEADER_BY_OWNER = """
        SELECT d.id, d.file_id, d.file_name
        FROM dbo.doc_documents d
        WHERE d.id = ? AND d.locked_by = ?
        """;

    private static final String SQL_BINARY_SIZE = """
        SELECT ISNULL(DATALENGTH(b.Data), 0)
        FROM dbo.dvsys_files f WITH (NOLOCK)
             INNER JOIN dbo.dvsys_binaries b WITH (NOLOCK) ON b.ID = f.BinaryID
        WHERE f.FileID = ?
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

    public record TaskHeader(long id, String fileId, String fileName) {}

    /** Заголовок задачи без бинарника. WHERE locked_by — проверка владения. */
    public Optional<TaskHeader> findHeaderByIdAndOwner(long id, String instanceId) {
        List<TaskHeader> rows = jdbc.query(SQL_HEADER_BY_OWNER,
                (rs, i) -> new TaskHeader(rs.getLong("id"),
                        rs.getString("file_id"),
                        rs.getString("file_name")),
                id, instanceId);
        return rows.stream().findFirst();
    }

    /** Размер файла БЕЗ загрузки данных — для маршрутизации лёгкий/тяжёлый пул. */
    public long binarySize(String fileId) {
        Long size = jdbc.queryForObject(SQL_BINARY_SIZE, Long.class, fileId);
        return size == null ? 0 : size;
    }

    /** Загрузка бинарника напрямую из действующих таблиц (только чтение). */
    public byte[] loadBinary(String fileId) {
        return jdbc.queryForObject(SQL_LOAD_BINARY, byte[].class, fileId);
    }

    /**
     * Фиксация результата в ОДНОЙ транзакции: статус DONE в очереди +
     * строка в doc_results. WHERE locked_by = ? — fencing.
     */
    public boolean markDone(long id, String instanceId, byte[] xml) {
        Boolean ok = tx.execute(status -> {
            int updated = jdbc.update(SQL_DONE_RELEASE, id, instanceId);
            if (updated == 0) {
                return false; // владение потеряно — результат отбрасываем
            }
            jdbc.update(SQL_DONE_INSERT_RESULT, xml, instanceId, id);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    /** Ошибка: счётчик попыток +1, повтор после retry-delay. */
    public boolean markFailed(long id, String instanceId, String error) {
        String msg = error.length() > 2000 ? error.substring(0, 2000) : error;
        return jdbc.update(SQL_MARK_FAILED, msg, id, instanceId) > 0;
    }
}