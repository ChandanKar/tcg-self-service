package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AccessGrantMode;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for AutomationRule entity operations.
 */
@Repository
public interface AutomationRuleRepository extends JpaRepository<AutomationRule, String> {

    /**
     * All rules for an environment (list/manage view).
     */
    List<AutomationRule> findByEnvironment_EnvironmentIdOrderByCreatedAtDesc(String environmentId);

    /**
     * All rules across every environment (list/manage view, no filter).
     */
    List<AutomationRule> findAllByOrderByCreatedAtDesc();

    /**
     * Enabled SCHEDULE rules — evaluated every tick by the scheduler.
     */
    List<AutomationRule> findByEnabledTrueAndTriggerType(AutomationTriggerType triggerType);

    /**
     * Enabled ACCESS_GRANT rules for a specific environment and sub-mode —
     * used by both the lock-acquire and access-approved hooks.
     */
    List<AutomationRule> findByEnabledTrueAndTriggerTypeAndAccessGrantModeAndEnvironment_EnvironmentId(
            AutomationTriggerType triggerType, AccessGrantMode accessGrantMode, String environmentId);
}
