package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmStateHistory;
import com.tcgdigital.vmcontrol.model.VmStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VmCostCalculatorTest {

    private VmCostCalculator calculator;

    // A 10-hour window comfortably in the past so clamp-to-now never engages.
    private Timestamp windowStart;
    private Timestamp windowEnd;

    @BeforeEach
    void setUp() {
        calculator = new VmCostCalculator();
        Instant now = Instant.now();
        windowStart = Timestamp.from(now.minus(20, ChronoUnit.HOURS));
        windowEnd = Timestamp.from(now.minus(10, ChronoUnit.HOURS));
    }

    private Vm vmCreatedAt(Timestamp createdAt) {
        Vm vm = new Vm("vm-1");
        vm.setCreatedAt(createdAt);
        return vm;
    }

    private VmStateHistory transitionAt(Timestamp changedAt, VmStatus newStatus) {
        VmStateHistory history = VmStateHistory.builder().newStatus(newStatus).build();
        history.setChangedAt(changedAt);
        return history;
    }

    @Test
    void runningWholeWindowCountsFullDuration() {
        Vm vm = vmCreatedAt(Timestamp.from(windowStart.toInstant().minus(1, ChronoUnit.DAYS)));

        BigDecimal hours = calculator.computeRuntimeHours(vm, VmStatus.RUNNING, List.of(), windowStart, windowEnd);

        assertEquals(0, new BigDecimal("10.000").compareTo(hours));
    }

    @Test
    void stoppedWholeWindowCountsZero() {
        Vm vm = vmCreatedAt(Timestamp.from(windowStart.toInstant().minus(1, ChronoUnit.DAYS)));

        BigDecimal hours = calculator.computeRuntimeHours(vm, VmStatus.STOPPED, List.of(), windowStart, windowEnd);

        assertEquals(0, BigDecimal.ZERO.compareTo(hours));
    }

    @Test
    void vmCreatedMidWindowClampsToCreationTime() {
        // Window is 10h wide; VM is created 4h after windowStart, so only 6h could ever count.
        Timestamp createdAt = Timestamp.from(windowStart.toInstant().plus(4, ChronoUnit.HOURS));
        Vm vm = vmCreatedAt(createdAt);

        BigDecimal hours = calculator.computeRuntimeHours(vm, VmStatus.RUNNING, List.of(), windowStart, windowEnd);

        assertEquals(0, new BigDecimal("6.000").compareTo(hours));
    }

    @Test
    void noHistoryRowsAssumesSeedStatusForWholeWindow() {
        Vm vm = vmCreatedAt(Timestamp.from(windowStart.toInstant().minus(1, ChronoUnit.DAYS)));

        BigDecimal runningHours = calculator.computeRuntimeHours(vm, VmStatus.RUNNING, null, windowStart, windowEnd);
        BigDecimal stoppedHours = calculator.computeRuntimeHours(vm, VmStatus.STOPPED, null, windowStart, windowEnd);

        assertEquals(0, new BigDecimal("10.000").compareTo(runningHours));
        assertEquals(0, BigDecimal.ZERO.compareTo(stoppedHours));
    }

    @Test
    void singleTransitionSplitsWindowBetweenStates() {
        Vm vm = vmCreatedAt(Timestamp.from(windowStart.toInstant().minus(1, ChronoUnit.DAYS)));
        Timestamp stopAt = Timestamp.from(windowStart.toInstant().plus(4, ChronoUnit.HOURS));
        List<VmStateHistory> transitions = List.of(transitionAt(stopAt, VmStatus.STOPPED));

        BigDecimal hours = calculator.computeRuntimeHours(vm, VmStatus.RUNNING, transitions, windowStart, windowEnd);

        assertEquals(0, new BigDecimal("4.000").compareTo(hours));
    }

    @Test
    void multipleTransitionsSumEachRunningInterval() {
        Vm vm = vmCreatedAt(Timestamp.from(windowStart.toInstant().minus(1, ChronoUnit.DAYS)));
        Instant base = windowStart.toInstant();
        List<VmStateHistory> transitions = List.of(
                transitionAt(Timestamp.from(base.plus(2, ChronoUnit.HOURS)), VmStatus.RUNNING),
                transitionAt(Timestamp.from(base.plus(6, ChronoUnit.HOURS)), VmStatus.STOPPED),
                transitionAt(Timestamp.from(base.plus(8, ChronoUnit.HOURS)), VmStatus.RUNNING)
        );

        // Seed STOPPED: [0,2) stopped, [2,6) running = 4h, [6,8) stopped, [8,10) running = 2h.
        BigDecimal hours = calculator.computeRuntimeHours(vm, VmStatus.STOPPED, transitions, windowStart, windowEnd);

        assertEquals(0, new BigDecimal("6.000").compareTo(hours));
    }

    @Test
    void invertedEffectiveWindowReturnsZero() {
        // VM created after the window ends entirely.
        Vm vm = vmCreatedAt(Timestamp.from(windowEnd.toInstant().plus(1, ChronoUnit.HOURS)));

        BigDecimal hours = calculator.computeRuntimeHours(vm, VmStatus.RUNNING, List.of(), windowStart, windowEnd);

        assertEquals(0, BigDecimal.ZERO.compareTo(hours));
    }

    @Test
    void estimateCostForFullMonthWindowAppliesFullStorageRate() {
        BigDecimal cost = calculator.estimateCost(
                new BigDecimal("1.00"), new BigDecimal("200"), 90, new BigDecimal("0.30"), new BigDecimal("30"));

        // compute: 1.00 * 200 = 200.00; storage: 0.30 * 90 (full month) = 27.00
        assertEquals(0, new BigDecimal("227.00").compareTo(cost));
    }

    @Test
    void estimateCostForOneDayWindowProratesStorageToOneThirtieth() {
        BigDecimal cost = calculator.estimateCost(
                BigDecimal.ZERO, BigDecimal.ZERO, 90, new BigDecimal("0.30"), BigDecimal.ONE);

        // storage: 0.30 * 90 / 30 = 0.90; compute is zero
        assertEquals(0, new BigDecimal("0.90").compareTo(cost));
    }

    @Test
    void windowDaysComputesFractionalDaysBetweenTimestamps() {
        Instant base = Instant.now();
        Timestamp start = Timestamp.from(base);
        Timestamp oneDayLater = Timestamp.from(base.plus(24, ChronoUnit.HOURS));
        Timestamp twelveHoursLater = Timestamp.from(base.plus(12, ChronoUnit.HOURS));

        assertEquals(0, new BigDecimal("1.000000").compareTo(calculator.windowDays(start, oneDayLater)));
        assertEquals(0, new BigDecimal("0.500000").compareTo(calculator.windowDays(start, twelveHoursLater)));
    }

    // --- storageWindowDays (E08-T01): storage only while the VM existed, never into the future ---

    @Test
    void storageForAVmCreated18HoursIntoADayIsAQuarterDay() {
        Timestamp dayStart = Timestamp.from(Instant.now().minus(3, ChronoUnit.DAYS));
        Timestamp dayEnd = Timestamp.from(dayStart.toInstant().plus(24, ChronoUnit.HOURS));
        Timestamp created = Timestamp.from(dayStart.toInstant().plus(18, ChronoUnit.HOURS));

        assertEquals(0, new BigDecimal("0.250000").compareTo(calculator.storageWindowDays(created, dayStart, dayEnd)));
    }

    @Test
    void aVmCreatedAfterTheWindowHasNoStorageDays() {
        Timestamp created = Timestamp.from(windowEnd.toInstant().plus(1, ChronoUnit.HOURS));

        assertEquals(0, BigDecimal.ZERO.compareTo(calculator.storageWindowDays(created, windowStart, windowEnd)));
    }

    @Test
    void aWindowEndingInTheFutureStopsAtNow() {
        Timestamp start = Timestamp.from(Instant.now().minus(6, ChronoUnit.HOURS));
        Timestamp end = Timestamp.from(Instant.now().plus(18, ChronoUnit.HOURS));

        BigDecimal days = calculator.storageWindowDays(null, start, end);

        // About 6 hours (0.25 day), not the full 24.
        assertEquals(0.25, days.doubleValue(), 0.002);
    }
}
