package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;

/**
 * Where a cloud call goes (E28-T05): the AWS account (its registry ref) and the region. Calls
 * that once took only a region take this, so a VM in a member account is reached through that
 * account's own client. A null ref means the default account.
 */
public record CloudTarget(String accountRef, String region) {

    public CloudTarget {
        if (accountRef == null || accountRef.isBlank()) {
            accountRef = AwsAccount.DEFAULT_REF;
        }
    }

    public static CloudTarget of(String accountRef, String region) {
        return new CloudTarget(accountRef, region);
    }

    /** A region of the default account. */
    public static CloudTarget of(String region) {
        return new CloudTarget(AwsAccount.DEFAULT_REF, region);
    }

    /** A VM's account (its own, else its environment's, else the default) and region. */
    public static CloudTarget of(Vm vm) {
        String ref = vm.getAwsAccountRef();
        if (ref == null && vm.getGroup() != null && org.hibernate.Hibernate.isInitialized(vm.getGroup())) {
            Environment env = vm.getGroup().getEnvironment();
            ref = env != null && org.hibernate.Hibernate.isInitialized(env) ? env.getAwsAccountRef() : null;
        }
        return new CloudTarget(ref, vm.getRegion());
    }

    public boolean isDefaultAccount() {
        return AwsAccount.DEFAULT_REF.equals(accountRef);
    }

    /** "provider grouping" key: accountRef:region. */
    public String key() {
        return accountRef + ":" + region;
    }

    @Override
    public String toString() {
        return isDefaultAccount() ? String.valueOf(region) : region + " (account " + accountRef + ")";
    }
}
