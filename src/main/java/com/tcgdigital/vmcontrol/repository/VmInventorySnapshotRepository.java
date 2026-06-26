package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface VmInventorySnapshotRepository extends JpaRepository<VmInventorySnapshot, String> {
    Optional<VmInventorySnapshot> findByVmVmId(String vmId);
    List<VmInventorySnapshot> findByVmVmIdIn(List<String> vmIds);
}
