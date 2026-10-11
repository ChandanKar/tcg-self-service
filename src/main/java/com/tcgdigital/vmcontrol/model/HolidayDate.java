package com.tcgdigital.vmcontrol.model;

import jakarta.persistence.*;

import java.time.LocalDate;

/** One day of a holiday calendar, with an optional label such as "Gandhi Jayanti" (E19-T02). */
@Entity
@Table(name = "holiday_date")
public class HolidayDate {

    @Id
    @Column(name = "holiday_id", length = 36)
    private String holidayId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id", nullable = false)
    private HolidayCalendar calendar;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column(name = "label", length = 100)
    private String label;

    public HolidayDate() {
    }

    public String getHolidayId() {
        return holidayId;
    }

    public void setHolidayId(String holidayId) {
        this.holidayId = holidayId;
    }

    public HolidayCalendar getCalendar() {
        return calendar;
    }

    public void setCalendar(HolidayCalendar calendar) {
        this.calendar = calendar;
    }

    public LocalDate getHolidayDate() {
        return holidayDate;
    }

    public void setHolidayDate(LocalDate holidayDate) {
        this.holidayDate = holidayDate;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}
