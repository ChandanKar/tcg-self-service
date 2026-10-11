package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.TeamQuota;
import com.tcgdigital.vmcontrol.repository.TeamMemberRepository;
import com.tcgdigital.vmcontrol.repository.TeamQuotaRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Quota projection and enforcement (E25-T07). */
class QuotaServiceTest {

    private TeamQuotaRepository quotas;
    private QuotaUsageService usage;
    private QuotaService service;

    @BeforeEach
    void setUp() {
        quotas = mock(TeamQuotaRepository.class);
        usage = mock(QuotaUsageService.class);
        service = new QuotaService(quotas, mock(TeamRepository.class), mock(TeamMemberRepository.class), usage,
                mock(NotificationService.class), mock(AuditService.class));
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    private static TeamQuota quota(TeamQuota.Metric metric, String limit, TeamQuota.Enforcement enforcement) {
        TeamQuota q = new TeamQuota();
        q.setQuotaId("q-" + metric);
        q.setTeamId("t");
        q.setMetric(metric);
        q.setLimitValue(new BigDecimal(limit));
        q.setEnforcement(enforcement);
        q.setWarnPercent(80);
        return q;
    }

    private void used(String hours, String cost, TeamQuota... list) {
        when(usage.monthToDate("t")).thenReturn(new QuotaUsageService.Usage(new BigDecimal(hours), new BigDecimal(cost)));
        when(quotas.findByTeamIdAndIsActiveTrue("t")).thenReturn(List.of(list));
    }

    @Test
    void blockWhenTheProjectionPassesTheLimit() {
        used("990", "0", quota(TeamQuota.Metric.VM_HOURS, "1000", TeamQuota.Enforcement.BLOCK));
        QuotaService.QuotaCheck check = service.check("t", 4, 8, null);
        assertThat(check.outcome()).isEqualTo(QuotaService.Outcome.BLOCK);
        assertThat(check.messages()).containsExactly("Team quota exceeded: 990 of 1000 VM-hours used; this start adds 32 VM-hours");

        assertThat(service.check("t", 1, 8, null).outcome()).isEqualTo(QuotaService.Outcome.WARN); // 998 of 1000: near the limit
    }

    @Test
    void eachEnforcementModeAndTheMostSevereWins() {
        used("10", "95", quota(TeamQuota.Metric.VM_HOURS, "100", TeamQuota.Enforcement.WARN),
                quota(TeamQuota.Metric.COST, "100", TeamQuota.Enforcement.REQUIRE_APPROVAL));
        QuotaService.QuotaCheck check = service.check("t", 1, 2, new BigDecimal("12.50"));
        assertThat(check.outcome()).isEqualTo(QuotaService.Outcome.REQUIRE_APPROVAL);
        assertThat(check.messages()).containsExactly("Team quota exceeded: $95 of $100 used; this start adds $12.50");

        // 100 VM-hours more passes the WARN quota; the cost (96%) is only near its limit.
        assertThat(service.check("t", 100, 1, BigDecimal.ONE).outcome()).isEqualTo(QuotaService.Outcome.WARN);
        assertThat(service.check("t", 1, 1, BigDecimal.ZERO).outcome()).isEqualTo(QuotaService.Outcome.WARN); // 95% of the cost quota
    }

    @Test
    void underTheWarningLevelOrOffNothingHappens() {
        used("10", "10", quota(TeamQuota.Metric.VM_HOURS, "100", TeamQuota.Enforcement.BLOCK));
        assertThat(service.check("t", 2, 8, null).outcome()).isEqualTo(QuotaService.Outcome.OK);
        assertThat(service.check(null, 2, 8, null).outcome()).isEqualTo(QuotaService.Outcome.OK);

        ReflectionTestUtils.setField(service, "enabled", false);
        assertThat(service.check("t", 1000, 8, null).outcome()).isEqualTo(QuotaService.Outcome.OK);
    }
}
