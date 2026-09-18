package kvo.convertXML.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ServiceConfig {

    /** Лёгкий пул: обычные файлы. */
    @Bean("docWorkers")
    public ThreadPoolTaskExecutor docWorkers(
            @Value("${app.worker-threads:4}") int threads) {
        return buildPool("doc-worker-", threads);
    }

    /**
     * Тяжёлый пул: отдельные 1–2 потока под гигантские файлы.
     * Очередь 0 + CallerRuns: при всплеске тяжёлых задач лишние выполняются
     * в потоке-отправителе, задачи не теряются и не ждут дольше stale-таймаута.
     */
    @Bean("docHeavyWorkers")
    public ThreadPoolTaskExecutor docHeavyWorkers(
            @Value("${app.heavy-threads:1}") int threads) {
        return buildPool("doc-heavy-", threads);
    }

    private ThreadPoolTaskExecutor buildPool(String prefix, int threads) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix(prefix);
        ex.setCorePoolSize(threads);
        ex.setMaxPoolSize(threads);
        ex.setQueueCapacity(0);
        // Страховка: если свободных потоков нет — задача выполняется в потоке
        // отправителя, а НЕ отбрасывается с исключением
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        ex.initialize();
        return ex;
    }
}
