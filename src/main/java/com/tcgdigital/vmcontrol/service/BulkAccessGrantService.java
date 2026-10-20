package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.BulkGrantPlanDTO;
import com.tcgdigital.vmcontrol.dto.BulkGrantPlanDTO.Outcome;
import com.tcgdigital.vmcontrol.dto.BulkGrantRequestDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.TeamMemberRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.service.support.AfterCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Bulk grants (U10-T02, GrantAccess.dc.html): one level to many people (by email, or a team's
 * members as of now) on many environments or groups, previewed first. Every target is
 * authorized before anything is written (no partial grants). Each (person, scope) row is NEW,
 * EXTEND (same level, later end), UPGRADE (higher level; the end never moves earlier) or SKIP
 * with the reason: bulk never lowers a level or shortens an end, and never changes the
 * reviewer's own access. Applying writes the rows in one transaction through the same upsert
 * and audit as a direct grant, then sends one message per person.
 */
@Service
public class BulkAccessGrantService {

    private static final Logger log = LoggerFactory.getLogger(BulkAccessGrantService.class);

    static final int MAX_ROWS = 500;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final EnvironmentAccessService accessService;
    private final SecurityService securityService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final EnvironmentRepository environmentRepository;
    private final VmGroupRepository vmGroupRepository;
    private final NotificationService notificationService;
    private final AfterCommit afterCommit;

    public BulkAccessGrantService(EnvironmentAccessService accessService, SecurityService securityService,
                                  UserService userService, UserRepository userRepository, TeamRepository teamRepository,
                                  TeamMemberRepository teamMemberRepository, EnvironmentRepository environmentRepository,
                                  VmGroupRepository vmGroupRepository, NotificationService notificationService,
                                  AfterCommit afterCommit) {
        this.accessService = accessService;
        this.securityService = securityService;
        this.userService = userService;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.teamMemberRepository = teamMemberRepository;
        this.environmentRepository = environmentRepository;
        this.vmGroupRepository = vmGroupRepository;
        this.notificationService = notificationService;
        this.afterCommit = afterCommit;
    }

    /** One scope to grant: the whole environment (group null) or one of its groups. */
    private record Scope(Environment environment, VmGroup group) {
        AccessScopeType type() {
            return group == null ? AccessScopeType.ENVIRONMENT : AccessScopeType.GROUP;
        }

        String id() {
            return group == null ? environment.getEnvironmentId() : group.getGroupId();
        }

        String name() {
            return group == null ? envName(environment) : envName(environment) + ", " + group.getDisplayName() + " group";
        }
    }

    /** A planned row with what to pass when applying it. */
    private record Planned(BulkGrantPlanDTO.Row row, Scope scope, User user, Integer days, boolean clear) {
    }

    /** What the grant would do; writes nothing. */
    @Transactional(readOnly = true)
    public BulkGrantPlanDTO preview(String actorUserId, BulkGrantRequestDTO dto) {
        return summary(plan(actorUserId, dto));
    }

    /** Recomputes the plan and applies every row that changes something, all or nothing. */
    @Transactional
    public BulkGrantPlanDTO apply(String actorUserId, BulkGrantRequestDTO dto) {
        List<Planned> plan = plan(actorUserId, dto);
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", actorUserId));
        Set<String> changedEnvironments = new LinkedHashSet<>();
        Map<String, List<String>> linesByUser = new LinkedHashMap<>();
        for (Planned p : plan) {
            if (p.row().outcome() == Outcome.SKIP) {
                continue;
            }
            boolean changed = accessService.applyBulkRow(actor, p.scope().environment(), p.scope().group(), p.user(),
                    dto.accessLevel(), p.days(), p.clear(), blankToNull(dto.notes()));
            if (changed) {
                changedEnvironments.add(p.scope().environment().getEnvironmentId());
            }
            linesByUser.computeIfAbsent(p.user().getUserId(), k -> new ArrayList<>())
                    .add(levelText(dto.accessLevel()) + " on " + p.scope().name() + endText(p.row().resultExpiresAt()));
        }
        boolean email = Boolean.TRUE.equals(dto.emailPeople());
        linesByUser.forEach((userId, lines) -> afterCommit.run(() -> {
            try {
                notificationService.notifyBulkAccessGranted(userId, lines, email, actorUserId);
            } catch (RuntimeException e) {
                log.warn("Could not tell {} about their new access: {}", userId, e.getMessage());
            }
        }));
        changedEnvironments.forEach(accessService::accessGrantedAutomation);
        log.info("Bulk grant by {}: {} rows applied", actorUserId, linesByUser.values().stream().mapToInt(List::size).sum());
        return summary(plan);
    }

