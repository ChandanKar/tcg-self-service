package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.BudgetAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BudgetAlertRepository extends JpaRepository<BudgetAlert, String> {

    boolean existsByBudgetBudgetIdAndBudgetMonthAndThresholdAndKind(String budgetId, String budgetMonth,
                                                                      Integer threshold, BudgetAlert.Kind kind);

    List<BudgetAlert> findByBudgetBudgetIdAndBudgetMonthOrderByFiredAtAsc(String budgetId, String budgetMonth);
}
