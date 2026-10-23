package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.Team;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.EmailLog;
import com.tcgdigital.vmcontrol.repository.EmailLogRepository;
import com.tcgdigital.vmcontrol.repository.TeamMemberRepository;
import com.tcgdigital.vmcontrol.repository.TeamRepository;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U13-T02: Add people in one step. The preview says who is new, who already uses the app and who
 * is skipped, and what access each gets, writing nothing; applying makes the accounts with the
 * role, department, project code and team, grants every person every target, keeps existing
 * people's role, and rolls everything back when a target cannot be granted. Administrators only.
 */
class BulkOnboardIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/users/bulk-onboard";

    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private EmailLogRepository emailLog;
    @Autowired private SettingsService settings;

    private Environment crm;
    private Environment payments;
    private Team team;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        crm = newEnvironment("CRM UAT");
        payments = newEnvironment("Payments Dev");
        team = new Team();
        team.setTeamId(UUID.randomUUID().toString());
        team.setName("data-lake-" + tag);
        team.setDisplayName("Data lake");
        team = teams.saveAndFlush(team);
    }

    @AfterEach
    void switchEmailOff() {
        settings.reset("notification.email.enabled", null);
        settings.reset("notification.email.onboarded.enabled", null);
    }

    private String email(String name) {
        return name + "." + tag + "@onboard.test";
    }

    private ResultActions send(boolean dryRun, RequestPostProcessor who, String json) throws Exception {
        return mockMvc.perform(post(URL).param("dryRun", String.valueOf(dryRun)).with(who)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    /** Three new people (with department, project code and team), the operator (existing), two environments. */
    private String body(String role, String extraAccess) {
        String person = "{\"email\":\"%s\",\"displayName\":\"%s\",\"department\":\"Engineering\",\"projectCode\":\"DLK-204\",\"teamId\":\"%s\"}";
        return "{\"people\":["
                + String.format(person, email("neha"), "Neha Gupta", team.getTeamId()) + ","
                + String.format(person, email("ravi"), "Ravi Kumar", team.getTeamId()) + ","
                + String.format(person, email("asha"), "Asha Rao", team.getTeamId()) + ","
                + "{\"email\":\"" + operator.getEmail() + "\"}],"
                + "\"role\":\"" + role + "\","
                + "\"access\":[{\"environmentId\":\"" + crm.getEnvironmentId() + "\",\"accessLevel\":\"USER\",\"durationDays\":30},"
                + "{\"environmentId\":\"" + payments.getEnvironmentId() + "\",\"accessLevel\":\"USER\",\"durationDays\":30}"
                + extraAccess + "]}";
    }

    private long grantsOf(String userId) {
        return environmentAccessRepository.findAll().stream().filter(a -> a.getUser().getUserId().equals(userId)).count();
    }

    @Test
    void thePreviewClassifiesThePeopleAndCountsTheGrantsWritingNothing() throws Exception {
        send(true, asAdmin(), body("USER", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.newPeople").value(3))
                .andExpect(jsonPath("$.existingPeople").value(1))
                .andExpect(jsonPath("$.skippedPeople").value(0))
                .andExpect(jsonPath("$.newGrants").value(8))
                .andExpect(jsonPath("$.people[3].outcome").value("EXISTING"))
                .andExpect(jsonPath("$.people[3].reason").value("Already a user: access and team only"))
                .andExpect(jsonPath("$.people[0].grantsNew").value(2));
        assertThat(userRepository.findByEmail(email("neha"))).isEmpty();
        assertThat(grantsOf(operator.getUserId())).isZero();
    }

    @Test
    void applyingMakesTheAccountsWithRoleProfileTeamAndEveryGrant() throws Exception {
        send(false, asAdmin(), body("ENV_ADMIN", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.newPeople").value(3))
                .andExpect(jsonPath("$.newGrants").value(8));

        for (String name : List.of("neha", "ravi", "asha")) {
            User user = userRepository.findByEmail(email(name)).orElseThrow();
            assertThat(user.isEnvAdmin()).isTrue();
            assertThat(user.isAdmin()).isFalse();
            assertThat(user.getDepartment()).isEqualTo("Engineering");
            assertThat(user.getProjectCode()).isEqualTo("DLK-204");
            assertThat(members.findByTeamId(team.getTeamId())).anyMatch(m -> m.getUserId().equals(user.getUserId()));
            assertThat(grantsOf(user.getUserId())).isEqualTo(2);
        }
        // The existing person got the access and kept their role and profile.
        User op = userRepository.findById(operator.getUserId()).orElseThrow();
        assertThat(grantsOf(op.getUserId())).isEqualTo(2);
        assertThat(op.isEnvAdmin()).isFalse();
        assertThat(op.getDepartment()).isNull();
    }

    @Test
    void aTargetThatCannotBeGrantedRollsEverythingBack() throws Exception {
        VmGroup elsewhere = newGroup(payments, "web");
        String bad = ",{\"environmentId\":\"" + crm.getEnvironmentId() + "\",\"scopeType\":\"GROUP\",\"groupIds\":[\""
                + elsewhere.getGroupId() + "\"],\"accessLevel\":\"VIEWER\"}";
        expectError(send(false, asAdmin(), body("USER", bad)), 400)
                .andExpect(jsonPath("$.message", containsString("CRM UAT")));
        assertThat(userRepository.findByEmail(email("neha"))).isEmpty();
        assertThat(grantsOf(operator.getUserId())).isZero();
    }

    @Test
    void duplicatesInactivePeopleAndMissingEmailsAreSkippedWithTheReason() throws Exception {
        User gone = newUser(email("gone"), false, false);
        gone.setIsActive(false);
        userRepository.saveAndFlush(gone);
        String json = "{\"people\":[{\"email\":\"" + email("neha") + "\"},{\"email\":\"" + email("neha").toUpperCase()
                + "\"},{\"email\":\"" + gone.getEmail() + "\"},{\"displayName\":\"Nobody\"}],\"role\":\"USER\"}";
        send(true, asAdmin(), json)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newPeople").value(1))
                .andExpect(jsonPath("$.skippedPeople").value(3))
                .andExpect(jsonPath("$.people[1].reason").value("Listed twice"))
                .andExpect(jsonPath("$.people[2].reason").value("Deactivated: reactivate them first"))
                .andExpect(jsonPath("$.people[3].reason").value("No email address"));
    }

    @Test
    void aBadProjectCodeNamesThePerson() throws Exception {
        String json = "{\"people\":[{\"email\":\"" + email("neha") + "\",\"projectCode\":\"DLK 204\"}],\"role\":\"USER\"}";
        expectError(send(true, asAdmin(), json), 400);
    }

    @Test
    void aNewPersonWithAnInitialPasswordSignsInWithTheirEmailAndMustChooseTheirOwn() throws Exception {
        String json = "{\"people\":[{\"email\":\"" + email("Pia").toUpperCase() + "\",\"displayName\":\"Pia Sen\","
                + "\"initialPassword\":\"given-by-admin-123\"},{\"email\":\"" + email("mo") + "\"}],\"role\":\"USER\"}";
        send(false, asAdmin(), json).andExpect(status().isOk()).andExpect(jsonPath("$.newPeople").value(2));

        User pia = userRepository.findByEmail(email("pia")).orElseThrow();
        assertThat(pia.getUsername()).isEqualTo(email("pia"));
        assertThat(pia.getPassword()).startsWith("$2");
        assertThat(pia.isPasswordChangeRequired()).isTrue();
        // Without a password: Microsoft sign-in, as before.
        User mo = userRepository.findByEmail(email("mo")).orElseThrow();
        assertThat(mo.getUsername()).isNull();
        assertThat(mo.getPassword()).isNull();
        assertThat(mo.isPasswordChangeRequired()).isFalse();
    }

    @Test
    void aShortInitialPasswordNamesThePersonAndAddsNoOne() throws Exception {
        String json = "{\"people\":[{\"email\":\"" + email("neha") + "\",\"initialPassword\":\"short\"}],\"role\":\"USER\"}";
        send(true, asAdmin(), json)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(email("neha") + ": the initial password must be 12 to 72 characters"));
        assertThat(userRepository.findByEmail(email("neha"))).isEmpty();
    }

    @Test
    void onlyAdministratorsAddPeople() throws Exception {
        expectError(send(true, asEnvAdmin(), body("USER", "")), 403);
        expectError(send(false, asUser(), body("USER", "")), 403);
        assertThat(userRepository.findByEmail(email("neha"))).isEmpty();
    }

    // ---- U13-T09: one email per person, after commit, while the switch is on ----

    private void emailSwitches(boolean onboarded) {
        settings.set("notification.email.enabled", "true", null); // the test SMTP refuses: the send is logged as failed
        settings.set("notification.email.onboarded.enabled", String.valueOf(onboarded), null);
    }

    /** This test's email log rows of one type (the recipients carry the test's tag). */
    private List<EmailLog> logged(String type) {
        return emailLog.findAll().stream()
                .filter(e -> type.equals(e.getNotificationType()) && e.getRecipients() != null && e.getRecipients().contains(tag))
                .toList();
    }

    /** Three new people, two environments, the granter asking to email people. */
    private String threePeople() {
        String person = "{\"email\":\"%s\",\"displayName\":\"%s\"}";
        return "{\"people\":[" + String.format(person, email("neha"), "Neha Gupta") + ","
                + String.format(person, email("ravi"), "Ravi Kumar") + "," + String.format(person, email("asha"), "Asha Rao") + "],"
                + "\"role\":\"USER\",\"emailPeople\":true,"
                + "\"access\":[{\"environmentId\":\"" + crm.getEnvironmentId() + "\",\"accessLevel\":\"USER\",\"durationDays\":30},"
                + "{\"environmentId\":\"" + payments.getEnvironmentId() + "\",\"accessLevel\":\"VIEWER\"}]}";
    }

    @Test
    void withTheSwitchOnEachPersonGetsOneEmailAndNoGrantEmails() throws Exception {
        emailSwitches(true);
        send(false, asAdmin(), threePeople()).andExpect(status().isOk()).andExpect(jsonPath("$.newPeople").value(3));

        awaitAsync(() -> assertThat(logged("ONBOARDED")).hasSize(3));
        assertThat(logged("ONBOARDED")).allSatisfy(e -> {
            assertThat(e.getSubject()).isEqualTo("You can now use VM Control");
            assertThat(e.getRecipientCount()).isEqualTo(1);
            assertThat(e.getSentByUserId()).isEqualTo(admin.getUserId());
        });
        assertThat(logged("ONBOARDED")).extracting(EmailLog::getRecipients)
                .containsExactlyInAnyOrder(email("neha"), email("ravi"), email("asha"));
        assertThat(logged("BULK_ACCESS_GRANTED")).isEmpty();
    }

    @Test
    void aRolledBackBatchSendsNoEmail() throws Exception {
        emailSwitches(true);
        VmGroup elsewhere = newGroup(payments, "web");
        String bad = ",{\"environmentId\":\"" + crm.getEnvironmentId() + "\",\"scopeType\":\"GROUP\",\"groupIds\":[\""
                + elsewhere.getGroupId() + "\"],\"accessLevel\":\"VIEWER\"}";
        expectError(send(false, asAdmin(), body("USER", bad)), 400);

        Awaitility.await().during(Duration.ofMillis(800)).atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(logged("ONBOARDED")).isEmpty());
        assertThat(logged("BULK_ACCESS_GRANTED")).isEmpty();
    }

    @Test
    void withTheSwitchOffTheGrantEmailsGoAsBefore() throws Exception {
        emailSwitches(false);
        send(false, asAdmin(), threePeople()).andExpect(status().isOk());

        // As before: one grant email per person per level and length (two each here).
        awaitAsync(() -> assertThat(logged("BULK_ACCESS_GRANTED")).hasSize(6));
        assertThat(logged("ONBOARDED")).isEmpty();
    }
}
