package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CostSplitRuleDTO;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CostSplitRule;
import com.tcgdigital.vmcontrol.model.CostSplitShare;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.repository.CostSplitRuleRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared-cost split rules (E23-T05): a shared environment's cost is charged to the listed teams
 * evenly, by fixed percentages, or in proportion to their direct cost. {@link #split} always
 * returns cents that sum exactly to the (cent-rounded) amount.
 */
@Service
public class CostSplitService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");

    private final CostSplitRuleRepository ruleRepository;
    private final EnvironmentRepository environmentRepository;
    private final TeamRepository teamRepository;
    private final AuditService auditService;

    public CostSplitService(CostSplitRuleRepository ruleRepository, EnvironmentRepository environmentRepository,
                            TeamRepository teamRepository, AuditService auditService) {
        this.ruleRepository = ruleRepository;
        this.environmentRepository = environmentRepository;
        this.teamRepository = teamRepository;
        this.auditService = auditService;
    }

    // ============= Split =============

    /**
     * Splits {@code amount} between the rule's teams, in share order. EVEN gives the rounding
     * remainder to the first team; FIXED_PERCENT and PROPORTIONAL_DIRECT give it to the largest
     * share. PROPORTIONAL_DIRECT weighs by {@code directByTeam} (missing or negative = 0) and falls
     * back to EVEN when every weight is zero.
     */
    public Map<String, BigDecimal> split(BigDecimal amount, CostSplitRule rule, Map<String, BigDecimal> directByTeam) {
        List<String> teams = rule.getShares().stream().map(CostSplitShare::getTeamId).toList();
        if (teams.isEmpty()) {
            throw new IllegalArgumentException("Split rule " + rule.getRuleId() + " has no teams");
        }
        BigDecimal total = (amount == null ? BigDecimal.ZERO : amount).setScale(2, RoundingMode.HALF_UP);
        return switch (rule.getMethod()) {
            case EVEN -> even(total, teams);
            case FIXED_PERCENT -> weighted(total, teams, rule.getShares().stream()
                    .map(s -> s.getPercent() == null ? BigDecimal.ZERO : s.getPercent()).toList());
            case PROPORTIONAL_DIRECT -> {
                Map<String, BigDecimal> direct = directByTeam == null ? Map.of() : directByTeam;
                List<BigDecimal> weights = teams.stream()
                        .map(t -> direct.getOrDefault(t, BigDecimal.ZERO))
                        .map(w -> w == null || w.signum() < 0 ? BigDecimal.ZERO : w)
                        .toList();
                yield weights.stream().allMatch(w -> w.signum() == 0) ? even(total, teams) : weighted(total, teams, weights);
            }
        };
    }

    private static Map<String, BigDecimal> even(BigDecimal total, List<String> teams) {
        BigDecimal each = total.divide(BigDecimal.valueOf(teams.size()), 2, RoundingMode.HALF_UP);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        teams.forEach(t -> result.put(t, each));
        BigDecimal remainder = total.subtract(each.multiply(BigDecimal.valueOf(teams.size())));
        result.merge(teams.get(0), remainder, BigDecimal::add);
        return result;
    }

    private static Map<String, BigDecimal> weighted(BigDecimal total, List<String> teams, List<BigDecimal> weights) {
        BigDecimal weightSum = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        BigDecimal allocated = BigDecimal.ZERO;
        int largest = 0;
        for (int i = 0; i < teams.size(); i++) {
            BigDecimal part = total.multiply(weights.get(i)).divide(weightSum, 2, RoundingMode.HALF_UP);
            result.put(teams.get(i), part);
            allocated = allocated.add(part);
            if (weights.get(i).compareTo(weights.get(largest)) > 0) {
                largest = i;
            }
        }
        result.merge(teams.get(largest), total.subtract(allocated), BigDecimal::add);
        return result;
    }

    // ============= Rules =============

    /** Every rule, for chargeback. */
    @Transactional(readOnly = true)
    public List<CostSplitRule> rules() {
        return ruleRepository.findAllWithShares();
    }

    @Transactional(readOnly = true)
    public List<CostSplitRuleDTO> list() {
        List<CostSplitRule> rules = ruleRepository.findAllWithShares();
        Map<String, String> teamNames = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getTeamId, Team::getDisplayName));
        return rules.stream()
                .map(r -> toDto(r, teamNames))
                .sorted(Comparator.comparing(d -> d.environmentName() == null ? "" : d.environmentName().toLowerCase(Locale.ROOT)))
                .toList();
    }

    @Transactional(readOnly = true)
    public CostSplitRuleDTO get(String ruleId) {
        return toDto(rule(ruleId), teamNames());
    }

    @Transactional
    public CostSplitRuleDTO create(String actorId, CostSplitRuleDTO.Request request) {
        if (request == null || request.environmentId() == null || request.environmentId().isBlank()) {
            throw new ValidationException("Choose the shared environment");
        }
        Environment environment = environmentRepository.findById(request.environmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Environment", request.environmentId()));
        if (ruleRepository.findByEnvironmentId(environment.getEnvironmentId()).isPresent()) {
            throw new ConflictException("Environment " + name(environment) + " already has a split rule");
        }
        CostSplitRule rule = new CostSplitRule();
        rule.setRuleId(UUID.randomUUID().toString());
        rule.setEnvironmentId(environment.getEnvironmentId());
        rule.setCreatedBy(actorId);
        apply(rule, request);
        rule = ruleRepository.save(rule);
        auditService.logAction(actorId, AuditAction.COST_SPLIT_RULE_CREATED, "environment", environment.getEnvironmentId(),
                name(environment), describe(rule));
        return toDto(rule, teamNames());
    }

    @Transactional
    public CostSplitRuleDTO update(String ruleId, String actorId, CostSplitRuleDTO.Request request) {
        CostSplitRule rule = rule(ruleId);
        if (request == null) {
            throw new ValidationException("The rule is required");
        }
        if (request.environmentId() != null && !request.environmentId().isBlank()
                && !request.environmentId().equals(rule.getEnvironmentId())) {
            throw new ValidationException("A rule's environment cannot change; delete it and create a new one");
        }
        String before = describe(rule);
        // Clear first so reordered teams do not collide on the (rule, team) key.
        rule.getShares().clear();
        ruleRepository.saveAndFlush(rule);
        apply(rule, request);
        rule = ruleRepository.save(rule);
        Environment environment = environmentRepository.findById(rule.getEnvironmentId()).orElse(null);
        auditService.logAction(actorId, AuditAction.COST_SPLIT_RULE_UPDATED, "environment", rule.getEnvironmentId(),
                name(environment), before + " -> " + describe(rule));
        return toDto(rule, teamNames());
    }

    @Transactional
    public void delete(String ruleId, String actorId) {
        CostSplitRule rule = rule(ruleId);
        Environment environment = environmentRepository.findById(rule.getEnvironmentId()).orElse(null);
        String description = describe(rule);
        ruleRepository.delete(rule);
        auditService.logAction(actorId, AuditAction.COST_SPLIT_RULE_DELETED, "environment", rule.getEnvironmentId(),
                name(environment), description);
    }

    /** Validates the request and writes method, start month and shares onto the rule. */
    private void apply(CostSplitRule rule, CostSplitRuleDTO.Request request) {
        CostSplitRule.Method method = method(request.method());
        List<CostSplitRuleDTO.ShareRequest> shares = request.shares() == null ? List.of() : request.shares();
        if (shares.isEmpty()) {
            throw new ValidationException("Add at least one team");
        }
        Set<String> seen = new HashSet<>();
        List<CostSplitShare> result = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (CostSplitRuleDTO.ShareRequest share : shares) {
            if (share == null || share.teamId() == null || share.teamId().isBlank()) {
                throw new ValidationException("Each share needs a team");
            }
            if (!seen.add(share.teamId())) {
                throw new ValidationException("A team is listed twice");
            }
            Team team = teamRepository.findById(share.teamId())
                    .orElseThrow(() -> new ResourceNotFoundException("Team", share.teamId()));
            if (!Boolean.TRUE.equals(team.getIsActive())) {
                throw new ValidationException("Team " + team.getDisplayName() + " is inactive");
            }
            BigDecimal percent = null;
            if (method == CostSplitRule.Method.FIXED_PERCENT) {
                if (share.percent() == null || share.percent().signum() <= 0
                        || share.percent().stripTrailingZeros().scale() > 2) {
                    throw new ValidationException("Each percentage must be above 0 with at most two decimals");
                }
                percent = share.percent().setScale(2, RoundingMode.UNNECESSARY);
                sum = sum.add(percent);
            }
            result.add(new CostSplitShare(team.getTeamId(), percent));
        }
        if (method == CostSplitRule.Method.FIXED_PERCENT && sum.compareTo(HUNDRED) != 0) {
            throw new ValidationException("Percentages must add up to 100 (they add up to " + sum.toPlainString() + ")");
        }
        rule.setMethod(method);
        rule.setEffectiveFrom((request.effectiveFrom() != null ? request.effectiveFrom() : LocalDate.now()).withDayOfMonth(1));
        rule.setShares(result);
    }

    private static CostSplitRule.Method method(String value) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("Choose a split method");
        }
        try {
            return CostSplitRule.Method.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown split method: " + value);
        }
    }

    private CostSplitRule rule(String ruleId) {
        return ruleRepository.findById(ruleId).orElseThrow(() -> new ResourceNotFoundException("CostSplitRule", ruleId));
    }

    private Map<String, String> teamNames() {
        return teamRepository.findAll().stream().collect(Collectors.toMap(Team::getTeamId, Team::getDisplayName));
    }

    private CostSplitRuleDTO toDto(CostSplitRule rule, Map<String, String> teamNames) {
        Environment environment = environmentRepository.findById(rule.getEnvironmentId()).orElse(null);
        List<CostSplitRuleDTO.Share> shares = rule.getShares().stream()
                .map(s -> new CostSplitRuleDTO.Share(s.getTeamId(), teamNames.get(s.getTeamId()), s.getPercent()))
                .toList();
        return new CostSplitRuleDTO(rule.getRuleId(), rule.getEnvironmentId(), name(environment), rule.getMethod().name(),
                rule.getEffectiveFrom(), shares, rule.getCreatedBy(), rule.getCreatedAt(), rule.getUpdatedAt());
    }

    private static String name(Environment environment) {
        if (environment == null) {
            return null;
        }
        return environment.getDisplayName() != null ? environment.getDisplayName() : environment.getName();
    }

    /** "FIXED_PERCENT: team-a 60.00%, team-b 40.00% from 2026-09-01". */
    private String describe(CostSplitRule rule) {
        Map<String, String> names = teamNames();
        Function<CostSplitShare, String> share = s -> names.getOrDefault(s.getTeamId(), s.getTeamId())
                + (s.getPercent() != null ? " " + s.getPercent().toPlainString() + "%" : "");
        return rule.getMethod() + ": " + rule.getShares().stream().map(share).collect(Collectors.joining(", "))
                + " from " + rule.getEffectiveFrom();
    }
}
