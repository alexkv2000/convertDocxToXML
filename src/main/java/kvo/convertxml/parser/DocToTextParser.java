package kvo.convertxml.parser;

import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.OldWordFileFormatException;
import org.apache.poi.hwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

@Component
public class DocToTextParser {

    static final char CELL_SEPARATOR = '\t';

    public Path parseToText(String fileName, byte[] docBytes) {
        Path tmp = null;
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(docBytes))) {
            tmp = Files.createTempFile("doctext-", ".txt");
            try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                walkBody(doc, out);
            }
            return tmp;
        } catch (EncryptedDocumentException e) {
            throw new IllegalArgumentException("Документ .doc защищён паролем: " + fileName, e);
        } catch (OldWordFileFormatException e) {
            throw new IllegalStateException(
                    "Не удалось разобрать doc: " + fileName + " — формат Word 6/95 не поддерживается", e);
        } catch (Exception e) {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
            throw new IllegalStateException(
                    "Не удалось разобрать doc: " + fileName + " — " + e.getMessage(), e);
        }
    }

    private void walkBody(HWPFDocument doc, BufferedWriter out) throws IOException {
        Range range = doc.getRange();
        TableIterator tables = new TableIterator(range);
        int i = 0;
        while (i < range.numParagraphs()) {
            Paragraph p = range.getParagraph(i);
            if (p.isInTable() && tables.hasNext()) {
                Table table = tables.next();
                writeTable(out, table);
                i++;                                        // текущий параграф покрыт таблицей
                while (i < range.numParagraphs()
                        && range.getParagraph(i).getStartOffset() < table.getEndOffset()) {
                    i++;                                    // пропускаем остальные параграфы таблицы
                }
            } else {
                String text = paragraphText(p);
                if (!text.isBlank()) {                      // пустые абзацы не пишем
                    out.write(text);
                    out.newLine();
                }
                i++;
            }
        }
    }
    private void writeTable(BufferedWriter out, Table table) throws IOException {
        for (int r = 0; r < table.numRows(); r++) {
            TableRow row = table.getRow(r);
            StringBuilder sb = new StringBuilder();
            boolean firstCell = true;
            for (int c = 0; c < row.numCells(); c++) {
                TableCell cell = row.getCell(c);
                // хвост горизонтального объединения: контент живёт в первой ячейке
                if (cell.isMerged() && !cell.isFirstMerged()) {
                    continue;
                }
                while (c + 1 < row.numCells()
                        && row.getCell(c + 1).isMerged()
                        && !row.getCell(c + 1).isFirstMerged()) {
                    c++;                                    // пропускаем накрытые grid-колонки
                }
                if (!firstCell) sb.append(CELL_SEPARATOR);
                firstCell = false;
                sb.append(cellText(cell));
            }
            if (sb.toString().isBlank()) {
                continue;           // строка целиком из пустых ячеек — не пишем пустую строку
            }
            out.write(sb.toString());
            out.newLine();
        }
    }

    private String paragraphText(Paragraph p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.numCharacterRuns(); i++) {
            sb.append(cleanText(p.getCharacterRun(i).text()));
        }
        return sb.toString();
    }

    private String cellText(TableCell cell) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int k = 0; k < cell.numParagraphs(); k++) {
            String t = paragraphText(cell.getParagraph(k));
            if (t.isEmpty()) {
                continue;
            }
            if (!first) sb.append(' ');
            first = false;
            sb.append(t);
        }
        return sb.toString();
    }

    /** Чистка бинарных артефактов .doc: поля Word, метки ячеек/абзацев/разрывов. */
    private static String cleanText(String s) {
        if (s == null) {
            return "";
        }
        return s
                .replaceAll("\u0013[^\u0014\u0015]*\u0014([^\u0015]*)\u0015", "$1") // поле: берём результат, код выбрасываем
                .replaceAll("\u0013[^\u0015]*\u0015", "")                          // поле без результата
                .replaceAll("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]", ""); // 0x07=метка ячейки, 0x0D=конец абзаца и пр.
    }
}