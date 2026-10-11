package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.EksWorkloadCostDaily;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.sql.Date;
import java.util.List;

public interface EksWorkloadCostDailyRepository extends JpaRepository<EksWorkloadCostDaily, EksWorkloadCostDaily.Key> {

    List<EksWorkloadCostDaily> findByEnvironmentIdAndUsageDateBetweenOrderByUsageDateAsc(String environmentId, Date from, Date to);

    /** Clears one environment's day before its fresh rows are written (E26-T04). */
    @Modifying
    @Query("DELETE FROM EksWorkloadCostDaily c WHERE c.environmentId = :environmentId AND c.usageDate = :usageDate")
    int deleteDay(@Param("environmentId") String environmentId, @Param("usageDate") Date usageDate);
}
