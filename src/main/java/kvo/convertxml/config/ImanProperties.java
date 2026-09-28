package kvo.convertxml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "iman")
public record ImanProperties(
        boolean enabled,
        String baseUrl,       // ПОЛНЫЙ адрес: https://ai.loodsen.ru/api/v1/chat/completions
        String model,         // Qwen/Qwen3-Coder-Next-FP8
        String accessToken,
        double temperature,   // 0 = детерминированно (0.0 неотличим от «не задано»)
        int maxTokens,
        Duration connectTimeout,
        Duration readTimeout,
        int maxTextChars,
        int maxAttempts,
        Duration retryBackoff) {

    /** Компактный конструктор записи: дефолты + fail-fast при старте. */
    public ImanProperties {
        if (temperature <= 0) temperature = 0.1;
        if (maxTokens <= 0) maxTokens = 10_000;
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(20);
        if (readTimeout == null) readTimeout = Duration.ofMinutes(5);   // LLM отвечает небыстро
        if (maxTextChars <= 0) maxTextChars = 100_000;
        if (maxAttempts < 1) maxAttempts = 3;
        if (retryBackoff == null) retryBackoff = Duration.ofSeconds(2);
        if (enabled && (baseUrl == null || baseUrl.isBlank()
                || model == null || model.isBlank()
                || accessToken == null || accessToken.isBlank())) {
            throw new IllegalStateException(
                    "iman.enabled=true, но не заданы iman.base-url/model/access-token");
        }
    }
}