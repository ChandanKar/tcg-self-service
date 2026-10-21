package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.BulkGrantPlanDTO;
import com.tcgdigital.vmcontrol.dto.BulkGrantRequestDTO;
import com.tcgdigital.vmcontrol.dto.BulkOnboardRequestDTO;
import com.tcgdigital.vmcontrol.dto.BulkOnboardResultDTO;
import com.tcgdigital.vmcontrol.dto.BulkOnboardResultDTO.Outcome;
import com.tcgdigital.vmcontrol.dto.DirectoryUserDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Add people (U13-T02, AddUsers.dc.html): many people, one role, optional access and a team per
 * person, previewed first. Each person is NEW (gets an account with the role, department and
 * project code), EXISTING (already uses the app: role and profile stay, access and team are
 * added) or SKIPPED with the reason. Access goes through the bulk grant (U10-T02), so the same
 * new / extend / upgrade / skip rules apply and nothing is ever lowered. Applying runs in one
 * transaction: a target that cannot be granted rolls back every account it would have made.
 */
@Service
public class BulkOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(BulkOnboardingService.class);

    private final UserService userService;
    private final UserRepository userRepository;
    private final GraphDirectoryService graphDirectoryService;
    private final BulkAccessGrantService bulkGrants;
    private final TeamService teamService;
    private final TeamRepository teamRepository;

    public BulkOnboardingService(UserService userService, UserRepository userRepository,
                                 GraphDirectoryService graphDirectoryService, BulkAccessGrantService bulkGrants,
                                 TeamService teamService, TeamRepository teamRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.graphDirectoryService = graphDirectoryService;
        this.bulkGrants = bulkGrants;
        this.teamService = teamService;
        this.teamRepository = teamRepository;
    }

    /** One person as understood: who they are, what happens to them and why. */
    private static final class Resolved {
        String email;
        String displayName;
        String azureAdObjectId;
        String department;
        String projectCode;
        String teamId;
        Outcome outcome;
        String reason;
        User user;
    }

    /** dryRun: what would happen, nothing written. Otherwise all of it, or nothing. */
    @Transactional
    public BulkOnboardResultDTO run(BulkOnboardRequestDTO request, String actorUserId, boolean dryRun) {
        if (request.people() == null || request.people().isEmpty()) {
            throw new ValidationException("Add at least one person");
        }
        List<Resolved> people = resolve(request.people());
        List<BulkGrantRequestDTO> grants = grantRequests(request);

        List<BulkGrantPlanDTO.Row> rows = new ArrayList<>();
        List<Resolved> receiving = people.stream().filter(p -> p.outcome != Outcome.SKIPPED).toList();
        if (dryRun) {
            List<String> existing = receiving.stream().filter(p -> p.outcome == Outcome.EXISTING).map(p -> p.email).toList();
            for (BulkGrantRequestDTO grant : grants) {
                for (Resolved p : receiving) {
                    if (p.outcome == Outcome.NEW) {
                        rows.addAll(bulkGrants.rowsForNewPerson(p.displayName, p.email, grant));
                    }
                }
                if (!existing.isEmpty()) {
                    rows.addAll(bulkGrants.preview(actorUserId, withPeople(grant, existing)).rows());
                } else if (receiving.isEmpty()) {
                    bulkGrants.rowsForNewPerson("", "", grant); // still say when a target cannot be granted
                }
            }
            return result(false, people, rows);
        }

        // Every target is checked before any account is made.
        grants.forEach(grant -> bulkGrants.rowsForNewPerson("", "", grant));
        boolean admin = request.role() == BulkOnboardRequestDTO.Role.ADMIN;
        boolean envAdmin = request.role() == BulkOnboardRequestDTO.Role.ENV_ADMIN;
        for (Resolved p : receiving) {
            if (p.outcome == Outcome.NEW) {
                p.user = userService.createOnboardedUser(p.email, p.displayName, p.azureAdObjectId, admin, envAdmin,
                        actorUserId, p.department, p.projectCode);
            }
            if (p.teamId != null) {
                teamService.addMember(p.teamId, p.user.getUserId(), actorUserId);
            }
        }
        List<String> emails = receiving.stream().map(p -> p.email).toList();
        if (!emails.isEmpty()) {
            for (BulkGrantRequestDTO grant : grants) {
                rows.addAll(bulkGrants.apply(actorUserId, withPeople(grant, emails)).rows());
            }
        }
        log.info("Add people by {}: {} new, {} existing, {} skipped, {} access rows", actorUserId,
                count(people, Outcome.NEW), count(people, Outcome.EXISTING), count(people, Outcome.SKIPPED), rows.size());
        return result(true, people, rows);
    }

    // ---- people ----

    private List<Resolved> resolve(List<BulkOnboardRequestDTO.Person> input) {
        List<Resolved> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (BulkOnboardRequestDTO.Person person : input) {
            Resolved r = new Resolved();
            r.teamId = blankToNull(person.teamId());
            String directoryId = blankToNull(person.directoryObjectId());
            String directoryDepartment = null;
            if (directoryId != null && graphDirectoryService.isEnabled()) {
                DirectoryUserDTO found;
                try {
                    found = graphDirectoryService.fetchByObjectId(directoryId);
                } catch (RuntimeException e) {
                    out.add(skipped(r, blankToNull(person.email()), person.displayName(), "Not found in the directory"));
                    continue;
                }
                r.azureAdObjectId = found.directoryObjectId();
                r.email = blankToNull(found.email());
                r.displayName = firstNonBlank(found.displayName(), person.displayName());
                directoryDepartment = found.department();
            } else {
                r.email = blankToNull(person.email());
                r.displayName = blankToNull(person.displayName());
            }
            if (r.email == null) {
                out.add(skipped(r, null, r.displayName, "No email address"));
                continue;
            }
            r.email = r.email.toLowerCase(Locale.ROOT);
            if (r.displayName == null) {
                r.displayName = r.email.substring(0, Math.max(1, r.email.indexOf('@')));
            }
            if (!seen.add(r.email)) {
                out.add(skipped(r, r.email, r.displayName, "Listed twice"));
                continue;
            }
            try {
                r.department = UserService.cleanDepartment(firstNonBlank(person.department(), directoryDepartment));
                r.projectCode = UserService.cleanProjectCode(person.projectCode());
            } catch (ValidationException e) {
                throw new ValidationException(r.email + ": " + e.getMessage());
            }
            if (r.teamId != null && teamRepository.findById(r.teamId).isEmpty()) {
                throw new ValidationException(r.email + ": team not found");
            }
            Optional<User> existing = r.azureAdObjectId != null ? userRepository.findByAzureAdObjectId(r.azureAdObjectId) : Optional.empty();
            if (existing.isEmpty()) {
                existing = userService.getUserByEmail(r.email);
            }
            if (existing.isPresent()) {
                r.user = existing.get();
                if (!Boolean.TRUE.equals(r.user.getIsActive())) {
                    out.add(skipped(r, r.email, r.displayName, "Deactivated: reactivate them first"));
                    continue;
                }
                r.email = r.user.getEmail();
                r.displayName = firstNonBlank(r.user.getDisplayName(), r.displayName);
                r.outcome = Outcome.EXISTING;
                r.reason = "Already a user: access and team only";
            } else {
                r.outcome = Outcome.NEW;
            }
            out.add(r);
        }
        return out;
    }

    private static Resolved skipped(Resolved r, String email, String name, String reason) {
        r.email = email;
        r.displayName = blankToNull(name);
        r.outcome = Outcome.SKIPPED;
        r.reason = reason;
        return r;
    }

    // ---- access ----

    /** One bulk grant per (level, days), each with its targets. */
    private static List<BulkGrantRequestDTO> grantRequests(BulkOnboardRequestDTO request) {
        Map<String, List<BulkGrantRequestDTO.Target>> targets = new LinkedHashMap<>();
        Map<String, BulkOnboardRequestDTO.Access> firstOf = new LinkedHashMap<>();
        for (BulkOnboardRequestDTO.Access a : request.access() == null ? List.<BulkOnboardRequestDTO.Access>of() : request.access()) {
            String key = a.accessLevel() + "|" + a.durationDays();
            firstOf.putIfAbsent(key, a);
            targets.computeIfAbsent(key, k -> new ArrayList<>()).add(new BulkGrantRequestDTO.Target(a.environmentId(),
                    a.scopeType() == null ? AccessScopeType.ENVIRONMENT : a.scopeType(), a.groupIds()));
        }
        List<BulkGrantRequestDTO> out = new ArrayList<>();
        targets.forEach((key, list) -> {
            BulkOnboardRequestDTO.Access a = firstOf.get(key);
            out.add(new BulkGrantRequestDTO(List.of(), List.of(), list, a.accessLevel(), a.durationDays(), null,
                    request.notes(), request.emailPeople()));
        });
        return out;
    }

    private static BulkGrantRequestDTO withPeople(BulkGrantRequestDTO grant, List<String> emails) {
        return new BulkGrantRequestDTO(emails, List.of(), grant.targets(), grant.accessLevel(), grant.durationDays(),
                grant.clearExpiry(), grant.notes(), grant.emailPeople());
    }

    // ---- result ----

    private static BulkOnboardResultDTO result(boolean applied, List<Resolved> people, List<BulkGrantPlanDTO.Row> rows) {
        List<BulkOnboardResultDTO.PersonResult> results = people.stream().map(p -> {
            List<BulkGrantPlanDTO.Row> mine = p.email == null ? List.of()
                    : rows.stream().filter(r -> p.email.equalsIgnoreCase(r.userEmail())).toList();
            return new BulkOnboardResultDTO.PersonResult(p.email, p.displayName, p.outcome, p.reason,
                    p.user == null ? null : p.user.getUserId(), p.teamId,
                    count(mine, BulkGrantPlanDTO.Outcome.NEW), changed(mine), count(mine, BulkGrantPlanDTO.Outcome.SKIP));
        }).toList();
        return new BulkOnboardResultDTO(applied, count(people, Outcome.NEW), count(people, Outcome.EXISTING),
                count(people, Outcome.SKIPPED), count(rows, BulkGrantPlanDTO.Outcome.NEW), changed(rows),
                count(rows, BulkGrantPlanDTO.Outcome.SKIP), results, rows);
    }

    private static int count(List<Resolved> people, Outcome outcome) {
        return (int) people.stream().filter(p -> p.outcome == outcome).count();
    }

    private static int count(List<BulkGrantPlanDTO.Row> rows, BulkGrantPlanDTO.Outcome outcome) {
        return (int) rows.stream().filter(r -> r.outcome() == outcome).count();
    }

    private static int changed(List<BulkGrantPlanDTO.Row> rows) {
        return (int) rows.stream().filter(r -> r.outcome() == BulkGrantPlanDTO.Outcome.EXTEND
                || r.outcome() == BulkGrantPlanDTO.Outcome.UPGRADE).count();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String firstNonBlank(String a, String b) {
        String x = blankToNull(a);
        return x != null ? x : blankToNull(b);
    }
}
