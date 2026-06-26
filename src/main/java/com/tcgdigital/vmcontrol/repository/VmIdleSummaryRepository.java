package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmIdleSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface VmIdleSummaryRepository extends JpaRepository<VmIdleSummary, String> {
    Optional<VmIdleSummary> findByVmVmId(String vmId);
    List<VmIdleSummary> findByVmVmIdIn(List<String> vmIds);
}
