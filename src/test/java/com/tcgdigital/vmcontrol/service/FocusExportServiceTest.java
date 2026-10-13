package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** The FOCUS 1.2 export (E35-T03): header, row mapping, totals, CSV escaping and the month checks. */
@ExtendWith(MockitoExtension.class)
class FocusExportServiceTest {

    @Mock private VmRepository vmRepository;
    @Mock private VmInventorySnapshotRepository inventoryRepository;
    @Mock private CostDailySnapshotRepository snapshotRepository;
    @Mock private AwsAccountRepository accountRepository;
    @Mock private CostDataProvider costDataProvider;
    @Mock private CostDayBoundary dayBoundary;

    private FocusExportService service;
    private Environment env;

    @BeforeEach
    void setUp() {
        service = new FocusExportService(vmRepository, inventoryRepository, snapshotRepository, accountRepository,
                costDataProvider, dayBoundary, new TeamResolver(new ObjectMapper()));
        ReflectionTestUtils.setField(service, "tagKeyPrefix", "tcg:");
        lenient().when(dayBoundary.today()).thenReturn(LocalDate.of(2026, 10, 14));
        lenient().when(accountRepository.findFirstByIsBillingAccountTrue()).thenReturn(Optional.empty());
        lenient().when(accountRepository.findByIsDefaultTrue()).thenReturn(Optional.empty());
        env = new Environment();
        env.setEnvironmentId("env-1");
        env.setName("qa");
        env.setMetadata("{\"ownerTeam\":\"payments\"}");
    }

    private Vm vm(int i, String displayName) {
        VmGroup group = new VmGroup();
        group.setGroupId("g-1");
        group.setEnvironment(env);
        Vm vm = new Vm();
        vm.setVmId("vm-" + i);
        vm.setGroup(group);
        vm.setName("web-" + i);
        vm.setDisplayName(displayName);
        vm.setProvider(CloudProvider.AWS);
        vm.setRegion("us-east-1");
        vm.setProviderVmId("i-" + i);
        vm.setAwsAccountRef("default");
        return vm;
    }

