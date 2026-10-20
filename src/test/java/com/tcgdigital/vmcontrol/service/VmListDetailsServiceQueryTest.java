package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmInventorySnapshot;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmStateHistoryRepository;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** U05-T01/T02: a group page of 25 VMs costs one history query and one inventory query. */
class VmListDetailsServiceQueryTest {

    private static Vm vm(String id) {
        Vm vm = new Vm();
        vm.setVmId(id);
        vm.setStatus(VmStatus.RUNNING);
        return vm;
    }

    @Test
    void twentyFiveVmsAreOneHistoryQueryAndOneInventoryQuery() {
        VmInventorySnapshotRepository inventory = mock(VmInventorySnapshotRepository.class);
        VmStateHistoryRepository history = mock(VmStateHistoryRepository.class);
        Timestamp since = Timestamp.valueOf("2026-10-20 14:10:00");
        List<Object[]> rows = new ArrayList<>();
        rows.add(new Object[]{"vm-3", VmStatus.RUNNING, since});
        rows.add(new Object[]{"vm-3", VmStatus.STOPPED, Timestamp.valueOf("2026-10-20 12:00:00")});
        when(history.findLastEnteredAtByStatus(anyList())).thenReturn(rows);
        VmInventorySnapshot snapshot = new VmInventorySnapshot();
        snapshot.setVm(vm("vm-3"));
        snapshot.setPrivateIp("10.0.1.23");
        snapshot.setInstanceType("t3.medium");
        when(inventory.findByVmVmIdIn(anyList())).thenReturn(List.of(snapshot));

        List<Vm> vms = IntStream.rangeClosed(1, 25).mapToObj(i -> vm("vm-" + i)).toList();

        Map<String, VmListDetailsService.Details> details = new VmListDetailsService(inventory, history).detailsFor(vms);

        verify(history, times(1)).findLastEnteredAtByStatus(anyList());
        verify(inventory, times(1)).findByVmVmIdIn(anyList());
        assertThat(details).hasSize(25);
        assertThat(details.get("vm-3")).isEqualTo(new VmListDetailsService.Details("10.0.1.23", "t3.medium", since));
        assertThat(details.get("vm-4")).isEqualTo(new VmListDetailsService.Details(null, null, null));
    }
}
