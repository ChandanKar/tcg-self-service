package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.AccessLevel;
import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.model.VmGroup;
import com.tcgdigital.vmcontrol.model.VmStatus;
import com.tcgdigital.vmcontrol.service.LockService;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Environment and group lists carry what each row needs in a fixed number of queries
 * (E10-T05, M36): the lock holder, clamped paging, display-name search, grouped VM counts, and on
 * request the VM state counts per group (U05-T03).
 */
class EnvironmentListControllerTest extends SecuredWebTestBase {

    @Autowired private LockService lockService;
    @Autowired private com.tcgdigital.vmcontrol.repository.AutomationRuleRepository automationRules;

    private Environment env;
    private String tag;

    @BeforeEach
    void seed() {
        tag = UUID.randomUUID().toString().substring(0, 6);
        env = newEnvironment("pay-qa");
        env.setDisplayName("Payments QA " + tag);
        env = environmentRepository.saveAndFlush(env);
        grantEnv(operator, env.getEnvironmentId(), AccessLevel.USER);
    }

    private String row(String field) {
        return "$.content[?(@.environmentId == '" + env.getEnvironmentId() + "')]." + field;
    }

    @Test
    void aLockedEnvironmentCarriesItsHolderInTheList() throws Exception {
        lockService.acquireLock(env.getEnvironmentId(), operator.getUserId(), "release testing", null);

        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row("locked")).value(hasItem(true)))
                .andExpect(jsonPath(row("lockedByUserId")).value(hasItem(operator.getUserId())))
                .andExpect(jsonPath(row("lockedByDisplayName")).value(hasItem(operator.getDisplayName())));
        mockMvc.perform(get("/api/v1/environments").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.environmentId == '" + env.getEnvironmentId() + "')].locked").value(hasItem(true)));
    }

    @Test
    void anUnlockedEnvironmentIsNotLocked() throws Exception {
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asAdmin()))
                .andExpect(jsonPath(row("locked")).value(hasItem(false)));
    }

    @Test
    void pagingIsValidatedAndClamped() throws Exception {
        expectError(mockMvc.perform(get("/api/v1/environments/page").param("page", "-1").with(asAdmin())), 400);
        mockMvc.perform(get("/api/v1/environments/page").param("size", "5000").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.size").value(100));
    }

    @Test
    void searchMatchesTheDisplayNameForAdminsAndUsers() throws Exception {
        String search = "payments qa " + tag; // not part of the slug name
        mockMvc.perform(get("/api/v1/environments/page").param("search", search).with(asAdmin()))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].displayName").value("Payments QA " + tag));
        mockMvc.perform(get("/api/v1/environments/page").param("search", search).with(asUser()))
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void groupListsCountVmsPerGroup() throws Exception {
        VmGroup web = newGroup(env, "web");
        VmGroup db = newGroup(env, "db");
        newVm(web, "web-1", VmStatus.RUNNING);
        newVm(web, "web-2", VmStatus.STOPPED);

        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/groups").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.groupId == '" + web.getGroupId() + "')].vmCount").value(hasItem(2)))
                .andExpect(jsonPath("$[?(@.groupId == '" + web.getGroupId() + "')].runningVmCount").value(hasItem(1)))
                .andExpect(jsonPath("$[?(@.groupId == '" + db.getGroupId() + "')].vmCount").value(hasItem(0)));
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/groups/start-order").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.groupId == '" + web.getGroupId() + "')].vmCount").value(hasItem(2)));
    }

    @Test
    void theRegionFilterKeepsEnvironmentsWithAVmThereForAdminsAndUsers() throws Exception {
        // U19-T08: env has a VM in me-central-1; another environment only in ap-south-1.
        VmGroup app = newGroup(env, "app");
        com.tcgdigital.vmcontrol.model.Vm far = newVm(app, "far-1", VmStatus.RUNNING);
        far.setRegion("me-central-1");
        vmRepository.saveAndFlush(far);
        Environment other = newEnvironment("pay-uat");
        other.setDisplayName("Payments UAT " + tag);
        other = environmentRepository.saveAndFlush(other);
        newVm(newGroup(other, "app"), "near-1", VmStatus.RUNNING);
        grantEnv(operator, other.getEnvironmentId(), AccessLevel.USER);

        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("region", "me-central-1").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].environmentId").value(env.getEnvironmentId()));
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("region", "me-central-1").with(asUser()))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asAdmin()))
                .andExpect(jsonPath("$.content", hasSize(2)));
        expectError(mockMvc.perform(get("/api/v1/environments/page").param("region", "mumbai").with(asAdmin())), 400);
        mockMvc.perform(get("/api/v1/environments/regions").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(hasItem("me-central-1")))
                .andExpect(jsonPath("$").value(hasItem("ap-south-1")));
    }

    // ---------------------------------------------------------------- U05-T03: groupStates

    private void vms(VmGroup group, VmStatus status, int count) {
        for (int i = 0; i < count; i++) newVm(group, group.getDisplayName() + "-" + status + "-" + i, status);
    }

    @Test
    void includeGroupsListsEachGroupsStatesInStartOrder() throws Exception {
        VmGroup database = newGroup(env, "Database");
        VmGroup application = newGroup(env, "Application");
        VmGroup web = newGroup(env, "Web");
        vms(web, VmStatus.STOPPED, 2);
        vms(database, VmStatus.RUNNING, 3);
        vms(application, VmStatus.RUNNING, 2);

        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("includeGroups", "true").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].groupStates[*].name").value(contains("Database", "Application", "Web")))
                .andExpect(jsonPath("$.content[0].groupStates[*].running").value(contains(3, 2, 0)))
                .andExpect(jsonPath("$.content[0].groupStates[*].stopped").value(contains(0, 0, 2)))
                .andExpect(jsonPath("$.content[0].groupStates[*].total").value(contains(3, 2, 2)));
        mockMvc.perform(get("/api/v1/environments").param("includeGroups", "true").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.environmentId == '" + env.getEnvironmentId() + "')].groupStates[*].name")
                        .value(contains("Database", "Application", "Web")));
    }

    @Test
    void statesFallIntoTheStripsBuckets() throws Exception {
        VmGroup app = newGroup(env, "App");
        vms(app, VmStatus.RUNNING, 1);
        vms(app, VmStatus.STARTING, 1);
        vms(app, VmStatus.STOPPING, 1);
        vms(app, VmStatus.ERROR, 1);
        vms(app, VmStatus.NOT_FOUND, 1);
        vms(app, VmStatus.UNKNOWN, 2);
        vms(app, VmStatus.STOPPED, 1);

        String group = "$.content[0].groupStates[0].";
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("includeGroups", "true").with(asAdmin()))
                .andExpect(jsonPath(group + "total").value(8))
                .andExpect(jsonPath(group + "running").value(1))
                .andExpect(jsonPath(group + "busy").value(2))
                .andExpect(jsonPath(group + "problem").value(2))
                .andExpect(jsonPath(group + "unknown").value(2))
                .andExpect(jsonPath(group + "stopped").value(1));
    }

    @Test
    void withoutTheFlagThereAreNoGroupStates() throws Exception {
        vms(newGroup(env, "App"), VmStatus.RUNNING, 1);
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].groupStates").doesNotExist());
    }

    @Test
    void aGroupScopedViewerSeesOnlyTheirGroupAndItsCounts() throws Exception {
        VmGroup mine = newGroup(env, "Mine");
        VmGroup other = newGroup(env, "Other");
        vms(mine, VmStatus.RUNNING, 1);
        vms(mine, VmStatus.STOPPED, 1);
        vms(other, VmStatus.RUNNING, 4);
        grantGroup(viewer, mine.getGroupId(), AccessLevel.VIEWER);

        String row = "$[?(@.environmentId == '" + env.getEnvironmentId() + "')].";
        mockMvc.perform(get("/api/v1/environments").param("includeGroups", "true").with(asViewer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row + "groupStates[*].name").value(contains("Mine")))
                .andExpect(jsonPath(row + "vmCount").value(contains(2)))
                .andExpect(jsonPath(row + "runningVmCount").value(contains(1)))
                .andExpect(jsonPath(row + "groupCount").value(contains(1)));
        // Someone with environment-wide access still sees every group.
        mockMvc.perform(get("/api/v1/environments").param("includeGroups", "true").with(asUser()))
                .andExpect(jsonPath(row + "groupStates[*].name").value(contains("Mine", "Other")))
                .andExpect(jsonPath(row + "vmCount").value(contains(6)));
    }

    @Test
    void listRowsSayWhetherTheViewerMayOperate() throws Exception {
        Environment readOnly = newEnvironment("ro-" + tag);
        grantEnv(operator, readOnly.getEnvironmentId(), AccessLevel.VIEWER);

        mockMvc.perform(get("/api/v1/environments").param("includeGroups", "true").with(asUser()))
                .andExpect(jsonPath("$[?(@.environmentId == '" + env.getEnvironmentId() + "')].viewerCanOperate").value(contains(true)))
                .andExpect(jsonPath("$[?(@.environmentId == '" + readOnly.getEnvironmentId() + "')].viewerCanOperate").value(contains(false)))
                .andExpect(jsonPath("$[?(@.environmentId == '" + readOnly.getEnvironmentId() + "')].groupStates").value(contains(empty())));
    }

    @Test
    void theEnvironmentPagesGroupListingCountsEveryState() throws Exception {
        VmGroup app = newGroup(env, "App");
        vms(app, VmStatus.RUNNING, 2);
        vms(app, VmStatus.ERROR, 1);
        vms(app, VmStatus.STOPPED, 3);
        vms(app, VmStatus.STARTING, 1);

        String group = "$[?(@.group.groupId == '" + app.getGroupId() + "')].group.";
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId() + "/vms").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(group + "vmCount").value(contains(7)))
                .andExpect(jsonPath(group + "runningVmCount").value(contains(2)))
                .andExpect(jsonPath(group + "problemVmCount").value(contains(1)))
                .andExpect(jsonPath(group + "stoppedVmCount").value(contains(3)))
                .andExpect(jsonPath(group + "busyVmCount").value(contains(1)))
                .andExpect(jsonPath(group + "unknownVmCount").value(contains(0)));
    }

    // ---------------------------------------------------------------- U06-T04: filter, access, cost, totals

    /** env runs 2 of 5 VMs; a second environment "bill-<tag>" has 3 stopped VMs. */
    private Environment runningAndStopped() {
        VmGroup app = newGroup(env, "App");
        vms(app, VmStatus.RUNNING, 2);
        vms(app, VmStatus.STOPPED, 3);
        Environment stopped = newEnvironment("bill-" + tag);
        vms(newGroup(stopped, "App"), VmStatus.STOPPED, 3);
        grantEnv(operator, stopped.getEnvironmentId(), AccessLevel.USER);
        return stopped;
    }

    @Test
    void stateRunningKeepsEnvironmentsWithARunningVmAndTheTotalsFollow() throws Exception {
        Environment stopped = runningAndStopped();

        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("state", "RUNNING").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].environmentId").value(contains(env.getEnvironmentId())))
                .andExpect(jsonPath("$.page.totalElements").value(1))
                .andExpect(header().string("X-Total-Vms", "5"))
                .andExpect(header().string("X-Running-Vms", "2"));
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("state", "stopped").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].environmentId").value(contains(stopped.getEnvironmentId())))
                .andExpect(header().string("X-Total-Vms", "3"))
                .andExpect(header().string("X-Running-Vms", "0"));
    }

    @Test
    void theTotalsCountTheWholeFilteredSetNotThePage() throws Exception {
        runningAndStopped();

        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("size", "1").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(header().string("X-Total-Vms", "8"))
                .andExpect(header().string("X-Running-Vms", "2"));
    }

    @Test
    void anUnknownStateIsA400NamingTheAllowedValues() throws Exception {
        mockMvc.perform(get("/api/v1/environments/page").param("state", "FOO").with(asUser()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("state must be one of RUNNING, STOPPED"));
    }

    @Test
    void eachRowCarriesTheViewersAccessAndThisMonthsCost() throws Exception {
        grantEnv(viewer, env.getEnvironmentId(), AccessLevel.VIEWER);

        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asUser()))
                .andExpect(jsonPath(row("myAccess")).value(contains("USER")))
                .andExpect(jsonPath(row("monthToDateCost")).value(contains(0.0)));
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asViewer()))
                .andExpect(jsonPath(row("myAccess")).value(contains("VIEWER")));
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asAdmin()))
                .andExpect(jsonPath(row("myAccess")).value(contains("ADMIN")));
    }

    @Test
    void aGroupScopedViewerFiltersAndCountsOnlyTheGroupsTheySee() throws Exception {
        VmGroup mine = newGroup(env, "Mine");
        VmGroup other = newGroup(env, "Other");
        vms(mine, VmStatus.STOPPED, 2);
        vms(other, VmStatus.RUNNING, 3);
        grantGroup(viewer, mine.getGroupId(), AccessLevel.VIEWER);

        // Only a hidden group runs: to this viewer the environment is stopped.
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("state", "RUNNING").with(asViewer()))
                .andExpect(jsonPath("$.content", empty()));
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("state", "STOPPED").with(asViewer()))
                .andExpect(jsonPath("$.content[*].environmentId").value(contains(env.getEnvironmentId())))
                .andExpect(jsonPath(row("myAccess")).value(empty()))     // group grant only: left out
                .andExpect(jsonPath(row("monthToDateCost")).value(empty()))
                .andExpect(header().string("X-Total-Vms", "2"))
                .andExpect(header().string("X-Running-Vms", "0"));
        // The operator sees the whole environment.
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("state", "RUNNING").with(asUser()))
                .andExpect(jsonPath("$.content[*].environmentId").value(contains(env.getEnvironmentId())))
                .andExpect(header().string("X-Running-Vms", "3"));
    }

    // ---------------------------------------------------------------- U22-T13: order and export

    @Test
    void mostRunningOrdersTheWholeFilteredSetThenPages() throws Exception {
        Environment stopped = runningAndStopped(); // "bill-<tag>" comes first by name

        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asUser()))
                .andExpect(jsonPath("$.content[*].environmentId").value(contains(stopped.getEnvironmentId(), env.getEnvironmentId())));
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("sort", "running")
                        .param("size", "1").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].environmentId").value(contains(env.getEnvironmentId())))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                .andExpect(header().string("X-Total-Vms", "8"))
                .andExpect(header().string("X-Running-Vms", "2"));
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("sort", "running")
                        .param("size", "1").param("page", "1").with(asUser()))
                .andExpect(jsonPath("$.content[*].environmentId").value(contains(stopped.getEnvironmentId())));
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).param("sort", "cost").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    void theExportListsWhatTheCallerSeesWithTheFilters() throws Exception {
        runningAndStopped();
        Environment risky = newEnvironment("risky-" + tag);
        risky.setDisplayName("=HYPERLINK(\"x\") " + tag);
        environmentRepository.saveAndFlush(risky);

        String csv = mockMvc.perform(get("/api/v1/environments/export").param("search", tag).param("state", "RUNNING").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"environments-")))
                .andReturn().getResponse().getContentAsString();
        String[] lines = csv.split("\r\n");
        org.assertj.core.api.Assertions.assertThat(lines[0]).startsWith("Environment,Short name,Region,Team,VMs running,VMs,Locked");
        org.assertj.core.api.Assertions.assertThat(lines).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(lines[1]).startsWith("Payments QA " + tag + ",pay-qa").contains(",2,5,no,USER,");

        // The admin sees the third one; a formula-looking name is written as text.
        String all = mockMvc.perform(get("/api/v1/environments/export").param("search", tag).with(asAdmin()))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(all.split("\r\n")).hasSize(4);
        org.assertj.core.api.Assertions.assertThat(all).contains("\"'=HYPERLINK(\"\"x\"\") " + tag + "\"");
    }

    // ---------------------------------------------------------------- U23-T04: the schedule follows its scope

    @Test
    void aGroupScheduleIsNotTheEnvironmentsStopInTheListOrTheDetails() throws Exception {
        newVm(newGroup(env, "Group-1"), "master-1", VmStatus.RUNNING);
        VmGroup second = newGroup(env, "Group-2");
        newVm(second, "g2-1", VmStatus.STOPPED);
        com.tcgdigital.vmcontrol.model.AutomationRule rule = new com.tcgdigital.vmcontrol.model.AutomationRule();
        rule.setRuleId(UUID.randomUUID().toString());
        rule.setName("ise start-stop " + tag);
        rule.setEnvironment(env);
        rule.setScopeType(com.tcgdigital.vmcontrol.model.AutomationScopeType.GROUP);
        rule.setScopeId(second.getGroupId());
        rule.setTriggerType(com.tcgdigital.vmcontrol.model.AutomationTriggerType.SCHEDULE);
        rule.addWindow("MON,TUE,WED,THU,FRI,SAT,SUN", "11:00", "17:00");
        rule.setTimezone("Asia/Kolkata");
        rule.setEnabled(true);
        rule.setCreatedByUserId(operator.getUserId());
        automationRules.saveAndFlush(rule);

        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(row("scheduleRuleCount")).value(contains(1)))
                .andExpect(jsonPath(row("nextScheduledStop")).value(contains((Object) null)))
                .andExpect(jsonPath(row("partialScheduledStop.scope")).value(contains("Group-2")))
                .andExpect(jsonPath(row("partialScheduledStop.groupIds[0]")).value(contains(second.getGroupId())));
        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId()).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleRuleCount").value(1))
                .andExpect(jsonPath("$.nextScheduledStop").doesNotExist())
                .andExpect(jsonPath("$.partialScheduledStop.scope").value("Group-2"))
                .andExpect(jsonPath("$.partialScheduledStop.at").exists());
    }

    @Test
    void anEnvironmentScheduleIsTheEnvironmentsStopInTheDetailsToo() throws Exception {
        newVm(newGroup(env, "App"), "app-1", VmStatus.RUNNING);
        com.tcgdigital.vmcontrol.model.AutomationRule rule = new com.tcgdigital.vmcontrol.model.AutomationRule();
        rule.setRuleId(UUID.randomUUID().toString());
        rule.setName("nightly " + tag);
        rule.setEnvironment(env);
        rule.setScopeType(com.tcgdigital.vmcontrol.model.AutomationScopeType.ENVIRONMENT);
        rule.setTriggerType(com.tcgdigital.vmcontrol.model.AutomationTriggerType.SCHEDULE);
        rule.addWindow("MON,TUE,WED,THU,FRI,SAT,SUN", null, "20:00");
        rule.setTimezone("Asia/Kolkata");
        rule.setEnabled(true);
        rule.setCreatedByUserId(operator.getUserId());
        automationRules.saveAndFlush(rule);

        mockMvc.perform(get("/api/v1/environments/" + env.getEnvironmentId()).with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleRuleCount").value(1))
                .andExpect(jsonPath("$.nextScheduledStop").exists())
                .andExpect(jsonPath("$.partialScheduledStop").doesNotExist());
        mockMvc.perform(get("/api/v1/environments/page").param("search", tag).with(asUser()))
                .andExpect(jsonPath(row("nextScheduledStop")).value(hasSize(1)))
                .andExpect(jsonPath(row("partialScheduledStop")).value(contains((Object) null)));
    }
}
