package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.ApprovalRequestDTO;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.*;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;

/**
 * Starts (and runtime extensions, E25-T05) held for approval (E25-T04). The approvers are
 * snapshotted when the request is made: the matched policies' users, team members and environment
 * admins, never the requester, falling back to the global admins. ANY completes on the first
 * approval, ALL when every approver approved; one denial denies. Approvals lock the request row,
 * and completion is a conditional PENDING -> APPROVED update, so a request executes once. The
 * operation then runs after commit as the requester, whose permissions are checked again.
 */
@Service
public class ApprovalRequestService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalRequestService.class);

    private final ApprovalRequestRepository requestRepository;
    private final ApprovalRequestDecisionRepository decisionRepository;
    private final ApprovalPolicyRepository policyRepository;
    private final ApprovalPolicyService policyService;
    private final TeamMemberRepository teamMemberRepository;
    private final EnvironmentAccessRepository accessRepository;
    private final UserRepository userRepository;
    private final EnvironmentRepository environmentRepository;
    private final VmOperationsService operationsService;
    private final SecurityService securityService;
    private final NotificationService notificationService;
    private final AuditService auditService;
    private final AfterCommit afterCommit;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final TransactionTemplate newTransaction;

    /** Runs an approved EXTEND request (set by the lease integration, E25-T05). */
    private Consumer<ApprovalRequest> extensionExecutor;

    @Value("${approvals.request-ttl-hours:24}")
    private long ttlHours = 24;

    public ApprovalRequestService(ApprovalRequestRepository requestRepository, ApprovalRequestDecisionRepository decisionRepository,
                                  ApprovalPolicyRepository policyRepository, ApprovalPolicyService policyService,
                                  TeamMemberRepository teamMemberRepository, EnvironmentAccessRepository accessRepository,
                                  UserRepository userRepository, EnvironmentRepository environmentRepository,
                                  VmOperationsService operationsService, SecurityService securityService,
                                  NotificationService notificationService, AuditService auditService, AfterCommit afterCommit,
                                  ObjectMapper objectMapper, EntityManager entityManager,
                                  PlatformTransactionManager transactionManager) {
        this.requestRepository = requestRepository;
        this.decisionRepository = decisionRepository;
        this.policyRepository = policyRepository;
        this.policyService = policyService;
        this.teamMemberRepository = teamMemberRepository;
        this.accessRepository = accessRepository;
        this.userRepository = userRepository;
        this.environmentRepository = environmentRepository;
        this.operationsService = operationsService;
        this.securityService = securityService;
        this.notificationService = notificationService;
        this.auditService = auditService;
        this.afterCommit = afterCommit;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void setExtensionExecutor(Consumer<ApprovalRequest> extensionExecutor) {
        this.extensionExecutor = extensionExecutor;
    }

    // ============= Holding a start =============

    /**
     * Evaluates a start against the approval policies; holds it and returns what to answer (202)
     * when it needs approval, or null when it may run now.
     */
    @Transactional
    public ApprovalRequestDTO.Held holdStartIfNeeded(String environmentId, String requesterId, StartOperationDTO dto) {
        if (!policyService.isEnabled() || dto == null || dto.getOperationType() == null
                || (dto.getOperationType() != OperationType.START && dto.getOperationType() != OperationType.RESTART)) {
            return null;
        }
        String groupId = dto.getGroupIds() != null && dto.getGroupIds().size() == 1 ? dto.getGroupIds().get(0) : null;
        String vmId = dto.getVmIds() != null && dto.getVmIds().size() == 1 ? dto.getVmIds().get(0) : null;
        Integer hours = dto.getLeaseMinutes() == null ? null : (int) Math.ceil(dto.getLeaseMinutes() / 60.0);
        ApprovalPolicyService.ApprovalContext ctx = policyService.context(environmentId, groupId, vmId, hours, false, requesterId);
        ApprovalPolicyService.ApprovalDecision decision = policyService.evaluate(ctx);
        if (!decision.requiresApproval()) {
            return null;
        }
        String scope = (vmId != null ? "1 VM" : groupId != null ? "a group" : "the whole environment") + ", " + ctx.requestedHours() + " h";
        return hold(ApprovalRequest.Type.START, ctx, decision, write(dto), scope);
    }

    /** Creates the request, snapshots the approvers and tells them. Also used for extensions. */
    @Transactional
    public ApprovalRequestDTO.Held hold(ApprovalRequest.Type type, ApprovalPolicyService.ApprovalContext ctx,
                                        ApprovalPolicyService.ApprovalDecision decision, String payload, String scopeSummary) {
        Environment environment = environmentRepository.findById(ctx.environmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Environment", ctx.environmentId()));
        ApprovalRequest request = new ApprovalRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setRequestType(type);
        request.setEnvironmentId(ctx.environmentId());
        request.setRequesterUserId(ctx.requesterId());
        request.setPayload(payload);
        request.setScopeSummary(scopeSummary);
        request.setEstimatedCost(ctx.preview() == null ? null : ctx.preview().estimatedTotal());
        request.setRequestedHours(ctx.requestedHours());
        request.setReasons(String.join("\n", decision.reasons()));
        request.setApproverMode(decision.matches().stream().anyMatch(m -> "ALL".equals(m.approverMode()))
                ? ApprovalPolicy.ApproverMode.ALL : ApprovalPolicy.ApproverMode.ANY);
        Instant now = Instant.now();
        request.setCreatedAt(Timestamp.from(now));
        request.setExpiresAt(Timestamp.from(now.plus(ttlHours, ChronoUnit.HOURS)));
        request.getApproverUserIds().addAll(approvers(decision, ctx.environmentId(), ctx.requesterId()));
        requestRepository.saveAndFlush(request);

        String envName = name(environment);
        String requesterName = userName(ctx.requesterId());
        auditService.logEnvironmentAction(ctx.requesterId(), AuditAction.APPROVAL_REQUESTED, ctx.environmentId(), envName,
                "approval_request", request.getRequestId(), envName,
                type + " held for approval (" + request.getApproverMode() + " of " + request.getApproverUserIds().size()
                        + "): " + String.join("; ", decision.reasons()));
        List<String> approverIds = List.copyOf(request.getApproverUserIds());
        String requestId = request.getRequestId();
        BigDecimal cost = request.getEstimatedCost();
        List<String> reasons = decision.reasons();
        afterCommit.run(() -> notificationService.notifyApprovalRequested(approverIds, ctx.environmentId(), envName, requestId,
                requesterName, type.name(), scopeSummary, cost, reasons));
        return new ApprovalRequestDTO.Held(requestId, "PENDING_APPROVAL", reasons,
                approverIds.stream().map(this::userName).toList(), cost);
    }

    /** USER ids, TEAM members and environment admins of the matched policies; never the requester. */
    Set<String> approvers(ApprovalPolicyService.ApprovalDecision decision, String environmentId, String requesterId) {
        Set<String> ids = new LinkedHashSet<>();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (ApprovalPolicyService.PolicyMatch match : decision.matches()) {
            ApprovalPolicy policy = policyRepository.findById(match.policyId()).orElse(null);
            if (policy == null) {
                continue;
            }
            for (ApprovalPolicy.Approver approver : policy.getApprovers()) {
                switch (approver.getType()) {
                    case USER -> ids.add(approver.getApproverId());
                    case TEAM -> teamMemberRepository.findByTeamId(approver.getApproverId()).forEach(m -> ids.add(m.getUserId()));
                    case ENV_ADMINS -> {
                        accessRepository.findActiveAccessWithUsersByEnvironment(environmentId).stream()
                                .filter(EnvironmentAccess::isActive)
                                .filter(a -> a.getScopeType() == AccessScopeType.ENVIRONMENT && a.getAccessLevel() == AccessLevel.ADMIN)
                                .forEach(a -> ids.add(a.getUser().getUserId()));
                        accessRepository.findActiveEnvAdminGrantHolders(environmentId, now).forEach(u -> ids.add(u.getUserId()));
                    }
                }
            }
        }
        ids.remove(requesterId);
        ids.removeIf(id -> userRepository.findById(id).map(u -> !Boolean.TRUE.equals(u.getIsActive())).orElse(true));
        if (ids.isEmpty()) {
            userRepository.findByAdminTrueAndIsActiveTrue().forEach(u -> ids.add(u.getUserId()));
            ids.remove(requesterId);
        }
        return ids;
    }

    // ============= Decisions =============

    @Transactional
    public ApprovalRequestDTO approve(String requestId, String userId, String reason) {
        ApprovalRequest request = lockPending(requestId, userId);
        record(request, userId, ApprovalRequestDecision.Decision.APPROVED, reason);
        String envName = environmentName(request.getEnvironmentId());
        boolean complete = request.getApproverMode() == ApprovalPolicy.ApproverMode.ANY || allApproved(request);
        auditService.logEnvironmentAction(userId, AuditAction.APPROVAL_APPROVED, request.getEnvironmentId(), envName,
                "approval_request", requestId, envName, "Approved" + (complete ? "" : " (waiting for the other approvers)")
                        + (reason == null || reason.isBlank() ? "" : ": " + reason));
        if (complete && requestRepository.decideIfPending(requestId, ApprovalRequest.Status.APPROVED, null,
                new Timestamp(System.currentTimeMillis())) == 1) {
            afterCommit.run(() -> executeApproved(requestId));
        }
        return get(requestId, userId);
    }

    @Transactional
    public ApprovalRequestDTO deny(String requestId, String userId, String reason) {
        if (reason == null || reason.trim().length() < 3) {
            throw new ValidationException("Say why (at least 3 characters)");
        }
        ApprovalRequest request = lockPending(requestId, userId);
        record(request, userId, ApprovalRequestDecision.Decision.DENIED, reason.trim());
        String text = "Denied by " + userName(userId) + ": " + reason.trim();
        requestRepository.decideIfPending(requestId, ApprovalRequest.Status.DENIED, truncate(text), new Timestamp(System.currentTimeMillis()));
        String envName = environmentName(request.getEnvironmentId());
        auditService.logEnvironmentAction(userId, AuditAction.APPROVAL_DENIED, request.getEnvironmentId(), envName,
                "approval_request", requestId, envName, text);
        String requester = request.getRequesterUserId();
        String envId = request.getEnvironmentId();
        afterCommit.run(() -> notificationService.notifyApprovalOutcome(requester, envId, envName, requestId,
                NotificationType.APPROVAL_DENIED, "Request denied: " + envName, text + "."));
        return get(requestId, userId);
    }

    @Transactional
    public ApprovalRequestDTO cancel(String requestId, String userId) {
        ApprovalRequest request = request(requestId);
        if (!request.getRequesterUserId().equals(userId)) {
            throw new UnauthorizedException("Only the requester can cancel a request");
        }
        if (requestRepository.decideIfPending(requestId, ApprovalRequest.Status.CANCELLED, "Cancelled by the requester",
                new Timestamp(System.currentTimeMillis())) == 0) {
            throw new ConflictException("This request is no longer pending");
        }
        String envName = environmentName(request.getEnvironmentId());
        auditService.logEnvironmentAction(userId, AuditAction.APPROVAL_CANCELLED, request.getEnvironmentId(), envName,
                "approval_request", requestId, envName, "Cancelled by the requester");
        return get(requestId, userId);
    }

    /** Locks the request row, checks it is pending and that the user may decide it. */
    private ApprovalRequest lockPending(String requestId, String userId) {
        ApprovalRequest request = entityManager.find(ApprovalRequest.class, requestId, LockModeType.PESSIMISTIC_WRITE);
        if (request == null) {
            throw new ResourceNotFoundException("ApprovalRequest", requestId);
        }
        entityManager.refresh(request, LockModeType.PESSIMISTIC_WRITE);
        if (userId.equals(request.getRequesterUserId())) {
            throw new UnauthorizedException("You cannot approve or deny your own request");
        }
        if (!request.getApproverUserIds().contains(userId)) {
            throw new UnauthorizedException("You are not an approver of this request");
        }
        if (request.getStatus() != ApprovalRequest.Status.PENDING) {
            throw new ConflictException("This request is already " + request.getStatus().name().toLowerCase(Locale.ROOT));
        }
        if (request.getExpiresAt().toInstant().isBefore(Instant.now())) {
            throw new ConflictException("This request has expired");
        }
        return request;
    }

    private void record(ApprovalRequest request, String userId, ApprovalRequestDecision.Decision decision, String reason) {
        if (decisionRepository.existsById(new ApprovalRequestDecision.Key(request.getRequestId(), userId))) {
            throw new ConflictException("You already decided this request");
        }
        try {
            decisionRepository.saveAndFlush(new ApprovalRequestDecision(request.getRequestId(), userId, decision,
                    reason == null || reason.isBlank() ? null : truncate(reason.trim()), new Timestamp(System.currentTimeMillis())));
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("You already decided this request");
        }
    }

    /** Every snapshotted approver approved (a locking count, so it sees concurrent approvals). */
    private boolean allApproved(ApprovalRequest request) {
        Number approved = (Number) entityManager.createNativeQuery(
                        "SELECT COUNT(*) FROM approval_decision WHERE request_id = ? AND decision = 'APPROVED' FOR UPDATE")
                .setParameter(1, request.getRequestId()).getSingleResult();
        return approved.intValue() >= request.getApproverUserIds().size();
    }

    // ============= Execution and expiry =============

    /** Runs an APPROVED request as its requester; EXECUTED with the execution id, or FAILED with why. */
    public void executeApproved(String requestId) {
        ApprovalRequest request = requestRepository.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != ApprovalRequest.Status.APPROVED) {
            return;
        }
        String envName = environmentName(request.getEnvironmentId());
        String outcome;
        try {
            User requester = userRepository.findById(request.getRequesterUserId())
                    .orElseThrow(() -> new ValidationException("The requester no longer exists"));
            if (!securityService.canOperateInEnvironment(requester, request.getEnvironmentId())) {
                throw new UnauthorizedException("The requester can no longer operate in this environment");
            }
            if (request.getRequestType() == ApprovalRequest.Type.EXTEND) {
                if (extensionExecutor == null) {
                    throw new ValidationException("Lease extensions are not available");
                }
                newTransaction.executeWithoutResult(s -> extensionExecutor.accept(request));
                outcome = null;
            } else {
                StartOperationDTO dto = objectMapper.readValue(request.getPayload(), StartOperationDTO.class);
                OperationExecution execution = newTransaction.execute(s ->
                        operationsService.startOperation(request.getEnvironmentId(), request.getRequesterUserId(), dto));
                outcome = execution == null ? null : execution.getExecutionId();
            }
            String executionId = outcome;
            newTransaction.executeWithoutResult(s -> {
                ApprovalRequest r = requestRepository.findById(requestId).orElseThrow();
                r.setStatus(ApprovalRequest.Status.EXECUTED);
                r.setExecutionId(executionId);
                requestRepository.save(r);
            });
            notificationService.notifyApprovalOutcome(request.getRequesterUserId(), request.getEnvironmentId(), envName, requestId,
                    NotificationType.APPROVAL_GRANTED, "Request approved: " + envName,
                    (request.getRequestType() == ApprovalRequest.Type.EXTEND ? "The extension" : "The start") + " of "
                            + envName + " was approved and is running.");
        } catch (Exception e) {
            String why = e instanceof JsonProcessingException ? "The stored request is unreadable" : e.getMessage();
            log.warn("Approved request {} could not run: {}", requestId, why);
            newTransaction.executeWithoutResult(s -> {
                ApprovalRequest r = requestRepository.findById(requestId).orElseThrow();
                r.setStatus(ApprovalRequest.Status.FAILED);
                r.setStatusReason(truncate("Approved, but it could not run: " + why));
                requestRepository.save(r);
            });
            notificationService.notifyApprovalOutcome(request.getRequesterUserId(), request.getEnvironmentId(), envName, requestId,
                    NotificationType.APPROVAL_DENIED, "Approved request failed: " + envName,
                    "Your request for " + envName + " was approved, but it could not run: " + why);
        }
    }

    /** Expires pending requests past their deadline; returns how many. */
    public int expire() {
        int count = 0;
        Timestamp now = new Timestamp(System.currentTimeMillis());
        for (ApprovalRequest request : requestRepository.findExpired(now)) {
            Integer changed = newTransaction.execute(s -> requestRepository.decideIfPending(request.getRequestId(),
                    ApprovalRequest.Status.EXPIRED, "Nobody decided in time", now));
            if (changed != null && changed == 1) {
                count++;
                String envName = environmentName(request.getEnvironmentId());
                auditService.logEnvironmentAction(null, AuditAction.APPROVAL_EXPIRED, request.getEnvironmentId(), envName,
                        "approval_request", request.getRequestId(), envName, "Expired: nobody decided in time");
                notificationService.notifyApprovalOutcome(request.getRequesterUserId(), request.getEnvironmentId(), envName,
                        request.getRequestId(), NotificationType.APPROVAL_DENIED, "Request expired: " + envName,
                        "Nobody approved your request for " + envName + " in time. Ask again if you still need it.");
            }
        }
        return count;
    }

    // ============= Reading =============

    @Transactional(readOnly = true)
    public List<ApprovalRequestDTO> list(String userId, String view) {
        List<ApprovalRequest> requests = "to-review".equalsIgnoreCase(view) ? requestRepository.findForApprover(userId)
                : requestRepository.findByRequesterUserIdOrderByCreatedAtDesc(userId);
        return requests.stream().limit(200).map(r -> toDto(r, userId)).toList();
    }

    /** Visible to the requester, its approvers and admins. */
    @Transactional(readOnly = true)
    public ApprovalRequestDTO get(String requestId, String userId) {
        ApprovalRequest request = request(requestId);
        if (!userId.equals(request.getRequesterUserId()) && !request.getApproverUserIds().contains(userId) && !securityService.isAdmin()) {
            throw new UnauthorizedException("You cannot see this request");
        }
        return toDto(request, userId);
    }

    /** The request's environment and approvers, for the chat action (E25-T04). */
    @Transactional(readOnly = true)
    public ApprovalRequest find(String requestId) {
        ApprovalRequest request = request(requestId);
        request.getApproverUserIds().size();
        return request;
    }

    private ApprovalRequestDTO toDto(ApprovalRequest r, String viewerId) {
        Map<String, ApprovalRequestDecision> decisions = new HashMap<>();
        decisionRepository.findByRequestId(r.getRequestId()).forEach(d -> decisions.put(d.getApproverUserId(), d));
        List<ApprovalRequestDTO.Approver> approvers = r.getApproverUserIds().stream().map(id -> {
            ApprovalRequestDecision d = decisions.get(id);
            return new ApprovalRequestDTO.Approver(id, userName(id), d == null ? null : d.getDecision().name(),
                    d == null ? null : d.getReason(), d == null ? null : d.getDecidedAt());
        }).toList();
        boolean pending = r.getStatus() == ApprovalRequest.Status.PENDING;
        boolean canDecide = pending && r.getApproverUserIds().contains(viewerId) && !viewerId.equals(r.getRequesterUserId())
                && !decisions.containsKey(viewerId);
        List<String> reasons = r.getReasons() == null || r.getReasons().isBlank() ? List.of() : List.of(r.getReasons().split("\n"));
        return new ApprovalRequestDTO(r.getRequestId(), r.getRequestType().name(), r.getEnvironmentId(),
                environmentName(r.getEnvironmentId()), r.getRequesterUserId(), userName(r.getRequesterUserId()), r.getScopeSummary(),
                r.getEstimatedCost(), r.getRequestedHours(), reasons, r.getApproverMode().name(), r.getStatus().name(),
                r.getStatusReason(), r.getCreatedAt(), r.getExpiresAt(), r.getDecidedAt(), r.getExecutionId(), approvers,
                canDecide, pending && viewerId.equals(r.getRequesterUserId()));
    }

    private ApprovalRequest request(String requestId) {
        return requestRepository.findById(requestId).orElseThrow(() -> new ResourceNotFoundException("ApprovalRequest", requestId));
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store the request");
        }
    }

    private String userName(String userId) {
        return userId == null ? null : userRepository.findById(userId)
                .map(u -> u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getEmail())
                .orElse(userId);
    }

    private String environmentName(String environmentId) {
        return environmentRepository.findById(environmentId).map(ApprovalRequestService::name).orElse(environmentId);
    }

    private static String name(Environment environment) {
        return environment.getDisplayName() != null ? environment.getDisplayName() : environment.getName();
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() <= 500 ? s : s.substring(0, 497) + "...";
    }
}
