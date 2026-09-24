package kvo.convertxml.client;

import kvo.convertxml.config.ImanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ImanDocumentEnricher {

    private static final Logger log = LoggerFactory.getLogger(ImanDocumentEnricher.class);

    // Имена полей A2A/JSON-RPC протокола iman — вынесены в константы, чтобы не дублировать литералы
    private static final String KEY_PARTS = "parts";
    private static final String KEY_TEXT = "text";
    private static final String KEY_TYPE = "type";
    private static final String KEY_KIND = "kind";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_STATUS = "status";
    private static final String KEY_ERROR = "error";

    private static final String DOC_OPEN = "<Document>";
    private static final String DOC_CLOSE = "</Document>";

    private final ImanProperties props;
    private final String prompt;
    private final RestClient restClient;
    private final DocumentBuilderFactory dbf;

    public ImanDocumentEnricher(ImanProperties props,
                                @Value("${app.prompt}") String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("app.prompt не задан (application.yml) — промпт обязателен");
        }
        this.props = props;
        this.prompt = prompt;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(props.connectTimeout()).build());
        factory.setReadTimeout(props.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl() + "/agents/" + props.agent())
                .defaultHeader("Authorization", "Bearer " + props.accessToken())
                .requestFactory(factory)
                .build();
        this.dbf = newSecureDbf();
    }

    /** Возвращает заполненную XML-шапку <Document>...</Document>. Транзитные сбои агента повторяем. */
    public String extractHeader(String fullDocumentText) {
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            try {
                return attemptExtract(fullDocumentText);
            } catch (ImanUnavailableException e) {
                log.warn("iman: попытка {}/{} не удалась: {}",
                        attempt, props.maxAttempts(), e.getMessage());
                if (attempt == props.maxAttempts()) {
                    throw e;
                }
                sleepQuietly(props.retryBackoff().multipliedBy(attempt));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private String attemptExtract(String fullDocumentText) {
        Map<String, Object> body = buildRequest(truncate(fullDocumentText));

        long started = System.currentTimeMillis();
        JsonNode response = restClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    String err = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                    throw new ImanUnavailableException(
                            "iman HTTP " + res.getStatusCode() + ": " + abbreviate(err));
                })
                .body(JsonNode.class);
        log.info("iman: ответ агента за {} мс", System.currentTimeMillis() - started);

        String xml = extractXml(agentText(response));
        validate(xml);
        return xml;
    }

    // id, messageId, contextId генерируются ЗАНОВО на каждый вызов (в т.ч. на каждый ретрай).
    private Map<String, Object> buildRequest(String documentText) {
        Map<String, Object> message = Map.of(
                "role", "user",
                KEY_PARTS, List.of(Map.of(KEY_TYPE, KEY_TEXT, KEY_TEXT, prompt + documentText)),
                "messageId", UUID.randomUUID().toString(),
                "contextId", UUID.randomUUID().toString());

        return Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "message/send",
                "params", Map.of(
                        KEY_MESSAGE, message,
                        "configuration", Map.of("acceptedOutputModes", List.of(KEY_TEXT)),
                        "skillId", "default"));
    }

    private String agentText(JsonNode response) {
        if (response == null) {
            throw new ImanUnavailableException("iman: пустой ответ");
        }
        if (response.has(KEY_ERROR)) {
            throw new ImanUnavailableException("iman: jsonrpc error "
                    + response.path(KEY_ERROR).path("code").asInt() + ": "
                    + response.path(KEY_ERROR).path(KEY_MESSAGE).asString(""));
        }
        JsonNode result = response.path("result");
        String s = firstText(result.path(KEY_PARTS));
        if (s != null) return s;
        s = firstText(result.path(KEY_STATUS).path(KEY_MESSAGE).path(KEY_PARTS));
        if (s != null) return s;
        String fallback = null;
        StringBuilder names = new StringBuilder();
        for (JsonNode artifact : result.path("artifacts")) {
            String name = artifact.path("name").asString("");
            names.append(name).append(", ");
            String text = "reasoning".equalsIgnoreCase(name)
                    ? null                                   // «рассуждения» агента пропускаем
                    : firstText(artifact.path(KEY_PARTS));
            if (text == null) {
                continue;
            }
            if (text.contains(DOC_OPEN)) {
                return text;
            }
            if (fallback == null) {
                fallback = text;
            }
        }
        if (fallback != null) {
            return fallback;
        }
        String state = result.path(KEY_STATUS).path("state").asString("");
        throw new ImanUnavailableException("iman: нет текстовых parts (state=" + state
                + ", artifacts=[" + names + "]): " + abbreviate(response.toString()));
    }

    /** Первый непустой текстовый part; type или kind = "text". */
    private String firstText(JsonNode parts) {
        for (JsonNode part : parts) {
            JsonNode text = part.path(KEY_TEXT);
            if (!text.isString() || text.asString().isBlank()) {
                continue;
            }
            String kind = part.path(KEY_TYPE).asString(part.path(KEY_KIND).asString(""));
            if (kind.isEmpty() || KEY_TEXT.equals(kind)) {
                return text.asString();
            }
        }
        return null;
    }

    /** Вырезает <Document>...</Document> из ответа агента (в т.ч. из markdown-заборов ```xml). */
    private String extractXml(String agentText) {
        String cleaned = agentText.replace("```xml", "").replace("```", "").trim();
        int start = cleaned.indexOf(DOC_OPEN);
        int end = cleaned.lastIndexOf(DOC_CLOSE);
        if (start < 0 || end < start) {
            throw new ImanUnavailableException(
                    "iman: нет <Document>...</Document> в ответе: " + abbreviate(cleaned));
        }
        return cleaned.substring(start, end + DOC_CLOSE.length());
    }

    private void validate(String xml) {
        try {
            dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new ImanUnavailableException("iman: невалидный XML — " + e.getMessage());
        }
    }

    private String truncate(String text) {
        return text.length() <= props.maxTextChars()
                ? text
                : text.substring(0, props.maxTextChars());
    }

    private static void sleepQuietly(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ImanUnavailableException("iman: ожидание повтора прервано", ie);
        }
    }

    private static DocumentBuilderFactory newSecureDbf() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setExpandEntityReferences(false);
            return f;
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось настроить XML-парсер", e);
        }
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String abbreviate(String s) {
        return abbreviate(s, 300);
    }
}