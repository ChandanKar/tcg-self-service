package com.tcgdigital.vmcontrol.service.discovery;

import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
import com.tcgdigital.vmcontrol.dto.FoundInAwsDTO;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.DiscoveryIgnore;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.DiscoveryIgnoreRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.CloudTarget;
import com.tcgdigital.vmcontrol.service.Ec2Service;
import com.tcgdigital.vmcontrol.service.EksCloudProviderService;
import com.tcgdigital.vmcontrol.service.aws.AwsAccountService;
import com.tcgdigital.vmcontrol.service.support.InstanceRefCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Found in AWS (U19-T03): what no environment holds and nobody ignored, in every account and region,
 * with the regions that could not be read; ignoring hides it; the sweep is kept ten minutes.
 */
class DiscoveryInboxServiceTest {

    private static final CloudTarget EU = CloudTarget.of("eu-west-1");
    private static final CloudTarget ME = CloudTarget.of("acct-b", "me-central-1");
    private static final CloudTarget US = CloudTarget.of("us-east-1");

    private AwsResourceSweep sweep;
    private VmRepository vms;
    private EnvironmentRepository environments;
    private DiscoveryIgnoreRepository ignores;
    private InstanceRefCodec refs;
    private DiscoveryInboxService inbox;
    private Instant now = Instant.parse("2026-10-22T08:00:00Z");

