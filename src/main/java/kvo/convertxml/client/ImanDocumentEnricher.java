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
import org.springframework.web.client.RestClientResponseException;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ImanDocumentEnricher {

    private static final Logger log = LoggerFactory.getLogger(ImanDocumentEnricher.class);
    private static final Pattern TAG = Pattern.compile("<(/?)([A-Za-z][A-Za-z0-9_.-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'])*?)(/?)>");
    private final LlmAvailability llm;
    private final ImanTokenHolder tokens;              // НОВОЕ: живой access_token + авто-refresh
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
                                ImanTokenHolder tokens,              // НОВОЕ
                                @Value("${app.prompt}") String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("app.prompt не задан (application.yml) — промпт обязателен");
        }
        this.props = props;
        this.llm = llm;
        this.tokens = tokens;                                          // НОВОЕ
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
                // ИЗМЕНЕНО: Authorization больше не статичный defaultHeader —
                // токен может обновиться в runtime, ставим на каждый запрос
                .build();
        JdkClientHttpRequestFactory pingFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build());
        pingFactory.setReadTimeout(Duration.ofSeconds(30));
        this.pingClient = RestClient.builder()
                .requestFactory(pingFactory)
                .build();                                              // ИЗМЕНЕНО: см. выше
        this.dbf = newSecureDbf();
    }

    /** Автопочинка: дособирает пропущенные закрывающие теги и оборванный хвост.
     *  null — если дефект сложнее (лишний закрывающий и т.п.), не берёмся. */
    private String tryRepair(String xml) {
        record Fix(int pos, String tag) {}
        List<Fix> fixes = new ArrayList<>();
        Deque<String> open = new ArrayDeque<>();
        Matcher m = TAG.matcher(xml);
        while (m.find()) {
            String name = m.group(2);
            if (m.group(1).isEmpty()) {                       // открывающий
                if (m.group(4).isEmpty()) open.push(name);    // не self-closing
            } else if (!open.isEmpty() && open.peek().equals(name)) {
                open.pop();
            } else if (open.contains(name)) {                 // пропущен </...> — дособрать
                while (!open.peek().equals(name)) fixes.add(new Fix(m.start(), "</" + open.pop() + ">"));
                open.pop();
            } else {
                return null;                                  // закрывающий без пары
            }
        }
        while (!open.isEmpty()) fixes.add(new Fix(xml.length(), "</" + open.pop() + ">"));
        if (fixes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(xml);
        fixes.sort(Comparator.comparingInt(Fix::pos).reversed());
        for (Fix f : fixes) sb.insert(f.pos, f.tag());
        return sb.toString();
    }

    public void ping() {
        final String token = tokens.current();
        try {
            pingClient.post()
                    .uri(URI.create(props.baseUrl()))   // тот же полный адрес, что в attemptExtract
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(token))
                    .body(Map.of(
                            "model", props.model(),
                            "messages", List.of(Map.of("role", "user", KEY_CONTENT, "ping")),
                            "max_tokens", 1))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            // сидели на паузе и токен протух — обновим прямо из пробы,
            // следующая проба (через 60 с) пойдёт уже с новым токеном
            if (e.getStatusCode().value() == 401 || e.getStatusCode().value() == 403) {
                tokens.refreshIfStale(token);
            }
            throw e;
        }
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
        final String token = tokens.current();          // НОВОЕ: токен момента запроса
        JsonNode response;
        try {
            response = restClient.post()
                    .uri(URI.create(props.baseUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(token))   // НОВОЕ
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        int code = res.getStatusCode().value();
                        if (code == 401 || code == 403) {
                            // НОВОЕ: токен мог протухнуть — обновляем и повторяем запрос.
                            // Прошёл refresh — транзитный сбой, пауза не нужна.
                            if (tokens.refreshIfStale(token)) {
                                throw new ImanUnavailableException("LLM HTTP " + code
                                        + ": access_token обновлён, повторяем с новым");
                            }
                            // refresh НЕ прошёл — аккаунт/ключ отключены: пауза, задачи не жжём
                            llm.failure();
                        } else if (res.getStatusCode().is5xxServerError() || code == 429) {
                            llm.failure();   // провайдер лежит/перегружен
                        }
                        String err = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new ImanUnavailableException(
                                "LLM HTTP " + res.getStatusCode() + ": " + abbreviate(err));
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {        // таймаут/обрыв/DNS — не отвечает
            llm.failure();
            throw e;                                 // дальше — как раньше, на уровень задачи
        }
        llm.success();                              // HTTP 200 получен — эндпоинт жив
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
        return validateOrRepair(xml);
    }

    private String sanitize(String xml) {
        int close = xml.indexOf(DOC_CLOSE);          // дальше закрывающего — только мусор
        String s = close >= 0 ? xml.substring(0, close + DOC_CLOSE.length()) : xml;
        s = s.replace("\\u003c", "<")                // JSON-эскейпы, которые модель иногда не снимает
                .replace("\\u003e", ">")
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "")
                .replace("\\\"", "\"");
        s = s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\uFFFD\\u200B-\\u200D\\uFEFF]", "");
        return s.trim();
    }

    /** Валидация с правом на починку: пробуем варианты по очереди, строгий validate — финальный арбитр. */
    private String validateOrRepair(String xml) {
        try {
            validate(xml);
            return xml;
        } catch (ImanUnavailableException e) {
            Map<String, String> fixes = new LinkedHashMap<>();   // метка → кандидат
            fixes.put("очистка хвоста и эскейпов", sanitize(xml));
            fixes.put("досбор тегов", tryRepair(xml));
            String cleaned = sanitize(xml);
            fixes.put("очистка + досбор", cleaned.equals(xml) ? null : tryRepair(cleaned));
            for (Map.Entry<String, String> en : fixes.entrySet()) {
                String c = en.getValue();
                if (c == null) continue;
                try {
                    validate(c);                                 // повторная проверка — как просили
                    log.warn("LLM: XML с дефектом ({}), исправлен ({})",
                            abbreviate(e.getMessage(), 120), en.getKey());
                    return c;
                } catch (ImanUnavailableException ignore) { /* вариант не помог — берём следующий */ }
            }
            log.warn("LLM: невалидный XML, конец ответа: …{}",
                    xml.substring(Math.max(0, xml.length() - 200)));
            throw e;
        }
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