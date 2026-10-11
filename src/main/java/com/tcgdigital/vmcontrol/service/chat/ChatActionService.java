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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Runs one-click chat actions (E24-T04) as the signed-in user: the same permission checks as the
 * matching page (approve = administers the environment and is not the requester; stop = may
 * operate, plus the lock check inside the operation; extend = the lease's own limits; snooze =
 * may operate, 1/4/8 hours). The link is used up in the same transaction as the action, so a
 * refused or failed action leaves it usable. Every attempt is audited.
 */
@Service
public class ChatActionService {

    static final String VIA_CHAT = "via chat";
    static final List<Integer> EXTEND_MINUTES = List.of(60, 120, 240);
    static final int DEFAULT_EXTEND_MINUTES = 120;
    static final List<Integer> SNOOZE_HOURS = List.of(1, 4, 8);
    static final int DEFAULT_SNOOZE_HOURS = 4;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final ChatActionTokenService tokenService;
    private final EnvironmentAccessService accessService;
    private final VmOperationsService operationsService;
    private final SecurityService securityService;
    private final EnvironmentRepository environmentRepository;
    private final AuditService auditService;
    private final com.tcgdigital.vmcontrol.service.LeaseService leaseService;
    private final com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository leaseRepository;
    private final com.tcgdigital.vmcontrol.service.idle.IdleStopSnoozeService snoozeService;
    private final com.tcgdigital.vmcontrol.repository.VmRepository vmRepository;

    public ChatActionService(ChatActionTokenService tokenService, EnvironmentAccessService accessService,
                             VmOperationsService operationsService, SecurityService securityService,
                             EnvironmentRepository environmentRepository, AuditService auditService,
                             com.tcgdigital.vmcontrol.service.LeaseService leaseService,
                             com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository leaseRepository,
                             com.tcgdigital.vmcontrol.service.idle.IdleStopSnoozeService snoozeService,
                             com.tcgdigital.vmcontrol.repository.VmRepository vmRepository) {
        this.tokenService = tokenService;
        this.accessService = accessService;
        this.operationsService = operationsService;
        this.securityService = securityService;
        this.environmentRepository = environmentRepository;
        this.auditService = auditService;
        this.leaseService = leaseService;
        this.leaseRepository = leaseRepository;
        this.snoozeService = snoozeService;
        this.vmRepository = vmRepository;
    }

