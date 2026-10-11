package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.dto.AwsAccountDTO;
import com.tcgdigital.vmcontrol.dto.ImportAwsAccountsDTO;
import com.tcgdigital.vmcontrol.dto.OrganizationAccountCandidateDTO;
import com.tcgdigital.vmcontrol.dto.UpsertAwsAccountDTO;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin operations on the AWS account registry (E28-T07): register a member account by its role
 * ARN (the 12-digit number is taken from the ARN), edit, deactivate when no active environment
 * uses it, and verify the connection with STS GetCallerIdentity through the account's own
 * credentials. Every change is audited; changed or deactivated accounts drop their cached clients.
 */
@Service
public class AwsAccountAdminService {

    private static final Pattern ARN_ACCOUNT = Pattern.compile("^arn:aws(?:-[a-z]+)?:iam::(\\d{12}):role/");
    private static final int MAX_ERROR = 1000;

    private final AwsAccountRepository repository;
    private final EnvironmentRepository environmentRepository;
    private final AwsClientFactory clientFactory;
    private final AuditService auditService;

    @Value("${aws.multi-account.enabled:false}")
    private boolean multiAccountEnabled;

    @Value("${aws.organizations.enabled:false}")
    private boolean organizationsEnabled;

    @Value("${aws.organizations.member-role-name:VmControlAccess}")
    private String memberRoleName = "VmControlAccess";

