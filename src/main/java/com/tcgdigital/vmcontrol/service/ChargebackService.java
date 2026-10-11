package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.ChargebackDTO;
import com.tcgdigital.vmcontrol.dto.ChargebackRowDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.CostSplitRule;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Monthly chargeback per team (E23-T06). Each environment's cost for the month is the sum of its
 * daily snapshots, using actual billing where present and the estimate otherwise. An environment
 * with a split rule in effect is shared between the rule's teams ({@link CostSplitService#split},
 * PROPORTIONAL_DIRECT weighing by this month's direct totals); any other environment is charged to
 * its own team, or to "Unassigned". The grand total always equals the sum of environment costs.
 */
@Service
public class ChargebackService {

    private static final Logger log = LoggerFactory.getLogger(ChargebackService.class);

    static final String UNASSIGNED = "Unassigned";
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final List<String> HEADERS = List.of(
            "Team", "Cost center", "Direct cost", "Shared allocated", "Total", "Actual coverage %");

    private final CostDailySnapshotRepository snapshotRepository;
    private final CostSplitService splitService;
    private final TeamRepository teamRepository;
    private final ExcelExportService excelExportService;

    public ChargebackService(CostDailySnapshotRepository snapshotRepository, CostSplitService splitService,
                             TeamRepository teamRepository, ExcelExportService excelExportService) {
        this.snapshotRepository = snapshotRepository;
        this.splitService = splitService;
        this.teamRepository = teamRepository;
        this.excelExportService = excelExportService;
    }

    /** "2026-09" -> YearMonth; 400 when malformed or in the future. */
    public static YearMonth parseMonth(String value) {
        YearMonth month;
        try {
            month = YearMonth.parse(value == null ? "" : value.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException("month must look like 2026-09");
        }
        if (month.isAfter(YearMonth.now())) {
            throw new ValidationException("Chargeback is only available for past and current months");
        }
        return month;
    }

    /** One environment's month: cost, and how many of its days had actual billing. */
    private static final class EnvironmentMonth {
        final Environment environment;
        BigDecimal cost = ZERO;
        int days;
        int actualDays;

        EnvironmentMonth(Environment environment) {
            this.environment = environment;
        }
    }

    /** A row being built. */
    private static final class Bucket {
        final String teamId;
        final String teamName;
        final String costCenter;
        BigDecimal direct = ZERO;
        BigDecimal shared = ZERO;
        double days;
        double actualDays;
        final List<ChargebackRowDTO.EnvironmentCharge> environments = new ArrayList<>();

        Bucket(String teamId, String teamName, String costCenter) {
            this.teamId = teamId;
            this.teamName = teamName;
            this.costCenter = costCenter;
        }

        BigDecimal total() {
            return direct.add(shared);
        }
    }

    @Transactional(readOnly = true)
    public ChargebackDTO compute(YearMonth month) {
        LocalDate start = month.atDay(1);
        LocalDate end = month.atEndOfMonth();

        Map<String, EnvironmentMonth> byEnvironment = new LinkedHashMap<>();
        for (CostDailySnapshot s : snapshotRepository.findWithEnvironmentAndTeamBetween(Date.valueOf(start), Date.valueOf(end))) {
            EnvironmentMonth em = byEnvironment.computeIfAbsent(s.getEnvironment().getEnvironmentId(),
                    id -> new EnvironmentMonth(s.getEnvironment()));
            BigDecimal value = s.getActualCost() != null ? s.getActualCost() : s.getEstimatedCost();
            em.cost = em.cost.add(value == null ? BigDecimal.ZERO : value);
            em.days++;
            if (s.getActualCost() != null) {
                em.actualDays++;
            }
        }

        Map<String, CostSplitRule> rules = splitService.rules().stream()
                .filter(r -> !r.getEffectiveFrom().isAfter(end) && !r.getShares().isEmpty())
                .collect(Collectors.toMap(CostSplitRule::getEnvironmentId, Function.identity()));

        Map<String, Bucket> buckets = new LinkedHashMap<>();
        Bucket unassigned = new Bucket(null, UNASSIGNED, null);

        // Direct cost first: PROPORTIONAL_DIRECT needs every team's direct total.
        for (EnvironmentMonth em : byEnvironment.values()) {
            if (rules.containsKey(em.environment.getEnvironmentId())) {
                continue;
            }
            Team team = em.environment.getTeam();
            Bucket bucket = team == null ? unassigned
                    : buckets.computeIfAbsent(team.getTeamId(), id -> new Bucket(id, team.getDisplayName(), team.getCostCenter()));
            bucket.direct = bucket.direct.add(em.cost);
            bucket.days += em.days;
            bucket.actualDays += em.actualDays;
            bucket.environments.add(new ChargebackRowDTO.EnvironmentCharge(em.environment.getEnvironmentId(),
                    name(em.environment), em.cost.setScale(2, RoundingMode.HALF_UP), false));
        }

        Map<String, BigDecimal> directByTeam = buckets.values().stream()
                .collect(Collectors.toMap(b -> b.teamId, b -> b.direct));
        Map<String, Team> splitTeams = teamRepository.findAllById(rules.values().stream()
                        .flatMap(r -> r.getShares().stream().map(s -> s.getTeamId())).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Team::getTeamId, Function.identity()));

        for (EnvironmentMonth em : byEnvironment.values()) {
            CostSplitRule rule = rules.get(em.environment.getEnvironmentId());
            if (rule == null) {
                continue;
            }
            Map<String, BigDecimal> parts = splitService.split(em.cost, rule, directByTeam);
            BigDecimal emCost = em.cost.setScale(2, RoundingMode.HALF_UP);
            for (Map.Entry<String, BigDecimal> part : parts.entrySet()) {
                Team team = splitTeams.get(part.getKey());
                Bucket bucket = buckets.computeIfAbsent(part.getKey(), id -> team == null ? new Bucket(id, id, null)
                        : new Bucket(id, team.getDisplayName(), team.getCostCenter()));
                bucket.shared = bucket.shared.add(part.getValue());
                // Weigh the shared environment's coverage by the team's share of it.
                double weight = emCost.signum() == 0 ? 1.0 / parts.size()
                        : part.getValue().doubleValue() / emCost.doubleValue();
                bucket.days += em.days * weight;
                bucket.actualDays += em.actualDays * weight;
                bucket.environments.add(new ChargebackRowDTO.EnvironmentCharge(em.environment.getEnvironmentId(),
                        name(em.environment), part.getValue(), true));
            }
        }

        List<Bucket> ordered = new ArrayList<>(buckets.values());
        if (!unassigned.environments.isEmpty()) {
            ordered.add(unassigned);
        }
        ordered.sort(Comparator.comparing(Bucket::total).reversed().thenComparing(b -> b.teamName, String.CASE_INSENSITIVE_ORDER));

        List<ChargebackRowDTO> rows = ordered.stream().map(ChargebackService::row).toList();
        BigDecimal direct = rows.stream().map(ChargebackRowDTO::directCost).reduce(ZERO, BigDecimal::add);
        BigDecimal shared = rows.stream().map(ChargebackRowDTO::sharedAllocated).reduce(ZERO, BigDecimal::add);
        int days = byEnvironment.values().stream().mapToInt(e -> e.days).sum();
        int actualDays = byEnvironment.values().stream().mapToInt(e -> e.actualDays).sum();
        ChargebackRowDTO totals = new ChargebackRowDTO(null, "Total", null, direct, shared, direct.add(shared),
                pct(actualDays, days), List.of());

        BigDecimal expected = byEnvironment.values().stream().map(e -> e.cost.setScale(2, RoundingMode.HALF_UP))
                .reduce(ZERO, BigDecimal::add);
        if (expected.compareTo(totals.total()) != 0) {
            log.error("Chargeback {} total {} does not match the environment costs {}", month, totals.total(), expected);
        }

        return new ChargebackDTO(month.toString(), rows, totals, dataSource(actualDays, days), Instant.now());
    }

