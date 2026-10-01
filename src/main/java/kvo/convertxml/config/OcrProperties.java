package kvo.convertxml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.time.Duration;
@ConfigurationProperties(prefix = "ocr")
public record OcrProperties(
        String url,
        @DefaultValue("10s") Duration connectTimeout,
        @DefaultValue("600s") Duration readTimeout,   // OCR может работать минуты
        @DefaultValue("2") int maxAttempts,
        @DefaultValue("10s") Duration retryBackoff
) {}