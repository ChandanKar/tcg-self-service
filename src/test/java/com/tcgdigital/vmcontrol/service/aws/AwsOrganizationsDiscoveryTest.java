package com.tcgdigital.vmcontrol.service.aws;

import com.tcgdigital.vmcontrol.dto.OrganizationAccountCandidateDTO;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AwsAccount;
import com.tcgdigital.vmcontrol.repository.AwsAccountRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.organizations.OrganizationsClient;
import software.amazon.awssdk.services.organizations.model.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Listing an organization's member accounts for import (E28-T08), with a mocked Organizations client. */
class AwsOrganizationsDiscoveryTest {

    private AwsAccountRepository repository;
    private OrganizationsClient organizations;
    private AwsAccountAdminService service;

    @BeforeEach
    void setUp() {
        repository = mock(AwsAccountRepository.class);
        organizations = mock(OrganizationsClient.class);
        AwsClientFactory factory = mock(AwsClientFactory.class);
        AwsAccount payer = new AwsAccount();
        payer.setAwsAccountRef("payer-ref");
        when(repository.findFirstByIsBillingAccountTrue()).thenReturn(Optional.of(payer));
        when(factory.organizations("payer-ref")).thenReturn(organizations);
        when(repository.findByAccountNumber(any())).thenReturn(Optional.empty());
        service = new AwsAccountAdminService(repository, mock(EnvironmentRepository.class), factory, mock(AuditService.class));
        ReflectionTestUtils.setField(service, "organizationsEnabled", true);
        ReflectionTestUtils.setField(service, "memberRoleName", "VmControlAccess");
    }

    private static Account account(String id, String name, AccountStatus status) {
        return Account.builder().id(id).name(name).email(name + "@example.com").status(status).build();
    }

    @Test
    void everyActiveMemberOfEveryPageIsListedWithAProposedRoleAndTheRegisteredFlag() {
        when(organizations.listAccounts(any(ListAccountsRequest.class))).thenAnswer(inv -> {
            ListAccountsRequest r = inv.getArgument(0);
            return r.nextToken() == null
                    ? ListAccountsResponse.builder().nextToken("p2").accounts(account("111111111111", "shared", AccountStatus.ACTIVE),
                            account("222222222222", "closed", AccountStatus.SUSPENDED)).build()
                    : ListAccountsResponse.builder().accounts(account("333333333333", "payments", AccountStatus.ACTIVE)).build();
        });
        when(repository.findByAccountNumber("333333333333")).thenReturn(Optional.of(new AwsAccount()));

        List<OrganizationAccountCandidateDTO> candidates = service.listOrganizationCandidates();

        assertThat(candidates).extracting(OrganizationAccountCandidateDTO::accountNumber).containsExactly("111111111111", "333333333333");
        assertThat(candidates.get(0).proposedRoleArn()).isEqualTo("arn:aws:iam::111111111111:role/VmControlAccess");
        assertThat(candidates.get(0).email()).isEqualTo("shared@example.com");
        assertThat(candidates.get(0).alreadyRegistered()).isFalse();
        assertThat(candidates.get(1).alreadyRegistered()).isTrue();
        verify(organizations, times(2)).listAccounts(any(ListAccountsRequest.class));
    }

    @Test
    void anAccountOutsideAnOrganizationOrWithoutAccessGetsAClearMessage() {
        when(organizations.listAccounts(any(ListAccountsRequest.class)))
                .thenThrow(AwsOrganizationsNotInUseException.builder().message("not in use").build())
                .thenThrow(AccessDeniedException.builder().message("denied").build());

        assertThatThrownBy(service::listOrganizationCandidates).isInstanceOf(ValidationException.class)
                .hasMessageContaining("not part of an AWS organization");
        assertThatThrownBy(service::listOrganizationCandidates).isInstanceOf(ValidationException.class)
                .hasMessageContaining("organizations:ListAccounts");
    }

    @Test
    void withDiscoveryOffNothingIsCalled() {
        ReflectionTestUtils.setField(service, "organizationsEnabled", false);
        assertThatThrownBy(service::listOrganizationCandidates).isInstanceOf(ValidationException.class)
                .hasMessageContaining("disabled");
        verifyNoInteractions(organizations);
    }
}
