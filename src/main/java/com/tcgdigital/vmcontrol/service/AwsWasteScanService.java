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
