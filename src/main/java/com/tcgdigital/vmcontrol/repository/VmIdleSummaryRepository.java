package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmIdleSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface VmIdleSummaryRepository extends JpaRepository<VmIdleSummary, String> {
    Optional<VmIdleSummary> findByVmVmId(String vmId);
    List<VmIdleSummary> findByVmVmIdIn(List<String> vmIds);

    /** Summaries still marked idle for VMs that are not running (U23-T03): a stopped VM is never idle. */
    @Query("SELECT s FROM VmIdleSummary s WHERE s.idle = true AND s.vm.status <> com.tcgdigital.vmcontrol.model.VmStatus.RUNNING")
    List<VmIdleSummary> findIdleOfVmsNotRunning();
}
