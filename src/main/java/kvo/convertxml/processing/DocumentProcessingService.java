package kvo.convertxml.processing;

import kvo.convertxml.client.ImanDocumentEnricher;
import kvo.convertxml.config.ImanProperties;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    /** Ограничение текста ошибки для записи в БД — защита колонки error от переполнения. */
    private static final int MAX_ERROR_LEN = 500;

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
                                     @Value("${app.min-length-xml:20}") int minTextLength) {
        this.dao = dao;
        this.parser = parser;
        this.instanceId = instanceId;
        this.heavyWorkers = heavyWorkers;
        this.heavyThresholdBytes = heavyThresholdMb * 1024L * 1024L;
        this.iman = iman;
        this.imanEnabled = imanProps.enabled();
        this.minTextLength = minTextLength;
    }

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
        Path txtFile = null;
        try {
            long t = System.nanoTime();
            byte[] source = dao.loadBinary(row.fileId());
            long loadMs = elapsed(t);

            t = System.nanoTime();
            txtFile = parser.parseToText(row.fileName(), source);
            source = null;                       // отпустить исходник до вызова LLM/записи в БД
            long parseMs = elapsed(t);

            // ПУСТОТА ПРОВЕРЯЕТСЯ ЗДЕСЬ — по сырому тексту, ДО вызова iman:
            // не тратим LLM-вызов на пустышку
            String text = Files.readString(txtFile, StandardCharsets.UTF_8);
            if (text.isBlank() || text.length() < minTextLength) {
                boolean marked = dao.markEmptyResult(row.id(), me);
                log.warn("Задача {}: извлечённый текст пуст/короче {} симв. (marked={})",
                        row.id(), minTextLength, marked);
                return;
            }

            t = System.nanoTime();
            String header = imanEnabled ? iman.extractHeader(text) : "";
            long imanMs = elapsed(t);

            String enriched = header.isEmpty() ? text : header + "\n" + text;
            boolean saved = dao.markDone(row.id(), me, enriched);
            if (saved) {
                log.info("Задача {} завершена: load={} мс, parse={} мс, iman={} мс, итог {} мс",
                        row.id(), loadMs, parseMs, imanMs, elapsed(t0));
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
            if (txtFile != null) {
                try {
                    Files.deleteIfExists(txtFile);
                } catch (IOException e) {
                    log.warn("Не удалось удалить временный файл {}", txtFile, e);
                }
            }
        }
    }

    private static long elapsed(long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000;
    }

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