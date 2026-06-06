package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.UpdateEnvironmentDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for Environment management operations.
 */
@Service
public class EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentService.class);

    private final EnvironmentRepository environmentRepository;
    private final EnvironmentAccessRepository accessRepository;
    private final VmGroupRepository groupRepository;
    private final VmRepository vmRepository;
    private final AuditService auditService;
    private final UserService userService;

    public EnvironmentService(EnvironmentRepository environmentRepository,
                              EnvironmentAccessRepository accessRepository,
                              VmGroupRepository groupRepository,
                              VmRepository vmRepository,
                              AuditService auditService,
                              UserService userService) {
        this.environmentRepository = environmentRepository;
        this.accessRepository = accessRepository;
        this.groupRepository = groupRepository;
        this.vmRepository = vmRepository;
        this.auditService = auditService;
        this.userService = userService;
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
     * Get environments the current user has access to.
     * Returns only active environments where the user has explicit access grant.
     */
    public List<Environment> getEnvironmentsForCurrentUser() {
        String userId = userService.getCurrentUserId();
        Timestamp now = new Timestamp(System.currentTimeMillis());

        List<EnvironmentAccess> accessList = accessRepository.findActiveAccessByUser(userId, now);

        return accessList.stream()
                .map(EnvironmentAccess::getEnvironment)
                .filter(env -> env.getIsActive())
                .distinct()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    /**
     * Get active environments the current user does NOT have access to.
     * Used for the "Request Access" feature - shows environments users can request access to.
     */
    public List<Environment> getEnvironmentsWithoutAccessForCurrentUser() {
        String userId = userService.getCurrentUserId();
        Timestamp now = new Timestamp(System.currentTimeMillis());

        // Get all active environments
        List<Environment> allActive = getAllActiveEnvironments();

        // Get environment IDs user already has access to
        List<EnvironmentAccess> accessList = accessRepository.findActiveAccessByUser(userId, now);
        Set<String> accessedEnvIds = accessList.stream()
                .map(ea -> ea.getEnvironment().getEnvironmentId())
                .collect(Collectors.toSet());

        // Return environments user does NOT have access to
        return allActive.stream()
                .filter(env -> !accessedEnvIds.contains(env.getEnvironmentId()))
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
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

        // Audit logging
        auditService.logEnvironmentCreated("system", saved.getEnvironmentId(), saved.getName());

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