    // ---- planning ----

    private List<Planned> plan(String actorUserId, BulkGrantRequestDTO dto) {
        if (dto.durationDays() != null && Boolean.TRUE.equals(dto.clearExpiry())) {
            throw new ValidationException("Choose a duration or no end date, not both");
        }
        if (dto.durationDays() != null && dto.durationDays() > accessService.getGrantMaxDurationDays()) {
            throw new ValidationException("Access can be granted for at most " + accessService.getGrantMaxDurationDays() + " days");
        }
        List<User> people = people(dto);
        List<Scope> scopes = scopes(dto);
        int rows = people.size() * scopes.size();
        if (rows > MAX_ROWS) {
            throw new ValidationException("This would make " + rows + " changes; at most " + MAX_ROWS + " at once");
        }
        LocalDateTime now = LocalDateTime.now();
        Timestamp newEnd = dto.durationDays() == null ? null : Timestamp.valueOf(now.plusDays(dto.durationDays()));
        boolean clear = Boolean.TRUE.equals(dto.clearExpiry());

        List<Planned> plan = new ArrayList<>();
        for (User person : people) {
            for (Scope scope : scopes) {
                plan.add(row(actorUserId, person, scope, dto.accessLevel(), newEnd, dto.durationDays(), clear));
            }
        }
        return plan;
    }

    private Planned row(String actorUserId, User person, Scope scope, AccessLevel level, Timestamp newEnd,
                        Integer days, boolean clear) {
        Optional<EnvironmentAccess> current = accessService.activeGrant(person.getUserId(), scope.type(), scope.id());
        AccessLevel currentLevel = current.map(EnvironmentAccess::getAccessLevel).orElse(null);
        Timestamp currentEnd = current.map(EnvironmentAccess::getExpiresAt).orElse(null);

        if (person.getUserId().equals(actorUserId)) {
            return planned(person, scope, Outcome.SKIP, currentLevel, currentEnd, currentLevel, currentEnd,
                    "You can't change your own access", null, false);
        }
        if (current.isEmpty()) {
            return planned(person, scope, Outcome.NEW, null, null, level, clear ? null : newEnd, null, days, clear);
        }
        if (currentLevel.ordinal() > level.ordinal()) {
            return planned(person, scope, Outcome.SKIP, currentLevel, currentEnd, currentLevel, currentEnd,
                    "Already has " + levelText(currentLevel) + ", which is more", null, false);
        }
        // The end never moves earlier: no end beats any date, a later date beats an earlier one.
        boolean openEnd = currentEnd == null;
        boolean laterEnd = clear ? !openEnd : (newEnd != null && !openEnd && newEnd.after(currentEnd));
        Timestamp resultEnd = laterEnd ? (clear ? null : newEnd) : currentEnd;
        Integer applyDays = laterEnd && !clear ? days : null;
        boolean applyClear = laterEnd && clear;

        if (currentLevel.ordinal() < level.ordinal()) {
            return planned(person, scope, Outcome.UPGRADE, currentLevel, currentEnd, level, resultEnd, null, applyDays, applyClear);
        }
        if (laterEnd) {
            return planned(person, scope, Outcome.EXTEND, currentLevel, currentEnd, level, resultEnd, null, applyDays, applyClear);
        }
        return planned(person, scope, Outcome.SKIP, currentLevel, currentEnd, currentLevel, currentEnd,
                "Already has " + levelText(currentLevel) + (openEnd ? " with no end date" : " until " + day(currentEnd)),
                null, false);
    }

    private static Planned planned(User person, Scope scope, Outcome outcome, AccessLevel currentLevel, Timestamp currentEnd,
                                   AccessLevel resultLevel, Timestamp resultEnd, String reason, Integer days, boolean clear) {
        BulkGrantPlanDTO.Row row = new BulkGrantPlanDTO.Row(person.getUserId(), nameOf(person), person.getEmail(),
                scope.environment().getEnvironmentId(), envName(scope.environment()), scope.type().name(), scope.id(),
                scope.name(), outcome, currentLevel == null ? null : currentLevel.name(), currentEnd,
                resultLevel == null ? null : resultLevel.name(), resultEnd, reason);
        return new Planned(row, scope, person, days, clear);
    }

