package com.tcgdigital.vmcontrol.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * U25-T03: GET /users/email-domains lists the email domains of active people with counts, most
 * used first, lower-case, so Add people can say a typed domain looks mistyped. Administrators only.
 */
class UserEmailDomainsIntegrationTest extends SecuredWebTestBase {

    private static final String URL = "/api/v1/users/email-domains";

    @Test
    void domainsOfActivePeopleWithCountsMostUsedFirst() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String big = "big-" + tag + ".test";
        String small = "small-" + tag + ".test";
        String gone = "gone-" + tag + ".test";
        newUser("a@" + big, false, false);
        newUser("B@" + big.toUpperCase(), false, false);
        newUser("c@" + big, false, false);
        newUser("d@" + small, false, false);
        User inactive = newUser("e@" + gone, false, false);
        inactive.setIsActive(false);
        userRepository.saveAndFlush(inactive);
        User service = newUser("svc@" + gone, false, false);
        service.setServiceAccount(true);
        userRepository.saveAndFlush(service);

        String json = mockMvc.perform(get(URL).with(asAdmin())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode list = new ObjectMapper().readTree(json);
        List<String> domains = new ArrayList<>();
        long previous = Long.MAX_VALUE;
        for (JsonNode row : list) {
            String domain = row.get("domain").asText();
            long count = row.get("count").asLong();
            assertThat(domain).isEqualTo(domain.toLowerCase());
            assertThat(count).as("most used first").isLessThanOrEqualTo(previous);
            previous = count;
            domains.add(domain);
            if (domain.equals(big)) assertThat(count).isEqualTo(3);
            if (domain.equals(small)) assertThat(count).isEqualTo(1);
        }
        assertThat(domains).contains(big, small).doesNotContain(gone).doesNotHaveDuplicates();
        assertThat(domains.indexOf(big)).isLessThan(domains.indexOf(small));
    }

    @Test
    void onlyAdministratorsSeeTheDomains() throws Exception {
        expectError(mockMvc.perform(get(URL).with(asEnvAdmin())), 403);
        expectError(mockMvc.perform(get(URL).with(asUser())), 403);
        expectError(mockMvc.perform(get(URL).with(asViewer())), 403);
    }
}
