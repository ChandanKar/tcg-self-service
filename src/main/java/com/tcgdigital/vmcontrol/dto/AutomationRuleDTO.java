package com.tcgdigital.vmcontrol.dto;

import com.tcgdigital.vmcontrol.model.AccessGrantMode;
import com.tcgdigital.vmcontrol.model.AutomationRule;
import com.tcgdigital.vmcontrol.model.AutomationRunReason;
import com.tcgdigital.vmcontrol.model.AutomationRunStatus;
import com.tcgdigital.vmcontrol.model.AutomationScopeType;
import com.tcgdigital.vmcontrol.model.AutomationTriggerType;

import java.sql.Timestamp;
import java.util.ArrayList;
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
    /** Schedule only: the rule's windows (E19-T01). */
    private List<AutomationRuleWindowDTO> windows;
    /** Schedule only: e.g. "Mon-Fri 08:00-20:00; Sat 10:00-14:00". */
    private String scheduleSummary;
    /** Legacy single-window fields, from the first window (older clients). */
    private List<String> daysOfWeek;
    private String stopTime;
    private String startTime;
    private String timezone;
    /** Schedule only: the linked holiday calendar (E19-T02). */
    private String holidayCalendarId;
    private String holidayCalendarName;
    /** Schedule only: the next stop and start of an enabled rule (E19-T03 'Skip next stop'). */
    private java.time.Instant nextStopAt;
    private java.time.Instant nextStartAt;
    /** Active and upcoming overrides (E19-T03). */
    private long overrideCount;
    /** Schedule only: the first run that is not skipped (E19-T04). */
    private java.time.OffsetDateTime nextRunAt;
    private String nextRunAction;
    private com.tcgdigital.vmcontrol.model.HolidayBehavior holidayBehavior;
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
    private AutomationRunReason lastRunReason;
    private String disabledReason;

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
        dto.windows = new ArrayList<>();
        for (com.tcgdigital.vmcontrol.model.AutomationRuleWindow w : rule.getWindows()) {
            dto.windows.add(new AutomationRuleWindowDTO(days(w.getDaysOfWeek()), w.getStartTime(), w.getStopTime()));
        }
        dto.scheduleSummary = dto.windows.isEmpty() ? null : summarize(dto.windows);
        AutomationRuleWindowDTO first = dto.windows.isEmpty() ? null : dto.windows.get(0);
        dto.daysOfWeek = first == null ? List.of() : first.getDaysOfWeek();
        dto.stopTime = first == null ? null : first.getStopTime();
        dto.startTime = first == null ? null : first.getStartTime();
        dto.timezone = rule.getTimezone();
        dto.holidayCalendarId = rule.getHolidayCalendarId();
        dto.holidayBehavior = rule.getHolidayBehavior();
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
        dto.lastRunReason = rule.getLastRunReason();
        dto.disabledReason = rule.getDisabledReason();
        return dto;
    }

    private static List<String> days(String csv) {
        return csv == null || csv.isBlank() ? List.of() : Arrays.asList(csv.split(","));
    }

    private static final List<String> WEEK = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");

    /** "Mon-Fri 08:00-20:00; Sat 10:00-14:00"; a start-only window reads "start 08:00", a stop-only one "stop 20:00". */
    public static String summarize(List<AutomationRuleWindowDTO> windows) {
        List<String> parts = new ArrayList<>();
        for (AutomationRuleWindowDTO w : windows) {
            String times;
            if (w.getStartTime() != null && w.getStopTime() != null) {
                times = w.getStartTime() + "-" + w.getStopTime();
            } else if (w.getStartTime() != null) {
                times = "start " + w.getStartTime();
            } else {
                times = "stop " + w.getStopTime();
            }
            parts.add(dayRanges(w.getDaysOfWeek()) + " " + times);
        }
        return String.join("; ", parts);
    }

    /** MON,TUE,WED,THU,FRI -> "Mon-Fri"; MON,WED,FRI -> "Mon, Wed, Fri"; runs of three or more collapse. */
    static String dayRanges(List<String> days) {
        List<Integer> idx = days == null ? List.of() : days.stream().map(d -> WEEK.indexOf(d.trim().toUpperCase(java.util.Locale.ROOT)))
                .filter(i -> i >= 0).distinct().sorted().toList();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < idx.size()) {
            int j = i;
            while (j + 1 < idx.size() && idx.get(j + 1) == idx.get(j) + 1) {
                j++;
            }
            if (j - i >= 2) {
                out.add(label(idx.get(i)) + "-" + label(idx.get(j)));
            } else {
                for (int k = i; k <= j; k++) {
                    out.add(label(idx.get(k)));
                }
            }
            i = j + 1;
        }
        return String.join(", ", out);
    }

    private static String label(int index) {
        String d = WEEK.get(index);
        return d.charAt(0) + d.substring(1).toLowerCase(java.util.Locale.ROOT);
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

    public List<AutomationRuleWindowDTO> getWindows() {
        return windows;
    }

    public String getScheduleSummary() {
        return scheduleSummary;
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

    public String getHolidayCalendarId() {
        return holidayCalendarId;
    }

    public java.time.Instant getNextStopAt() {
        return nextStopAt;
    }

    public java.time.Instant getNextStartAt() {
        return nextStartAt;
    }

    public java.time.OffsetDateTime getNextRunAt() {
        return nextRunAt;
    }

    public String getNextRunAction() {
        return nextRunAction;
    }

    public void setNextRun(java.time.OffsetDateTime nextRunAt, String nextRunAction) {
        this.nextRunAt = nextRunAt;
        this.nextRunAction = nextRunAction;
    }

    public long getOverrideCount() {
        return overrideCount;
    }

    /** Set by the service, which knows the clock and the overrides. */
    public void setSchedule(java.time.Instant nextStopAt, java.time.Instant nextStartAt, long overrideCount) {
        this.nextStopAt = nextStopAt;
        this.nextStartAt = nextStartAt;
        this.overrideCount = overrideCount;
    }

    public String getHolidayCalendarName() {
        return holidayCalendarName;
    }

    public void setHolidayCalendarName(String holidayCalendarName) {
        this.holidayCalendarName = holidayCalendarName;
    }

    public com.tcgdigital.vmcontrol.model.HolidayBehavior getHolidayBehavior() {
        return holidayBehavior;
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

    public AutomationRunReason getLastRunReason() {
        return lastRunReason;
    }

    /** Why the system switched the rule off; null if it was not. */
    public String getDisabledReason() {
        return disabledReason;
    }
}
