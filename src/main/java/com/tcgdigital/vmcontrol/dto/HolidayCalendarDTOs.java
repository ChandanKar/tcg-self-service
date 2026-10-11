package com.tcgdigital.vmcontrol.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** Request and response shapes of the holiday calendar API (E19-T02). */
public final class HolidayCalendarDTOs {

    private HolidayCalendarDTOs() {
    }

    /** A calendar with its size and next holiday (null when none is ahead). */
    public record CalendarDTO(String calendarId, String name, String description, long dateCount,
                              LocalDate nextHoliday, String nextHolidayLabel) {}

    public record CalendarRequest(
            @NotBlank(message = "Name is required") @Size(max = 100, message = "Name must be at most 100 characters") String name,
            @Size(max = 500, message = "Description must be at most 500 characters") String description) {}

    public record DateDTO(String holidayId, LocalDate date, String label) {}

    public record DateRequest(
            @NotNull(message = "Date is required") LocalDate date,
            @Size(max = 100, message = "Label must be at most 100 characters") String label) {}

    /** One CSV line that was not imported (1-based line number). */
    public record InvalidLine(int line, String content, String reason) {}

    public record ImportResult(int added, int duplicates, List<InvalidLine> invalid) {}
}
