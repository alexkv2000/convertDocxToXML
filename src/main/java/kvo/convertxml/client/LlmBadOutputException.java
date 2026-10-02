package kvo.convertxml.client;

/**
 * Ответ LLM неисправим и повтор бессмыслен: битый XML (не спасла даже автопочинка)
 * или ответ обрезан по max_tokens. Не транзитный сбой: при низкой temperature
 * модель выдаст то же самое — задача должна засчитываться как неудачная попытка,
 * а не крутиться в очереди бесконечно.
 */
public class LlmBadOutputException extends RuntimeException {
    public LlmBadOutputException(String msg) {
        super(msg);
    }
}