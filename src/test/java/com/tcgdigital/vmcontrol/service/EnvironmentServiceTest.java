package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.UpdateEnvironmentDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class EnvironmentServiceTest {

    @Autowired
    private EnvironmentService environmentService;

    @Autowired
    private EnvironmentRepository environmentRepository;

    @Autowired
    private VmGroupRepository groupRepository;

    @Autowired
    private VmRepository vmRepository;

    @BeforeEach
    void setUp() {
        // Clean up any existing test data
        vmRepository.deleteAll();
        groupRepository.deleteAll();
        environmentRepository.deleteAll();
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
}

