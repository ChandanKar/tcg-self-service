package com.tcgdigital.vmcontrol.service.idle;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.IdleStopSnooze;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.IdleStopSnoozeRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Snoozes pause idle auto-stop for an environment for 1, 4 or 8 hours (E16-T04, G5). Anyone who
 * can operate the environment can snooze; a new snooze extends an active one; the snoozer or an
 * environment admin can end it. Audited.
 */
@Service
public class IdleStopSnoozeService {

    static final Set<Integer> ALLOWED_HOURS = Set.of(1, 4, 8);

    private final IdleStopSnoozeRepository snoozeRepository;
    private final EnvironmentRepository environmentRepository;
    private final AuditService auditService;
    private Clock clock = Clock.systemUTC();

    public IdleStopSnoozeService(IdleStopSnoozeRepository snoozeRepository, EnvironmentRepository environmentRepository,
                                 AuditService auditService) {
        this.snoozeRepository = snoozeRepository;
        this.environmentRepository = environmentRepository;
        this.auditService = auditService;
    }

    public Optional<IdleStopSnooze> getActive(String environmentId) {
        List<IdleStopSnooze> active = snoozeRepository.findActive(environmentId, Timestamp.from(clock.instant()));
        return active.isEmpty() ? Optional.empty() : Optional.of(active.get(0));
    }

    @Transactional
    public IdleStopSnooze snooze(String environmentId, Integer hours, String reason, String userId) {
        if (hours == null || !ALLOWED_HOURS.contains(hours)) {
            throw new ValidationException("Snooze for 1, 4 or 8 hours");
        }
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        Instant until = clock.instant().plus(Duration.ofHours(hours)).truncatedTo(ChronoUnit.SECONDS);
        IdleStopSnooze snooze = getActive(environmentId).orElseGet(() -> {
            IdleStopSnooze s = new IdleStopSnooze();
            s.setSnoozeId(UUID.randomUUID().toString());
            s.setEnvironmentId(environmentId);
            return s;
        });
        // Extends an active snooze, never shortens it.
        if (snooze.getSnoozedUntil() == null || snooze.getSnoozedUntil().toInstant().isBefore(until)) {
            snooze.setSnoozedUntil(Timestamp.from(until));
        }
        snooze.setSnoozedByUserId(userId);
        snooze.setReason(reason != null && !reason.isBlank() ? truncate(reason.trim()) : null);
        snooze = snoozeRepository.save(snooze);
        auditService.logEnvironmentAction(userId, AuditAction.IDLE_STOP_SNOOZED, environmentId, environment.getName(),
                "environment", environmentId, environment.getName(),
                "Idle auto-stop snoozed for " + hours + " h until " + snooze.getSnoozedUntil().toInstant()
                        + (snooze.getReason() != null ? ": " + snooze.getReason() : ""));
        return snooze;
    }

    /** End the active snooze; allowed for the user who set it or an environment admin. */
    @Transactional
    public void cancel(String environmentId, String userId, boolean canAdminister) {
        IdleStopSnooze snooze = getActive(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("No active snooze for this environment"));
        if (!canAdminister && !userId.equals(snooze.getSnoozedByUserId())) {
            throw new AccessDeniedException("Only the person who snoozed or an environment admin can end the snooze");
        }
        Environment environment = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        snooze.setSnoozedUntil(Timestamp.from(clock.instant().truncatedTo(ChronoUnit.SECONDS)));
        snoozeRepository.save(snooze);
        auditService.logEnvironmentAction(userId, AuditAction.IDLE_STOP_SNOOZED, environmentId, environment.getName(),
                "environment", environmentId, environment.getName(), "Idle auto-stop snooze ended");
    }

    private static String truncate(String text) {
        return text.length() > 255 ? text.substring(0, 252) + "..." : text;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
