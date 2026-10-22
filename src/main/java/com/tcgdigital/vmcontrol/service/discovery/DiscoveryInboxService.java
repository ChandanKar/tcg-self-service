package com.tcgdigital.vmcontrol.service.discovery;

import com.tcgdigital.vmcontrol.dto.Ec2InstanceInfo;
import com.tcgdigital.vmcontrol.dto.FoundInAwsDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.DiscoveryIgnore;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.DiscoveryIgnoreRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.CloudTarget;
import com.tcgdigital.vmcontrol.service.Ec2Service;
import com.tcgdigital.vmcontrol.service.EksCloudProviderService;
import com.tcgdigital.vmcontrol.service.aws.AwsAccountService;
import com.tcgdigital.vmcontrol.service.support.InstanceRefCodec;
import com.tcgdigital.vmcontrol.service.support.NameNormalizer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * "Found in AWS — needs review" (U19-T03): every EC2 instance and EKS cluster in every account and
 * enabled region (the discovery engine) that no environment holds and nobody ignored. Nothing is added
 * from here by itself: an administrator adds it to an environment, or ignores it. The sweep is kept 10
 * minutes (Refresh sweeps again); references are made per request, for the person asking.
 */
@Service
public class DiscoveryInboxService {

    static final Duration KEEP = Duration.ofMinutes(10);
    private static final Set<String> GONE = Set.of("terminated", "shutting-down");

    /** One sweep's raw answer, shared by everyone until it is 10 minutes old. */
    record Snapshot(Map<CloudTarget, List<Ec2InstanceInfo>> instances, Map<CloudTarget, List<String>> clusters,
                    List<CloudTarget> unreachable, Instant at) { }

    private final AwsResourceSweep sweep;
    private final Ec2Service ec2Service;
    private final EksCloudProviderService eksService;
    private final AwsAccountService accounts;
    private final VmRepository vmRepository;
    private final EnvironmentRepository environmentRepository;
    private final DiscoveryIgnoreRepository ignoreRepository;
    private final InstanceRefCodec refs;
    private final AuditService auditService;
    private volatile Snapshot snapshot;
    private Clock clock = Clock.systemUTC();

    @Value("${vm.discovery.tag.environment-key:tcg:environment}")
    private String envTagKey = "tcg:environment";

