package com.tcgdigital.vmcontrol.security;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CSRF set-up used by EntraidSecurityConfig, outside the shared Spring test context
 * (spring-security-test's csrf() helper rewires the shared CsrfFilter, so cookie behaviour is
 * checked here): every response issues a script-readable XSRF-TOKEN cookie.
 */
class CsrfCookieFilterTest {

    @Test
    void firstRequestGetsAScriptReadableXsrfCookie() throws Exception {
        CsrfFilter csrfFilter = new CsrfFilter(CookieCsrfTokenRepository.withHttpOnlyFalse());
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        csrfFilter.doFilter(request, response, new MockFilterChain(new jakarta.servlet.http.HttpServlet() { },
                new CsrfCookieFilter()));

        Cookie cookie = response.getCookie("XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).isNotBlank();
        assertThat(cookie.isHttpOnly()).isFalse();
    }

    @Test
    void withoutCsrfCookieFilterTheLazyTokenWritesNoCookie() throws Exception {
        CsrfFilter csrfFilter = new CsrfFilter(CookieCsrfTokenRepository.withHttpOnlyFalse());
        csrfFilter.setRequestHandler(new CsrfTokenRequestAttributeHandler());
        MockHttpServletResponse response = new MockHttpServletResponse();

        csrfFilter.doFilter(new MockHttpServletRequest("GET", "/login.html"), response, new MockFilterChain());

        assertThat(response.getCookie("XSRF-TOKEN")).isNull();
    }
}
