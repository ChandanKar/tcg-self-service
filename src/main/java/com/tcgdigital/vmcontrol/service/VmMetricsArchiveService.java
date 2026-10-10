package com.tcgdigital.vmcontrol.service;

import java.time.LocalDate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.model.VmMetricSampleArchive;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleArchiveRepository;
import com.tcgdigital.vmcontrol.repository.VmMetricSampleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service
public class VmMetricsArchiveService {

    private static final Logger log = LoggerFactory.getLogger(VmMetricsArchiveService.class);

    private final VmMetricSampleRepository sampleRepository;
    private final VmMetricSampleArchiveRepository archiveRepository;

    @Value("${vm.metrics.archive.raw-to-archive-days:30}")
    private int rawToArchiveDays = 30;

    @Value("${vm.metrics.archive.batch-size:10000}")
    private int batchSize = 10000;

    /** Bounds one run on a backlog (E12-T01); the rest moves on the next run. */
    @Value("${vm.metrics.archive.max-batches-per-run:200}")
    private int maxBatchesPerRun = 200;

    /** Archive rows older than this many days are deleted; 0 keeps them forever (E12-T01). */
    @Value("${vm.metrics.archive.retention-days:365}")
    private int retentionDays = 365;

    private static final int PURGE_BATCH = 10000;

    private final TransactionTemplate batchTransaction;

    public VmMetricsArchiveService(VmMetricSampleRepository sampleRepository,
                                   VmMetricSampleArchiveRepository archiveRepository,
                                   PlatformTransactionManager transactionManager) {
        this.sampleRepository = sampleRepository;
        this.archiveRepository = archiveRepository;
        this.batchTransaction = new TransactionTemplate(transactionManager);
        this.batchTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Moves hot samples from before the start of day (today - raw-to-archive-days) into the
     * archive, in batches that each commit on their own (M17), until a short batch or
     * max-batches-per-run; then purges archive rows past retention-days. The cutoff is a day
     * boundary so the daily rollup never sees a partly archived day.
     *
     * @return how many hot samples were moved
     */
    public int archiveOldRawSamples() {
        Timestamp cutoff = Timestamp.valueOf(LocalDate.now().minusDays(rawToArchiveDays).atStartOfDay());
        int moved = 0;
        int batches = 0;
        int last;
        do {
            Integer n = batchTransaction.execute(status -> archiveBatch(cutoff));
            last = n == null ? 0 : n;
            moved += last;
            if (last > 0) {
                batches++;
            }
        } while (last >= batchSize && batches < maxBatchesPerRun);

        int purged = purgeArchive();
        log.info("VM metrics archive: moved {} sample(s) in {} batch(es) (cutoff {}), purged {} archive row(s)",
                moved, batches, cutoff, purged);
        return moved;
    }

    /** One batch: copy the not-yet-archived samples, then delete the whole batch from the hot table. */
    private int archiveBatch(Timestamp cutoff) {
        List<VmMetricSample> batch = sampleRepository.findArchiveBatch(cutoff, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return 0;
        }
        java.util.Set<String> alreadyArchived = new java.util.HashSet<>(archiveRepository.findExistingOriginalIds(
                batch.stream().map(VmMetricSample::getMetricSampleId).toList()));
        List<VmMetricSampleArchive> archives = new ArrayList<>();
        for (VmMetricSample sample : batch) {
            if (!alreadyArchived.contains(sample.getMetricSampleId())) {
                archives.add(copyToArchive(sample));
            }
        }
        if (!archives.isEmpty()) {
            archiveRepository.saveAll(archives);
            archiveRepository.flush();
        }
        sampleRepository.deleteAllInBatch(batch);
        return batch.size();
    }

    private int purgeArchive() {
        if (retentionDays <= 0) {
            return 0;
        }
        Timestamp cutoff = Timestamp.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        int total = 0;
        int last;
        do {
            Integer n = batchTransaction.execute(status -> archiveRepository.deleteOlderThan(cutoff, PURGE_BATCH));
            last = n == null ? 0 : n;
            total += last;
        } while (last >= PURGE_BATCH);
        return total;
    }

    private VmMetricSampleArchive copyToArchive(VmMetricSample sample) {
        VmMetricSampleArchive archive = new VmMetricSampleArchive();
        archive.setOriginalMetricSampleId(sample.getMetricSampleId());
        archive.setVm(sample.getVm());
        archive.setProvider(sample.getProvider());
        archive.setProviderVmId(sample.getProviderVmId());
        archive.setSampleTime(sample.getSampleTime());
        archive.setPeriodSeconds(sample.getPeriodSeconds());
        archive.setCpuUtilization(sample.getCpuUtilization());
        archive.setMemoryUtilization(sample.getMemoryUtilization());
        archive.setNetworkInBytes(sample.getNetworkInBytes());
        archive.setNetworkOutBytes(sample.getNetworkOutBytes());
        archive.setDiskReadBytes(sample.getDiskReadBytes());
        archive.setDiskWriteBytes(sample.getDiskWriteBytes());
        archive.setStatusAtSample(sample.getStatusAtSample());
        archive.setCreatedAt(sample.getCreatedAt());
        return archive;
    }
}
