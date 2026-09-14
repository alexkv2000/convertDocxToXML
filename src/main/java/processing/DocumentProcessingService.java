package processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import infra.DocumentTaskDao;
import infra.DocumentTaskDao.TaskHeader;
import infra.InstanceId;
import parser.DocxToXmlParser;

@Service
public class DocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentProcessingService.class);

    private final DocumentTaskDao dao;
    private final DocxToXmlParser parser;
    private final InstanceId instanceId;
    private final ThreadPoolTaskExecutor heavyWorkers;
    private final long heavyThresholdBytes;

    public DocumentProcessingService(DocumentTaskDao dao,
                                     DocxToXmlParser parser,
                                     InstanceId instanceId,
                                     @Qualifier("docHeavyWorkers") ThreadPoolTaskExecutor heavyWorkers,
                                     @Value("${app.heavy-threshold-mb:10}") int heavyThresholdMb) {
        this.dao = dao;
        this.parser = parser;
        this.instanceId = instanceId;
        this.heavyWorkers = heavyWorkers;
        this.heavyThresholdBytes = heavyThresholdMb * 1024L * 1024L;
    }

    public void process(long taskId) {
        String me = instanceId.get();
        try {
            TaskHeader row = dao.findHeaderByIdAndOwner(taskId, me).orElse(null);
            if (row == null) {
                // задачу перехватил другой инстанс по stale-таймауту — выходим
                log.warn("Задача {} больше не принадлежит этому инстансу", taskId);
                return;
            }

            // Маршрутизация по размеру ДО загрузки бинарника:
            // тяжёлый файл грузится и парсится только в тяжёлом пуле
            long size = dao.binarySize(row.fileId());
            if (size >= heavyThresholdBytes) {
                log.info("Задача {} ({}): {} МБ — тяжёлый пул",
                        row.id(), row.fileName(), size / (1024L * 1024L));
                heavyWorkers.execute(() -> execute(row, me));
            } else {
                execute(row, me); // лёгкий файл — текущий поток лёгкого пула
            }

        } catch (Exception e) {
            log.error("Ошибка обработки задачи {}", taskId, e);
            dao.markFailed(taskId, me, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private void execute(TaskHeader row, String me) {
        try {
            byte[] docx = dao.loadBinary(row.fileId());
            log.info("Обработка {} ({})", row.id(), row.fileName());

            byte[] xml = parser.parseToXml(row.fileName(), docx);
            boolean saved = dao.markDone(row.id(), me, xml);

            if (saved) {
                log.info("Задача {} завершена, XML в doc_results", row.id());
            } else {
                log.warn("Задача {} перехвачена другим инстансом, результат отброшен", row.id());
            }

        } catch (Exception e) {
            log.error("Ошибка обработки задачи {}", row.id(), e);
            dao.markFailed(row.id(), me, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}