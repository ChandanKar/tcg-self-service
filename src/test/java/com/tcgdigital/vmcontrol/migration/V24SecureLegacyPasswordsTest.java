package com.tcgdigital.vmcontrol.migration;

import db.migration.V24__secure_legacy_passwords;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.support.AbstractIntegrationTest;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Re-runs the V24 migration body against rows inserted after Flyway has already migrated the
 * test schema, covering each kind of stored password it must handle.
 */
class V24SecureLegacyPasswordsTest extends AbstractIntegrationTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void clearsV3AccountsAndHashesOtherPlainTextPasswords() throws Exception {
        User legacyPlain = user("legacy.plain", "leaked-value-1", 9001);
        User legacyRehashed = user("legacy.rehashed", encoder.encode("leaked-value-2"), 9002);
        User otherPlain = user("other.plain", "plain-value-3", null);
        String existingHash = encoder.encode("kept-value-4");
        User otherHashed = user("other.hashed", existingHash, null);
        User blank = user("blank", "", null);

        runMigration();

        assertThat(password(legacyPlain)).as("V3 plain text is cleared").isNull();
        assertThat(password(legacyRehashed)).as("V3 value re-hashed at login is still the leaked value").isNull();
        assertThat(password(otherPlain)).startsWith("$2");
        assertThat(encoder.matches("plain-value-3", password(otherPlain))).isTrue();
        assertThat(password(otherHashed)).as("existing bcrypt hash is untouched").isEqualTo(existingHash);
        assertThat(password(blank)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE password IS NOT NULL AND password NOT LIKE '$2%'", Integer.class))
                .isZero();
    }

    @Test
    void runningTwiceChangesNothingTheSecondTime() throws Exception {
        User otherPlain = user("other.plain", "plain-value-3", null);
        runMigration();
        String firstHash = password(otherPlain);

        runMigration();

        assertThat(password(otherPlain)).isEqualTo(firstHash);
    }

    private void runMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            Context context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V24__secure_legacy_passwords().migrate(context);
        }
    }

    private User user(String username, String password, Integer legacyUserId) {
        User user = newUser(username + "@example.com", false, false);
        user.setUsername(username);
        user.setPassword(password);
        user.setLegacyUserId(legacyUserId);
        return userRepository.save(user);
    }

    private String password(User user) {
        return jdbcTemplate.queryForObject("SELECT password FROM app_user WHERE user_id = ?",
                String.class, user.getUserId());
    }
}
