package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.CircularDependencyException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.*;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class DependencyValidatorTest {

    @Autowired
    private DependencyValidator dependencyValidator;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private VmGroupRepository groupRepository;

    @Autowired
    private VmRepository vmRepository;

    private Environment testEnvironment;

    @BeforeEach
    void setUp() {
        vmRepository.deleteAll();
        groupRepository.deleteAll();
        environmentRepository.deleteAll();

        // Create test environment
        testEnvironment = new Environment();
        testEnvironment.setEnvironmentId(UUID.randomUUID().toString());
        testEnvironment.setName("test-env");
        testEnvironment.setDisplayName("Test Environment");
        testEnvironment.setIsActive(true);
        testEnvironment = environmentRepository.save(testEnvironment);
    }

    // ==================== Group Dependency Tests ====================

    @Test
    void testTopologicalSort_SimpleChain() {
        // Given: Group1 -> Group2 -> Group3
        VmGroup group1 = createGroup("data-tier", 1, null);
        VmGroup group2 = createGroup("backend-tier", 2, List.of(group1.getGroupId()));
        VmGroup group3 = createGroup("frontend-tier", 3, List.of(group2.getGroupId()));

        // When
        List<VmGroup> sorted = dependencyValidator.topologicalSort(List.of(group1, group2, group3));

        // Then
        assertEquals(3, sorted.size());
        assertEquals(group1.getGroupId(), sorted.get(0).getGroupId());
        assertEquals(group2.getGroupId(), sorted.get(1).getGroupId());
        assertEquals(group3.getGroupId(), sorted.get(2).getGroupId());
    }

    @Test
    void testTopologicalSort_ParallelDependencies() {
        // Given: Group1 <- Group2
        //              <- Group3
        VmGroup group1 = createGroup("data-tier", 1, null);
        VmGroup group2 = createGroup("backend-tier", 2, List.of(group1.getGroupId()));
        VmGroup group3 = createGroup("cache-tier", 3, List.of(group1.getGroupId()));

        // When
        List<VmGroup> sorted = dependencyValidator.topologicalSort(List.of(group1, group2, group3));

        // Then
        assertEquals(3, sorted.size());
        assertEquals(group1.getGroupId(), sorted.get(0).getGroupId());
        // Group2 and Group3 can be in any order after Group1
        assertTrue(sorted.get(1).getGroupId().equals(group2.getGroupId()) ||
                sorted.get(1).getGroupId().equals(group3.getGroupId()));
    }

    @Test
    void testTopologicalSort_DiamondDependency() {
        // Given: Group1 <- Group2 <- Group4
        //              <- Group3 <-
        VmGroup group1 = createGroup("data-tier", 1, null);
        VmGroup group2 = createGroup("backend-tier", 2, List.of(group1.getGroupId()));
        VmGroup group3 = createGroup("cache-tier", 3, List.of(group1.getGroupId()));
        VmGroup group4 = createGroup("frontend-tier", 4, List.of(group2.getGroupId(), group3.getGroupId()));

        // When
        List<VmGroup> sorted = dependencyValidator.topologicalSort(List.of(group1, group2, group3, group4));

        // Then
        assertEquals(4, sorted.size());
        assertEquals(group1.getGroupId(), sorted.get(0).getGroupId());
        assertEquals(group4.getGroupId(), sorted.get(3).getGroupId());
    }

    @Test
    void testCircularDependencyDetection() {
        // Given: Group1 -> Group2 -> Group3
        VmGroup group1 = createGroup("group1", 1, null);
        VmGroup group2 = createGroup("group2", 2, List.of(group1.getGroupId()));
        VmGroup group3 = createGroup("group3", 3, List.of(group2.getGroupId()));

        // When/Then: Try to make Group1 depend on Group3 (creates cycle)
        assertThrows(CircularDependencyException.class, () -> {
            dependencyValidator.detectCircularDependency(
                    testEnvironment.getEnvironmentId(),
                    group1.getGroupId(),
                    List.of(group3.getGroupId())
            );
        });
    }

    @Test
    void testValidateGroupDependencies_NonExistentDependency() {
        // When/Then
        assertThrows(ValidationException.class, () -> {
            dependencyValidator.validateGroupDependencies(
                    testEnvironment.getEnvironmentId(),
                    "new-group-id",
                    List.of("non-existent-group-id")
            );
        });
    }

    // ==================== VM Dependency Tests ====================

    @Test
    void testValidateVmDependencies_CrossGroupDependency_ThrowsException() {
        // Given: Two groups with one VM each
        VmGroup group1 = createGroup("group1", 1, null);
        VmGroup group2 = createGroup("group2", 2, null);
        Vm vm1 = createVm(group1, "vm1", 1, null);

        // When/Then: Try to create VM in group2 that depends on VM in group1
        assertThrows(ValidationException.class, () -> {
            dependencyValidator.validateVmDependencies(
                    group2.getGroupId(),
                    "new-vm-id",
                    List.of(vm1.getVmId())
            );
        });
    }

    @Test
    void testAreVmDependenciesSatisfied_NoDependencies() {
        // Given
        VmGroup group = createGroup("test-group", 1, null);
        Vm vm = createVm(group, "vm1", 1, null);

        // When/Then
        assertTrue(dependencyValidator.areVmDependenciesSatisfied(vm));
    }

    @Test
    void testAreVmDependenciesSatisfied_DependencyNotRunning() {
        // Given
        VmGroup group = createGroup("test-group", 1, null);
        Vm vm1 = createVm(group, "vm1", 1, null);
        vm1.setStatus(VmStatus.STOPPED);
        vmRepository.save(vm1);

        Vm vm2 = createVm(group, "vm2", 2, List.of(vm1.getVmId()));

        // When/Then
        assertFalse(dependencyValidator.areVmDependenciesSatisfied(vm2));
    }

    @Test
    void testAreVmDependenciesSatisfied_DependencyRunning() {
        // Given
        VmGroup group = createGroup("test-group", 1, null);
        Vm vm1 = createVm(group, "vm1", 1, null);
        vm1.setStatus(VmStatus.RUNNING);
        vmRepository.save(vm1);

        Vm vm2 = createVm(group, "vm2", 2, List.of(vm1.getVmId()));

        // When/Then
        assertTrue(dependencyValidator.areVmDependenciesSatisfied(vm2));
    }

    @Test
    void testGetVmStartBatches() {
        // Given: 3 VMs with sequence positions 1, 2, 3
        // Note: sequence_position must be unique per group due to DB constraint
        VmGroup group = createGroup("test-group", 1, null);
        Vm vm1 = createVm(group, "vm1", 1, null);
        Vm vm2 = createVm(group, "vm2", 2, null);
        Vm vm3 = createVm(group, "vm3", 3, null);

        // When
        List<List<Vm>> batches = dependencyValidator.getVmStartBatches(group.getGroupId());

        // Then
        assertEquals(3, batches.size());
        assertEquals(1, batches.get(0).size()); // First batch with sequence 1
        assertEquals(1, batches.get(1).size()); // Second batch with sequence 2
        assertEquals(1, batches.get(2).size()); // Third batch with sequence 3
    }

    // ==================== Helper Methods ====================

    private VmGroup createGroup(String name, int sequence, List<String> dependsOn) {
        VmGroup group = new VmGroup();
        group.setGroupId(UUID.randomUUID().toString());
        group.setEnvironment(testEnvironment);
        group.setName(name);
        group.setDisplayName(name);
        group.setSequencePosition(sequence);
        group.setDependencies(dependsOn);
        return groupRepository.save(group);
    }

    private Vm createVm(VmGroup group, String name, int sequence, List<String> dependsOn) {
        Vm vm = new Vm();
        vm.setVmId(UUID.randomUUID().toString());
        vm.setGroup(group);
        vm.setName(name);
        vm.setDisplayName(name);
        vm.setProvider(CloudProvider.AWS);
        vm.setRegion("us-east-1");
        vm.setProviderVmId("i-" + UUID.randomUUID().toString().substring(0, 8));
        vm.setVmType(VmType.DEV);
        vm.setSequencePosition(sequence);
        vm.setDependencies(dependsOn);
        vm.setStatus(VmStatus.UNKNOWN);
        return vmRepository.save(vm);
    }
}

