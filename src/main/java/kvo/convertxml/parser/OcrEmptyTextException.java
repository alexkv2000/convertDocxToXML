package kvo.convertxml.parser;

/** Сервер OCR ответил успехом (IsSuccess=true), но текста нет — 0 символов.
 *  Повтор не нужен */
public class OcrEmptyTextException extends RuntimeException {
    public OcrEmptyTextException(String message) {
        super(message);
    }
}