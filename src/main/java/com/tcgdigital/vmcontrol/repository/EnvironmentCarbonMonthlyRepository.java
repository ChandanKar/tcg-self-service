package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.EnvironmentCarbonMonthly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface EnvironmentCarbonMonthlyRepository extends JpaRepository<EnvironmentCarbonMonthly, String> {

    @Query("SELECT c FROM EnvironmentCarbonMonthly c LEFT JOIN FETCH c.environment WHERE c.month BETWEEN :from AND :to ORDER BY c.month")
    List<EnvironmentCarbonMonthly> findByMonthBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    List<EnvironmentCarbonMonthly> findByEnvironmentEnvironmentIdAndMonthBetweenOrderByMonth(String environmentId, LocalDate from,
                                                                                             LocalDate to);

    @Modifying
    @Query("DELETE FROM EnvironmentCarbonMonthly c WHERE c.month = :month")
    int deleteByMonth(@Param("month") LocalDate month);
}
