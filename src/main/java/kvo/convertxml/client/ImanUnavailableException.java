package kvo.convertxml.client;
/** Сбой iman: задача уйдёт в retry, после исчерпания попыток — status=3. */
public class ImanUnavailableException extends RuntimeException {
    public ImanUnavailableException(String message) { super(message); }
    public ImanUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}