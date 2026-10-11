package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.sql.Date;

/**
 * One time window of a schedule rule (E19-T01): the days it applies to, an optional start and an
 * optional stop time, and the day each time last fired (so two windows fire independently).
 */
@Entity
@Table(name = "automation_rule_window")
public class AutomationRuleWindow {

    @Id
    @Column(name = "window_id", length = 36)
    private String windowId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rule_id", nullable = false)
    private AutomationRule rule;

    @Column(name = "position", nullable = false)
    private int position;

    /** CSV of three-letter days in week order, e.g. "MON,TUE,WED,THU,FRI". */
    @Column(name = "days_of_week", nullable = false, length = 30)
    private String daysOfWeek;

    /** "HH:mm" or null (a stop-only window). */
    @Column(name = "start_time", length = 5)
    private String startTime;

    /** "HH:mm" or null (a start-only window). */
    @Column(name = "stop_time", length = 5)
    private String stopTime;

    @Column(name = "last_start_fired_on")
    private Date lastStartFiredOn;

    @Column(name = "last_stop_fired_on")
    private Date lastStopFiredOn;

    public AutomationRuleWindow() {
    }

    public String getWindowId() {
        return windowId;
    }

    public void setWindowId(String windowId) {
        this.windowId = windowId;
    }

    public AutomationRule getRule() {
        return rule;
    }

    public void setRule(AutomationRule rule) {
        this.rule = rule;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public String getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(String daysOfWeek) {
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

    public Date getLastStartFiredOn() {
        return lastStartFiredOn;
    }

    public void setLastStartFiredOn(Date lastStartFiredOn) {
        this.lastStartFiredOn = lastStartFiredOn;
    }

    public Date getLastStopFiredOn() {
        return lastStopFiredOn;
    }

    public void setLastStopFiredOn(Date lastStopFiredOn) {
        this.lastStopFiredOn = lastStopFiredOn;
    }
}