    static Ec2InstanceInfo instance(String id, String name, String state, Map<String, String> extraTags) {
        Map<String, String> tags = new HashMap<>(extraTags);
        if (name != null) tags.put("Name", name);
        return new Ec2InstanceInfo(id, "t3.medium", state, "10.0.1.15", null, null, null, "vpc-1", "subnet-1", "eu-west-1a",
                "ami-1", null, Instant.parse("2026-01-01T00:00:00Z"), "linux", "x86_64", "ebs", "/dev/xvda", "hvm", "nitro",
                null, List.of(), List.of(), tags, false, "disabled", "default", 1, 2, "eu-west-1");
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sweep = mock(AwsResourceSweep.class);
        vms = mock(VmRepository.class);
        environments = mock(EnvironmentRepository.class);
        ignores = mock(DiscoveryIgnoreRepository.class);
        refs = new InstanceRefCodec();
        org.springframework.test.util.ReflectionTestUtils.setField(refs, "ttlMinutes", 30L);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(refs, "init");
        AwsAccountService accounts = mock(AwsAccountService.class);
        when(accounts.namesByRef()).thenReturn(Map.of(EU.accountRef(), "Default account", "acct-b", "Payments"));

        Map<CloudTarget, List<Ec2InstanceInfo>> found = new LinkedHashMap<>();
        found.put(EU, List.of(instance("i-0000000000000free", "web-01", "running", Map.of()),
                instance("i-00000000000000reg", "registered", "running", Map.of()),
                instance("i-0000000000000gone", "old", "terminated", Map.of()),
                instance("i-00000000000tagged", null, "stopped", Map.of("tcg:environment", "crm-uat"))));
        found.put(ME, List.of(instance("i-000000000000ignor", "ignored-one", "running", Map.of())));
        doReturn(new AwsResourceSweep.Result<>(found, List.of(new AwsResourceSweep.RegionOutcome("EC2", US, now, 0, "connection reset", false))))
                .when(sweep).everywhere(eq("EC2 instances (inbox)"), anyList(), eq("ec2:instance"), any());
        Map<CloudTarget, List<String>> clusters = new LinkedHashMap<>();
        clusters.put(EU, List.of("data-platform", "payments-eks"));
        doReturn(new AwsResourceSweep.Result<>(clusters, List.of()))
                .when(sweep).everywhere(eq("EKS clusters (inbox)"), anyList(), eq("eks:cluster"), any());

        Vm registered = new Vm();
        registered.setProviderVmId("i-00000000000000reg");
        when(vms.findByProviderVmIdsFetchEnvironment(eq(CloudProvider.AWS), anyList())).thenReturn(List.of(registered));
        Environment crm = new Environment();
        crm.setEnvironmentId("env-crm");
        crm.setName("crm-uat");
        crm.setDisplayName("CRM UAT");
        when(environments.findByIsActiveTrue()).thenReturn(List.of(crm));
        when(environments.existsByEksClusterName("payments-eks")).thenReturn(true);
        DiscoveryIgnore ignored = new DiscoveryIgnore();
        ignored.setResourceKind(DiscoveryIgnore.Kind.EC2);
        ignored.setAwsAccountRef("acct-b");
        ignored.setRegionCode("me-central-1");
        ignored.setResourceId("i-000000000000ignor");
        when(ignores.findAll()).thenReturn(List.of(ignored));
        when(ignores.count()).thenReturn(1L);

        inbox = new DiscoveryInboxService(sweep, mock(Ec2Service.class), mock(EksCloudProviderService.class), accounts, vms,
                environments, ignores, refs, mock(AuditService.class));
        inbox.setClock(Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void onlyWhatNoEnvironmentHoldsAndNobodyIgnoredIsListed() {
        FoundInAwsDTO dto = inbox.inbox(false, "admin-1");

        assertThat(dto.items()).extracting(FoundInAwsDTO.Item::resourceId)
                .containsExactly("i-0000000000000free", "i-00000000000tagged", "data-platform");
        assertThat(dto.items()).extracting(FoundInAwsDTO.Item::accountName).containsOnly("Default account");
        FoundInAwsDTO.Item tagged = dto.items().stream().filter(i -> "i-00000000000tagged".equals(i.resourceId())).findFirst().orElseThrow();
        assertThat(tagged.name()).isNull();
        assertThat(tagged.taggedEnvironment()).isEqualTo("CRM UAT");
        assertThat(tagged.taggedEnvironmentId()).isEqualTo("env-crm");
        assertThat(refs.decode(tagged.ref(), "admin-1").instanceId()).isEqualTo("i-00000000000tagged");
        assertThat(dto.unreachable()).containsExactly("us-east-1");
        assertThat(dto.ignored()).isEqualTo(1);
    }

    @Test
    void theSweepIsKeptTenMinutesUnlessRefreshed() {
        inbox.inbox(false, "admin-1");
        inbox.setClock(Clock.fixed(now.plus(Duration.ofMinutes(9)), ZoneOffset.UTC));
        inbox.inbox(false, "admin-1");
        verify(sweep, times(1)).everywhere(eq("EC2 instances (inbox)"), anyList(), any(), any());
        inbox.inbox(true, "admin-1");
        inbox.setClock(Clock.fixed(now.plus(Duration.ofMinutes(25)), ZoneOffset.UTC));
        inbox.inbox(false, "admin-1");
        verify(sweep, times(3)).everywhere(eq("EC2 instances (inbox)"), anyList(), any(), any());
    }

    @Test
    void ignoringAnInstanceByItsReferenceAndAClusterByItsName() {
        FoundInAwsDTO dto = inbox.inbox(false, "admin-1");
        String ref = dto.items().stream().filter(i -> "web-01".equals(i.name())).findFirst().orElseThrow().ref();
        when(ignores.findByResourceKindAndAwsAccountRefAndRegionCodeAndResourceId(any(), any(), any(), any())).thenReturn(Optional.empty());

        inbox.ignore("EC2", ref, null, null, null, "admin-1");
        inbox.ignore("EKS", null, null, "eu-west-1", "data-platform", "admin-1");

        ArgumentCaptor<DiscoveryIgnore> saved = ArgumentCaptor.forClass(DiscoveryIgnore.class);
        verify(ignores, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(DiscoveryIgnore::key).containsExactly(
                "EC2|" + EU.accountRef() + "|eu-west-1|i-0000000000000free", "EKS|" + EU.accountRef() + "|eu-west-1|data-platform");
        assertThat(saved.getAllValues().get(0).getResourceName()).isEqualTo("web-01");
    }

    @Test
    void anotherPersonsReferenceIsRefused() {
        String ref = inbox.inbox(false, "admin-1").items().stream().filter(i -> "EC2".equals(i.kind())).findFirst().orElseThrow().ref();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> inbox.ignore("EC2", ref, null, null, null, "admin-2"))
                .isInstanceOf(RuntimeException.class);
        verify(ignores, never()).save(any());
    }
}
