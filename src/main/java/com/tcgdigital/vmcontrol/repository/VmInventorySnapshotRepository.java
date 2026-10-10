package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface VmInventorySnapshotRepository extends JpaRepository<VmInventorySnapshot, String> {
    Optional<VmInventorySnapshot> findByVmVmId(String vmId);
    List<VmInventorySnapshot> findByVmVmIdIn(List<String> vmIds);

    /**
     * Instance type per VM for a batch of VMs, for cost estimation — avoids loading (and lazily
     * dereferencing) full snapshot entities just to read one field.
     */
    @Query("SELECT s.vm.vmId AS vmId, s.instanceType AS instanceType, s.instanceTypeChangedAt AS instanceTypeChangedAt " +
           "FROM VmInventorySnapshot s WHERE s.vm.vmId IN :vmIds")
    List<InstanceTypeProjection> findInstanceTypesByVmIds(@Param("vmIds") List<String> vmIds);

    interface InstanceTypeProjection {
        String getVmId();
        String getInstanceType();
        /** When the type last changed (E08-T06); null if never seen to change. */
        java.sql.Timestamp getInstanceTypeChangedAt();
    }
}
