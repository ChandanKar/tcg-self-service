package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A team's usage this month (E25-T07), over its environments: running VM-hours from the state
 * history (the runtime the cost figures use) and dollars from the daily cost snapshots (actual
 * billing where present, else the estimate). Months are UTC.
 */
@Service
public class QuotaUsageService {

    private final EnvironmentRepository environmentRepository;
    private final CostDailySnapshotRepository snapshotRepository;
    private final CostEstimationService costEstimationService;

    public QuotaUsageService(EnvironmentRepository environmentRepository, CostDailySnapshotRepository snapshotRepository,
                             CostEstimationService costEstimationService) {
        this.environmentRepository = environmentRepository;
        this.snapshotRepository = snapshotRepository;
        this.costEstimationService = costEstimationService;
    }

    public record Usage(BigDecimal vmHours, BigDecimal cost) {}

    @Transactional(readOnly = true)
    public Usage monthToDate(String teamId) {
        return monthToDate(teamId, Instant.now());
    }

    @Transactional(readOnly = true)
    public Usage monthToDate(String teamId, Instant now) {
        Set<String> envIds = environmentRepository.findAll().stream()
                .filter(e -> teamId.equals(e.getTeamId()))
                .map(Environment::getEnvironmentId).collect(Collectors.toSet());
        if (envIds.isEmpty()) {
            return new Usage(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        }
        LocalDate first = now.atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        Timestamp start = Timestamp.from(first.atStartOfDay(ZoneOffset.UTC).toInstant());

        Map<String, BigDecimal> hours = costEstimationService.getUptimeHoursByEnvironment(start, Timestamp.from(now));
        BigDecimal vmHours = envIds.stream().map(id -> hours.getOrDefault(id, BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

        BigDecimal cost = BigDecimal.ZERO;
        for (CostDailySnapshotRepository.EnvironmentCostTotal t : snapshotRepository.sumByEnvironmentBetween(
                Date.valueOf(first), Date.valueOf(first.plusMonths(1)))) {
            if (!envIds.contains(t.getEnvironmentId())) {
                continue;
            }
            BigDecimal estimated = nz(t.getTotalEstimatedCost());
            BigDecimal actual = nz(t.getTotalActualCost());
            // Actual billing where a day has it, the estimate for the other days.
            cost = cost.add(actual).add(estimated.subtract(nz(t.getEstimatedOnActualDays())));
        }
        return new Usage(vmHours, cost.setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
