package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.CostDailySnapshot;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cost data as a FOCUS 1.2 CSV (E35-T03). The 57 FOCUS 1.2 columns come first, in specification
 * order, then the custom columns x_CostBasis and x_PriceApproximate. Two bases:
 * <ul>
 *   <li>estimated: one row per VM per day, from the platform's estimate (static pricing
 *       reference, runtime from state history), for the platform's AWS resources;</li>
 *   <li>actual: one row per environment per day, from Cost Explorer's UnblendedCost by the
 *       environment tag (not per resource).</li>
 * </ul>
 * Everything the export needs is read first ({@link #prepare}); {@link Plan#writeTo} then streams
 * the rows, estimating one day at a time so memory stays bounded. CSV follows RFC 4180.
 */
@Service
public class FocusExportService {

    /** The FOCUS 1.2 columns, in specification order (focus.finops.org, v1.2 sections 3.1-3.57). */
    public static final List<String> FOCUS_COLUMNS = List.of(
            "AvailabilityZone", "BilledCost", "BillingAccountId", "BillingAccountName", "BillingAccountType",
            "BillingCurrency", "BillingPeriodEnd", "BillingPeriodStart", "CapacityReservationId",
            "CapacityReservationStatus", "ChargeCategory", "ChargeClass", "ChargeDescription", "ChargeFrequency",
            "ChargePeriodEnd", "ChargePeriodStart", "CommitmentDiscountCategory", "CommitmentDiscountId",
            "CommitmentDiscountName", "CommitmentDiscountQuantity", "CommitmentDiscountStatus", "CommitmentDiscountType",
            "CommitmentDiscountUnit", "ConsumedQuantity", "ConsumedUnit", "ContractedCost", "ContractedUnitPrice",
            "EffectiveCost", "InvoiceId", "InvoiceIssuerName", "ListCost", "ListUnitPrice", "PricingCategory",
            "PricingCurrency", "PricingCurrencyContractedUnitPrice", "PricingCurrencyEffectiveCost",
            "PricingCurrencyListUnitPrice", "PricingQuantity", "PricingUnit", "ProviderName", "PublisherName",
            "RegionId", "RegionName", "ResourceId", "ResourceName", "ResourceType", "ServiceCategory", "ServiceName",
            "ServiceSubcategory", "SkuId", "SkuMeter", "SkuPriceDetails", "SkuPriceId", "SubAccountId",
            "SubAccountName", "SubAccountType", "Tags");
    public static final List<String> CUSTOM_COLUMNS = List.of("x_CostBasis", "x_PriceApproximate");
    public static final List<String> HEADER;

    static {
        List<String> all = new ArrayList<>(FOCUS_COLUMNS);
        all.addAll(CUSTOM_COLUMNS);
        HEADER = List.copyOf(all);
    }

    public enum Basis { ESTIMATED, ACTUAL }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${cost.tagging.key-prefix:tcg:}")
    private String tagKeyPrefix;

    private final VmRepository vmRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final CostDailySnapshotRepository snapshotRepository;
    private final AwsAccountRepository accountRepository;
    private final CostDataProvider costDataProvider;
    private final CostDayBoundary dayBoundary;
    private final TeamResolver teamResolver;

    public FocusExportService(VmRepository vmRepository, VmInventorySnapshotRepository inventoryRepository,
                              CostDailySnapshotRepository snapshotRepository, AwsAccountRepository accountRepository,
                              CostDataProvider costDataProvider, CostDayBoundary dayBoundary, TeamResolver teamResolver) {
        this.vmRepository = vmRepository;
        this.inventoryRepository = inventoryRepository;
        this.snapshotRepository = snapshotRepository;
        this.accountRepository = accountRepository;
        this.costDataProvider = costDataProvider;
        this.dayBoundary = dayBoundary;
        this.teamResolver = teamResolver;
    }

    /** yyyy-MM, not in the future; 400 otherwise. */
    public YearMonth parseMonth(String month) {
        YearMonth ym;
        try {
            ym = YearMonth.parse(month == null ? "" : month.trim());
        } catch (Exception e) {
            throw new ValidationException("month must be yyyy-MM");
        }
        if (ym.isAfter(YearMonth.from(dayBoundary.today()))) {
            throw new ValidationException("month cannot be in the future");
        }
        return ym;
    }

    public static Basis parseBasis(String basis) {
        try {
            return Basis.valueOf((basis == null ? "estimated" : basis.trim()).toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            throw new ValidationException("basis must be estimated or actual");
        }
    }

    /** One VM's fixed columns, read before streaming. */
    record VmInfo(Vm vm, String resourceType, String serviceName, String serviceCategory, String serviceSubcategory,
                  String sku, String subAccountId, String subAccountName, String tags) {
    }

    /** One environment-day of actual cost. */
    record ActualRow(LocalDate day, String environmentName, String tags, BigDecimal cost) {
    }

    /** Everything an export writes, read in the request's thread. */
    public final class Plan {
        private final YearMonth month;
        private final Basis basis;
        private final List<LocalDate> days;
        private final List<VmInfo> vms;
        private final List<ActualRow> actuals;
        private final String billingAccountId;
        private final String billingAccountName;

        Plan(YearMonth month, Basis basis, List<LocalDate> days, List<VmInfo> vms, List<ActualRow> actuals,
             String billingAccountId, String billingAccountName) {
            this.month = month;
            this.basis = basis;
            this.days = days;
            this.vms = vms;
            this.actuals = actuals;
            this.billingAccountId = billingAccountId;
            this.billingAccountName = billingAccountName;
        }

        public String filename() {
            return "focus-" + basis.name().toLowerCase(Locale.ROOT) + "-" + month + ".csv";
        }

        /** Streams the header and every row. */
        public void writeTo(OutputStream out) throws IOException {
            Writer w = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            writeRow(w, HEADER);
            if (basis == Basis.ESTIMATED) {
                List<Vm> vmList = vms.stream().map(VmInfo::vm).toList();
                for (LocalDate day : days) {
                    Timestamp start = Timestamp.from(day.atStartOfDay(ZoneOffset.UTC).toInstant());
                    Timestamp end = Timestamp.from(day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());
                    Map<String, CostDataProvider.VmCostEstimate> estimates = vmList.isEmpty() ? Map.of()
                            : costDataProvider.estimateCosts(vmList, start, end);
                    for (VmInfo info : vms) {
                        CostDataProvider.VmCostEstimate e = estimates.get(info.vm().getVmId());
                        if (e == null) continue;
                        writeRow(w, estimatedRow(this, info, day, e));
                    }
                    w.flush();
                }
            } else {
                for (ActualRow a : actuals) {
                    writeRow(w, actualRow(this, a));
                }
            }
            w.flush();
        }
    }

    /** Reads what an export of {@code month} needs: the VMs and their fixed columns, or the actual-cost rows. */
    @Transactional(readOnly = true)
    public Plan prepare(YearMonth month, Basis basis) {
        LocalDate first = month.atDay(1);
        LocalDate today = dayBoundary.today();
        // Complete days only: the current month stops at yesterday.
        LocalDate endExclusive = month.equals(YearMonth.from(today)) ? today : month.plusMonths(1).atDay(1);
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = first; d.isBefore(endExclusive); d = d.plusDays(1)) days.add(d);

        AwsAccount billing = accountRepository.findFirstByIsBillingAccountTrue()
                .or(accountRepository::findByIsDefaultTrue).orElse(null);
        String billingId = billing == null ? null : billing.getAccountNumber();
        String billingName = billing == null ? null : billing.getDisplayName();

        if (basis == Basis.ACTUAL) {
            List<ActualRow> rows = new ArrayList<>();
            if (!days.isEmpty()) {
                for (CostDailySnapshot s : snapshotRepository.findWithActualBetween(Date.valueOf(first),
                        Date.valueOf(days.get(days.size() - 1)))) {
                    Environment env = s.getEnvironment();
                    rows.add(new ActualRow(s.getSnapshotDate().toLocalDate(), env.getName(), tags(env), s.getActualCost()));
                }
            }
            rows.sort((a, b) -> a.day().equals(b.day()) ? a.environmentName().compareTo(b.environmentName()) : a.day().compareTo(b.day()));
            return new Plan(month, basis, days, List.of(), rows, billingId, billingName);
        }

        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment().stream()
                .filter(v -> v.getProvider() != null && v.getProvider().isAws()).toList();
        Map<String, String> types = new HashMap<>();
        if (!vms.isEmpty()) {
            inventoryRepository.findInstanceTypesByVmIds(vms.stream().map(Vm::getVmId).toList())
                    .forEach(p -> types.put(p.getVmId(), p.getInstanceType()));
        }
        Map<String, AwsAccount> accounts = new HashMap<>();
        accountRepository.findAll().forEach(a -> accounts.put(a.getAwsAccountRef(), a));
        List<VmInfo> infos = new ArrayList<>();
        for (Vm vm : vms) {
            Environment env = vm.getGroup() == null ? null : vm.getGroup().getEnvironment();
            AwsAccount account = vm.getAwsAccountRef() == null ? null : accounts.get(vm.getAwsAccountRef());
            boolean rds = vm.getProvider() == CloudProvider.AWS_RDS;
            boolean eks = vm.getProvider() == CloudProvider.AWS_EKS;
            infos.add(new VmInfo(vm,
                    rds ? (vm.getProviderVmId() != null && vm.getProviderVmId().startsWith("cluster:") ? "DB Cluster" : "DB Instance")
                            : eks ? "Node Group" : "Instance",
                    rds ? "Amazon Relational Database Service" : "Amazon Elastic Compute Cloud",
                    rds ? "Databases" : "Compute",
                    rds ? "Relational Databases" : "Virtual Machines",
                    types.get(vm.getVmId()),
                    account == null ? null : account.getAccountNumber(),
                    account == null ? null : account.getDisplayName(),
                    tags(env)));
        }
        return new Plan(month, basis, days, infos, List.of(), billingId, billingName);
    }

    /** The FOCUS Tags column: the environment and team cost-allocation tags as a JSON object. */
    private String tags(Environment env) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (env != null) {
            tags.put(tagKeyPrefix + "environment", env.getName());
            tags.put(tagKeyPrefix + "team", teamResolver.resolveTeam(env));
            if (env.getTeam() != null && env.getTeam().getCostCenter() != null) {
                tags.put(tagKeyPrefix + "cost-center", env.getTeam().getCostCenter());
            }
        }
        try {
            return JSON.writeValueAsString(tags);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static List<String> estimatedRow(Plan p, VmInfo info, LocalDate day, CostDataProvider.VmCostEstimate e) {
        Map<String, String> r = base(p, day);
        String cost = e.cost() == null ? "0" : e.cost().toPlainString();
        r.put("BilledCost", cost);
        r.put("EffectiveCost", cost);
        r.put("ListCost", cost);
        r.put("ContractedCost", cost);
        r.put("ChargeDescription", info.serviceName() + " " + (info.sku() == null ? "" : info.sku() + " ")
                + "estimated usage of " + info.vm().getName());
        String hours = e.runtimeHours() == null ? "0" : e.runtimeHours().stripTrailingZeros().toPlainString();
        r.put("ConsumedQuantity", hours);
        r.put("ConsumedUnit", "Hours");
        r.put("PricingQuantity", hours);
        r.put("PricingUnit", "Hours");
        if (e.hourlyRate() != null) {
            String rate = e.hourlyRate().toPlainString();
            r.put("ListUnitPrice", rate);
            r.put("ContractedUnitPrice", rate);
        }
        r.put("PricingCategory", "Standard");
        r.put("RegionId", info.vm().getRegion());
        r.put("ResourceId", info.vm().getProviderVmId());
        r.put("ResourceName", info.vm().getDisplayName() != null ? info.vm().getDisplayName() : info.vm().getName());
        r.put("ResourceType", info.resourceType());
        r.put("ServiceCategory", info.serviceCategory());
        r.put("ServiceName", info.serviceName());
        r.put("ServiceSubcategory", info.serviceSubcategory());
        r.put("SkuId", info.sku());
        r.put("SkuPriceId", info.sku());
        r.put("SubAccountId", info.subAccountId());
        r.put("SubAccountName", info.subAccountName());
        r.put("Tags", info.tags());
        r.put("x_CostBasis", "Estimated");
        r.put("x_PriceApproximate", e.costKnown() ? String.valueOf(e.priceApproximate()) : "unknown");
        return values(r);
    }

    private static List<String> actualRow(Plan p, ActualRow a) {
        Map<String, String> r = base(p, a.day());
        String cost = a.cost() == null ? "0" : a.cost().toPlainString();
        r.put("BilledCost", cost);
        r.put("EffectiveCost", cost);
        r.put("ListCost", cost);
        r.put("ContractedCost", cost);
        r.put("ChargeDescription", "Unblended cost of resources tagged with environment " + a.environmentName());
        r.put("ServiceCategory", "Other");
        r.put("ServiceName", "AWS");
        r.put("ServiceSubcategory", "Other (Other)");
        r.put("Tags", a.tags());
        r.put("x_CostBasis", "Actual (Cost Explorer UnblendedCost by tag)");
        r.put("x_PriceApproximate", "false");
        return values(r);
    }

    /** The columns every row shares. */
    private static Map<String, String> base(Plan p, LocalDate day) {
        Map<String, String> r = new HashMap<>();
        r.put("BillingAccountId", p.billingAccountId);
        r.put("BillingAccountName", p.billingAccountName);
        r.put("BillingCurrency", "USD");
        r.put("PricingCurrency", "USD");
        r.put("BillingPeriodStart", iso(p.month.atDay(1)));
        r.put("BillingPeriodEnd", iso(p.month.plusMonths(1).atDay(1)));
        r.put("ChargeCategory", "Usage");
        r.put("ChargeFrequency", "Usage-Based");
        r.put("ChargePeriodStart", iso(day));
        r.put("ChargePeriodEnd", iso(day.plusDays(1)));
        r.put("InvoiceIssuerName", "AWS");
        r.put("ProviderName", "AWS");
        r.put("PublisherName", "AWS");
        return r;
    }

    private static List<String> values(Map<String, String> row) {
        List<String> out = new ArrayList<>(HEADER.size());
        for (String column : HEADER) out.add(row.get(column));
        return out;
    }

    private static String iso(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
    }

    /** One RFC 4180 line: fields with a comma, quote or line break are quoted, inner quotes doubled; CRLF ends it. */
    static void writeRow(Writer w, List<String> fields) throws IOException {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) w.write(',');
            w.write(csv(fields.get(i)));
        }
        w.write("\r\n");
    }

    static String csv(String value) {
        if (value == null) return "";
        if (value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }
}
