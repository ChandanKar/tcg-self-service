package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The AWS account registry (E28-T03): which account an environment or VM belongs to, and the
 * default account. A VM's own account wins, then its environment's, then the default. Once the
 * app is ready, the default account's number is filled in from STS GetCallerIdentity in the
 * background; a failure is only logged.
 */
@Service
public class AwsAccountService {

    private static final Logger log = LoggerFactory.getLogger(AwsAccountService.class);

    private final AwsAccountRepository repository;
    private final AwsClientFactory clientFactory;

    @Value("${aws.accounts.resolve-default-number:true}")
    private boolean resolveDefaultNumber = true;

    public AwsAccountService(AwsAccountRepository repository, AwsClientFactory clientFactory) {
        this.repository = repository;
        this.clientFactory = clientFactory;
    }

    public AwsAccount getDefault() {
        return repository.findByIsDefaultTrue()
                .orElseThrow(() -> new IllegalStateException("The default AWS account row is missing (V70)"));
    }

    public String resolveRef(Vm vm) {
        if (vm == null) {
            return AwsAccount.DEFAULT_REF;
        }
        if (vm.getAwsAccountRef() != null) {
            return vm.getAwsAccountRef();
        }
        Environment env = vm.getGroup() != null ? vm.getGroup().getEnvironment() : null;
        return resolveRef(env);
    }

    public String resolveRef(Environment environment) {
        return environment != null && environment.getAwsAccountRef() != null
                ? environment.getAwsAccountRef() : AwsAccount.DEFAULT_REF;
    }

    public List<AwsAccount> listActive() {
        return repository.findByIsActiveTrueOrderByDisplayNameAsc();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (resolveDefaultNumber && clientFactory.isConfigured()) {
            CompletableFuture.runAsync(this::resolveDefaultAccountNumber);
        }
    }

    /** Stores the default account's number from STS when it is not known yet; never throws. */
    public void resolveDefaultAccountNumber() {
        try {
            AwsAccount account = getDefault();
            if (account.getAccountNumber() != null || !clientFactory.isConfigured()) {
                return;
            }
            String number = clientFactory.sts(null).getCallerIdentity().account();
            if (number != null && !number.isBlank()) {
                account.setAccountNumber(number);
                repository.save(account);
                log.info("Default AWS account resolved to {}", number);
            }
        } catch (Exception e) {
            log.warn("Could not resolve the default AWS account number from STS: {}", e.getMessage());
        }
    }
}
