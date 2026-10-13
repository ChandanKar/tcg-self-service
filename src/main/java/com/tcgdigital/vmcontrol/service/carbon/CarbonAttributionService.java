package com.tcgdigital.vmcontrol.service.carbon;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.CarbonMonthly;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentCarbonMonthly;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import com.tcgdigital.vmcontrol.repository.CarbonMonthlyRepository;
import com.tcgdigital.vmcontrol.repository.CostDailySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentCarbonMonthlyRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.CostDataProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Attributes a month's carbon emissions to environments by cost share (E37-T02). AWS reports carbon
 * per account, region and service, not per resource, so each environment gets the part of a bucket's
 * emissions that its cost is of that bucket's cost: the platform's estimate for its VMs that month
 * (EC2 instances and EKS node groups as AmazonEC2, the EKS control-plane fee as AmazonEKS, RDS as
 * AmazonRDS), scaled by the environment's actual/estimated ratio where Cost Explorer actuals exist.
 * The bucket's cost is the account's Cost Explorer cost for that service and region when available,
 * else what the platform tracks. What no environment gets is the unallocated row. A month is
 * rewritten as a whole, so re-running replaces it.
 */
@Service
public class CarbonAttributionService implements CarbonIngestionService.CarbonMonthsListener {

    private static final Logger log = LoggerFactory.getLogger(CarbonAttributionService.class);
    static final String ANY_ACCOUNT = "*";

    /** One environment's cost in an account (null when its number is unknown), region and service. */
    record EnvCost(String environmentId, String account, String region, String service, BigDecimal cost) {
    }

    /** One environment's (or, with a null id, the unallocated) emissions for the month. */
    record Share(String environmentId, BigDecimal mbmKg, BigDecimal lbmKg, BigDecimal costBasis) {
    }

    record Attribution(List<Share> shares, BigDecimal coveragePct) {
    }

    private final CarbonMonthlyRepository carbonRepository;
    private final EnvironmentCarbonMonthlyRepository attributedRepository;
    private final EnvironmentRepository environmentRepository;
    private final VmRepository vmRepository;
    private final AwsAccountRepository accountRepository;
    private final CostDailySnapshotRepository snapshotRepository;
    private final CostDataProvider costDataProvider;
    private final CarbonBucketCostService bucketCostService;
    private final TransactionTemplate tx;
    private Clock clock = Clock.systemUTC();

