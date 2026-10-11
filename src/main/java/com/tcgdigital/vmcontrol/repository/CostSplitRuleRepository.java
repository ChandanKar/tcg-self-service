package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.CostSplitRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** Shared-cost split rules (E23-T05). */
public interface CostSplitRuleRepository extends JpaRepository<CostSplitRule, String> {

    Optional<CostSplitRule> findByEnvironmentId(String environmentId);

    @Query("SELECT DISTINCT r FROM CostSplitRule r LEFT JOIN FETCH r.shares")
    List<CostSplitRule> findAllWithShares();
}
