package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.GroupStateCountsDTO;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** U05-T03: group state counts for a page of environments come from one query; scoped viewers see their groups. */
class GroupStateCountsQueryTest {

    private static VmRepository.GroupStatusCount row(String env, String group, int seq, VmStatus status, long total) {
        return new VmRepository.GroupStatusCount() {
            public String getEnvironmentId() { return env; }
            public String getGroupId() { return group; }
            public String getName() { return group.toLowerCase(); }
            public String getDisplayName() { return group; }
            public Integer getSequencePosition() { return seq; }
            public VmStatus getStatus() { return status; }
            public long getTotal() { return total; }
        };
    }

    private static EnvironmentService serviceWith(VmRepository vms) {
        EnvironmentService service = mock(EnvironmentService.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(service, "vmRepository", vms);
        return service;
    }

    @Test
    void fifteenEnvironmentsAreOneQueryAndGroupsComeInStartOrder() {
        VmRepository vms = mock(VmRepository.class);
        when(vms.countVmsByGroupAndStatus(anyList())).thenReturn(List.of(
                row("env-1", "Web", 3, VmStatus.STOPPED, 2),
                row("env-1", "Database", 1, VmStatus.RUNNING, 3),
                row("env-1", "Application", 2, VmStatus.RUNNING, 2),
                row("env-1", "Database", 1, VmStatus.ERROR, 1)));
        List<String> ids = IntStream.rangeClosed(1, 15).mapToObj(i -> "env-" + i).toList();

        Map<String, List<GroupStateCountsDTO>> states = serviceWith(vms).getGroupStateCounts(ids, null);

        verify(vms, times(1)).countVmsByGroupAndStatus(anyList());
        assertThat(states.get("env-1")).extracting(GroupStateCountsDTO::name).containsExactly("Database", "Application", "Web");
        assertThat(states.get("env-1").get(0)).isEqualTo(new GroupStateCountsDTO("Database", "Database", 1, 4, 3, 0, 0, 1, 0));
        assertThat(states).doesNotContainKey("env-2");
    }

    @Test
    void aScopedEnvironmentKeepsOnlyTheVisibleGroups() {
        VmRepository vms = mock(VmRepository.class);
        when(vms.countVmsByGroupAndStatus(anyList())).thenReturn(List.of(
                row("env-1", "Mine", 1, VmStatus.RUNNING, 1),
                row("env-1", "Other", 2, VmStatus.RUNNING, 4),
                row("env-2", "Shared", 1, VmStatus.STOPPED, 2)));

        Map<String, List<GroupStateCountsDTO>> states = serviceWith(vms)
                .getGroupStateCounts(List.of("env-1", "env-2"), Map.of("env-1", Set.of("Mine")));

        assertThat(states.get("env-1")).extracting(GroupStateCountsDTO::name).containsExactly("Mine");
        assertThat(states.get("env-2")).extracting(GroupStateCountsDTO::name).containsExactly("Shared");
    }

    @Test
    void noEnvironmentsAskNothing() {
        VmRepository vms = mock(VmRepository.class);
        assertThat(serviceWith(vms).getGroupStateCounts(List.of(), null)).isEmpty();
        verifyNoInteractions(vms);
    }
}
