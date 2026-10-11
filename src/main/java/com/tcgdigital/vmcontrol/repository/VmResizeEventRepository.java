package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmResizeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public interface VmResizeEventRepository extends JpaRepository<VmResizeEvent, String> {

    Optional<VmResizeEvent> findTopByVmIdAndChangedAtLessThanOrderByChangedAtDesc(String vmId, Timestamp before);

    /** Resizes of these VMs in [since, before), oldest first (the ledger keeps each VM's latest). */
    List<VmResizeEvent> findByVmIdInAndChangedAtGreaterThanEqualAndChangedAtLessThanOrderByChangedAtAsc(
            List<String> vmIds, Timestamp since, Timestamp before);
}
