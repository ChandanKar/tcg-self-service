package com.tcgdigital.vmcontrol.controller;

import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@Import(GlobalExceptionHandlerTest.ThrowingController.class)
class GlobalExceptionHandlerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void wrongParameterTypeIs400() throws Exception {
        expectShape(mockMvc.perform(get("/api/v1/environments/page").param("page", "abc")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'page'"));
    }

    @Test
    void unknownPathIs404() throws Exception {
        expectShape(mockMvc.perform(get("/api/v1/does-not-exist")))
                .andExpect(status().isNotFound());
    }

    @Test
    void wrongMethodIs405() throws Exception {
        expectShape(mockMvc.perform(delete("/api/v1/dashboard/summary")))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void duplicateKeyIs409() throws Exception {
        expectShape(mockMvc.perform(get("/test-only/conflict")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Conflict"));
    }

    private static ResultActions expectShape(ResultActions result) throws Exception {
        return result.andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/test-only/conflict")
        String conflict() {
            throw new DataIntegrityViolationException("Duplicate entry 'x' for key 'idx_test'");
        }
    }
}
