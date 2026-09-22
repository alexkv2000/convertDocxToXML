package kvo.convertxml.parser;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class ParserDispatcher {
    private final DocxToXmlParser docxParser;
    private final DocToXmlParser docParser;
    private final PdfToXmlParser pdfParser;
    public ParserDispatcher(DocxToXmlParser docxParser, DocToXmlParser docParser, PdfToXmlParser pdfParser) {
        this.docxParser = docxParser;
        this.docParser = docParser;
        this.pdfParser = pdfParser;
    }
    public Path parseToXml(String fileName, byte[] bytes) {
        DocumentExtractor.SourceFormat fmt = DocumentExtractor.sniff(bytes);
        return switch (fmt) {
            case DOCX -> docxParser.parseToXml(fileName, bytes);
            case DOC  -> docParser.parseToXml(fileName, bytes);
            case PDF  -> pdfParser.parseToXml(fileName, bytes);
            case RTF, HTML -> throw new IllegalArgumentException(
                    "Файл «" + fileName + "» содержит " + fmt + ", а не Word/PDF — конвертируйте в .doc/.docx/.pdf");
            case UNKNOWN -> throw new IllegalArgumentException(
                    "Не удалось определить формат файла: " + fileName);
        };
    }
}
