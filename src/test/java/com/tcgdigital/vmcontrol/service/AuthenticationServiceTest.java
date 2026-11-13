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
    void setUserPasswordForSomeoneWithoutAUsernameLetsThemSignInWithTheirEmail() {
        // U21-T21: on staging "Set password" for a person added by email saved a password that never worked.
        User target = newUser("Asha.Rao@Example.com", false, false);
        assertThat(target.getUsername()).isNull();

        authenticationService.setUserPassword(target.getUserId(), GOOD_PASSWORD, null);

        User saved = userRepository.findById(target.getUserId()).orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("asha.rao@example.com");
        assertThat(saved.isPasswordChangeRequired()).as("they choose their own at first sign-in").isTrue();
        assertThat(authenticationService.authenticateUser("asha.rao@example.com", GOOD_PASSWORD).getUserId())
                .isEqualTo(target.getUserId());
        assertThat(authenticationService.changeInitialPassword("Asha.Rao@Example.com", GOOD_PASSWORD, "their-own-password-1")
                .isPasswordChangeRequired()).isFalse();
    }

    @Test
    void setUserPasswordKeepsAnExistingUsername() {
        User target = withPassword("legacy.user", passwordEncoder.encode("old-password-123"));

        authenticationService.setUserPassword(target.getUserId(), GOOD_PASSWORD, null);

        User saved = userRepository.findById(target.getUserId()).orElseThrow();
        assertThat(saved.getUsername()).isEqualTo("legacy.user");
        // U25-T04: a password an administrator set is always replaced at the next sign-in.
        assertThat(saved.isPasswordChangeRequired()).isTrue();
        assertThat(authenticationService.authenticateUser("legacy.user", GOOD_PASSWORD).getUserId()).isEqualTo(target.getUserId());
    }

    @Test
    void resetPasswordGeneratesOneThatSignsInMustBeChangedAndIsAuditedWithoutIt() {
        User target = newUser("Reset.Me@Example.com", false, false);
        User actor = newUser("actor@example.com", true, false);

        AuthenticationService.PasswordReset reset = authenticationService.resetPassword(target.getUserId(), actor.getUserId());

        assertThat(reset.username()).isEqualTo("reset.me@example.com");
        assertThat(reset.password()).matches("[ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789\\-_.!]{16}");
        assertThat(reset.toString()).doesNotContain(reset.password());
        User saved = userRepository.findById(target.getUserId()).orElseThrow();
        assertThat(passwordEncoder.matches(reset.password(), saved.getPassword())).isTrue();
        assertThat(saved.isPasswordChangeRequired()).isTrue();
        assertThat(saved.getPasswordUpdatedAt()).isNotNull();
        assertThat(authenticationService.authenticateUser("reset.me@example.com", reset.password()).getUserId())
                .isEqualTo(target.getUserId());
        awaitAsync(() -> assertThat(auditLogRepository.findTop100ByOrderByCreatedAtDesc())
                .anyMatch(l -> l.getAction() == AuditAction.USER_UPDATED
                        && target.getUserId().equals(l.getTargetId())
                        && l.getDetails().startsWith("Password reset by user")
                        && !l.getDetails().contains(reset.password())));
    }

    @Test
    void resetPasswordRefusesServiceAccountsAndUnknownPeople() {
        User service = newUser("svc@example.com", false, false);
        service.setServiceAccount(true);
        userRepository.save(service);

        assertThatThrownBy(() -> authenticationService.resetPassword(service.getUserId(), null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("service account");
        assertThatThrownBy(() -> authenticationService.resetPassword("no-such-user", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setUserPasswordRefusesAnEmailThatIsSomeoneElsesUsername() {
        withPassword("taken@example.com", passwordEncoder.encode(GOOD_PASSWORD));
        User target = newUser("taken@example.com".toUpperCase(), false, false);

        assertThatThrownBy(() -> authenticationService.setUserPassword(target.getUserId(), GOOD_PASSWORD, null))
                .isInstanceOf(ValidationException.class).hasMessageContaining("already someone's username");
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
