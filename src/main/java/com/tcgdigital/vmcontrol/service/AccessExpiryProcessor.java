package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;

/**
 * Expires one access grant in its own transaction, so one failing row neither rolls back nor
 * blocks the rest of the nightly batch (C4). A separate bean so the REQUIRES_NEW proxy applies.
 */
@Service
public class AccessExpiryProcessor {

    private static final Logger log = LoggerFactory.getLogger(AccessExpiryProcessor.class);

    private final EnvironmentAccessRepository accessRepository;
    private final VmGroupRepository vmGroupRepository;
    private final NotificationService notificationService;

    public AccessExpiryProcessor(EnvironmentAccessRepository accessRepository,
                                 VmGroupRepository vmGroupRepository,
                                 NotificationService notificationService) {
        this.accessRepository = accessRepository;
        this.vmGroupRepository = vmGroupRepository;
        this.notificationService = notificationService;
    }

    /**
     * @return true when the grant was expired; false when it is no longer an active, past-due
     *         grant (revoked, extended or expired elsewhere since the ids were listed)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expireOne(String accessId) {
        EnvironmentAccess access = accessRepository.findById(accessId).orElse(null);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        if (access == null || access.getStatus() != AccessStatus.ACTIVE
                || access.getExpiresAt() == null || access.getExpiresAt().after(now)) {
            return false;
        }

        access.setStatus(AccessStatus.EXPIRED);
        accessRepository.save(access);

        String userId = access.getUser().getUserId();
        String environmentId = access.getEnvironment().getEnvironmentId();
        String scopeLabel = EnvironmentAccessService.scopeLabel(access, vmGroupRepository);
        log.info("Access {} expired for user {} on environment {}", accessId, userId, environmentId);

        AfterCommit.run(() -> notificationService.notifyAccessExpired(userId, scopeLabel, environmentId, accessId));
        return true;
    }
}
