package com.tcgdigital.vmcontrol.controller;

import com.jayway.jsonpath.JsonPath;
import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessScopeType;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.NotificationType;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.model.TeamMember;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.repository.NotificationRepository;
import com.tcgdigital.vmcontrol.repository.TeamMemberRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U10-T02: bulk grants. The preview says per person and scope NEW / EXTEND / UPGRADE / SKIP and
 * writes nothing; applying writes the changing rows in one go, never lowers a level or moves an
 * end earlier, skips the reviewer's own access, sends one message per person, refuses everything
 * when one target is not the reviewer's, expands a team as of now, and caps the rows.
 */
class BulkAccessGrantIntegrationTest extends SecuredWebTestBase {

    private static final String BULK = "/api/v1/access-grants/bulk";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    @Autowired private SecurityService securityService;
    @Autowired private NotificationRepository notifications;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Object originalScope;
    private Environment crm;
    private Environment payments;
    private User priya;
    private User neha;
    private User ravi;

    @BeforeEach
    void setUp() {
        originalScope = ReflectionTestUtils.getField(securityService, "envAdminScope");
        crm = newEnvironment("CRM UAT");
        payments = newEnvironment("Payments Dev");
        priya = newUser("priya." + tag() + "@bulk.test", false, false);
        neha = newUser("neha." + tag() + "@bulk.test", false, false);
        ravi = newUser("ravi." + tag() + "@bulk.test", false, false);
    }

    @AfterEach
    void restoreScope() {
        ReflectionTestUtils.setField(securityService, "envAdminScope", originalScope);
    }

    private static String tag() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private EnvironmentAccess grantUntil(User user, Environment env, AccessLevel level, Instant end) {
        EnvironmentAccess grant = grantEnv(user, env.getEnvironmentId(), level);
        grant.setExpiresAt(end == null ? null : Timestamp.from(end));
        return environmentAccessRepository.saveAndFlush(grant);
    }

    private Optional<EnvironmentAccess> active(User user, Environment env) {
        return environmentAccessRepository.findActiveByUserAndScope(user.getUserId(), AccessScopeType.ENVIRONMENT,
                env.getEnvironmentId(), new Timestamp(System.currentTimeMillis()));
    }

