package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.CalendarDTO;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.CalendarRequest;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.DateDTO;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.DateRequest;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.ImportResult;
import com.tcgdigital.vmcontrol.dto.HolidayCalendarDTOs.InvalidLine;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.HolidayCalendar;
import com.tcgdigital.vmcontrol.model.HolidayDate;
import com.tcgdigital.vmcontrol.repository.HolidayCalendarRepository;
import com.tcgdigital.vmcontrol.repository.HolidayDateRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Holiday calendars (E19-T02): named lists of dates that schedule rules skip. Admins manage them;
 * the scheduler asks {@link #holidayOn} for a rule's local date.
 */
@Service
public class HolidayCalendarService {

    /** Most lines a CSV import may have. */
    static final int MAX_IMPORT_LINES = 500;
    private static final int MAX_LABEL = 100;

    private final HolidayCalendarRepository calendars;
    private final HolidayDateRepository dates;
    private final AuditService auditService;

    /** Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public HolidayCalendarService(HolidayCalendarRepository calendars, HolidayDateRepository dates,
                                  AuditService auditService) {
        this.calendars = calendars;
        this.dates = dates;
        this.auditService = auditService;
    }

    // ============= Calendars =============

    @Transactional(readOnly = true)
    public List<CalendarDTO> list() {
        Map<String, Long> counts = dates.countByCalendar().stream()
                .collect(Collectors.toMap(r -> (String) r[0], r -> (Long) r[1]));
        LocalDate today = LocalDate.now(clock);
        return calendars.findAllByOrderByNameAsc().stream()
                .map(c -> toDto(c, counts.getOrDefault(c.getCalendarId(), 0L), today))
                .toList();
    }

    @Transactional(readOnly = true)
    public CalendarDTO get(String calendarId) {
        HolidayCalendar calendar = calendar(calendarId);
        return toDto(calendar, dates.countByCalendarCalendarId(calendarId), LocalDate.now(clock));
    }

    @Transactional(readOnly = true)
    public boolean exists(String calendarId) {
        return calendarId != null && calendars.existsById(calendarId);
    }

    /** The calendar's name, or null when it does not exist. */
    @Transactional(readOnly = true)
    public String nameOf(String calendarId) {
        return calendarId == null ? null : calendars.findById(calendarId).map(HolidayCalendar::getName).orElse(null);
    }

    @Transactional
    public CalendarDTO create(String userId, CalendarRequest request) {
        String name = request.name().trim();
        requireUniqueName(name, null);
        HolidayCalendar calendar = new HolidayCalendar();
        calendar.setCalendarId(UUID.randomUUID().toString());
        calendar.setName(name);
        calendar.setDescription(blankToNull(request.description()));
        calendar.setCreatedByUserId(userId);
        calendar = calendars.save(calendar);
        audit(userId, calendar, "Created calendar");
        return toDto(calendar, 0, LocalDate.now(clock));
    }

    @Transactional
    public CalendarDTO update(String calendarId, String userId, CalendarRequest request) {
        HolidayCalendar calendar = calendar(calendarId);
        String name = request.name().trim();
        requireUniqueName(name, calendarId);
        String before = calendar.getName();
        calendar.setName(name);
        calendar.setDescription(blankToNull(request.description()));
        calendars.save(calendar);
        audit(userId, calendar, before.equals(name) ? "Updated calendar" : "Renamed calendar from " + before);
        return get(calendarId);
    }

    /** Rules linked to the calendar keep running without one (the column is set to null). */
    @Transactional
    public void delete(String calendarId, String userId) {
        HolidayCalendar calendar = calendar(calendarId);
        calendars.delete(calendar);
        audit(userId, calendar, "Deleted calendar");
    }

    // ============= Dates =============

    @Transactional(readOnly = true)
    public Page<DateDTO> listDates(String calendarId, Pageable pageable) {
        calendar(calendarId);
        return dates.findByCalendarCalendarIdOrderByHolidayDateAsc(calendarId, pageable)
                .map(d -> new DateDTO(d.getHolidayId(), d.getHolidayDate(), d.getLabel()));
    }

    @Transactional
    public DateDTO addDate(String calendarId, String userId, DateRequest request) {
        HolidayCalendar calendar = calendar(calendarId);
        if (dates.existsByCalendarCalendarIdAndHolidayDate(calendarId, request.date())) {
            throw new ValidationException(request.date() + " is already in " + calendar.getName());
        }
        HolidayDate date = newDate(calendar, request.date(), blankToNull(request.label()));
        dates.save(date);
        audit(userId, calendar, "Added " + request.date() + (date.getLabel() == null ? "" : " (" + date.getLabel() + ")"));
        return new DateDTO(date.getHolidayId(), date.getHolidayDate(), date.getLabel());
    }

    @Transactional
    public void removeDate(String calendarId, String holidayId, String userId) {
        HolidayCalendar calendar = calendar(calendarId);
        HolidayDate date = dates.findById(holidayId)
                .filter(d -> d.getCalendar().getCalendarId().equals(calendarId))
                .orElseThrow(() -> new ResourceNotFoundException("HolidayDate", holidayId));
        dates.delete(date);
        audit(userId, calendar, "Removed " + date.getHolidayDate());
    }

    /**
     * Imports "yyyy-MM-dd,label" lines (label optional; an optional "date,label" header is
     * skipped). Valid dates are added, dates already present are counted as duplicates, and
     * every other line is reported with its 1-based number.
     */
    @Transactional
    public ImportResult importCsv(String calendarId, String userId, InputStream csv) throws IOException {
        HolidayCalendar calendar = calendar(calendarId);
        String text = new String(csv.readAllBytes(), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String[] lines = text.split("\\r?\\n", -1);
        long nonBlank = java.util.Arrays.stream(lines).filter(l -> !l.isBlank()).count();
        if (nonBlank > MAX_IMPORT_LINES) {
            throw new ValidationException("A CSV import is limited to " + MAX_IMPORT_LINES + " lines");
        }
        Set<LocalDate> existing = new HashSet<>();
        dates.findByCalendarCalendarIdOrderByHolidayDateAsc(calendarId, Pageable.unpaged())
                .forEach(d -> existing.add(d.getHolidayDate()));

        int added = 0;
        int duplicates = 0;
        List<InvalidLine> invalid = new ArrayList<>();
        boolean first = true;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            boolean header = first && line.toLowerCase(java.util.Locale.ROOT).startsWith("date");
            first = false;
            if (header) {
                continue;
            }
            int comma = line.indexOf(',');
            String datePart = (comma < 0 ? line : line.substring(0, comma)).trim();
            String label = comma < 0 ? null : unquote(line.substring(comma + 1).trim());
            LocalDate day;
            try {
                day = LocalDate.parse(datePart);
            } catch (DateTimeParseException e) {
                invalid.add(new InvalidLine(i + 1, lines[i], "Not a date (expected yyyy-MM-dd)"));
                continue;
            }
            if (label != null && label.length() > MAX_LABEL) {
                invalid.add(new InvalidLine(i + 1, lines[i], "Label longer than " + MAX_LABEL + " characters"));
                continue;
            }
            if (!existing.add(day)) {
                duplicates++;
                continue;
            }
            dates.save(newDate(calendar, day, blankToNull(label)));
            added++;
        }
        audit(userId, calendar, "Imported " + added + " date(s); " + duplicates + " duplicate(s); "
                + invalid.size() + " invalid line(s)");
        return new ImportResult(added, duplicates, invalid);
    }

    /** The holiday on {@code day} in the calendar, if any (the scheduler passes the rule's local date). */
    @Transactional(readOnly = true)
    public Optional<HolidayDate> holidayOn(String calendarId, LocalDate day) {
        if (calendarId == null) {
            return Optional.empty();
        }
        return dates.findByCalendarCalendarIdAndHolidayDate(calendarId, day);
    }

    /** Holidays from {@code from} to {@code to} inclusive: day to label ("" when unlabelled). */
    @Transactional(readOnly = true)
    public Map<LocalDate, String> holidaysBetween(String calendarId, LocalDate from, LocalDate to) {
        Map<LocalDate, String> out = new java.util.HashMap<>();
        if (calendarId != null) {
            dates.findByCalendarCalendarIdAndHolidayDateBetweenOrderByHolidayDateAsc(calendarId, from, to)
                    .forEach(d -> out.put(d.getHolidayDate(), d.getLabel() == null ? "" : d.getLabel()));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public boolean isHoliday(String calendarId, LocalDate day) {
        return holidayOn(calendarId, day).isPresent();
    }

    // ============= Helpers =============

    private HolidayCalendar calendar(String calendarId) {
        return calendars.findById(calendarId)
                .orElseThrow(() -> new ResourceNotFoundException("HolidayCalendar", calendarId));
    }

    private void requireUniqueName(String name, String exceptCalendarId) {
        calendars.findByNameIgnoreCase(name)
                .filter(c -> !c.getCalendarId().equals(exceptCalendarId))
                .ifPresent(c -> {
                    throw new ValidationException("A holiday calendar named '" + name + "' already exists");
                });
    }

    private static HolidayDate newDate(HolidayCalendar calendar, LocalDate day, String label) {
        HolidayDate date = new HolidayDate();
        date.setHolidayId(UUID.randomUUID().toString());
        date.setCalendar(calendar);
        date.setHolidayDate(day);
        date.setLabel(label);
        return date;
    }

    private CalendarDTO toDto(HolidayCalendar c, long count, LocalDate today) {
        Optional<HolidayDate> next = dates.findNext(c.getCalendarId(), today);
        return new CalendarDTO(c.getCalendarId(), c.getName(), c.getDescription(), count,
                next.map(HolidayDate::getHolidayDate).orElse(null), next.map(HolidayDate::getLabel).orElse(null));
    }

    private void audit(String userId, HolidayCalendar calendar, String details) {
        auditService.logAction(userId, AuditAction.HOLIDAY_CALENDAR_CHANGED, "holiday_calendar",
                calendar.getCalendarId(), calendar.getName(), details);
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1).trim() : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
