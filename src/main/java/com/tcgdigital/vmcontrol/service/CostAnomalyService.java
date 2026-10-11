package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.CostAnomaly;
import com.tcgdigital.vmcontrol.model.CostAnomalyMonitor;
import com.tcgdigital.vmcontrol.repository.CostAnomalyMonitorRepository;
import com.tcgdigital.vmcontrol.repository.CostAnomalyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.costexplorer.model.Anomaly;
import software.amazon.awssdk.services.costexplorer.model.AnomalyDateInterval;
import software.amazon.awssdk.services.costexplorer.model.GetAnomaliesRequest;
import software.amazon.awssdk.services.costexplorer.model.GetAnomaliesResponse;
import software.amazon.awssdk.services.costexplorer.model.RootCause;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Ingests AWS Cost Anomaly Detection anomalies (E21-T02). One GetAnomalies call per page per run
 * (no monitor filter), each anomaly mapped to an environment through its monitor's link; an
 * unlinked monitor's anomaly is kept with no environment (admins only). A re-reported anomaly is
 * updated in place, keeping when its owners were told and their feedback.
 */
@Service
public class CostAnomalyService {

    private static final Logger log = LoggerFactory.getLogger(CostAnomalyService.class);

    private final CostAnomalyRepository anomalyRepository;
    private final CostAnomalyMonitorRepository monitorRepository;
    private final CostExplorerClientProvider clientProvider;
    private final ObjectMapper objectMapper;
    private final int lookbackDays;

    /** Replaceable in tests. */
    private Clock clock = Clock.systemUTC();

    public CostAnomalyService(CostAnomalyRepository anomalyRepository, CostAnomalyMonitorRepository monitorRepository,
                              CostExplorerClientProvider clientProvider, ObjectMapper objectMapper,
                              @Value("${cost.anomalies.lookback-days:7}") int lookbackDays) {
        this.anomalyRepository = anomalyRepository;
        this.monitorRepository = monitorRepository;
        this.clientProvider = clientProvider;
        this.objectMapper = objectMapper;
        this.lookbackDays = lookbackDays;
    }

    /** Anomalies seen and how many were new. */
    public record IngestResult(int seen, int added) {}

    @Transactional
    public IngestResult ingest() {
        if (!clientProvider.isConfigured()) {
            log.info("Cost anomaly ingestion skipped: AWS credentials are not configured");
            return new IngestResult(0, 0);
        }
        Map<String, String> environmentByMonitor = monitorRepository.findAll().stream()
                .filter(m -> m.getEnvironmentId() != null)
                .collect(Collectors.toMap(CostAnomalyMonitor::getMonitorArn, CostAnomalyMonitor::getEnvironmentId));
        LocalDate today = LocalDate.now(clock);
        AnomalyDateInterval interval = AnomalyDateInterval.builder()
                .startDate(today.minusDays(lookbackDays).toString()).endDate(today.toString()).build();
        Timestamp now = Timestamp.from(clock.instant());
        int seen = 0;
        int added = 0;
        String token = null;
        do {
            GetAnomaliesResponse page = clientProvider.client().getAnomalies(GetAnomaliesRequest.builder()
                    .dateInterval(interval).nextPageToken(token).build());
            for (Anomaly anomaly : page.anomalies()) {
                seen++;
                CostAnomaly row = anomalyRepository.findById(anomaly.anomalyId()).orElse(null);
                if (row == null) {
                    row = new CostAnomaly();
                    row.setAnomalyId(anomaly.anomalyId());
                    row.setFirstSeenAt(now);
                    added++;
                }
                apply(row, anomaly, environmentByMonitor.get(anomaly.monitorArn()));
                row.setLastSeenAt(now);
                anomalyRepository.save(row);
            }
            token = page.nextPageToken();
        } while (token != null && !token.isEmpty());
        if (added > 0) {
            log.info("Ingested {} new cost anomal{} ({} seen)", added, added == 1 ? "y" : "ies", seen);
        }
        return new IngestResult(seen, added);
    }

    /** Copies AWS's view onto the row; notifiedAt and feedback stay the app's. */
    void apply(CostAnomaly row, Anomaly anomaly, String environmentId) {
        row.setMonitorArn(anomaly.monitorArn());
        row.setEnvironmentId(environmentId);
        row.setAnomalyStart(date(anomaly.anomalyStartDate()));
        row.setAnomalyEnd(date(anomaly.anomalyEndDate()));
        if (anomaly.impact() != null) {
            row.setTotalImpact(money(anomaly.impact().totalImpact()));
            row.setTotalExpected(money(anomaly.impact().totalExpectedSpend()));
        }
        if (anomaly.anomalyScore() != null) {
            row.setMaxScore(money(anomaly.anomalyScore().maxScore()));
        }
        row.setRootCauses(rootCauses(anomaly.rootCauses()));
    }

    private String rootCauses(List<RootCause> causes) {
        if (causes == null || causes.isEmpty()) {
            return null;
        }
        List<Map<String, String>> out = causes.stream().map(c -> {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("service", c.service());
            m.put("region", c.region());
            m.put("usageType", c.usageType());
            m.put("linkedAccount", c.linkedAccount());
            return m;
        }).toList();
        try {
            return objectMapper.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }

    /** AWS sends "yyyy-MM-dd" or a timestamp starting with it. */
    static LocalDate date(String value) {
        if (value == null || value.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal money(Double value) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
