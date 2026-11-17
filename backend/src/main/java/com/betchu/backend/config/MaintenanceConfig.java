package com.betchu.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class MaintenanceConfig {
  @Bean
  ThreadPoolTaskScheduler taskScheduler() {
    var scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(4);
    scheduler.setThreadNamePrefix("maintenance-");
    scheduler.setRemoveOnCancelPolicy(true);
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(30);
    scheduler.setErrorHandler(
        error ->
            org.slf4j.LoggerFactory.getLogger(MaintenanceConfig.class)
                .error(
                    "Maintenance task failed; next scheduled attempt will retry ({})",
                    error.getClass().getSimpleName()));
    return scheduler;
  }
}
