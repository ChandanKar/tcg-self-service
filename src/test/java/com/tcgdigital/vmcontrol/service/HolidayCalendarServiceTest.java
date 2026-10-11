package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.CalendarDTO;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.CalendarRequest;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.DateRequest;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.ImportResult;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Holiday calendars: CRUD, CSV import with invalid lines and duplicates, and the holiday lookup (E19-T02). */
class HolidayCalendarServiceTest extends AbstractIntegrationTest {

    private static final LocalDate GANDHI_JAYANTI = LocalDate.of(2026, 10, 2);

    @Autowired private HolidayCalendarService service;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User admin;

    @BeforeEach
    void setUp() {
        admin = newUser("holiday-admin@example.com", true, false);
    }

    private static ByteArrayInputStream csv(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void calendarsAreCreatedRenamedAndDeletedWithUniqueNames() {
        CalendarDTO india = service.create(admin.getUserId(), new CalendarRequest(" India 2026 ", "Public holidays"));
        assertThat(india.name()).isEqualTo("India 2026");

        assertThatThrownBy(() -> service.create(admin.getUserId(), new CalendarRequest("india 2026", null)))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A holiday calendar named 'india 2026' already exists");

        CalendarDTO renamed = service.update(india.calendarId(), admin.getUserId(), new CalendarRequest("India", null));
        assertThat(renamed.name()).isEqualTo("India");
        assertThat(renamed.description()).isNull();
        assertThat(service.list()).extracting(CalendarDTO::name).containsExactly("India");

        service.addDate(india.calendarId(), admin.getUserId(), new DateRequest(GANDHI_JAYANTI, "Gandhi Jayanti"));
        service.delete(india.calendarId(), admin.getUserId());
        assertThat(service.list()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM holiday_date", Integer.class)).isZero();
        awaitAsync(() -> assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE action_type = 'HOLIDAY_CALENDAR_CHANGED' AND target_id = ?",
                Integer.class, india.calendarId())).isEqualTo(4));
    }

    @Test
    void datesAreAddedOnceListedInOrderAndLookedUp() {
        String id = service.create(admin.getUserId(), new CalendarRequest("India 2026", null)).calendarId();
        service.addDate(id, admin.getUserId(), new DateRequest(LocalDate.of(2026, 11, 8), "Diwali"));
        service.addDate(id, admin.getUserId(), new DateRequest(GANDHI_JAYANTI, "Gandhi Jayanti"));

        assertThatThrownBy(() -> service.addDate(id, admin.getUserId(), new DateRequest(GANDHI_JAYANTI, "again")))
                .isInstanceOf(ValidationException.class)
                .hasMessage("2026-10-02 is already in India 2026");

        assertThat(service.listDates(id, PageRequest.of(0, 10)).getContent())
                .extracting(d -> d.label()).containsExactly("Gandhi Jayanti", "Diwali");
        assertThat(service.isHoliday(id, GANDHI_JAYANTI)).isTrue();
        assertThat(service.isHoliday(id, GANDHI_JAYANTI.plusDays(1))).isFalse();
        assertThat(service.holidayOn(null, GANDHI_JAYANTI)).isEmpty();
        assertThat(service.get(id).dateCount()).isEqualTo(2);
    }

    @Test
    void anImportAddsValidDatesReportsBadLinesAndSkipsDuplicates() throws Exception {
        String id = service.create(admin.getUserId(), new CalendarRequest("India 2026", null)).calendarId();
        service.addDate(id, admin.getUserId(), new DateRequest(LocalDate.of(2026, 11, 8), "Diwali"));

        ImportResult result = service.importCsv(id, admin.getUserId(), csv(
                "﻿date,label\n"
                + "2026-10-02,Gandhi Jayanti\r\n"
                + "2026-13-40,Not a day\n"
                + "\n"
                + "2026-10-20,\"Dussehra\"\n"
                + "2026-11-08,Diwali\n"
                + "2026-10-20,Dussehra twice\n"
                + "2026-12-25\n"));

        assertThat(result.added()).isEqualTo(3);
        assertThat(result.duplicates()).isEqualTo(2);
        assertThat(result.invalid()).singleElement().satisfies(line -> {
            assertThat(line.line()).isEqualTo(3);
            assertThat(line.content()).isEqualTo("2026-13-40,Not a day");
        });
        assertThat(service.listDates(id, PageRequest.of(0, 10)).getContent())
                .extracting(d -> d.date().toString(), d -> d.label())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("2026-10-02", "Gandhi Jayanti"),
                        org.assertj.core.groups.Tuple.tuple("2026-10-20", "Dussehra"),
                        org.assertj.core.groups.Tuple.tuple("2026-11-08", "Diwali"),
                        org.assertj.core.groups.Tuple.tuple("2026-12-25", null));
    }

    @Test
    void anImportIsLimitedTo500Lines() {
        String id = service.create(admin.getUserId(), new CalendarRequest("Big", null)).calendarId();
        StringBuilder text = new StringBuilder();
        LocalDate day = LocalDate.of(2026, 1, 1);
        for (int i = 0; i < 501; i++) {
            text.append(day.plusDays(i)).append(",Day ").append(i).append('\n');
        }
        assertThatThrownBy(() -> service.importCsv(id, admin.getUserId(), csv(text.toString())))
                .isInstanceOf(ValidationException.class)
                .hasMessage("A CSV import is limited to 500 lines");
    }
}
