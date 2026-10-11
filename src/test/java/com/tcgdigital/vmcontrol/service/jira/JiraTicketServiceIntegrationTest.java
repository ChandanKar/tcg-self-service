package com.tcgdigital.vmcontrol.service.jira;

import com.tcgdigital.vmcontrol.exception.IntegrationException;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.CostAnomaly;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.repository.CostAnomalyRepository;
import com.tcgdigital.vmcontrol.repository.ExternalTicketRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.service.TeamService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Jira tickets for anomalies (E24-T06): idempotent, project chosen by team, credential failures
 * leave nothing behind, off means 409, and only those who administer the source may create.
 */
class JiraTicketServiceIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/integrations/jira/tickets";

    @Autowired private JiraTicketService ticketService;
    @Autowired private CostAnomalyRepository anomalies;
    @Autowired private TeamRepository teams;
    @Autowired private ExternalTicketRepository tickets;

    private Object target;
    private Object realClient;
    private JiraClient jira;
    private Environment env;
    private String anomalyId;

    @BeforeEach
    void wire() {
        target = AopTestUtils.getTargetObject(ticketService);
        realClient = ReflectionTestUtils.getField(target, "jiraClient");
        jira = Mockito.mock(JiraClient.class);
        when(jira.isConfigured()).thenReturn(true);
        ReflectionTestUtils.setField(target, "jiraClient", jira);
        ReflectionTestUtils.setField(target, "enabled", true);
        ReflectionTestUtils.setField(target, "projectKey", "OPS");
        ReflectionTestUtils.setField(target, "projectByTeam", "Platform Team=PLAT, data=DATA");

        Team team = new Team();
        team.setTeamId(UUID.randomUUID().toString());
        team.setName(TeamService.slug("Platform Team"));
        team.setDisplayName("Platform Team");
        team = teams.saveAndFlush(team);
        env = newEnvironment("Payments");
        env.setTeam(team);
        environmentRepository.saveAndFlush(env);

        CostAnomaly a = new CostAnomaly();
        anomalyId = "anomaly-" + UUID.randomUUID();
        a.setAnomalyId(anomalyId);
        a.setEnvironmentId(env.getEnvironmentId());
        a.setAnomalyStart(LocalDate.of(2026, 10, 1));
        a.setTotalImpact(new BigDecimal("412.50"));
        a.setRootCauses("[{\"service\":\"Amazon Elastic Compute Cloud - Compute\",\"usageType\":\"BoxUsage:m5.large\"}]");
        a.setFirstSeenAt(new Timestamp(System.currentTimeMillis()));
        a.setLastSeenAt(new Timestamp(System.currentTimeMillis()));
        anomalies.saveAndFlush(a);
    }

    @AfterEach
    void unwire() {
        ReflectionTestUtils.setField(target, "jiraClient", realClient);
        ReflectionTestUtils.setField(target, "enabled", false);
        ReflectionTestUtils.setField(target, "projectKey", "");
        ReflectionTestUtils.setField(target, "projectByTeam", "");
    }

    private String body() {
        return "{\"sourceType\":\"anomaly\",\"sourceId\":\"" + anomalyId + "\"}";
    }

    @Test
    void oneIssuePerSourceInTheTeamsProject() throws Exception {
        when(jira.createIssue(anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(new JiraClient.Issue("PLAT-7", "https://acme.atlassian.net/browse/PLAT-7"));

        mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.issueKey").value("PLAT-7"))
                .andExpect(jsonPath("$.url").value("https://acme.atlassian.net/browse/PLAT-7"))
                .andExpect(jsonPath("$.created").value(true));
        mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueKey").value("PLAT-7"))
                .andExpect(jsonPath("$.created").value(false));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<JiraClient.Paragraph>> description = ArgumentCaptor.forClass(List.class);
        verify(jira, times(1)).createIssue(eq("PLAT"), eq("Task"), summary.capture(), description.capture(), eq(List.of("vmcontrol", "finops")));
        assertThat(summary.getValue()).isEqualTo("[VM Control] Cost anomaly in Payments: $412.50 since 2026-10-01");
        assertThat(description.getValue()).extracting(JiraClient.Paragraph::text)
                .anySatisfy(t -> assertThat(t).contains("team Platform Team"))
                .anySatisfy(t -> assertThat(t).contains("BoxUsage:m5.large"));
        assertThat(tickets.count()).isEqualTo(1);

        mockMvc.perform(get(URL).param("sourceType", "ANOMALY").param("sourceIds", anomalyId + ",other").with(asAdmin()))
                .andExpect(jsonPath("$['" + anomalyId + "'].issueKey").value("PLAT-7"))
                .andExpect(jsonPath("$.other").doesNotExist());
    }

    @Test
    void withoutATeamMappingTheDefaultProjectIsUsed() {
        Team other = new Team();
        other.setDisplayName("Web");
        other.setName("web");
        assertThat(ticketService.projectFor(other)).isEqualTo("OPS");
        assertThat(ticketService.projectFor(null)).isEqualTo("OPS");
        other.setName("data");
        assertThat(ticketService.projectFor(other)).isEqualTo("DATA");
    }

    @Test
    void badCredentialsAre502AndLeaveNoRow() throws Exception {
        when(jira.createIssue(anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenThrow(new IntegrationException(HttpStatus.BAD_GATEWAY, "jira_auth", "Jira credentials rejected"));
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(body())), 502)
                .andExpect(jsonPath("$.message").value("Jira credentials rejected"));
        assertThat(tickets.count()).isZero();
    }

    @Test
    void offIs409AndOnlyAdministratorsMayCreate() throws Exception {
        mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.ADMIN);
        when(jira.createIssue(anyString(), anyString(), anyString(), anyList(), anyList()))
                .thenReturn(new JiraClient.Issue("PLAT-8", "https://acme.atlassian.net/browse/PLAT-8"));
        mockMvc.perform(post(URL).with(asUser()).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isCreated());

        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"ANOMALY\",\"sourceId\":\"missing\"}")), 404);
        ReflectionTestUtils.setField(target, "enabled", false);
        tickets.deleteAll();
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON).content(body())), 409)
                .andExpect(jsonPath("$.message").value("Jira integration is disabled"));
        mockMvc.perform(get("/api/v1/integrations/jira/status").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        expectError(mockMvc.perform(post(URL).with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sourceType\":\"BOGUS\",\"sourceId\":\"x\"}")), 400);
    }
}
