package parser;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTcPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;
import org.springframework.stereotype.Component;
import parser.model.DocumentXml;
import parser.model.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;

@Component
public class DocxToXmlParser {

    private static final JAXBContext JAXB_CTX;

    static {
        try {
            JAXB_CTX = JAXBContext.newInstance(
                    DocumentXml.class, ParagraphXml.class, TableXml.class);
        } catch (JAXBException e) {
            throw new IllegalStateException("JAXB init failed", e);
        }
    }

    public byte[] parseToXml(String fileName, byte[] docxBytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            DocumentXml result = new DocumentXml();
            result.fileName = fileName;
            result.processedAt = Instant.now().toString();
            // getBodyElements() сохраняет порядок: параграфы и таблицы вперемешку
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    result.body.add(toParagraphXml(p));
                } else if (element instanceof XWPFTable t) {
                    result.body.add(toTableXml(t));
                }
            }
            return marshal(result);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Не удалось разобрать docx: " + fileName + " — " + e.getMessage(), e);
        }
    }

    private ParagraphXml toParagraphXml(XWPFParagraph p) {
        ParagraphXml px = new ParagraphXml();
        px.style = p.getStyleID();                      // может быть null → атрибут опускается
        px.alignment = p.getAlignment() != null ? p.getAlignment().name() : null;
        for (XWPFRun run : p.getRuns()) {
            RunXml rx = toRunXml(run);
            if (rx != null) {
                px.runs.add(rx);
            }
        }
        return px;
    }

    private RunXml toRunXml(XWPFRun run) {
        RunXml rx = new RunXml();
        rx.text = run.text();
        if (run.isBold())   rx.bold = true;
        if (run.isItalic()) rx.italic = true;
        return (rx.text == null || rx.text.isEmpty()) ? null : rx;
    }

    private TableXml toTableXml(XWPFTable table) {
        TableXml tx = new TableXml();
        for (XWPFTableRow row : table.getRows()) {
            tx.rows.add(toRowXml(row));
        }
        return tx;
    }

    private TableRowXml toRowXml(XWPFTableRow row) {
        TableRowXml rxx = new TableRowXml();
        for (XWPFTableCell cell : row.getTableCells()) {
            rxx.cells.add(toCellXml(cell));
        }
        return rxx;
    }

    private TableCellXml toCellXml(XWPFTableCell cell) {
        TableCellXml cellXml = new TableCellXml();
        applyCellProperties(cell.getCTTc().getTcPr(), cellXml);
        for (XWPFParagraph cp : cell.getParagraphs()) {
            cellXml.paragraphs.add(toParagraphXml(cp));
        }
        return cellXml;
    }

    private void applyCellProperties(CTTcPr tcPr, TableCellXml cellXml) {
        if (tcPr == null) {
            return;
        }
        if (tcPr.getGridSpan() != null) {
            cellXml.gridSpan = tcPr.getGridSpan().getVal().intValue();
        }
        if (tcPr.getVMerge() != null) {
            cellXml.vMerge = vMergeValue(tcPr);
        }
    }

    /** Отсутствие val трактуется Word'ом как "continue". */
    private String vMergeValue(CTTcPr tcPr) {
        STMerge.Enum v = tcPr.getVMerge().getVal();
        return (v == null) ? "continue" : v.toString();
    }

    private byte[] marshal(DocumentXml doc) throws JAXBException {
        Marshaller m = JAXB_CTX.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        m.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.marshal(doc, out);
        return out.toByteArray();
    }
}