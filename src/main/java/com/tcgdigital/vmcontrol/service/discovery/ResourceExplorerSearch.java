package com.tcgdigital.vmcontrol.service.discovery;

import com.tcgdigital.vmcontrol.service.CloudTarget;
import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.resourceexplorer2.ResourceExplorer2Client;
import software.amazon.awssdk.services.resourceexplorer2.model.Index;
import software.amazon.awssdk.services.resourceexplorer2.model.IndexType;
import software.amazon.awssdk.services.resourceexplorer2.model.ListIndexesRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.Resource;
import software.amazon.awssdk.services.resourceexplorer2.model.SearchRequest;
import software.amazon.awssdk.services.resourceexplorer2.model.SearchResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * AWS Resource Explorer as a shortcut for the sweep (U19-T02). When an account has an aggregator
 * index (one that collects every region) and a default view, one search says which regions hold a
 * resource type (ec2:instance, eks:cluster), and the sweep only calls those regions. Anything else
 * (no aggregator index, no default view, no permission, a failed call) returns the targets as they
 * were, so the sweep covers every region. Answers are kept 15 minutes; "no index" for a day.
 */
@Service
public class ResourceExplorerSearch {

    private static final Logger log = LoggerFactory.getLogger(ResourceExplorerSearch.class);
    static final Duration REGIONS_TTL = Duration.ofMinutes(15);
    static final Duration NO_INDEX_TTL = Duration.ofDays(1);
    private static final int MAX_PAGES = 20;

    private record Cached(Optional<Set<String>> regions, Instant at) {}

    private final BiFunction<String, String, ResourceExplorer2Client> clients;
    private final Supplier<String> homeRegion;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    @Value("${aws.resource-explorer.enabled:true}")
    private boolean enabled = true;

    @Autowired
    public ResourceExplorerSearch(AwsClientFactory clientFactory) {
        this(clientFactory::resourceExplorer, clientFactory::defaultRegion);
    }

    ResourceExplorerSearch(BiFunction<String, String, ResourceExplorer2Client> clients, Supplier<String> homeRegion) {
        this.clients = clients;
        this.homeRegion = homeRegion;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** The targets whose region holds at least one resource of the type; all of them when that is not known. */
    public List<CloudTarget> narrow(List<CloudTarget> targets, String resourceType) {
        if (!enabled || targets.isEmpty()) {
            return targets;
        }
        Map<String, List<CloudTarget>> byAccount = new LinkedHashMap<>();
        targets.forEach(t -> byAccount.computeIfAbsent(t.accountRef(), k -> new ArrayList<>()).add(t));
        List<CloudTarget> out = new ArrayList<>();
        byAccount.forEach((ref, list) -> {
            Optional<Set<String>> regions = regionsHolding(ref, resourceType);
            if (regions.isEmpty()) {
                out.addAll(list);
            } else {
                list.stream().filter(t -> regions.get().contains(t.region())).forEach(out::add);
                log.debug("Resource Explorer: {} of account {} is in {} of {} region(s)", resourceType, ref,
                        regions.get().size(), list.size());
            }
        });
        return out;
    }

    /** The regions of the account that hold the type, or empty when Resource Explorer cannot say. */
    Optional<Set<String>> regionsHolding(String accountRef, String resourceType) {
        Instant now = clock.instant();
        String noIndexKey = accountRef + "|no-index";
        Cached none = cache.get(noIndexKey);
        if (none != null && none.at().plus(NO_INDEX_TTL).isAfter(now)) {
            return Optional.empty();
        }
        String key = accountRef + "|" + resourceType;
        Cached cached = cache.get(key);
        if (cached != null && cached.at().plus(REGIONS_TTL).isAfter(now)) {
            return cached.regions();
        }
        Optional<Set<String>> regions;
        try {
            Optional<String> aggregator = aggregatorRegion(accountRef);
            if (aggregator.isEmpty()) {
                cache.put(noIndexKey, new Cached(Optional.empty(), now));
                log.info("Account {} has no Resource Explorer aggregator index; discovery sweeps every region", accountRef);
                return Optional.empty();
            }
            regions = Optional.of(search(accountRef, aggregator.get(), resourceType));
        } catch (Exception e) {
            // No default view, no permission, a failure: the sweep covers every region.
            log.info("Resource Explorer could not narrow {} for account {} ({}); discovery sweeps every region",
                    resourceType, accountRef, e.getMessage());
            regions = Optional.empty();
        }
        cache.put(key, new Cached(regions, now));
        return regions;
    }

    private Optional<String> aggregatorRegion(String accountRef) {
        ResourceExplorer2Client home = clients.apply(accountRef, homeRegion.get());
        String token = null;
        do {
            var page = home.listIndexes(ListIndexesRequest.builder().type(IndexType.AGGREGATOR).nextToken(token).build());
            for (Index index : page.indexes()) {
                if (index.type() == IndexType.AGGREGATOR && index.region() != null) {
                    return Optional.of(index.region());
                }
            }
            token = page.nextToken();
        } while (token != null);
        return Optional.empty();
    }

    private Set<String> search(String accountRef, String region, String resourceType) {
        ResourceExplorer2Client client = clients.apply(accountRef, region);
        Set<String> regions = new TreeSet<>();
        String token = null;
        int pages = 0;
        do {
            SearchResponse page = client.search(SearchRequest.builder()
                    .queryString("resourcetype:" + resourceType).maxResults(1000).nextToken(token).build());
            for (Resource resource : page.resources()) {
                if (resource.region() != null && !resource.region().isBlank() && !"global".equals(resource.region())) {
                    regions.add(resource.region());
                }
            }
            token = page.nextToken();
        } while (token != null && ++pages < MAX_PAGES);
        return regions;
    }
}
