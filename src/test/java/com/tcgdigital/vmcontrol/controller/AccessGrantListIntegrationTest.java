package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.AccessStatus;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.EnvironmentAccess;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.service.SecurityService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U10-T01: one paged list of grants across the environments the caller manages, with ACTIVE /
 * ENDING / ENDED buckets, search and header counts; an assigned environment admin sees only
 * their environments; users and viewers get 403.
 */
class AccessGrantListIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/access-grants";

    @Autowired private SecurityService securityService;

    private Object originalScope;
    private Environment analytics;
    private Environment crm;

    @BeforeEach
    void setUp() {
        originalScope = ReflectionTestUtils.getField(securityService, "envAdminScope");
        analytics = newEnvironment("Analytics QA");
        crm = newEnvironment("CRM UAT");
    }

    @AfterEach
    void restoreScope() {
        ReflectionTestUtils.setField(securityService, "envAdminScope", originalScope);
    }

    private EnvironmentAccess grantUntil(User user, Environment env, AccessLevel level, Instant expires) {
        EnvironmentAccess grant = grantEnv(user, env.getEnvironmentId(), level);
        grant.setExpiresAt(expires == null ? null : Timestamp.from(expires));
        return environmentAccessRepository.saveAndFlush(grant);
    }

    private EnvironmentAccess revoked(User user, Environment env, Instant at) {
        EnvironmentAccess grant = grantEnv(user, env.getEnvironmentId(), AccessLevel.USER);
        grant.setStatus(AccessStatus.REVOKED);
        grant.setRevokedAt(Timestamp.from(at));
        return environmentAccessRepository.saveAndFlush(grant);
    }

    @Test
    void bucketsSplitActiveEndingAndEnded() throws Exception {
        Instant now = Instant.now();
        User ana = newUser("ana@grants.test", false, false);
        User ben = newUser("ben@grants.test", false, false);
        User cai = newUser("cai@grants.test", false, false);
        User dev = newUser("dev@grants.test", false, false);
        grantUntil(ana, analytics, AccessLevel.USER, null);
        grantUntil(ben, analytics, AccessLevel.VIEWER, now.plus(Duration.ofDays(3)));
        grantUntil(cai, analytics, AccessLevel.USER, now.minus(Duration.ofDays(2)));
        revoked(dev, analytics, now.minus(Duration.ofDays(1)));
        revoked(dev, crm, now.minus(Duration.ofDays(200))); // older than the history window
        String env = analytics.getEnvironmentId();

        mockMvc.perform(get(URL).with(asAdmin()).param("environmentId", env))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].userEmail", containsInAnyOrder("ana@grants.test", "ben@grants.test")))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        mockMvc.perform(get(URL).with(asAdmin()).param("environmentId", env).param("status", "ENDING"))
                .andExpect(jsonPath("$.content[*].userEmail", containsInAnyOrder("ben@grants.test")));
        mockMvc.perform(get(URL).with(asAdmin()).param("environmentId", env).param("status", "ENDED"))
                .andExpect(jsonPath("$.content[*].userEmail", containsInAnyOrder("dev@grants.test", "cai@grants.test")))
                .andExpect(jsonPath("$.content[0].userEmail").value("dev@grants.test"))
                .andExpect(jsonPath("$.content[0].endedAt").exists());
        mockMvc.perform(get(URL).with(asAdmin()).param("environmentId", crm.getEnvironmentId()).param("status", "ENDED"))
                .andExpect(jsonPath("$.page.totalElements").value(0));
    }

    @Test
    void searchFindsPeopleEnvironmentsAndGroups() throws Exception {
        User ana = newUser("ana.search@grants.test", false, false);
        User ben = newUser("ben.search@grants.test", false, false);
        VmGroup application = newGroup(crm, "Application");
        grantEnv(ana, analytics.getEnvironmentId(), AccessLevel.USER);
        grantGroup(ben, application.getGroupId(), AccessLevel.USER);

        mockMvc.perform(get(URL).with(asAdmin()).param("search", "ANA.SEARCH"))
                .andExpect(jsonPath("$.content[*].userEmail", containsInAnyOrder("ana.search@grants.test")));
        mockMvc.perform(get(URL).with(asAdmin()).param("search", "applic"))
                .andExpect(jsonPath("$.content[*].userEmail", containsInAnyOrder("ben.search@grants.test")))
                .andExpect(jsonPath("$.content[0].scopeName").value("Application"));
        mockMvc.perform(get(URL).with(asAdmin()).param("search", "analytics q").param("environmentId", analytics.getEnvironmentId()))
                .andExpect(jsonPath("$.content[*].userEmail", containsInAnyOrder("ana.search@grants.test")));
    }

    @Test
    void pagesAreCappedAndCountTheWholeList() throws Exception {
        for (int i = 0; i < 5; i++) {
            grantEnv(newUser("page" + i + "@grants.test", false, false), analytics.getEnvironmentId(), AccessLevel.VIEWER);
        }
        mockMvc.perform(get(URL).with(asAdmin()).param("environmentId", analytics.getEnvironmentId()).param("size", "2").param("page", "1"))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(5));
        mockMvc.perform(get(URL).with(asAdmin()).param("size", "1000"))
                .andExpect(jsonPath("$.page.size").value(100));
    }

    @Test
    void anAssignedEnvironmentAdminSeesOnlyTheirEnvironments() throws Exception {
        ReflectionTestUtils.setField(securityService, "envAdminScope", "assigned");
        grantEnv(envAdmin, analytics.getEnvironmentId(), AccessLevel.ADMIN);
        grantEnv(operator, analytics.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, crm.getEnvironmentId(), AccessLevel.VIEWER);

        mockMvc.perform(get(URL).with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].environmentId", everyItem(is(analytics.getEnvironmentId()))))
                .andExpect(jsonPath("$.page.totalElements").value(2));
        expectError(mockMvc.perform(get(URL).with(asEnvAdmin()).param("environmentId", crm.getEnvironmentId())), 404);
        expectError(mockMvc.perform(get(URL + "/summary").with(asEnvAdmin()).param("environmentId", crm.getEnvironmentId())), 404);
        mockMvc.perform(get(URL + "/summary").with(asEnvAdmin()))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.admins").value(1))
                .andExpect(jsonPath("$.operators").value(1));
    }

    @Test
    void anEnvironmentAdminWithNoEnvironmentsGetsAnEmptyList() throws Exception {
        ReflectionTestUtils.setField(securityService, "envAdminScope", "assigned");
        grantEnv(operator, analytics.getEnvironmentId(), AccessLevel.USER);
        mockMvc.perform(get(URL).with(asEnvAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));
        mockMvc.perform(get(URL + "/summary").with(asEnvAdmin())).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void theSummaryCountsGrantsInForcePerLevel() throws Exception {
        Instant now = Instant.now();
        grantUntil(newUser("s1@grants.test", false, false), analytics, AccessLevel.ADMIN, null);
        grantUntil(newUser("s2@grants.test", false, false), analytics, AccessLevel.USER, null);
        grantUntil(newUser("s3@grants.test", false, false), analytics, AccessLevel.USER, now.plus(Duration.ofDays(2)));
        grantUntil(newUser("s4@grants.test", false, false), analytics, AccessLevel.VIEWER, now.plus(Duration.ofDays(30)));
        grantUntil(newUser("s5@grants.test", false, false), analytics, AccessLevel.VIEWER, now.minus(Duration.ofDays(1)));

        mockMvc.perform(get(URL + "/summary").with(asAdmin()).param("environmentId", analytics.getEnvironmentId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.admins").value(1))
                .andExpect(jsonPath("$.operators").value(2))
                .andExpect(jsonPath("$.viewers").value(1))
                .andExpect(jsonPath("$.endingWithin7Days").value(1));
    }

    @Test
    void usersAndViewersMayNotList() throws Exception {
        grantEnv(operator, analytics.getEnvironmentId(), AccessLevel.USER);
        grantEnv(viewer, analytics.getEnvironmentId(), AccessLevel.VIEWER);
        mockMvc.perform(get(URL).with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL).with(asViewer())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/summary").with(asUser())).andExpect(status().isForbidden());
        mockMvc.perform(get(URL + "/summary").with(asViewer())).andExpect(status().isForbidden());
    }

    @Test
    void anUnknownStatusIsABadRequest() throws Exception {
        mockMvc.perform(get(URL).with(asAdmin()).param("status", "SOON")).andExpect(status().isBadRequest());
    }
}
