package kvo.convertxml.parser;

import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
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
                        String text = paragraphText(p);
                        if (text.isBlank()) {
                            continue;               // пустые абзацы не пишем
                        }
                        out.write(text);
                        out.newLine();
                    } else if (element instanceof XWPFTable t) {
                        writeTable(out, t);
                    }
                }
            }
            return tmp;
        } catch (Exception e) {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
            throw new IllegalStateException(
                    "Не удалось разобрать docx: " + fileName + " — " + e.getMessage(), e);
        }
    }
    private void writeTable(BufferedWriter out, XWPFTable table) throws IOException {
        for (XWPFTableRow row : table.getRows()) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (XWPFTableCell cell : row.getTableCells()) {
                if (isVertMergeContinue(cell)) {
                    continue;       // контент вертикального объединения живёт в первой ячейке
                }
                if (!first) sb.append(CELL_SEPARATOR);
                first = false;
                sb.append(cellText(cell));
            }
            if (sb.toString().isBlank()) {
                continue;           // строка целиком из пустых ячеек — не пишем пустую строку
            }
            out.write(sb.toString());
            out.newLine();
        }
    }

    private String paragraphText(XWPFParagraph p) {
        StringBuilder sb = new StringBuilder();
        for (XWPFRun run : p.getRuns()) {
            String t = run.text();
            if (t != null) {
                sb.append(t);
            }
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
        if (tcPr == null || tcPr.getVMerge() == null) {
            return false;
        }
        STMerge.Enum v = tcPr.getVMerge().getVal();
        return v == null || v == STMerge.CONTINUE;
    }
}