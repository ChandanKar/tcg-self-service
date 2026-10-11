package com.tcgdigital.vmcontrol.service.jira;

import com.tcgdigital.vmcontrol.dto.IdleWasteRowDTO;
import com.tcgdigital.vmcontrol.dto.RightsizingCandidateDTO;
import com.tcgdigital.vmcontrol.exception.IntegrationException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.CostAnomaly;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.ExternalTicket;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.repository.CostAnomalyRepository;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.ExternalTicketRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.CostAnomalyService;
import com.tcgdigital.vmcontrol.service.CostEstimationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.*;

/**
 * Jira tickets for rightsizing and idle recommendations and cost anomalies (E24-T06). One ticket
 * per source: asking again returns the existing issue. The project comes from jira.project-by-team
 * ("Team name or slug=KEY,...") for the environment's team, else jira.project-key. Off unless
 * jira.enabled.
 */
@Service
public class JiraTicketService {

    private static final Logger log = LoggerFactory.getLogger(JiraTicketService.class);
    static final String SYSTEM = "JIRA";

    private final JiraClient jiraClient;
    private final ExternalTicketRepository ticketRepository;
    private final CostEstimationService costEstimationService;
    private final CostAnomalyRepository anomalyRepository;
    private final CostAnomalyService anomalyService;
    private final EnvironmentRepository environmentRepository;
    private final AuditService auditService;

    @Value("${jira.enabled:false}")
    private boolean enabled;

    @Value("${jira.project-key:}")
    private String projectKey = "";

    @Value("${jira.issue-type:Task}")
    private String issueType = "Task";

    @Value("${jira.labels:vmcontrol,finops}")
    private String labels = "vmcontrol,finops";

    @Value("${jira.project-by-team:}")
    private String projectByTeam = "";

    @Value("${chat.public-base-url:}")
    private String publicBaseUrl = "";

    public JiraTicketService(JiraClient jiraClient, ExternalTicketRepository ticketRepository,
                             CostEstimationService costEstimationService, CostAnomalyRepository anomalyRepository,
                             CostAnomalyService anomalyService, EnvironmentRepository environmentRepository,
                             AuditService auditService) {
        this.jiraClient = jiraClient;
        this.ticketRepository = ticketRepository;
        this.costEstimationService = costEstimationService;
        this.anomalyRepository = anomalyRepository;
        this.anomalyService = anomalyService;
        this.environmentRepository = environmentRepository;
        this.auditService = auditService;
    }

    /** What the UI needs to show or hide the buttons. */
    public record Status(boolean enabled, boolean configured, String baseUrl) {}

    public Status status() {
        return new Status(enabled, jiraClient.isConfigured() && !projectKey.isBlank(), enabled ? jiraClient.baseUrl() : null);
    }

    /** A ticket for a source; created = false when it already existed. */
    public record TicketResult(String sourceType, String sourceId, String issueKey, String url, boolean created) {}

    /** What a ticket is about: its environment (null = none), summary and description. */
    record Content(String environmentId, String summary, List<JiraClient.Paragraph> description) {}

    public static ExternalTicket.SourceType sourceType(String value) {
        try {
            return ExternalTicket.SourceType.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("sourceType must be RIGHTSIZING, IDLE or ANOMALY");
        }
    }

    /** The source's environment, for authorization; 404 when the source does not exist. */
    public String environmentOf(ExternalTicket.SourceType type, String sourceId) {
        return content(type, sourceId).environmentId();
    }

    /** Existing tickets for these sources, by source id. */
    public Map<String, TicketResult> existing(ExternalTicket.SourceType type, Collection<String> sourceIds) {
        Map<String, TicketResult> out = new LinkedHashMap<>();
        if (sourceIds.isEmpty()) {
            return out;
        }
        ticketRepository.findBySourceTypeAndSystemAndSourceIdIn(type, SYSTEM, sourceIds)
                .forEach(t -> out.put(t.getSourceId(), result(t, false)));
        return out;
    }