    private static BulkGrantPlanDTO summary(List<Planned> plan) {
        List<BulkGrantPlanDTO.Row> rows = plan.stream().map(Planned::row).toList();
        int people = (int) rows.stream().map(BulkGrantPlanDTO.Row::userId).distinct().count();
        int environments = (int) rows.stream().map(BulkGrantPlanDTO.Row::environmentId).distinct().count();
        int skipped = (int) rows.stream().filter(r -> r.outcome() == Outcome.SKIP).count();
        return new BulkGrantPlanDTO(people, environments, rows.size() - skipped, skipped, rows);
    }

    /** The people: app users by email (unknown or inactive ones refused, named) and teams' active members now. */
    private List<User> people(BulkGrantRequestDTO dto) {
        Map<String, User> byId = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String email : distinct(dto.userEmails())) {
            Optional<User> user = userService.getUserByEmail(email);
            if (user.isEmpty() || !Boolean.TRUE.equals(user.get().getIsActive())) {
                unknown.add(email);
            } else {
                byId.putIfAbsent(user.get().getUserId(), user.get());
            }
        }
        if (!unknown.isEmpty()) {
            throw new ValidationException("Not people of this app: " + String.join(", ", unknown));
        }
        List<User> members = new ArrayList<>();
        for (String teamId : distinct(dto.teamIds())) {
            teamRepository.findById(teamId).orElseThrow(() -> new ResourceNotFoundException("Team", teamId));
            userRepository.findAllById(teamMemberRepository.findByTeamId(teamId).stream()
                            .map(com.tcgdigital.vmcontrol.model.TeamMember::getUserId).toList()).stream()
                    .filter(u -> Boolean.TRUE.equals(u.getIsActive()))
                    .forEach(members::add);
        }
        members.stream().sorted(Comparator.comparing(BulkAccessGrantService::nameOf, String.CASE_INSENSITIVE_ORDER))
                .forEach(u -> byId.putIfAbsent(u.getUserId(), u));
        if (byId.isEmpty()) {
            throw new ValidationException("Choose at least one person or a team with members");
        }
        return new ArrayList<>(byId.values());
    }

    /** The scopes, every one authorized before anything is planned (403 names none of the others). */
    private List<Scope> scopes(BulkGrantRequestDTO dto) {
        List<Scope> scopes = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (BulkGrantRequestDTO.Target target : dto.targets()) {
            Environment environment = environmentRepository.findById(target.environmentId())
                    .orElseThrow(() -> new ResourceNotFoundException("Environment", target.environmentId()));
            if (target.scopeType() == AccessScopeType.GROUP) {
                if (!accessService.isGroupScopeEnabled()) {
                    throw new ResourceNotFoundException("Group-scoped access", "feature disabled");
                }
                List<String> groupIds = distinct(target.groupIds());
                if (groupIds.isEmpty()) {
                    throw new ValidationException("Choose at least one group in " + envName(environment));
                }
                for (String groupId : groupIds) {
                    VmGroup group = vmGroupRepository.findById(groupId)
                            .orElseThrow(() -> new ResourceNotFoundException("Group", groupId));
                    if (!group.getEnvironment().getEnvironmentId().equals(environment.getEnvironmentId())) {
                        throw new ValidationException("Group " + groupId + " is not in " + envName(environment));
                    }
                    if (!securityService.canManageGroupAccess(groupId)) {
                        throw new UnauthorizedException("You cannot manage access on " + envName(environment));
                    }
                    if (seen.add("G:" + groupId)) {
                        scopes.add(new Scope(environment, group));
                    }
                }
            } else {
                if (!securityService.canManageEnvironmentAccess(environment.getEnvironmentId())) {
                    throw new UnauthorizedException("You cannot manage access on " + envName(environment));
                }
                if (seen.add("E:" + environment.getEnvironmentId())) {
                    scopes.add(new Scope(environment, null));
                }
            }
        }
        return scopes;
    }

    // ---- text ----

    static String levelText(AccessLevel level) {
        return switch (level) {
            case ADMIN -> "Admin";
            case USER -> "Operator";
            case VIEWER -> "View only";
        };
    }

    private static String endText(Timestamp end) {
        return end == null ? ", no end date" : " until " + day(end);
    }

    private static String day(Timestamp ts) {
        return DAY.format(ts.toLocalDateTime());
    }

    private static String nameOf(User user) {
        return user.getDisplayName() != null && !user.getDisplayName().isBlank() ? user.getDisplayName() : user.getEmail();
    }

    private static String envName(Environment environment) {
        return environment.getDisplayName() != null ? environment.getDisplayName() : environment.getName();
    }

    private static List<String> distinct(List<String> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(v -> !v.isEmpty()).distinct().toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
