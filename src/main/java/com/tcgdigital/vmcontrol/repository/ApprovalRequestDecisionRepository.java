package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ApprovalRequestDecision;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Approvers' decisions (E25-T04). */
public interface ApprovalRequestDecisionRepository extends JpaRepository<ApprovalRequestDecision, ApprovalRequestDecision.Key> {

    List<ApprovalRequestDecision> findByRequestId(String requestId);
}
