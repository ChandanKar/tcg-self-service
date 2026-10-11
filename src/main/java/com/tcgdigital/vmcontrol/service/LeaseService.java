package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.LeaseDTO;
import com.tcgdigital.vmcontrol.dto.LeasePolicyDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentLease;
import com.tcgdigital.vmcontrol.model.EnvironmentLeaseParticipant;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseParticipantRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentLeaseRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.service.support.AuditChanges;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Environment leases (E15): every opted-in running environment has an end time. This class
 * holds the policy (platform limits from lease.*, per-environment default and maximum) and reads
 * leases; later tasks add opening, extending and expiring them. Everything is a no-op or a 404
 * while lease.enabled is false.
 */
@Service
public class LeaseService {

    /** Shortest lease an owner may configure. */
    public static final int MIN_MINUTES = 15;

    private final EnvironmentRepository environmentRepository;
    private final EnvironmentLeaseRepository leaseRepository;
    private final EnvironmentLeaseParticipantRepository participantRepository;
    private final UserRepository userRepository;
    private final SecurityService securityService;
    private final AuditService auditService;
    private final UserService userService;

    private final boolean featureEnabled;
    private final int platformDefaultMinutes;
    private final int platformMaxMinutes;
    private final int platformAbsoluteMaxMinutes;

    public LeaseService(EnvironmentRepository environmentRepository,
                        EnvironmentLeaseRepository leaseRepository,
                        EnvironmentLeaseParticipantRepository participantRepository,
                        UserRepository userRepository,
                        @Lazy SecurityService securityService,
                        AuditService auditService,
                        UserService userService,
                        @Value("${lease.enabled:false}") boolean featureEnabled,
                        @Value("${lease.platform.default-minutes:240}") int platformDefaultMinutes,
                        @Value("${lease.platform.max-minutes:720}") int platformMaxMinutes,
                        @Value("${lease.platform.absolute-max-minutes:4320}") int platformAbsoluteMaxMinutes) {
        this.environmentRepository = environmentRepository;
        this.leaseRepository = leaseRepository;
        this.participantRepository = participantRepository;
        this.userRepository = userRepository;
        this.securityService = securityService;
        this.auditService = auditService;
        this.userService = userService;
        this.featureEnabled = featureEnabled;
        this.platformDefaultMinutes = platformDefaultMinutes;
        this.platformMaxMinutes = platformMaxMinutes;
        this.platformAbsoluteMaxMinutes = platformAbsoluteMaxMinutes;
    }

    // ---------------------------------------------------------------- policy

    /** The master switch, lease.enabled. */
    public boolean isFeatureEnabled() {
        return featureEnabled;
    }

    /** Leases apply to this environment: the feature is on and the owner opted it in. */
    public boolean isEnabledFor(Environment env) {
        return featureEnabled && env != null && Boolean.TRUE.equals(env.getLeaseEnabled());
    }

    /** The owner's default, or the platform default; never above the effective maximum. */
    public int effectiveDefault(Environment env) {
        int value = env.getLeaseDefaultMinutes() != null ? env.getLeaseDefaultMinutes() : platformDefaultMinutes;
        return Math.min(value, effectiveMax(env));
    }

    /** The owner's maximum, or the platform maximum; never above the platform maximum. */
    public int effectiveMax(Environment env) {
        int value = env.getLeaseMaxMinutes() != null ? env.getLeaseMaxMinutes() : platformMaxMinutes;
        return Math.min(value, platformMaxMinutes);
    }

    public int getPlatformAbsoluteMaxMinutes() {
        return platformAbsoluteMaxMinutes;
    }

    /** 404 for every lease endpoint while the feature is off. */
    public void assertFeatureEnabled() {
        if (!featureEnabled) {
            throw new ResourceNotFoundException("Leases", "feature disabled");
        }
    }

    @Transactional(readOnly = true)
    public LeasePolicyDTO getPolicy(String environmentId) {
        assertFeatureEnabled();
        securityService.assertCanView(environmentId);
        return toPolicyDto(loadEnvironment(environmentId), securityService.canManageEnvironmentAccess(environmentId));
    }

