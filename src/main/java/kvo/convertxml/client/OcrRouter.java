package kvo.convertxml.client;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Роутер по кластеру OCR-серверов doc-app…doc-app8.
 * Политика: round-robin по доступным серверам; перед отправкой опираемся на свежий
 * статус живости (фоновый опрос каждые 5 с); сбой — штраф серверу и следующий;
 * все заняты/недоступны — OcrUnavailableException, задача уходит на повтор.
 * Два вида штрафа: транспортный (снимается зелёной пробой) и логический
 * (IsSuccess=false при живом сервисе — пробой НЕ снимается, истекает по времени).
 */
@Component
public class OcrRouter {

    private static final Logger log = LoggerFactory.getLogger(OcrRouter.class);

    /** Только для разбора конверта ответа CDvService. Jackson 3 = пакеты tools.jackson (Spring Boot 4). */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OcrProperties props;
    private final RestClient ocrClient;       // долгий read-timeout — сам OCR
    private final RestClient healthClient;    // короткие таймауты — пробы живости
    private final List<Server> servers;
    private final AtomicInteger cursor = new AtomicInteger();
    private int probeRounds;                  // для периодической сводки (один @Scheduled-поток)

    public OcrRouter(OcrProperties props) {
        if (props.servers().isEmpty()) {
            throw new IllegalStateException(
                    "ocr.servers пуст — укажите адреса doc-app…doc-app8 в application.yml");
        }
        for (String url : props.servers()) {
            validateServer(url);
        }
        this.props = props;
        // HTTP/1.1 (как у iman-клиента): без мультиплексирования, мёртвое соединение
        // выбрасывается, следующий запрос открывает новое
        JdkClientHttpRequestFactory ocrFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(props.connectTimeout())
                        .build());
        ocrFactory.setReadTimeout(props.readTimeout());
        this.ocrClient = RestClient.builder().requestFactory(ocrFactory).build();

