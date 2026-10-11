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

    /** Not yet routed to owners (E21-T03). */
    List<CostAnomaly> findByNotifiedAtIsNullAndEnvironmentIdIsNotNull();
}
