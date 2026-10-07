package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.AuditLogRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthenticationServiceTest extends AbstractIntegrationTest {

    private static final String GOOD_PASSWORD = "correct-horse-battery";

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    void plainTextStoredPasswordNeverAuthenticates() {
        User user = withPassword("plain.user", GOOD_PASSWORD);

        assertThatThrownBy(() -> authenticationService.authenticateUser("plain.user", GOOD_PASSWORD))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(userRepository.findById(user.getUserId()).orElseThrow().getPassword())
                .as("the stored value is not silently re-hashed").isEqualTo(GOOD_PASSWORD);
    }

    @Test
    void bcryptPasswordAuthenticates() {
        User user = withPassword("hashed.user", passwordEncoder.encode(GOOD_PASSWORD));

        assertThat(authenticationService.authenticateUser("hashed.user", GOOD_PASSWORD).getUserId())
                .isEqualTo(user.getUserId());
    }

    @Test
    void wrongPasswordIsRejected() {
        withPassword("hashed.user", passwordEncoder.encode(GOOD_PASSWORD));

        assertThatThrownBy(() -> authenticationService.authenticateUser("hashed.user", "wrong-password-123"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void setUserPasswordStoresBcryptHashAndAudits() {
        User target = newUser("target@example.com", false, false);
        User actor = newUser("actor@example.com", true, false);

        authenticationService.setUserPassword(target.getUserId(), GOOD_PASSWORD, actor.getUserId());

        String stored = userRepository.findById(target.getUserId()).orElseThrow().getPassword();
        assertThat(stored).startsWith("$2");
        assertThat(passwordEncoder.matches(GOOD_PASSWORD, stored)).isTrue();
        awaitAsync(() -> assertThat(auditLogRepository.findTop100ByOrderByCreatedAtDesc())
                .anyMatch(l -> l.getAction() == AuditAction.USER_UPDATED
                        && target.getUserId().equals(l.getTargetId())
                        && l.getDetails().startsWith("Password set")
                        && !l.getDetails().contains(GOOD_PASSWORD)));
    }

    @Test
    void setUserPasswordRejectsShortPassword() {
        User target = newUser("target@example.com", false, false);

        assertThatThrownBy(() -> authenticationService.setUserPassword(target.getUserId(), "short", null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void setUserPasswordRejectsUnknownUser() {
        assertThatThrownBy(() -> authenticationService.setUserPassword("no-such-user", GOOD_PASSWORD, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void disabledPasswordLoginRejectsEvenCorrectBcryptPassword() {
        UserRepository repository = mock(UserRepository.class);
        AuthenticationService disabled = new AuthenticationService(
                repository, new BCryptPasswordEncoder(), mock(AuditService.class), false);

        assertThat(disabled.isPasswordLoginEnabled()).isFalse();
        assertThatThrownBy(() -> disabled.authenticateUser("hashed.user", GOOD_PASSWORD))
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(repository);
    }

    private User withPassword(String username, String storedPassword) {
        User user = newUser(username + "@example.com", false, false);
        user.setUsername(username);
        user.setPassword(storedPassword);
        return userRepository.save(user);
    }
}
