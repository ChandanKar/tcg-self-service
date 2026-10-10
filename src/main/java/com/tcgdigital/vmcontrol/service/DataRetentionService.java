package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.EmailLogRepository;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Deletes old rows from the tables that otherwise grow forever (E11-T07, M37): audit_log,
 * notification (read and unread kept for different periods), vm_state_history and email_log.
 * Each table is pruned in batches of retention.batch-size, one short transaction per batch, so
 * a large backlog never holds long locks. Run by DataRetentionScheduler only when
 * retention.enabled=true.
 */
@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    private final AuditLogRepository auditLogRepository;
    private final NotificationRepository notificationRepository;
    private final VmStateHistoryRepository stateHistoryRepository;
    private final EmailLogRepository emailLogRepository;
    private final AuditService auditService;
    private final TransactionTemplate batchTransaction;

    @Value("${retention.audit-log.days:365}")
    private int auditLogDays = 365;

    @Value("${retention.notification.read-days:90}")
    private int readNotificationDays = 90;

    @Value("${retention.notification.unread-days:180}")
    private int unreadNotificationDays = 180;

    @Value("${retention.state-history.days:180}")
    private int stateHistoryDays = 180;

    @Value("${retention.email-log.days:180}")
    private int emailLogDays = 180;

    @Value("${retention.batch-size:5000}")
    private int batchSize = 5000;

    public DataRetentionService(AuditLogRepository auditLogRepository,
                                NotificationRepository notificationRepository,
                                VmStateHistoryRepository stateHistoryRepository,
                                EmailLogRepository emailLogRepository,
                                AuditService auditService,
                                PlatformTransactionManager transactionManager) {
        this.auditLogRepository = auditLogRepository;
        this.notificationRepository = notificationRepository;
        this.stateHistoryRepository = stateHistoryRepository;
        this.emailLogRepository = emailLogRepository;
        this.auditService = auditService;
        this.batchTransaction = new TransactionTemplate(transactionManager);
        this.batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Prune every table; returns the rows deleted per table. */
    public Map<String, Integer> purgeAll() {
        Instant now = Instant.now();
        Map<String, Integer> deleted = new LinkedHashMap<>();
        deleted.put("audit_log", purge(cutoff(now, auditLogDays), auditLogRepository::deleteOlderThan));
        deleted.put("notification_read", purge(cutoff(now, readNotificationDays), notificationRepository::deleteReadOlderThan));
        deleted.put("notification", purge(cutoff(now, unreadNotificationDays), notificationRepository::deleteOlderThan));
        deleted.put("vm_state_history", purge(cutoff(now, stateHistoryDays), stateHistoryRepository::deleteOlderThan));
        deleted.put("email_log", purge(cutoff(now, emailLogDays), emailLogRepository::deleteOlderThan));
        log.info("Data retention removed {}", deleted);
        auditService.logAction(null, AuditAction.SCHEDULED_JOB_EXECUTED, "job", "data_retention", "Data retention",
                "Deleted rows: " + deleted);
        return deleted;
    }

    private static Timestamp cutoff(Instant now, int days) {
        return Timestamp.from(now.minus(Duration.ofDays(days)));
    }

    /** Delete in batches until a batch comes back short; each batch commits on its own. */
    private int purge(Timestamp cutoff, BiFunction<Timestamp, Integer, Integer> deleteBatch) {
        int total = 0;
        int batch;
        do {
            Integer n = batchTransaction.execute(status -> deleteBatch.apply(cutoff, batchSize));
            batch = n == null ? 0 : n;
            total += batch;
        } while (batch >= batchSize);
        return total;
    }
}