    private static ChargebackRowDTO row(Bucket b) {
        List<ChargebackRowDTO.EnvironmentCharge> environments = b.environments.stream()
                .sorted(Comparator.comparing(ChargebackRowDTO.EnvironmentCharge::amount).reversed())
                .toList();
        return new ChargebackRowDTO(b.teamId, b.teamName, b.costCenter, b.direct.setScale(2, RoundingMode.HALF_UP),
                b.shared.setScale(2, RoundingMode.HALF_UP), b.total().setScale(2, RoundingMode.HALF_UP),
                pct(b.actualDays, b.days), environments);
    }

    private static BigDecimal pct(double part, double whole) {
        if (whole <= 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP);
    }

    private static String dataSource(int actualDays, int days) {
        if (days == 0) {
            return "No cost data for this month";
        }
        if (actualDays == days) {
            return "AWS billing for every environment-day";
        }
        if (actualDays == 0) {
            return "Estimates only (no AWS billing data for this month)";
        }
        return "AWS billing for " + pct(actualDays, days) + "% of environment-days; estimates for the rest";
    }

    private static String name(Environment environment) {
        return environment.getDisplayName() != null ? environment.getDisplayName() : environment.getName();
    }

    // ============= Export =============

    private static List<Object[]> exportRows(ChargebackDTO chargeback) {
        List<Object[]> rows = new ArrayList<>();
        for (ChargebackRowDTO r : chargeback.rows()) {
            rows.add(new Object[]{r.teamName(), r.costCenter(), r.directCost(), r.sharedAllocated(), r.total(), r.actualCoveragePct()});
        }
        ChargebackRowDTO t = chargeback.totals();
        rows.add(new Object[]{t.teamName(), null, t.directCost(), t.sharedAllocated(), t.total(), t.actualCoveragePct()});
        return rows;
    }

    public byte[] toXlsx(ChargebackDTO chargeback) {
        return excelExportService.toWorkbook("Chargeback " + chargeback.month(), HEADERS, exportRows(chargeback));
    }

    /** CSV with text cells neutralised against formula injection (the same rule as Utils.csvCell). */
    public byte[] toCsv(ChargebackDTO chargeback) {
        StringBuilder out = new StringBuilder();
        out.append(HEADERS.stream().map(ChargebackService::csvCell).collect(Collectors.joining(","))).append("\r\n");
        for (Object[] row : exportRows(chargeback)) {
            out.append(Arrays.stream(row).map(ChargebackService::csvCell).collect(Collectors.joining(","))).append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Numbers as plain text; text quoted, and prefixed with ' when it starts like a formula. */
    static String csvCell(Object value) {
        if (value instanceof BigDecimal number) {
            return number.toPlainString();
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        String s = value == null ? "" : value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
