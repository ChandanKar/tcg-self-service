package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.IdleStopSnooze;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public interface IdleStopSnoozeRepository extends JpaRepository<IdleStopSnooze, String> {

    /** Snoozes still in force, latest end first. */
    @Query("SELECT s FROM IdleStopSnooze s WHERE s.environmentId = :environmentId AND s.snoozedUntil > :now " +
           "ORDER BY s.snoozedUntil DESC")
    List<IdleStopSnooze> findActive(@Param("environmentId") String environmentId, @Param("now") Timestamp now);
}
