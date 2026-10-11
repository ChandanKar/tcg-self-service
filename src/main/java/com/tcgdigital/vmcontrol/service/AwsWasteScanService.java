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

    // ============= Fix actions (E22-T04); these change AWS state =============

    /** How often and how long to wait for a snapshot to complete (tests shorten it). */
    private long snapshotPollMs = 15_000;
    private Duration snapshotTimeout = Duration.ofMinutes(30);

    void setSnapshotPolling(long pollMs, Duration timeout) {
        this.snapshotPollMs = pollMs;
        this.snapshotTimeout = timeout;
    }

    /** The volume now, or null when it no longer exists. */
    public software.amazon.awssdk.services.ec2.model.Volume describeVolume(String region, String volumeId) {
        try {
            List<software.amazon.awssdk.services.ec2.model.Volume> volumes = client(region).describeVolumes(
                    DescribeVolumesRequest.builder().volumeIds(volumeId).build()).volumes();
            return volumes.isEmpty() ? null : volumes.get(0);
        } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
            if (e.awsErrorDetails() != null && "InvalidVolume.NotFound".equals(e.awsErrorDetails().errorCode())) {
                return null;
            }
            throw e;
        }
    }

    /** Snapshots a volume with the given tags and waits until the snapshot is completed. */
    public String createSnapshotAndWait(String region, String volumeId, Map<String, String> tags) {
        Ec2Client ec2 = client(region);
        String snapshotId = ec2.createSnapshot(software.amazon.awssdk.services.ec2.model.CreateSnapshotRequest.builder()
                .volumeId(volumeId)
                .description("Before deleting unattached volume " + volumeId + " (waste action)")
                .tagSpecifications(software.amazon.awssdk.services.ec2.model.TagSpecification.builder()
                        .resourceType(software.amazon.awssdk.services.ec2.model.ResourceType.SNAPSHOT)
                        .tags(toTags(tags)).build())
                .build()).snapshotId();
        Instant deadline = Instant.now().plus(snapshotTimeout);
        while (true) {
            List<software.amazon.awssdk.services.ec2.model.Snapshot> found = ec2.describeSnapshots(
                    software.amazon.awssdk.services.ec2.model.DescribeSnapshotsRequest.builder().snapshotIds(snapshotId).build()).snapshots();
            String state = found.isEmpty() ? null : found.get(0).stateAsString();
            if ("completed".equals(state)) {
                return snapshotId;
            }
            if ("error".equals(state)) {
                throw new IllegalStateException("Snapshot " + snapshotId + " failed");
            }
            if (Instant.now().isAfter(deadline)) {
                throw new IllegalStateException("Snapshot " + snapshotId + " did not complete within " + snapshotTimeout.toMinutes() + " minutes");
            }
            try {
                Thread.sleep(snapshotPollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for snapshot " + snapshotId, e);
            }
        }
    }

    public void deleteVolume(String region, String volumeId) {
        client(region).deleteVolume(software.amazon.awssdk.services.ec2.model.DeleteVolumeRequest.builder().volumeId(volumeId).build());
    }

    /** Recreates a volume from a snapshot; returns the new volume id. */
    public String createVolumeFromSnapshot(String region, String snapshotId, String availabilityZone, String volumeType,
                                           Integer sizeGib, Integer iops, Integer throughput, Map<String, String> tags) {
        software.amazon.awssdk.services.ec2.model.CreateVolumeRequest.Builder request =
                software.amazon.awssdk.services.ec2.model.CreateVolumeRequest.builder()
                        .snapshotId(snapshotId).availabilityZone(availabilityZone).volumeType(volumeType).size(sizeGib)
                        .tagSpecifications(software.amazon.awssdk.services.ec2.model.TagSpecification.builder()
                                .resourceType(software.amazon.awssdk.services.ec2.model.ResourceType.VOLUME).tags(toTags(tags)).build());
        if (iops != null && ("io1".equals(volumeType) || "io2".equals(volumeType) || "gp3".equals(volumeType))) {
            request.iops(iops);
        }
        if (throughput != null && "gp3".equals(volumeType)) {
            request.throughput(throughput);
        }
        return client(region).createVolume(request.build()).volumeId();
    }

    private static List<Tag> toTags(Map<String, String> tags) {
        return tags.entrySet().stream().filter(e -> !e.getKey().startsWith("aws:"))
                .map(e -> Tag.builder().key(e.getKey()).value(e.getValue()).build()).toList();
    }

    Ec2Client client(String region) {
        return clients.apply(region);
    }
}
