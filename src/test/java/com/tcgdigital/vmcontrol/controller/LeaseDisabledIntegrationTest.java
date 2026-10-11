package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.model.Environment;
import com.tcgdigital.vmcontrol.support.SecuredWebTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With lease.enabled=false (the default) every lease endpoint is 404 and the DTO has no flag (E15-T01). */
class LeaseDisabledIntegrationTest extends SecuredWebTestBase {

    @Test
    void everyLeaseEndpointIsNotFound() throws Exception {
        Environment env = newEnvironment("No leases");
        String base = "/api/v1/environments/" + env.getEnvironmentId();
        mockMvc.perform(get(base + "/lease").with(asAdmin())).andExpect(status().isNotFound());
        mockMvc.perform(get(base + "/lease-policy").with(asAdmin())).andExpect(status().isNotFound());
        mockMvc.perform(put(base + "/lease-policy").with(asAdmin()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isNotFound());
        mockMvc.perform(get(base).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leaseEnabled").doesNotExist());
        mockMvc.perform(get("/api/v1/users/me/leases").with(asUser())).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/users/me/profile").with(asUser()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leasesEnabled").value(false));
    }
}
