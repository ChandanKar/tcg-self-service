package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmVolumeSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VmVolumeSnapshotRepository extends JpaRepository<VmVolumeSnapshot, String> {
    List<VmVolumeSnapshot> findByVmVmIdOrderByDeviceNameAsc(String vmId);
    List<VmVolumeSnapshot> findByVmVmIdIn(List<String> vmIds);
    Optional<VmVolumeSnapshot> findByVmVmIdAndVolumeId(String vmId, String volumeId);
    void deleteByVmVmIdAndVolumeIdNotIn(String vmId, List<String> volumeIds);
    void deleteByVmVmId(String vmId);

    /**
     * Total provisioned storage per VM for a batch of VMs in one query, for cost estimation.
     */
    @Query("SELECT v.vm.vmId AS vmId, COALESCE(SUM(v.sizeGib), 0) AS totalSizeGib " +
           "FROM VmVolumeSnapshot v WHERE v.vm.vmId IN :vmIds GROUP BY v.vm.vmId")
    List<VolumeSizeTotal> sumSizeGibByVmIds(@Param("vmIds") List<String> vmIds);

    interface VolumeSizeTotal {
        String getVmId();
        Long getTotalSizeGib();
    }
}
