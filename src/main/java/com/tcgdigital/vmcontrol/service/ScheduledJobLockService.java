package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.ScheduledJobLock;
import com.tcgdigital.vmcontrol.repository.ScheduledJobLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class ScheduledJobLockService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobLockService.class);

    private final ScheduledJobLockRepository lockRepository;
    private final String ownerId;

    @Value("${scheduler.lock.default-ttl-ms:900000}")
    private long defaultTtlMs;

    private final Environment environment;
    /** runLocked calls tryAcquire/release on this instance, bypassing the @Transactional proxy. */
    private final TransactionTemplate transactions;

    public ScheduledJobLockService(ScheduledJobLockRepository lockRepository, Environment environment,
                                   PlatformTransactionManager transactionManager) {
        this.lockRepository = lockRepository;
        this.environment = environment;
        this.transactions = new TransactionTemplate(transactionManager);
        this.ownerId = resolveOwnerId();
    }

    /** {@link #runLocked(String, Duration, Runnable)} with the default TTL (scheduler.lock.default-ttl-ms). */
    public boolean runLocked(String jobName, Runnable job) {
        return runLocked(jobName, Duration.ofMillis(defaultTtlMs), job);
    }

    /**
     * Run a scheduled job on at most one instance at a time (M6). The TTL is
     * scheduler.lock.ttl.&lt;jobName&gt;-ms when set, else {@code defaultTtl}; it must outlast a
     * worst-case run. Not @Transactional: the job does not run inside the lock's transaction.
     * A failing job is logged and its lock released, so the next tick runs.
     *
     * @return false when another instance (or a still-running tick) holds the lock; that lock is
     *         left alone
     */
    public boolean runLocked(String jobName, Duration defaultTtl, Runnable job) {
        long ttlMs = environment.getProperty("scheduler.lock.ttl." + jobName + "-ms", Long.class, defaultTtl.toMillis());
        if (!Boolean.TRUE.equals(transactions.execute(status -> tryAcquire(jobName, ttlMs)))) {
            log.debug("Scheduled job {} skipped: lock held elsewhere", jobName);
            return false;
        }
        try {
            job.run();
        } catch (RuntimeException e) {
            log.error("Scheduled job {} failed", jobName, e);
        } finally {
            transactions.executeWithoutResult(status -> release(jobName));
        }
        return true;
    }

    @Transactional
    public boolean tryAcquire(String lockName) {
        return tryAcquire(lockName, defaultTtlMs);
    }

    @Transactional
    public boolean tryAcquire(String lockName, long ttlMs) {
        Timestamp now = Timestamp.from(Instant.now());
        Timestamp until = Timestamp.from(Instant.now().plusMillis(ttlMs));

        Optional<ScheduledJobLock> existing = lockRepository.findForUpdate(lockName);
        if (existing.isPresent()) {
            ScheduledJobLock lock = existing.get();
            if (lock.getLockedUntil().after(now)) {
                log.debug("Scheduled job lock {} held by {} until {}", lockName, lock.getLockedBy(), lock.getLockedUntil());
                return false;
            }
            lock.setLockedBy(ownerId);
            lock.setAcquiredAt(now);
            lock.setLockedUntil(until);
            lockRepository.save(lock);
            return true;
        }

        ScheduledJobLock lock = new ScheduledJobLock();
        lock.setLockName(lockName);
        lock.setLockedBy(ownerId);
        lock.setAcquiredAt(now);
        lock.setLockedUntil(until);
        try {
            lockRepository.saveAndFlush(lock);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("Scheduled job lock {} was acquired concurrently", lockName);
            return false;
        }
    }

    @Transactional
    public void release(String lockName) {
        lockRepository.findForUpdate(lockName).ifPresent(lock -> {
            if (ownerId.equals(lock.getLockedBy())) {
                lock.setLockedUntil(Timestamp.from(Instant.now()));
                lockRepository.save(lock);
            }
        });
    }

    public String getOwnerId() {
        return ownerId;
    }

    private String resolveOwnerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "-" + ProcessHandle.current().pid();
        } catch (Exception e) {
            return "vmcontrol-" + ProcessHandle.current().pid();
        }
    }
}
