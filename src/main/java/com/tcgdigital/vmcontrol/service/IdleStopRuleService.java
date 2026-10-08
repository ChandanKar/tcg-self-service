package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.IdleStopRuleDTO;
import com.tcgdigital.vmcontrol.dto.IdleStopRuleRequestDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.IdleStopMode;
import com.tcgdigital.vmcontrol.model.IdleStopRule;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.IdleStopRuleRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Idle auto-stop rules of an environment (E16-T01, G5). New rules always start in DRY_RUN; moving
 * to ENFORCE needs a dry run of {@code automation.idle-stop.min-dry-run-days} first, unless an
 * ADMIN does it. Production environments cannot have rules.
 */
@Service
public class IdleStopRuleService {

    private final IdleStopRuleRepository ruleRepository;
    private final EnvironmentRepository environmentRepository;
    private final VmGroupRepository groupRepository;
    private final AuditService auditService;

    @Value("${automation.idle-stop.min-dry-run-days:14}")
    private long minDryRunDays = 14;

    public IdleStopRuleService(IdleStopRuleRepository ruleRepository, EnvironmentRepository environmentRepository,
                               VmGroupRepository groupRepository, AuditService auditService) {
        this.ruleRepository = ruleRepository;
        this.environmentRepository = environmentRepository;
        this.groupRepository = groupRepository;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<IdleStopRuleDTO> listRules(String environmentId) {
        return ruleRepository.findByEnvironment(environmentId).stream().map(this::toDto).toList();
    }

    /** The rule, checked to belong to the environment in the path (404 otherwise). */
    @Transactional(readOnly = true)
    public IdleStopRule getRule(String environmentId, String ruleId) {
        IdleStopRule rule = ruleRepository.findByIdFetchEnvironment(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Idle stop rule", ruleId));
        if (!rule.getEnvironment().getEnvironmentId().equals(environmentId)) {
            throw new ResourceNotFoundException("Idle stop rule", ruleId);
        }
        return rule;
    }

    @Transactional
    public IdleStopRuleDTO createRule(String environmentId, IdleStopRuleRequestDTO dto, String userId) {
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        if (Boolean.TRUE.equals(environment.getIsProduction())) {
            throw new ValidationException("Production environments are excluded from idle auto-stop");
        }
        AutomationScopeType scope = scopeOf(dto);
        String groupId = scope == AutomationScopeType.GROUP ? requireGroup(environmentId, dto.getGroupId()).getGroupId() : null;
        boolean exists = groupId == null
                ? ruleRepository.existsByEnvironment_EnvironmentIdAndGroupIdIsNull(environmentId)
                : ruleRepository.existsByEnvironment_EnvironmentIdAndGroupId(environmentId, groupId);
        if (exists) {
            throw new ValidationException(groupId == null
                    ? "This environment already has an idle-stop rule" : "This group already has an idle-stop rule");
        }

        IdleStopRule rule = new IdleStopRule();
        rule.setRuleId(UUID.randomUUID().toString());
        rule.setEnvironment(environment);
        rule.setScopeType(scope);
        rule.setGroupId(groupId);
        applyThresholds(rule, dto);
        rule.setMode(IdleStopMode.DRY_RUN); // always: what it would do is visible before it acts
        rule.setDryRunStartedAt(Timestamp.from(Instant.now()));
        rule.setEnabled(dto.getEnabled() == null || dto.getEnabled());
        rule.setCreatedByUserId(userId);
        rule = ruleRepository.save(rule);
        audit(userId, environment, rule, "Idle-stop rule created (dry run, " + rule.getIdleMinutes() + " min)");
        return toDto(rule);
    }

    @Transactional
    public IdleStopRuleDTO updateRule(String environmentId, String ruleId, IdleStopRuleRequestDTO dto,
                                      String userId, boolean callerIsAdmin) {
        IdleStopRule rule = getRule(environmentId, ruleId);
        applyThresholds(rule, dto);
        if (dto.getEnabled() != null) {
            rule.setEnabled(dto.getEnabled());
        }
        if (dto.getMode() != null && dto.getMode() != rule.getMode()) {
            if (dto.getMode() == IdleStopMode.ENFORCE) {
                Instant dryRunFrom = rule.getDryRunStartedAt() != null ? rule.getDryRunStartedAt().toInstant() : Instant.now();
                boolean longEnough = !dryRunFrom.isAfter(Instant.now().minus(Duration.ofDays(minDryRunDays)));
                if (!longEnough && !callerIsAdmin) {
                    throw new ValidationException("Run in dry-run mode for " + minDryRunDays + " days first");
                }
            } else {
                rule.setDryRunStartedAt(Timestamp.from(Instant.now())); // back to dry run: the clock restarts
            }
            rule.setMode(dto.getMode());
        }
        rule = ruleRepository.save(rule);
        audit(userId, rule.getEnvironment(), rule, "Idle-stop rule updated (" + rule.getMode() + ", "
                + rule.getIdleMinutes() + " min, enabled=" + rule.getEnabled() + ")");
        return toDto(rule);
    }

    @Transactional
    public void deleteRule(String environmentId, String ruleId, String userId) {
        IdleStopRule rule = getRule(environmentId, ruleId);
        ruleRepository.delete(rule);
        audit(userId, rule.getEnvironment(), rule, "Idle-stop rule deleted");
    }

    private void audit(String userId, Environment environment, IdleStopRule rule, String details) {
        auditService.logEnvironmentAction(userId, AuditAction.IDLE_STOP_RULE_CHANGED, environment.getEnvironmentId(),
                environment.getName(), "idle_stop_rule", rule.getRuleId(),
                rule.getScopeType() == AutomationScopeType.GROUP ? "group " + rule.getGroupId() : environment.getName(),
                details);
    }

    private static AutomationScopeType scopeOf(IdleStopRuleRequestDTO dto) {
        AutomationScopeType scope = dto.getScopeType() == null ? AutomationScopeType.ENVIRONMENT : dto.getScopeType();
        if (scope == AutomationScopeType.VM) {
            throw new ValidationException("Idle-stop rules apply to an environment or a group");
        }
        return scope;
    }

    /** A group of this environment; another environment's group is reported as not found. */
    private VmGroup requireGroup(String environmentId, String groupId) {
        if (groupId == null || groupId.isBlank()) {
            throw new ValidationException("Group is required for a group rule");
        }
        VmGroup group = groupRepository.findById(groupId)
                .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
        if (!group.getEnvironment().getEnvironmentId().equals(environmentId)) {
            throw new ResourceNotFoundException("Group", groupId);
        }
        return group;
    }

    private static void applyThresholds(IdleStopRule rule, IdleStopRuleRequestDTO dto) {
        if (dto.getIdleMinutes() != null) rule.setIdleMinutes(dto.getIdleMinutes());
        if (dto.getCpuMaxPercent() != null) rule.setCpuMaxPercent(dto.getCpuMaxPercent());
        if (dto.getNetworkMbPerDay() != null) rule.setNetworkMbPerDay(dto.getNetworkMbPerDay());
    }

    private IdleStopRuleDTO toDto(IdleStopRule rule) {
        String groupName = rule.getGroupId() == null ? null : groupRepository.findById(rule.getGroupId())
                .map(g -> g.getDisplayName() != null ? g.getDisplayName() : g.getName()).orElse(null);
        return IdleStopRuleDTO.fromEntity(rule, groupName);
    }
}
