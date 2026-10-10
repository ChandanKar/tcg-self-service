package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.repository.VmVolumeSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Inventory sync notices an instance-type change made outside the app and stamps when it
 * happened, so rightsizing ignores evidence from the old size (E08-T06).
 */
@ExtendWith(MockitoExtension.class)
class VmInventoryServiceTest {

    @Mock private VmRepository vmRepository;
    @Mock private VmInventorySnapshotRepository inventoryRepository;
    @Mock private VmVolumeSnapshotRepository volumeRepository;
    @Mock private CloudInventoryProviderFactory providerFactory;
    @Mock private CloudInventoryProviderService provider;

    private VmInventoryService service;
    private Vm vm;
    private VmInventorySnapshot stored;

    @BeforeEach
    void setUp() {
        service = new VmInventoryService(vmRepository, inventoryRepository, volumeRepository, providerFactory);
        vm = new Vm();
        vm.setVmId("vm-1");
        vm.setProvider(CloudProvider.AWS);
        vm.setProviderVmId("i-1");
        vm.setRegion("us-east-1");
        stored = new VmInventorySnapshot();
        stored.setInstanceType("m5.xlarge");
        when(vmRepository.findByIsActiveTrue()).thenReturn(List.of(vm));
        when(providerFactory.getService(CloudProvider.AWS)).thenReturn(Optional.of(provider));
        when(provider.isAvailable()).thenReturn(true);
        when(inventoryRepository.findByVmVmId("vm-1")).thenReturn(Optional.of(stored));
        lenient().when(volumeRepository.findByVmVmIdOrderByDeviceNameAsc(any())).thenReturn(List.of());
    }

    private void awsReports(String type) {
        CloudInventoryProviderService.VmInventoryData data = new CloudInventoryProviderService.VmInventoryData();
        data.setProviderVmId("i-1");
        data.setInstanceType(type);
        when(provider.fetchInventory(anyList(), any())).thenReturn(Map.of("i-1", data));
    }

    @Test
    void aTypeChangedOutsideTheAppIsStamped() {
        awsReports("m5.large");

        service.syncAllInventory();

        assertThat(stored.getInstanceType()).isEqualTo("m5.large");
        assertThat(stored.getInstanceTypeChangedAt()).isNotNull();
    }

    @Test
    void anUnchangedTypeKeepsThePreviousStamp() {
        Timestamp earlier = Timestamp.from(Instant.parse("2026-09-01T00:00:00Z"));
        stored.setInstanceTypeChangedAt(earlier);
        awsReports("m5.xlarge");

        service.syncAllInventory();

        assertThat(stored.getInstanceTypeChangedAt()).isEqualTo(earlier);
    }
}