    /**
     * Owners set the policy within the platform limits: {@value #MIN_MINUTES} <= default <= max <=
     * lease.platform.max-minutes (null minutes mean the platform value). Changes are audited.
     */
    @Transactional
    public LeasePolicyDTO updatePolicy(String environmentId, LeasePolicyDTO dto) {
        assertFeatureEnabled();
        securityService.assertCanView(environmentId);
        if (!securityService.canManageEnvironmentAccess(environmentId)) {
            throw new com.tcgdigital.vmcontrol.exception.UnauthorizedException(
                    "Only the environment's owners can change its lease policy");
        }
        Environment env = loadEnvironment(environmentId);
        validatePolicy(dto.getDefaultMinutes(), dto.getMaxMinutes());

        AuditChanges changes = new AuditChanges()
                .add("leaseEnabled", env.getLeaseEnabled(), dto.getEnabled())
                .add("leaseDefaultMinutes", env.getLeaseDefaultMinutes(), dto.getDefaultMinutes())
                .add("leaseMaxMinutes", env.getLeaseMaxMinutes(), dto.getMaxMinutes());
        env.setLeaseEnabled(Boolean.TRUE.equals(dto.getEnabled()));
        env.setLeaseDefaultMinutes(dto.getDefaultMinutes());
        env.setLeaseMaxMinutes(dto.getMaxMinutes());
        environmentRepository.save(env);
        if (!changes.isEmpty()) {
            auditService.logEnvironmentUpdated(userService.getCurrentUserId(), env.getEnvironmentId(), env.getName(),
                    changes.toString());
        }
        return toPolicyDto(env, true);
    }

    void validatePolicy(Integer defaultMinutes, Integer maxMinutes) {
        int max = maxMinutes != null ? maxMinutes : platformMaxMinutes;
        int def = defaultMinutes != null ? defaultMinutes : Math.min(platformDefaultMinutes, max);
        if (max > platformMaxMinutes) {
            throw new ValidationException("The maximum lease is " + platformMaxMinutes + " minutes on this platform");
        }
        if (def < MIN_MINUTES || max < MIN_MINUTES) {
            throw new ValidationException("A lease is at least " + MIN_MINUTES + " minutes");
        }
        if (def > max) {
            throw new ValidationException("The default lease cannot be longer than the maximum");
        }
    }

    private LeasePolicyDTO toPolicyDto(Environment env, boolean canEdit) {
        LeasePolicyDTO dto = new LeasePolicyDTO();
        dto.setEnabled(Boolean.TRUE.equals(env.getLeaseEnabled()));
        dto.setDefaultMinutes(env.getLeaseDefaultMinutes());
        dto.setMaxMinutes(env.getLeaseMaxMinutes());
        dto.setEffectiveDefaultMinutes(effectiveDefault(env));
        dto.setEffectiveMaxMinutes(effectiveMax(env));
        dto.setPlatformDefaultMinutes(platformDefaultMinutes);
        dto.setPlatformMaxMinutes(platformMaxMinutes);
        dto.setPlatformAbsoluteMaxMinutes(platformAbsoluteMaxMinutes);
        dto.setCanEdit(canEdit);
        return dto;
    }

    // ---------------------------------------------------------------- leases

    /** The environment's active lease, or empty. */
    @Transactional(readOnly = true)
    public Optional<EnvironmentLease> getActiveLease(String environmentId) {
        return leaseRepository.findActiveByEnvironmentId(environmentId);
    }

    /** The active lease as the caller may see it; empty when there is none (204). */
    @Transactional(readOnly = true)
    public Optional<LeaseDTO> getActiveLeaseDto(String environmentId) {
        assertFeatureEnabled();
        securityService.assertCanView(environmentId);
        return leaseRepository.findActiveByEnvironmentId(environmentId).map(lease -> toDto(lease,
                new LeaseDTO.Permissions(securityService.canOperateInEnvironment(environmentId),
                        securityService.canManageEnvironmentAccess(environmentId))));
    }

    LeaseDTO toDto(EnvironmentLease lease, LeaseDTO.Permissions permissions) {
        List<EnvironmentLeaseParticipant> participants =
                participantRepository.findByLeaseIdAndLeftAtIsNullOrderByJoinedAtAsc(lease.getLeaseId());
        List<String> ids = new java.util.ArrayList<>(participants.stream().map(EnvironmentLeaseParticipant::getUserId).toList());
        ids.add(lease.getStartedByUserId());
        Map<String, User> users = new HashMap<>(userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity(), (a, b) -> a)));

        LeaseDTO dto = new LeaseDTO();
        dto.setLeaseId(lease.getLeaseId());
        dto.setEnvironmentId(lease.getEnvironment().getEnvironmentId());
        dto.setStatus(lease.getStatus().name());
        dto.setSource(lease.getSource().name());
        dto.setStartedByUserId(lease.getStartedByUserId());
        dto.setStartedByName(displayName(users.get(lease.getStartedByUserId())));
        dto.setStartedAt(lease.getStartedAt());
        dto.setEndsAt(lease.getEndsAt());
        dto.setExtendedMinutesTotal(lease.getExtendedMinutesTotal());
        dto.setParticipants(participants.stream()
                .map(p -> new LeaseDTO.Participant(p.getUserId(), displayName(users.get(p.getUserId())), p.getJoinedAt()))
                .toList());
        dto.setPermissions(permissions);
        return dto;
    }

    private static String displayName(User user) {
        if (user == null) return null;
        return user.getDisplayName() != null && !user.getDisplayName().isBlank() ? user.getDisplayName() : user.getEmail();
    }

    private Environment loadEnvironment(String environmentId) {
        return environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
    }
}
