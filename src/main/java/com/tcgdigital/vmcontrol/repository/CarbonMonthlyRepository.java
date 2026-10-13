package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.CarbonMonthly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface CarbonMonthlyRepository extends JpaRepository<CarbonMonthly, String> {

    List<CarbonMonthly> findByMonth(LocalDate month);

    List<CarbonMonthly> findByMonthBetweenOrderByMonth(LocalDate from, LocalDate to);

    /** The newest month with carbon data, or null before the first ingestion. */
    @Query("SELECT MAX(c.month) FROM CarbonMonthly c")
    LocalDate latestMonth();

    @Modifying
    @Query("DELETE FROM CarbonMonthly c WHERE c.month IN :months")
    int deleteByMonthIn(@Param("months") Collection<LocalDate> months);
}