    public AwsAccountAdminService(AwsAccountRepository repository, EnvironmentRepository environmentRepository,
                                  AwsClientFactory clientFactory, AuditService auditService) {
        this.repository = repository;
        this.environmentRepository = environmentRepository;
        this.clientFactory = clientFactory;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AwsAccountDTO> list() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : environmentRepository.countActiveByAwsAccountRef()) {
            if (row[0] != null) {
                counts.put((String) row[0], ((Number) row[1]).longValue());
            }
        }
        return repository.findAll().stream()
                .sorted((a, b) -> a.isDefault() != b.isDefault() ? (a.isDefault() ? -1 : 1)
                        : a.getDisplayName().compareToIgnoreCase(b.getDisplayName()))
                .map(a -> AwsAccountDTO.from(a, counts.getOrDefault(a.getAwsAccountRef(), 0L)))
                .toList();
    }

    @Transactional
    public AwsAccountDTO create(String actorId, UpsertAwsAccountDTO request) {
        if (request.roleArn() == null || request.roleArn().isBlank()) {
            throw new ValidationException("roleArn is required for a member account");
        }
        AwsAccount account = new AwsAccount();
        account.setSource(AwsAccount.Source.MANUAL);
        apply(account, request);
        AwsAccount saved = repository.save(account);
        clearOtherBillingAccounts(saved, request);
        auditService.logAction(actorId, AuditAction.AWS_ACCOUNT_CREATED, "aws_account", saved.getAwsAccountRef(),
                saved.getDisplayName(), "Account " + saved.getAccountNumber() + " via " + saved.getRoleArn());
        return toDto(saved);
    }

    /** Creates an account found in AWS Organizations (E28-T08); same checks as {@link #create}. */
    @Transactional
    public AwsAccountDTO createImported(String actorId, UpsertAwsAccountDTO request) {
        AwsAccountDTO dto = create(actorId, request);
        AwsAccount account = repository.findById(dto.awsAccountRef()).orElseThrow();
        account.setSource(AwsAccount.Source.ORGANIZATIONS);
        return toDto(repository.save(account));
    }

    @Transactional
    public AwsAccountDTO update(String actorId, String ref, UpsertAwsAccountDTO request) {
        AwsAccount account = find(ref);
        if (!account.isDefault() && (request.roleArn() == null || request.roleArn().isBlank())) {
            throw new ValidationException("roleArn is required for a member account");
        }
        apply(account, request);
        AwsAccount saved = repository.save(account);
        clearOtherBillingAccounts(saved, request);
        clientFactory.evict(ref);
        auditService.logAction(actorId, AuditAction.AWS_ACCOUNT_UPDATED, "aws_account", ref, saved.getDisplayName(),
                "Role " + saved.getRoleArn() + ", regions " + saved.getRegions());
        return toDto(saved);
    }

    @Transactional
    public void deactivate(String actorId, String ref) {
        AwsAccount account = find(ref);
        if (account.isDefault()) {
            throw new ConflictException("The default account cannot be deactivated");
        }
        long environments = environmentRepository.countByAwsAccountRefAndIsActiveTrue(ref);
        if (environments > 0) {
            throw new ConflictException("Account '" + account.getDisplayName() + "' still has " + environments
                    + " active environment(s); move or deactivate them first");
        }
        account.setActive(false);
        repository.save(account);
        clientFactory.evict(ref);
        auditService.logAction(actorId, AuditAction.AWS_ACCOUNT_DEACTIVATED, "aws_account", ref, account.getDisplayName(), null);
    }

    /** Calls STS GetCallerIdentity with the account's credentials and records the outcome. */
    @Transactional
    public AwsAccountDTO verify(String actorId, String ref) {
        AwsAccount account = find(ref);
        if (!account.isDefault() && !multiAccountEnabled) {
            throw new ValidationException("Multi-account is disabled (aws.multi-account.enabled)");
        }
        String outcome;
        try {
            String number = clientFactory.sts(ref, null).getCallerIdentity().account();
            account.setLastVerifiedAt(Timestamp.from(Instant.now()));
            if (account.getAccountNumber() == null) {
                account.setAccountNumber(number);
                account.setLastError(null);
            } else if (!account.getAccountNumber().equals(number)) {
                account.setLastError("Role resolved to account " + number);
            } else {
                account.setLastError(null);
            }
            outcome = account.getLastError() == null ? "Verified" : account.getLastError();
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            account.setLastError(message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) : message);
            outcome = "Failed: " + account.getLastError();
        }
        AwsAccount saved = repository.save(account);
        auditService.logAction(actorId, AuditAction.AWS_ACCOUNT_VERIFIED, "aws_account", ref, saved.getDisplayName(), outcome);
        return toDto(saved);
    }

    /**
     * The organization's ACTIVE member accounts (E28-T08), read with the billing account's
     * credentials, each with a proposed role ARN and whether it is registered already.
     */
    @Transactional(readOnly = true)
    public List<OrganizationAccountCandidateDTO> listOrganizationCandidates() {
        if (!organizationsEnabled) {
            throw new ValidationException("Organizations discovery is disabled (aws.organizations.enabled)");
        }
        String billingRef = repository.findFirstByIsBillingAccountTrue().map(AwsAccount::getAwsAccountRef).orElse(AwsAccount.DEFAULT_REF);
        List<OrganizationAccountCandidateDTO> candidates = new ArrayList<>();
        try {
            var organizations = clientFactory.organizations(billingRef);
            String token = null;
            do {
                var page = organizations.listAccounts(software.amazon.awssdk.services.organizations.model.ListAccountsRequest.builder()
                        .nextToken(token).build());
                for (var account : page.accounts()) {
                    if (account.status() != software.amazon.awssdk.services.organizations.model.AccountStatus.ACTIVE) {
                        continue;
                    }
                    candidates.add(new OrganizationAccountCandidateDTO(account.id(), account.name(), account.email(),
                            "arn:aws:iam::" + account.id() + ":role/" + memberRoleName,
                            repository.findByAccountNumber(account.id()).isPresent()));
                }
                token = page.nextToken();
            } while (token != null && !token.isEmpty());
        } catch (software.amazon.awssdk.services.organizations.model.AwsOrganizationsNotInUseException e) {
            throw new ValidationException("The billing account is not part of an AWS organization; add member accounts by role ARN instead");
        } catch (software.amazon.awssdk.services.organizations.model.AccessDeniedException e) {
            throw new ValidationException("The billing account may not list the organization's accounts: it must be the management "
                    + "or a delegated administrator account with organizations:ListAccounts");
        }
        return candidates;
    }

    /** Registers the chosen organization accounts (source ORGANIZATIONS); registered numbers are skipped. */
    @Transactional
    public ImportAwsAccountsDTO.Result importAccounts(String actorId, ImportAwsAccountsDTO.Request request) {
        List<AwsAccountDTO> created = new ArrayList<>();
        List<ImportAwsAccountsDTO.Skipped> skipped = new ArrayList<>();
        for (ImportAwsAccountsDTO.Item item : request.accounts()) {
            if (repository.findByAccountNumber(item.accountNumber()).isPresent()) {
                skipped.add(new ImportAwsAccountsDTO.Skipped(item.accountNumber(), "already registered"));
                continue;
            }
            if (!item.roleArn().contains(":iam::" + item.accountNumber() + ":")) {
                skipped.add(new ImportAwsAccountsDTO.Skipped(item.accountNumber(), "the role ARN is in another account"));
                continue;
            }
            created.add(createImported(actorId, new UpsertAwsAccountDTO(item.displayName(), item.roleArn(), item.externalId(),
                    item.regions(), false)));
        }
        return new ImportAwsAccountsDTO.Result(created, skipped);
    }

    private void apply(AwsAccount account, UpsertAwsAccountDTO request) {
        account.setDisplayName(request.displayName().trim());
        if (request.roleArn() != null && !request.roleArn().isBlank()) {
            Matcher m = ARN_ACCOUNT.matcher(request.roleArn());
            if (!m.find()) {
                throw new ValidationException("roleArn must be an IAM role ARN");
            }
            String number = m.group(1);
            repository.findByAccountNumber(number)
                    .filter(other -> !other.getAwsAccountRef().equals(account.getAwsAccountRef()))
                    .ifPresent(other -> {
                        throw new ConflictException("Account " + number + " is already registered as '" + other.getDisplayName() + "'");
                    });
            account.setRoleArn(request.roleArn());
            account.setAccountNumber(number);
        }
        if (request.externalId() != null && !request.externalId().isBlank()) {
            account.setExternalId(request.externalId());
        }
        account.setRegions(request.regions() == null || request.regions().isEmpty() ? null : String.join(",", request.regions()));
        if (request.isBillingAccount() != null) {
            account.setBillingAccount(request.isBillingAccount());
        }
    }

    /** Only one billing account: setting it on one clears it on the others, in the same transaction. */
    private void clearOtherBillingAccounts(AwsAccount saved, UpsertAwsAccountDTO request) {
        if (!Boolean.TRUE.equals(request.isBillingAccount())) {
            return;
        }
        repository.findAll().stream()
                .filter(a -> a.isBillingAccount() && !a.getAwsAccountRef().equals(saved.getAwsAccountRef()))
                .forEach(a -> {
                    a.setBillingAccount(false);
                    repository.save(a);
                });
    }

    private AwsAccount find(String ref) {
        return repository.findById(ref).orElseThrow(() -> new ResourceNotFoundException("AWS account", ref));
    }

    private AwsAccountDTO toDto(AwsAccount account) {
        return AwsAccountDTO.from(account, environmentRepository.countByAwsAccountRefAndIsActiveTrue(account.getAwsAccountRef()));
    }
}