    public CarbonAttributionService(CarbonMonthlyRepository carbonRepository, EnvironmentCarbonMonthlyRepository attributedRepository,
                                    EnvironmentRepository environmentRepository, VmRepository vmRepository,
                                    AwsAccountRepository accountRepository, CostDailySnapshotRepository snapshotRepository,
                                    CostDataProvider costDataProvider, CarbonBucketCostService bucketCostService,
                                    PlatformTransactionManager transactionManager) {
        this.carbonRepository = carbonRepository;
        this.attributedRepository = attributedRepository;
        this.environmentRepository = environmentRepository;
        this.vmRepository = vmRepository;
        this.accountRepository = accountRepository;
        this.snapshotRepository = snapshotRepository;
        this.costDataProvider = costDataProvider;
        this.bucketCostService = bucketCostService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    static String bucket(String account, String region, String service) {
        return account + "|" + region + "|" + service;
    }

    @Override
    public void monthsChanged(Set<LocalDate> months) {
        months.forEach(m -> attributeMonth(YearMonth.from(m)));
    }

    /** Rewrites the month's environment rows; returns how many were written. */
    public int attributeMonth(YearMonth month) {
        LocalDate first = month.atDay(1);
        List<CarbonMonthly> carbon = carbonRepository.findByMonth(first);
        List<EnvCost> costs = carbon.isEmpty() ? List.of() : costs(month);
        Map<String, String> numbers = new HashMap<>();
        accountRepository.findAll().forEach(a -> numbers.put(a.getAwsAccountRef(), a.getAccountNumber()));
        Set<String> known = new HashSet<>();
        numbers.values().stream().filter(n -> n != null && !n.isBlank()).forEach(known::add);
        Set<String> regions = new TreeSet<>();
        costs.forEach(c -> regions.add(c.region()));
        Map<String, BigDecimal> totals = carbon.isEmpty() ? Map.of() : bucketCostService.costs(month, regions);

        Attribution attribution = attribute(carbon, costs, totals, known);
        Map<String, Environment> envs = new HashMap<>();
        environmentRepository.findAllById(attribution.shares().stream().map(Share::environmentId).filter(id -> id != null).toList())
                .forEach(e -> envs.put(e.getEnvironmentId(), e));
        return tx.execute(status -> {
            attributedRepository.deleteByMonth(first);
            attributedRepository.flush();
            List<EnvironmentCarbonMonthly> rows = new ArrayList<>();
            for (Share s : attribution.shares()) {
                if (s.environmentId() != null && !envs.containsKey(s.environmentId())) continue;
                EnvironmentCarbonMonthly row = new EnvironmentCarbonMonthly();
                row.setId(UUID.randomUUID().toString());
                row.setEnvironment(s.environmentId() == null ? null : envs.get(s.environmentId()));
                row.setMonth(first);
                row.setEmissionsMbmKg(scale4(s.mbmKg()));
                row.setEmissionsLbmKg(scale4(s.lbmKg()));
                row.setCostBasis(s.costBasis() == null ? null : s.costBasis().setScale(2, RoundingMode.HALF_UP));
                row.setCoveragePct(attribution.coveragePct());
                rows.add(row);
            }
            attributedRepository.saveAll(rows);
            return rows.size();
        });
    }

    /** Each platform environment's cost that month by account number, region and service. */
    private List<EnvCost> costs(YearMonth month) {
        Map<String, String> numbers = new HashMap<>();
        accountRepository.findAll().forEach(a -> numbers.put(a.getAwsAccountRef(), a.getAccountNumber()));
        Instant start = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        if (end.isAfter(clock.instant())) end = clock.instant();
        if (!end.isAfter(start)) return List.of();
        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment().stream()
                .filter(v -> v.getProvider() != null && v.getProvider().isAws() && v.getGroup() != null).toList();
        if (vms.isEmpty()) return List.of();

        Map<String, BigDecimal> ratio = new HashMap<>();
        snapshotRepository.sumByEnvironmentBetween(Date.valueOf(month.atDay(1)), Date.valueOf(month.plusMonths(1).atDay(1)))
                .forEach(t -> {
                    if (t.getActualDays() != null && t.getActualDays() > 0 && t.getTotalActualCost() != null
                            && t.getEstimatedOnActualDays() != null && t.getEstimatedOnActualDays().signum() > 0) {
                        ratio.put(t.getEnvironmentId(), t.getTotalActualCost().divide(t.getEstimatedOnActualDays(), 6, RoundingMode.HALF_UP));
                    }
                });
        Map<String, CostDataProvider.VmCostEstimate> estimates = costDataProvider.estimateCosts(vms, Timestamp.from(start), Timestamp.from(end));
        Map<String, BigDecimal> fees = costDataProvider.environmentFees(vms, Timestamp.from(start), Timestamp.from(end));

        Map<String, BigDecimal> byKey = new LinkedHashMap<>();
        Map<String, String> eksRegion = new HashMap<>();
        Map<String, String> envAccount = new HashMap<>();
        for (Vm vm : vms) {
            CostDataProvider.VmCostEstimate e = estimates.get(vm.getVmId());
            String env = vm.getGroup().getEnvironment().getEnvironmentId();
            String account = numbers.get(vm.getAwsAccountRef() == null ? AwsAccount.DEFAULT_REF : vm.getAwsAccountRef());
            String region = vm.getRegion() == null ? "global" : vm.getRegion();
            if (vm.getProvider() == CloudProvider.AWS_EKS) {
                eksRegion.putIfAbsent(env, region);
                envAccount.putIfAbsent(env, account == null ? "" : account);
            }
            if (e == null || !e.costKnown() || e.cost() == null) continue;
            String service = vm.getProvider() == CloudProvider.AWS_RDS ? "AmazonRDS" : "AmazonEC2";
            BigDecimal cost = e.cost().multiply(ratio.getOrDefault(env, BigDecimal.ONE));
            byKey.merge(env + "\n" + (account == null ? "" : account) + "\n" + region + "\n" + service, cost, BigDecimal::add);
        }
        fees.forEach((env, fee) -> {
            if (fee == null || fee.signum() <= 0 || !eksRegion.containsKey(env)) return;
            byKey.merge(env + "\n" + envAccount.get(env) + "\n" + eksRegion.get(env) + "\nAmazonEKS",
                    fee.multiply(ratio.getOrDefault(env, BigDecimal.ONE)), BigDecimal::add);
        });
        List<EnvCost> costs = new ArrayList<>();
        byKey.forEach((k, cost) -> {
            String[] p = k.split("\n", -1);
            costs.add(new EnvCost(p[0], p[1].isEmpty() ? null : p[1], p[2], p[3], cost));
        });
        return costs;
    }

    /**
     * The arithmetic: shares of each known account's buckets, the remainder unallocated. Costs of an
     * account whose number the platform does not know count for the one account in the carbon data
     * that is not known; with several such accounts they cannot be placed and are left out.
     */
    static Attribution attribute(List<CarbonMonthly> carbon, List<EnvCost> costs, Map<String, BigDecimal> bucketTotals,
                                 Set<String> knownAccounts) {
        Set<String> unknownInCarbon = new TreeSet<>();
        carbon.forEach(c -> { if (!knownAccounts.contains(c.getAccountNumber())) unknownInCarbon.add(c.getAccountNumber()); });
        boolean anyUnnumbered = costs.stream().anyMatch(c -> c.account() == null);
        String resolved = anyUnnumbered && unknownInCarbon.size() == 1 ? unknownInCarbon.iterator().next() : null;
        if (anyUnnumbered && resolved == null) {
            log.info("Carbon attribution: costs of accounts without a number cannot be placed among {} unknown accounts", unknownInCarbon.size());
        }
        Set<String> counted = new HashSet<>(knownAccounts);
        if (resolved != null) counted.add(resolved);
        boolean all = counted.isEmpty();

        Map<String, Map<String, BigDecimal>> byBucket = new HashMap<>();
        Map<String, BigDecimal> costBasis = new LinkedHashMap<>();
        for (EnvCost c : costs) {
            String account = c.account() == null ? resolved : c.account();
            costBasis.merge(c.environmentId(), c.cost(), BigDecimal::add);
            if (account == null) continue;
            byBucket.computeIfAbsent(bucket(account, c.region(), c.service()), k -> new HashMap<>())
                    .merge(c.environmentId(), c.cost(), BigDecimal::add);
        }

        Map<String, BigDecimal[]> perEnv = new LinkedHashMap<>();
        BigDecimal[] unallocated = {null, null};
        BigDecimal totalMain = BigDecimal.ZERO, allocatedMain = BigDecimal.ZERO;
        for (CarbonMonthly row : carbon) {
            if (!all && !counted.contains(row.getAccountNumber())) continue;
            BigDecimal mbm = row.getEmissionsMbmKg(), lbm = row.getEmissionsLbmKg();
            BigDecimal main = mbm != null ? mbm : lbm == null ? BigDecimal.ZERO : lbm;
            totalMain = totalMain.add(main);
            Map<String, BigDecimal> envCosts = byBucket.getOrDefault(bucket(row.getAccountNumber(), row.getRegion(), row.getServiceCode()), Map.of());
            BigDecimal platform = envCosts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal total = bucketTotals.getOrDefault(bucket(row.getAccountNumber(), row.getRegion(), row.getServiceCode()), BigDecimal.ZERO)
                    .max(platform);
            BigDecimal givenMbm = BigDecimal.ZERO, givenLbm = BigDecimal.ZERO;
            if (total.signum() > 0 && platform.signum() > 0) {
                for (Map.Entry<String, BigDecimal> e : envCosts.entrySet()) {
                    BigDecimal share = e.getValue().divide(total, 10, RoundingMode.HALF_UP);
                    BigDecimal[] acc = perEnv.computeIfAbsent(e.getKey(), k -> new BigDecimal[]{null, null});
                    if (mbm != null) { BigDecimal v = mbm.multiply(share); acc[0] = add(acc[0], v); givenMbm = givenMbm.add(v); }
                    if (lbm != null) { BigDecimal v = lbm.multiply(share); acc[1] = add(acc[1], v); givenLbm = givenLbm.add(v); }
                }
            }
            if (mbm != null) unallocated[0] = add(unallocated[0], mbm.subtract(givenMbm));
            if (lbm != null) unallocated[1] = add(unallocated[1], lbm.subtract(givenLbm));
            allocatedMain = allocatedMain.add(mbm != null ? givenMbm : givenLbm);
        }

        List<Share> shares = new ArrayList<>();
        perEnv.forEach((env, v) -> shares.add(new Share(env, v[0], v[1], costBasis.get(env))));
        if (unallocated[0] != null || unallocated[1] != null) {
            shares.add(new Share(null, unallocated[0], unallocated[1], null));
        }
        BigDecimal coverage = totalMain.signum() == 0 ? null
                : allocatedMain.multiply(BigDecimal.valueOf(100)).divide(totalMain, 2, RoundingMode.HALF_UP);
        return new Attribution(shares, coverage);
    }

    private static BigDecimal add(BigDecimal a, BigDecimal b) {
        return a == null ? b : a.add(b);
    }

    private static BigDecimal scale4(BigDecimal v) {
        return v == null ? null : v.setScale(4, RoundingMode.HALF_UP);
    }
}
