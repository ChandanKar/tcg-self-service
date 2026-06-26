package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.VmVolumeSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
