package kvo.convertxml.parser;

/** Файл слишком велик для OCR — задача завершается ошибкой БЕЗ повторов. */
public class OcrSizeLimitException extends RuntimeException {
    public OcrSizeLimitException(String message) {
        super(message);
    }
}