    /**
     * What the confirm page shows. choices/defaultChoice: minutes (extend) or hours (snooze) the
     * user may pick; empty for actions without a choice.
     */
    public record Preview(String type, String environmentId, String environmentName, String description,
                          String confirmLabel, Instant expiresAt, List<Integer> choices, Integer defaultChoice,
                          String choiceUnit) {
        Preview(String type, String environmentId, String environmentName, String description, String confirmLabel,
                Instant expiresAt) {
            this(type, environmentId, environmentName, description, confirmLabel, expiresAt, List.of(), null, null);
        }
    }

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
            case EXTEND_LEASE -> {
                securityService.assertCanOperate(claims.environmentId());
                Instant end = activeLeaseEnd(claims.environmentId(), environment);
                yield new Preview(claims.type().name(), claims.environmentId(), name(environment),
                        "Keep " + name(environment) + " running longer. It now stops at " + TIME.format(end)
                                + "; 2 hours more would be " + TIME.format(later(end, DEFAULT_EXTEND_MINUTES)) + ".",
                        "Extend", claims.expiresAt(), EXTEND_MINUTES, DEFAULT_EXTEND_MINUTES, "minutes");
            }
            case SNOOZE_IDLE_STOP -> {
                securityService.assertCanOperate(claims.environmentId());
                assertStillRunning(claims.environmentId(), environment);
                yield new Preview(claims.type().name(), claims.environmentId(), name(environment),
                        "Pause idle auto-stop for " + name(environment) + ", so it keeps running while it looks idle.",
                        "Snooze", claims.expiresAt(), SNOOZE_HOURS, DEFAULT_SNOOZE_HOURS, "hours");
            }
        };
    }

    /** The active lease's end; 409 when the lease already ended (the environment is stopping or stopped). */
    private Instant activeLeaseEnd(String environmentId, Environment environment) {
        return leaseRepository.findActiveByEnvironmentId(environmentId)
                .map(l -> l.getEndsAt().toInstant())
                .orElseThrow(() -> new com.tcgdigital.vmcontrol.exception.ConflictException(
                        "The lease on " + name(environment) + " already ended, so there is nothing to extend"));
    }

    /** 409 when no VM is running: idle auto-stop already stopped it (or someone did). */
    private void assertStillRunning(String environmentId, Environment environment) {
        boolean running = vmRepository.findByEnvironmentId(environmentId).stream()
                .anyMatch(vm -> vm.getStatus() == com.tcgdigital.vmcontrol.model.VmStatus.RUNNING);
        if (!running) {
            throw new com.tcgdigital.vmcontrol.exception.ConflictException(name(environment) + " was already stopped");
        }
    }

    private static Instant later(Instant end, int minutes) {
        Instant base = end.isAfter(Instant.now()) ? end : Instant.now();
        return base.plusSeconds(minutes * 60L);
    }

    @Transactional
    public Result execute(String token, String userId) {
        return execute(token, userId, null);
    }

    /** choice: minutes for EXTEND_LEASE, hours for SNOOZE_IDLE_STOP (null = the default); ignored otherwise. */
    @Transactional
    public Result execute(String token, String userId, Integer choice) {
        ChatActionTokenService.Claims claims = tokenService.verify(token);
        try {
            Result result = run(claims, userId, choice);
            auditService.logAction(userId, AuditAction.CHAT_ACTION_EXECUTED, "chat_action", claims.jti(), claims.type().name(),
                    claims.type() + " " + VIA_CHAT + " (link " + claims.jti() + "): " + result.message());
            return result;
        } catch (RuntimeException e) {
            auditService.logFailure(userId, AuditAction.CHAT_ACTION_EXECUTED, "chat_action", claims.jti(), claims.type().name(),
                    claims.type() + " " + VIA_CHAT + " (link " + claims.jti() + ") refused: " + e.getMessage());
            throw e;
        }
    }

    private Result run(ChatActionTokenService.Claims claims, String userId, Integer choice) {
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
            case EXTEND_LEASE -> {
                int minutes = choice == null ? DEFAULT_EXTEND_MINUTES : choice;
                if (!EXTEND_MINUTES.contains(minutes)) {
                    throw new ValidationException("Extend by 1, 2 or 4 hours");
                }
                securityService.assertCanOperate(claims.environmentId());
                activeLeaseEnd(claims.environmentId(), environment);
                String envName = name(environment);
                tokenService.markUsed(claims.jti(), userId);
                // The lease's own rules apply: within its maximum it moves the end, beyond it it asks the owners.
                com.tcgdigital.vmcontrol.service.LeaseService.ExtendOutcome outcome =
                        leaseService.extend(claims.environmentId(), minutes, "Extended " + VIA_CHAT);
                String message = outcome.applied()
                        ? envName + " now runs until " + TIME.format(outcome.lease().getEndsAt().toInstant()) + "."
                        : "That is beyond " + envName + "'s maximum, so the owners were asked to approve it.";
                return new Result(claims.type().name(), claims.environmentId(), message);
            }
            case SNOOZE_IDLE_STOP -> {
                int hours = choice == null ? DEFAULT_SNOOZE_HOURS : choice;
                securityService.assertCanOperate(claims.environmentId());
                assertStillRunning(claims.environmentId(), environment);
                String envName = name(environment);
                tokenService.markUsed(claims.jti(), userId);
                com.tcgdigital.vmcontrol.model.IdleStopSnooze snooze =
                        snoozeService.snooze(claims.environmentId(), hours, "Snoozed " + VIA_CHAT, userId);
                return new Result(claims.type().name(), claims.environmentId(),
                        "Idle auto-stop is paused for " + envName + " until " + TIME.format(snooze.getSnoozedUntil().toInstant()) + ".");
            }
        }
        throw new IllegalStateException("Unhandled chat action " + claims.type());
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

}
