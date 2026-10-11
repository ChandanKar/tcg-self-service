package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AutomationRuleOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public interface AutomationRuleOverrideRepository extends JpaRepository<AutomationRuleOverride, String> {

    /** Overrides of a rule covering an instant (starts_at <= at < ends_at). */
    @Query("SELECT o FROM AutomationRuleOverride o WHERE o.ruleId = :ruleId AND o.startsAt <= :at AND o.endsAt > :at "
            + "ORDER BY o.createdAt ASC")
    List<AutomationRuleOverride> findActive(@Param("ruleId") String ruleId, @Param("at") Timestamp at);

    /** Active and future overrides of a rule, soonest first. */
    @Query("SELECT o FROM AutomationRuleOverride o WHERE o.ruleId = :ruleId AND o.endsAt > :now ORDER BY o.startsAt ASC")
    List<AutomationRuleOverride> findUpcoming(@Param("ruleId") String ruleId, @Param("now") Timestamp now);

    long countByRuleIdAndEndsAtAfter(String ruleId, Timestamp now);
}
