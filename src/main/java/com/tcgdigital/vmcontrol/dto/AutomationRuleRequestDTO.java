package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessGrantMode;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Request body for creating or updating an AutomationRule.
 * Reused for both create and update — environmentId is treated as immutable on update.
 */
public class AutomationRuleRequestDTO {

    @NotBlank(message = "Environment is required")
    private String environmentId;

    @NotBlank(message = "Name is required")
    private String name;

    private String description;

    @NotNull(message = "Scope type is required")
    private AutomationScopeType scopeType;

    /** Group ID or VM ID, required when scopeType is GROUP or VM. */
    private String scopeId;

    @NotNull(message = "Trigger type is required")
    private AutomationTriggerType triggerType;

    /** Schedule only: three-letter day abbreviations, e.g. ["MON","TUE","WED"]. */
    private List<String> daysOfWeek;

    /** Schedule only: "HH:mm". At least one of stopTime/startTime is required. */
    private String stopTime;

    private String startTime;

    /** Schedule only: IANA zone id, e.g. "Asia/Kolkata". */
    private String timezone;

    /** Access-grant only. */
    private AccessGrantMode accessGrantMode;

    private boolean skipIfAlreadyInTargetState = true;

    private boolean enabled = true;

    public AutomationRuleRequestDTO() {
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public AutomationScopeType getScopeType() {
        return scopeType;
    }

    public void setScopeType(AutomationScopeType scopeType) {
        this.scopeType = scopeType;
    }

    public String getScopeId() {
        return scopeId;
    }

    public void setScopeId(String scopeId) {
        this.scopeId = scopeId;
    }

    public AutomationTriggerType getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(AutomationTriggerType triggerType) {
        this.triggerType = triggerType;
    }

    public List<String> getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(List<String> daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    public String getStopTime() {
        return stopTime;
    }

    public void setStopTime(String stopTime) {
        this.stopTime = stopTime;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public AccessGrantMode getAccessGrantMode() {
        return accessGrantMode;
    }

    public void setAccessGrantMode(AccessGrantMode accessGrantMode) {
        this.accessGrantMode = accessGrantMode;
    }

    public boolean isSkipIfAlreadyInTargetState() {
        return skipIfAlreadyInTargetState;
    }

    public void setSkipIfAlreadyInTargetState(boolean skipIfAlreadyInTargetState) {
        this.skipIfAlreadyInTargetState = skipIfAlreadyInTargetState;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
