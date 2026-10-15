package kvo.convertxml.client;

/** Ошибка LLM, не зависящая от документа и не проходящая со временем
 *  (400 "Model not found", 404, 413). Повтор бессмыслен: задача не должна
 *  крутиться в транзитном цикле и на каждом витке перемалывать OCR. */
public class LlmPermanentException extends RuntimeException {
    public LlmPermanentException(String message) {
        super(message);
    }
}