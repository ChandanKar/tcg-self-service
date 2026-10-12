package com.tcgdigital.vmcontrol.service.settings;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AppSetting;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.repository.AppSettingRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runtime settings (E29-T01): precedence, typing, the 30 s cache, validation and the registry's guards. */
class SettingsServiceTest {

    private static final String FLAG = "notification.email.lock-broken.enabled";
    private static final String CPU = "vm.metrics.idle.cpu-threshold-percent";

    private AppSettingRepository repository;
    private AuditService audit;
    private MockEnvironment environment;
    private SettingsService service;
    private Instant now = Instant.parse("2026-10-13T10:00:00Z");

    @BeforeEach
    void setUp() {
        repository = mock(AppSettingRepository.class);
        audit = mock(AuditService.class);
        environment = new MockEnvironment().withProperty(FLAG, "false").withProperty(CPU, "5");
        when(repository.findById(any())).thenReturn(Optional.empty());
        service = new SettingsService(repository, environment, audit);
        service.clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    private void advance(long seconds) {
        now = now.plusSeconds(seconds);
        service.clock = Clock.fixed(now, ZoneOffset.UTC);
    }

    @Test
    void anOverrideWinsOverThePropertyWhichWinsOverTheDefault() {
        assertThat(service.getBoolean(FLAG, true)).isFalse();             // property
        assertThat(service.getInt("no.such.key", 7)).isEqualTo(7);        // caller's default

        when(repository.findById(FLAG)).thenReturn(Optional.of(new AppSetting(FLAG, "true", "u")));
        advance(31);
        assertThat(service.getBoolean(FLAG, false)).isTrue();             // override
    }

    @Test
    void valuesAreCachedForThirtySecondsAndAWriteForgetsThemAtOnce() {
        assertThat(service.getDecimal(CPU, BigDecimal.ONE)).isEqualByComparingTo("5");
        when(repository.findById(CPU)).thenReturn(Optional.of(new AppSetting(CPU, "8", "other-instance")));

        advance(20);
        assertThat(service.getDecimal(CPU, BigDecimal.ONE)).isEqualByComparingTo("5"); // still cached
        advance(11);
        assertThat(service.getDecimal(CPU, BigDecimal.ONE)).isEqualByComparingTo("8"); // picked up within 30 s

        service.set(CPU, "12.5", "admin");
        when(repository.findById(CPU)).thenReturn(Optional.of(new AppSetting(CPU, "12.5", "admin")));
        assertThat(service.getDecimal(CPU, BigDecimal.ONE)).isEqualByComparingTo("12.5"); // no wait on this instance
    }

    @Test
    void aWriteIsValidatedSavedAndAuditedWithOldAndNewValues() {
        service.set(FLAG, "TRUE", "admin");

        verify(repository).save(argThat(s -> s.getSettingKey().equals(FLAG) && s.getSettingValue().equals("true")));
        verify(audit).logAction(eq("admin"), eq(AuditAction.SYSTEM_CONFIG_UPDATED), eq("setting"), isNull(), eq(FLAG),
                eq(FLAG + ": false -> true"));
    }

    @Test
    void badValuesAndReadOnlyOrUnknownKeysAreRefused() {
        assertThatThrownBy(() -> service.set(FLAG, "yes", "a")).isInstanceOf(ValidationException.class).hasMessageContaining("true or false");
        assertThatThrownBy(() -> service.set(CPU, "150", "a")).isInstanceOf(ValidationException.class).hasMessageContaining("between 0 and 100");
        assertThatThrownBy(() -> service.set("rightsizing.scale-down.consecutive-days", "2.5", "a"))
                .isInstanceOf(ValidationException.class).hasMessageContaining("whole number");
        assertThatThrownBy(() -> service.set("entraid.enabled", "false", "a")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> service.set("spring.datasource.password", "x", "a"))
                .isInstanceOf(com.tcgdigital.vmcontrol.exception.ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void resetRemovesTheOverride() {
        when(repository.existsById(FLAG)).thenReturn(true);
        when(repository.findById(FLAG)).thenReturn(Optional.of(new AppSetting(FLAG, "true", "u")), Optional.empty());

        SettingsService.SettingView view = service.reset(FLAG, "admin");

        verify(repository).deleteById(FLAG);
        assertThat(view.source()).isEqualTo(SettingsService.Source.DEFAULT);
        assertThat(view.value()).isEqualTo("false");
    }

    @Test
    void noSecretIsEverRegistered() {
        Pattern secret = Pattern.compile("(password|secret|token|api-key|signing-key|access-key|credential)", Pattern.CASE_INSENSITIVE);
        assertThat(SettingsRegistry.DEFINITIONS).noneMatch(d -> secret.matcher(d.key()).find());
    }

    @Test
    void everyRegisteredKeyExistsInTheApplicationProperties() throws Exception {
        Properties properties = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/application.properties")) {
            properties.load(in);
        }
        // The main application.properties is on the test classpath behind the test one; read it explicitly.
        try (InputStream in = new java.io.FileInputStream("src/main/resources/application.properties")) {
            properties.load(in);
        }
        assertThat(SettingsRegistry.DEFINITIONS).allSatisfy(d -> assertThat(properties).containsKey(d.key()));
    }

    @Test
    void definitionsHaveUniqueKeysAndNumbersHaveBounds() {
        assertThat(SettingsRegistry.DEFINITIONS).extracting(SettingDefinition::key).doesNotHaveDuplicates();
        assertThat(SettingsRegistry.DEFINITIONS).filteredOn(d -> d.type() == SettingDefinition.Type.INT || d.type() == SettingDefinition.Type.DECIMAL)
                .allSatisfy(d -> assertThat(d.min()).isNotNull());
    }
}
