package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ApprovalPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Approval policies (E25-T03). */
public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicy, String> {

    /** Active policies that apply to an environment: global ones, its own, and its team's. */
    @Query("SELECT DISTINCT p FROM ApprovalPolicy p WHERE p.isActive = true AND ("
            + "(p.environmentId IS NULL AND p.teamId IS NULL) OR p.environmentId = :environmentId "
            + "OR (:teamId IS NOT NULL AND p.teamId = :teamId))")
    List<ApprovalPolicy> findActiveForEnvironment(@Param("environmentId") String environmentId, @Param("teamId") String teamId);

    List<ApprovalPolicy> findAllByOrderByNameAsc();

    List<ApprovalPolicy> findByEnvironmentIdOrderByNameAsc(String environmentId);
}