        JdkClientHttpRequestFactory healthFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(2))
                        .build());
        healthFactory.setReadTimeout(Duration.ofSeconds(5));
        this.healthClient = RestClient.builder().requestFactory(healthFactory).build();

        List<Server> list = new ArrayList<>(props.servers().size());
        for (String url : props.servers()) {
            String trimmed = url.trim();
            list.add(new Server(shortName(trimmed), trimmed));
        }
        this.servers = List.copyOf(list);
    }

    /** Стартовый опрос — ротация сразу начинается с живых серверов. */
    @PostConstruct
    void init() {
        probeAll();
    }

    /**
     * Распознать PDF на одном из доступных серверов.
     * Пустой текст — валидный результат (далее сработает существующая проверка «короче 20 симв.»).
     */
    public String extractText(String fileName, byte[] pdf) {
        List<Server> order = pickOrder();
        String lastError = null;
        int attempts = 0;
        int idx = 0;
        while (idx < order.size() && attempts < props.maxServersPerRequest()) {
            Server s = order.get(idx);
            idx++;
            if (tryAcquire(s)) {
                attempts++;
                try {
                    String text = callOcr(s, fileName, pdf);
                    return text == null ? "" : text;
                } catch (ResourceAccessException e) {      // коннект/таймаут — сервер не отвечает
                    lastError = punish(s, "нет ответа: " + e.getMessage());
                } catch (RestClientResponseException e) {  // 4xx/5xx от сервиса
                    lastError = punish(s, "HTTP " + e.getStatusCode().value());
                } catch (OcrServiceException e) {          // жив, но ответил отказом (IsSuccess=false)
                    lastError = punishLogical(s, e.getMessage());
                } finally {
                    s.release();
                }
            }
        }
        throw noServer(lastError);
    }

    private static void validateServer(String url) {
        boolean ok = false;
        try {
            URI u = URI.create(url);
            ok = u.getScheme() != null && u.getHost() != null;
        } catch (IllegalArgumentException e) {
            log.warn("OCR: не удалось разобрать адрес '{}': {}", url, e.getMessage());
        }
        if (!ok) {
            throw new IllegalStateException("Некорректный адрес OCR-сервера: '" + url
                    + "' — ожидается http://host:port[/база_сервиса]; проверьте '/' после порта");
        }
    }

    /** Порядок обхода: ротация от последнего выданного сервера. */
    private List<Server> pickOrder() {
        int n = servers.size();
        int start = Math.floorMod(cursor.getAndIncrement(), n);
        List<Server> order = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            order.add(servers.get((start + i) % n));
        }
        return order;
    }

    /** Слот на сервере: false — сервер в штрафе или достигнут лимит параллелизма. */
    private boolean tryAcquire(Server s) {
        if (s.penalized()) {
            return false;
        }
        int limit = props.maxInFlightPerServer();
        if (limit > 0 && s.inFlight.get() >= limit) {
            return false;   // занят — возьмём следующий, вернёмся к нему по ротации
        }
        s.inFlight.incrementAndGet();   // гонка на ±1 слот допустима: лимит страховой, не бизнес-правило
        return true;
    }

    /**
     * Транспорт: POST {base}{ocrPath}, JSON {"FileName":…, "Base64Data": base64(PDF)} —
     * контракт GAZ.CDvMainService.Web.Entities.ReadFileRequest.
     * Ответ — конверт {"IsSuccess","Notes","Data"}: HTTP всегда 200, ошибки внутри конверта.
     * При успехе распознанный текст лежит в Notes (подтверждено контрольным запросом),
     * Data="True" — просто статус и игнорируется.
     */
    private String callOcr(Server s, String fileName, byte[] pdf) {
        String request = "{\"FileName\":\"" + jsonEscape(fileName)
                + "\",\"Base64Data\":\"" + Base64.getEncoder().encodeToString(pdf) + "\"}";
        String resp = ocrClient.post()
                .uri(URI.create(s.baseUrl + props.ocrPath()))
                .contentType(new MediaType(MediaType.APPLICATION_JSON, StandardCharsets.UTF_8))
                .body(request)
                .retrieve()
                .body(String.class);

        if (resp == null || resp.isBlank()) {
            throw new OcrServiceException("пустой ответ");
        }
        String t = resp.strip();
        if (t.charAt(0) != '{') {
            return t;                                        // чистый text/plain — как раньше
        }
        JsonNode root;
        try {
            root = JSON.readTree(t);
        } catch (JacksonException e) {
            throw new OcrServiceException("битый JSON: " + abbreviate(t));
        }
        if (!root.has("IsSuccess")) {
            throw new OcrServiceException("JSON без конверта IsSuccess/Notes/Data: " + abbreviate(t));
        }
        if (!root.get("IsSuccess").asBoolean()) {
            throw new OcrServiceException("IsSuccess=false — "
                    + abbreviate(root.path("Notes").asText("")));
        }
        // Контракт ReadFile: при успехе текст — в Notes; пустой → сработает проверка «короче 20 симв.»
        return root.path("Notes").asText("");
    }

    /** Сжать стек-трейс/мусор до одной строки для лога и исключений. */
    private static String abbreviate(String s) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 160 ? t : t.substring(0, 160) + "…";
    }

    /** Минимальное экранирование для ручной сборки JSON. */
    private static String jsonEscape(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** Транспортный штраф: коннект/таймаут/HTTP; зелёная проба живости его снимает. */
    private String punish(Server s, String why) {
        s.penalizeTransportFor(props.failureCooldown());
        log.warn("OCR {}: сбой ({}), транспортный штраф на {} с",
                s.name, why, props.failureCooldown().toSeconds());
        return s.name + ": " + why;
    }

    /** Логический штраф: сервис жив, но ответил отказом — проба его НЕ снимает. */
    private String punishLogical(Server s, String why) {
        s.penalizeLogicalFor(props.failureCooldown());
        log.warn("OCR {}: отказ ({}), логический штраф на {} с (проба не снимает)",
                s.name, why, props.failureCooldown().toSeconds());
        return s.name + ": " + why;
    }

    /** Фоновый опрос доступности: 2xx — сервер в ротации, иначе — штраф. */
    @Scheduled(fixedDelayString = "${ocr.probe-interval-ms:5000}")
    public void probeAll() {
        for (Server s : servers) {
            probe(s);
        }
        probeRounds++;
        if (probeRounds % 12 == 0) {           // раз в минуту — сводка по кластеру
            String snapshot = statusSnapshot();
            log.info("OCR-кластер (занятость слотов / DOWN): {}", snapshot);
        }
    }

    private void probe(Server s) {
        boolean alive = isAlive(s);
        if (alive) {
            s.clearTransportPenalty();         // снимает ТОЛЬКО транспортный штраф
            if (!s.alive) {
                log.info("OCR {}: снова доступен", s.name);
            }
        } else {
            if (s.alive) {
                long seconds = props.failureCooldown().toSeconds();
                log.warn("OCR {}: проба не прошла, выводим из ротации на {} с", s.name, seconds);
            }
            s.penalizeTransportFor(props.failureCooldown());
        }
        s.alive = alive;
    }

    private boolean isAlive(Server s) {
        try {
            healthClient.get()
                    .uri(URI.create(s.baseUrl + props.healthPath()))
                    .retrieve()
                    .toBodilessEntity();
            return true;                              // 2xx
        } catch (RestClientResponseException e) {
            return true;                              // 404/405/… — сервис отвечает
        } catch (ResourceAccessException e) {
            return false;                             // нет соединения
        }
    }

    private OcrUnavailableException noServer(String lastError) {
        String snapshot = statusSnapshot();
        if (lastError == null) {
            return new OcrUnavailableException("OCR: свободных серверов нет — " + snapshot);
        }
        return new OcrUnavailableException(
                "OCR: серверы недоступны, последняя ошибка: " + lastError + " — " + snapshot);
    }

    private String statusSnapshot() {
        List<String> parts = new ArrayList<>(servers.size());
        for (Server s : servers) {
            String state = s.penalized() ? "DOWN" : String.valueOf(s.inFlight.get());
            parts.add(s.name + "=" + state);
        }
        return String.join(", ", parts);
    }

    /** Из http://doc-app2.tit:8080 → doc-app2.tit (для логов). */
    private static String shortName(String url) {
        String n = url.replaceFirst("^https?://", "");
        int cut = n.indexOf('/');
        if (cut > 0) {
            n = n.substring(0, cut);
        }
        cut = n.indexOf(':');
        if (cut > 0) {
            n = n.substring(0, cut);
        }
        return n;
    }

    /** Один сервер кластера: имя, штрафы до момента (epoch ms), счётчик занятых слотов. */
    private static final class Server {
        final String name;
        final String baseUrl;
        final AtomicInteger inFlight = new AtomicInteger();
        private volatile long transportPenalizedUntil;   // сбрасывается зелёной пробой живости
        private volatile long logicalPenalizedUntil;     // истекает только по времени
        volatile boolean alive;

        Server(String name, String baseUrl) {
            this.name = name;
            this.baseUrl = baseUrl;
        }

        boolean penalized() {
            long now = System.currentTimeMillis();
            return now < transportPenalizedUntil || now < logicalPenalizedUntil;
        }

        void clearTransportPenalty() {
            transportPenalizedUntil = 0;
        }

        void penalizeTransportFor(Duration d) {
            transportPenalizedUntil = System.currentTimeMillis() + d.toMillis();
        }

        void penalizeLogicalFor(Duration d) {
            logicalPenalizedUntil = Math.max(logicalPenalizedUntil, System.currentTimeMillis() + d.toMillis());
        }

        void release() {
            inFlight.decrementAndGet();
        }
    }

    /** Сервис ответил, но логически отказал: HTTP 200 + IsSuccess=false либо неожиданный формат. */
    static final class OcrServiceException extends RuntimeException {
        OcrServiceException(String message) {
            super(message);
        }
    }
}