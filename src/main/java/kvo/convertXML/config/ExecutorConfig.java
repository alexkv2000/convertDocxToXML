package kvo.convertXML.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ExecutorConfig {

    @Bean("docWorkers")
    public ThreadPoolTaskExecutor docWorkers(
            @Value("${app.exec.light.core:16}") int core,
            @Value("${app.exec.light.max:16}") int max,
            @Value("${app.exec.light.queue:0}") int queue) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(core);
        ex.setMaxPoolSize(max);
        ex.setQueueCapacity(queue);
        ex.setThreadNamePrefix("doc-worker-");
        return ex;
    }

    @Bean("docHeavyWorkers")
    public ThreadPoolTaskExecutor docHeavyWorkers(
            @Value("${app.exec.heavy.core:2}") int core,
            @Value("${app.exec.heavy.max:2}") int max,
            @Value("${app.exec.heavy.queue:500}") int queue) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(core);
        ex.setMaxPoolSize(max);
        ex.setQueueCapacity(queue);
        ex.setThreadNamePrefix("doc-heavy-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return ex;
    }
}