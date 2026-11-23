package gr.pnet.camara.qualityondemand.config;

import java.time.Clock;
import java.util.concurrent.Executor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableConfigurationProperties(QodProperties.class)
public class QodConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    // Notifications to sinks go out on their own threads, so an API response or the expiry run never waits for one
    @Bean
    Executor sinkNotificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("sink-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }
}
