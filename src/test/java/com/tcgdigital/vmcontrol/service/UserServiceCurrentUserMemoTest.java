package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The current user is looked up once per GET request (U26-T01): a page of 100 environments asked for
 * them about 460 times, one query each. Other requests, a new sign-in and work outside a request still
 * look them up every time.
 */
class UserServiceCurrentUserMemoTest {

    private final UserRepository users = mock(UserRepository.class);
    private final UserService service = new UserService(users, mock(AuditService.class), mock(LockService.class));

    @BeforeEach
    void signIn() {
        User user = new User();
        user.setUserId("u-1");
        user.setEmail("u1@example.com");
        user.setIsActive(true);
        when(users.findByEmail("u-1")).thenReturn(Optional.empty());
        when(users.findById("u-1")).thenReturn(Optional.of(user));
        signInAs("u-1");
    }

    @AfterEach
    void clear() {
        RequestContextHolder.resetRequestAttributes();
        SecurityContextHolder.clearContext();
    }

    private static void signInAs(String principal) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    private static void request(String method) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest(method, "/api/v1/x")));
    }

    @Test
    void aGetRequestLooksTheUserUpOnce() {
        request("GET");

        for (int i = 0; i < 5; i++) {
            assertThat(service.getCurrentUser().getUserId()).isEqualTo("u-1");
        }

        verify(users, times(1)).findById("u-1");
    }

    @Test
    void otherRequestsAndWorkOutsideARequestLookItUpEveryTime() {
        request("POST");
        service.getCurrentUser();
        service.getCurrentUser();
        RequestContextHolder.resetRequestAttributes();
        service.getCurrentUser();

        verify(users, times(3)).findById("u-1");
    }

    @Test
    void aNewSignInWithinTheRequestIsLookedUpAgain() {
        request("GET");
        User other = new User();
        other.setUserId("u-2");
        other.setIsActive(true);
        when(users.findById("u-2")).thenReturn(Optional.of(other));

        assertThat(service.getCurrentUser().getUserId()).isEqualTo("u-1");
        signInAs("u-2");
        assertThat(service.getCurrentUser().getUserId()).isEqualTo("u-2");
        SecurityContextHolder.clearContext();
        assertThat(service.getCurrentUser()).isNull();
    }

    @Test
    void aDeactivatedUserIsStillSignedOut() {
        request("GET");
        User inactive = new User();
        inactive.setUserId("u-3");
        inactive.setIsActive(false);
        when(users.findById("u-3")).thenReturn(Optional.of(inactive));
        signInAs("u-3");

        assertThat(service.getCurrentUser()).isNull();
        assertThat(service.getCurrentUser()).isNull();
        verify(users, times(1)).findById("u-3");
    }
}
