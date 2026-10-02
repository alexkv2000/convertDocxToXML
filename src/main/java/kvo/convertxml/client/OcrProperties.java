package kvo.convertxml.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.List;

/** Адреса и режимы кластера OCR: doc-app…doc-app8. */
@ConfigurationProperties(prefix = "ocr")
public record OcrProperties(
        List<String> servers,            // http://host:port/CDvService/{guid} — БАЗА, без пути метода
        String ocrPath,                  // путь метода распознавания, напр. /ToolsPage/ReadFile
        String healthPath,
        Duration failureCooldown,
        Duration connectTimeout,
        Duration readTimeout,
        int maxServersPerRequest,
        int maxInFlightPerServer
) {
    public OcrProperties {
        if (servers == null) {
            servers = List.of();
        }
        servers = servers.stream()
                .map(String::trim)
                .map(u -> u.endsWith("/") ? u.substring(0, u.length() - 1) : u)   // без хвостового "/"
                .toList();
        if (ocrPath == null || ocrPath.isBlank()) {
            ocrPath = "/api/ocr";
        }
        if (!ocrPath.startsWith("/")) {
            ocrPath = "/" + ocrPath;     // защита от потерянного "/" при конкатенации
        }
        if (healthPath == null || healthPath.isBlank()) {
            healthPath = ocrPath;        // для нестандартного сервиса пробуем путь метода
        }
        if (!healthPath.startsWith("/")) {
            healthPath = "/" + healthPath;
        }
        if (failureCooldown == null) failureCooldown = Duration.ofSeconds(30);
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(5);
        if (readTimeout == null) readTimeout = Duration.ofMinutes(20);
        if (maxServersPerRequest <= 0) maxServersPerRequest = Math.max(1, servers.size());
        if (maxInFlightPerServer < 0) maxInFlightPerServer = 0;
    }
}