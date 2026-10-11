package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.CostAnomalyMonitor;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.CostAnomalyMonitorRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.costexplorer.model.AnomalyMonitor;
import software.amazon.awssdk.services.costexplorer.model.CreateAnomalyMonitorRequest;
import software.amazon.awssdk.services.costexplorer.model.Expression;
import software.amazon.awssdk.services.costexplorer.model.GetAnomalyMonitorsRequest;
import software.amazon.awssdk.services.costexplorer.model.GetAnomalyMonitorsResponse;
import software.amazon.awssdk.services.costexplorer.model.LimitExceededException;
import software.amazon.awssdk.services.costexplorer.model.MatchOption;
import software.amazon.awssdk.services.costexplorer.model.MonitorType;
import software.amazon.awssdk.services.costexplorer.model.TagValues;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maps AWS Cost Anomaly Detection monitors to environments (E21-T01), so each anomaly can be
 * routed to one environment's owners. A CUSTOM monitor on the tag {@code <prefix>environment}
 * with exactly one value equal to an environment's name links itself; others are linked by an
 * admin. With cost.anomalies.manage-monitors (off by default: it changes AWS state) the app
 * creates one such monitor for every active environment that has none.
 */
@Service
public class CostAnomalyMonitorService {

    private static final Logger log = LoggerFactory.getLogger(CostAnomalyMonitorService.class);

    private final CostAnomalyMonitorRepository monitorRepository;
    private final EnvironmentRepository environmentRepository;
    private final CostExplorerClientProvider clientProvider;
    private final boolean manageMonitors;
    private final String tagKeyPrefix;

    public CostAnomalyMonitorService(CostAnomalyMonitorRepository monitorRepository, EnvironmentRepository environmentRepository,
                                     CostExplorerClientProvider clientProvider,
                                     @Value("${cost.anomalies.manage-monitors:false}") boolean manageMonitors,
                                     @Value("${cost.tagging.key-prefix:tcg:}") String tagKeyPrefix) {
        this.monitorRepository = monitorRepository;
        this.environmentRepository = environmentRepository;
        this.clientProvider = clientProvider;
        this.manageMonitors = manageMonitors;
        this.tagKeyPrefix = tagKeyPrefix;
    }

    /** What a sync did; {@code warning} explains a stop (no credentials, quota reached). */
    public record SyncResult(int monitors, int linked, int created, String warning) {}

    public record MonitorView(String monitorArn, String monitorName, String monitorType, String environmentId,
                              String environmentName, boolean managed, boolean linkedManually, Timestamp lastSeenAt) {}

    @Transactional(readOnly = true)
    public List<MonitorView> list() {
        Map<String, Environment> environments = environmentRepository.findAll().stream()
                .collect(Collectors.toMap(Environment::getEnvironmentId, Function.identity()));
        return monitorRepository.findAllByOrderByMonitorNameAsc().stream().map(m -> {
            Environment e = m.getEnvironmentId() == null ? null : environments.get(m.getEnvironmentId());
            return new MonitorView(m.getMonitorArn(), m.getMonitorName(), m.getMonitorType(), m.getEnvironmentId(),
                    e == null ? null : (e.getDisplayName() != null ? e.getDisplayName() : e.getName()),
                    Boolean.TRUE.equals(m.getManaged()), Boolean.TRUE.equals(m.getLinkedManually()), m.getLastSeenAt());
        }).toList();
    }

    /** Refreshes the monitors from AWS, then provisions missing ones when enabled. */
    @Transactional
    public SyncResult sync() {
        if (!clientProvider.isConfigured()) {
            return new SyncResult(0, 0, 0, "AWS credentials are not configured");
        }
        int[] counts = refreshFromAws();
        int created = 0;
        String warning = null;
        if (manageMonitors) {
            try {
                created = provisionMissing();
            } catch (LimitExceededException e) {
                warning = "The account's anomaly monitor quota is reached; some environments have no monitor";
                log.warn("Anomaly monitor quota reached: {}", e.getMessage());
            }
        }
        return new SyncResult(counts[0], counts[1], created, warning);
    }

