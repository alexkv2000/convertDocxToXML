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

@Component
public class ImanDocumentEnricher {

    private static final Logger log = LoggerFactory.getLogger(ImanDocumentEnricher.class);
    private final LlmAvailability llm;
    private final ImanTokenHolder tokens;              // живой access_token + авто-refresh
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

    /** Найденный XML-тег: позиция '<', позиция сразу за '>', имя, закрывающий ли, самозакрывающийся ли. */
    private record Tag(int start, int end, String name, boolean closing, boolean selfClosing) {}

    /** Правка автопочинки: вставить закрывающий тег tag в позицию pos. */
    private record TagFix(int pos, String tag) {}

    /** Разобранный ответ /chat/completions. */
    private record Completion(String content, String finishReason,
                              int promptTokens, int completionTokens) {
    }

    public ImanDocumentEnricher(ImanProperties props,
                                LlmAvailability llm,
                                ImanTokenHolder tokens,
                                @Value("${app.prompt}") String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("app.prompt не задан (application.yml) — промпт обязателен");
        }
        this.props = props;
        this.llm = llm;
        this.tokens = tokens;
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
                // Authorization не статичный defaultHeader —
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
                .build();
        this.dbf = newSecureDbf();
    }

    public void ping() {
        final String token = tokens.current();
        try {
            pingClient.post()
                    .uri(URI.create(props.baseUrl()))   // тот же полный адрес, что в requestCompletion
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
        final int maxAttempts = props.maxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return attemptExtract(fullDocumentText);
            } catch (ImanUnavailableException e) {
                String reason = e.getMessage();
                log.warn("LLM: попытка {}/{} не удалась: {}", attempt, maxAttempts, reason);
                if (attempt == maxAttempts) {
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
        final String token = tokens.current();          // токен момента запроса
        JsonNode response = requestCompletion(body, token);
        llm.success();                                  // HTTP 200 получен — эндпоинт жив
        Completion c = parseCompletion(response);
        logCompletion(c, System.currentTimeMillis() - started);
        warnIfTruncated(c);
        String xml = extractXml(c.content());
        return validateOrRepair(xml);
    }

    /** Аргументы лога — заранее вычисленные переменные, без вызовов методов внутри log(...). */
    private void logCompletion(Completion c, long elapsedMs) {
        String model = props.model();
        String finishReason = c.finishReason();
        int promptTokens = c.promptTokens();
        int completionTokens = c.completionTokens();
        log.info("LLM {}: ответ за {} мс, finish_reason={}, tokens prompt/completion: {}/{}",
                model, elapsedMs, finishReason, promptTokens, completionTokens);
    }

    /** POST /chat/completions; сетевые сбои помечают LLM недоступным и пробрасываются. */
    private JsonNode requestCompletion(Map<String, Object> body, String token) {
        try {
            return restClient.post()
                    .uri(URI.create(props.baseUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .headers(h -> h.setBearerAuth(token))
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        HttpStatusCode status = res.getStatusCode();
                        handleLlmError(token, status);   // refresh токена / пауза / проброс
                        String err = new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        throw new ImanUnavailableException("LLM HTTP " + status + ": " + abbreviate(err));
                    })
                    .body(JsonNode.class);
        } catch (ResourceAccessException e) {        // таймаут/обрыв/DNS — не отвечает
            llm.failure();
            throw e;                                 // дальше — как раньше, на уровень задачи
        }
    }

    /** Классификация HTTP-ошибки LLM. Может выбросить «повторяем с новым токеном». */
    private void handleLlmError(String token, HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403) {
            // токен мог протухнуть — обновляем и повторяем запрос.
            // Прошёл refresh — транзитный сбой, пауза не нужна.
            if (tokens.refreshIfStale(token)) {
                throw new ImanUnavailableException("LLM HTTP " + code
                        + ": access_token обновлён, повторяем с новым");
            }
            // refresh НЕ прошёл — аккаунт/ключ отключены: пауза, задачи не жжём
            llm.failure();
        } else if (status.is5xxServerError() || code == 429) {
            llm.failure();   // провайдер лежит/перегружен
        }
    }

    private void warnIfTruncated(Completion c) {
        int completionTokens = c.completionTokens();
        int maxTokens = props.maxTokens();
        if (completionTokens * 5 >= maxTokens * 4) {
            log.warn("LLM: completion_tokens={} близок к max_tokens={} — риск обрезки ответа, "
                            + "увеличьте iman.max-tokens или уменьшите iman.max-text-chars",
                    completionTokens, maxTokens);
        }
    }

    /** Автопочинка: дособирает пропущенные закрывающие теги и оборванный хвост.
     *  null — если дефект сложнее (лишний закрывающий и т.п.), не берёмся. */
    private String tryRepair(String xml) {
        List<TagFix> fixes = new ArrayList<>();
        Deque<String> open = new ArrayDeque<>();
        for (Tag t : scanTags(xml)) {
            if (!applyTag(t, open, fixes)) {
                return null;                 // закрывающий без пары — не берёмся
            }
        }
        while (!open.isEmpty()) fixes.add(new TagFix(xml.length(), "</" + open.pop() + ">"));
        if (fixes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder(xml);
        fixes.sort(Comparator.comparingInt(TagFix::pos).reversed());
        for (TagFix f : fixes) sb.insert(f.pos, f.tag());
        return sb.toString();
    }

    /** Накладывает тег на стек открытых; false — чужой закрывающий, починка невозможна. */
    private static boolean applyTag(Tag t, Deque<String> open, List<TagFix> fixes) {
        if (!t.closing()) {
            if (!t.selfClosing()) {          // открывающий и не self-closing
                open.push(t.name());
            }
            return true;
        }
        if (!open.isEmpty() && open.peek().equals(t.name())) {
            open.pop();                      // штатное закрытие
            return true;
        }
        if (!open.contains(t.name())) {
            return false;                    // закрывающий без пары
        }
        while (!open.peek().equals(t.name())) {   // пропущен </...> — дособрать
            fixes.add(new TagFix(t.start(), "</" + open.pop() + ">"));
        }
        open.pop();
        return true;
    }

    /** Линейный сканер XML-тегов вместо regex: квантифицированная альтернатива
     *  в старом паттерне на длинных атрибутах переполняла стек рекурсии бэктрекинга. */
    private static List<Tag> scanTags(String xml) {
        List<Tag> tags = new ArrayList<>();
        int pos = 0;
        while (true) {
            int lt = xml.indexOf('<', pos);
            if (lt < 0) {
                return tags;
            }
            Tag tag = parseTagAt(xml, lt);
            if (tag == null) {
                pos = lt + 1;                // '<' не начинает корректный тег — ищем дальше
            } else {
                tags.add(tag);
                pos = tag.end();
            }
        }
    }

    /** Тег, начинающийся с '<' в позиции lt; null — если это не корректный тег
     *  (нет имени, либо '>' вне кавычек не найден). */
    private static Tag parseTagAt(String xml, int lt) {
        int pos = lt + 1;
        boolean closing = pos < xml.length() && xml.charAt(pos) == '/';
        if (closing) {
            pos++;
        }
        if (pos >= xml.length() || !isNameStart(xml.charAt(pos))) {
            return null;
        }
        int nameEnd = pos + 1;
        while (nameEnd < xml.length() && isNameChar(xml.charAt(nameEnd))) {
            nameEnd++;
        }
        int gt = findTagEnd(xml, nameEnd);
        if (gt < 0) {
            return null;
        }
        String name = xml.substring(pos, nameEnd);
        boolean selfClosing = !closing && xml.charAt(gt - 1) == '/';
        return new Tag(lt, gt + 1, name, closing, selfClosing);
    }

    /** Конец тега: '>' вне кавычек; -1, если кавычка или тег не закрыты. */
    private static int findTagEnd(String xml, int from) {
        int p = from;
        while (p < xml.length()) {
            char c = xml.charAt(p);
            if (c == '"' || c == '\'') {
                int close = xml.indexOf(c, p + 1);
                if (close < 0) {
                    return -1;
                }
                p = close + 1;
            } else if (c == '>') {
                return p;
            } else {
                p++;
            }
        }
        return -1;
    }

    private static boolean isNameStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isNameChar(char c) {
        return isNameStart(c) || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
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
            return firstValid(xml, repairCandidates(xml), e);
        }
    }

    /** Кандидаты починки: метка → вариант; порядок = приоритет применения. */
    private Map<String, String> repairCandidates(String xml) {
        String cleaned = sanitize(xml);
        Map<String, String> fixes = new LinkedHashMap<>();
        fixes.put("очистка хвоста и эскейпов", cleaned);
        fixes.put("досбор тегов", tryRepair(xml));
        // очистка уже не изменила ничего — комбо дублировало бы чистый досбор
        fixes.put("очистка + досбор", cleaned.equals(xml) ? null : tryRepair(cleaned));
        return fixes;
    }

    /** Первый кандидат, прошедший валидацию; не подошёл ни один — исходная ошибка. */
    private String firstValid(String xml, Map<String, String> fixes, ImanUnavailableException cause) {
        for (Map.Entry<String, String> en : fixes.entrySet()) {
            String candidate = en.getValue();
            if (candidate == null || !isValid(candidate)) {
                continue;                    // вариант не помог — берём следующий
            }
            String defect = abbreviate(cause.getMessage(), 120);
            String appliedFix = en.getKey();
            log.warn("LLM: XML с дефектом ({}), исправлен ({})", defect, appliedFix);
            return candidate;
        }
        String tail = xml.substring(Math.max(0, xml.length() - 200));
        log.warn("LLM: невалидный XML, конец ответа: …{}", tail);
        throw cause;
    }

    /** Тихая проверка кандидата (повторная валидация — как просили). */
    private boolean isValid(String xml) {
        try {
            validate(xml);
            return true;
        } catch (ImanUnavailableException e) {
            return false;
        }
    }

    /** choices[0] + usage. */
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
        checkFinishReason(finishReason);
        String content = contentOf(choice.path(KEY_MESSAGE), finishReason);
        return new Completion(content, finishReason,
                response.path("usage").path("prompt_tokens").asInt(-1),
                response.path("usage").path("completion_tokens").asInt(-1));
    }

    /** Обрезанные и заблокированные ответы — ошибка; нестандартные — предупреждение. */
    private void checkFinishReason(String finishReason) {
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
    }

    /** content с fallback на reasoning_content для reasoning-моделей. */
    private static String contentOf(JsonNode message, String finishReason) {
        String content = message.path(KEY_CONTENT).asString("");
        if (content.isBlank()) {
            content = message.path("reasoning_content").asString("");
        }
        if (content.isBlank()) {
            throw new ImanUnavailableException(
                    "LLM: пустой content (finish_reason=" + finishReason + ")");
        }
        return content;
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