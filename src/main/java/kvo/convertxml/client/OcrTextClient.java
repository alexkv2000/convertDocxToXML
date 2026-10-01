package kvo.convertxml.client;

import kvo.convertxml.config.OcrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

@Component
public class OcrTextClient {

    private static final Logger log = LoggerFactory.getLogger(OcrTextClient.class);

    private final OcrProperties props;
    private final OcrAvailability availability;
    private final RestClient restClient;

    public OcrTextClient(OcrProperties props, OcrAvailability availability) {
        if (props.url() == null || props.url().isBlank()) {
            throw new IllegalStateException("ocr.url не задан (application.yml) — адрес сервиса ReadFile обязателен");
        }
        this.props = props;
        this.availability = availability;
        // HTTP/1.1 — по тому же принципу, что у LLM-клиента
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(props.connectTimeout())
                        .build());
        factory.setReadTimeout(props.readTimeout());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Распознать скан (PDF без текстового слоя) через CDvService/ReadFile.
     *  Транзитные сбои — повтор + пауза; IsSuccess=false — окончательный отказ. */
    public String extractText(String fileName, byte[] pdfBytes) {
        if (availability.isPaused()) {
            throw new ImanUnavailableException("OCR на паузе — отдаём задачу на повтор, не жжём таймаут");
        }
        String base64 = Base64.getEncoder().encodeToString(pdfBytes);
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            try {
                return attemptExtract(fileName, base64);
            } catch (ImanUnavailableException | ResourceAccessException e) {
                availability.failure();
                log.warn("OCR: попытка {}/{} не удалась: {}", attempt, props.maxAttempts(), e.getMessage());
                if (attempt == props.maxAttempts()) {
                    throw new ImanUnavailableException("OCR недоступен: " + e.getMessage(), e);
                }
                sleepQuietly(props.retryBackoff().multipliedBy(attempt));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private String attemptExtract(String fileName, String base64) {
        JsonNode response = restClient.post()
                .uri(URI.create(props.url()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of("FileName", fileName, "Base64Data", base64))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    String err;
                    try {
                        err = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    } catch (Exception readEx) {
                        err = String.valueOf(res.getStatusCode());
                    }
                    throw new ImanUnavailableException("OCR HTTP " + res.getStatusCode() + ": " + abbreviate(err));
                })
                .body(JsonNode.class);
        availability.success();
        if (response == null) {
            throw new ImanUnavailableException("OCR: пустой ответ");
        }
        boolean ok = response.path("IsSuccess").asBoolean(false);
        String notes = response.path("Notes").asString("");
        if (!ok) {
            // сервис принял запрос, но сам упал — повтор бессмыслен,
            // фиксируем как окончательную ошибку задачи (текст в error_message)
            throw new IllegalStateException(
                    "OCR не смог разобрать файл " + fileName + ": " + abbreviate(notes));
        }
        if (notes.isBlank()) {
            throw new IllegalStateException("OCR вернул пустой текст для " + fileName);
        }
        return cleanup(notes);
    }

    /** OCR выдаёт десятки пустых строк (остатки разметки страниц) — схлопываем,
     *  чтобы не тратить токены LLM. Если мешает — просто верните notes. */
    private String cleanup(String text) {
        return text.replace("\r\n", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ImanUnavailableException("OCR: ожидание повтора прервано", ie);
        }
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String abbreviate(String s) {
        return abbreviate(s, 300);
    }
}