package kvo.convertxml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "iman")
public record ImanProperties(
        boolean enabled,
        String baseUrl,       // https://iman.ai.loodsen.ru
        String agent,         // example_react
        String accessToken,   // imanv_tok_v1...
        Duration connectTimeout,
        Duration readTimeout,
        int maxTextChars,
        int maxAttempts,
        Duration retryBackoff) {

    /** Компактный конструктор записи: дефолты + fail-fast при старте. */
    public ImanProperties {
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(20);
        if (readTimeout == null) readTimeout = Duration.ofMinutes(5);   // LLM отвечает небыстро
        if (maxTextChars <= 0) maxTextChars = 100_000;
        if (maxAttempts < 1) maxAttempts = 3;
        if (retryBackoff == null) retryBackoff = Duration.ofSeconds(2);
        if (enabled && (baseUrl == null || baseUrl.isBlank()
                || agent == null || agent.isBlank()
                || accessToken == null || accessToken.isBlank())) {
            throw new IllegalStateException(
                    "iman.enabled=true, но не заданы iman.base-url/agent/access-token. "
                            + "Проверьте: секция iman должна быть верхнего уровня, не внутри app:");
        }
    }
}