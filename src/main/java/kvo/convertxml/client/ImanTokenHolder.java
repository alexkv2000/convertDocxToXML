package kvo.convertxml.client;

import kvo.convertxml.config.ImanProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.Map;

/**
 * Живой access_token для шлюза iman. Стартует из iman.access-token (может быть пустым
 * или протухшим), обновляется через POST {iman.refresh-url} с iman.refresh-token
 * при 401/403 от /chat/completions. refresh_token — долговременный integration key,
 * хранится только в env.
 */
@Component
public class ImanTokenHolder {

    private static final Logger log = LoggerFactory.getLogger(ImanTokenHolder.class);

    /** Анти-штурм: 40 воркеров одновременно словили 401 — refresh дёргаем не чаще раза в 30 с. */
    private static final long MIN_REFRESH_INTERVAL_MS = 30_000;

    private final ImanProperties props;
    private final RestClient authClient = RestClient.builder().build(); // авторизация не нужна

    private String accessToken;   // guarded by this
    private long lastAttemptAt;   // guarded by this

    public ImanTokenHolder(ImanProperties props) {
        this.props = props;
        String initial = props.accessToken();
        this.accessToken = initial == null ? "" : initial;
        if (this.accessToken.isBlank() && props.refreshToken() != null && !props.refreshToken().isBlank()) {
            log.info("iman.access-token пуст — первый запрос пойдёт без токена и обновится через auth/refresh");
        }
    }

    /** Текущий токен для заголовка Authorization: Bearer. */
    public synchronized String current() {
        return accessToken;
    }

    /**
     * Вызывается при 401/403 на запросе, ушедшем с токеном {@code failedToken}.
     * true  — токен актуален (обновили сейчас или это сделал другой поток) — запрос можно повторять;
     * false — refresh не прошёл (аккаунт/ключ отключены) — включать паузу.
     */
    public synchronized boolean refreshIfStale(String failedToken) {
        if (!failedToken.equals(accessToken)) {
            return true;                       // другой поток уже обновил — просто повторяем
        }
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < MIN_REFRESH_INTERVAL_MS) {
            return false;                      // недавно пробовали и не вышло — не дёргаем
        }
        lastAttemptAt = now;
        if (props.refreshToken() == null || props.refreshToken().isBlank()) {
            log.warn("auth/refresh: iman.refresh-token не настроен — авто-refresh невозможен");
            return false;
        }
        try {
            JsonNode r = authClient.post()
                    .uri(URI.create(props.refreshUrl()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("refresh_token", props.refreshToken()))
                    .retrieve()
                    .body(JsonNode.class);
            String token = r == null ? null : r.path("access_token").asString(null);
            if (token == null || token.isBlank()) {
                log.warn("auth/refresh: в ответе нет access_token: {}", r);
                return false;
            }
            accessToken = token;
            log.info("auth/refresh: access_token обновлён");
            String rotated = r.path("refresh_token").asString("");
            if (!rotated.isBlank() && !rotated.equals(props.refreshToken())) {
                // если шлюз ротирует refresh_token, старый со временем перестанет работать
                String prefix = rotated.substring(0, Math.min(8, rotated.length()));
                log.warn("auth/refresh вернул НОВЫЙ refresh_token — сохраните его и обновите "
                        + "IMAN_REFRESH_TOKEN (префикс): {}…", prefix);
            }
            return true;
        } catch (Exception e) {
            String reason = e.getMessage();
            log.warn("auth/refresh не прошёл: {}", reason);
            return false;
        }
    }
}