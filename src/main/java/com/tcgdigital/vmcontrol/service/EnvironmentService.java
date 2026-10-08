package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateEnvironmentDTO;
import com.tcgdigital.vmcontrol.dto.UpdateEnvironmentDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

        // A user can hold several grants in one environment (an ENVIRONMENT grant plus GROUP
        // grants) — collapse to one entry per environment.
        java.util.Map<String, Environment> byId = new java.util.LinkedHashMap<>();
        for (EnvironmentAccess ea : accessList) {
            Environment env = ea.getEnvironment();
            if (Boolean.TRUE.equals(env.getIsActive())) {
                byId.putIfAbsent(env.getEnvironmentId(), env);
            }
        }
        return byId.values().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .toList();
    }

    /**
     * Paginated, optionally name/description-filtered active environments — backs the
     * server-side-paginated "My Environments" table (admin view).
     */
    public Page<Environment> getAllActiveEnvironments(String search, Pageable pageable) {
        return environmentRepository.searchActive(blankToNull(search), pageable);
    }

    /**
     * Paginated, optionally name/description-filtered environments including inactive ones —
     * the admin "include inactive" variant.
     */
    public Page<Environment> getAllEnvironments(String search, Pageable pageable) {
        return environmentRepository.searchAll(blankToNull(search), pageable);
    }

    /**
     * Paginated, optionally name/description-filtered environments the current user has access
     * to — backs the server-side-paginated "My Environments" table (regular-user view).
     */
    public Page<Environment> getEnvironmentsForCurrentUser(String search, Pageable pageable) {
        String userId = userService.getCurrentUserId();
        Timestamp now = new Timestamp(System.currentTimeMillis());
        // DISTINCT environments — a user with an ENVIRONMENT grant plus GROUP grants in the
        // same environment must still see it once, not once per grant.
        return accessRepository.findDistinctActiveEnvironmentsForUser(userId, blankToNull(search), now, pageable);
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
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
        // Validate name uniqueness on the name as stored (M3)
        String name = com.tcgdigital.vmcontrol.service.support.NameNormalizer.slug(dto.getName());
        if (environmentRepository.existsByName(name)) {
            throw new ValidationException("Environment with name '" + dto.getName() + "' already exists");
        }
        String serviceType = dto.getServiceType() != null ? dto.getServiceType() : "EC2";
        String clusterName = null;
        if ("EKS".equalsIgnoreCase(serviceType)) {
            // AWS cluster names are case-sensitive: keep the exact one for every EKS call.
            clusterName = (dto.getEksClusterName() != null && !dto.getEksClusterName().isBlank()
                    ? dto.getEksClusterName() : dto.getName()).trim();
            if (environmentRepository.existsByEksClusterName(clusterName)) {
                throw new ValidationException("EKS cluster '" + clusterName + "' is already registered");
            }
        }

        Environment environment = new Environment();
        environment.setEnvironmentId(UUID.randomUUID().toString());
        environment.setName(name);
        environment.setEksClusterName(clusterName);
        environment.setDisplayName(dto.getDisplayName());
        environment.setDescription(dto.getDescription());
        environment.setMetadata(dto.getMetadata());
        environment.setServiceType(serviceType);
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
            // An emptied field clears the description (null means "not sent").
            environment.setDescription(dto.getDescription().isBlank() ? null : dto.getDescription().trim());
        }
        if (dto.getIsActive() != null) {
            environment.setIsActive(dto.getIsActive());
        }
        if (dto.getMetadata() != null) {
            // A patch over the stored metadata: keys the form does not know (an EKS region) stay (M4).
            environment.setMetadata(com.tcgdigital.vmcontrol.service.support.JsonMetadataMerger.merge(
                    environment.getMetadata(), dto.getMetadata()));
        }
        if (dto.getServiceType() != null && !dto.getServiceType().equalsIgnoreCase(environment.getServiceType())) {
            if (!groupRepository.findByEnvironmentEnvironmentIdOrderBySequencePositionAsc(environmentId).isEmpty()) {
                throw new ValidationException("The service type cannot be changed while the environment has groups");
            }
            environment.setServiceType(dto.getServiceType());
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
        auditService.logEnvironmentAction("system", AuditAction.ENVIRONMENT_DEACTIVATED, environmentId,
                environment.getName(), "environment", environmentId, environment.getName(), null);
    }

    /**
     * Reactivate a previously deactivated environment.
     */
    @Transactional
    public Environment reactivateEnvironment(String environmentId) {
        Environment environment = getEnvironmentById(environmentId);
        environment.setIsActive(true);
        Environment saved = environmentRepository.save(environment);
        log.info("Reactivated environment: {} ({})", saved.getName(), environmentId);
        auditService.logEnvironmentAction("system", AuditAction.ENVIRONMENT_ACTIVATED, environmentId,
                saved.getName(), "environment", environmentId, saved.getName(), null);
        return saved;
    }

    /**
     * Group/VM/running counts for a set of environments, computed with exactly two queries
     * regardless of how many environments are requested — used by listing endpoints so they
     * don't issue a pair of count queries per environment.
     */
    public Map<String, EnvironmentCounts> getBatchCounts(List<String> environmentIds) {
        Map<String, EnvironmentCounts> result = new HashMap<>();
        for (String id : environmentIds) {
            result.put(id, new EnvironmentCounts(0, 0, 0, List.of()));
        }

        for (VmGroupRepository.EnvironmentGroupCounts gc : groupRepository.countGroupsGroupedByEnvironment(environmentIds)) {
            result.merge(gc.getEnvironmentId(), new EnvironmentCounts((int) gc.getTotal(), 0, 0, List.of()), EnvironmentCounts::mergeGroupCount);
        }

        for (VmRepository.EnvironmentVmCounts vc : vmRepository.countVmsGroupedByEnvironment(environmentIds, VmStatus.RUNNING)) {
            result.merge(vc.getEnvironmentId(), new EnvironmentCounts(0, (int) vc.getTotal(), (int) vc.getRunning(), List.of()), EnvironmentCounts::mergeVmCounts);
        }

        // Distinct regions in use, derived from the environment's own VMs — works the same way
        // for EC2 and EKS, since an EKS node group's Vm carries its cluster's region too.
        Map<String, List<String>> regionsByEnvironmentId = vmRepository.findDistinctRegionsGroupedByEnvironment(environmentIds).stream()
                .collect(Collectors.groupingBy(VmRepository.EnvironmentRegion::getEnvironmentId,
                        Collectors.mapping(VmRepository.EnvironmentRegion::getRegion, Collectors.toList())));
        regionsByEnvironmentId.forEach((environmentId, regions) -> {
            List<String> sortedRegions = regions.stream().sorted().toList();
            result.merge(environmentId, new EnvironmentCounts(0, 0, 0, sortedRegions), EnvironmentCounts::mergeRegions);
        });

        return result;
    }

    public record EnvironmentCounts(int groupCount, int vmCount, int runningVmCount, List<String> regions) {
        private static EnvironmentCounts mergeGroupCount(EnvironmentCounts existing, EnvironmentCounts groupUpdate) {
            return new EnvironmentCounts(groupUpdate.groupCount(), existing.vmCount(), existing.runningVmCount(), existing.regions());
        }

        private static EnvironmentCounts mergeVmCounts(EnvironmentCounts existing, EnvironmentCounts vmUpdate) {
            return new EnvironmentCounts(existing.groupCount(), vmUpdate.vmCount(), vmUpdate.runningVmCount(), existing.regions());
        }

        private static EnvironmentCounts mergeRegions(EnvironmentCounts existing, EnvironmentCounts regionsUpdate) {
            return new EnvironmentCounts(existing.groupCount(), existing.vmCount(), existing.runningVmCount(), regionsUpdate.regions());
        }
    }
}
