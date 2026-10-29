package kvo.convertxml.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Единая точка регистрации метрик Micrometer.
 * Экспорт: GET /actuator/prometheus → scrape Prometheus → Grafana.
 * <p>
 * Все имена с префиксом docx_, чтобы не смешиваться со стандартными
 * jvm_/http_server_requests_ метриками actuator.
 */
@Component
public class ServiceMetrics {

    private final MeterRegistry registry;

    // ---------- Задачи: исходы (Counter) ----------
    private final Counter tasksDone;
    private final Counter tasksTransient;
    private final Counter tasksEmpty;
    private final Counter tasksMissing;
    private final Counter tasksClaimed;
    private final Counter ingestNew;

    // ---------- Задачи: тайминги фаз (Timer) ----------
    private final Timer taskTotal;
    private final Timer taskLoad;
    private final Timer taskParse;
    private final Timer taskIman;

    // ---------- Очередь / воркеры (Gauge-значения) ----------
    private final AtomicLong workersInFlight = new AtomicLong();
    private final AtomicLong queueNew = new AtomicLong();
    private final AtomicLong queueProcessing = new AtomicLong();
    private final AtomicLong queueDone = new AtomicLong();
    private final AtomicLong queueError = new AtomicLong();
    private final AtomicLong queueOldestPendingSec = new AtomicLong();

    // ---------- LLM ----------
    private final Counter llmOk;
    private final Counter llmSlow;
    private final Counter llmBadOutput;
    private final Counter llmXmlRepair;
    private final Counter llmPromptTokens;
    private final Counter llmCompletionTokens;
    private final Timer llmDuration;
    private final AtomicLong llmInFlight = new AtomicLong();
    private final AtomicLong llmLastDurationMs = new AtomicLong(-1);
    private final AtomicLong llmPaused = new AtomicLong();
    private final AtomicLong llmConsecutiveFailures = new AtomicLong();

    // ---------- OCR ----------
    private final AtomicLong ocrServersAvailable = new AtomicLong();
    // FIX: имя без суффикса _total — Prometheus-конвенция Micrometer срезает
    // «_total» у gauge (тот экспортировался как docx_ocr_servers), из-за чего
    // запрос docx_ocr_servers_total в Grafana никогда не находил серию.
    private final AtomicLong ocrServersRegistered = new AtomicLong();
    private final Counter ocrUnavailable;

    // FIX: Micrometer Gauge держит наблюдаемый объект через WeakReference.
    // Лямбда s::isReady, переданная в ocrRegisterServer, больше никем не держалась —
    // первый GC собирал её, и docx_ocr_server_up навсегда становился NaN.
    // Список — сильная ссылка на время жизни приложения.
    private final List<Supplier<Boolean>> ocrUpRefs = new ArrayList<>();

    public ServiceMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.tasksDone = registry.counter("docx_tasks_total", "outcome", "done");
        this.tasksTransient = registry.counter("docx_tasks_total", "outcome", "transient");
        this.tasksEmpty = registry.counter("docx_tasks_total", "outcome", "empty");
        this.tasksMissing = registry.counter("docx_tasks_total", "outcome", "missing");
        this.tasksClaimed = registry.counter("docx_tasks_claimed_total");
        this.ingestNew = registry.counter("docx_ingest_new_total");

        this.taskTotal = registry.timer("docx_task_duration_seconds", "phase", "total");
        this.taskLoad = registry.timer("docx_task_duration_seconds", "phase", "load");
        this.taskParse = registry.timer("docx_task_duration_seconds", "phase", "parse");
        this.taskIman = registry.timer("docx_task_duration_seconds", "phase", "iman");

        registry.gauge("docx_workers_in_flight", workersInFlight, AtomicLong::doubleValue);
        registry.gauge("docx_queue_depth", Tags.of("status", "new"), queueNew, AtomicLong::doubleValue);
        registry.gauge("docx_queue_depth", Tags.of("status", "processing"), queueProcessing, AtomicLong::doubleValue);
        registry.gauge("docx_queue_depth", Tags.of("status", "done"), queueDone, AtomicLong::doubleValue);
        registry.gauge("docx_queue_depth", Tags.of("status", "error"), queueError, AtomicLong::doubleValue);
        registry.gauge("docx_queue_oldest_pending_seconds", queueOldestPendingSec, AtomicLong::doubleValue);

        this.llmOk = registry.counter("docx_llm_requests_total", "outcome", "success");
        this.llmSlow = registry.counter("docx_llm_slow_total");
        this.llmBadOutput = registry.counter("docx_llm_bad_output_total");
        this.llmXmlRepair = registry.counter("docx_llm_xml_repair_total");
        this.llmPromptTokens = registry.counter("docx_llm_tokens_total", "type", "prompt");
        this.llmCompletionTokens = registry.counter("docx_llm_tokens_total", "type", "completion");
        this.llmDuration = registry.timer("docx_llm_duration_seconds");
        registry.gauge("docx_llm_in_flight", llmInFlight, AtomicLong::doubleValue);
        registry.gauge("docx_llm_last_duration_ms", llmLastDurationMs, AtomicLong::doubleValue);
        registry.gauge("docx_llm_paused", llmPaused, AtomicLong::doubleValue);
        registry.gauge("docx_llm_consecutive_failures", llmConsecutiveFailures, AtomicLong::doubleValue);

