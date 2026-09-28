package kvo.convertxml.client;

import kvo.convertxml.config.ImanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class ImanDocumentEnricher {

    private static final Logger log = LoggerFactory.getLogger(ImanDocumentEnricher.class);
    private final LlmAvailability llm;
    private final RestClient pingClient;
    // Имена полей OpenAI-совместимого /chat/completions
    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_CHOICES = "choices";
    private static final String KEY_FINISH_REASON = "finish_reason";

    private static final String DOC_OPEN = "<Document>";
    private static final String DOC_CLOSE = "</Document>";

    private final ImanProperties props;
    private final String prompt;                 // системная инструкция из app.prompt
    private final RestClient restClient;
    private final DocumentBuilderFactory dbf;

    public ImanDocumentEnricher(ImanProperties props,
                                LlmAvailability llm,
                                @Value("${app.prompt}") String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("app.prompt не задан (application.yml) — промпт обязателен");
        }
        this.props = props;
        this.llm = llm;
        this.prompt = prompt;
        // HTTP/1.1: после GOAWAY от балансировщика HTTP/2-клиент JDK продолжал слать запросы
        // в «мёртвое» мультиплексированное соединение — проба вечно висела на нём
        // (Request cancelled каждые 60 с). На 1.1 соединения не мультиплексируются,
        // мёртвое выкидывается, следующий запрос открывает новое.
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(props.connectTimeout())
                        .build());
        factory.setReadTimeout(props.readTimeout());
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.accessToken())
                .build();
        JdkClientHttpRequestFactory pingFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        pingFactory.setReadTimeout(Duration.ofSeconds(30));
        this.pingClient = RestClient.builder()
                .requestFactory(pingFactory)
                .defaultHeader("Authorization", "Bearer " + props.accessToken())
                .build();
        this.dbf = newSecureDbf();
    }

    public void ping() {
        pingClient.post()
                .uri(URI.create(props.baseUrl()))   // тот же полный адрес, что в attemptExtract
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "model", props.model(),
                        "messages", List.of(Map.of("role", "user", KEY_CONTENT, "ping")),
                        "max_tokens", 1))
                .retrieve()
                .toBodilessEntity();
    }
    /** Возвращает заполненную XML-шапку <Document>...</Document>. Транзитные сбои LLM повторяем. */
    public String extractHeader(String fullDocumentText) {
        for (int attempt = 1; attempt <= props.maxAttempts(); attempt++) {
            try {
                return attemptExtract(fullDocumentText);
            } catch (ImanUnavailableException e) {
                log.warn("LLM: попытка {}/{} не удалась: {}",
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
        if (llm.isPaused()) {
            throw new ImanUnavailableException("LLM на паузе — отдаём задачу на повтор, не жжём таймаут");
        }
        Map<String, Object> body = buildRequest(truncate(fullDocumentText));
        long started = System.currentTimeMillis();
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(URI.create(props.baseUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        // НОВОЕ: 5xx — провайдер лежит/перегружен, 4xx — эндпоинт жив
                        if (res.getStatusCode().is5xxServerError()
                                || res.getStatusCode().value() == 429) {
                            llm.failure();
                        }
                        String err = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new ImanUnavailableException(
                                "LLM HTTP " + res.getStatusCode() + ": " + abbreviate(err));
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {        // НОВОЕ: таймаут/обрыв/DNS — не отвечает
            llm.failure();
            throw e;                                 // дальше — как раньше, на уровень задачи
        }
        llm.success();                              // НОВОЕ: HTTP 200 получен — эндпоинт жив
        Completion c = parseCompletion(response);
        log.info("LLM {}: ответ за {} мс, finish_reason={}, tokens prompt/completion: {}/{}",
                props.model(), System.currentTimeMillis() - started,
                c.finishReason(), c.promptTokens(), c.completionTokens());
        if (c.completionTokens() * 5 >= props.maxTokens() * 4) {
            log.warn("LLM: completion_tokens={} близок к max_tokens={} — риск обрезки ответа, "
                            + "увеличьте iman.max-tokens или уменьшите iman.max-text-chars",
                    c.completionTokens(), props.maxTokens());
        }
        String xml = extractXml(c.content());
        validate(xml);
        return xml;
    }

    /** Разобранный ответ /chat/completions. */
    private record Completion(String content, String finishReason,
                              int promptTokens, int completionTokens) {
    }
    /** choices[0] + usage; content с fallback на reasoning_content для reasoning-моделей. */
    private Completion parseCompletion(JsonNode response) {
        if (response == null) {
            throw new ImanUnavailableException("LLM: пустой ответ");
        }
        JsonNode error = response.path(KEY_ERROR);
        if (error.isObject()) {
            throw new ImanUnavailableException("LLM error: "
                    + error.path(KEY_MESSAGE).asString(""));
        }
        JsonNode choices = response.path(KEY_CHOICES);
        if (!choices.isArray() || choices.isEmpty()) {
            throw new ImanUnavailableException(
                    "LLM: нет choices: " + abbreviate(response.toString()));
        }
        JsonNode choice = choices.get(0);
        String finishReason = choice.path(KEY_FINISH_REASON).asString("");
        if ("length".equals(finishReason)) {
            throw new ImanUnavailableException(
                    "LLM: ответ обрезан по max_tokens=" + props.maxTokens()
                            + " — увеличьте iman.max-tokens или уменьшите iman.max-text-chars");
        }
        if ("content_filter".equals(finishReason)) {
            throw new ImanUnavailableException("LLM: ответ заблокирован контент-фильтром");
        }
        if (!finishReason.isEmpty() && !"stop".equals(finishReason) && !"eos".equals(finishReason)) {
            // не ломаем обработку, но фиксируем нестандартное поведение бэкенда
            log.warn("LLM: нестандартный finish_reason={} — проверьте ответ модели", finishReason);
        }
        JsonNode message = choice.path(KEY_MESSAGE);
        String content = message.path(KEY_CONTENT).asString("");
        if (content.isBlank()) {
            content = message.path("reasoning_content").asString("");
        }
        if (content.isBlank()) {
            throw new ImanUnavailableException(
                    "LLM: пустой content (finish_reason=" + finishReason + ")");
        }
        return new Completion(content, finishReason,
                response.path("usage").path("prompt_tokens").asInt(-1),
                response.path("usage").path("completion_tokens").asInt(-1));
    }

    /** OpenAI-совместимое тело запроса: system — инструкция, user — текст документа. */
    private Map<String, Object> buildRequest(String documentText) {
        return Map.of(
                "model", props.model(),
                "messages", List.of(
                        Map.of("role", "system", KEY_CONTENT, prompt),
                        Map.of("role", "user", KEY_CONTENT, documentText)),
                "temperature", props.temperature(),
                "max_tokens", props.maxTokens(),
                "stream", false);
    }


    /** Вырезает <Document>...</Document> из ответа модели (в т.ч. из markdown-заборов ```xml). */
    private String extractXml(String agentText) {
        String cleaned = agentText.replace("```xml", "").replace("```", "").trim();
        int start = cleaned.indexOf(DOC_OPEN);
        int end = cleaned.lastIndexOf(DOC_CLOSE);
        if (start < 0 || end < start) {
            throw new ImanUnavailableException(
                    "LLM: нет <Document>...</Document> в ответе: " + abbreviate(cleaned));
        }
        return cleaned.substring(start, end + DOC_CLOSE.length());
    }

    private void validate(String xml) {
        try {
            dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new ImanUnavailableException("LLM: невалидный XML — " + e.getMessage());
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
            throw new ImanUnavailableException("LLM: ожидание повтора прервано", ie);
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