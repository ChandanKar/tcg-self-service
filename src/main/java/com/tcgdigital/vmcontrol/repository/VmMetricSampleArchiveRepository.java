package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmMetricSampleArchive;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VmMetricSampleArchiveRepository extends JpaRepository<VmMetricSampleArchive, String> {
    boolean existsByVmVmIdAndSampleTimeAndPeriodSeconds(String vmId, java.sql.Timestamp sampleTime, Integer periodSeconds);

    /** Which of these hot-sample ids are already archived: one query per batch (E12-T01). */
    @org.springframework.data.jpa.repository.Query(
            "SELECT a.originalMetricSampleId FROM VmMetricSampleArchive a WHERE a.originalMetricSampleId IN :ids")
    java.util.List<String> findExistingOriginalIds(@org.springframework.data.repository.query.Param("ids") java.util.Collection<String> ids);

    /** Archive retention (E12-T01): delete up to {@code limit} archive rows older than {@code cutoff}. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(nativeQuery = true,
            value = "DELETE FROM vm_metric_sample_archive WHERE sample_time < :cutoff ORDER BY sample_time LIMIT :limit")
    int deleteOlderThan(@org.springframework.data.repository.query.Param("cutoff") java.sql.Timestamp cutoff,
                        @org.springframework.data.repository.query.Param("limit") int limit);
}
