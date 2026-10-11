package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.Budget;
import com.tcgdigital.vmcontrol.model.BudgetScopeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, String> {

    Optional<Budget> findByScopeTypeAndScopeId(BudgetScopeType scopeType, String scopeId);

    List<Budget> findByEnabledTrue();

    List<Budget> findAllByOrderByScopeTypeAscScopeIdAsc();
}
