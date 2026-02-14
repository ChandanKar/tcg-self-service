package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AuditAction;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * DTO for audit report response.
 */
public class AuditReportDTO {

    private LocalDate startDate;
    private LocalDate endDate;
    private long totalActions;
    private long successfulActions;
    private long failedActions;
    private Map<AuditAction, Long> actionCounts;
    private Map<String, Long> userActivityCounts;
    private List<EnvironmentActivityDTO> environmentActivities;
    private List<AuditLogDTO> recentLogs;

    public AuditReportDTO() {
    }

    // Getters and Setters
    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public long getTotalActions() {
        return totalActions;
    }

    public void setTotalActions(long totalActions) {
        this.totalActions = totalActions;
    }

    public long getSuccessfulActions() {
        return successfulActions;
    }

    public void setSuccessfulActions(long successfulActions) {
        this.successfulActions = successfulActions;
    }

    public long getFailedActions() {
        return failedActions;
    }

    public void setFailedActions(long failedActions) {
        this.failedActions = failedActions;
    }

    public Map<AuditAction, Long> getActionCounts() {
        return actionCounts;
    }

    public void setActionCounts(Map<AuditAction, Long> actionCounts) {
        this.actionCounts = actionCounts;
    }

    public Map<String, Long> getUserActivityCounts() {
        return userActivityCounts;
    }

    public void setUserActivityCounts(Map<String, Long> userActivityCounts) {
        this.userActivityCounts = userActivityCounts;
    }

    public List<EnvironmentActivityDTO> getEnvironmentActivities() {
        return environmentActivities;
    }

    public void setEnvironmentActivities(List<EnvironmentActivityDTO> environmentActivities) {
        this.environmentActivities = environmentActivities;
    }

    public List<AuditLogDTO> getRecentLogs() {
        return recentLogs;
    }

    public void setRecentLogs(List<AuditLogDTO> recentLogs) {
        this.recentLogs = recentLogs;
    }

    public static class EnvironmentActivityDTO {
        private String environmentId;
        private String environmentName;
        private long actionCount;

        public EnvironmentActivityDTO() {
        }

        public EnvironmentActivityDTO(String environmentId, String environmentName, long actionCount) {
            this.environmentId = environmentId;
            this.environmentName = environmentName;
            this.actionCount = actionCount;
        }

        public String getEnvironmentId() {
            return environmentId;
        }

        public void setEnvironmentId(String environmentId) {
            this.environmentId = environmentId;
        }

        public String getEnvironmentName() {
            return environmentName;
        }

        public void setEnvironmentName(String environmentName) {
            this.environmentName = environmentName;
        }

        public long getActionCount() {
            return actionCount;
        }

        public void setActionCount(long actionCount) {
            this.actionCount = actionCount;
        }
    }
}

