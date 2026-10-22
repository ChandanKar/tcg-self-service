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

    @Value("${aws.multi-account.enabled:false}")
    private boolean multiAccountEnabled;

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

    /** The account Cost Explorer and Organizations are read from (E28-T11): the billing one, else the default. */
    public String getBillingAccountRef() {
        return repository.findFirstByIsBillingAccountTrue().filter(AwsAccount::isActive)
                .map(AwsAccount::getAwsAccountRef).orElse(AwsAccount.DEFAULT_REF);
    }

    public List<AwsAccount> listActive() {
        return repository.findByIsActiveTrueOrderByDisplayNameAsc();
    }

    /** The enabled regions of each account (U19-T01); absent in plain unit tests, then the configured regions are used. */
    private AwsRegionService regionService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setRegionService(AwsRegionService regionService) {
        this.regionService = regionService;
    }

    /** An admin's override from Settings (aws.regions.exclude); absent in plain unit tests. */
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSettings(com.tcgdigital.vmcontrol.service.settings.SettingsService settings) {
        this.settings = settings;
    }

    @Value("${aws.regions.exclude:}")
    private String excludeRegions = "";

    /**
     * Where to scan (E28-T06, U19-T01): every active account (only the default one with multi-account
     * off; members would otherwise be read with the base credentials, i.e. the same account again) in
     * every region it has enabled in AWS, read once a day, opt-in regions included. {@code limit} (a
     * job's own region list, e.g. EKS_SYNC_REGIONS) and the account's own regions narrow that when set;
     * aws.regions.exclude and regions that refused access in the last day are left out. When an
     * account's enabled regions cannot be read (no AWS access yet), its own regions are used, else
     * {@code limit}, else aws.region.
     */
    public List<com.tcgdigital.vmcontrol.service.CloudTarget> scanTargets(List<String> limit) {
        List<AwsAccount> accounts = multiAccountEnabled ? listActive()
                : repository.findByIsDefaultTrue().map(List::of).orElse(List.of());
        java.util.Set<String> exclude = excludedRegions();
        List<String> jobLimit = limit == null ? List.of() : limit;
        String fallback = clientFactory.defaultRegion();
        List<com.tcgdigital.vmcontrol.service.CloudTarget> targets = new java.util.ArrayList<>();
        for (AwsAccount account : accounts) {
            String ref = account.getAwsAccountRef();
            java.util.Optional<List<String>> enabled = regionService == null ? java.util.Optional.empty() : regionService.enabledRegions(ref);
            java.util.Set<String> refused = regionService == null ? java.util.Set.of() : regionService.refusedRegions(ref);
            regionsFor(enabled, account.getRegionList(), jobLimit, exclude, refused, fallback)
                    .forEach(r -> targets.add(com.tcgdigital.vmcontrol.service.CloudTarget.of(ref, r)));
        }
        if (accounts.isEmpty()) {
            regionsFor(java.util.Optional.empty(), List.of(), jobLimit, exclude, java.util.Set.of(), fallback)
                    .forEach(r -> targets.add(com.tcgdigital.vmcontrol.service.CloudTarget.of(r)));
        }
        return targets;
    }

    /**
     * One account's regions to scan: its enabled regions narrowed by its own list and the job's list,
     * else (enabled regions unknown) its own list, else the job's, else the fallback; then without the
     * excluded and refused regions.
     */
    static List<String> regionsFor(java.util.Optional<List<String>> enabled, List<String> accountLimit, List<String> jobLimit,
                                   java.util.Set<String> exclude, java.util.Set<String> refused, String fallback) {
        List<String> regions;
        if (enabled.isPresent()) {
            regions = enabled.get().stream()
                    .filter(r -> accountLimit.isEmpty() || accountLimit.contains(r))
                    .filter(r -> jobLimit.isEmpty() || jobLimit.contains(r))
                    .toList();
        } else if (!accountLimit.isEmpty()) {
            regions = accountLimit;
        } else if (!jobLimit.isEmpty()) {
            regions = jobLimit;
        } else {
            regions = fallback == null || fallback.isBlank() ? List.of() : List.of(fallback);
        }
        return regions.stream().map(String::trim).filter(r -> !r.isEmpty())
                .filter(r -> !exclude.contains(r) && !refused.contains(r)).distinct().toList();
    }

    /** aws.regions.exclude: the Settings override, else the configured value. */
    java.util.Set<String> excludedRegions() {
        String value = settings == null ? excludeRegions : settings.getString("aws.regions.exclude", excludeRegions);
        return parseRegions(value);
    }

    /** A comma-separated region list, trimmed and lower-cased; blanks dropped. */
    public static java.util.Set<String> parseRegions(String value) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (value != null) {
            for (String r : value.split(",")) {
                if (!r.isBlank()) {
                    out.add(r.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return out;
    }

    /**
     * Records a scan's failure: when AWS refused the call (permissions, an SCP, a region not opted in),
     * the region is left out for a day instead of being called on every run.
     */
    public void reportScanFailure(com.tcgdigital.vmcontrol.service.CloudTarget target, Throwable e) {
        if (regionService != null && target != null && AwsRegionService.isRefusal(e)) {
            try {
                regionService.recordRefusal(target.accountRef(), target.region(), e.getMessage());
            } catch (Exception recordFailed) {
                log.warn("Could not record the refused region {}: {}", target, recordFailed.getMessage());
            }
        }
    }

    /** Display names by account ref, in one query. */
    public java.util.Map<String, String> namesByRef() {
        java.util.Map<String, String> names = new java.util.HashMap<>();
        repository.findAll().forEach(a -> names.put(a.getAwsAccountRef(), a.getDisplayName()));
        return names;
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
