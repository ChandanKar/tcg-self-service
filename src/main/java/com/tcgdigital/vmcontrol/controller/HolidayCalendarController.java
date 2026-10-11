package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.controller.support.Paging;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.CalendarDTO;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.CalendarRequest;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.DateDTO;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.DateRequest;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.ImportResult;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.service.HolidayCalendarService;
import com.tcgdigital.vmcontrol.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Holiday calendars that schedule rules skip (E19-T02). Admins and env admins read them (to link
 * one to a rule); only admins change them.
 */
@RestController
@RequestMapping("/api/v1/holiday-calendars")
@Tag(name = "Holiday Calendars", description = "Holiday calendars that schedule rules can skip")
public class HolidayCalendarController {

    private static final int MAX_DATES_PAGE = 500;

    private final HolidayCalendarService holidayCalendarService;
    private final UserService userService;

    public HolidayCalendarController(HolidayCalendarService holidayCalendarService, UserService userService) {
        this.holidayCalendarService = holidayCalendarService;
        this.userService = userService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "List holiday calendars")
    public List<CalendarDTO> list() {
        return holidayCalendarService.list();
    }

    @GetMapping("/{calendarId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "Get a holiday calendar")
    public CalendarDTO get(@PathVariable String calendarId) {
        return holidayCalendarService.get(calendarId);
    }

    @GetMapping("/{calendarId}/dates")
    @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')")
    @Operation(summary = "List a calendar's dates, oldest first")
    public Page<DateDTO> dates(@PathVariable String calendarId,
                               @RequestParam(defaultValue = "0") int page,
                               @RequestParam(defaultValue = "100") int size) {
        return holidayCalendarService.listDates(calendarId, Paging.of(page, size, MAX_DATES_PAGE));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a holiday calendar")
    public CalendarDTO create(@Valid @RequestBody CalendarRequest request) {
        return holidayCalendarService.create(userService.getCurrentUserId(), request);
    }

    @PutMapping("/{calendarId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Rename or describe a holiday calendar")
    public CalendarDTO update(@PathVariable String calendarId, @Valid @RequestBody CalendarRequest request) {
        return holidayCalendarService.update(calendarId, userService.getCurrentUserId(), request);
    }

    @DeleteMapping("/{calendarId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Delete a holiday calendar (linked rules keep running without one)")
    public ResponseEntity<Void> delete(@PathVariable String calendarId) {
        holidayCalendarService.delete(calendarId, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{calendarId}/dates")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Add a date to a holiday calendar")
    public DateDTO addDate(@PathVariable String calendarId, @Valid @RequestBody DateRequest request) {
        return holidayCalendarService.addDate(calendarId, userService.getCurrentUserId(), request);
    }

    @DeleteMapping("/{calendarId}/dates/{holidayId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Remove a date from a holiday calendar")
    public ResponseEntity<Void> removeDate(@PathVariable String calendarId, @PathVariable String holidayId) {
        holidayCalendarService.removeDate(calendarId, holidayId, userService.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{calendarId}/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Import 'yyyy-MM-dd,label' lines from a CSV file (at most 500)")
    public ImportResult importCsv(@PathVariable String calendarId, @RequestParam("file") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Choose a CSV file to import");
        }
        return holidayCalendarService.importCsv(calendarId, userService.getCurrentUserId(), file.getInputStream());
    }
}
