package kvo.convertxml.processing;

import kvo.convertxml.infra.DocumentTaskDao;
import kvo.convertxml.infra.DocumentTaskDao.TaskHeader;
import kvo.convertxml.infra.InstanceId;
import kvo.convertxml.parser.ParserDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    /** Ограничение текста ошибки для записи в БД — защита колонки error от переполнения. */
    private static final int MAX_ERROR_LEN = 500;

    /**
     * ВРЕМЕННЫЙ обход: известные медленные семейства файлов уводим в тяжёлый пул
     * по имени, не дожидаясь измерения на лёгком воркере. Удалить после лечения
     * причины медленного парсинга этих PDF.
     */

    private final DocumentTaskDao dao;
    private final ParserDispatcher parser;
    private final InstanceId instanceId;
    private final ThreadPoolTaskExecutor heavyWorkers;
    private final long heavyThresholdBytes;

    public DocumentProcessingService(DocumentTaskDao dao,
                                     ParserDispatcher parser,
                                     InstanceId instanceId,
                                     @Qualifier("docHeavyWorkers") ThreadPoolTaskExecutor heavyWorkers,
                                     @Value("${app.heavy-threshold-mb:10}") int heavyThresholdMb) {
        this.dao = dao;
        this.parser = parser;
        this.instanceId = instanceId;
        this.heavyWorkers = heavyWorkers;
        this.heavyThresholdBytes = heavyThresholdMb * 1024L * 1024L;
    }

    /**
     * Заголовок (id, file_id, file_name, sizeBytes) приходит из поллера сразу
     * после захвата — отдельная проверка владения не нужна: авторитетный fencing
     * остаётся в markDone/markFailed (WHERE locked_by = ?).
     */
    public void process(TaskHeader row) {
        String me = instanceId.get();
        if (row.sizeBytes() >= heavyThresholdBytes) {
            log.info("Задача {} ({}): {} КБ — тяжёлый пул",
                    row.id(), row.fileName(), row.sizeBytes() / 1024);
            try {
                heavyWorkers.execute(() -> execute(row, me));
            } catch (TaskRejectedException e) {
                log.error("Тяжёлый пул переполнен, задача {} отклонена", row.id());
                safeMarkFailed(row.id(), me, "heavy pool queue is full");
            }
        } else {
            execute(row, me);
        }
    }

private void execute(TaskHeader row, String me) {
    long t0 = System.nanoTime();
    Path xmlFile = null;
    try {
        long t = System.nanoTime();
        byte[] source = dao.loadBinary(row.fileId());
        long loadMs = elapsed(t);
        t = System.nanoTime();
        xmlFile = parser.parseToXml(row.fileName(), source);
        source = null;                       // отпустить 282 МБ до записи в БД
        boolean saved = dao.markDone(row.id(), me, xmlFile);
        long parseMs = elapsed(t);           // теперь включает marshal в файл + запись в БД
        if (saved) {
            log.info("Задача {} завершена: load={} мс, parse={} мс, итог {} мс",
                    row.id(), loadMs, parseMs, elapsed(t0));
            if (parseMs > 10_000) {
                log.warn("Задача {}: медленный parse {} мс: {}",
                        row.id(), parseMs, row.fileName());
            }
        } else {
            log.warn("Задача {} перехвачена другим инстансом, результат отброшен", row.id());
        }
    } catch (Exception e) {
        log.error("Ошибка обработки задачи {} за {} мс", row.id(), elapsed(t0), e);
        safeMarkFailed(row.id(), me, e);
    } finally {
        if (xmlFile != null) {
            try {
                Files.deleteIfExists(xmlFile);
            } catch (IOException e) {
                log.warn("Не удалось удалить временный файл {}", xmlFile, e);
            }
        }
    }
}

    private static long elapsed(long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000;
    }

    /**
     * Записывает FAILED, но сам не падает: если БД недоступна,
     * задача останется в прежнем статусе и будет перехвачена по stale-таймауту —
     * это штатный механизм восстановления.
     */
    private void safeMarkFailed(long taskId, String me, Exception e) {
        safeMarkFailed(taskId, me, e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    private void safeMarkFailed(long taskId, String me, String error) {
        try {
            dao.markFailed(taskId, me, truncate(error));
        } catch (Exception markError) {
            log.error("Не удалось записать FAILED для задачи {} — останется для перехвата "
                    + "по stale-таймауту. Причина: {}", taskId, markError.getMessage());
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= MAX_ERROR_LEN ? s : s.substring(0, MAX_ERROR_LEN);
    }
}