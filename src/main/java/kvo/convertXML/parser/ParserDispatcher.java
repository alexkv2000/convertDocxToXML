package kvo.convertXML.parser;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
@Component
public class ParserDispatcher {
    private final DocxToXmlParser docxParser;
    private final PdfToXmlParser pdfParser;
    public ParserDispatcher(DocxToXmlParser docxParser, PdfToXmlParser pdfParser) {
        this.docxParser = docxParser;
        this.pdfParser = pdfParser;
    }
    public Path parseToXml(String fileName, byte[] bytes) {
        return switch (extensionOf(fileName)) {
            case "docx" -> docxParser.parseToXml(fileName, bytes);
            case "pdf"  -> pdfParser.parseToXml(fileName, bytes);
            default -> throw new IllegalArgumentException(
                    "Неподдерживаемый формат: ." + extensionOf(fileName)
                            + " (поддерживаются .docx и .pdf)");
        };
    }
    private static String extensionOf(String fileName) {
        int i = fileName == null ? -1 : fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i + 1).toLowerCase(Locale.ROOT);
    }
}
