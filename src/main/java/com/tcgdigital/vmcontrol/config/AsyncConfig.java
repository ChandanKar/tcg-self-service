package com.tcgdigital.vmcontrol.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean(name = "syncExecutor")
    public Executor syncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("vm-sync-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        // When queue is full, calling thread runs the task instead of rejecting
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Executes the individual VM start/stop steps of a single operation. A group/environment
     * operation submits every VM that has no unmet dependency to this pool at once, so
     * independent VMs start (and poll their AWS status checks) concurrently rather than one
     * blocking the next. {@code vm.operations.parallelism} caps how many run at a time across
     * all executions; {@code vm.operations.per-execution-parallelism} caps one execution's share
     * (M10), so a 40-VM Start All cannot hold every thread while a one-VM start elsewhere waits.
     * With the defaults (20 / 5) four executions run side by side at full speed.
     * VMs with dependencies still wait for their prerequisites.
     */
    @Bean(name = "vmOperationExecutor")
    public Executor vmOperationExecutor(@Value("${vm.operations.parallelism:20}") int parallelism) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(parallelism);
        executor.setMaxPoolSize(parallelism);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("vm-op-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        // Queue full + all threads busy: run on the submitting thread rather than reject —
        // degrades to partial parallelism, never drops a VM.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Runs EKS node-group start/stop calls and their polling (M10), which used the JVM-wide
     * ForkJoinPool.commonPool shared with everything else.
     */
    @Bean(name = "eksOperationExecutor")
    public Executor eksOperationExecutor(@Value("${eks.operations.parallelism:10}") int parallelism) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(parallelism);
        executor.setMaxPoolSize(parallelism);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("eks-op-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Backs {@code VmOperationsService.executeOperationAsync} — one thread per environment
     * operation, held for the operation's entire duration (it blocks on the per-VM waves via
     * {@code CompletableFuture.join}). Kept separate from the default {@code @Async} executor
     * (shared by audit logging and email) so a burst of concurrent environment operations can't
     * starve unrelated fire-and-forget work, or vice versa. Queue is large rather than relying
     * on {@code CallerRunsPolicy}, since the caller here is the HTTP request thread committing
     * the transaction — running the task on it would block the request for the whole operation.
     */
    @Bean(name = "operationExecutionExecutor")
    public Executor operationExecutionExecutor(@Value("${vm.operations.max-concurrent:20}") int maxConcurrent) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrent);
        executor.setMaxPoolSize(maxConcurrent);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("vm-op-exec-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.initialize();
        return executor;
    }

    /**
     * Backs fire-and-forget notification work (audit logging, email) so it never queues behind
     * — or blocks — VM operation execution on the shared default executor.
     */
    @Bean(name = "notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("notify-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
