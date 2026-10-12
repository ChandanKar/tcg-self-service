package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.model.ProvisioningStatus;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentTemplateRepository;
import com.tcgdigital.vmcontrol.repository.ProvisioningRequestRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.EnvironmentAccessService;
import com.tcgdigital.vmcontrol.service.EnvironmentService;
import com.tcgdigital.vmcontrol.service.VmDiscoveryService;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The success transaction on MySQL (E32-T04): an approved request is started, then on success the
 * environment exists under the requested name, the request is READY with its destroy time, and
 * the requester holds ADMIN access to it. The engine is a mock; everything else is real.
 */
class ProvisioningExecutorIntegrationTest extends AbstractIntegrationTest {

    @Autowired private EnvironmentTemplateRepository templates;
    @Autowired private ProvisioningRequestRepository requests;
    @Autowired private TemplateService templateService;
    @Autowired private EnvironmentService environmentService;
    @Autowired private EnvironmentAccessService accessService;
    @Autowired private VmDiscoveryService discoveryService;
    @Autowired private AuditService auditService;
    @Autowired private SettingsService settings;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository accessRepository;

    private User admin;

    @BeforeEach
    void switchOn() {
        admin = newUser("prov.admin@example.com", true, false);
        settings.set("provisioning.enabled", "true", admin.getUserId());
    }

    @AfterEach
    void switchOff() {
        settings.reset("provisioning.enabled", admin.getUserId());
    }

    @Test
    void anApprovedRequestBecomesAReadyEnvironmentOwnedByItsRequester() {
        User requester = newUser("requester@example.com", false, false);
        EnvironmentTemplate t = new EnvironmentTemplate();
        t.setName("Dev stack");
        t.setEngine(ProvisioningEngineType.SERVICE_CATALOG);
        t.setEngineRef("{\"productId\":\"prod-1\",\"provisioningArtifactId\":\"pa-1\"}");
        t.setParameterSchema("[]");
        t.setRegion("us-east-1");
        t = templates.saveAndFlush(t);
        ProvisioningRequest r = new ProvisioningRequest();
        r.setTemplateId(t.getTemplateId());
        r.setRequestedByUserId(requester.getUserId());
        r.setReviewedByUserId(admin.getUserId());
        r.setEnvironmentName("feature-int");
        r.setTtlHours(72);
        r.setStatus(ProvisioningStatus.APPROVED);
        r = requests.saveAndFlush(r);

        ProvisioningEngine engine = mock(ProvisioningEngine.class);
        when(engine.type()).thenReturn(ProvisioningEngineType.SERVICE_CATALOG);
        when(engine.provision(any(), any())).thenReturn("rec-int");
        when(engine.poll(any(), any())).thenReturn(new ProvisioningProgress(ProvisioningProgress.State.SUCCEEDED, "pp-int", "Succeeded"));
        ProvisioningExecutor executor = new ProvisioningExecutor(requests, templateService, new ProvisioningEngineRegistry(List.of(engine)),
                environmentService, accessService, discoveryService, userRepository, auditService, new ObjectMapper(),
                new TransactionTemplate(transactionManager));

        executor.run(); // starts it, then polls it in the same pass and finds it built
        executor.run(); // nothing left to do: it is not started twice
        org.mockito.Mockito.verify(engine, org.mockito.Mockito.times(1)).provision(any(), any());

        ProvisioningRequest done = requests.findById(r.getRequestId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ProvisioningStatus.READY);
        assertThat(done.getEngineResourceId()).isEqualTo("pp-int");
        assertThat(done.getDestroyAt()).isNotNull();
        Environment env = environmentRepository.findById(done.getEnvironmentId()).orElseThrow();
        assertThat(env.getName()).isEqualTo("feature-int");
        assertThat(env.getMetadata()).contains("us-east-1").contains(r.getRequestId());
        List<EnvironmentAccess> grants = accessRepository.findAll().stream()
                .filter(a -> requester.getUserId().equals(a.getUser().getUserId())).toList();
        assertThat(grants).anySatisfy(a -> assertThat(a.getAccessLevel()).isEqualTo(AccessLevel.ADMIN));
    }
}
