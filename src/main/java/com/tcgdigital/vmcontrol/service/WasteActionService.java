package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.WasteActionDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.WasteAction;
import com.tcgdigital.vmcontrol.model.WasteFinding;
import com.tcgdigital.vmcontrol.model.WasteFindingStatus;
import com.tcgdigital.vmcontrol.model.WasteFindingType;
import com.tcgdigital.vmcontrol.repository.WasteActionRepository;
import com.tcgdigital.vmcontrol.repository.WasteFindingRepository;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.ec2.model.Volume;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Fixes for waste findings (E22-T04), behind waste.actions.enabled (off by default: it changes AWS
 * state). One admin proposes, another approves (never the proposer; the entity version stops a
 * double approval); the fix then runs in the background, never inside a database transaction.
 *
 * <p>DELETE_VOLUME re-checks that the volume is still unattached, snapshots it (tagged with the
 * action id and the volume's tags) and waits for the snapshot to complete before deleting the
 * volume; rollback recreates the volume from that snapshot in the same AZ with its type and tags.
 */
@Service
public class WasteActionService {

    private static final Logger log = LoggerFactory.getLogger(WasteActionService.class);

    /** The action each finding type may get; a type without one gets none (e.g. long-stopped VMs). */
    static final Map<WasteFindingType, WasteAction.Type> ACTION_FOR = new EnumMap<>(Map.of(
            WasteFindingType.UNATTACHED_VOLUME, WasteAction.Type.DELETE_VOLUME));

    private final WasteActionRepository actionRepository;
    private final WasteFindingRepository findingRepository;
    private final AwsWasteScanService aws;
    private final AuditService auditService;
    private final AfterCommit afterCommit;
    private final ObjectMapper objectMapper;

    @Value("${waste.actions.enabled:false}")
    private boolean enabled = false;

    @Value("${cost.tagging.key-prefix:tcg:}")
    private String tagKeyPrefix = "tcg:";

    /** Where approved fixes run; replaceable in tests. */
    private Executor executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "waste-action");
        t.setDaemon(true);
        return t;
    });

    /** Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public WasteActionService(WasteActionRepository actionRepository, WasteFindingRepository findingRepository,
                              AwsWasteScanService aws, AuditService auditService, AfterCommit afterCommit, ObjectMapper objectMapper) {
        this.actionRepository = actionRepository;
        this.findingRepository = findingRepository;
        this.aws = aws;
        this.auditService = auditService;
        this.afterCommit = afterCommit;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ValidationException("Waste fix actions are turned off: set waste.actions.enabled");
        }
    }

    // ============= Reads =============

    @Transactional(readOnly = true)
    public List<WasteActionDTO> list(WasteAction.Status status) {
        List<WasteAction> actions = status == null ? actionRepository.findAllByOrderByProposedAtDesc()
                : actionRepository.findByStatusOrderByProposedAtDesc(status);
        return actions.stream().map(this::toDto).toList();
    }

    // ============= Proposal and approval =============

    @Transactional
    public WasteActionDTO propose(String findingId, WasteAction.Type type, String userId) {
        requireEnabled();
        WasteFinding finding = finding(findingId);
        if (ACTION_FOR.get(finding.getFindingType()) != type) {
            throw new ValidationException(type + " is not available for a " + finding.getFindingType() + " finding");
        }
        if (finding.getStatus() != WasteFindingStatus.OPEN) {
            throw new ValidationException("Only an open finding can get a fix action");
        }
        WasteAction action = new WasteAction();
        action.setActionId(UUID.randomUUID().toString());
        action.setFindingId(findingId);
        action.setActionType(type);
        action.setProposedBy(userId);
        action.setProposedAt(now());
        actionRepository.save(action);
        finding.setStatus(WasteFindingStatus.ACTION_PENDING);
        findingRepository.save(finding);
        audit(userId, AuditAction.WASTE_ACTION_PROPOSED, action, finding, "Proposed " + type);
        return toDto(action);
    }

    /** Another admin approves; the fix then runs in the background after this commits. */
    @Transactional
    public WasteActionDTO approve(String actionId, String userId) {
        requireEnabled();
        WasteAction action = action(actionId);
        if (action.getStatus() != WasteAction.Status.PROPOSED) {
            throw new ValidationException("This action was already " + action.getStatus().name().toLowerCase().replace('_', ' '));
        }
        if (userId.equals(action.getProposedBy())) {
            throw new ValidationException("You proposed this action; another admin must approve it");
        }
        action.setStatus(WasteAction.Status.APPROVED);
        action.setApprovedBy(userId);
        action.setApprovedAt(now());
        try {
            actionRepository.saveAndFlush(action);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new ValidationException("This action was approved or changed by someone else; reload it");
        }
        audit(userId, AuditAction.WASTE_ACTION_APPROVED, action, finding(action.getFindingId()), "Approved " + action.getActionType());
        afterCommit.run(() -> executor.execute(() -> execute(actionId)));
        return toDto(action);
    }

    @Transactional
    public WasteActionDTO reject(String actionId, String userId) {
        requireEnabled();
        WasteAction action = action(actionId);
        if (action.getStatus() != WasteAction.Status.PROPOSED) {
            throw new ValidationException("Only a proposed action can be rejected");
        }
        action.setStatus(WasteAction.Status.REJECTED);
        actionRepository.save(action);
        WasteFinding finding = finding(action.getFindingId());
        finding.setStatus(WasteFindingStatus.OPEN);
        findingRepository.save(finding);
        audit(userId, AuditAction.WASTE_ACTION_REJECTED, action, finding, "Rejected " + action.getActionType());
        return toDto(action);
    }

    // ============= Execution (background, outside a transaction) =============

    void execute(String actionId) {
        WasteAction action = action(actionId);
        if (action.getStatus() != WasteAction.Status.APPROVED) {
            return;
        }
        WasteFinding finding = finding(action.getFindingId());
        action.setStatus(WasteAction.Status.EXECUTING);
        action = actionRepository.save(action);
        try {
            Map<String, Object> rollback = switch (action.getActionType()) {
                case DELETE_VOLUME -> deleteVolume(action, finding);
                default -> throw new IllegalStateException(action.getActionType() + " is not available yet");
            };
            action.setRollbackData(objectMapper.writeValueAsString(rollback));
            action.setStatus(WasteAction.Status.EXECUTED);
            action.setExecutedAt(now());
            actionRepository.save(action);
            finding.setStatus(WasteFindingStatus.RESOLVED);
            findingRepository.save(finding);
            audit(action.getApprovedBy(), AuditAction.WASTE_ACTION_EXECUTED, action, finding, "Executed " + action.getActionType());
        } catch (Exception e) {
            log.warn("Waste action {} failed: {}", actionId, e.getMessage());
            action.setStatus(WasteAction.Status.FAILED);
            action.setErrorMessage(e.getMessage());
            actionRepository.save(action);
            finding.setStatus(WasteFindingStatus.OPEN);
            findingRepository.save(finding);
            audit(action.getApprovedBy(), AuditAction.WASTE_ACTION_FAILED, action, finding, "Failed: " + e.getMessage());
        }
    }

    private Map<String, Object> deleteVolume(WasteAction action, WasteFinding finding) {
        String region = finding.getRegion();
        Volume volume = aws.describeVolume(region, finding.getResourceId());
        if (volume == null) {
            throw new IllegalStateException("The volume no longer exists");
        }
        if (!"available".equals(volume.stateAsString())) {
            throw new IllegalStateException("The volume is no longer unattached (state " + volume.stateAsString() + "); nothing was deleted");
        }
        Map<String, String> tags = new LinkedHashMap<>(AwsWasteScanService.tags(volume.tags()));
        Map<String, String> snapshotTags = new LinkedHashMap<>(tags);
        snapshotTags.put(tagKeyPrefix + "waste-action", action.getActionId());
        snapshotTags.put(tagKeyPrefix + "source-volume", volume.volumeId());
        String snapshotId = aws.createSnapshotAndWait(region, volume.volumeId(), snapshotTags);
        Map<String, Object> rollback = new LinkedHashMap<>();
        rollback.put("snapshotId", snapshotId);
        rollback.put("availabilityZone", volume.availabilityZone());
        rollback.put("volumeType", volume.volumeTypeAsString());
        rollback.put("size", volume.size());
        rollback.put("iops", volume.iops());
        rollback.put("throughput", volume.throughput());
        rollback.put("tags", tags);
        aws.deleteVolume(region, volume.volumeId());
        return rollback;
    }

    // ============= Rollback =============

    @Transactional
    public WasteActionDTO rollback(String actionId, String userId) {
        requireEnabled();
        WasteAction action = action(actionId);
        if (action.getStatus() != WasteAction.Status.EXECUTED) {
            throw new ValidationException("Only an executed action can be rolled back");
        }
        if (action.getActionType() != WasteAction.Type.DELETE_VOLUME) {
            throw new ValidationException(action.getActionType() + " cannot be rolled back");
        }
        WasteFinding finding = finding(action.getFindingId());
        Map<String, Object> data = rollbackData(action);
        @SuppressWarnings("unchecked")
        Map<String, String> tags = (Map<String, String>) data.getOrDefault("tags", Map.of());
        String restored = aws.createVolumeFromSnapshot(finding.getRegion(), (String) data.get("snapshotId"),
                (String) data.get("availabilityZone"), (String) data.get("volumeType"), number(data.get("size")),
                number(data.get("iops")), number(data.get("throughput")), tags);
        data.put("restoredVolumeId", restored);
        try {
            action.setRollbackData(objectMapper.writeValueAsString(data));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        action.setStatus(WasteAction.Status.ROLLED_BACK);
        action.setRolledBackAt(now());
        actionRepository.save(action);
        audit(userId, AuditAction.WASTE_ACTION_ROLLED_BACK, action, finding, "Rolled back: recreated volume " + restored);
        return toDto(action);
    }

    // ============= Helpers =============

    private Map<String, Object> rollbackData(WasteAction action) {
        try {
            return action.getRollbackData() == null ? new LinkedHashMap<>()
                    : objectMapper.readValue(action.getRollbackData(), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable rollback data of action " + action.getActionId(), e);
        }
    }

    private static Integer number(Object value) {
        return value instanceof Number n ? n.intValue() : null;
    }

    private WasteAction action(String actionId) {
        return actionRepository.findById(actionId).orElseThrow(() -> new ResourceNotFoundException("WasteAction", actionId));
    }

    private WasteFinding finding(String findingId) {
        return findingRepository.findById(findingId).orElseThrow(() -> new ResourceNotFoundException("WasteFinding", findingId));
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private void audit(String userId, AuditAction auditAction, WasteAction action, WasteFinding finding, String details) {
        try {
            auditService.logAction(userId, auditAction, "waste_action", action.getActionId(),
                    action.getActionType() + " " + finding.getResourceId() + " (" + finding.getRegion() + ")", details);
        } catch (Exception e) {
            log.warn("Could not audit waste action {}: {}", action.getActionId(), e.getMessage());
        }
    }

    WasteActionDTO toDto(WasteAction a) {
        WasteFinding f = findingRepository.findById(a.getFindingId()).orElse(null);
        return new WasteActionDTO(a.getActionId(), a.getFindingId(), f == null ? null : f.getFindingType(),
                f == null ? null : f.getRegion(), f == null ? null : f.getResourceId(), a.getActionType(), a.getStatus(),
                a.getProposedBy(), a.getProposedAt(), a.getApprovedBy(), a.getApprovedAt(), a.getExecutedAt(), a.getRolledBackAt(),
                a.getErrorMessage(), a.getActionType() != WasteAction.Type.RELEASE_IP);
    }
}
