package kvo.convertxml.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** Пауза при недоступности OCR-сервиса: после 3 сбоев подряд 60 с не пускаем задачи,
 *  затем очередной вызов работает как проба. */
@Component
public class OcrAvailability {

    private static final Logger log = LoggerFactory.getLogger(OcrAvailability.class);
    private static final int FAILS_TO_PAUSE = 3;
    private static final long PAUSE_MS = Duration.ofSeconds(60).toMillis();

    private final AtomicInteger fails = new AtomicInteger();
    private volatile long pausedUntil;

    public boolean isPaused() {
        return System.currentTimeMillis() < pausedUntil;
    }

    public void success() {
        fails.set(0);
        pausedUntil = 0L;
    }

    public void failure() {
        if (fails.incrementAndGet() >= FAILS_TO_PAUSE) {
            pausedUntil = System.currentTimeMillis() + PAUSE_MS;
            log.warn("OCR: {} сбоев подряд — пауза на {} с, задачи со сканами уходят на повтор",
                    FAILS_TO_PAUSE, PAUSE_MS / 1000);
        }
    }
}