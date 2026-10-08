package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.MoveVmDTO;
import com.tcgdigital.vmcontrol.dto.RegisterVmDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VM names are checked as stored, and moving a VM between groups (E09-T08, M34).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class VmServiceTest {

    @Mock private VmRepository vmRepository;
    @Mock private VmGroupRepository groupRepository;
    @Mock private DependencyValidator dependencyValidator;
    @Mock private AuditService auditService;
    @Mock private CloudProviderFactory cloudProviderFactory;

    private VmService service;
    private Environment env;
    private VmGroup discovered;
    private VmGroup web;
    private Vm vm;

    private static VmGroup group(String id, String name, Environment env) {
        VmGroup g = new VmGroup();
        g.setGroupId(id);
        g.setName(name);
        g.setEnvironment(env);
        return g;
    }

    @BeforeEach
    void setUp() {
        service = new VmService(vmRepository, groupRepository, dependencyValidator, auditService, cloudProviderFactory);
        env = new Environment();
        env.setEnvironmentId("env-1");
        env.setName("prod");
        discovered = group("g-disc", "discovered", env);
        web = group("g-web", "web", env);
        vm = new Vm();
        vm.setVmId("vm-1");
        vm.setName("found-1");
        vm.setGroup(discovered);
        vm.setProvider(CloudProvider.AWS);
        vm.setSequencePosition(1);
        vm.setDiscoveryPending(true);
        vm.setDependencies(new ArrayList<>(List.of("vm-0")));
        when(vmRepository.findById("vm-1")).thenReturn(Optional.of(vm));
        when(groupRepository.findById("g-web")).thenReturn(Optional.of(web));
        when(groupRepository.findById("g-disc")).thenReturn(Optional.of(discovered));
        when(vmRepository.findByGroupId("g-disc")).thenReturn(List.of(vm));
        when(vmRepository.save(any(Vm.class))).then(returnsFirstArg());
    }

    private static MoveVmDTO moveTo(String groupId, Integer position) {
        MoveVmDTO dto = new MoveVmDTO();
        dto.setTargetGroupId(groupId);
        dto.setSequencePosition(position);
        return dto;
    }

    // ---- names (inventory-low: uniqueness checked on the raw name, stored normalised) ----

    @Test
    void registeringANameThatNormalisesToAnExistingOneIsRefused() {
        RegisterVmDTO dto = new RegisterVmDTO();
        dto.setGroupId("g-web");
        dto.setName(" Web 1 ");
        when(vmRepository.existsByGroupGroupIdAndName("g-web", "web-1")).thenReturn(true);

        assertThatThrownBy(() -> service.registerVm(dto))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already exists");
        verify(vmRepository, never()).save(any());
    }

    @Test
    void renamingToANameThatNormalisesToAnExistingOneIsRefused() {
        RegisterVmDTO dto = new RegisterVmDTO();
        dto.setName("Found 2");
        dto.setSequencePosition(1);
        when(vmRepository.existsByGroupGroupIdAndName("g-disc", "found-2")).thenReturn(true);

        assertThatThrownBy(() -> service.updateVm("vm-1", dto))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already exists");
    }

    @Test
    void normalisation() {
        assertThat(VmService.normalizeName("  Web   Server 1 ")).isEqualTo("web-server-1");
    }

    // ---- move (M34) ----

    @Test
    void aMoveTakesTheRequestedPositionWhenFree() {
        when(vmRepository.existsByGroupGroupIdAndSequencePosition("g-web", 4)).thenReturn(false);

        Vm moved = service.moveVm("vm-1", moveTo("g-web", 4), "admin-1");

        assertThat(moved.getGroup()).isSameAs(web);
        assertThat(moved.getSequencePosition()).isEqualTo(4);
        assertThat(moved.getDiscoveryPending()).isFalse();
        assertThat(moved.getDependencies()).isEmpty();
        verify(auditService).logEnvironmentAction(eq("admin-1"), eq(AuditAction.VM_UPDATED), eq("env-1"), eq("prod"),
                eq("vm"), eq("vm-1"), eq("found-1"), contains("Moved from group 'discovered' to 'web'"));
    }

    @Test
    void aTakenOrMissingPositionBecomesTheNextFreeOne() {
        when(vmRepository.existsByGroupGroupIdAndSequencePosition("g-web", 2)).thenReturn(true);
        when(vmRepository.findMaxSequencePositionByGroupId("g-web")).thenReturn(7);

        assertThat(service.moveVm("vm-1", moveTo("g-web", 2), "admin-1").getSequencePosition()).isEqualTo(8);
    }

    @Test
    void aGroupOfAnotherEnvironmentIsRefused() {
        Environment other = new Environment();
        other.setEnvironmentId("env-2");
        when(groupRepository.findById("g-other")).thenReturn(Optional.of(group("g-other", "web", other)));

        assertThatThrownBy(() -> service.moveVm("vm-1", moveTo("g-other", null), "admin-1"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("another environment");
        verify(vmRepository, never()).save(any());
    }

    @Test
    void eksNodeGroupsAreNotMoved() {
        vm.setProvider(CloudProvider.AWS_EKS);

        assertThatThrownBy(() -> service.moveVm("vm-1", moveTo("g-web", null), "admin-1"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("EKS");
    }

    @Test
    void aVmOthersDependOnIsNotMoved() {
        Vm dependent = new Vm();
        dependent.setVmId("vm-2");
        dependent.setName("app");
        dependent.setDependencies(List.of("vm-1"));
        when(vmRepository.findByGroupId("g-disc")).thenReturn(List.of(vm, dependent));

        assertThatThrownBy(() -> service.moveVm("vm-1", moveTo("g-web", null), "admin-1"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("'app' depends on it");
        verify(vmRepository, never()).save(any());
    }

    @Test
    void aNameTakenInTheTargetGroupIsRefused() {
        when(vmRepository.existsByGroupGroupIdAndName("g-web", "found-1")).thenReturn(true);

        assertThatThrownBy(() -> service.moveVm("vm-1", moveTo("g-web", null), "admin-1"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already exists in group 'web'");
        verify(vmRepository, never()).existsByGroupGroupIdAndSequencePosition(anyString(), anyInt());
    }
}
