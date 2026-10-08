package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.eks.model.Nodegroup;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Applies cost-allocation tags ({@code tcg:managed-by}/{@code tcg:environment}/{@code tcg:team})
 * to every managed AWS EC2 instance and EKS node group, so real AWS billing data (Cost Explorer,
 * a later phase) can be grouped the same way estimated spend already is by {@link TeamResolver}.
 *
 * This app never creates AWS resources — it only discovers and starts/stops pre-existing ones —
 * so tagging is always a retrofit against already-registered {@link Vm} rows, never done at
 * "creation" time. Disabled by default ({@code cost.tagging.enabled=false}): writing to real AWS
 * resource tags is a materially bigger blast radius than the read-only reporting the rest of
 * Cost Management does.
 */
@Service
public class TagReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(TagReconciliationService.class);

    @Value("${cost.tagging.enabled:false}")
    private boolean taggingEnabled;

    @Value("${cost.tagging.key-prefix:tcg:}")
    private String tagKeyPrefix;

    private final VmRepository vmRepository;
    private final AwsCloudProviderService awsCloudProviderService;
    private final EksCloudProviderService eksCloudProviderService;
    private final CostExplorerTagActivationService tagActivationService;
    private final TeamResolver teamResolver;

    public TagReconciliationService(VmRepository vmRepository,
                                     AwsCloudProviderService awsCloudProviderService,
                                     EksCloudProviderService eksCloudProviderService,
                                     CostExplorerTagActivationService tagActivationService,
                                     TeamResolver teamResolver) {
        this.vmRepository = vmRepository;
        this.awsCloudProviderService = awsCloudProviderService;
        this.eksCloudProviderService = eksCloudProviderService;
        this.tagActivationService = tagActivationService;
        this.teamResolver = teamResolver;
    }

    public record Result(int tagged, int failed, int total) {
    }

    /**
     * Sweeps every active VM fleet-wide. Used by the nightly scheduler and the manual
     * "Reconcile Tags Now" admin action.
     */
    public Result reconcileAll() {
        if (!taggingEnabled) {
            log.debug("Cost-allocation tagging disabled (cost.tagging.enabled=false) — skipping");
            return new Result(0, 0, 0);
        }
        return reconcile(vmRepository.findByIsActiveTrueFetchGroupAndEnvironment());
    }

    /**
     * Sweeps only the VMs belonging to one environment — called right after VM discovery so
     * newly-registered VMs get tagged immediately instead of waiting for the nightly sweep.
     */
    public Result reconcileEnvironment(Environment environment) {
        if (!taggingEnabled) {
            return new Result(0, 0, 0);
        }
        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment().stream()
                .filter(vm -> vm.getGroup().getEnvironment().getEnvironmentId().equals(environment.getEnvironmentId()))
                .toList();
        return reconcile(vms);
    }

    private Result reconcile(List<Vm> vms) {
        if (vms.isEmpty()) {
            return new Result(0, 0, 0);
        }

        int tagged = 0;
        int failed = 0;

        // EC2: batch by (region, environment) so every instance in a batch shares the same tag
        // values and can go out in a single CreateTags call (tagInstances chunks at 20 internally).
        Map<String, List<Vm>> ec2ByRegionAndEnv = vms.stream()
                .filter(vm -> vm.getProvider() == CloudProvider.AWS)
                .collect(Collectors.groupingBy(vm -> vm.getRegion() + "|" + vm.getGroup().getEnvironment().getEnvironmentId()));

        for (List<Vm> group : ec2ByRegionAndEnv.values()) {
            Vm first = group.get(0);
            Environment env = first.getGroup().getEnvironment();
            Map<String, String> tags = buildTags(env);
            List<String> instanceIds = group.stream().map(Vm::getProviderVmId).toList();
            try {
                awsCloudProviderService.tagInstances(first.getRegion(), instanceIds, tags);
                markTagged(group);
                tagged += group.size();
            } catch (Exception e) {
                log.error("Failed to tag {} EC2 instance(s) in region {} for environment {}: {}",
                        group.size(), first.getRegion(), env.getName(), e.getMessage());
                failed += group.size();
            }
        }

        // EKS: one node group at a time — TagResource needs the node group's real ARN, which
        // requires its own DescribeNodegroup call per group (no batch tagging API for EKS).
        List<Vm> eksVms = vms.stream().filter(vm -> vm.getProvider() == CloudProvider.AWS_EKS).toList();
        for (Vm vm : eksVms) {
            try {
                String[] parts = vm.getProviderVmId().split("/", 2);
                if (parts.length != 2) {
                    failed++;
                    continue;
                }
                Nodegroup nodegroup = eksCloudProviderService.describeNodegroup(parts[0], parts[1], vm.getRegion());
                if (nodegroup == null || nodegroup.nodegroupArn() == null) {
                    log.warn("Could not resolve ARN for EKS node group {} — skipping tag", vm.getProviderVmId());
                    failed++;
                    continue;
                }
                Environment env = vm.getGroup().getEnvironment();
                eksCloudProviderService.tagNodeGroup(nodegroup.nodegroupArn(), vm.getRegion(), buildTags(env));
                markTagged(List.of(vm));
                tagged++;
            } catch (Exception e) {
                log.error("Failed to tag EKS node group {}: {}", vm.getProviderVmId(), e.getMessage());
                failed++;
            }
        }

        if (tagged > 0) {
            tagActivationService.ensureTagKeysActivated(List.of(
                    tagKeyPrefix + "managed-by", tagKeyPrefix + "environment", tagKeyPrefix + "team"));
        }

        log.info("Tag reconciliation complete — {} tagged, {} failed, {} total", tagged, failed, vms.size());
        return new Result(tagged, failed, vms.size());
    }

    private Map<String, String> buildTags(Environment environment) {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(tagKeyPrefix + "managed-by", "vmcontrol");
        tags.put(tagKeyPrefix + "environment", environment.getName());
        tags.put(tagKeyPrefix + "team", teamResolver.resolveTeam(environment.getMetadata()));
        return tags;
    }

    /** Targeted update: never re-save VMs loaded at the start of the run (they may be stale, H14). */
    private void markTagged(List<Vm> vms) {
        if (vms.isEmpty()) {
            return;
        }
        vmRepository.markTagsSynced(vms.stream().map(Vm::getVmId).toList(), Timestamp.from(Instant.now()));
    }
}
