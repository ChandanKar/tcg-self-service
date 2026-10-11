package com.tcgdigital.vmcontrol.service.chat;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.ChatActionType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccessRequest;
import com.tcgdigital.vmcontrol.model.OperationType;
import com.tcgdigital.vmcontrol.dto.StartOperationDTO;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.service.VmOperationsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Runs one-click chat actions (E24-T04) as the signed-in user: the same permission checks as the
 * matching page (approve = administers the environment and is not the requester; stop = may
 * operate, plus the lock check inside the operation). The link is used up in the same transaction
 * as the action, so a refused or failed action leaves it usable. Every attempt is audited.
 */
@Service
public class ChatActionService {

    static final String VIA_CHAT = "via chat";

    private final ChatActionTokenService tokenService;
    private final EnvironmentAccessService accessService;
    private final VmOperationsService operationsService;
    private final SecurityService securityService;
    private final EnvironmentRepository environmentRepository;
    private final AuditService auditService;

    public ChatActionService(ChatActionTokenService tokenService, EnvironmentAccessService accessService,
                             VmOperationsService operationsService, SecurityService securityService,
                             EnvironmentRepository environmentRepository, AuditService auditService) {
        this.tokenService = tokenService;
        this.accessService = accessService;
        this.operationsService = operationsService;
        this.securityService = securityService;
        this.environmentRepository = environmentRepository;
        this.auditService = auditService;
    }

    /** What the confirm page shows. */
    public record Preview(String type, String environmentId, String environmentName, String description,
                          String confirmLabel, Instant expiresAt) {}

    /** What happened. */
    public record Result(String type, String environmentId, String message) {}

    @Transactional(readOnly = true)
    public Preview preview(String token) {
        ChatActionTokenService.Claims claims = tokenService.verify(token);
        Environment environment = environment(claims);
        return switch (claims.type()) {
            case APPROVE_ACCESS_REQUEST -> {
                EnvironmentAccessRequest request = checkApprove(claims);
                yield new Preview(claims.type().name(), claims.environmentId(), name(environment),
                        describe(request, environment), "Approve", claims.expiresAt());
            }
            case STOP_ENVIRONMENT -> {
                securityService.assertCanOperate(claims.environmentId());
                yield new Preview(claims.type().name(), claims.environmentId(), name(environment),
                        "Stop every running VM in " + name(environment) + ".", "Stop environment", claims.expiresAt());
            }
            case EXTEND_LEASE, SNOOZE_IDLE_STOP -> throw notYet(claims.type());
        };
    }

    @Transactional
    public Result execute(String token, String userId) {
        ChatActionTokenService.Claims claims = tokenService.verify(token);
        try {
            Result result = run(claims, userId);
            auditService.logAction(userId, AuditAction.CHAT_ACTION_EXECUTED, "chat_action", claims.jti(), claims.type().name(),
                    claims.type() + " " + VIA_CHAT + " (link " + claims.jti() + "): " + result.message());
            return result;
        } catch (RuntimeException e) {
            auditService.logFailure(userId, AuditAction.CHAT_ACTION_EXECUTED, "chat_action", claims.jti(), claims.type().name(),
                    claims.type() + " " + VIA_CHAT + " (link " + claims.jti() + ") refused: " + e.getMessage());
            throw e;
        }
    }

    private Result run(ChatActionTokenService.Claims claims, String userId) {
        Environment environment = environment(claims);
        switch (claims.type()) {
            case APPROVE_ACCESS_REQUEST -> {
                EnvironmentAccessRequest request = checkApprove(claims);
                // Read names first: marking the link used clears the persistence context.
                String message = "Approved " + requesterName(request) + "'s request on " + name(environment) + ".";
                tokenService.markUsed(claims.jti(), userId);
                accessService.approveRequest(request.getRequestId(), userId, "Approved " + VIA_CHAT, null);
                return new Result(claims.type().name(), claims.environmentId(), message);
            }
            case STOP_ENVIRONMENT -> {
                securityService.assertCanOperate(claims.environmentId());
                String message = "Stopping " + name(environment) + ".";
                tokenService.markUsed(claims.jti(), userId);
                StartOperationDTO stop = new StartOperationDTO();
                stop.setOperationType(OperationType.STOP);
                stop.setReason("Stopped " + VIA_CHAT);
                operationsService.startOperation(claims.environmentId(), userId, stop);
                return new Result(claims.type().name(), claims.environmentId(), message);
            }
            default -> throw notYet(claims.type());
        }
    }

    /** The reviewer administers the request's environment and is not its requester; the link matches it. */
    private EnvironmentAccessRequest checkApprove(ChatActionTokenService.Claims claims) {
        EnvironmentAccessRequest request = accessService.getAccessRequest(claims.targetId());
        if (request.getEnvironment() == null || !request.getEnvironment().getEnvironmentId().equals(claims.environmentId())) {
            throw new ValidationException("This link is not valid");
        }
        securityService.assertCanAdminister(claims.environmentId());
        if (request.getRequester() != null) {
            securityService.assertNotSelf(request.getRequester().getUserId(), "You cannot approve your own request");
        }
        return request;
    }

    private static String describe(EnvironmentAccessRequest request, Environment environment) {
        String level = request.getRequestedAccessLevel() == null ? "" : request.getRequestedAccessLevel().name() + " ";
        String period = request.getDurationDays() == null ? " with no end date" : " for " + request.getDurationDays() + " days";
        return "Approve " + requesterName(request) + "'s request for " + level + "access on " + name(environment) + period + ".";
    }

    private static String requesterName(EnvironmentAccessRequest request) {
        if (request.getRequester() == null) {
            return "a user";
        }
        String name = request.getRequester().getDisplayName();
        return name != null && !name.isBlank() ? name : request.getRequester().getEmail();
    }

    private Environment environment(ChatActionTokenService.Claims claims) {
        if (claims.environmentId() == null) {
            throw new ValidationException("This link is not valid");
        }
        return environmentRepository.findById(claims.environmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Environment", claims.environmentId()));
    }

    private static String name(Environment environment) {
        return environment.getDisplayName() != null ? environment.getDisplayName() : environment.getName();
    }

    private static ValidationException notYet(ChatActionType type) {
        return new ValidationException(type + " from chat is not yet supported");
    }
}
