package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.SpotPolicy;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spot opt-in per environment (E31-T03): an environment's administrators may allow Spot for an
 * interruptible, non-production environment, and exclude single VMs. Nothing happens unless
 * {@code spot.enabled}; every change is audited.
 */
@Service
public class SpotPolicyService {

    private final EnvironmentRepository environmentRepository;
    private final VmRepository vmRepository;
    private final SecurityService securityService;
    private final AuditService auditService;

    @Value("${spot.enabled:false}")
    private boolean enabled;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    public SpotPolicyService(EnvironmentRepository environmentRepository, VmRepository vmRepository,
                             SecurityService securityService, AuditService auditService) {
        this.environmentRepository = environmentRepository;
        this.vmRepository = vmRepository;
        this.securityService = securityService;
        this.auditService = auditService;
    }

    public boolean isEnabled() {
        return settings == null ? enabled : settings.overrideOr("spot.enabled", enabled);
    }

    private void requireEnabled() {
        if (!isEnabled()) {
            throw new ValidationException("Spot is disabled (spot.enabled)");
        }
    }

    /**
     * Allow or stop Spot in an environment. Allowing needs the caller to acknowledge that Spot
     * capacity can be interrupted, and is refused for a production environment.
     */
    @Transactional
    public Environment setPolicy(String environmentId, SpotPolicy policy, boolean acknowledgeInterruptible, String userId) {
        requireEnabled();
        securityService.assertCanAdminister(environmentId);
        if (policy == null) {
            throw new ValidationException("policy must be NONE or ALLOWED");
        }
        Environment env = environmentRepository.findById(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment", environmentId));
        if (policy == SpotPolicy.ALLOWED) {
            if (Boolean.TRUE.equals(env.getIsProduction())) {
                throw new ValidationException("Production environments cannot use Spot");
            }
            if (!acknowledgeInterruptible) {
                throw new ValidationException("Confirm that this environment can be interrupted (acknowledgeInterruptible)");
            }
        }
        SpotPolicy before = env.getSpotPolicy();
        if (before != policy) {
            env.setSpotPolicy(policy);
            environmentRepository.save(env);
            auditService.logEnvironmentAction(userId, AuditAction.SPOT_POLICY_CHANGED, environmentId, env.getDisplayName(),
                    "environment", environmentId, env.getDisplayName(), "Spot policy " + before + " -> " + policy);
        }
        return env;
    }

    /** Keep one VM (or node group) of the environment off Spot, or allow it again. */
    @Transactional
    public Vm setVmExcluded(String environmentId, String vmId, boolean excluded, String userId) {
        requireEnabled();
        securityService.assertCanAdminister(environmentId);
        Vm vm = vmRepository.findById(vmId).orElseThrow(() -> new ResourceNotFoundException("VM", vmId));
        Environment env = vm.getGroup().getEnvironment();
        securityService.assertSameEnvironment(env.getEnvironmentId(), environmentId);
        if (vm.isSpotExcluded() != excluded) {
            vm.setSpotExcluded(excluded);
            vmRepository.save(vm);
            auditService.logEnvironmentAction(userId, AuditAction.SPOT_POLICY_CHANGED, environmentId, env.getDisplayName(),
                    "vm", vmId, vm.getDisplayName(), excluded ? "Excluded from Spot" : "Allowed on Spot again");
        }
        return vm;
    }
}
