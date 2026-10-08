package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AccessGrantMode;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

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

    /** One rule with its environment, for evaluation outside a web request (C6). */
    @Query("SELECT r FROM AutomationRule r JOIN FETCH r.environment WHERE r.ruleId = :id")
    Optional<AutomationRule> findByIdFetchEnvironment(@Param("id") String id);

    /** Ids of enabled rules of one trigger type; each is then evaluated in its own transaction. */
    @Query("SELECT r.ruleId FROM AutomationRule r WHERE r.enabled = true AND r.triggerType = :type")
    List<String> findEnabledIdsByTriggerType(@Param("type") AutomationTriggerType type);

    /** Enabled ACCESS_GRANT rules of an environment and mode, environment fetched (hooks). */
    @Query("SELECT r FROM AutomationRule r JOIN FETCH r.environment " +
           "WHERE r.enabled = true AND r.triggerType = :type AND r.accessGrantMode = :mode " +
           "AND r.environment.environmentId = :environmentId")
    List<AutomationRule> findEnabledAccessGrantRulesFetchEnvironment(@Param("type") AutomationTriggerType type,
                                                                     @Param("mode") AccessGrantMode mode,
                                                                     @Param("environmentId") String environmentId);

    /** All rules with their environments in one query (no lazy load per rule in the list). */
    @Query("SELECT r FROM AutomationRule r JOIN FETCH r.environment ORDER BY r.createdAt DESC")
    List<AutomationRule> findAllFetchEnvironment();

    @Query("SELECT r FROM AutomationRule r JOIN FETCH r.environment " +
           "WHERE r.environment.environmentId = :environmentId ORDER BY r.createdAt DESC")
    List<AutomationRule> findByEnvironmentFetchEnvironment(@Param("environmentId") String environmentId);

    /** Rules targeting one group or VM (group delete disables them, M2). */
    List<AutomationRule> findByScopeTypeAndScopeId(com.tcgdigital.vmcontrol.model.AutomationScopeType scopeType,
                                                   String scopeId);

    /** Enabled schedule rules of many environments in one query (next stop/start in lists, E18). */
    @Query("SELECT r FROM AutomationRule r JOIN FETCH r.environment e WHERE e.environmentId IN :ids " +
           "AND r.enabled = true AND r.triggerType = com.tcgdigital.vmcontrol.model.AutomationTriggerType.SCHEDULE")
    List<AutomationRule> findEnabledSchedulesForEnvironments(@Param("ids") List<String> environmentIds);
}
