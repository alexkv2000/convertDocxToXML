package kvo.convertXML.parser;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;
import kvo.convertXML.parser.model.*;
//import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
@Component
public class PdfToXmlParser {
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
    /** Абзац рвём, когда вертикальный промежуток между строками больше 1.3 высоты шрифта. */
    private static final float PARAGRAPH_GAP_FACTOR = 1.3f;
    /** Маркеры списков: тире, точки-буллеты, "1.", "1)", "а)". */
    private static final Pattern BULLET = Pattern.compile(
            "^\\s*([•●▪◦\\-*–—]|\\d{1,3}[.)]|[A-Za-zА-Яа-яЁё][.)])\\s+");

    public Path parseToXml(String fileName, byte[] pdfBytes) {
        Path tmp = null;
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(pdfBytes),
                IOUtils.createTempFileOnlyStreamCache())) {
            List<PdfLine> lines = new LineCollector().extract(doc);
            DocumentXml result = new DocumentXml();
            result.fileName = fileName;
            result.processedAt = Instant.now().toString();
            ParagraphXml current = null;
            PdfLine prev = null;
            for (PdfLine line : lines) {
                boolean newParagraph = current == null
                        || startsWithBullet(line)
                        || isParagraphBreak(prev, line);
                if (newParagraph) {
                    current = new ParagraphXml();
                    result.body.add(current);
                }
                boolean joinTight = !newParagraph && resolveHyphenation(current);
                appendRuns(current, line, joinTight);
                prev = line;
            }
            tmp = Files.createTempFile("docxml-", ".xml");
            try (OutputStream os = Files.newOutputStream(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                marshal(result, os);          // JAXB пишет потоком на диск
            }
            return tmp;
        } catch (Exception e) {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
            }
            throw new IllegalStateException(
                    "Не удалось разобрать pdf: " + fileName + " — " + e.getMessage(), e);
        }
    }
    private void marshal(DocumentXml doc, OutputStream os) throws JAXBException {
        Marshaller m = JAXB_CTX.createMarshaller();
        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        m.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
        m.marshal(doc, os);
    }
//    public byte[] parseToXml(String fileName, byte[] pdfBytes) {
//        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
//            List<PdfLine> lines = new LineCollector().extract(doc);
//            DocumentXml result = new DocumentXml();
//            result.fileName = fileName;
//            result.processedAt = Instant.now().toString();
//            ParagraphXml current = null;
//            PdfLine prev = null;
//            for (PdfLine line : lines) {
//                boolean newParagraph = current == null
//                        || startsWithBullet(line)
//                        || isParagraphBreak(prev, line);
//                if (newParagraph) {
//                    current = new ParagraphXml();
//                    result.body.add(current);
//                }
//                boolean joinTight = !newParagraph && resolveHyphenation(current);
//                appendRuns(current, line, joinTight);
//                prev = line;
//            }
//            return marshal(result);
//        } catch (Exception e) {
//            throw new IllegalStateException(
//                    "Не удалось разобрать pdf: " + fileName + " — " + e.getMessage(), e);
//        }
//    }
    // ---- склейка строк в абзацы ----
    private boolean isParagraphBreak(PdfLine prev, PdfLine line) {
        if (prev == null || prev.page != line.page) {
            return false;                       // абзац, перетёкший через страницу, склеиваем
        }
        float gap = prev.y - line.y;            // Y растёт сверху вниз
        return gap > PARAGRAPH_GAP_FACTOR * prev.fontSize;
    }
    private boolean startsWithBullet(PdfLine line) {
        return !line.chunks.isEmpty() && BULLET.matcher(line.chunks.get(0).text()).find();
    }
    /** Строка кончается переносом ("-"): убираем дефис и склеиваем без пробела. */
    private boolean resolveHyphenation(ParagraphXml px) {
        if (px.runs.isEmpty()) return false;
        RunXml last = px.runs.get(px.runs.size() - 1);
        String t = last.text;
        if (t != null && t.length() > 1
                && (t.endsWith("-") || t.endsWith("\u00AD"))) {
            last.text = t.substring(0, t.length() - 1);
            return true;
        }
        return false;
    }
    private void appendRuns(ParagraphXml px, PdfLine line, boolean joinTight) {
        boolean firstChunk = true;
        for (Chunk c : line.chunks) {
            String t = c.text().strip();
            if (t.isEmpty()) {
                firstChunk = false;
                continue;
            }
            RunXml last = px.runs.isEmpty() ? null : px.runs.get(px.runs.size() - 1);
            boolean sameFormat = last != null
                    && Boolean.TRUE.equals(last.bold) == c.bold()
                    && Boolean.TRUE.equals(last.italic) == c.italic();
            if (sameFormat) {
                boolean tight = firstChunk && joinTight;
                last.text = last.text + (tight ? "" : " ") + t;
            } else {
                RunXml r = new RunXml();
                r.text = t;
                if (c.bold())   r.bold = true;
                if (c.italic()) r.italic = true;
                px.runs.add(r);
            }
            firstChunk = false;
        }
    }
    // ---- сбор строк из PDF ----
    private record Chunk(String text, boolean bold, boolean italic) {}
    private static final class PdfLine {
        final int page;
        final float y;
        float fontSize;
        final List<Chunk> chunks = new ArrayList<>();
        PdfLine(int page, float y, float fontSize) {
            this.page = page;
            this.y = y;
            this.fontSize = fontSize;
        }
    }
    private static final class LineCollector extends PDFTextStripper {
        private final List<PdfLine> lines = new ArrayList<>();
        private PdfLine current;
        private int page = -1;
        List<PdfLine> extract(PDDocument doc) throws IOException {
            setSortByPosition(true);
            getText(doc);
            flush();
            return lines;
        }
        @Override
        protected void startPage(PDPage p) {
            flush();
            page++;
        }
        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.isBlank() || positions.isEmpty()) {
                return;
            }
            TextPosition first = positions.get(0);
            float y = first.getYDirAdj();
            if (current == null || current.page != page
                    || Math.abs(current.y - y) > 2.5f) {   // новый визуальный ряд
                flush();
                current = new PdfLine(page, y, first.getFontSizeInPt());
            }
            current.fontSize = Math.max(current.fontSize, first.getFontSizeInPt());
            current.chunks.add(new Chunk(text, isBold(first), isItalic(first)));
        }
        @Override
        protected void writeLineSeparator() {
            flush();
        }
        private void flush() {
            if (current != null && !current.chunks.isEmpty()) {
                lines.add(current);
            }
            current = null;
        }
    }
    private static boolean isBold(TextPosition pos) {
        return fontNameContains(pos, "bold");
    }
    private static boolean isItalic(TextPosition pos) {
        return fontNameContains(pos, "italic") || fontNameContains(pos, "oblique");
    }
    private static boolean fontNameContains(TextPosition pos, String marker) {
        String name = pos.getFont() != null ? pos.getFont().getName() : null;
        return name != null && name.toLowerCase(Locale.ROOT).contains(marker);
    }
//    private byte[] marshal(DocumentXml doc) throws JAXBException {
//        Marshaller m = JAXB_CTX.createMarshaller();
//        m.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
//        m.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
//        ByteArrayOutputStream out = new ByteArrayOutputStream();
//        m.marshal(doc, out);
//        return out.toByteArray();
//    }
}
