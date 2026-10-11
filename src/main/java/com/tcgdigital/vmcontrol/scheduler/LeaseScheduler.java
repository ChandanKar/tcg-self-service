package com.tcgdigital.vmcontrol.scheduler;

import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.service.LeaseExpiryProcessor;
import com.tcgdigital.vmcontrol.service.LeaseService;
import com.tcgdigital.vmcontrol.service.ScheduledJobLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Lease sweep (E15-T05), every lease.sweep.interval-ms under the cluster job lock: warnings first,
 * then every overdue lease. Overdue means ends_at has passed, so leases that ended while the
 * platform was down are processed on the first run after a restart. One failing lease does not
 * stop the others. Absent unless lease.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "lease.enabled", havingValue = "true")
public class LeaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(LeaseScheduler.class);

    static final String JOB_NAME = "lease-sweep";

    private final LeaseService leaseService;
    private final LeaseExpiryProcessor processor;
    private final EnvironmentLeaseRepository leaseRepository;
    private final ScheduledJobLockService jobLockService;

    public LeaseScheduler(LeaseService leaseService, LeaseExpiryProcessor processor,
                          EnvironmentLeaseRepository leaseRepository, ScheduledJobLockService jobLockService) {
        this.leaseService = leaseService;
        this.processor = processor;
        this.leaseRepository = leaseRepository;
        this.jobLockService = jobLockService;
    }

    @Scheduled(fixedDelayString = "${lease.sweep.interval-ms:60000}", initialDelayString = "60000")
    public void run() {
        jobLockService.runLocked(JOB_NAME, Duration.ofMinutes(5), this::sweep);
    }

    void sweep() {
        try {
            leaseService.processLeaseWarnings();
        } catch (RuntimeException e) {
            log.warn("Lease warnings failed", e);
        }
        for (String leaseId : leaseRepository.findOverdueIds(Timestamp.from(Instant.now()))) {
            try {
                processor.expire(leaseId);
            } catch (RuntimeException e) {
                log.warn("Expiring lease {} failed; retrying next sweep", leaseId, e);
            }
        }
    }
}
