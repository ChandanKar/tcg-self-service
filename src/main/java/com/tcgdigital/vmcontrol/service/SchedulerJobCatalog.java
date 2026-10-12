package com.tcgdigital.vmcontrol.service;

import org.springframework.core.env.Environment;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Every scheduled job, with the lock name it runs under, its expected interval and whether it is
 * switched on (E12-T05). Intervals and switches are read from the same properties (and
 * defaults) the scheduler classes use, so the health view judges each job by its own schedule.
 */
@Component
public class SchedulerJobCatalog {

    /** One job: a fixed-rate property (milliseconds or, with minutes=true, minutes) or a cron property. */
    private record Spec(String jobName, String label, String rateProperty, long defaultRate, boolean minutes,
                        String cronProperty, String defaultCron, String enabledProperty, boolean enabledByDefault) {
    }

    private static Spec rate(String job, String label, String property, long defaultMs, String enabled, boolean on) {
        return new Spec(job, label, property, defaultMs, false, null, null, enabled, on);
    }

    private static Spec rateMinutes(String job, String label, String property, long defaultMinutes, String enabled, boolean on) {
        return new Spec(job, label, property, defaultMinutes, true, null, null, enabled, on);
    }

    private static Spec cron(String job, String label, String property, String defaultCron, String enabled, boolean on) {
        return new Spec(job, label, null, 0, false, property, defaultCron, enabled, on);
    }

    private static final List<Spec> SPECS = List.of(
            rate("vm_state_sync", "State sync", "vm.state.sync.interval", 300_000, "vm.state.sync.enabled", true),
            rateMinutes("vm_metrics_sync", "Metrics sync", "cloudwatch.metric.schedule.interval", 5, "cloudwatch.metric.enable", true),
            rateMinutes("vm_metric_daily_rollup", "Metrics daily rollup", "cloudwatch.metric.rollup.schedule.interval", 60,
                    "cloudwatch.metric.rollup.enable", true),
            cron("vm_metrics_archive", "Metrics archive (daily)", "vm.metrics.archive.cron", "0 30 2 * * *",
                    "vm.metrics.archive.enabled", true),
            rate("vm_inventory_sync", "Inventory sync", "vm.inventory.sync.interval", 3_600_000, "vm.inventory.sync.enabled", true),
            rate("vm_discovery", "VM discovery", "vm.discovery.interval", 600_000, "vm.discovery.enabled", false),
            rate("eks_sync", "EKS sync", "eks.sync.interval", 300_000, "eks.sync.status-refresh.enabled", true),
            rate("automation_rules", "Automation rules", "automation.rules.interval", 60_000, "automation.rules.enabled", true),
            rate("idle_auto_stop", "Idle auto-stop", "automation.idle-stop.interval", 300_000, "automation.idle-stop.enabled", false),
            rate("lock-expiry", "Lock expiry", "locks.expiry.interval-ms", 60_000, "locks.expiry.enabled", false),
            cron("access_expiration", "Access expiry (daily)", null, "0 0 1 * * *", null, true),
            cron("cost_snapshot", "Cost snapshot (daily)", "cost.snapshot.cron", "0 0 3 * * *", "cost.snapshot.enabled", true),
            cron("actual_cost_ingestion", "Actual costs (daily)", "cost.actuals.cron", "0 0 5 * * *", "cost.actuals.enabled", true),
            cron("reservation_coverage_snapshot", "RI/SP coverage (daily)", "cost.reservations.cron", "0 15 5 * * *",
                    "cost.reservations.enabled", true),
            cron("tag_reconciliation", "Cost tagging (daily)", "cost.tagging.cron", "0 30 2 * * *", "cost.tagging.enabled", false),
            cron("weekly_notification_reports", "Weekly reports", "notification.weekly-reports.cron", "0 0 9 * * MON",
                    "notification.weekly-reports.enabled", true),
            cron("data_retention", "Data retention (daily)", "retention.cron", "0 0 3 * * *", "retention.enabled", false),
            rate("lease-sweep", "Lease sweep", "lease.sweep.interval-ms", 60_000, "lease.enabled", false),
            cron("savings_ledger", "Savings ledger (daily)", "savings.ledger.cron", "0 30 3 * * *",
                    "savings.ledger.enabled", true),
            cron("budget_evaluation", "Budget evaluation (daily)", "budgets.evaluation.cron", "0 30 5 * * *",
                    "budgets.evaluation.enabled", true),
            cron("cost_anomaly_ingest", "Cost anomalies (twice daily)", "cost.anomalies.cron", "0 0 */12 * * *",
                    "cost.anomalies.enabled", false),
            cron("waste_sweep", "Waste sweep (daily)", "waste.sweep.cron", "0 0 4 * * *", "waste.sweep.enabled", false),
            cron("team_membership_sync", "Team membership sync (hourly)", "teams.entra-sync.cron", "0 15 * * * *",
                    "teams.entra-sync.enabled", false),
            cron("chargeback_statement", "Chargeback statement (monthly)", "chargeback.cron", "0 0 6 5 * *",
                    "chargeback.enabled", false),
            cron("approval_expiry", "Approval expiry (every 10 min)", "approvals.expiry-cron", "0 */10 * * * *",
                    "approvals.enabled", false),
            cron("quota_warnings", "Team quota warnings (hourly)", "quotas.warning-cron", "0 5 * * * *",
                    "quotas.enabled", false),
            cron("eks_split_cost", "EKS split cost ingestion (daily)", "eks.scad.cron", "0 30 6 * * *",
                    "eks.scad.enabled", false),
            cron("compute_optimizer_capture", "Compute Optimizer capture (daily)", "cost.optimizer.cron", "0 45 5 * * *",
                    "cost.optimizer.enabled", true),
            cron("spot_price_capture", "Spot prices (daily)", "spot.pricing.cron", "0 20 4 * * *",
                    "spot.pricing.enabled", false));

    /** A job as the health view needs it. */
    public record Job(String jobName, String label, Duration expectedInterval, boolean enabled) {
    }

    private final Environment environment;

    public SchedulerJobCatalog(Environment environment) {
        this.environment = environment;
    }

    public List<Job> jobs() {
        boolean schedulingOn = environment.getProperty("app.scheduling.enabled", Boolean.class, true);
        return SPECS.stream().map(spec -> new Job(spec.jobName(), spec.label(), interval(spec),
                schedulingOn && (spec.enabledProperty() == null
                        || environment.getProperty(spec.enabledProperty(), Boolean.class, spec.enabledByDefault()))))
                .toList();
    }

    private Duration interval(Spec spec) {
        if (spec.cronProperty() != null || spec.defaultCron() != null) {
            String expression = spec.cronProperty() == null ? spec.defaultCron()
                    : environment.getProperty(spec.cronProperty(), spec.defaultCron());
            return cronPeriod(expression);
        }
        long value = environment.getProperty(spec.rateProperty(), Long.class, spec.defaultRate());
        return spec.minutes() ? Duration.ofMinutes(value) : Duration.ofMillis(value);
    }

    /** The gap between a cron expression's next two firings (daily = 24 h, weekly = 7 d). */
    static Duration cronPeriod(String expression) {
        try {
            CronExpression cron = CronExpression.parse(expression);
            LocalDateTime first = cron.next(LocalDateTime.now());
            LocalDateTime second = first == null ? null : cron.next(first);
            return first == null || second == null ? Duration.ofDays(1) : Duration.between(first, second);
        } catch (IllegalArgumentException e) {
            return Duration.ofDays(1);
        }
    }
}