    public DiscoveryInboxService(AwsResourceSweep sweep, Ec2Service ec2Service, EksCloudProviderService eksService,
                                 AwsAccountService accounts, VmRepository vmRepository, EnvironmentRepository environmentRepository,
                                 DiscoveryIgnoreRepository ignoreRepository, InstanceRefCodec refs, AuditService auditService) {
        this.sweep = sweep;
        this.ec2Service = ec2Service;
        this.eksService = eksService;
        this.accounts = accounts;
        this.vmRepository = vmRepository;
        this.environmentRepository = environmentRepository;
        this.ignoreRepository = ignoreRepository;
        this.refs = refs;
        this.auditService = auditService;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    /** The inbox for {@code userId}: what no environment holds and nobody ignored; {@code refresh} sweeps again. */
    public FoundInAwsDTO inbox(boolean refresh, String userId) {
        Snapshot s = snapshot(refresh);
        Map<String, String> accountNames = accounts.namesByRef();
        Set<String> ignored = ignoreRepository.findAll().stream().map(DiscoveryIgnore::key).collect(Collectors.toSet());

        List<String> ids = s.instances().values().stream().flatMap(List::stream).map(Ec2InstanceInfo::instanceId).toList();
        Set<String> registered = ids.isEmpty() ? Set.of() : vmRepository.findByProviderVmIdsFetchEnvironment(CloudProvider.AWS, ids)
                .stream().map(v -> v.getProviderVmId()).collect(Collectors.toSet());
        Map<String, Environment> envByName = new HashMap<>();
        for (Environment e : environmentRepository.findByIsActiveTrue()) {
            envByName.put(e.getName(), e);
        }

        List<FoundInAwsDTO.Item> items = new ArrayList<>();
        s.instances().forEach((target, list) -> {
            for (Ec2InstanceInfo i : list) {
                if (registered.contains(i.instanceId()) || (i.state() != null && GONE.contains(i.state().toLowerCase()))
                        || ignored.contains(DiscoveryIgnore.key(DiscoveryIgnore.Kind.EC2, target.accountRef(), target.region(), i.instanceId()))) {
                    continue;
                }
                String name = i.tags() == null ? null : i.tags().get("Name");
                String tag = i.tags() == null ? null : i.tags().get(envTagKey);
                Environment taggedEnv = tag == null ? null : envByName.get(tag);
                items.add(new FoundInAwsDTO.Item("EC2", refs.encode(CloudProvider.AWS, target.accountRef(), target.region(), i.instanceId(), userId),
                        i.instanceId(), name == null || name.isBlank() ? null : name, target.accountRef(),
                        accountNames.getOrDefault(target.accountRef(), target.accountRef()), target.region(), i.availabilityZone(),
                        i.instanceType(), i.state(), taggedEnv == null ? null : label(taggedEnv),
                        taggedEnv == null ? null : taggedEnv.getEnvironmentId()));
            }
        });
        s.clusters().forEach((target, names) -> {
            for (String cluster : names) {
                if (environmentRepository.existsByEksClusterName(cluster) || environmentRepository.existsByName(NameNormalizer.slug(cluster))
                        || ignored.contains(DiscoveryIgnore.key(DiscoveryIgnore.Kind.EKS, target.accountRef(), target.region(), cluster))) {
                    continue;
                }
                items.add(new FoundInAwsDTO.Item("EKS", null, cluster, cluster, target.accountRef(),
                        accountNames.getOrDefault(target.accountRef(), target.accountRef()), target.region(), null, null, null, null, null));
            }
        });
        items.sort(Comparator.comparing(FoundInAwsDTO.Item::accountName, Comparator.nullsLast(String::compareTo))
                .thenComparing(FoundInAwsDTO.Item::region).thenComparing(FoundInAwsDTO.Item::kind)
                .thenComparing(i -> i.name() == null ? "~" : i.name().toLowerCase()));
        List<String> unreachable = s.unreachable().stream()
                .map(t -> t.region() + (t.isDefaultAccount() ? "" : " (account " + accountNames.getOrDefault(t.accountRef(), t.accountRef()) + ")"))
                .toList();
        return new FoundInAwsDTO(items, s.at(), unreachable, ignoreRepository.count());
    }

    /** Ignores an instance (by its reference) or a cluster (by account, region and name). */
    public void ignore(String kind, String ref, String accountRef, String region, String clusterName, String userId) {
        DiscoveryIgnore row = new DiscoveryIgnore();
        if ("EC2".equalsIgnoreCase(kind)) {
            InstanceRefCodec.InstanceRef decoded = refs.decode(ref, userId);
            row.setResourceKind(DiscoveryIgnore.Kind.EC2);
            row.setAwsAccountRef(CloudTarget.of(decoded.accountRef(), null).accountRef());
            row.setRegionCode(decoded.region());
            row.setResourceId(decoded.instanceId());
            row.setResourceName(nameOf(decoded));
        } else if ("EKS".equalsIgnoreCase(kind)) {
            if (clusterName == null || clusterName.isBlank() || region == null || region.isBlank()) {
                throw new ValidationException("A cluster is ignored by its name and region");
            }
            row.setResourceKind(DiscoveryIgnore.Kind.EKS);
            row.setAwsAccountRef(CloudTarget.of(accountRef, null).accountRef());
            row.setRegionCode(region.trim());
            row.setResourceId(clusterName.trim());
            row.setResourceName(clusterName.trim());
        } else {
            throw new ValidationException("kind must be EC2 or EKS");
        }
        if (ignoreRepository.findByResourceKindAndAwsAccountRefAndRegionCodeAndResourceId(row.getResourceKind(),
                row.getAwsAccountRef(), row.getRegionCode(), row.getResourceId()).isPresent()) {
            return;
        }
        row.setIgnoredBy(userId);
        ignoreRepository.save(row);
        auditService.logAction(userId, AuditAction.DISCOVERY_IGNORED, "discovery", row.getDiscoveryIgnoreId(),
                row.getResourceName() == null ? row.getResourceKind().name() : row.getResourceName(),
                "Hidden from Found in AWS (" + row.getResourceKind() + " in " + row.getRegionCode() + ")");
    }

    /** The ignored resources, newest first. */
    public List<FoundInAwsDTO.Ignored> ignored() {
        Map<String, String> accountNames = accounts.namesByRef();
        return ignoreRepository.findAllByOrderByIgnoredAtDesc().stream().map(r -> new FoundInAwsDTO.Ignored(r.getDiscoveryIgnoreId(),
                r.getResourceKind().name(), r.getResourceId(), r.getResourceName(),
                accountNames.getOrDefault(r.getAwsAccountRef(), r.getAwsAccountRef()), r.getRegionCode(),
                r.getIgnoredAt() == null ? null : r.getIgnoredAt().toInstant())).toList();
    }

    /** Brings an ignored resource back to the inbox. */
    public void unignore(String id, String userId) {
        DiscoveryIgnore row = ignoreRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Ignored resource", id));
        ignoreRepository.delete(row);
        auditService.logAction(userId, AuditAction.DISCOVERY_UNIGNORED, "discovery", id,
                row.getResourceName() == null ? row.getResourceKind().name() : row.getResourceName(), "Back in Found in AWS");
    }

    private Snapshot snapshot(boolean refresh) {
        Snapshot s = snapshot;
        Instant now = clock.instant();
        if (!refresh && s != null && s.at().plus(KEEP).isAfter(now)) {
            return s;
        }
        var instances = sweep.everywhere("EC2 instances (inbox)", List.of(), "ec2:instance",
                t -> ec2Service.listInstances(t.accountRef(), t.region()));
        var clusters = sweep.everywhere("EKS clusters (inbox)", List.of(), "eks:cluster", eksService::listClusters);
        Set<CloudTarget> failed = new HashSet<>();
        instances.failures().forEach(o -> failed.add(o.target()));
        clusters.failures().forEach(o -> failed.add(o.target()));
        Snapshot fresh = new Snapshot(instances.found(), clusters.found(),
                failed.stream().sorted(Comparator.comparing(CloudTarget::key)).toList(), clock.instant());
        snapshot = fresh;
        return fresh;
    }

    private String nameOf(InstanceRefCodec.InstanceRef ref) {
        Snapshot s = snapshot;
        if (s == null) {
            return null;
        }
        return s.instances().getOrDefault(CloudTarget.of(ref.accountRef(), ref.region()), List.of()).stream()
                .filter(i -> ref.instanceId().equals(i.instanceId())).findFirst()
                .map(i -> i.tags() == null ? null : i.tags().get("Name")).orElse(null);
    }

    private static String label(Environment e) {
        return e.getDisplayName() != null && !e.getDisplayName().isBlank() ? e.getDisplayName() : e.getName();
    }
}
