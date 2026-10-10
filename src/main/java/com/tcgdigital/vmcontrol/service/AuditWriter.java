package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditLog;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists audit rows (E11-T01, H9). A separate bean so that {@code @Async} and
 * {@code REQUIRES_NEW} actually apply: AuditService used to call its own annotated methods, which
 * bypassed the proxy, so audit rows ran in (and rolled back with) the caller's transaction.
 */
@Service
public class AuditWriter {

    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);

    private final AuditLogRepository auditLogRepository;

    public AuditWriter(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /** Write one row on the notification executor, in its own transaction; never throws. */
    @Async("notificationExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(AuditLog entry) {
        try {
            auditLogRepository.save(entry);
            log.debug("Audit log created: {} - {} on {}", entry.getAction(), entry.getUserId(), entry.getTargetName());
        } catch (Exception e) {
            log.error("Failed to create audit log: {}", e.getMessage());
        }
    }

    /** Write one row now, in its own transaction; returns it, or null if it could not be saved. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditLog writeSync(AuditLog entry) {
        try {
            return auditLogRepository.save(entry);
        } catch (Exception e) {
            log.error("Failed to create sync audit log: {}", e.getMessage());
            return null;
        }
    }
}
