package kvo.convertxml.client;

import kvo.convertxml.config.ImanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final String PROMPT = """
            Ты получаешь текст документа. Выполни шаги:
            1. Выбери из текста названия всех юр. лиц (организация-автор и контрагенты), их ИНН и КПП, если присутствуют.
            2. Определи Вид документа (классификация, принятая в системах ЭДО).
            3. Заполни XML-структуру по шаблону ниже данными из текста; заполни все теги, если данных нет — оставь тег пустым.
            4. Выведи мне ТОЛЬКО XML без стороннего текста и без markdown.

            Шаблон:
            <Document><Header><DocumentType></DocumentType><RelatedDocument></RelatedDocument><Date></Date><Number></Number><OrderDate></OrderDate><OrderNumber></OrderNumber><CopyNumber></CopyNumber><Summa></Summa><NDS></NDS><Currency></Currency></Header><Parties><Company><Country></Country><Name></Name><Role></Role><Address></Address><INN></INN><Phone></Phone><SignatoryCompany></SignatoryCompany></Company><Counterparties><Counterparty><Country></Country><Name></Name><Address></Address><INN></INN><Phones><Phone></Phone></Phones><SignatoryCounterparty></SignatoryCounterparty></Counterparty></Counterparties></Parties></Document>

            Текст документа:
            """;

    private final ImanProperties props;
    private final RestClient restClient;
    private final DocumentBuilderFactory dbf;

    public ImanDocumentEnricher(ImanProperties props) {
        this.props = props;
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
                "parts", List.of(Map.of("type", "text", "text", PROMPT + documentText)),
                "messageId", UUID.randomUUID().toString(),
                "contextId", UUID.randomUUID().toString());

        return Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "message/send",
                "params", Map.of(
                        "message", message,
                        "configuration", Map.of("acceptedOutputModes", List.of("text")),
                        "skillId", "default"));
    }

    private String agentText(JsonNode response) {
        if (response == null) {
            throw new ImanUnavailableException("iman: пустой ответ");
        }
        if (response.has("error")) {
            throw new ImanUnavailableException("iman: jsonrpc error "
                    + response.path("error").path("code").asInt() + ": "
                    + response.path("error").path("message").asString(""));
        }

        JsonNode result = response.path("result");

        String s = firstText(result.path("parts"));
        if (s != null) return s;

        s = firstText(result.path("status").path("message").path("parts"));
        if (s != null) return s;

        String fallback = null;
        StringBuilder names = new StringBuilder();
        for (JsonNode artifact : result.path("artifacts")) {
            String name = artifact.path("name").asString("");
            names.append(name).append(", ");
            if ("reasoning".equalsIgnoreCase(name)) {
                continue;
            }
            String text = firstText(artifact.path("parts"));
            if (text == null) {
                continue;
            }
            if (text.contains("<Document")) {
                return text;
            }
            if (fallback == null) {
                fallback = text;
            }
        }

        if (fallback != null) {
            return fallback;
        }

        String state = result.path("status").path("state").asString("");
        throw new ImanUnavailableException("iman: нет текстовых parts (state=" + state
                + ", artifacts=[" + names + "]): " + abbreviate(response.toString()));
    }

    /** Первый непустой текстовый part; type или kind = "text". */
    private String firstText(JsonNode parts) {
        for (JsonNode part : parts) {
            JsonNode text = part.path("text");
            if (!text.isTextual() || text.asString().isBlank()) {
                continue;
            }
            String kind = part.path("type").asString(part.path("kind").asString(""));
            if (kind.isEmpty() || "text".equals(kind)) {
                return text.asString();
            }
        }
        return null;
    }

    /** Вырезает <Document>...</Document> из ответа агента (в т.ч. из markdown-заборов ```xml). */
    private String extractXml(String agentText) {
        String cleaned = agentText.replace("```xml", "").replace("```", "").trim();
        int start = cleaned.indexOf("<Document>");
        int end = cleaned.lastIndexOf("</Document>");
        if (start < 0 || end < start) {
            throw new ImanUnavailableException(
                    "iman: нет <Document>...</Document> в ответе: " + abbreviate(cleaned));
        }
        return cleaned.substring(start, end + "</Document>".length());
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