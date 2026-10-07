package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Secures passwords at rest (finding C1).
 *
 * <p>1. Accounts seeded by V3 ({@code legacy_user_id IS NOT NULL}) lose their password. The V3
 * values are in git history and must be treated as compromised, including any that were already
 * re-hashed by the old lazy plain-text migration (the hash still matches the leaked value). An
 * admin sets a new password with {@code PUT /api/v1/users/{id}/password}; Entra sign-in is
 * unaffected.
 *
 * <p>2. Any other password that is not a bcrypt hash is hashed in place, so plain-text values no
 * longer exist in the database and the login code no longer needs a plain-text branch.
 *
 * <p>Never print or log password values here.
 */
public class V24__secure_legacy_passwords extends BaseJavaMigration {

    private static final Logger log = LoggerFactory.getLogger(V24__secure_legacy_passwords.class);

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        int cleared;
        try (PreparedStatement clear = connection.prepareStatement(
                "UPDATE app_user SET password = NULL, password_updated_at = NULL "
                        + "WHERE legacy_user_id IS NOT NULL AND password IS NOT NULL")) {
            cleared = clear.executeUpdate();
        }

        Map<String, String> plainText = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT user_id, password FROM app_user "
                        + "WHERE password IS NOT NULL AND password <> '' AND password NOT LIKE '$2%'");
             ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                plainText.put(rows.getString(1), rows.getString(2));
            }
        }

        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE app_user SET password = ?, password_updated_at = CURRENT_TIMESTAMP WHERE user_id = ?")) {
            for (Map.Entry<String, String> row : plainText.entrySet()) {
                update.setString(1, encoder.encode(row.getValue()));
                update.setString(2, row.getKey());
                update.addBatch();
            }
            if (!plainText.isEmpty()) {
                update.executeBatch();
            }
        }

        try (PreparedStatement blank = connection.prepareStatement(
                "UPDATE app_user SET password = NULL WHERE password = ''")) {
            blank.executeUpdate();
        }

        log.info("V24: cleared passwords of {} V3-seeded account(s); hashed {} other plain-text password(s)",
                cleared, plainText.size());
    }
}
