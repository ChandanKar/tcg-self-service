package com.tcgdigital.vmcontrol.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tcgdigital.vmcontrol.dto.VmDTO;
import com.tcgdigital.vmcontrol.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** U01-T01: the rule behind {@code @AdminOnly} fails closed and is asked once per request. */
class ProviderIdVisibilityTest {

    private final UserService userService = mock(UserService.class);
    private final ProviderIdVisibility visibility = new ProviderIdVisibility(userService);
    /** The application's own rule, put back after each test so later tests in the JVM are unaffected. */
    private ProviderIdVisibility applicationRule;
    private boolean installed;

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        if (installed) {
            ProviderIdVisibility.install(applicationRule);
        }
    }

    private void useThisRuleForJson() {
        applicationRule = ProviderIdVisibility.install(visibility);
        installed = true;
    }

    private static void inRequest() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
    }

    @Test
    void withoutARequestNobodySeesProviderIds() {
        when(userService.isCurrentUserAdmin()).thenReturn(true);

        assertThat(visibility.viewerMaySeeProviderIds()).isFalse();
    }

    @Test
    void anAdministratorsRequestSeesThemAndTheAnswerIsReused() {
        inRequest();
        when(userService.isCurrentUserAdmin()).thenReturn(true);

        assertThat(visibility.viewerMaySeeProviderIds()).isTrue();
        assertThat(visibility.viewerMaySeeProviderIds()).isTrue();
        verify(userService, times(1)).isCurrentUserAdmin();
    }

    @Test
    void aFailingUserLookupHidesThem() {
        inRequest();
        when(userService.isCurrentUserAdmin()).thenThrow(new IllegalStateException("no user"));

        assertThat(visibility.viewerMaySeeProviderIds()).isFalse();
    }

    @Test
    void theJsonOmitsTheIdForNonAdministratorsAndKeepsTheRest() throws Exception {
        useThisRuleForJson();
        inRequest();
        when(userService.isCurrentUserAdmin()).thenReturn(false);
        VmDTO dto = new VmDTO();
        dto.setProviderVmId("i-0123456789abcdef0");
        dto.setPrivateIp("10.0.1.15");

        String json = new ObjectMapper().writeValueAsString(dto);

        assertThat(json).doesNotContain("providerVmId").doesNotContain("i-0123").contains("10.0.1.15");
    }

    @Test
    void theJsonKeepsTheIdForAnAdministrator() throws Exception {
        useThisRuleForJson();
        inRequest();
        when(userService.isCurrentUserAdmin()).thenReturn(true);
        VmDTO dto = new VmDTO();
        dto.setProviderVmId("i-0123456789abcdef0");

        assertThat(new ObjectMapper().writeValueAsString(dto)).contains("\"providerVmId\":\"i-0123456789abcdef0\"");
    }
}
