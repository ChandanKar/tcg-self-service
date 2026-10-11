package com.tcgdigital.vmcontrol.service.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.exception.IntegrationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Jira REST client (E24-T06): ADF payload, auth headers, error mapping; the token never appears in logs. */
@ExtendWith(OutputCaptureExtension.class)
class JiraClientTest {

    private static final String BASE = "https://acme.atlassian.net";
    private static final String TOKEN = "SECRET-JIRA-TOKEN";

    private MockRestServiceServer server;

    private JiraClient client(String email) {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        return new JiraClient(builder.build(), new ObjectMapper(), BASE + "/", email, TOKEN);
    }

    private static List<JiraClient.Paragraph> description() {
        return List.of(JiraClient.Paragraph.text("Resize web-1 from m5.xlarge to m5.large."),
                new JiraClient.Paragraph("Open in VM Control", "https://vmc.example.com/home#/cost-management"));
    }

    @Test
    void createsAnIssueWithAnAdfDescriptionAndBasicAuth() {
        JiraClient jira = client("bot@acme.com");
        String basic = "Basic " + Base64.getEncoder().encodeToString(("bot@acme.com:" + TOKEN).getBytes());
        server.expect(once(), requestTo(BASE + "/rest/api/3/issue")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", basic))
                .andExpect(jsonPath("$.fields.project.key").value("PLAT"))
                .andExpect(jsonPath("$.fields.issuetype.name").value("Task"))
                .andExpect(jsonPath("$.fields.summary").value("[VM Control] Rightsize web-1"))
                .andExpect(jsonPath("$.fields.labels", hasSize(2)))
                .andExpect(jsonPath("$.fields.description.type").value("doc"))
                .andExpect(jsonPath("$.fields.description.version").value(1))
                .andExpect(jsonPath("$.fields.description.content", hasSize(2)))
                .andExpect(jsonPath("$.fields.description.content[0].type").value("paragraph"))
                .andExpect(jsonPath("$.fields.description.content[0].content[0].text").value("Resize web-1 from m5.xlarge to m5.large."))
                .andExpect(jsonPath("$.fields.description.content[1].content[0].marks[0].type").value("link"))
                .andExpect(jsonPath("$.fields.description.content[1].content[0].marks[0].attrs.href")
                        .value("https://vmc.example.com/home#/cost-management"))
                .andRespond(withSuccess("{\"id\":\"10001\",\"key\":\"PLAT-42\"}", MediaType.APPLICATION_JSON));

        JiraClient.Issue issue = jira.createIssue("PLAT", "Task", "[VM Control] Rightsize web-1", description(), List.of("vmcontrol", "finops"));
        assertThat(issue.key()).isEqualTo("PLAT-42");
        assertThat(issue.url()).isEqualTo(BASE + "/browse/PLAT-42");
        server.verify();
    }

    @Test
    void withoutAnEmailTheTokenIsABearerPat() {
        JiraClient jira = client("");
        server.expect(once(), requestTo(BASE + "/rest/api/3/issue")).andExpect(header("Authorization", "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"key\":\"OPS-1\"}", MediaType.APPLICATION_JSON));
        assertThat(jira.createIssue("OPS", "Task", "s", description(), List.of()).key()).isEqualTo("OPS-1");
    }

    @Test
    void errorsAreMappedWithoutLeakingTheToken(CapturedOutput output) {
        JiraClient jira = client("bot@acme.com");
        server.expect(once(), requestTo(BASE + "/rest/api/3/issue")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> jira.createIssue("PLAT", "Task", "s", description(), List.of()))
                .isInstanceOfSatisfying(IntegrationException.class, e -> {
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(e.getMessage()).isEqualTo("Jira credentials rejected");
                });

        server.reset();
        server.expect(once(), requestTo(BASE + "/rest/api/3/issue")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errorMessages\":[],\"errors\":{\"project\":\"valid project is required\"}}"));
        assertThatThrownBy(() -> jira.createIssue("NOPE", "Task", "s", description(), List.of()))
                .hasMessage("Jira rejected the issue: project: valid project is required");

        server.reset();
        server.expect(once(), requestTo(BASE + "/rest/api/3/issue")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> jira.createIssue("PLAT", "Task", "s", description(), List.of())).hasMessage("Jira returned HTTP 503");

        server.reset();
        server.expect(once(), requestTo(BASE + "/rest/api/3/issue")).andRespond(request -> { throw new java.net.ConnectException(TOKEN); });
        assertThatThrownBy(() -> jira.createIssue("PLAT", "Task", "s", description(), List.of())).hasMessage("Jira is unreachable");

        assertThat(output.getAll()).doesNotContain(TOKEN);
        assertThatThrownBy(() -> new JiraClient(RestClient.create(), new ObjectMapper(), "", "", "")
                .createIssue("P", "Task", "s", description(), List.of()))
                .isInstanceOfSatisfying(IntegrationException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
