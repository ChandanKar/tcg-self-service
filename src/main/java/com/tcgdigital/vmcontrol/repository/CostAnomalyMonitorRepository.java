package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.CostAnomalyMonitor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CostAnomalyMonitorRepository extends JpaRepository<CostAnomalyMonitor, String> {

    List<CostAnomalyMonitor> findByEnvironmentIdIsNotNull();

    List<CostAnomalyMonitor> findAllByOrderByMonitorNameAsc();
}