    public TicketResult create(ExternalTicket.SourceType type, String sourceId, String actorId) {
        if (!enabled) {
            throw new IntegrationException(HttpStatus.CONFLICT, "jira_disabled", "Jira integration is disabled");
        }
        Optional<ExternalTicket> existing = ticketRepository.findBySourceTypeAndSourceIdAndSystem(type, sourceId, SYSTEM);
        if (existing.isPresent()) {
            return result(existing.get(), false);
        }
        Content content = content(type, sourceId);
        Environment environment = content.environmentId() == null ? null
                : environmentRepository.findById(content.environmentId()).orElse(null);
        String project = projectFor(environment == null ? null : environment.getTeam());
        if (project == null || project.isBlank()) {
            throw new IntegrationException(HttpStatus.CONFLICT, "jira_not_configured", "Jira is not configured: set JIRA_PROJECT_KEY");
        }
        JiraClient.Issue issue = jiraClient.createIssue(project, issueType, content.summary(), content.description(), labelList());
        ExternalTicket ticket = new ExternalTicket(UUID.randomUUID().toString(), type, sourceId, SYSTEM, issue.key(), issue.url(),
                content.environmentId(), actorId, new Timestamp(System.currentTimeMillis()));
        try {
            ticketRepository.saveAndFlush(ticket);
        } catch (DataIntegrityViolationException e) {
            // Someone created one at the same moment: keep theirs (the extra Jira issue is logged).
            log.warn("Jira issue {} duplicates an existing ticket for {} {}", issue.key(), type, sourceId);
            return ticketRepository.findBySourceTypeAndSourceIdAndSystem(type, sourceId, SYSTEM)
                    .map(t -> result(t, false)).orElseThrow(() -> e);
        }
        auditService.logAction(actorId, AuditAction.JIRA_TICKET_CREATED, "external_ticket", ticket.getTicketId(), issue.key(),
                "Created Jira " + issue.key() + " for " + type + " " + sourceId + ": " + content.summary());
        return result(ticket, true);
    }

    String projectFor(Team team) {
        if (team != null && projectByTeam != null && !projectByTeam.isBlank()) {
            for (String pair : projectByTeam.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = pair.substring(0, eq).trim();
                if (name.equalsIgnoreCase(team.getDisplayName()) || name.equalsIgnoreCase(team.getName())) {
                    return pair.substring(eq + 1).trim();
                }
            }
        }
        return projectKey;
    }

    private List<String> labelList() {
        return Arrays.stream(labels == null ? new String[0] : labels.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).map(s -> s.replace(' ', '-')).distinct().toList();
    }

