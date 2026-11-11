package com.tcgdigital.vmcontrol.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * U21-T11 (owner decision 2026-11-11): environment admins manage only the environments they hold an
 * environment-wide Admin grant on, unless SECURITY_ENV_ADMIN_SCOPE=global. Reads the main property
 * file, not the test one (the tests pin 'global').
 */
class SecurityDefaultsTest {

    @Test
    void environmentAdminsAreLimitedToTheirOwnEnvironmentsByDefault() throws IOException {
        Properties p = PropertiesLoaderUtils.loadProperties(new FileSystemResource("src/main/resources/application.properties"));

        assertThat(p.getProperty("security.env-admin.scope")).isEqualTo("${SECURITY_ENV_ADMIN_SCOPE:assigned}");
    }
}