        this.ocrUnavailable = registry.counter("docx_ocr_unavailable_total");
        registry.gauge("docx_ocr_servers_available", ocrServersAvailable, AtomicLong::doubleValue);
        registry.gauge("docx_ocr_servers_registered", ocrServersRegistered, AtomicLong::doubleValue);
    }

    // =====================================================================
    // Задачи
    // =====================================================================

    public void taskDone() {
        tasksDone.increment();
    }

    /**
     * outcome=failed с уточнением kind: llm_bad_output | llm_config | error.
     */
    public void taskFailed(String kind) {
        registry.counter("docx_tasks_total", "outcome", "failed", "kind", kind).increment();
    }

    /**
     * outcome=rejected с уточнением причины: bad_format | empty_ocr | oversized | poison | zombie.
     */
    public void taskRejected(String reason) {
        registry.counter("docx_tasks_total", "outcome", "rejected", "reason", reason).increment();
    }

    public void taskTransient() {
        tasksTransient.increment();
    }

    public void taskEmpty() {
        tasksEmpty.increment();
    }

    public void taskMissing(long n) {
        tasksMissing.increment(n);
    }

    public void tasksClaimed(long n) {
        tasksClaimed.increment(n);
    }

    public void ingestAdded(long n) {
        ingestNew.increment(n);
    }

    public void recordTaskTotal(long ms) {
        taskTotal.record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordTaskLoad(long ms) {
        taskLoad.record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordTaskParse(long ms) {
        taskParse.record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordTaskIman(long ms) {
        taskIman.record(ms, TimeUnit.MILLISECONDS);
    }

    public void workersInFlight(long v) {
        workersInFlight.set(v);
    }

    // =====================================================================
    // Очередь (значения обновляет QueueMetrics по расписанию)
    // Статусы doc_documents: 0=new, 1=processing, 2=done, 3=error.
    // =====================================================================

    public void queueDepth(int status, long v) {
        switch (status) {
            case 0 -> queueNew.set(v);
            case 1 -> queueProcessing.set(v);
            case 2 -> queueDone.set(v);
            case 3 -> queueError.set(v);
            default -> { /* неизвестный статус — игнорируем */ }
        }
    }

    public void queueOldestPendingSec(long v) {
        queueOldestPendingSec.set(v);
    }

    // =====================================================================
    // LLM
    // =====================================================================

    /**
     * Успешный /chat/completions: латентность + расход токенов (usage может быть -1).
     */
    public void llmSuccess(long elapsedMs, int promptTokens, int completionTokens) {
        llmOk.increment();
        llmDuration.record(elapsedMs, TimeUnit.MILLISECONDS);
        llmLastDurationMs.set(elapsedMs);
        if (promptTokens >= 0) {
            llmPromptTokens.increment(promptTokens);
        }
        if (completionTokens >= 0) {
            llmCompletionTokens.increment(completionTokens);
        }
    }

    /**
     * kind: server_error | network_error | no_slot | token_refresh.
     */
    public void llmRequestError(String kind) {
        registry.counter("docx_llm_requests_total", "outcome", kind).increment();
    }

    public void llmSlow() {
        llmSlow.increment();
    }

    public void llmBadOutput() {
        llmBadOutput.increment();
    }

    public void llmXmlRepair() {
        llmXmlRepair.increment();
    }

    public void llmInFlightStart() {
        llmInFlight.incrementAndGet();
    }

    public void llmInFlightEnd() {
        llmInFlight.decrementAndGet();
    }

    public void llmPaused(boolean v) {
        llmPaused.set(v ? 1 : 0);
    }

    public void llmConsecutiveFailures(long v) {
        llmConsecutiveFailures.set(v);
    }

    // =====================================================================
    // OCR
    // =====================================================================

    /**
     * Регистрация сервера кластера: gauge занятых слотов + gauge «участвует в ротации».
     */
    public void ocrRegisterServer(String server, AtomicInteger inFlight, Supplier<Boolean> ready) {
        ocrUpRefs.add(ready);   // FIX: сильная ссылка — без неё gauge станет NaN после GC
        registry.gauge("docx_ocr_server_in_flight", Tags.of("server", server),
                inFlight, AtomicInteger::doubleValue);
        Gauge.builder("docx_ocr_server_up", ready, r -> r.get() ? 1.0 : 0.0)
                .tag("server", server)
                .register(registry);
        ocrServersRegistered.incrementAndGet();
    }

    public void ocrAvailable(long n) {
        ocrServersAvailable.set(n);
    }

    /**
     * outcome: success | transport_error | http_error | service_refusal | document_error.
     */
    public void ocrRequest(String server, String outcome, long elapsedMs) {
        registry.counter("docx_ocr_requests_total", "server", server, "outcome", outcome).increment();
        registry.timer("docx_ocr_duration_seconds", "server", server)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Свободных серверов не нашлось вовсе (задача уйдёт в повтор).
     */
    public void ocrNoServer() {
        ocrUnavailable.increment();
    }

    /**
     * Пререгистрация серий сервера: Micrometer создаёт таймеры/счётчики лениво,
     * до первого OCR-запроса docx_ocr_duration_seconds и docx_ocr_requests_total
     * в /actuator/prometheus отсутствуют — панели пустые, конфиг не проверить.
     */
    public void ocrPreregisterServer(String server) {
        registry.timer("docx_ocr_duration_seconds", "server", server);
        for (String outcome : new String[]{
                "success", "transport_error", "http_error", "service_refusal", "document_error"}) {
            registry.counter("docx_ocr_requests_total", "server", server, "outcome", outcome);
        }
    }
}