    Content content(ExternalTicket.SourceType type, String sourceId) {
        return switch (type) {
            case RIGHTSIZING -> {
                RightsizingCandidateDTO r = costEstimationService.getRightsizingCandidates(PageRequest.of(0, Integer.MAX_VALUE))
                        .getContent().stream().filter(c -> sourceId.equals(c.vmId())).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Rightsizing recommendation", sourceId));
                String vm = r.vmName() != null ? r.vmName() : r.vmId();
                List<JiraClient.Paragraph> d = new ArrayList<>(List.of(
                        JiraClient.Paragraph.text("VM Control recommends resizing " + vm + " from " + r.currentInstanceType()
                                + " to " + r.suggestedInstanceType() + " (" + nvl(r.direction()) + ")."),
                        JiraClient.Paragraph.text("Environment: " + nvl(r.environmentName()) + team(r.environmentId())),
                        JiraClient.Paragraph.text("Evidence: average CPU " + pct(r.avgCpuUtilization()) + ", peak "
                                + pct(r.peakCpuUtilization()) + "; source " + nvl(r.source()) + "."),
                        JiraClient.Paragraph.text("Cost: " + money(r.currentMonthlyCost()) + "/month now, about "
                                + money(r.estimatedMonthlyCostAfter()) + "/month after; estimated saving "
                                + money(r.estimatedMonthlySavings()) + "/month.")));
                link(d, "#/cost-management");
                yield new Content(r.environmentId(), "[VM Control] Rightsize " + vm + " (" + r.currentInstanceType() + " -> "
                        + r.suggestedInstanceType() + "), est. " + money(r.estimatedMonthlySavings()) + "/month", d);
            }
            case IDLE -> {
                IdleWasteRowDTO r = costEstimationService.getIdleWaste(PageRequest.of(0, Integer.MAX_VALUE))
                        .getContent().stream().filter(c -> sourceId.equals(c.vmId())).findFirst()
                        .orElseThrow(() -> new ResourceNotFoundException("Idle recommendation", sourceId));
                String vm = r.vmName() != null ? r.vmName() : r.vmId();
                List<JiraClient.Paragraph> d = new ArrayList<>(List.of(
                        JiraClient.Paragraph.text(vm + " looks idle and costs " + money(r.monthlyIdleCost()) + "/month while idle."),
                        JiraClient.Paragraph.text("Environment: " + nvl(r.environmentName()) + team(r.environmentId())
                                + (r.groupName() == null ? "" : "; group " + r.groupName())),
                        JiraClient.Paragraph.text("Evidence: latest CPU " + pct(r.latestCpuUtilization())
                                + (r.idleSince() == null ? "" : ", idle since " + r.idleSince().toInstant()) + "."),
                        JiraClient.Paragraph.text("Consider stopping it on a schedule, turning on idle auto-stop, or removing it.")));
                link(d, "#/cost-management");
                yield new Content(r.environmentId(), "[VM Control] Idle " + vm + ": " + money(r.monthlyIdleCost()) + "/month wasted", d);
            }
            case ANOMALY -> {
                CostAnomaly a = anomalyRepository.findById(sourceId)
                        .orElseThrow(() -> new ResourceNotFoundException("Cost anomaly", sourceId));
                Environment env = a.getEnvironmentId() == null ? null : environmentRepository.findById(a.getEnvironmentId()).orElse(null);
                String where = env == null ? "an unlinked monitor" : name(env);
                String cause = anomalyService.topCause(a);
                List<JiraClient.Paragraph> d = new ArrayList<>(List.of(
                        JiraClient.Paragraph.text("AWS Cost Anomaly Detection found unexpected spend of " + money(a.getTotalImpact())
                                + " in " + where + (a.getAnomalyStart() == null ? "" : " since " + a.getAnomalyStart()) + "."),
                        JiraClient.Paragraph.text("Environment: " + where + team(a.getEnvironmentId())),
                        JiraClient.Paragraph.text("Top cause: " + (cause == null ? "not reported" : cause) + ".")));
                link(d, a.getEnvironmentId() == null ? "#/cost-management" : "#/environments/" + a.getEnvironmentId());
                yield new Content(a.getEnvironmentId(), "[VM Control] Cost anomaly in " + where + ": " + money(a.getTotalImpact())
                        + (a.getAnomalyStart() == null ? "" : " since " + a.getAnomalyStart()), d);
            }
        };
    }

    private void link(List<JiraClient.Paragraph> description, String hash) {
        if (publicBaseUrl != null && !publicBaseUrl.isBlank()) {
            description.add(new JiraClient.Paragraph("Open in VM Control", publicBaseUrl.replaceAll("/+$", "") + "/home" + hash));
        }
    }

    private String team(String environmentId) {
        if (environmentId == null) {
            return "";
        }
        return environmentRepository.findById(environmentId).map(Environment::getTeam)
                .map(t -> "; team " + t.getDisplayName() + (t.getCostCenter() == null ? "" : " (cost center " + t.getCostCenter() + ")"))
                .orElse("");
    }

    private static String name(Environment environment) {
        return environment.getDisplayName() != null ? environment.getDisplayName() : environment.getName();
    }

    private static String nvl(String s) {
        return s == null ? "unknown" : s;
    }

    private static String pct(BigDecimal value) {
        return value == null ? "n/a" : value.setScale(1, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    static String money(BigDecimal value) {
        return "$" + String.format(Locale.US, "%,.2f", value == null ? BigDecimal.ZERO : value);
    }

    private static TicketResult result(ExternalTicket t, boolean created) {
        return new TicketResult(t.getSourceType().name(), t.getSourceId(), t.getIssueKey(), t.getUrl(), created);
    }
}
