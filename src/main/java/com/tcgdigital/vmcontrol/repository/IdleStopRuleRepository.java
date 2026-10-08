package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.IdleStopRule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IdleStopRuleRepository extends JpaRepository<IdleStopRule, String> {

    /** Enabled rules with their environments (the scheduler runs without a session). */
    @Query("SELECT r FROM IdleStopRule r JOIN FETCH r.environment WHERE r.enabled = true")
    List<IdleStopRule> findByEnabledTrue();

    @Query("SELECT r FROM IdleStopRule r JOIN FETCH r.environment WHERE r.ruleId = :ruleId")
    Optional<IdleStopRule> findByIdFetchEnvironment(@Param("ruleId") String ruleId);

    @Query("SELECT r FROM IdleStopRule r JOIN FETCH r.environment e WHERE e.environmentId = :environmentId " +
           "ORDER BY r.createdAt")
    List<IdleStopRule> findByEnvironment(@Param("environmentId") String environmentId);

    boolean existsByEnvironment_EnvironmentIdAndGroupIdIsNull(String environmentId);

    boolean existsByEnvironment_EnvironmentIdAndGroupId(String environmentId, String groupId);
}
