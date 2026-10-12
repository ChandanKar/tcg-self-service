package com.tcgdigital.vmcontrol.service.idle;

import com.tcgdigital.vmcontrol.model.VmMetricSample;
import com.tcgdigital.vmcontrol.service.settings.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * When an RDS database counts as idle (E33-T06): no more than rds.idle.max-connections
 * database connections and CPU below rds.idle.cpu-threshold-percent in the sample. Network is not
 * looked at (replication and monitoring keep it busy). A sample without connection data is never
 * idle: an unknown is not a quiet database. Used by idle auto-stop and the idle summary alike.
 */
@Component
public class RdsIdlePolicy {

    @Value("${rds.idle.max-connections:0}")
    private int maxConnections;

    @Value("${rds.idle.cpu-threshold-percent:5}")
    private BigDecimal cpuThresholdPercent = BigDecimal.valueOf(5);

    @Autowired(required = false)
    private SettingsService settings;

    public RdsIdlePolicy() {
    }

    /** For tests and the defaults used when no bean is wired. */
    public RdsIdlePolicy(int maxConnections, BigDecimal cpuThresholdPercent) {
        this.maxConnections = maxConnections;
        this.cpuThresholdPercent = cpuThresholdPercent;
    }

    private int maxConnections() {
        return settings == null ? maxConnections : settings.overrideOr("rds.idle.max-connections", maxConnections);
    }

    private BigDecimal cpuThreshold() {
        return settings == null ? cpuThresholdPercent : settings.overrideOr("rds.idle.cpu-threshold-percent", cpuThresholdPercent);
    }

    /** Null when the sample is idle; otherwise why it is not. */
    public String notIdleReason(VmMetricSample s) {
        if (s.getDbConnections() == null) {
            return "No database connection data at " + s.getSampleTime().toInstant();
        }
        int limit = maxConnections();
        if (s.getDbConnections().compareTo(BigDecimal.valueOf(limit)) > 0) {
            return s.getDbConnections().stripTrailingZeros().toPlainString() + " database connection(s) at "
                    + s.getSampleTime().toInstant() + " (at most " + limit + ")";
        }
        BigDecimal threshold = cpuThreshold();
        if (s.getCpuUtilization() == null) {
            return "No CPU data at " + s.getSampleTime().toInstant();
        }
        if (s.getCpuUtilization().compareTo(threshold) >= 0) {
            return "CPU " + s.getCpuUtilization().stripTrailingZeros().toPlainString() + "% at "
                    + s.getSampleTime().toInstant() + " (threshold " + threshold.stripTrailingZeros().toPlainString() + "%)";
        }
        return null;
    }

    public boolean isIdle(VmMetricSample s) {
        return notIdleReason(s) == null;
    }
}
