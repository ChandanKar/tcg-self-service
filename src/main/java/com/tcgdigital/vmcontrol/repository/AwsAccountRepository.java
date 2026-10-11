package com.tcgdigital.vmcontrol.repository;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AwsAccountRepository extends JpaRepository<AwsAccount, String> {

    Optional<AwsAccount> findByIsDefaultTrue();

    List<AwsAccount> findByIsActiveTrueOrderByDisplayNameAsc();

    Optional<AwsAccount> findByAccountNumber(String accountNumber);

    Optional<AwsAccount> findFirstByIsBillingAccountTrue();
}