    /** Upserts every AWS monitor and auto-links tag monitors; returns [monitors seen, newly linked]. */
    int[] refreshFromAws() {
        Map<String, String> environmentIdsByName = environmentRepository.findAll().stream()
                .collect(Collectors.toMap(Environment::getName, Environment::getEnvironmentId, (a, b) -> a));
        Timestamp now = Timestamp.from(Instant.now());
        int seen = 0;
        int linked = 0;
        String token = null;
        do {
            GetAnomalyMonitorsResponse page = clientProvider.client().getAnomalyMonitors(
                    GetAnomalyMonitorsRequest.builder().nextPageToken(token).build());
            for (AnomalyMonitor m : page.anomalyMonitors()) {
                seen++;
                CostAnomalyMonitor row = monitorRepository.findById(m.monitorArn()).orElseGet(() -> {
                    CostAnomalyMonitor fresh = new CostAnomalyMonitor();
                    fresh.setMonitorArn(m.monitorArn());
                    return fresh;
                });
                row.setMonitorName(m.monitorName() == null ? m.monitorArn() : m.monitorName());
                row.setMonitorType(m.monitorTypeAsString());
                row.setLastSeenAt(now);
                if (row.getEnvironmentId() == null && !Boolean.TRUE.equals(row.getLinkedManually())) {
                    String environmentName = taggedEnvironment(m.monitorSpecification());
                    String environmentId = environmentName == null ? null : environmentIdsByName.get(environmentName);
                    if (environmentId != null) {
                        row.setEnvironmentId(environmentId);
                        linked++;
                    }
                }
                monitorRepository.save(row);
            }
            token = page.nextPageToken();
        } while (token != null && !token.isEmpty());
        return new int[] {seen, linked};
    }

    /** The environment name of a monitor on {@code <prefix>environment} = one value, else null. */
    String taggedEnvironment(Expression spec) {
        if (spec == null || spec.tags() == null) {
            return null;
        }
        TagValues tags = spec.tags();
        if (!(tagKeyPrefix + "environment").equals(tags.key()) || tags.values() == null || tags.values().size() != 1) {
            return null;
        }
        return tags.values().get(0);
    }

    /** One CUSTOM tag monitor per active environment without a linked monitor (manage-monitors only). */
    int provisionMissing() {
        Set<String> watched = new HashSet<>();
        monitorRepository.findByEnvironmentIdIsNotNull().forEach(m -> watched.add(m.getEnvironmentId()));
        int created = 0;
        for (Environment environment : environmentRepository.findByIsActiveTrue()) {
            if (watched.contains(environment.getEnvironmentId())) {
                continue;
            }
            String name = "tcg-env-" + environment.getName();
            AnomalyMonitor monitor = AnomalyMonitor.builder()
                    .monitorName(name.length() > 1024 ? name.substring(0, 1024) : name)
                    .monitorType(MonitorType.CUSTOM)
                    .monitorSpecification(Expression.builder().tags(TagValues.builder()
                            .key(tagKeyPrefix + "environment").values(environment.getName())
                            .matchOptions(MatchOption.EQUALS).build()).build())
                    .build();
            String arn = clientProvider.client().createAnomalyMonitor(
                    CreateAnomalyMonitorRequest.builder().anomalyMonitor(monitor).build()).monitorArn();
            CostAnomalyMonitor row = new CostAnomalyMonitor();
            row.setMonitorArn(arn);
            row.setMonitorName(name);
            row.setMonitorType(MonitorType.CUSTOM.toString());
            row.setEnvironmentId(environment.getEnvironmentId());
            row.setManaged(true);
            row.setLastSeenAt(Timestamp.from(Instant.now()));
            monitorRepository.save(row);
            created++;
            log.info("Created anomaly monitor {} for environment {}", arn, environment.getName());
        }
        return created;
    }

    /** Links a monitor to an environment (or unlinks with null); a sync then leaves it alone. */
    @Transactional
    public MonitorView link(String monitorArn, String environmentId) {
        CostAnomalyMonitor monitor = monitorRepository.findById(monitorArn)
                .orElseThrow(() -> new ResourceNotFoundException("CostAnomalyMonitor", monitorArn));
        if (environmentId != null && !environmentId.isBlank() && !environmentRepository.existsById(environmentId)) {
            throw new ValidationException("Environment not found: " + environmentId);
        }
        monitor.setEnvironmentId(environmentId == null || environmentId.isBlank() ? null : environmentId);
        monitor.setLinkedManually(true);
        monitorRepository.save(monitor);
        return list().stream().filter(v -> v.monitorArn().equals(monitorArn)).findFirst().orElseThrow();
    }
}
