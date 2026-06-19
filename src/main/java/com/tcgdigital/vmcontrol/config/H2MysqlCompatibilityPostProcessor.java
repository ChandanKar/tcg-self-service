package com.tcgdigital.vmcontrol.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * Automatically appends H2 MySQL-compatibility parameters to any H2 JDBC URL
 * so that migration files written in MySQL dialect work in both H2 (dev) and
 * MySQL (prod) without any change to the SQL scripts or the .env file.
 *
 * Runs last (LOWEST_PRECEDENCE) so that dotenv has already loaded DB_URL
 * before we inspect it.
 */
public class H2MysqlCompatibilityPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String H2_EXTRAS = ";MODE=MySQL;NON_KEYWORDS=VALUE";

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String dbUrl = environment.getProperty("DB_URL",
                "jdbc:h2:file:./data/vmcontrol;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");

        if (dbUrl.startsWith("jdbc:h2:") && !dbUrl.contains("MODE=")) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("h2-mysql-compat", Map.of("DB_URL", dbUrl + H2_EXTRAS)));
        }
    }
}
