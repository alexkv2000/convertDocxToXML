package kvo.convertxml.parser;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class PdfToTextParser {

    private static final Logger log = LoggerFactory.getLogger(PdfToTextParser.class);

    /** Абзац рвём, когда вертикальный промежуток между строками больше 1.3 высоты шрифта. */
    private static final float PARAGRAPH_GAP_FACTOR = 1.3f;
    /** Маркеры списков: тире, точки-буллеты, "1.", "1)", "а)". */
    private static final Pattern BULLET = Pattern.compile(
            "^\\s*([•●▪◦\\-*–—]|\\d{1,3}[.)]|[A-Za-zА-Яа-яЁё][.)])\\s+");

    public Path parseToText(String fileName, byte[] pdfBytes) {
        Path tmp = null;
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(pdfBytes),
                IOUtils.createTempFileOnlyStreamCache())) {
            List<PdfLine> lines = new LineCollector().extract(doc);
            tmp = Files.createTempFile("doctext-", ".txt");
            try (BufferedWriter out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                writeParagraphs(lines, out);
            }
            return tmp;
        } catch (Exception e) {
            deleteQuietly(tmp);
            throw new IllegalStateException(
                    "Не удалось разобрать pdf: " + fileName + " — " + e.getMessage(), e);
        }
    }

    /** Склеивает строки PDF в абзацы (маркеры списков, разрывы, дефисы переноса) и пишет их в out. */
    private void writeParagraphs(List<PdfLine> lines, BufferedWriter out) throws IOException {
        StringBuilder current = null;
        PdfLine prev = null;
        for (PdfLine line : lines) {
            String lineText = lineText(line);
            if (lineText.isEmpty()) {
                continue;
            }
            if (isNewParagraph(current, lineText, prev, line)) {
                flushParagraph(out, current);
                current = new StringBuilder(lineText);
            } else if (endsWithHyphen(current)) {
                current.setLength(current.length() - 1); // убрать дефис переноса
                current.append(lineText);
            } else {
                current.append(' ').append(lineText);
            }
            prev = line;
        }
        flushParagraph(out, current);
    }

    private boolean isNewParagraph(StringBuilder current, String lineText, PdfLine prev, PdfLine line) {
        return current == null
                || BULLET.matcher(lineText).find()
                || isParagraphBreak(prev, line);
    }

    private void flushParagraph(BufferedWriter out, StringBuilder paragraph) throws IOException {
        if (paragraph == null) {
            return;
        }
        out.write(paragraph.toString());
        out.newLine();
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

    // ---- склейка строк в абзацы ----
    private boolean isParagraphBreak(PdfLine prev, PdfLine line) {
        if (prev == null || prev.page != line.page) {
            return false;                       // абзац, перетёкший через страницу, склеиваем
        }
        float gap = prev.y - line.y;            // Y растёт сверху вниз
        return gap > PARAGRAPH_GAP_FACTOR * prev.fontSize;
    }

    private boolean endsWithHyphen(StringBuilder sb) {
        if (sb == null || sb.length() < 2) {
            return false;
        }
        char last = sb.charAt(sb.length() - 1);
        return last == '-' || last == '\u00AD';
    }

    /** Склейка кусков строки: пробел только если его там ещё нет. */
    private String lineText(PdfLine line) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : line.chunks) {
            String t = chunk.strip();
            if (t.isEmpty()) {
                continue;
            }
            if (!sb.isEmpty() && !Character.isWhitespace(sb.charAt(sb.length() - 1))) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    // ---- сбор строк из PDF ----
    private static final class PdfLine {
        final int page;
        final float y;
        float fontSize;
        final List<String> chunks = new ArrayList<>();

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
            current.chunks.add(text);
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
}