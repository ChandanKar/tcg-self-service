package com.tcgdigital.vmcontrol.service;

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
    private int rawToArchiveDays;

    @Value("${vm.metrics.archive.batch-size:10000}")
    private int batchSize;

    public VmMetricsArchiveService(VmMetricSampleRepository sampleRepository,
                                   VmMetricSampleArchiveRepository archiveRepository) {
        this.sampleRepository = sampleRepository;
        this.archiveRepository = archiveRepository;
    }

    @Transactional
    public int archiveOldRawSamples() {
        Timestamp cutoff = Timestamp.from(Instant.now().minus(rawToArchiveDays, ChronoUnit.DAYS));
        List<VmMetricSample> batch = sampleRepository.findArchiveBatch(cutoff, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            log.debug("No VM metric samples older than {} to archive", cutoff);
            return 0;
        }

        List<VmMetricSampleArchive> archives = new ArrayList<>();
        List<VmMetricSample> deleteCandidates = new ArrayList<>();
        for (VmMetricSample sample : batch) {
            boolean alreadyArchived = archiveRepository.existsByVmVmIdAndSampleTimeAndPeriodSeconds(
                    sample.getVm().getVmId(), sample.getSampleTime(), sample.getPeriodSeconds());
            if (!alreadyArchived) {
                archives.add(copyToArchive(sample));
            }
            deleteCandidates.add(sample);
        }

        if (!archives.isEmpty()) {
            archiveRepository.saveAll(archives);
            archiveRepository.flush();
        }

        sampleRepository.deleteAllInBatch(deleteCandidates);
        log.info("Archived {} VM metric sample(s), deleted {} hot sample(s)", archives.size(), deleteCandidates.size());
        return deleteCandidates.size();
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
        archive.setNetworkInBytes(sample.getNetworkInBytes());
        archive.setNetworkOutBytes(sample.getNetworkOutBytes());
        archive.setDiskReadBytes(sample.getDiskReadBytes());
        archive.setDiskWriteBytes(sample.getDiskWriteBytes());
        archive.setStatusAtSample(sample.getStatusAtSample());
        archive.setCreatedAt(sample.getCreatedAt());
        return archive;
    }
}
