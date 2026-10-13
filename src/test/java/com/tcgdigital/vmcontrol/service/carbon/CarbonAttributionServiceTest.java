package com.tcgdigital.vmcontrol.service.carbon;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.CarbonMonthly;
import com.tcgdigital.vmcontrol.model.EnvironmentCarbonMonthly;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import com.tcgdigital.vmcontrol.repository.CarbonMonthlyRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentCarbonMonthlyRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Attributing carbon to environments by cost share (E37-T02). */
class CarbonAttributionServiceTest extends AbstractIntegrationTest {

    @Autowired private CarbonAttributionService service;
    @Autowired private CarbonMonthlyRepository carbonRepository;
    @Autowired private EnvironmentCarbonMonthlyRepository attributedRepository;
    @Autowired private AwsAccountRepository accountRepository;

    private static CarbonMonthly carbon(String account, String region, String serviceCode, String mbm, String lbm) {
        CarbonMonthly c = new CarbonMonthly();
        c.setCarbonId(UUID.randomUUID().toString());
        c.setMonth(LocalDate.of(2026, 8, 1));
        c.setAccountNumber(account);
        c.setRegion(region);
        c.setServiceCode(serviceCode);
        c.setEmissionsMbmKg(mbm == null ? null : new BigDecimal(mbm));
        c.setEmissionsLbmKg(lbm == null ? null : new BigDecimal(lbm));
        return c;
    }

    private static CarbonAttributionService.EnvCost cost(String env, String account, String amount) {
        return new CarbonAttributionService.EnvCost(env, account, "us-east-1", "AmazonEC2", new BigDecimal(amount));
    }

    private static CarbonAttributionService.Share share(CarbonAttributionService.Attribution a, String env) {
        return a.shares().stream().filter(s -> java.util.Objects.equals(s.environmentId(), env)).findFirst().orElseThrow();
    }

    @Test
    void sixtyAndTwentyPercentOfTheAccountsCostGetSixtyAndTwentyKilograms() {
        CarbonAttributionService.Attribution a = CarbonAttributionService.attribute(
                List.of(carbon("111111111111", "us-east-1", "AmazonEC2", "100", "50")),
                List.of(cost("A", "111111111111", "600"), cost("B", "111111111111", "200")),
                Map.of(CarbonAttributionService.bucket("111111111111", "us-east-1", "AmazonEC2"), new BigDecimal("1000")),
                Set.of("111111111111"));

        assertThat(share(a, "A").mbmKg()).isEqualByComparingTo("60");
        assertThat(share(a, "A").lbmKg()).isEqualByComparingTo("30");
        assertThat(share(a, "B").mbmKg()).isEqualByComparingTo("20");
        assertThat(share(a, null).mbmKg()).isEqualByComparingTo("20");
        assertThat(share(a, null).lbmKg()).isEqualByComparingTo("10");
        assertThat(share(a, "A").costBasis()).isEqualByComparingTo("600");
        assertThat(a.coveragePct()).isEqualByComparingTo("80");
    }

    @Test
    void withoutCostExplorerTheTrackedCostIsTheWholeAndOtherServicesAreUnallocated() {
        CarbonAttributionService.Attribution a = CarbonAttributionService.attribute(
                List.of(carbon("111111111111", "us-east-1", "AmazonEC2", "40", null),
                        carbon("111111111111", "us-east-1", "AmazonS3", "10", null),
                        carbon("999999999999", "us-east-1", "AmazonEC2", "500", null)), // an account the platform does not know
                List.of(cost("A", "111111111111", "75"), cost("B", "111111111111", "25")),
                Map.of(), Set.of("111111111111"));

        assertThat(share(a, "A").mbmKg()).isEqualByComparingTo("30");
        assertThat(share(a, "B").mbmKg()).isEqualByComparingTo("10");
        assertThat(share(a, null).mbmKg()).isEqualByComparingTo("10");
        assertThat(share(a, null).lbmKg()).isNull();
        assertThat(a.coveragePct()).isEqualByComparingTo("80");
    }

    @Test
    void zeroCostDividesByNothingAndAnUnnumberedAccountIsTheOneUnknownAccount() {
        CarbonAttributionService.Attribution zero = CarbonAttributionService.attribute(
                List.of(carbon("111111111111", "us-east-1", "AmazonEC2", "5", null)),
                List.of(cost("A", "111111111111", "0")), Map.of(), Set.of("111111111111"));
        assertThat(share(zero, null).mbmKg()).isEqualByComparingTo("5");
        assertThat(zero.coveragePct()).isEqualByComparingTo("0");

        CarbonAttributionService.Attribution single = CarbonAttributionService.attribute(
                List.of(carbon("222222222222", "us-east-1", "AmazonEC2", "8", null)),
                List.of(cost("A", null, "10")), Map.of(), Set.of());
        assertThat(share(single, "A").mbmKg()).isEqualByComparingTo("8");

        CarbonAttributionService.Attribution ambiguous = CarbonAttributionService.attribute(
                List.of(carbon("222222222222", "us-east-1", "AmazonEC2", "8", null),
                        carbon("333333333333", "us-east-1", "AmazonEC2", "2", null)),
                List.of(cost("A", null, "10")), Map.of(), Set.of());
        assertThat(ambiguous.shares()).extracting(CarbonAttributionService.Share::environmentId).containsExactly((String) null);
        assertThat(share(ambiguous, null).mbmKg()).isEqualByComparingTo("10");
    }

    @Test
    void reRunningAMonthReplacesItsRows() {
        // Only the accounts the platform knows count; AWS accounts are not reset between tests.
        String account = accountRepository.findAll().stream().map(AwsAccount::getAccountNumber)
                .filter(n -> n != null && !n.isBlank()).findFirst().orElse("111111111111");
        carbonRepository.save(carbon(account, "me-central-1", "AmazonEC2", "12", "6"));

        service.attributeMonth(YearMonth.of(2026, 8));
        service.attributeMonth(YearMonth.of(2026, 8));

        List<EnvironmentCarbonMonthly> rows = attributedRepository.findByMonthBetween(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getEnvironment()).isNull();
        assertThat(rows.get(0).getEmissionsMbmKg()).isEqualByComparingTo("12");

        carbonRepository.deleteAll();
        service.attributeMonth(YearMonth.of(2026, 8));
        assertThat(attributedRepository.findByMonthBetween(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1))).isEmpty();
    }
}
