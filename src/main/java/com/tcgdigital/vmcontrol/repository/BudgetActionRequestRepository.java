package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.BudgetActionRequest;
import com.tcgdigital.vmcontrol.model.BudgetActionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetActionRequestRepository extends JpaRepository<BudgetActionRequest, String> {

    Optional<BudgetActionRequest> findByBudgetBudgetIdAndBudgetMonthAndActionType(String budgetId, String budgetMonth,
                                                                                  BudgetActionType actionType);

    @Query("SELECT r FROM BudgetActionRequest r JOIN FETCH r.budget ORDER BY r.requestedAt DESC")
    List<BudgetActionRequest> findAllFetchBudget();

    @Query("SELECT r FROM BudgetActionRequest r JOIN FETCH r.budget WHERE r.requestId = :id")
    Optional<BudgetActionRequest> findByIdFetchBudget(@Param("id") String requestId);

    /** Requests still waiting from months before {@code month} ("yyyy-MM" sorts as text). */
    @Query("SELECT r FROM BudgetActionRequest r WHERE r.status = 'PENDING' AND r.budgetMonth < :month")
    List<BudgetActionRequest> findPendingBefore(@Param("month") String month);

    /** Pending requests of an environment's budget (the environment page). */
    @Query("SELECT r FROM BudgetActionRequest r JOIN FETCH r.budget b WHERE b.scopeType = 'ENVIRONMENT' "
            + "AND b.scopeId = :environmentId AND r.status = 'PENDING'")
    List<BudgetActionRequest> findPendingForEnvironment(@Param("environmentId") String environmentId);
}
