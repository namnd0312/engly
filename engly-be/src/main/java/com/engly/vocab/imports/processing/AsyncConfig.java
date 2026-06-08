package com.engly.vocab.imports.processing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables async processing + scheduling for the import subsystem and defines the SINGLE
 * bounded worker that runs CSV imports.
 *
 * core=1/max=1 → at most one import processes at a time (serialized). Combined with the DB
 * lease (claim), this is safe for a single instance and forward-compatible to N instances.
 * CallerRunsPolicy bounds the queue: if the small queue fills, the enqueuing thread runs the
 * task itself rather than dropping it (acceptable under the single-worker design).
 *
 * @EnableScheduling powers the crash-recovery sweeper + upload cleanup (phase-05/06).
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {

    @Bean("csvImportExecutor")
    public ThreadPoolTaskExecutor csvImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("csv-import-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
