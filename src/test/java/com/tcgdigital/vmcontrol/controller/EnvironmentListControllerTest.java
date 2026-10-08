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

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Environment and group lists carry what each row needs in a fixed number of queries
 * (E10-T05, M36): the lock holder, clamped paging, display-name search, grouped VM counts.
 */
class EnvironmentListControllerTest extends SecuredWebTestBase {

    @Autowired private LockService lockService;

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
}
