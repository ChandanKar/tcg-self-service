package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.ChargebackStatement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Frozen chargeback statements (E23-T07). */
public interface ChargebackStatementRepository extends JpaRepository<ChargebackStatement, String> {

    Optional<ChargebackStatement> findByMonth(String month);
}