    /** A tiny RFC 4180 reader for the checks. */
    private static List<List<String>> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                else if (c == '"') quoted = false;
                else field.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString()); field.setLength(0);
            } else if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                row.add(field.toString()); field.setLength(0); rows.add(row); row = new ArrayList<>(); i++;
            } else {
                field.append(c);
            }
        }
        return rows;
    }

    private String export(YearMonth month, FocusExportService.Basis basis) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.prepare(month, basis).writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theHeaderIsTheFocus12ColumnsInSpecOrderThenTheCustomOnes() {
        assertThat(FocusExportService.FOCUS_COLUMNS).hasSize(57);
        assertThat(FocusExportService.FOCUS_COLUMNS.get(0)).isEqualTo("AvailabilityZone");
        assertThat(FocusExportService.FOCUS_COLUMNS.get(1)).isEqualTo("BilledCost");
        assertThat(FocusExportService.FOCUS_COLUMNS).containsSubsequence("InvoiceId", "InvoiceIssuerName", "ListCost",
                "PricingCurrency", "PricingCurrencyContractedUnitPrice", "PricingQuantity");
        assertThat(FocusExportService.FOCUS_COLUMNS.get(56)).isEqualTo("Tags");
        assertThat(FocusExportService.HEADER.subList(57, 59)).containsExactly("x_CostBasis", "x_PriceApproximate");
    }

    @Test
    void tenVmsInSeptemberGive300RowsSummingToTheEstimates() throws Exception {
        List<Vm> vms = new ArrayList<>();
        for (int i = 0; i < 10; i++) vms.add(vm(i, "Web " + i));
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(vms);
        when(inventoryRepository.findInstanceTypesByVmIds(anyList())).thenReturn(List.of());
        when(costDataProvider.estimateCosts(anyList(), any(Timestamp.class), any(Timestamp.class))).thenAnswer(inv -> {
            Map<String, CostDataProvider.VmCostEstimate> m = new HashMap<>();
            for (Vm v : vms) m.put(v.getVmId(), new CostDataProvider.VmCostEstimate(true, new BigDecimal("0.0416"),
                    new BigDecimal("24.000"), 0, new BigDecimal("1.25")));
            return m;
        });

        List<List<String>> rows = parse(export(YearMonth.of(2026, 9), FocusExportService.Basis.ESTIMATED));

        assertThat(rows.get(0)).isEqualTo(FocusExportService.HEADER);
        assertThat(rows).hasSize(301);
        int billed = FocusExportService.HEADER.indexOf("BilledCost");
        BigDecimal sum = rows.subList(1, rows.size()).stream().map(r -> new BigDecimal(r.get(billed))).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("375.00"); // 300 x 1.25
    }

    @Test
    void oneVmDayIsMappedToFocusColumnsAndACommaNameIsQuoted() throws Exception {
        Vm vm = vm(1, "Web, primary");
        when(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment()).thenReturn(List.of(vm));
        VmInventorySnapshotRepository.InstanceTypeProjection type = new VmInventorySnapshotRepository.InstanceTypeProjection() {
            public String getVmId() { return "vm-1"; }
            public String getInstanceType() { return "t3.large"; }
            public Timestamp getInstanceTypeChangedAt() { return null; }
            public Integer getMemoryMib() { return null; }
            public String getArchitecture() { return null; }
        };
        when(inventoryRepository.findInstanceTypesByVmIds(anyList())).thenReturn(List.of(type));
        AwsAccount account = new AwsAccount();
        ReflectionTestUtils.setField(account, "awsAccountRef", "default");
        ReflectionTestUtils.setField(account, "accountNumber", "111122223333");
        ReflectionTestUtils.setField(account, "displayName", "Main");
        when(accountRepository.findAll()).thenReturn(List.of(account));
        when(accountRepository.findFirstByIsBillingAccountTrue()).thenReturn(Optional.of(account));
        when(costDataProvider.estimateCosts(anyList(), any(Timestamp.class), any(Timestamp.class))).thenReturn(
                Map.of("vm-1", new CostDataProvider.VmCostEstimate(true, new BigDecimal("0.0832"), new BigDecimal("10.000"), 0,
                        new BigDecimal("0.83"), true, null)));

        String csv = export(YearMonth.of(2026, 9), FocusExportService.Basis.ESTIMATED);
        assertThat(csv).contains("\"Web, primary\"");
        List<String> row = parse(csv).get(1);
        Map<String, String> r = new HashMap<>();
        for (int i = 0; i < FocusExportService.HEADER.size(); i++) r.put(FocusExportService.HEADER.get(i), row.get(i));

        assertThat(r).containsEntry("BilledCost", "0.83").containsEntry("EffectiveCost", "0.83")
                .containsEntry("ListCost", "0.83").containsEntry("ContractedCost", "0.83")
                .containsEntry("BillingCurrency", "USD")
                .containsEntry("BillingPeriodStart", "2026-09-01T00:00:00Z").containsEntry("BillingPeriodEnd", "2026-10-01T00:00:00Z")
                .containsEntry("ChargePeriodStart", "2026-09-01T00:00:00Z").containsEntry("ChargePeriodEnd", "2026-09-02T00:00:00Z")
                .containsEntry("ChargeCategory", "Usage").containsEntry("ChargeFrequency", "Usage-Based")
                .containsEntry("ServiceName", "Amazon Elastic Compute Cloud").containsEntry("ServiceCategory", "Compute")
                .containsEntry("ProviderName", "AWS").containsEntry("PublisherName", "AWS").containsEntry("InvoiceIssuerName", "AWS")
                .containsEntry("RegionId", "us-east-1").containsEntry("ResourceId", "i-1").containsEntry("ResourceName", "Web, primary")
                .containsEntry("ResourceType", "Instance").containsEntry("SkuId", "t3.large").containsEntry("SkuPriceId", "t3.large")
                .containsEntry("PricingQuantity", "10").containsEntry("ConsumedQuantity", "10")
                .containsEntry("PricingUnit", "Hours").containsEntry("ConsumedUnit", "Hours")
                .containsEntry("BillingAccountId", "111122223333").containsEntry("SubAccountId", "111122223333")
                .containsEntry("x_CostBasis", "Estimated").containsEntry("x_PriceApproximate", "true")
                .containsEntry("ChargeClass", "");
        assertThat(new ObjectMapper().readTree(r.get("Tags")).path("tcg:environment").asText()).isEqualTo("qa");
        assertThat(new ObjectMapper().readTree(r.get("Tags")).path("tcg:team").asText()).isEqualTo("payments");
    }

    @Test
    void theActualBasisIsOneRowPerEnvironmentDay() throws Exception {
        CostDailySnapshot s = new CostDailySnapshot();
        ReflectionTestUtils.setField(s, "environment", env);
        ReflectionTestUtils.setField(s, "snapshotDate", java.sql.Date.valueOf("2026-09-03"));
        ReflectionTestUtils.setField(s, "actualCost", new BigDecimal("42.17"));
        when(snapshotRepository.findWithActualBetween(any(), any())).thenReturn(List.of(s));

        List<List<String>> rows = parse(export(YearMonth.of(2026, 9), FocusExportService.Basis.ACTUAL));

        assertThat(rows).hasSize(2);
        List<String> row = rows.get(1);
        assertThat(row.get(FocusExportService.HEADER.indexOf("BilledCost"))).isEqualTo("42.17");
        assertThat(row.get(FocusExportService.HEADER.indexOf("ResourceId"))).isEmpty();
        assertThat(row.get(FocusExportService.HEADER.indexOf("ChargePeriodStart"))).isEqualTo("2026-09-03T00:00:00Z");
        assertThat(row.get(FocusExportService.HEADER.indexOf("x_CostBasis"))).isEqualTo("Actual (Cost Explorer UnblendedCost by tag)");
    }

    @Test
    void csvFieldsAreQuotedPerRfc4180() {
        assertThat(FocusExportService.csv("plain")).isEqualTo("plain");
        assertThat(FocusExportService.csv("a,b")).isEqualTo("\"a,b\"");
        assertThat(FocusExportService.csv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(FocusExportService.csv("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(FocusExportService.csv(null)).isEmpty();
    }

    @Test
    void badOrFutureMonthsAndUnknownBasesAreRefused() {
        assertThatThrownBy(() -> service.parseMonth("2026-13")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.parseMonth("2026-11")).isInstanceOf(ValidationException.class).hasMessageContaining("future");
        assertThat(service.parseMonth("2026-10")).isEqualTo(YearMonth.of(2026, 10));
        assertThatThrownBy(() -> FocusExportService.parseBasis("billed")).isInstanceOf(ValidationException.class);
        assertThat(FocusExportService.parseBasis("Actual")).isEqualTo(FocusExportService.Basis.ACTUAL);
    }
}
