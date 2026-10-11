package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.CostAnomaly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface CostAnomalyRepository extends JpaRepository<CostAnomaly, String> {

    List<CostAnomaly> findByEnvironmentIdAndAnomalyStartGreaterThanEqualOrderByAnomalyStartDesc(String environmentId, LocalDate since);

    List<CostAnomaly> findByAnomalyStartGreaterThanEqualOrderByAnomalyStartDesc(LocalDate since);

    org.springframework.data.domain.Page<CostAnomaly> findByAnomalyStartGreaterThanEqual(
            LocalDate since, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<CostAnomaly> findByEnvironmentIdAndAnomalyStartGreaterThanEqual(
            String environmentId, LocalDate since, org.springframework.data.domain.Pageable pageable);

    /** Not yet announced (E21-T03). */
    List<CostAnomaly> findByNotifiedAtIsNull();
}
