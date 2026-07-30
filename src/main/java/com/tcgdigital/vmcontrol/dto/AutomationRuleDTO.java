package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessGrantMode;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationRunStatus;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;

/**
 * Response shape for an AutomationRule, with resolved display names so the
 * frontend never has to make follow-up lookups.
 */
public class AutomationRuleDTO {

    private String ruleId;
    private String name;
    private String description;
    private String environmentId;
    private String environmentName;
    private AutomationScopeType scopeType;
    private String scopeId;
    private String scopeName;
    private AutomationTriggerType triggerType;
    private List<String> daysOfWeek;
    private String stopTime;
    private String startTime;
    private String timezone;
    private AccessGrantMode accessGrantMode;
    private boolean skipIfAlreadyInTargetState;
    private boolean enabled;
    private String createdByUserId;
    private String createdByDisplayName;
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Timestamp lastRunAt;
    private AutomationRunStatus lastRunStatus;
    private String lastRunDetail;

    public static AutomationRuleDTO fromEntity(AutomationRule rule, String environmentName,
                                               String scopeName, String createdByDisplayName) {
        AutomationRuleDTO dto = new AutomationRuleDTO();
        dto.ruleId = rule.getRuleId();
        dto.name = rule.getName();
        dto.description = rule.getDescription();
        dto.environmentId = rule.getEnvironment().getEnvironmentId();
        dto.environmentName = environmentName;
        dto.scopeType = rule.getScopeType();
        dto.scopeId = rule.getScopeId();
        dto.scopeName = scopeName;
        dto.triggerType = rule.getTriggerType();
        dto.daysOfWeek = rule.getDaysOfWeek() == null || rule.getDaysOfWeek().isBlank()
                ? List.of() : Arrays.asList(rule.getDaysOfWeek().split(","));
        dto.stopTime = rule.getStopTime();
        dto.startTime = rule.getStartTime();
        dto.timezone = rule.getTimezone();
        dto.accessGrantMode = rule.getAccessGrantMode();
        dto.skipIfAlreadyInTargetState = Boolean.TRUE.equals(rule.getSkipIfAlreadyInTargetState());
        dto.enabled = Boolean.TRUE.equals(rule.getEnabled());
        dto.createdByUserId = rule.getCreatedByUserId();
        dto.createdByDisplayName = createdByDisplayName;
        dto.createdAt = rule.getCreatedAt();
        dto.updatedAt = rule.getUpdatedAt();
        dto.lastRunAt = rule.getLastRunAt();
        dto.lastRunStatus = rule.getLastRunStatus();
        dto.lastRunDetail = rule.getLastRunDetail();
        return dto;
    }

    public String getRuleId() {
        return ruleId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public String getEnvironmentName() {
        return environmentName;
    }

    public AutomationScopeType getScopeType() {
        return scopeType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public String getScopeName() {
        return scopeName;
    }

    public AutomationTriggerType getTriggerType() {
        return triggerType;
    }

    public List<String> getDaysOfWeek() {
        return daysOfWeek;
    }

    public String getStopTime() {
        return stopTime;
    }

    public String getStartTime() {
        return startTime;
    }

    public String getTimezone() {
        return timezone;
    }

    public AccessGrantMode getAccessGrantMode() {
        return accessGrantMode;
    }

    public boolean isSkipIfAlreadyInTargetState() {
        return skipIfAlreadyInTargetState;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public String getCreatedByDisplayName() {
        return createdByDisplayName;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public Timestamp getLastRunAt() {
        return lastRunAt;
    }

    public AutomationRunStatus getLastRunStatus() {
        return lastRunStatus;
    }

    public String getLastRunDetail() {
        return lastRunDetail;
    }
}
