package com.tcgdigital.vmcontrol.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compute Optimizer and the bell reports are on by default; the billed Cost Explorer reads, tag
 * writes and report emails are off, and the 'cost' profile turns them on (E08-T10, U21-T05: the
 * AWS account denies ce:GetCostAndUsage). Reads the main property files, not the test ones.
 */
class CostDefaultsTest {

    private static Properties main(String name) throws IOException {
        return PropertiesLoaderUtils.loadProperties(new FileSystemResource("src/main/resources/" + name));
    }

    @Test
    void costExplorerAndTagWritesDefaultOffComputeOptimizerOn() throws IOException {
        Properties p = main("application.properties");

        assertThat(p.getProperty("cost.actuals.enabled")).isEqualTo("${COST_ACTUALS_ENABLED:false}");
        assertThat(p.getProperty("cost.optimizer.enabled")).isEqualTo("${COST_OPTIMIZER_ENABLED:true}");
        assertThat(p.getProperty("cost.reservations.enabled")).isEqualTo("${COST_RESERVATIONS_ENABLED:false}");
        assertThat(p.getProperty("notification.weekly-reports.enabled")).isEqualTo("${NOTIFICATION_WEEKLY_REPORTS_ENABLED:true}");
        assertThat(p.getProperty("cost.aws-forecast.enabled")).isEqualTo("${COST_AWS_FORECAST_ENABLED:false}");
        assertThat(p.getProperty("cost.commitments.recommendations.enabled")).isEqualTo("${COST_COMMITMENTS_RECOMMENDATIONS_ENABLED:false}");
        assertThat(p.getProperty("cost.tagging.enabled")).isEqualTo("${COST_TAGGING_ENABLED:false}");
        assertThat(p.getProperty("notification.email.weekly-cost-report.enabled")).endsWith(":false}");
    }

    @Test
    void theCostProfileEnablesCostExplorerTaggingAndReportEmails() throws IOException {
        Properties p = main("application-cost.properties");

        assertThat(p.getProperty("cost.actuals.enabled")).isEqualTo("true");
        assertThat(p.getProperty("cost.reservations.enabled")).isEqualTo("true");
        assertThat(p.getProperty("cost.aws-forecast.enabled")).isEqualTo("true");
        assertThat(p.getProperty("cost.commitments.recommendations.enabled")).isEqualTo("true");
        assertThat(p.getProperty("cost.tagging.enabled")).isEqualTo("true");
        assertThat(p.getProperty("notification.email.weekly-cost-report.enabled")).isEqualTo("true");
        assertThat(p.getProperty("notification.email.weekly-idle-waste-report.enabled")).isEqualTo("true");
        assertThat(p.getProperty("notification.email.weekly-rightsizing-report.enabled")).isEqualTo("true");
    }

    @Test
    void testsNeverTouchAwsCostApis() throws IOException {
        Properties p = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertThat(p.getProperty("cost.actuals.enabled")).isEqualTo("false");
        assertThat(p.getProperty("cost.optimizer.enabled")).isEqualTo("false");
        assertThat(p.getProperty("cost.reservations.enabled")).isEqualTo("false");
        assertThat(p.getProperty("notification.weekly-reports.enabled")).isEqualTo("false");
    }
}
