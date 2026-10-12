package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import com.tcgdigital.vmcontrol.model.PurchaseOption;
import com.tcgdigital.vmcontrol.model.SpotPriceSnapshot;
import com.tcgdigital.vmcontrol.model.Vm;
import com.tcgdigital.vmcontrol.repository.SpotPriceSnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmInventorySnapshotRepository;
import com.tcgdigital.vmcontrol.repository.VmRepository;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryRequest;
import software.amazon.awssdk.services.ec2.model.DescribeSpotPriceHistoryResponse;
import software.amazon.awssdk.services.ec2.model.SpotPrice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Spot prices (E31-T02): a daily, read-only capture of the newest Linux Spot price per
 * availability zone for the instance types Spot VMs and node groups run, and the lookup cost
 * estimation uses for them. Off unless {@code spot.pricing.enabled}; needs ec2:DescribeSpotPriceHistory.
 */
@Service
public class SpotPriceService {

    private static final Logger log = LoggerFactory.getLogger(SpotPriceService.class);

    private final SpotPriceSnapshotRepository repository;
    private final VmRepository vmRepository;
    private final VmInventorySnapshotRepository inventoryRepository;
    private final AwsClientFactory awsClientFactory;
    private Clock clock = Clock.systemUTC();

    @Value("${spot.pricing.enabled:false}")
    private boolean enabled;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    public SpotPriceService(SpotPriceSnapshotRepository repository, VmRepository vmRepository,
                            VmInventorySnapshotRepository inventoryRepository, AwsClientFactory awsClientFactory) {
        this.repository = repository;
        this.vmRepository = vmRepository;
        this.inventoryRepository = inventoryRepository;
        this.awsClientFactory = awsClientFactory;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public boolean isEnabled() {
        return settings == null ? enabled : settings.overrideOr("spot.pricing.enabled", enabled);
    }

    /**
     * Whether a VM's Spot price is worth capturing: it runs on Spot now. Spot-eligible
     * environments (E31-T03) add their on-demand VMs so opportunities can be priced.
     */
    protected boolean wantsPrice(Vm vm) {
        if (vm.getPurchaseOption() == PurchaseOption.SPOT) {
            return true;
        }
        com.tcgdigital.vmcontrol.model.Environment env = vm.getGroup() == null ? null : vm.getGroup().getEnvironment();
        return !vm.isSpotExcluded() && env != null && env.getSpotPolicy() == com.tcgdigital.vmcontrol.model.SpotPolicy.ALLOWED;
    }

    /** Captures today's prices; returns the number of (zone, type) prices stored. */
    public int captureDaily() {
        if (!isEnabled() || !awsClientFactory.isConfigured()) {
            return 0;
        }
        List<Vm> vms = vmRepository.findByIsActiveTrueFetchGroupAndEnvironment().stream()
                .filter(v -> (v.getProvider() == CloudProvider.AWS || v.getProvider() == CloudProvider.AWS_EKS) && v.getRegion() != null)
                .filter(this::wantsPrice)
                .toList();
        if (vms.isEmpty()) {
            return 0;
        }
        Map<String, String> typeByVmId = new HashMap<>();
        List<String> ec2Ids = vms.stream().filter(v -> v.getProvider() == CloudProvider.AWS).map(Vm::getVmId).toList();
        if (!ec2Ids.isEmpty()) {
            inventoryRepository.findInstanceTypesByVmIds(ec2Ids).forEach(p -> typeByVmId.put(p.getVmId(), p.getInstanceType()));
        }
        Map<CloudTarget, Set<String>> typesByTarget = new LinkedHashMap<>();
        for (Vm vm : vms) {
            String type = vm.getProvider() == CloudProvider.AWS_EKS
                    ? EstimatedCostProvider.eksNodeInfo(vm.getMetadata()).instanceType() : typeByVmId.get(vm.getVmId());
            if (type != null && !type.isBlank()) {
                typesByTarget.computeIfAbsent(CloudTarget.of(vm), t -> new LinkedHashSet<>()).add(type);
            }
        }
        int stored = 0;
        for (Map.Entry<CloudTarget, Set<String>> entry : typesByTarget.entrySet()) {
            try {
                stored += store(entry.getKey(), fetchNewest(entry.getKey(), entry.getValue()));
            } catch (RuntimeException e) {
                log.warn("Spot price capture failed for {}: {}", entry.getKey(), e.getMessage());
            }
        }
        log.info("Spot price capture: {} price(s) stored for {} account/region target(s)", stored, typesByTarget.size());
        return stored;
    }

    /** The newest price per "zone|type" over the last hour, every page. */
    private Map<String, SpotPrice> fetchNewest(CloudTarget target, Set<String> types) {
        Ec2Client ec2 = awsClientFactory.ec2(target.accountRef(), target.region());
        Map<String, SpotPrice> newest = new LinkedHashMap<>();
        String token = null;
        do {
            DescribeSpotPriceHistoryResponse response = ec2.describeSpotPriceHistory(DescribeSpotPriceHistoryRequest.builder()
                    .productDescriptions("Linux/UNIX")
                    .instanceTypesWithStrings(types)
                    .startTime(clock.instant().minus(Duration.ofHours(1)))
                    .nextToken(token)
                    .build());
            for (SpotPrice price : response.spotPriceHistory()) {
                String key = price.availabilityZone() + "|" + price.instanceTypeAsString();
                SpotPrice seen = newest.get(key);
                if (seen == null || (price.timestamp() != null && seen.timestamp() != null && price.timestamp().isAfter(seen.timestamp()))) {
                    newest.put(key, price);
                }
            }
            token = response.nextToken();
        } while (token != null && !token.isEmpty());
        return newest;
    }

    private int store(CloudTarget target, Map<String, SpotPrice> prices) {
        Date today = Date.valueOf(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        int count = 0;
        for (SpotPrice price : prices.values()) {
            BigDecimal perHour;
            try {
                perHour = new BigDecimal(price.spotPrice());
            } catch (RuntimeException e) {
                continue;
            }
            SpotPriceSnapshot row = repository.findByRegionAndAvailabilityZoneAndInstanceTypeAndCapturedOn(
                    target.region(), price.availabilityZone(), price.instanceTypeAsString(), today).orElseGet(SpotPriceSnapshot::new);
            row.setAwsAccountRef(target.accountRef());
            row.setRegion(target.region());
            row.setAvailabilityZone(price.availabilityZone());
            row.setInstanceType(price.instanceTypeAsString());
            row.setPricePerHour(perHour);
            row.setCapturedOn(today);
            repository.save(row);
            count++;
        }
        return count;
    }

    /**
     * The hourly Spot rate of a type: the VM's zone on the latest captured day, else the average
     * of that day's zones in the region, else empty (and empty whenever Spot pricing is off).
     */
    public Optional<BigDecimal> lookupSpotRate(String instanceType, String region, String availabilityZone) {
        if (!isEnabled() || instanceType == null || region == null) {
            return Optional.empty();
        }
        Date latest = repository.findLatestCapturedOn(region, instanceType);
        if (latest == null) {
            return Optional.empty();
        }
        List<SpotPriceSnapshot> rows = repository.findByRegionAndInstanceTypeAndCapturedOn(region, instanceType, latest);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        if (availabilityZone != null) {
            Optional<BigDecimal> exact = rows.stream().filter(r -> availabilityZone.equals(r.getAvailabilityZone()))
                    .map(SpotPriceSnapshot::getPricePerHour).findFirst();
            if (exact.isPresent()) {
                return exact;
            }
        }
        BigDecimal sum = rows.stream().map(SpotPriceSnapshot::getPricePerHour).reduce(BigDecimal.ZERO, BigDecimal::add);
        return Optional.of(sum.divide(BigDecimal.valueOf(rows.size()), 6, RoundingMode.HALF_UP));
    }
}
