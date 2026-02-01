package com.mortitech.completablefuture.level5_spring.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * ═══════════════════════════════════════════════════════════════════════════════
 * LEVEL 5 — SPRING BOOT INTEGRATION: Executor Configuration
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * WHY CUSTOM EXECUTORS IN SPRING:
 * 1. Default ForkJoinPool.commonPool() is shared across JVM - risky in production
 * 2. Custom executors give you control over thread naming, pool size, queue behavior
 * 3. Different workloads need different executor configurations
 * 4. Proper monitoring and graceful shutdown handling
 *
 * IMPORTANT: @EnableAsync is only needed if you use @Async annotation.
 * For manual CompletableFuture usage, you just need the executor beans.
 */
@Configuration
@EnableAsync // Only needed if using @Async annotation
public class AsyncConfig {

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * Executor for I/O-bound operations (database calls, HTTP requests)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * SIZING GUIDELINES:
     * - Core pool: minimum threads always kept alive
     * - Max pool: maximum threads when queue is full
     * - Queue capacity: tasks waiting when all core threads busy
     *
     * For I/O-bound work:
     * - Higher thread count (2-4× CPU cores)
     * - Threads spend most time waiting, not computing
     */
    @Bean(name = "ioTaskExecutor")
    public Executor ioTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int cpuCores = Runtime.getRuntime().availableProcessors();

        // Core threads: always available for immediate task execution
        executor.setCorePoolSize(cpuCores * 2);

        // Max threads: upper limit when queue is full
        executor.setMaxPoolSize(cpuCores * 4);

        // Queue capacity: buffer for bursts; tasks wait here before spawning new threads
        // Set lower to spawn threads faster, higher to reduce thread churn
        executor.setQueueCapacity(100);

        // Thread naming - CRITICAL for debugging and monitoring
        executor.setThreadNamePrefix("io-async-");

        // Rejection policy when both queue and max threads are exhausted
        // CallerRunsPolicy: caller thread executes the task (backpressure)
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // Wait for tasks to complete on shutdown
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * Executor for CPU-bound operations (calculations, transformations)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * For CPU-bound work:
     * - Thread count ≈ CPU cores
     * - More threads = context switching overhead with no benefit
     */
    @Bean(name = "cpuTaskExecutor")
    public Executor cpuTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        int cpuCores = Runtime.getRuntime().availableProcessors();

        // For CPU-bound, keep close to core count
        executor.setCorePoolSize(cpuCores);
        executor.setMaxPoolSize(cpuCores);

        // Smaller queue - we want tasks to execute immediately
        executor.setQueueCapacity(50);

        executor.setThreadNamePrefix("cpu-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }

    /**
     * ─────────────────────────────────────────────────────────────────────────
     * Virtual Thread Executor (Java 21+)
     * ─────────────────────────────────────────────────────────────────────────
     *
     * Virtual threads are ideal for I/O-bound operations:
     * - Extremely lightweight (can have millions)
     * - No pool sizing decisions needed
     * - Perfect for high-concurrency scenarios
     *
     * NOTE: Don't use for CPU-bound work - they're designed for blocking I/O.
     */
    @Bean(name = "virtualThreadExecutor")
    public Executor virtualThreadExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }
}
