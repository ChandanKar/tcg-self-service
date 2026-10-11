package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.HolidayCalendar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface HolidayCalendarRepository extends JpaRepository<HolidayCalendar, String> {

    List<HolidayCalendar> findAllByOrderByNameAsc();

    Optional<HolidayCalendar> findByNameIgnoreCase(String name);
}
