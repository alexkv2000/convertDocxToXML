package kvo.convertxml.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ExecutorConfig {

    @Bean("docHeavyWorkers")
    public ThreadPoolTaskExecutor docHeavyWorkers(
            @Value("${app.exec.heavy.core:1}") int core,
            @Value("${app.exec.heavy.max:1}") int max,
            @Value("${app.exec.heavy.queue:4}") int queue) {   // дефолт 500 → 4 (на каждый сервер doc-app)
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(core);
        ex.setMaxPoolSize(max);
        ex.setQueueCapacity(queue);
        ex.setThreadNamePrefix("doc-heavy-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(70);
        // CallerRunsPolicy убрана: иначе TaskRejectedException не возникнет и catch в поллере мёртв
        return ex;
    }

    @Bean("docWorkers")
    public ThreadPoolTaskExecutor docWorkers(
            @Value("${app.exec.light.core:25}") int core,
            @Value("${app.exec.light.max:25}") int max,
            @Value("${app.exec.light.queue:0}") int queue) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(core);
        ex.setMaxPoolSize(max);
        ex.setQueueCapacity(queue);
        ex.setThreadNamePrefix("doc-worker-");
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(60);
        return ex;
    }
}