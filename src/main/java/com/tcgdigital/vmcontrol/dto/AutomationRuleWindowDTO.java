package com.tcgdigital.vmcontrol.dto;

import java.util.List;

/** One time window of a schedule rule, in requests and responses (E19-T01). */
public class AutomationRuleWindowDTO {

    /** Three-letter day abbreviations, e.g. ["MON","TUE"]. */
    private List<String> daysOfWeek;

    /** "HH:mm" or null. */
    private String startTime;

    /** "HH:mm" or null. */
    private String stopTime;

    public AutomationRuleWindowDTO() {
    }

    public AutomationRuleWindowDTO(List<String> daysOfWeek, String startTime, String stopTime) {
        this.daysOfWeek = daysOfWeek;
        this.startTime = startTime;
        this.stopTime = stopTime;
    }

    public List<String> getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(List<String> daysOfWeek) {
        this.daysOfWeek = daysOfWeek;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getStopTime() {
        return stopTime;
    }

    public void setStopTime(String stopTime) {
        this.stopTime = stopTime;
    }
}
