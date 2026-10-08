package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.IdleStopEvent;
import com.tcgdigital.vmcontrol.model.IdleStopOutcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public interface IdleStopEventRepository extends JpaRepository<IdleStopEvent, String> {

    List<IdleStopEvent> findByEnvironmentIdAndEvaluatedAtBetweenOrderByEvaluatedAtDesc(
            String environmentId, Timestamp from, Timestamp to);

    List<IdleStopEvent> findTop20ByEnvironmentIdOrderByEvaluatedAtDesc(String environmentId);

    /** One WOULD_STOP / STOPPED per idle episode. */
    boolean existsByRuleIdAndOutcomeAndIdleSince(String ruleId, IdleStopOutcome outcome, Timestamp idleSince);

    /** At most one SKIPPED / SNOOZED event per rule and reason per hour. */
    boolean existsByRuleIdAndOutcomeAndReasonAndEvaluatedAtAfter(String ruleId, IdleStopOutcome outcome,
                                                                 String reason, Timestamp after);
}
