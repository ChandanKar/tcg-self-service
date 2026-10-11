package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.HolidayDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface HolidayDateRepository extends JpaRepository<HolidayDate, String> {

    Optional<HolidayDate> findByCalendarCalendarIdAndHolidayDate(String calendarId, LocalDate holidayDate);

    boolean existsByCalendarCalendarIdAndHolidayDate(String calendarId, LocalDate holidayDate);

    Page<HolidayDate> findByCalendarCalendarIdOrderByHolidayDateAsc(String calendarId, Pageable pageable);

    List<HolidayDate> findByCalendarCalendarIdAndHolidayDateBetweenOrderByHolidayDateAsc(
            String calendarId, LocalDate from, LocalDate to);

    long countByCalendarCalendarId(String calendarId);

    /** Dates per calendar, for the calendars list: [calendarId, count]. */
    @Query("SELECT d.calendar.calendarId, COUNT(d) FROM HolidayDate d GROUP BY d.calendar.calendarId")
    List<Object[]> countByCalendar();

    /** Next holiday on or after a day, per calendar. */
    @Query("SELECT d FROM HolidayDate d WHERE d.calendar.calendarId = :calendarId AND d.holidayDate >= :from "
            + "ORDER BY d.holidayDate ASC LIMIT 1")
    Optional<HolidayDate> findNext(@Param("calendarId") String calendarId, @Param("from") LocalDate from);
}
