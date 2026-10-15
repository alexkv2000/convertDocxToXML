package kvo.convertxml.parser;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ParserDispatcher {
    private final DocxToTextParser docxParser;
    private final DocToTextParser docParser;
    private final PdfToTextParser pdfParser;

    public ParserDispatcher(DocxToTextParser docxParser,
                            DocToTextParser docParser,
                            PdfToTextParser pdfParser) {
        this.docxParser = docxParser;
        this.docParser = docParser;
        this.pdfParser = pdfParser;
    }

    public Path parseToText(String fileName, byte[] bytes) {
        DocumentExtractor.SourceFormat fmt = DocumentExtractor.sniff(bytes);
        return switch (fmt) {
            case DOCX -> docxParser.parseToText(fileName, bytes);
            case DOC  -> docParser.parseToText(fileName, bytes);
            case PDF  -> pdfParser.parseToText(fileName, bytes);
            case RTF, HTML -> throw new IllegalArgumentException(
                    "Файл «" + fileName + "» содержит " + fmt + ", а не Word/PDF — конвертируйте в .doc/.docx/.pdf");
            case UNKNOWN -> throw new IllegalArgumentException(
                    "Не удалось определить формат файла: " + fileName
                            + " — размер " + (bytes == null ? 0 : bytes.length)
                            + " байт, сигнатура " + headHex(bytes));
        };
    }
    /** Первые байты файла в hex — видно, что на самом деле лежит в «.pdf». */
    private static String headHex(byte[] b) {
        if (b == null || b.length == 0) return "нет (файл пустой)";
        int n = Math.min(8, b.length);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i]));
        }
        return sb + "…";
    }
}