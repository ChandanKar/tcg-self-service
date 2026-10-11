package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.service.aws.AwsClientFactory;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.EksWorkloadCostDaily;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.repository.EksWorkloadCostDailyRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.athena.AthenaClient;
import software.amazon.awssdk.services.athena.model.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Pod-level EKS cost from Split Cost Allocation Data (E26-T04). Queries the CUR 2.0 export's
 * Athena table for the last eks.scad.lookback-days complete UTC days, summing
 * split_line_item_split_cost and split_line_item_unused_cost by day, cluster, namespace and
 * workload. Cluster and namespace come from the pod ARN in line_item_resource_id
 * (arn:aws:eks:region:account:pod/cluster/namespace/pod/uid); the workload from a resource tag
 * (eks.scad.workload-tag-column / -key, "(none)" when absent). Clusters map to environments by
 * their EKS cluster name; rows of unknown clusters are counted and skipped. Every result page is
 * read before anything is written, so a failed query changes no rows; each environment's day is
 * then replaced in its own short transaction. Off unless eks.scad.enabled.
 */
@Service
public class EksSplitCostIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EksSplitCostIngestionService.class);
    static final String JOB_NAME = "eks_split_cost";
    static final String NO_WORKLOAD = "(none)";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]{1,128}");
    private static final Pattern TAG_KEY = Pattern.compile("[A-Za-z0-9_:.\\-]{1,128}");
    private static final Duration QUERY_TIMEOUT = Duration.ofMinutes(10);

    public enum Status { OK, DISABLED, NOT_ENABLED, FAILED }

    public record IngestResult(Status status, int rowsWritten, int rowsSkipped, Set<String> unknownClusters, String message) {
        static IngestResult of(Status status, String message) {
            return new IngestResult(status, 0, 0, Set.of(), message);
        }
    }

    /** One summed result row, before it is mapped to an environment. */
    record SplitCostRow(LocalDate usageDate, String cluster, String namespace, String workload,
                        BigDecimal splitCost, BigDecimal unusedCost) {}

    private final EnvironmentRepository environmentRepository;
    private final EksWorkloadCostDailyRepository costRepository;
    private final AuditService auditService;
    private final TransactionTemplate dayTransaction;
    private final AwsClientFactory awsClientFactory;

    /** The client for a region (the factory's cached one); replaced in tests. */
    Function<String, AthenaClient> clientFactory;
    /** Pause between query-state polls; shortened in tests. */
    long pollMillis = 2000;
    Clock clock = Clock.systemUTC();

    @Value("${eks.scad.enabled:false}")
    private boolean enabled;

    @Value("${eks.scad.athena.database:}")
    private String database = "";

    @Value("${eks.scad.athena.table:}")
    private String table = "";

    @Value("${eks.scad.athena.workgroup:primary}")
    private String workgroup = "primary";

    @Value("${eks.scad.athena.output-location:}")
    private String outputLocation = "";

    @Value("${eks.scad.athena.region:}")
    private String athenaRegion = "";

    @Value("${eks.scad.workload-tag-column:resource_tags}")
    private String workloadTagColumn = "resource_tags";

    @Value("${eks.scad.workload-tag-key:aws_eks_workload_name}")
    private String workloadTagKey = "aws_eks_workload_name";

    @Value("${eks.scad.lookback-days:3}")
    private int lookbackDays = 3;

    @Value("${aws.region:ap-south-1}")
    private String defaultRegion = "ap-south-1";

    public EksSplitCostIngestionService(EnvironmentRepository environmentRepository,
                                        EksWorkloadCostDailyRepository costRepository,
                                        AuditService auditService,
                                        PlatformTransactionManager transactionManager,
                                        AwsClientFactory awsClientFactory) {
        this.awsClientFactory = awsClientFactory;
        this.clientFactory = awsClientFactory::athena;
        this.environmentRepository = environmentRepository;
        this.costRepository = costRepository;
        this.auditService = auditService;
        this.dayTransaction = new TransactionTemplate(transactionManager);
        this.dayTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public IngestResult ingest() {
        if (!enabled) {
            return IngestResult.of(Status.DISABLED, "eks.scad.enabled is false");
        }
        if (!awsClientFactory.isConfigured()) {
            return IngestResult.of(Status.DISABLED, "AWS credentials not configured");
        }
        if (!IDENTIFIER.matcher(nullToEmpty(database)).matches() || !IDENTIFIER.matcher(nullToEmpty(table)).matches()) {
            log.warn("EKS split cost: eks.scad.athena.database and eks.scad.athena.table must be plain identifiers; skipped");
            return IngestResult.of(Status.FAILED, "Athena database or table not configured");
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        LocalDate from = today.minusDays(Math.max(1, lookbackDays));

        IngestResult result;
        try {
            result = run(from, today);
        } catch (Exception e) {
            log.error("EKS split cost ingestion failed: {}", e.getMessage());
            result = IngestResult.of(Status.FAILED, e.getMessage());
        }
        auditService.logAction(null, AuditAction.SCHEDULED_JOB_EXECUTED, "job", JOB_NAME, "EKS split cost ingestion",
                String.format("%s: %d row(s) written, %d skipped (unknown clusters %s) for %s..%s%s", result.status(),
                        result.rowsWritten(), result.rowsSkipped(), result.unknownClusters(), from, today.minusDays(1),
                        result.message() == null ? "" : " - " + result.message()));
        return result;
    }

    private IngestResult run(LocalDate from, LocalDate toExclusive) {
        return run(clientFactory.apply(effectiveRegion()), from, toExclusive);
    }

    private IngestResult run(AthenaClient athena, LocalDate from, LocalDate toExclusive) {
        String queryId;
        try {
            StartQueryExecutionRequest.Builder request = StartQueryExecutionRequest.builder()
                    .queryString(buildQuery())
                    .queryExecutionContext(QueryExecutionContext.builder().database(database).build())
                    .executionParameters(literal(from), literal(toExclusive))
                    .workGroup(workgroup);
            if (outputLocation != null && !outputLocation.isBlank()) {
                request.resultConfiguration(ResultConfiguration.builder().outputLocation(outputLocation).build());
            }
            queryId = athena.startQueryExecution(request.build()).queryExecutionId();
        } catch (InvalidRequestException e) {
            return failure(e.getMessage());
        }

        QueryExecutionState state = awaitCompletion(athena, queryId);
        if (state != QueryExecutionState.SUCCEEDED) {
            String reason = state == null ? "timed out after " + QUERY_TIMEOUT.toMinutes() + " min"
                    : Optional.ofNullable(athena.getQueryExecution(GetQueryExecutionRequest.builder().queryExecutionId(queryId).build())
                            .queryExecution().status().stateChangeReason()).orElse(state.toString());
            return failure(reason);
        }

        List<SplitCostRow> rows = readResults(athena, queryId);
        return write(rows);
    }

    /** Polls until the query finishes; null after the timeout (the query is then stopped). */
    private QueryExecutionState awaitCompletion(AthenaClient athena, String queryId) {
        Instant deadline = clock.instant().plus(QUERY_TIMEOUT);
        while (true) {
            QueryExecutionState state = athena.getQueryExecution(GetQueryExecutionRequest.builder().queryExecutionId(queryId).build())
                    .queryExecution().status().state();
            if (state == QueryExecutionState.SUCCEEDED || state == QueryExecutionState.FAILED || state == QueryExecutionState.CANCELLED) {
                return state;
            }
            if (!clock.instant().isBefore(deadline)) {
                athena.stopQueryExecution(StopQueryExecutionRequest.builder().queryExecutionId(queryId).build());
                return null;
            }
            try {
                Thread.sleep(pollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private List<SplitCostRow> readResults(AthenaClient athena, String queryId) {
        List<SplitCostRow> rows = new ArrayList<>();
        String token = null;
        boolean header = true;
        do {
            GetQueryResultsResponse page = athena.getQueryResults(GetQueryResultsRequest.builder()
                    .queryExecutionId(queryId).nextToken(token).maxResults(1000).build());
            for (Row row : page.resultSet().rows()) {
                if (header) { // the first row of the first page holds the column names
                    header = false;
                    continue;
                }
                List<Datum> d = row.data();
                if (d.size() < 6 || value(d, 0) == null || value(d, 1) == null) {
                    continue;
                }
                rows.add(new SplitCostRow(LocalDate.parse(value(d, 0).substring(0, 10)), value(d, 1),
                        orElse(value(d, 2), NO_WORKLOAD), orElse(value(d, 3), NO_WORKLOAD),
                        decimal(value(d, 4)), decimal(value(d, 5))));
            }
            token = page.nextToken();
        } while (token != null && !token.isEmpty());
        return rows;
    }

    private IngestResult write(List<SplitCostRow> rows) {
        Map<String, String> environmentByCluster = new HashMap<>();
        for (Environment env : environmentRepository.findActiveEksEnvironments()) {
            environmentByCluster.putIfAbsent(env.getEffectiveClusterName(), env.getEnvironmentId());
        }
        Set<String> unknown = new TreeSet<>();
        int skipped = 0;
        // environmentId -> day -> key -> row, so a repeated key is summed rather than written twice
        Map<String, Map<LocalDate, Map<EksWorkloadCostDaily.Key, EksWorkloadCostDaily>>> byDay = new TreeMap<>();
        Timestamp now = Timestamp.from(clock.instant());
        for (SplitCostRow r : rows) {
            String environmentId = environmentByCluster.get(r.cluster());
            if (environmentId == null) {
                unknown.add(r.cluster());
                skipped++;
                continue;
            }
            Date day = Date.valueOf(r.usageDate());
            EksWorkloadCostDaily.Key key = new EksWorkloadCostDaily.Key(environmentId, day, r.namespace(), r.workload());
            byDay.computeIfAbsent(environmentId, e -> new TreeMap<>()).computeIfAbsent(r.usageDate(), d -> new LinkedHashMap<>())
                    .merge(key, new EksWorkloadCostDaily(environmentId, day, r.namespace(), r.workload(), r.splitCost(), r.unusedCost(), now),
                            (a, b) -> new EksWorkloadCostDaily(environmentId, day, r.namespace(), r.workload(),
                                    a.getSplitCost().add(b.getSplitCost()), a.getUnusedCost().add(b.getUnusedCost()), now));
        }
        int written = 0;
        for (Map.Entry<String, Map<LocalDate, Map<EksWorkloadCostDaily.Key, EksWorkloadCostDaily>>> env : byDay.entrySet()) {
            for (Map.Entry<LocalDate, Map<EksWorkloadCostDaily.Key, EksWorkloadCostDaily>> day : env.getValue().entrySet()) {
                Collection<EksWorkloadCostDaily> dayRows = day.getValue().values();
                dayTransaction.executeWithoutResult(status -> {
                    costRepository.deleteDay(env.getKey(), Date.valueOf(day.getKey()));
                    costRepository.flush();
                    costRepository.saveAll(dayRows);
                });
                written += dayRows.size();
            }
        }
        if (!unknown.isEmpty()) {
            log.info("EKS split cost: {} row(s) skipped for clusters with no environment: {}", skipped, unknown);
        }
        log.info("EKS split cost ingestion wrote {} row(s)", written);
        return new IngestResult(Status.OK, written, skipped, unknown, null);
    }

    private IngestResult failure(String reason) {
        String text = reason == null ? "" : reason;
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("TABLE_NOT_FOUND") || upper.contains("COLUMN_NOT_FOUND") || upper.contains("CANNOT BE RESOLVED")
                || upper.contains("DOES NOT EXIST")) {
            log.warn("Split Cost Allocation Data not enabled in Data Exports (or the Athena table lacks its columns): {}", text);
            return IngestResult.of(Status.NOT_ENABLED, "Split Cost Allocation Data not enabled in Data Exports: " + text);
        }
        log.error("EKS split cost Athena query failed: {}", text);
        return IngestResult.of(Status.FAILED, text);
    }

    /** The summing query; dates are execution parameters, the table and tag names validated identifiers. */
    String buildQuery() {
        String workload = TAG_KEY.matcher(nullToEmpty(workloadTagKey)).matches() && IDENTIFIER.matcher(nullToEmpty(workloadTagColumn)).matches()
                ? "element_at(" + workloadTagColumn + ", '" + workloadTagKey + "')"
                : "CAST(NULL AS VARCHAR)";
        return "SELECT CAST(DATE(line_item_usage_start_date) AS VARCHAR) AS usage_date,\n"
                + "       split_part(line_item_resource_id, '/', 2) AS cluster,\n"
                + "       split_part(line_item_resource_id, '/', 3) AS namespace,\n"
                + "       COALESCE(NULLIF(" + workload + ", ''), '" + NO_WORKLOAD + "') AS workload,\n"
                + "       SUM(COALESCE(split_line_item_split_cost, 0)) AS split_cost,\n"
                + "       SUM(COALESCE(split_line_item_unused_cost, 0)) AS unused_cost\n"
                + "FROM \"" + table + "\"\n"
                + "WHERE line_item_resource_id LIKE 'arn:aws:eks:%:pod/%'\n"
                + "  AND line_item_usage_start_date >= CAST(? AS TIMESTAMP)\n"
                + "  AND line_item_usage_start_date < CAST(? AS TIMESTAMP)\n"
                + "GROUP BY 1, 2, 3, 4";
    }

    private static String literal(LocalDate date) {
        return "'" + date + " 00:00:00'";
    }

    private static String value(List<Datum> data, int i) {
        String v = data.get(i).varCharValue();
        return v == null || v.isBlank() ? null : v;
    }

    private static BigDecimal decimal(String v) {
        try {
            return v == null ? BigDecimal.ZERO : new BigDecimal(v);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String orElse(String v, String fallback) {
        return v == null ? fallback : (v.length() > 253 ? v.substring(0, 253) : v);
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    private String effectiveRegion() {
        return athenaRegion != null && !athenaRegion.isBlank() ? athenaRegion : defaultRegion;
    }
}
