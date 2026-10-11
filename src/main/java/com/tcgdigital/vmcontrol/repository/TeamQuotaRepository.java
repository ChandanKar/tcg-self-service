package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.TeamQuota;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Team quotas (E25-T07). */
public interface TeamQuotaRepository extends JpaRepository<TeamQuota, String> {

    List<TeamQuota> findByTeamIdOrderByMetricAsc(String teamId);

    List<TeamQuota> findByTeamIdAndIsActiveTrue(String teamId);

    List<TeamQuota> findByIsActiveTrue();

    /** Records a monthly warning; 0 when it was already sent (so each goes out once). */
    @Modifying
    @Query(nativeQuery = true, value = "INSERT IGNORE INTO team_quota_alert (quota_id, alert_month, alert_level) VALUES (:quotaId, :month, :level)")
    int recordAlert(@Param("quotaId") String quotaId, @Param("month") String month, @Param("level") int level);
}
