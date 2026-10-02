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
import java.util.concurrent.Semaphore;                             // +++ cap параллелизма LLM
import java.util.concurrent.TimeUnit;                                // +++
import java.util.regex.Pattern;                                      // +++ экранирование «голых» &

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

    // +++ «Голый» амперсанд (не часть корректной сущности) — самая частая причина
    // +++ битого XML от модели ("The entity name must immediately follow the '&'").
    private static final Pattern BARE_AMP = Pattern.compile(
            "&(?!(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)");

    // +++ Cap параллельных запросов к LLM: 16–25 одновременных запросов (по числу
    // +++ doc-workers) перегружают провайдера — ответы деградируют до 115–125 с,
    // +++ затем 502-шторм. Семафор держит давление постоянным.
    private final Semaphore llmSlots;
    private final int llmMaxConcurrent;
    /** Сколько воркер ждёт слот, прежде чем уйти в транзитный повтор. */
    private final long slotWaitMs;
    /** Порог «медленного» ответа — ранний сигнал перегрузки провайдера. */
    private final long slowResponseMs;

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
                                @Value("${app.prompt}") String prompt,
                                @Value("${iman.max-concurrent:8}") int maxConcurrent,
                                @Value("${iman.slot-wait-ms:120000}") long slotWaitMs,
                                @Value("${iman.slow-response-ms:30000}") long slowResponseMs) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalStateException("app.prompt не задан (application.yml) — промпт обязателен");
        }
        this.props = props;
        this.llm = llm;
        this.tokens = tokens;
        this.llmMaxConcurrent = Math.max(1, maxConcurrent);
        this.llmSlots = new Semaphore(llmMaxConcurrent);
        this.slotWaitMs = Math.max(1_000, slotWaitMs);
        this.slowResponseMs = slowResponseMs > 0 ? slowResponseMs : 30_000L;
        // +++ Гарантированные дополнения к системному промпту из app.prompt:
        // +++ 1) экранирование спецсимволов XML (лечит сырой & в ответах);
        // +++ 2) /no_think — Qwen3 не тратит лимит completion на «размышления»
        // +++    (именно это давало обрезку по max_tokens=10000).
        // +++ Для не-Qwen моделей это безвредный текст в конце инструкции.
        this.prompt = prompt.strip()
                + "\n\nСпецсимволы &, <, > в текстовых значениях тегов экранируй: &amp; &lt; &gt;."
                + "\nВыводи только итоговый XML, без пояснений и размышлений."
                + "\n\n/no_think";
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
        log.info("LLM конфигурация: max-concurrent={}, slot-wait-ms={}, slow-response-ms={}",
                llmMaxConcurrent, this.slotWaitMs, this.slowResponseMs);
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

    /** Возвращает заполненную XML-шапку <Document>...</Document>. Транзитные сбои LLM повторяем.
     *  +++ LlmBadOutputException сквозь цикл НЕ ловится: ответ детерминированно битый,
     *  +++ повтор с тем же входом даст тот же результат — уходим сразу.
     *  +++ Слот семафора берётся ПОПЫТКОЙ (внутри attemptExtract): backoff-сон между
     *  +++ попытками не держит слот впустую. */
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
        boolean acquired = false;
        try {
            // +++ Не более iman.max-concurrent запросов к LLM одновременно.
            // +++ Нет слота за iman.slot-wait-ms — транзитный сбой, задача в повтор.
            acquired = llmSlots.tryAcquire(slotWaitMs, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new ImanUnavailableException("LLM: нет свободного слота за "
                        + slotWaitMs + " мс (лимит " + llmMaxConcurrent
                        + ", свободно " + llmSlots.availablePermits() + ")");
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
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new ImanUnavailableException("LLM: ожидание слота прервано", ie);
        } finally {
            if (acquired) {
                llmSlots.release();
            }
        }
    }

    /** Аргументы лога — заранее вычисленные переменные, без вызовов методов внутри log(...). */
    private void logCompletion(Completion c, long elapsedMs) {
        String model = props.model();
        String finishReason = c.finishReason();
        int promptTokens = c.promptTokens();
        int completionTokens = c.completionTokens();
        log.info("LLM {}: ответ за {} мс, finish_reason={}, tokens prompt/completion: {}/{}",
                model, elapsedMs, finishReason, promptTokens, completionTokens);
        // +++ Ранний сигнал перегрузки: ответы 115–125 с предшествовали 502-шторму.
        // +++ Грепабельно: "LLM ПЕРЕГРУЗКА".
        if (elapsedMs >= slowResponseMs) {
            log.warn("LLM ПЕРЕГРУЗКА: ответ {} мс при пороге {} мс; "
                            + "свободно слотов {}/{}",
                    elapsedMs, slowResponseMs,
                    llmSlots.availablePermits(), llmMaxConcurrent);
        }
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
        // +++ экранируем «голые» амперсанды (уже корректные сущности вида &amp;/&#160; не трогаем)
        s = BARE_AMP.matcher(s).replaceAll("&amp;");
        return s.trim();
    }

    /** Валидация с правом на починку: пробуем варианты по очереди, строгий validate — финальный арбитр. */
    private String validateOrRepair(String xml) {
        try {
            validate(xml);
            return xml;
        } catch (LlmBadOutputException e) {
            return firstValid(xml, repairCandidates(xml), e);
        }
    }

    /** Кандидаты починки: метка → вариант; порядок = приоритет применения. */
    private Map<String, String> repairCandidates(String xml) {
        String cleaned = sanitize(xml);          // +++ теперь ещё и экранирует «голые» &
        Map<String, String> fixes = new LinkedHashMap<>();
        fixes.put("очистка хвоста, эскейпов и &", cleaned);                   // +++
        fixes.put("досбор тегов", tryRepair(xml));
        // очистка уже не изменила ничего — комбо дублировало бы чистый досбор
        fixes.put("очистка + досбор", cleaned.equals(xml) ? null : tryRepair(cleaned));
        return fixes;
    }

    /** Первый кандидат, прошедший валидацию; не подошёл ни один — исходная ошибка. */
    private String firstValid(String xml, Map<String, String> fixes,
                              LlmBadOutputException cause) {
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
        } catch (LlmBadOutputException e) {
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
        // +++ обрезка по лимиту детерминирована (reasoning-токены при том же входе повторятся)
        if ("length".equals(finishReason)) {
            throw new LlmBadOutputException(
                    "LLM: ответ обрезан по max_tokens=" + props.maxTokens()
                            + " — увеличьте iman.max-tokens или уменьшите iman.max-text-chars");
        }
        if ("content_filter".equals(finishReason)) {
            throw new LlmBadOutputException("LLM: ответ заблокирован контент-фильтром");
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
            throw new ImanUnavailableException(     // оставлено транзитным: бывает при деградации провайдера
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
            // +++ при temperature 0.1 модель повторит тот же мусор — повтор бессмыслен
            throw new LlmBadOutputException(
                    "LLM: нет <Document>...</Document> в ответе: " + abbreviate(cleaned));
        }
        return cleaned.substring(start, end + DOC_CLOSE.length());
    }

    private void validate(String xml) {
        try {
            dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            // +++ битый XML после провала автопочинки — неисправим, не транзитный сбой
            throw new LlmBadOutputException("LLM: невалидный XML — " + e.getMessage());
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