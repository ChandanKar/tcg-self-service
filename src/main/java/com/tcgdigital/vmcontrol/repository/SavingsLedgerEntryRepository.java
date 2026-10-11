package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.SavingsLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/** Savings ledger rows and their sums (E17-T02); a null environmentId means every environment. */
@Repository
public interface SavingsLedgerEntryRepository extends JpaRepository<SavingsLedgerEntry, String> {

    List<SavingsLedgerEntry> findByLedgerDateBetweenOrderByLedgerDateAsc(LocalDate start, LocalDate end);

    List<SavingsLedgerEntry> findByEnvironmentIdAndLedgerDateBetweenOrderByLedgerDateAsc(
            String environmentId, LocalDate start, LocalDate end);

    /** Rows for one day are rewritten as a whole (the daily job is idempotent). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM SavingsLedgerEntry e WHERE e.ledgerDate = :day")
    int deleteByLedgerDate(@Param("day") LocalDate day);

    /** [cause, sum(net), sum(gross), sum(hours)] */
    @Query("SELECT e.cause, SUM(e.netSaving), SUM(e.grossSaving), SUM(e.avoidedHours) FROM SavingsLedgerEntry e "
            + "WHERE e.ledgerDate BETWEEN :start AND :end AND (:environmentId IS NULL OR e.environmentId = :environmentId) "
            + "GROUP BY e.cause")
    List<Object[]> sumByCause(@Param("start") LocalDate start, @Param("end") LocalDate end,
                              @Param("environmentId") String environmentId);

    /** [environmentId, sum(net), sum(gross)] */
    @Query("SELECT e.environmentId, SUM(e.netSaving), SUM(e.grossSaving) FROM SavingsLedgerEntry e "
            + "WHERE e.ledgerDate BETWEEN :start AND :end AND (:environmentId IS NULL OR e.environmentId = :environmentId) "
            + "GROUP BY e.environmentId")
    List<Object[]> sumByEnvironment(@Param("start") LocalDate start, @Param("end") LocalDate end,
                                    @Param("environmentId") String environmentId);

    /** [team, sum(net), sum(gross)] */
    @Query("SELECT e.team, SUM(e.netSaving), SUM(e.grossSaving) FROM SavingsLedgerEntry e "
            + "WHERE e.ledgerDate BETWEEN :start AND :end AND (:environmentId IS NULL OR e.environmentId = :environmentId) "
            + "GROUP BY e.team")
    List<Object[]> sumByTeam(@Param("start") LocalDate start, @Param("end") LocalDate end,
                             @Param("environmentId") String environmentId);
}
