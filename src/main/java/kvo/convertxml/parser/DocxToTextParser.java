package kvo.convertxml.parser;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class DocxToTextParser {

    private static final Logger log = LoggerFactory.getLogger(DocxToTextParser.class);

    /** Разделитель ячеек таблицы в простом тексте. */
    static final char CELL_SEPARATOR = '\t';

    public Path parseToText(String fileName, byte[] docxBytes) {
        Path tmp = null;
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            tmp = Files.createTempFile("doctext-", ".txt");
            try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                // getBodyElements() сохраняет порядок: параграфы и таблицы вперемешку
                for (IBodyElement element : doc.getBodyElements()) {
                    if (element instanceof XWPFParagraph p) {
                        writeParagraph(out, p);
                    } else if (element instanceof XWPFTable t) {
                        writeTable(out, t);
                    }
                }
            }
            return tmp;
        } catch (Exception e) {
            deleteQuietly(tmp);
            throw new IllegalStateException(
                    "Не удалось разобрать docx: " + fileName + " — " + e.getMessage(), e);
        }
    }

    /** Пустые абзацы не пишем. */
    private void writeParagraph(BufferedWriter out, XWPFParagraph p) throws IOException {
        String text = paragraphText(p);
        if (!text.isBlank()) {
            out.write(text);
            out.newLine();
        }
    }

    private void writeTable(BufferedWriter out, XWPFTable table) throws IOException {
        for (XWPFTableRow row : table.getRows()) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (XWPFTableCell cell : row.getTableCells()) {
                if (isVertMergeContinue(cell)) {
                    continue;   // контент вертикального объединения живёт в первой ячейке
                }
                if (!first) sb.append(CELL_SEPARATOR);
                first = false;
                sb.append(cellText(cell));
            }
            if (sb.toString().isBlank()) {
                continue;       // строка целиком из пустых ячеек — не пишем пустую строку
            }
            out.write(sb.toString());
            out.newLine();
        }
    }

    private String paragraphText(XWPFParagraph p) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : p.getRuns()) {
            sb.append(run.text());
        }
        return sb.toString();
    }

    private String cellText(XWPFTableCell cell) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (XWPFParagraph p : cell.getParagraphs()) {
            String t = paragraphText(p);
            if (t.isEmpty()) {
                continue;
            }
            if (!first) sb.append(' ');
            first = false;
            sb.append(t);
        }
        return sb.toString();
    }

    /** Отсутствие val трактуется Word'ом как "continue". */
    private boolean isVertMergeContinue(XWPFTableCell cell) {
        CTTcPr tcPr = cell.getCTTc().getTcPr();
        // RESTART — первая ячейка объединения; null и CONTINUE — продолжение.
        // Проверять getVMerge() == null нельзя: геттер XmlBeans сам создаёт элемент.
        return tcPr.isSetVMerge() && tcPr.getVMerge().getVal() != STMerge.RESTART;
    }

    private void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.debug("Не удалось удалить временный файл {}", file, e);
        }
    }
}