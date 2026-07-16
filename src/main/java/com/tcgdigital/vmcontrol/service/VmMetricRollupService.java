package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmMetricDaily;
import com.tcgdigital.vmcontrol.repository.VmMetricDailyRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Rolls raw {@code vm_metric_sample} rows (collected every few minutes by {@link VmMetricsService})
 * up into one {@code vm_metric_daily} row per VM per day — the aggregate Cost Management's
 * rightsizing recommendations actually read (see {@code CostEstimationService.buildRightsizingCandidates}).
 * Without this, rightsizing has no CPU history to evaluate no matter how long raw sampling runs.
 *
 * <p>Only running VMs get polled for CloudWatch metrics (stopped instances don't publish any),
 * so a VM that wasn't running on a given day gets an explicit zero-sample row for that day
 * rather than no row at all — {@link #rollupForDate} leaves its CPU fields {@code null} (not
 * {@code 0}) so it keeps failing rightsizing's null-CPU check exactly as before. Filling the row
 * is purely about giving {@code vm_metric_daily} complete day-by-day coverage per VM; it must
 * never make an intermittently-running VM's real utilization average look artificially lower by
 * blending in stopped-time zeros.
 */
@Service
public class VmMetricRollupService {

    private static final Logger log = LoggerFactory.getLogger(VmMetricRollupService.class);

    private final VmMetricSampleRepository sampleRepository;
    private final VmMetricDailyRepository dailyRepository;
    private final VmRepository vmRepository;

    /**
     * How many trailing days to (re-)aggregate on every run. Reuses the raw-sample retention
     * window rather than introducing a second "how far back" setting — there's no raw data
     * beyond this window to roll up anyway, and re-processing already-rolled-up days is a cheap,
     * idempotent upsert, so this doubles as automatic backfill the first time the job runs
     * against a database that already has weeks of un-rolled-up samples.
     */
    @Value("${vm.metrics.archive.raw-to-archive-days:30}")
    private int lookbackDays;

    public VmMetricRollupService(VmMetricSampleRepository sampleRepository,
                                  VmMetricDailyRepository dailyRepository,
                                  VmRepository vmRepository) {
        this.sampleRepository = sampleRepository;
        this.dailyRepository = dailyRepository;
        this.vmRepository = vmRepository;
    }

    /**
     * Aggregates every day in the lookback window, including today (so today's row stays fresh
     * as more samples arrive). Returns the number of vm_metric_daily rows written/updated.
     */
    @Transactional
    public int rollupDailyMetrics() {
        LocalDate today = LocalDate.now();
        List<Vm> activeVms = vmRepository.findByIsActiveTrue();
        int totalWritten = 0;
        for (int daysAgo = 0; daysAgo <= lookbackDays; daysAgo++) {
            totalWritten += rollupForDate(today.minusDays(daysAgo), activeVms);
        }
        log.info("VM metric daily rollup complete: {} bucket(s) written across {} day(s) for {} active VM(s)",
                totalWritten, lookbackDays + 1, activeVms.size());
        return totalWritten;
    }

    private int rollupForDate(LocalDate date, List<Vm> activeVms) {
        Timestamp dayStart = Timestamp.valueOf(date.atStartOfDay());
        Timestamp dayEnd = Timestamp.valueOf(date.plusDays(1).atStartOfDay());
        Date bucketDate = Date.valueOf(date);

        List<VmMetricSampleRepository.DailyAggregate> aggregates =
                sampleRepository.aggregateByVmForPeriod(dayStart, dayEnd);

        Map<String, Vm> vmsById = activeVms.stream().collect(Collectors.toMap(Vm::getVmId, Function.identity()));
        Map<String, VmMetricDaily> existingByVmId = dailyRepository.findByBucketDate(bucketDate).stream()
                .collect(Collectors.toMap(d -> d.getVm().getVmId(), Function.identity()));
        Set<String> vmIdsWithData = new HashSet<>();
        List<VmMetricDaily> toSave = new ArrayList<>();

        for (VmMetricSampleRepository.DailyAggregate agg : aggregates) {
            Vm vm = vmsById.get(agg.getVmId());
            if (vm == null) {
                continue; // deactivated/deleted since the sample was recorded
            }
            vmIdsWithData.add(agg.getVmId());

            VmMetricDaily daily = existingByVmId.getOrDefault(agg.getVmId(), new VmMetricDaily());
            daily.setVm(vm);
            daily.setBucketDate(bucketDate);
            daily.setAvgCpuUtilization(agg.getAvgCpu());
            daily.setMaxCpuUtilization(agg.getMaxCpu());
            daily.setSumNetworkInBytes(agg.getSumNetworkIn());
            daily.setSumNetworkOutBytes(agg.getSumNetworkOut());
            daily.setSumDiskReadBytes(agg.getSumDiskRead());
            daily.setSumDiskWriteBytes(agg.getSumDiskWrite());
            daily.setSampleCount(agg.getSampleCount() == null ? 0 : agg.getSampleCount().intValue());
            toSave.add(daily);
        }

        // Gap-fill VMs that had zero samples that day (not running, so CloudWatch had nothing to
        // report) with an explicit zero-sample row — CPU fields stay null so rightsizing's
        // null-CPU check keeps skipping them, same as if no row existed at all.
        for (Vm vm : activeVms) {
            if (vmIdsWithData.contains(vm.getVmId())) {
                continue;
            }
            VmMetricDaily existing = existingByVmId.get(vm.getVmId());
            if (existing != null && existing.getSampleCount() != null && existing.getSampleCount() > 0) {
                continue; // real data from a prior run whose raw samples have since been archived — keep it
            }

            VmMetricDaily daily = existing != null ? existing : new VmMetricDaily();
            daily.setVm(vm);
            daily.setBucketDate(bucketDate);
            daily.setAvgCpuUtilization(null);
            daily.setMaxCpuUtilization(null);
            daily.setSumNetworkInBytes(0L);
            daily.setSumNetworkOutBytes(0L);
            daily.setSumDiskReadBytes(0L);
            daily.setSumDiskWriteBytes(0L);
            daily.setSampleCount(0);
            toSave.add(daily);
        }

        dailyRepository.saveAll(toSave);
        return toSave.size();
    }
}
