package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.GrantAccessDTO;
import com.tcgdigital.vmcontrol.dto.UpdateEnvironmentDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.model.VmType;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Transactional
class EnvironmentServiceTest extends AbstractIntegrationTest {

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private VmGroupRepository groupRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private EnvironmentAccessRepository accessRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EnvironmentAccessService environmentAccessService;

    @BeforeEach
    void setUp() {
        // Clean up any existing test data
        accessRepository.deleteAll();
        vmRepository.deleteAll();
        groupRepository.deleteAll();
        environmentRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void testCreateEnvironment_Success() {
        // Given
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("test-env");
        dto.setDisplayName("Test Environment");
        dto.setDescription("A test environment");

        // When
        Environment created = environmentService.createEnvironment(dto);

        // Then
        assertNotNull(created.getEnvironmentId());
        assertEquals("test-env", created.getName());
        assertEquals("Test Environment", created.getDisplayName());
        assertEquals("A test environment", created.getDescription());
        assertTrue(created.getIsActive());
        // Note: createdAt may be null until flush in transactional test
    }

    @Test
    void testCreateEnvironment_DuplicateName_ThrowsException() {
        // Given
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("duplicate-env");
        dto.setDisplayName("Duplicate Environment");
        environmentService.createEnvironment(dto);

        // When / Then
        CreateEnvironmentDTO duplicateDto = new CreateEnvironmentDTO();
        duplicateDto.setName("duplicate-env");
        duplicateDto.setDisplayName("Another Environment");

        assertThrows(ValidationException.class, () -> {
            environmentService.createEnvironment(duplicateDto);
        });
    }

    // ---- E10-T02 (M3): duplicates checked on the stored name; EKS keeps the exact cluster name ----

    private static CreateEnvironmentDTO env(String name, String serviceType) {
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName(name);
        dto.setDisplayName(name);
        dto.setServiceType(serviceType);
        return dto;
    }

    @Test
    void aNameThatNormalisesToAnExistingOneIs400Not500() {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        environmentService.createEnvironment(env("my-env-" + suffix, "EC2"));

        ValidationException e = assertThrows(ValidationException.class,
                () -> environmentService.createEnvironment(env(" My Env-" + suffix.toUpperCase() + " ", "EC2")));
        assertTrue(e.getMessage().contains("already exists"));
    }

    @Test
    void anEksEnvironmentKeepsTheExactClusterName() {
        String cluster = "MyCluster" + UUID.randomUUID().toString().substring(0, 6);

        Environment created = environmentService.createEnvironment(env(cluster, "EKS"));

        assertEquals(cluster.toLowerCase(), created.getName());
        assertEquals(cluster, created.getEksClusterName());
        assertEquals(cluster, created.getEffectiveClusterName());
        ValidationException e = assertThrows(ValidationException.class, () -> {
            CreateEnvironmentDTO again = env("other-" + UUID.randomUUID().toString().substring(0, 6), "EKS");
            again.setEksClusterName(cluster);
            environmentService.createEnvironment(again);
        });
        assertTrue(e.getMessage().contains("already registered"));
    }

    @Test
    void anEc2EnvironmentHasNoClusterName() {
        Environment created = environmentService.createEnvironment(env("ec2-" + UUID.randomUUID().toString().substring(0, 6), "EC2"));

        assertNull(created.getEksClusterName());
    }

    @Test
    void testGetEnvironmentById_Found() {
        // Given
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("findable-env");
        dto.setDisplayName("Findable Environment");
        Environment created = environmentService.createEnvironment(dto);

        // When
        Environment found = environmentService.getEnvironmentById(created.getEnvironmentId());

        // Then
        assertEquals(created.getEnvironmentId(), found.getEnvironmentId());
        assertEquals("findable-env", found.getName());
    }

    @Test
    void testGetEnvironmentById_NotFound_ThrowsException() {
        // When / Then
        assertThrows(ResourceNotFoundException.class, () -> {
            environmentService.getEnvironmentById("non-existent-id");
        });
    }

    @Test
    void testUpdateEnvironment_Success() {
        // Given
        CreateEnvironmentDTO createDto = new CreateEnvironmentDTO();
        createDto.setName("update-env");
        createDto.setDisplayName("Original Name");
        Environment created = environmentService.createEnvironment(createDto);

        // When
        UpdateEnvironmentDTO updateDto = new UpdateEnvironmentDTO();
        updateDto.setDisplayName("Updated Name");
        updateDto.setDescription("Updated Description");
        Environment updated = environmentService.updateEnvironment(created.getEnvironmentId(), updateDto);

        // Then
        assertEquals("Updated Name", updated.getDisplayName());
        assertEquals("Updated Description", updated.getDescription());
        assertEquals("update-env", updated.getName()); // Name should not change
    }

    @Test
    void testDeactivateEnvironment_Success() {
        // Given
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("deactivate-env");
        dto.setDisplayName("Deactivate Environment");
        Environment created = environmentService.createEnvironment(dto);
        assertTrue(created.getIsActive());

        // When
        environmentService.deactivateEnvironment(created.getEnvironmentId());

        // Then
        Environment found = environmentService.getEnvironmentById(created.getEnvironmentId());
        assertFalse(found.getIsActive());
    }

    @Test
    void testGetAllActiveEnvironments() {
        // Given
        CreateEnvironmentDTO dto1 = new CreateEnvironmentDTO();
        dto1.setName("active-env-1");
        dto1.setDisplayName("Active Environment 1");
        environmentService.createEnvironment(dto1);

        CreateEnvironmentDTO dto2 = new CreateEnvironmentDTO();
        dto2.setName("active-env-2");
        dto2.setDisplayName("Active Environment 2");
        Environment env2 = environmentService.createEnvironment(dto2);
        environmentService.deactivateEnvironment(env2.getEnvironmentId());

        // When
        List<Environment> activeEnvs = environmentService.getAllActiveEnvironments();

        // Then
        assertEquals(1, activeEnvs.size());
        assertEquals("active-env-1", activeEnvs.get(0).getName());
    }

    @Test
    void testGetAllActiveEnvironmentsPaged_noSearch_returnsAllPaginated() {
        for (int i = 1; i <= 3; i++) {
            CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
            dto.setName("paged-env-" + i);
            dto.setDisplayName("Paged Environment " + i);
            environmentService.createEnvironment(dto);
        }

        Page<Environment> firstPage = environmentService.getAllActiveEnvironments(null, PageRequest.of(0, 2));

        assertEquals(3, firstPage.getTotalElements());
        assertEquals(2, firstPage.getTotalPages());
        assertEquals(2, firstPage.getContent().size());
    }

    @Test
    void testGetAllActiveEnvironmentsPaged_withSearch_filtersByNameOrDescription() {
        CreateEnvironmentDTO match = new CreateEnvironmentDTO();
        match.setName("production-cluster");
        match.setDisplayName("Production Cluster");
        environmentService.createEnvironment(match);

        CreateEnvironmentDTO descMatch = new CreateEnvironmentDTO();
        descMatch.setName("other-env");
        descMatch.setDisplayName("Other Environment");
        descMatch.setDescription("runs the production workload");
        environmentService.createEnvironment(descMatch);

        CreateEnvironmentDTO noMatch = new CreateEnvironmentDTO();
        noMatch.setName("staging-env");
        noMatch.setDisplayName("Staging Environment");
        environmentService.createEnvironment(noMatch);

        Page<Environment> results = environmentService.getAllActiveEnvironments("production", PageRequest.of(0, 10));

        assertEquals(2, results.getTotalElements());
        assertTrue(results.getContent().stream().anyMatch(e -> e.getName().equals("production-cluster")));
        assertTrue(results.getContent().stream().anyMatch(e -> e.getName().equals("other-env")));
    }

    @Test
    void testGetAllActiveEnvironmentsPaged_blankSearch_behavesAsNoFilter() {
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("blank-search-env");
        dto.setDisplayName("Blank Search Environment");
        environmentService.createEnvironment(dto);

        Page<Environment> results = environmentService.getAllActiveEnvironments("   ", PageRequest.of(0, 10));

        assertEquals(1, results.getTotalElements());
    }

    @Test
    void testGetAllEnvironmentsPaged_includesInactive() {
        CreateEnvironmentDTO activeDto = new CreateEnvironmentDTO();
        activeDto.setName("active-for-paged");
        activeDto.setDisplayName("Active For Paged");
        environmentService.createEnvironment(activeDto);

        CreateEnvironmentDTO inactiveDto = new CreateEnvironmentDTO();
        inactiveDto.setName("inactive-for-paged");
        inactiveDto.setDisplayName("Inactive For Paged");
        Environment inactiveEnv = environmentService.createEnvironment(inactiveDto);
        environmentService.deactivateEnvironment(inactiveEnv.getEnvironmentId());

        Page<Environment> activeOnly = environmentService.getAllActiveEnvironments(null, PageRequest.of(0, 10));
        Page<Environment> all = environmentService.getAllEnvironments(null, PageRequest.of(0, 10));

        assertEquals(1, activeOnly.getTotalElements());
        assertEquals(2, all.getTotalElements());
    }

    @Test
    void testSearchActiveAccessByUser_scopedToUsersAccessOnly_withSearch() {
        // getEnvironmentsForCurrentUser(search, pageable) resolves the acting user via
        // UserService.getCurrentUserId() (Spring Security context) — not exercisable directly in
        // this repository-backed test without a mocked principal, so this targets the new
        // repository query itself (EnvironmentAccessRepository.searchActiveAccessByUser), which
        // is the actual new logic; full current-user resolution is covered by the live endpoint
        // verification instead (confirmed manually: admin sees all 169 seed environments across
        // 34 pages, a regular user with 2 grants sees exactly those 2, both with correct search
        // filtering including a genuine zero-match case).
        CreateEnvironmentDTO accessibleDto = new CreateEnvironmentDTO();
        accessibleDto.setName("user-accessible-prod");
        accessibleDto.setDisplayName("User Accessible Prod");
        Environment accessibleEnv = environmentService.createEnvironment(accessibleDto);

        CreateEnvironmentDTO inaccessibleDto = new CreateEnvironmentDTO();
        inaccessibleDto.setName("user-inaccessible-prod");
        inaccessibleDto.setDisplayName("User Inaccessible Prod");
        environmentService.createEnvironment(inaccessibleDto);

        User testUser = User.fromUsernamePassword("paged.test.user", "irrelevant",
                "paged.test.user@tcgdigital.com", "Paged Test User", "TCG");
        testUser = userRepository.save(testUser);

        User admin = User.fromUsernamePassword("paged.admin", "irrelevant",
                "paged.admin@tcgdigital.com", "Paged Admin", "TCG");
        admin.setAdmin(true);
        admin = userRepository.save(admin);

        GrantAccessDTO grantDto = new GrantAccessDTO(testUser.getEmail(), AccessLevel.USER, null, null);
        environmentAccessService.grantAccess(accessibleEnv.getEnvironmentId(), admin.getUserId(), grantDto);

        Page<com.tcgdigital.vmcontrol.model.EnvironmentAccess> results = accessRepository.searchActiveAccessByUser(
                testUser.getUserId(), "prod", new java.sql.Timestamp(System.currentTimeMillis()), PageRequest.of(0, 10));

        // Only the user's own accessible environment matches, even though a second "prod"-named
        // environment exists that they have no grant for.
        assertEquals(1, results.getTotalElements());
        assertEquals("user-accessible-prod", results.getContent().get(0).getEnvironment().getName());
    }

    @Test
    void testGetBatchCounts_derivesDistinctSortedRegionsFromVms() {
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("region-env");
        dto.setDisplayName("Region Environment");
        Environment env = environmentService.createEnvironment(dto);

        VmGroup group = buildGroup(env);
        // us-east-1 saved before ap-south-1, but regions come back alphabetically sorted;
        // a repeated region (vm3) must not produce a duplicate entry.
        vmRepository.save(buildVm(group, "vm1", 1, "us-east-1"));
        vmRepository.save(buildVm(group, "vm2", 2, "ap-south-1"));
        vmRepository.save(buildVm(group, "vm3", 3, "ap-south-1"));

        Map<String, EnvironmentService.EnvironmentCounts> counts =
                environmentService.getBatchCounts(List.of(env.getEnvironmentId()));

        assertEquals(List.of("ap-south-1", "us-east-1"), counts.get(env.getEnvironmentId()).regions());
    }

    @Test
    void testGetBatchCounts_excludesInactiveVmsAndEnvironmentsWithNoVms() {
        CreateEnvironmentDTO dto = new CreateEnvironmentDTO();
        dto.setName("no-region-env");
        dto.setDisplayName("No Region Environment");
        Environment env = environmentService.createEnvironment(dto);

        VmGroup group = buildGroup(env);
        Vm inactiveVm = buildVm(group, "inactive-vm", 1, "us-east-1");
        inactiveVm.setIsActive(false);
        vmRepository.save(inactiveVm);

        Map<String, EnvironmentService.EnvironmentCounts> counts =
                environmentService.getBatchCounts(List.of(env.getEnvironmentId()));

        assertEquals(List.of(), counts.get(env.getEnvironmentId()).regions());
    }

    private VmGroup buildGroup(Environment environment) {
        VmGroup group = new VmGroup();
        group.setGroupId(UUID.randomUUID().toString());
        group.setEnvironment(environment);
        group.setName("group-" + UUID.randomUUID().toString().substring(0, 8));
        group.setDisplayName("Group");
        group.setSequencePosition(1);
        return groupRepository.save(group);
    }

    private Vm buildVm(VmGroup group, String name, int sequence, String region) {
        Vm vm = new Vm();
        vm.setVmId(UUID.randomUUID().toString());
        vm.setGroup(group);
        vm.setName(name);
        vm.setDisplayName(name);
        vm.setProvider(CloudProvider.AWS);
        vm.setRegion(region);
        vm.setProviderVmId("i-" + UUID.randomUUID().toString().substring(0, 8));
        vm.setVmType(VmType.DEV);
        vm.setSequencePosition(sequence);
        vm.setStatus(VmStatus.RUNNING);
        vm.setIsActive(true);
        return vm;
    }
}

