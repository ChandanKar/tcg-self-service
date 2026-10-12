package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.CreateApiTokenDTO;
import com.tcgdigital.vmcontrol.dto.CreatedApiTokenDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.ApiToken;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.ApiTokenRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Personal API tokens (E34-T01): format, hashing, scope rules, verification, expiry and revocation. */
@ExtendWith(MockitoExtension.class)
class ApiTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final String PEPPER = "unit-test-pepper-0123456789-abcdefghij";

    @Mock private ApiTokenRepository repository;
    @Mock private UserRepository users;
    @Mock private AuditService audit;

    private ApiTokenService service;
    private User user;
    private ApiToken saved;

    @BeforeEach
    void setUp() {
        service = new ApiTokenService(repository, users, audit);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "pepper", PEPPER);
        ReflectionTestUtils.setField(service, "maxLifetimeDays", 365);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        user = User.fromUsernamePassword("pat", "irrelevant", "pat@example.com", "Pat", "TCG");
        user.setUserId("u-1");
        lenient().when(repository.saveAndFlush(any(ApiToken.class))).thenAnswer(inv -> saved = inv.getArgument(0));
        lenient().when(repository.findByTokenPrefix(anyString())).thenAnswer(inv ->
                saved != null && saved.getTokenPrefix().equals(inv.getArgument(0)) ? Optional.of(saved) : Optional.empty());
        lenient().when(users.findById("u-1")).thenReturn(Optional.of(user));
    }

    private CreatedApiTokenDTO create(String... scopes) {
        return service.create(user, new CreateApiTokenDTO("ci", List.of(scopes), 30));
    }

    @Test
    void aTokenIsShownOnceAndOnlyItsKeyedHashIsStored() {
        CreatedApiTokenDTO created = create("environments:read");

        assertThat(created.token()).matches("vmc_[A-Za-z0-9]{8}_[A-Za-z0-9_-]{43}");
        assertThat(created.prefix()).isEqualTo(created.token().substring(4, 12));
        assertThat(created.expiresAt().toInstant()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(saved.getTokenHash()).hasSize(64).isEqualTo(service.hash(created.token()))
                .doesNotContain(created.token().substring(13));
        assertThat(saved.getScopes()).isEqualTo("environments:read");
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(audit).logAction(eq("u-1"), eq(AuditAction.API_TOKEN_CREATED), eq("API_TOKEN"), eq(saved.getTokenId()),
                eq("ci"), details.capture());
        assertThat(details.getValue()).doesNotContain(created.token());
    }

    @Test
    void aTokenVerifiesUntilRevokedOrExpired() {
        String token = create("environments:read").token();

        assertThat(service.verify(token)).get().extracting(v -> v.user().getUserId()).isEqualTo("u-1");
        assertThat(service.verify(token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A"))).isEmpty();
        assertThat(service.verify("vmc_short")).isEmpty();
        assertThat(service.verify("Bearer " + token)).isEmpty();

        service.setClock(Clock.fixed(NOW.plus(Duration.ofDays(31)), ZoneOffset.UTC));
        assertThat(service.verify(token)).isEmpty();

        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));
        saved.setRevokedAt(Timestamp.from(NOW));
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void aDeactivatedOwnerOrADifferentPepperFailsVerification() {
        String token = create("environments:read").token();

        user.setIsActive(false);
        assertThat(service.verify(token)).isEmpty();

        user.setIsActive(true);
        ReflectionTestUtils.setField(service, "pepper", PEPPER + "-rotated");
        assertThat(service.verify(token)).isEmpty();
    }

    @Test
    void scopesAreLimitedByTheOwnersRole() {
        assertThatThrownBy(() -> create("admin")).isInstanceOf(ValidationException.class).hasMessageContaining("admin");
        assertThatThrownBy(() -> create("access:write")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> create("root")).isInstanceOf(ValidationException.class).hasMessageContaining("Unknown scope");
        assertThatThrownBy(() -> create()).isInstanceOf(ValidationException.class);

        user.setEnvAdmin(true);
        assertThat(create("access:write").scopes()).containsExactly("access:write");
        user.setAdmin(true);
        assertThat(create("admin", "cost:read").scopes()).containsExactly("admin", "cost:read");
    }

    @Test
    void everyTokenExpiresWithinTheMaximumAndAUserHasAtMostTwenty() {
        assertThatThrownBy(() -> service.create(user, new CreateApiTokenDTO("ci", List.of("cost:read"), null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.create(user, new CreateApiTokenDTO("ci", List.of("cost:read"), 366)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("365");
        assertThatThrownBy(() -> service.create(user, new CreateApiTokenDTO(" ", List.of("cost:read"), 1)))
                .isInstanceOf(ValidationException.class);

        when(repository.countActive(eq("u-1"), any())).thenReturn(20L);
        assertThatThrownBy(() -> create("cost:read")).isInstanceOf(ValidationException.class).hasMessageContaining("20");
    }

    @Test
    void anotherUsersTokenCannotBeRevoked() {
        create("cost:read");
        when(repository.findById(saved.getTokenId())).thenReturn(Optional.of(saved));

        assertThatThrownBy(() -> service.revoke("u-2", saved.getTokenId(), "u-2")).isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).revoke(anyString(), anyString(), anyString(), any());

        when(repository.revoke(eq(saved.getTokenId()), eq("u-1"), eq("u-1"), any())).thenReturn(1);
        service.revoke("u-1", saved.getTokenId(), "u-1");
        verify(audit).logAction(eq("u-1"), eq(AuditAction.API_TOKEN_REVOKED), eq("API_TOKEN"), eq(saved.getTokenId()), eq("ci"), anyString());
    }

    @Test
    void offOrWithoutAStrongPepperNothingWorks() {
        String token = create("cost:read").token();
        ReflectionTestUtils.setField(service, "enabled", false);

        assertThat(service.verify(token)).isEmpty();
        assertThatThrownBy(() -> create("cost:read")).isInstanceOf(ResourceNotFoundException.class);
        service.checkPepper(); // off: no pepper needed

        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "pepper", "too-short");
        assertThatThrownBy(service::checkPepper).isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
        assertThat(service.isEnabled()).isFalse();
    }
}
