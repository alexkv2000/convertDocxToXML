package kvo.convertxml.client;

/** Кластер OCR занят/недоступен — задача должна уйти на повтор, а не падать. */
public class OcrUnavailableException extends RuntimeException {
    public OcrUnavailableException(String message) {
        super(message);
    }
    public OcrUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}