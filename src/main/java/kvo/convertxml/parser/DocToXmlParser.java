package kvo.convertxml.parser;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.OldWordFileFormatException;
import org.apache.poi.hwpf.model.StyleDescription;
import org.apache.poi.hwpf.model.StyleSheet;
import org.apache.poi.hwpf.usermodel.*;
import org.springframework.stereotype.Component;
import kvo.convertxml.parser.model.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

@Component
public class DocToXmlParser {

    private static final JAXBContext JAXB_CTX;

    static {
        try {
            JAXB_CTX = JAXBContext.newInstance(
                    DocumentXml.class, ParagraphXml.class, RunXml.class,
                    TableXml.class, TableRowXml.class, TableCellXml.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("JAXB init failed", e);
        }
    }

    public Path parseToXml(String fileName, byte[] docBytes) {
        Path tmp = null;
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(docBytes))) {
            DocumentXml result = new DocumentXml();
            result.fileName = fileName;
            result.processedAt = Instant.now().toString();

            walkBody(doc, result);

            tmp = Files.createTempFile("docxml-", ".xml");
            try (OutputStream os = Files.newOutputStream(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                marshal(result, os);          // JAXB пишет потоком на диск
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

    private void walkBody(HWPFDocument doc, DocumentXml result) {
        Range range = doc.getRange();
        StyleSheet styles = doc.getStyleSheet();
        TableIterator tables = new TableIterator(range);
        int i = 0;
        while (i < range.numParagraphs()) {
            Paragraph p = range.getParagraph(i);
            if (p.isInTable() && tables.hasNext()) {
                Table table = tables.next();
                result.body.add(toTableXml(table, styles));
                i++;                                        // текущий параграф покрыт таблицей
                while (i < range.numParagraphs()
                        && range.getParagraph(i).getStartOffset() < table.getEndOffset()) {
                    i++;                                    // пропускаем остальные параграфы таблицы
                }
            } else {
                result.body.add(toParagraphXml(p, styles));
                i++;
            }
        }
    }
    private void marshal(DocumentXml doc, OutputStream os) throws JAXBException {
        Marshaller m = JAXB_CTX.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        m.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
        m.marshal(doc, os);
    }
    private ParagraphXml toParagraphXml(Paragraph p, StyleSheet styles) {
        ParagraphXml px = new ParagraphXml();
        px.style = styleName(styles, p.getStyleIndex());
        px.alignment = alignmentName(p.getJustification());
        for (int i = 0; i < p.numCharacterRuns(); i++) {
            CharacterRun run = p.getCharacterRun(i);
            String text = cleanText(run.text());
            if (text.isEmpty()) {
                continue;
            }
            RunXml rx = new RunXml();
            rx.text = text;
            if (run.isBold())   rx.bold = true;
            if (run.isItalic()) rx.italic = true;
            px.runs.add(rx);
        }
        return px;
    }
    private TableXml toTableXml(Table table, StyleSheet styles) {
        TableXml tx = new TableXml();
        for (int r = 0; r < table.numRows(); r++) {
            TableRow row = table.getRow(r);
            TableRowXml rxx = new TableRowXml();
            for (int c = 0; c < row.numCells(); c++) {
                TableCell cell = row.getCell(c);
                // хвост горизонтального объединения: контент живёт в первой ячейке
                if (cell.isMerged() && !cell.isFirstMerged()) {
                    continue;
                }
                // gridSpan = сколько grid-колонок накрыто объединением
                int span = 1;
                while (c + 1 < row.numCells()
                        && row.getCell(c + 1).isMerged()
                        && !row.getCell(c + 1).isFirstMerged()) {
                    span++;
                    c++;
                }
                TableCellXml cellXml = new TableCellXml();
                if (span > 1) cellXml.gridSpan = span;
                if (cell.isFirstVerticallyMerged()) {
                    cellXml.vMerge = "restart";
                } else if (cell.isVerticallyMerged()) {
                    cellXml.vMerge = "continue";
                }
                for (int k = 0; k < cell.numParagraphs(); k++) {
                    cellXml.paragraphs.add(toParagraphXml(cell.getParagraph(k), styles));
                }
                rxx.cells.add(cellXml);
            }
            tx.rows.add(rxx);
        }
        return tx;
    }
    /** Имя стиля из таблицы стилей .doc (может быть локализовано: «Обычный», «Заголовок 1»). */
    private String styleName(StyleSheet styles, int idx) {
        if (styles == null || idx < 0 || idx >= styles.numStyles()) {
            return null;
        }
        StyleDescription sd = styles.getStyleDescription(idx);
        String name = sd == null ? null : sd.getName();
        return (name == null || name.isBlank()) ? null : name;
    }
    /** Те же значения, что ParagraphAlignment.name() в docx-ветке. */
    private String alignmentName(int jc) {
        return switch (jc) {
            case 0 -> "LEFT";
            case 1 -> "CENTER";
            case 2 -> "RIGHT";
            case 3 -> "BOTH";
            default -> null;
        };
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