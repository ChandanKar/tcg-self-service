package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.ApprovalPolicyDTO;
import com.tcgdigital.vmcontrol.dto.CostPreviewDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.ApprovalPolicy;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.ApprovalPolicyRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Approval policies and their evaluation (E25-T03). A start or runtime extension needs approval
 * when an active policy for its environment (global, environment or team) matches any of: an
 * instance type glob, a minimum vCPU count, a maximum runtime, a maximum estimated cost. Stops
 * never do. Off unless approvals.enabled; global admins bypass only with approvals.admin-bypass.
 */
@Service
public class ApprovalPolicyService {

    private final ApprovalPolicyRepository policyRepository;
    private final EnvironmentRepository environmentRepository;
    private final TeamRepository teamRepository;
    private final UserRepository userRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final OperationCostPreviewService costPreviewService;
    private final AuditService auditService;

    @Value("${approvals.enabled:false}")
    private boolean enabled;

    @Value("${approvals.admin-bypass:false}")
    private boolean adminBypass;

    public ApprovalPolicyService(ApprovalPolicyRepository policyRepository, EnvironmentRepository environmentRepository,
                                 TeamRepository teamRepository, UserRepository userRepository,
                                 VmInventorySnapshotRepository inventoryRepository, OperationCostPreviewService costPreviewService,
                                 AuditService auditService) {
        this.policyRepository = policyRepository;
        this.environmentRepository = environmentRepository;
        this.teamRepository = teamRepository;
        this.userRepository = userRepository;
        this.inventoryRepository = inventoryRepository;
        this.costPreviewService = costPreviewService;
        this.auditService = auditService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    // ============= Evaluation =============

    /** A VM the operation would start (or keep running): its type and vCPU when known. */
    public record Target(String vmId, String name, String instanceType, Integer vcpu) {}

    /** What is being asked for. operationType START, RESTART or EXTEND; STOP never needs approval. */
    public record ApprovalContext(String environmentId, String teamId, String requesterId, String operationType,
                                  List<Target> targets, int requestedHours, CostPreviewDTO preview) {}

    /** A matched policy and why it matched. */
    public record PolicyMatch(String policyId, String name, String approverMode, List<String> reasons) {}

    /** ALLOW (no matches) or REQUIRE_APPROVAL with the matched policies. */
    public record ApprovalDecision(boolean requiresApproval, List<PolicyMatch> matches) {
        public static ApprovalDecision allow() {
            return new ApprovalDecision(false, List.of());
        }

        @com.fasterxml.jackson.annotation.JsonProperty("outcome")
        public String outcome() {
            return requiresApproval ? "REQUIRE_APPROVAL" : "ALLOW";
        }

        @com.fasterxml.jackson.annotation.JsonProperty("reasons")
        public List<String> reasons() {
            return matches.stream().flatMap(m -> m.reasons().stream()).toList();
        }
    }

    /**
     * Builds the context for a start (extension = false) or a runtime extension of the VMs in
     * scope, pricing them through the cost preview.
     */
    @Transactional(readOnly = true)
    public ApprovalContext context(String environmentId, String groupId, String vmId, Integer hours, boolean extension,
                                   String requesterId) {
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        CostPreviewDTO preview = costPreviewService.preview(environmentId, groupId, vmId, hours, extension);
        Map<String, Integer> vcpus = new HashMap<>();
        List<String> ids = preview.vms().stream().map(CostPreviewDTO.VmLine::vmId).toList();
        if (!ids.isEmpty()) {
            inventoryRepository.findByVmVmIdIn(ids).forEach(s -> vcpus.put(s.getVm().getVmId(), s.getVcpuCount()));
        }
        List<Target> targets = preview.vms().stream()
                .map(l -> new Target(l.vmId(), l.name(), l.instanceType(), vcpus.get(l.vmId()))).toList();
        return new ApprovalContext(environmentId, environment.getTeamId(), requesterId, extension ? "EXTEND" : "START",
                targets, preview.hours(), preview);
    }

    @Transactional(readOnly = true)
    public ApprovalDecision evaluate(ApprovalContext ctx) {
        if (!enabled || ctx == null || "STOP".equalsIgnoreCase(ctx.operationType())) {
            return ApprovalDecision.allow();
        }
        if (adminBypass && ctx.requesterId() != null
                && userRepository.findById(ctx.requesterId()).map(User::isAdmin).orElse(false)) {
            return ApprovalDecision.allow();
        }
        List<PolicyMatch> matches = new ArrayList<>();
        for (ApprovalPolicy policy : policyRepository.findActiveForEnvironment(ctx.environmentId(), ctx.teamId())) {
            List<String> reasons = reasons(policy, ctx);
            if (!reasons.isEmpty()) {
                matches.add(new PolicyMatch(policy.getPolicyId(), policy.getName(), policy.getApproverMode().name(), reasons));
            }
        }
        matches.sort(Comparator.comparing(PolicyMatch::name, String.CASE_INSENSITIVE_ORDER));
        return matches.isEmpty() ? ApprovalDecision.allow() : new ApprovalDecision(true, matches);
    }

    /** Why a policy matches the context; empty when it does not. */
    static List<String> reasons(ApprovalPolicy policy, ApprovalContext ctx) {
        List<String> reasons = new ArrayList<>();
        List<String> patterns = patterns(policy.getInstanceTypePatterns());
        for (Target t : ctx.targets()) {
            if (t.instanceType() != null) {
                for (String pattern : patterns) {
                    if (globMatches(pattern, t.instanceType())) {
                        reasons.add(t.instanceType() + " matches " + pattern + " (" + t.name() + ")");
                        break;
                    }
                }
            }
            if (policy.getMinVcpu() != null && t.vcpu() != null && t.vcpu() >= policy.getMinVcpu()) {
                reasons.add(t.name() + " has " + t.vcpu() + " vCPU (" + policy.getMinVcpu() + " or more need approval)");
            }
        }
        if (policy.getMaxRuntimeHours() != null && ctx.requestedHours() > policy.getMaxRuntimeHours()) {
            reasons.add(ctx.requestedHours() + " h is longer than " + policy.getMaxRuntimeHours() + " h");
        }
        BigDecimal cost = ctx.preview() == null ? null : ctx.preview().estimatedTotal();
        if (policy.getMaxEstimatedCost() != null && cost != null && cost.compareTo(policy.getMaxEstimatedCost()) > 0) {
            reasons.add("estimated $" + cost.toPlainString() + " is more than $" + policy.getMaxEstimatedCost().toPlainString());
        }
        return reasons;
    }

    static List<String> patterns(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    }

    /** "*.8xlarge" / "p*" / "m5.?large", case-insensitive, whole string. */
    static boolean globMatches(String glob, String value) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE).matcher(value).matches();
    }

    // ============= Policies =============

    @Transactional(readOnly = true)
    public List<ApprovalPolicyDTO> list(String environmentId) {
        List<ApprovalPolicy> policies = environmentId == null ? policyRepository.findAllByOrderByNameAsc()
                : policyRepository.findByEnvironmentIdOrderByNameAsc(environmentId);
        return policies.stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public ApprovalPolicyDTO get(String policyId) {
        return toDto(policy(policyId));
    }

    /** The policy's environment (null = global or team), for authorization. */
    @Transactional(readOnly = true)
    public String environmentOf(String policyId) {
        return policy(policyId).getEnvironmentId();
    }

    @Transactional
    public ApprovalPolicyDTO create(String actorId, ApprovalPolicyDTO.Request request) {
        if (request == null) {
            throw new ValidationException("The policy is required");
        }
        ApprovalPolicy policy = new ApprovalPolicy();
        policy.setPolicyId(UUID.randomUUID().toString());
        policy.setCreatedBy(actorId);
        apply(policy, request, true);
        policy = policyRepository.save(policy);
        auditService.logAction(actorId, AuditAction.APPROVAL_POLICY_CREATED, "approval_policy", policy.getPolicyId(),
                policy.getName(), describe(policy));
        return toDto(policy);
    }

    @Transactional
    public ApprovalPolicyDTO update(String policyId, String actorId, ApprovalPolicyDTO.Request request) {
        ApprovalPolicy policy = policy(policyId);
        if (request == null) {
            throw new ValidationException("The policy is required");
        }
        String before = describe(policy);
        policy.getApprovers().clear();
        policyRepository.saveAndFlush(policy);
        apply(policy, request, false);
        policy = policyRepository.save(policy);
        auditService.logAction(actorId, AuditAction.APPROVAL_POLICY_UPDATED, "approval_policy", policyId, policy.getName(),
                before + " -> " + describe(policy));
        return toDto(policy);
    }

    @Transactional
    public void delete(String policyId, String actorId) {
        ApprovalPolicy policy = policy(policyId);
        policyRepository.delete(policy);
        auditService.logAction(actorId, AuditAction.APPROVAL_POLICY_DELETED, "approval_policy", policyId, policy.getName(),
                describe(policy));
    }

    private void apply(ApprovalPolicy policy, ApprovalPolicyDTO.Request r, boolean creating) {
        String name = r.name() == null ? "" : r.name().trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new ValidationException("A policy name of 1 to 100 characters is required");
        }
        String environmentId = blank(r.environmentId());
        String teamId = blank(r.teamId());
        if (creating) {
            if (environmentId != null && teamId != null) {
                throw new ValidationException("A policy applies to one environment, one team, or everything");
            }
            if (environmentId != null && environmentRepository.findById(environmentId).isEmpty()) {
                throw new ResourceNotFoundException("Environment", environmentId);
            }
            if (teamId != null && teamRepository.findById(teamId).isEmpty()) {
                throw new ResourceNotFoundException("Team", teamId);
            }
            policy.setEnvironmentId(environmentId);
            policy.setTeamId(teamId);
        } else if (!Objects.equals(environmentId, policy.getEnvironmentId()) && r.environmentId() != null
                || !Objects.equals(teamId, policy.getTeamId()) && r.teamId() != null) {
            throw new ValidationException("A policy's environment or team cannot change; create a new policy");
        }
        List<String> patterns = r.instanceTypePatterns() == null ? List.of() : r.instanceTypePatterns().stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        for (String p : patterns) {
            if (!p.matches("[A-Za-z0-9.*?_-]+")) {
                throw new ValidationException("Instance type patterns use letters, digits, '.', '-', '*' and '?': " + p);
            }
        }
        if (r.minVcpu() != null && r.minVcpu() < 1) {
            throw new ValidationException("Minimum vCPU must be at least 1");
        }
        if (r.maxRuntimeHours() != null && r.maxRuntimeHours() < 1) {
            throw new ValidationException("Maximum runtime must be at least 1 hour");
        }
        if (r.maxEstimatedCost() != null && r.maxEstimatedCost().signum() < 0) {
            throw new ValidationException("Maximum estimated cost cannot be negative");
        }
        if (patterns.isEmpty() && r.minVcpu() == null && r.maxRuntimeHours() == null && r.maxEstimatedCost() == null) {
            throw new ValidationException("Set at least one condition: instance types, vCPU, runtime or estimated cost");
        }
        ApprovalPolicy.ApproverMode mode;
        try {
            mode = ApprovalPolicy.ApproverMode.valueOf(r.approverMode() == null ? "ANY" : r.approverMode().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("approverMode must be ANY or ALL");
        }
        List<ApprovalPolicy.Approver> approvers = new ArrayList<>();
        for (ApprovalPolicyDTO.ApproverRequest a : r.approvers() == null ? List.<ApprovalPolicyDTO.ApproverRequest>of() : r.approvers()) {
            ApprovalPolicy.ApproverType type;
            try {
                type = ApprovalPolicy.ApproverType.valueOf(a.type() == null ? "" : a.type().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ValidationException("Approver type must be USER, TEAM or ENV_ADMINS");
            }
            String id = blank(a.approverId());
            switch (type) {
                case USER -> {
                    if (id == null || userRepository.findById(id).isEmpty()) {
                        throw new ValidationException("Unknown approver user: " + id);
                    }
                }
                case TEAM -> {
                    if (id == null || teamRepository.findById(id).isEmpty()) {
                        throw new ValidationException("Unknown approver team: " + id);
                    }
                }
                case ENV_ADMINS -> id = null;
            }
            ApprovalPolicy.Approver approver = new ApprovalPolicy.Approver(type, id);
            if (approvers.stream().noneMatch(x -> x.getType() == type && Objects.equals(x.getApproverId(), approver.getApproverId()))) {
                approvers.add(approver);
            }
        }
        policy.setName(name);
        policy.setInstanceTypePatterns(patterns.isEmpty() ? null : String.join(",", patterns));
        policy.setMinVcpu(r.minVcpu());
        policy.setMaxRuntimeHours(r.maxRuntimeHours());
        policy.setMaxEstimatedCost(r.maxEstimatedCost());
        policy.setApproverMode(mode);
        policy.setIsActive(r.active() == null || r.active());
        policy.setApprovers(approvers);
    }

    private ApprovalPolicy policy(String policyId) {
        return policyRepository.findById(policyId).orElseThrow(() -> new ResourceNotFoundException("ApprovalPolicy", policyId));
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String describe(ApprovalPolicy p) {
        List<String> parts = new ArrayList<>();
        if (p.getInstanceTypePatterns() != null) parts.add("types " + p.getInstanceTypePatterns());
        if (p.getMinVcpu() != null) parts.add(">= " + p.getMinVcpu() + " vCPU");
        if (p.getMaxRuntimeHours() != null) parts.add("> " + p.getMaxRuntimeHours() + " h");
        if (p.getMaxEstimatedCost() != null) parts.add("> $" + p.getMaxEstimatedCost().toPlainString());
        return p.getName() + " [" + String.join(", ", parts) + "; " + p.getApproverMode() + " of " + p.getApprovers().size()
                + " approver(s)" + (Boolean.TRUE.equals(p.getIsActive()) ? "" : "; off") + "]";
    }

    private ApprovalPolicyDTO toDto(ApprovalPolicy p) {
        String envName = p.getEnvironmentId() == null ? null : environmentRepository.findById(p.getEnvironmentId())
                .map(e -> e.getDisplayName() != null ? e.getDisplayName() : e.getName()).orElse(null);
        String teamName = p.getTeamId() == null ? null : teamRepository.findById(p.getTeamId()).map(Team::getDisplayName).orElse(null);
        List<ApprovalPolicyDTO.Approver> approvers = p.getApprovers().stream().map(a -> new ApprovalPolicyDTO.Approver(
                a.getType().name(), a.getApproverId(), switch (a.getType()) {
                    case USER -> userRepository.findById(a.getApproverId())
                            .map(u -> u.getDisplayName() != null ? u.getDisplayName() : u.getEmail()).orElse(a.getApproverId());
                    case TEAM -> teamRepository.findById(a.getApproverId()).map(Team::getDisplayName).orElse(a.getApproverId());
                    case ENV_ADMINS -> "Environment admins";
                })).toList();
        return new ApprovalPolicyDTO(p.getPolicyId(), p.getEnvironmentId(), envName, p.getTeamId(), teamName, p.getName(),
                patterns(p.getInstanceTypePatterns()), p.getMinVcpu(), p.getMaxRuntimeHours(), p.getMaxEstimatedCost(),
                p.getApproverMode().name(), approvers, Boolean.TRUE.equals(p.getIsActive()), p.getCreatedAt(), p.getUpdatedAt());
    }
}
