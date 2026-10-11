package com.tcgdigital.vmcontrol.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeVolumesResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.Volume;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Read-only AWS scans for the waste sweep (E22). One EC2 client per region, cached. A failed call
 * throws: an empty answer would make the sweep resolve every finding in the region (lesson H24).
 */
@Service
public class AwsWasteScanService {

    /** An AWS resource a scan found, with what the sweep needs to price and link it. */
    public record ScannedResource(String resourceId, String name, String volumeType, int sizeGib, Instant createdAt,
                                  Map<String, String> tags, Map<String, Object> facts) {}

    private final Function<String, Ec2Client> clients;

    @Autowired
    public AwsWasteScanService(@Value("${aws.access-key:}") String accessKey, @Value("${aws.secret-key:}") String secretKey) {
        Map<String, Ec2Client> cache = new ConcurrentHashMap<>();
        this.clients = region -> cache.computeIfAbsent(region, r -> Ec2Client.builder()
                .region(Region.of(r))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(60))
                        .apiCallAttemptTimeout(Duration.ofSeconds(30))
                        .build())
                .build());
    }

    /** With given clients (tests). */
    AwsWasteScanService(Function<String, Ec2Client> clients) {
        this.clients = clients;
    }

    /** EBS volumes not attached to anything (state "available"). */
    public List<ScannedResource> scanUnattachedVolumes(String region) {
        return volumes(region, Filter.builder().name("status").values("available").build());
    }

    /** gp2 volumes in any state (gp3 is cheaper per GB for the same baseline). */
    public List<ScannedResource> scanGp2Volumes(String region) {
        return volumes(region, Filter.builder().name("volume-type").values("gp2").build());
    }

    /** Elastic IPs not associated with anything (each still billed). */
    public List<ScannedResource> scanUnassociatedAddresses(String region) {
        return client(region).describeAddresses(software.amazon.awssdk.services.ec2.model.DescribeAddressesRequest.builder().build())
                .addresses().stream()
                .filter(a -> a.associationId() == null)
                .map(a -> {
                    Map<String, Object> facts = new LinkedHashMap<>();
                    facts.put("publicIp", a.publicIp());
                    String id = a.allocationId() != null ? a.allocationId() : a.publicIp();
                    return new ScannedResource(id, tags(a.tags()).get("Name"), null, 0, null, tags(a.tags()), facts);
                }).toList();
    }

    /**
     * Stopped instances, with their volume ids, Elastic IP (if any) and StateTransitionReason
     * (which says when a user stopped them).
     */
    public List<ScannedResource> scanStoppedInstances(String region) {
        Ec2Client ec2 = client(region);
        List<ScannedResource> out = new ArrayList<>();
        String token = null;
        do {
            software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse page = ec2.describeInstances(
                    software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest.builder()
                            .filters(Filter.builder().name("instance-state-name").values("stopped").build())
                            .nextToken(token).maxResults(500).build());
            page.reservations().forEach(r -> r.instances().forEach(i -> {
                Map<String, Object> facts = new LinkedHashMap<>();
                facts.put("stateTransitionReason", i.stateTransitionReason());
                facts.put("instanceType", i.instanceTypeAsString());
                facts.put("volumeIds", i.blockDeviceMappings() == null ? List.of() : i.blockDeviceMappings().stream()
                        .filter(m -> m.ebs() != null).map(m -> m.ebs().volumeId()).toList());
                facts.put("publicIp", i.publicIpAddress());
                out.add(new ScannedResource(i.instanceId(), tags(i.tags()).get("Name"), null, 0, i.launchTime(), tags(i.tags()), facts));
            }));
            token = page.nextToken();
        } while (token != null && !token.isEmpty());
        return out;
    }

    /** Sizes (GiB) and types of volumes, by id. */
    public Map<String, ScannedResource> volumesById(String region, List<String> volumeIds) {
        Map<String, ScannedResource> out = new LinkedHashMap<>();
        if (volumeIds.isEmpty()) {
            return out;
        }
        for (int i = 0; i < volumeIds.size(); i += 200) {
            List<String> batch = volumeIds.subList(i, Math.min(volumeIds.size(), i + 200));
            volumes(region, Filter.builder().name("volume-id").values(batch).build()).forEach(v -> out.put(v.resourceId(), v));
        }
        return out;
    }

    /** Own snapshots started before {@code olderThan}, except those an own AMI uses. */
    public List<ScannedResource> scanOldSnapshots(String region, Instant olderThan) {
        Ec2Client ec2 = client(region);
        java.util.Set<String> usedByImages = new java.util.HashSet<>();
        ec2.describeImages(software.amazon.awssdk.services.ec2.model.DescribeImagesRequest.builder().owners("self").build())
                .images().forEach(img -> {
                    if (img.blockDeviceMappings() != null) {
                        img.blockDeviceMappings().stream().filter(m -> m.ebs() != null && m.ebs().snapshotId() != null)
                                .forEach(m -> usedByImages.add(m.ebs().snapshotId()));
                    }
                });
        List<ScannedResource> out = new ArrayList<>();
        String token = null;
        do {
            software.amazon.awssdk.services.ec2.model.DescribeSnapshotsResponse page = ec2.describeSnapshots(
                    software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest.builder()
                            .ownerIds("self").nextToken(token).maxResults(1000).build());
            page.snapshots().stream()
                    .filter(s -> s.startTime() != null && s.startTime().isBefore(olderThan) && !usedByImages.contains(s.snapshotId()))
                    .forEach(s -> {
                        Map<String, Object> facts = new LinkedHashMap<>();
                        facts.put("volumeId", s.volumeId());
                        facts.put("description", s.description());
                        out.add(new ScannedResource(s.snapshotId(), tags(s.tags()).get("Name"), null,
                                s.volumeSize() == null ? 0 : s.volumeSize(), s.startTime(), tags(s.tags()), facts));
                    });
            token = page.nextToken();
        } while (token != null && !token.isEmpty());
        return out;
    }

    private static final java.util.regex.Pattern USER_STOPPED =
            java.util.regex.Pattern.compile("\\((\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) GMT\\)");

    /** "User initiated (2026-08-01 10:15:00 GMT)" -> that instant; null when absent. */
    static Instant stoppedAt(String stateTransitionReason) {
        if (stateTransitionReason == null) {
            return null;
        }
        java.util.regex.Matcher m = USER_STOPPED.matcher(stateTransitionReason);
        if (!m.find()) {
            return null;
        }
        return java.time.LocalDateTime.parse(m.group(1).replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC);
    }

    List<ScannedResource> volumes(String region, Filter... filters) {
        Ec2Client ec2 = clients.apply(region);
        List<ScannedResource> out = new ArrayList<>();
        String token = null;
        do {
            DescribeVolumesResponse page = ec2.describeVolumes(DescribeVolumesRequest.builder()
                    .filters(filters).nextToken(token).maxResults(500).build());
            for (Volume v : page.volumes()) {
                Map<String, Object> facts = new LinkedHashMap<>();
                facts.put("state", v.stateAsString());
                facts.put("attachedTo", v.attachments() == null ? List.of()
                        : v.attachments().stream().map(a -> a.instanceId()).toList());
                out.add(new ScannedResource(v.volumeId(), tags(v.tags()).get("Name"), v.volumeTypeAsString(),
                        v.size() == null ? 0 : v.size(), v.createTime(), tags(v.tags()), facts));
            }
            token = page.nextToken();
        } while (token != null && !token.isEmpty());
        return out;
    }

    static Map<String, String> tags(List<Tag> tags) {
        Map<String, String> out = new LinkedHashMap<>();
        if (tags != null) {
            tags.forEach(t -> out.put(t.key(), t.value()));
        }
        return out;
    }

    Ec2Client client(String region) {
        return clients.apply(region);
    }
}