    private static String quoted(List<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    private static String body(List<User> people, List<Environment> envs, String level, String extra) {
        String targets = envs.stream().map(e -> "{\"environmentId\":\"" + e.getEnvironmentId() + "\",\"scopeType\":\"ENVIRONMENT\"}")
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"userEmails\":" + quoted(people.stream().map(User::getEmail).toList()) + ",\"targets\":" + targets
                + ",\"accessLevel\":\"" + level + "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private ResultActions send(String url, RequestPostProcessor who, String json) throws Exception {
        return mockMvc.perform(post(url).with(who).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long grantsOf(User user) {
        return environmentAccessRepository.findAll().stream().filter(a -> a.getUser().getUserId().equals(user.getUserId())).count();
    }

    @Test
    void thePreviewSaysWhatEachRowDoesAndWritesNothing() throws Exception {
        Instant now = Instant.now();
        grantUntil(priya, crm, AccessLevel.USER, now.plus(Duration.ofDays(3)));
        grantUntil(neha, payments, AccessLevel.ADMIN, null);
        String json = body(List.of(priya, neha, ravi), List.of(crm, payments), "USER", "\"durationDays\":30");

        String result = send(BULK + "/preview", asAdmin(), json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.people").value(3))
                .andExpect(jsonPath("$.environments").value(2))
                .andExpect(jsonPath("$.changes").value(5))
                .andExpect(jsonPath("$.skipped").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(outcome(result, priya, crm)).isEqualTo("EXTEND");
        assertThat(outcome(result, priya, payments)).isEqualTo("NEW");
        assertThat(outcome(result, neha, crm)).isEqualTo("NEW");
        assertThat(outcome(result, neha, payments)).isEqualTo("SKIP");
        assertThat(field(result, neha, payments, "reason")).isEqualTo("Already has Admin, which is more");
        assertThat(field(result, priya, crm, "currentLevel")).isEqualTo("USER");
        assertThat(grantsOf(ravi)).isZero();
        assertThat(grantsOf(priya)).isEqualTo(1);
    }

    private static String outcome(String json, User user, Environment env) {
        return field(json, user, env, "outcome");
    }

    private static String field(String json, User user, Environment env, String name) {
        List<Object> values = JsonPath.read(json, "$.rows[?(@.userId == '" + user.getUserId() + "' && @.environmentId == '"
                + env.getEnvironmentId() + "')]." + name);
        assertThat(values).hasSize(1);
        return values.get(0) == null ? null : String.valueOf(values.get(0));
    }

    @Test
    void applyingWritesTheChangesAndSendsOneMessagePerPerson() throws Exception {
        Instant now = Instant.now();
        grantUntil(priya, crm, AccessLevel.USER, now.plus(Duration.ofDays(3)));
        grantUntil(neha, payments, AccessLevel.ADMIN, null);
        String json = body(List.of(priya, neha, ravi), List.of(crm, payments), "USER", "\"durationDays\":30");

        send(BULK, asAdmin(), json)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.changes").value(5));

        assertThat(active(neha, payments)).get().extracting(EnvironmentAccess::getAccessLevel).isEqualTo(AccessLevel.ADMIN);
        assertThat(active(priya, crm).orElseThrow().getExpiresAt().toInstant()).isAfter(now.plus(Duration.ofDays(29)));
        assertThat(active(ravi, crm)).isPresent();
        assertThat(active(ravi, payments)).isPresent();
        assertThat(active(neha, crm)).get().extracting(EnvironmentAccess::getAccessLevel).isEqualTo(AccessLevel.USER);
        awaitAsync(() -> {
            for (User person : List.of(priya, neha, ravi)) {
                assertThat(notifications.findAll()).filteredOn(n -> n.getUserId().equals(person.getUserId())
                        && n.getType() == NotificationType.ACCESS_GRANTED).as(person.getEmail()).hasSize(1);
            }
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log WHERE action_type = 'ACCESS_GRANTED' "
                    + "AND environment_id IN (?, ?)", Integer.class, crm.getEnvironmentId(), payments.getEnvironmentId())).isEqualTo(4);
        });
    }

    @Test
    void anUpgradeNeverMovesTheEndEarlier() throws Exception {
        Instant end = Instant.now().plus(Duration.ofDays(40));
        grantUntil(priya, crm, AccessLevel.VIEWER, end);

        String result = send(BULK, asAdmin(), body(List.of(priya), List.of(crm), "USER", "\"durationDays\":7"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(outcome(result, priya, crm)).isEqualTo("UPGRADE");
        EnvironmentAccess after = active(priya, crm).orElseThrow();
        assertThat(after.getAccessLevel()).isEqualTo(AccessLevel.USER);
        assertThat(Math.abs(after.getExpiresAt().toInstant().toEpochMilli() - end.toEpochMilli())).isLessThan(2000);
    }

    @Test
    void theSameLevelOnlyChangesForALaterEnd() throws Exception {
        Instant end = Instant.now().plus(Duration.ofDays(40));
        grantUntil(priya, crm, AccessLevel.USER, end);
        grantUntil(neha, crm, AccessLevel.USER, null);
        String day = DAY.format(LocalDateTime.ofInstant(end, java.time.ZoneId.systemDefault()));

        String shorter = send(BULK + "/preview", asAdmin(), body(List.of(priya, neha), List.of(crm), "USER", "\"durationDays\":7"))
                .andReturn().getResponse().getContentAsString();
        assertThat(outcome(shorter, priya, crm)).isEqualTo("SKIP");
        assertThat(field(shorter, priya, crm, "reason")).isEqualTo("Already has Operator until " + day);
        assertThat(field(shorter, neha, crm, "reason")).isEqualTo("Already has Operator with no end date");

        String open = send(BULK + "/preview", asAdmin(), body(List.of(priya, neha), List.of(crm), "USER", "\"clearExpiry\":true"))
                .andReturn().getResponse().getContentAsString();
        assertThat(outcome(open, priya, crm)).isEqualTo("EXTEND");
        assertThat(field(open, priya, crm, "resultExpiresAt")).isNull();
        assertThat(outcome(open, neha, crm)).isEqualTo("SKIP");
    }

    @Test
    void theReviewersOwnAccessIsSkipped() throws Exception {
        String result = send(BULK + "/preview", asAdmin(), body(List.of(admin, ravi), List.of(crm), "VIEWER", ""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(outcome(result, admin, crm)).isEqualTo("SKIP");
        assertThat(field(result, admin, crm, "reason")).isEqualTo("You can't change your own access");
        assertThat(outcome(result, ravi, crm)).isEqualTo("NEW");
    }

    @Test
    void oneTargetTheReviewerDoesNotManageRefusesEverything() throws Exception {
        ReflectionTestUtils.setField(securityService, "envAdminScope", "assigned");
        Environment third = newEnvironment("HR Portal");
        grantEnv(envAdmin, crm.getEnvironmentId(), AccessLevel.ADMIN);
        grantEnv(envAdmin, payments.getEnvironmentId(), AccessLevel.ADMIN);

        expectError(send(BULK, asEnvAdmin(), body(List.of(ravi), List.of(crm, payments, third), "USER", "")), 403);
        assertThat(grantsOf(ravi)).isZero();
        send(BULK, asEnvAdmin(), body(List.of(ravi), List.of(crm, payments), "USER", "")).andExpect(status().isCreated());
        assertThat(grantsOf(ravi)).isEqualTo(2);
    }

    @Test
    void aTeamAddsItsMembersAsOfNow() throws Exception {
        Team team = new Team();
        team.setTeamId(UUID.randomUUID().toString());
        team.setName("bulk-team-" + tag());
        team.setDisplayName("Bulk team");
        team = teams.saveAndFlush(team);
        Timestamp now = new Timestamp(System.currentTimeMillis());
        members.saveAndFlush(new TeamMember(team.getTeamId(), priya.getUserId(), TeamMember.Source.MANUAL, now));
        members.saveAndFlush(new TeamMember(team.getTeamId(), neha.getUserId(), TeamMember.Source.MANUAL, now));
        String json = "{\"teamIds\":[\"" + team.getTeamId() + "\"],\"targets\":[{\"environmentId\":\"" + crm.getEnvironmentId()
                + "\",\"scopeType\":\"ENVIRONMENT\"}],\"accessLevel\":\"VIEWER\"}";

        send(BULK, asAdmin(), json).andExpect(status().isCreated()).andExpect(jsonPath("$.people").value(2));
        members.saveAndFlush(new TeamMember(team.getTeamId(), ravi.getUserId(), TeamMember.Source.MANUAL, now));
        assertThat(active(priya, crm)).isPresent();
        assertThat(active(neha, crm)).isPresent();
        assertThat(active(ravi, crm)).isEmpty();
    }

    @Test
    void groupsAreRowsOfTheirOwn() throws Exception {
        VmGroup application = newGroup(crm, "Application");
        VmGroup database = newGroup(crm, "Database");
        String json = "{\"userEmails\":[\"" + ravi.getEmail() + "\"],\"targets\":[{\"environmentId\":\"" + crm.getEnvironmentId()
                + "\",\"scopeType\":\"GROUP\",\"groupIds\":[\"" + application.getGroupId() + "\",\"" + database.getGroupId()
                + "\"]}],\"accessLevel\":\"USER\"}";
        send(BULK + "/preview", asAdmin(), json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes").value(2))
                .andExpect(jsonPath("$.rows[*].scopeName", org.hamcrest.Matchers.containsInAnyOrder(
                        "CRM UAT, Application group", "CRM UAT, Database group")));
    }

    @Test
    void tooManyRowsAreRefusedWithTheCountAndTheLimit() throws Exception {
        List<User> people = new ArrayList<>();
        for (int i = 0; i < 26; i++) {
            people.add(newUser("many" + i + "." + tag() + "@bulk.test", false, false));
        }
        List<Environment> envs = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            envs.add(newEnvironment("Many " + i));
        }
        expectError(send(BULK + "/preview", asAdmin(), body(people, envs, "VIEWER", "")), 400)
                .andExpect(jsonPath("$.message").value("This would make 520 changes; at most 500 at once"));
    }

    @Test
    void unknownPeopleAreNamedAndUsersMayNotGrant() throws Exception {
        String json = "{\"userEmails\":[\"nobody@bulk.test\",\"" + ravi.getEmail() + "\"],\"targets\":[{\"environmentId\":\""
                + crm.getEnvironmentId() + "\",\"scopeType\":\"ENVIRONMENT\"}],\"accessLevel\":\"USER\"}";
        expectError(send(BULK + "/preview", asAdmin(), json), 400)
                .andExpect(jsonPath("$.message").value("Not people of this app: nobody@bulk.test"));
        send(BULK + "/preview", asUser(), body(List.of(ravi), List.of(crm), "USER", "")).andExpect(status().isForbidden());
        send(BULK, asViewer(), body(List.of(ravi), List.of(crm), "USER", "")).andExpect(status().isForbidden());
        expectError(send(BULK + "/preview", asAdmin(), body(List.of(ravi), List.of(crm), "USER",
                "\"durationDays\":7,\"clearExpiry\":true")), 400);
    }
}
