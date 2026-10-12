package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentLockRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Records that AWS took Spot capacity back (E31-T07): a 'spot_interruption' state-history row (so
 * reports count them), a SPOT_INTERRUPTION audit row, and a notification to the environment's
 * admins and the current lock holder. Used by state sync for EC2 Spot VMs and by the Spot
 * fallback for node-group twins.
 */
@Service
public class SpotInterruptionRecorder {

    public static final String CHANGE_SOURCE = "spot_interruption";

    private final VmRepository vmRepository;
    private final VmStateHistoryRepository stateHistoryRepository;
    private final EnvironmentLockRepository lockRepository;
    private final AuditService auditService;
    private final NotificationService notificationService;

    public SpotInterruptionRecorder(VmRepository vmRepository, VmStateHistoryRepository stateHistoryRepository,
                                    EnvironmentLockRepository lockRepository, AuditService auditService,
                                    NotificationService notificationService) {
        this.vmRepository = vmRepository;
        this.stateHistoryRepository = stateHistoryRepository;
        this.lockRepository = lockRepository;
        this.auditService = auditService;
        this.notificationService = notificationService;
    }

    public void record(String vmId, String vmName, String environmentId, String environmentName,
                       VmStatus from, VmStatus to, String details) {
        stateHistoryRepository.save(VmStateHistory.builder()
                .historyId(UUID.randomUUID().toString())
                .vm(vmRepository.getReferenceById(vmId))
                .previousStatus(from)
                .newStatus(to)
                .changeSource(CHANGE_SOURCE)
                .details(details)
                .build());
        auditService.logEnvironmentAction(null, AuditAction.SPOT_INTERRUPTION, environmentId, environmentName,
                "vm", vmId, vmName, details);
        String lockHolder = lockRepository.findByEnvironmentEnvironmentIdAndIsActiveTrue(environmentId)
                .map(l -> l.getLockedByUserId()).orElse(null);
        notificationService.notifySpotInterrupted(environmentId, environmentName, vmId, vmName, lockHolder, details);
    }
}
