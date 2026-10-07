package com.tcgdigital.vmcontrol.support;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.repository.EnvironmentAccessRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.repository.VmGroupRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.AwsCloudInventoryProviderService;
import com.tcgdigital.vmcontrol.service.AwsCloudMetricsProviderService;
import com.tcgdigital.vmcontrol.service.AwsCloudProviderService;
import com.tcgdigital.vmcontrol.service.CloudProviderFactory;
import com.tcgdigital.vmcontrol.service.ComputeOptimizerService;
import com.tcgdigital.vmcontrol.service.CostExplorerBillingService;
import com.tcgdigital.vmcontrol.service.CostExplorerTagActivationService;
import com.tcgdigital.vmcontrol.service.Ec2Service;
import com.tcgdigital.vmcontrol.service.EksCloudProviderService;
import com.tcgdigital.vmcontrol.service.GraphDirectoryService;
import com.tcgdigital.vmcontrol.service.ReservationCoverageService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.jdbc.Sql;

import java.time.Duration;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Base class for every test that starts the Spring context against a real MySQL database.
 *
 * <p>Database: a local schema when {@code TEST_DB_URL} is set (see build.gradle), otherwise a
 * Testcontainers MySQL. Scheduled jobs are off ({@code app.scheduling.enabled=false} in the
 * test properties) and every bean that would call AWS or Microsoft Graph is a Mockito mock, so
 * no test depends on timing or on a real cloud. Data is reset before each test method by
 * {@code reset-test-data.sql}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("db")
@Sql(scripts = "/db/reset-test-data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
public abstract class AbstractIntegrationTest {

    @MockitoBean protected CloudProviderFactory cloudProviderFactory;
    @MockitoBean protected AwsCloudProviderService awsCloudProviderService;
    @MockitoBean protected EksCloudProviderService eksCloudProviderService;
    @MockitoBean protected AwsCloudInventoryProviderService awsCloudInventoryProviderService;
    @MockitoBean protected AwsCloudMetricsProviderService awsCloudMetricsProviderService;
    @MockitoBean protected Ec2Service ec2Service;
    @MockitoBean protected ComputeOptimizerService computeOptimizerService;
    @MockitoBean protected CostExplorerBillingService costExplorerBillingService;
    @MockitoBean protected CostExplorerTagActivationService costExplorerTagActivationService;
    @MockitoBean protected ReservationCoverageService reservationCoverageService;
    @MockitoBean protected GraphDirectoryService graphDirectoryService;

    @Autowired protected EnvironmentRepository environmentRepository;
    @Autowired protected VmGroupRepository vmGroupRepository;
    @Autowired protected VmRepository vmRepository;
    @Autowired protected UserRepository userRepository;
    @Autowired protected EnvironmentAccessRepository environmentAccessRepository;

    @BeforeEach
    void stubCloudProviders() {
        when(cloudProviderFactory.getService(CloudProvider.AWS)).thenReturn(awsCloudProviderService);
        when(cloudProviderFactory.getService(CloudProvider.AWS_EKS)).thenReturn(eksCloudProviderService);
        when(cloudProviderFactory.isProviderAvailable(any())).thenReturn(true);
        when(awsCloudProviderService.getProvider()).thenReturn(CloudProvider.AWS);
        when(eksCloudProviderService.getProvider()).thenReturn(CloudProvider.AWS_EKS);
    }

    // ---------------------------------------------------------------- data helpers

    protected Environment newEnvironment(String name) {
        Environment env = new Environment();
        env.setEnvironmentId(UUID.randomUUID().toString());
        env.setName(name + "-" + shortId());
        env.setDisplayName(name);
        env.setIsActive(true);
        return environmentRepository.saveAndFlush(env);
    }

    protected VmGroup newGroup(Environment env, String name) {
        VmGroup group = new VmGroup();
        group.setGroupId(UUID.randomUUID().toString());
        group.setEnvironment(env);
        group.setName(name + "-" + shortId());
        group.setDisplayName(name);
        group.setSequencePosition((int) vmGroupRepository.count() + 1);
        return vmGroupRepository.saveAndFlush(group);
    }

    protected Vm newVm(VmGroup group, String name, VmStatus status) {
        Vm vm = new Vm();
        vm.setVmId(UUID.randomUUID().toString());
        vm.setGroup(group);
        vm.setName(name + "-" + shortId());
        vm.setDisplayName(name);
        vm.setProvider(CloudProvider.AWS);
        vm.setRegion("ap-south-1");
        vm.setProviderVmId("i-" + UUID.randomUUID().toString().replace("-", "").substring(0, 17));
        vm.setSequencePosition((int) vmRepository.count() + 1);
        vm.setStatus(status);
        vm.setIsActive(true);
        return vmRepository.saveAndFlush(vm);
    }

    protected User newUser(String email, boolean admin, boolean envAdmin) {
        User user = User.fromAzureAd("oid-" + UUID.randomUUID(), email, email.split("@")[0]);
        user.setAdmin(admin);
        user.setEnvAdmin(envAdmin);
        return userRepository.saveAndFlush(user);
    }

    /**
     * Grant {@code level} on an environment ({@code ENVIRONMENT} scope, scopeId = environment id)
     * or on one group ({@code GROUP} scope, scopeId = group id). The user is recorded as grantor.
     */
    protected EnvironmentAccess grant(User user, AccessScopeType scope, String scopeId, AccessLevel level) {
        Environment env = scope == AccessScopeType.GROUP
                ? vmGroupRepository.findById(scopeId).orElseThrow().getEnvironment()
                : environmentRepository.findById(scopeId).orElseThrow();
        EnvironmentAccess access = EnvironmentAccess.create(env, user, level, user);
        access.setScopeType(scope);
        access.setScopeId(scopeId);
        return environmentAccessRepository.saveAndFlush(access);
    }

    /** Retry an assertion for up to 5 seconds — for work done on the async executors. */
    protected void awaitAsync(Runnable assertion) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(assertion::run);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
