package kvo.convertxml.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * «Предохранитель» доступности LLM: после серии подряд сетевых сбоев
 * приостанавливает опрос БД, пока LLM не ответит на проверочный запрос.
 */
@Component
public class LlmAvailability {

    private static final Logger log = LoggerFactory.getLogger(LlmAvailability.class);

    /** сколько подряд сетевых сбоев = «эндпоинт лежит» */
    private static final int FAILURES_TO_PAUSE = 3;
    /** как часто отправлять пробу во время простоя */
    private static final Duration PROBE_EVERY = Duration.ofSeconds(60);

    private int consecutiveFailures;
    private long pausedUntil;   // epoch millis; 0 = паузы нет
    private long nextProbeAt;

    /** true — LLM считается недоступным, опрос БД приостановлен */
    public synchronized boolean isPaused() {
        return pausedUntil > System.currentTimeMillis();
    }

    /** разрешает пробу не чаще раза в PROBE_EVERY */
    public synchronized boolean shouldProbe() {
        long now = System.currentTimeMillis();
        if (now < nextProbeAt) return false;
        nextProbeAt = now + PROBE_EVERY.toMillis();
        return true;
    }

    public synchronized void success() {
        boolean wasPaused = pausedUntil > 0;
        consecutiveFailures = 0;
        pausedUntil = 0;
        nextProbeAt = 0;
        if (wasPaused) log.info("LLM ответил: возобновляем опрос и обработку задач");
    }

    public synchronized void failure() {
        consecutiveFailures++;
        if (consecutiveFailures >= FAILURES_TO_PAUSE) {
            long now = System.currentTimeMillis();
            if (pausedUntil == 0) {
                log.warn("LLM недоступен ({} сетевых сбоев подряд): приостанавливаем опрос БД, проба каждые {} c",
                        consecutiveFailures, PROBE_EVERY.toSeconds());
            }
            pausedUntil = now + PROBE_EVERY.toMillis();
        }
    }
}