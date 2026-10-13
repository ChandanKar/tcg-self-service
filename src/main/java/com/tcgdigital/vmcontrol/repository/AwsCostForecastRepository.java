package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AwsCostForecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

public interface AwsCostForecastRepository extends JpaRepository<AwsCostForecast, String> {

    /** The newest capture date of a granularity, or null before the first capture. */
    @Query("SELECT MAX(f.generatedOn) FROM AwsCostForecast f WHERE f.granularity = :granularity AND f.scope = :scope")
    LocalDate latestGeneration(@Param("granularity") String granularity, @Param("scope") String scope);

    List<AwsCostForecast> findByGeneratedOnAndGranularityAndScopeOrderByPeriodStart(LocalDate generatedOn, String granularity,
                                                                                    String scope);

    /** Clears one capture before it is written again (a re-run on the same day). */
    @Modifying
    @Transactional
    @Query("DELETE FROM AwsCostForecast f WHERE f.generatedOn = :generatedOn AND f.granularity = :granularity AND f.scope = :scope")
    int deleteGeneration(@Param("generatedOn") LocalDate generatedOn, @Param("granularity") String granularity,
                         @Param("scope") String scope);
}
