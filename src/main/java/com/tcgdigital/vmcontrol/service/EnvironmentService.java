package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.UpdateEnvironmentDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for Environment management operations.
 */
@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private final EnvironmentRepository environmentRepository;
    private final VmGroupRepository groupRepository;
    private final VmRepository vmRepository;

    public EnvironmentService(EnvironmentRepository environmentRepository,
                              VmGroupRepository groupRepository,
                              VmRepository vmRepository) {
        this.environmentRepository = environmentRepository;
        this.groupRepository = groupRepository;
        this.vmRepository = vmRepository;
    }

    /**
     * Get all active environments.
     */
    public List<Environment> getAllActiveEnvironments() {
        return environmentRepository.findByIsActiveTrueOrderByNameAsc();
    }

    /**
     * Get all environments (including inactive).
     */
    public List<Environment> getAllEnvironments() {
        return environmentRepository.findAll();
    }

    /**
     * Get environment by ID.
     */
    public Environment getEnvironmentById(String environmentId) {
        return environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
    }

    /**
     * Get environment with groups eagerly loaded.
     */
    public Environment getEnvironmentWithGroups(String environmentId) {
        return environmentRepository.findByIdWithGroups(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
    }

    /**
     * Create a new environment.
     */
    @Transactional
    public Environment createEnvironment(CreateEnvironmentDTO dto) {
        // Validate name uniqueness
        if (environmentRepository.existsByName(dto.getName())) {
            throw new ValidationException("Environment with name '" + dto.getName() + "' already exists");
        }

        Environment environment = new Environment();
        environment.setEnvironmentId(UUID.randomUUID().toString());
        environment.setName(dto.getName().toLowerCase().replaceAll("\\s+", "-"));
        environment.setDisplayName(dto.getDisplayName());
        environment.setDescription(dto.getDescription());
        environment.setMetadata(dto.getMetadata());
        environment.setIsActive(true);

        Environment saved = environmentRepository.save(environment);
        log.info("Created environment: {} ({})", saved.getName(), saved.getEnvironmentId());

        return saved;
    }

    /**
     * Update an existing environment.
     */
    @Transactional
    public Environment updateEnvironment(String environmentId, UpdateEnvironmentDTO dto) {
        Environment environment = getEnvironmentById(environmentId);

        if (dto.getDisplayName() != null) {
            environment.setDisplayName(dto.getDisplayName());
        }
        if (dto.getDescription() != null) {
            environment.setDescription(dto.getDescription());
        }
        if (dto.getIsActive() != null) {
            environment.setIsActive(dto.getIsActive());
        }
        if (dto.getMetadata() != null) {
            environment.setMetadata(dto.getMetadata());
        }

        Environment saved = environmentRepository.save(environment);
        log.info("Updated environment: {} ({})", saved.getName(), saved.getEnvironmentId());

        return saved;
    }

    /**
     * Deactivate an environment (soft delete).
     */
    @Transactional
    public void deactivateEnvironment(String environmentId) {
        Environment environment = getEnvironmentById(environmentId);
        environment.setIsActive(false);
        environmentRepository.save(environment);
        log.info("Deactivated environment: {} ({})", environment.getName(), environmentId);
    }

    /**
     * Get group count for an environment.
     */
    public int getGroupCount(String environmentId) {
        return (int) groupRepository.countByEnvironmentEnvironmentId(environmentId);
    }

    /**
     * Get VM count for an environment.
     */
    public int getVmCount(String environmentId) {
        return vmRepository.findByEnvironmentId(environmentId).size();
    }
}

