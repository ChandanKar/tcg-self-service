package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * AutomationRule — a calendar-schedule or access-grant/lock-acquire trigger that
 * automatically starts or stops an environment (optionally narrowed to a group or VM).
 */
@Entity
@Table(name = "automation_rule")
public class AutomationRule {

    @Id
    @Column(name = "rule_id", length = 36)
    private String ruleId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "environment_id", nullable = false)
    private Environment environment;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private AutomationScopeType scopeType;

    @Column(name = "scope_id", length = 36)
    private String scopeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    private AutomationTriggerType triggerType;

    /**
     * Schedule only: the rule's time windows in order (E19-T01). The old single-window columns on
     * automation_rule are no longer mapped.
     */
    @OneToMany(mappedBy = "rule", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @org.hibernate.annotations.BatchSize(size = 50)
    private List<AutomationRuleWindow> windows = new ArrayList<>();

    @Column(name = "timezone", length = 64)
    private String timezone;

    /** Schedule only: the holiday calendar whose dates the rule skips (E19-T02); null for none. */
    @Column(name = "holiday_calendar_id", length = 36)
    private String holidayCalendarId;

    @Enumerated(EnumType.STRING)
    @Column(name = "holiday_behavior", nullable = false, length = 20)
    private HolidayBehavior holidayBehavior = HolidayBehavior.SKIP_START;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_grant_mode", length = 20)
    private AccessGrantMode accessGrantMode;

    @Column(name = "skip_if_already_in_target_state", nullable = false)
    private Boolean skipIfAlreadyInTargetState = true;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_by_user_id", nullable = false, length = 36)
    private String createdByUserId;

    @Column(name = "created_at", nullable = false)
    @CreationTimestamp
    private Timestamp createdAt;

    @Column(name = "updated_at", nullable = false)
    @UpdateTimestamp
    private Timestamp updatedAt;

    @Column(name = "last_run_at")
    private Timestamp lastRunAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_status", length = 20)
    private AutomationRunStatus lastRunStatus;

    @Column(name = "last_run_detail", length = 500)
    private String lastRunDetail;

    /** Why the last run ended as it did (V31). */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_run_reason", length = 30)
    private AutomationRunReason lastRunReason;

    /** Set when the system disabled the rule (V31); cleared on re-enable. */
    @Column(name = "disabled_reason", length = 255)
    private String disabledReason;

    public AutomationRule() {
    }

    public boolean isScheduleRule() {
        return triggerType == AutomationTriggerType.SCHEDULE;
    }

    public boolean isAccessGrantRule() {
        return triggerType == AutomationTriggerType.ACCESS_GRANT;
    }

    /** Appends a window ("MON,TUE", "08:00" or null, "20:00" or null) and returns it. */
    public AutomationRuleWindow addWindow(String daysOfWeek, String startTime, String stopTime) {
        AutomationRuleWindow window = new AutomationRuleWindow();
        window.setWindowId(java.util.UUID.randomUUID().toString());
        window.setRule(this);
        window.setPosition(windows.size());
        window.setDaysOfWeek(daysOfWeek);
        window.setStartTime(startTime);
        window.setStopTime(stopTime);
        windows.add(window);
        return window;
    }

    // Getters and Setters

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
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

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
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

    public List<AutomationRuleWindow> getWindows() {
        return windows;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getHolidayCalendarId() {
        return holidayCalendarId;
    }

    public void setHolidayCalendarId(String holidayCalendarId) {
        this.holidayCalendarId = holidayCalendarId;
    }

    public HolidayBehavior getHolidayBehavior() {
        return holidayBehavior;
    }

    public void setHolidayBehavior(HolidayBehavior holidayBehavior) {
        this.holidayBehavior = holidayBehavior;
    }

    public AccessGrantMode getAccessGrantMode() {
        return accessGrantMode;
    }

    public void setAccessGrantMode(AccessGrantMode accessGrantMode) {
        this.accessGrantMode = accessGrantMode;
    }

    public Boolean getSkipIfAlreadyInTargetState() {
        return skipIfAlreadyInTargetState;
    }

    public void setSkipIfAlreadyInTargetState(Boolean skipIfAlreadyInTargetState) {
        this.skipIfAlreadyInTargetState = skipIfAlreadyInTargetState;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getCreatedByUserId() {
        return createdByUserId;
    }

    public void setCreatedByUserId(String createdByUserId) {
        this.createdByUserId = createdByUserId;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Timestamp getLastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Timestamp lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public AutomationRunStatus getLastRunStatus() {
        return lastRunStatus;
    }

    public void setLastRunStatus(AutomationRunStatus lastRunStatus) {
        this.lastRunStatus = lastRunStatus;
    }

    public String getLastRunDetail() {
        return lastRunDetail;
    }

    public void setLastRunDetail(String lastRunDetail) {
        this.lastRunDetail = lastRunDetail;
    }

    public AutomationRunReason getLastRunReason() {
        return lastRunReason;
    }

    public void setLastRunReason(AutomationRunReason lastRunReason) {
        this.lastRunReason = lastRunReason;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    public void setDisabledReason(String disabledReason) {
        this.disabledReason = disabledReason;
    }
}
