package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.ScheduledJobLock;
import com.tcgdigital.vmcontrol.repository.ScheduledJobLockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Service
public class ScheduledJobLockService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobLockService.class);

    private final ScheduledJobLockRepository lockRepository;
    private final String ownerId;

    @Value("${scheduler.lock.default-ttl-ms:900000}")
    private long defaultTtlMs;

    public ScheduledJobLockService(ScheduledJobLockRepository lockRepository) {
        this.lockRepository = lockRepository;
        this.ownerId = resolveOwnerId();
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
