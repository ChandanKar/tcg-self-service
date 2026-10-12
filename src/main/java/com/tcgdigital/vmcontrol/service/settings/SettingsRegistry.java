package com.tcgdigital.vmcontrol.service.settings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.tcgdigital.vmcontrol.service.settings.SettingDefinition.*;

/**
 * Every setting the Settings page knows (E29-T01). Tier 1: flags and thresholds the code reads
 * at call time, editable live. Tier 2: shown read-only (they are wired at startup). Secrets
 * (passwords, keys, tokens) are never registered, so they can neither be read nor written here.
 */
public final class SettingsRegistry {

    private SettingsRegistry() {
    }

    private static final String JOB_SWITCH = " The job itself must be enabled at startup for this to take effect.";

    public static final List<SettingDefinition> DEFINITIONS = List.of(
            // Notifications
            flag("Notifications", "notification.email.enabled", "Email notifications",
                    "Master switch for every notification email (needs SMTP).", false),
            flag("Notifications", "notification.email.access-requested.enabled", "Access requested", "Email approvers when someone requests access.", false),
            flag("Notifications", "notification.email.access-expiring.enabled", "Access expiring", "Email users before their access expires.", false),
            flag("Notifications", "notification.email.access-expired.enabled", "Access expired", "Email users when their access has expired.", false),
            flag("Notifications", "notification.email.access-granted.enabled", "Access granted", "Email users when access is granted.", false),
            flag("Notifications", "notification.email.access-request-approved.enabled", "Request approved", "Email requesters when a request is approved.", false),
            flag("Notifications", "notification.email.access-request-denied.enabled", "Request denied", "Email requesters when a request is denied.", false),
            flag("Notifications", "notification.email.access-revoked.enabled", "Access revoked", "Email users when access is revoked.", false),
            flag("Notifications", "notification.email.operation-failed.enabled", "Operation failed", "Email the requester when a start or stop fails.", false),
            flag("Notifications", "notification.email.lock-broken.enabled", "Lock broken", "Email the lock owner when an admin breaks their lock.", false),
            flag("Notifications", "notification.email.lease-expiring.enabled", "Lease expiring", "Email lease holders before a lease ends.", false),
            flag("Notifications", "notification.email.budget-alert.enabled", "Budget alerts", "Email budget alerts.", false),
            flag("Notifications", "notification.email.cost-anomaly.enabled", "Cost anomalies", "Email new cost anomalies.", false),
            flag("Notifications", "notification.weekly-reports.enabled", "Weekly reports", "Build the weekly in-app reports." + JOB_SWITCH, true),
            flag("Notifications", "notification.email.weekly-cost-report.enabled", "Weekly cost report email", "Email the weekly cost report.", false),
            flag("Notifications", "notification.email.weekly-idle-waste-report.enabled", "Weekly idle waste email", "Email the weekly idle waste report.", false),
            flag("Notifications", "notification.email.weekly-rightsizing-report.enabled", "Weekly rightsizing email", "Email the weekly rightsizing report.", false),

            // Cost management
            flag("Cost management", "cost.snapshot.enabled", "Daily cost snapshots", "Record each environment's estimated cost every day.", true),
            flag("Cost management", "cost.actuals.enabled", "Actual costs", "Ingest real costs from AWS Cost Explorer daily (billed API).", true),
            flag("Cost management", "cost.optimizer.enabled", "Compute Optimizer", "Use AWS Compute Optimizer recommendations for rightsizing.", true),
            flag("Cost management", "cost.reservations.enabled", "Reservation coverage", "Record Savings Plan and RI coverage daily (billed API).", true),
            flag("Cost management", "cost.tagging.enabled", "Cost-allocation tagging", "Tag AWS resources with their environment and team.", false),

            // Rightsizing
            decimal("Rightsizing", "rightsizing.scale-down.cpu-threshold", "Scale-down CPU %",
                    "Average CPU below which a VM is a scale-down candidate.", "15", "1", "100"),
            integer("Rightsizing", "rightsizing.scale-down.consecutive-days", "Scale-down days",
                    "Days in a row the CPU must stay below the threshold.", 3, 1, 90),
            decimal("Rightsizing", "rightsizing.scale-up.cpu-threshold", "Scale-up CPU %",
                    "CPU above which a VM needs more capacity.", "70", "1", "100"),
            integer("Rightsizing", "rightsizing.scale-up.consecutive-minutes", "Scale-up minutes",
                    "Minutes in a row the CPU must stay above the threshold.", 15, 1, 1440),
            integer("Rightsizing", "rightsizing.post-resize-cooldown-days", "Cooldown after a resize",
                    "Days a resized VM is not recommended again.", 14, 0, 365),
            flag("Rightsizing", "rightsizing.memory.enabled", "Memory-aware rightsizing",
                    "Use memory (CloudWatch agent) as well as CPU; off returns the CPU-only rules.", true),
            decimal("Rightsizing", "rightsizing.scale-down.memory-peak-threshold", "Scale-down memory peak %",
                    "Daily memory peak every recent day must stay below for a scale-down.", "60", "1", "100"),
            decimal("Rightsizing", "rightsizing.scale-up.memory-threshold", "Scale-up memory %",
                    "Memory above which (in every sample of the scale-up window) a VM needs more capacity.", "85", "1", "100"),
            integer("Rightsizing", "rightsizing.memory.headroom-percent", "Memory headroom %",
                    "Spare memory a smaller type must keep above the observed peak.", 20, 0, 200),
            flag("Rightsizing", "rightsizing.safe-resize.enabled", "Safe resize of running VMs",
                    "Apply may stop a running VM, change its type and start it again, rolling back on failure.", false),

            // Idle detection
            decimal("Idle detection", "vm.metrics.idle.cpu-threshold-percent", "Idle CPU %",
                    "CPU below which a running VM counts as idle.", "5", "0", "100"),
            integer("Idle detection", "vm.metrics.idle.network-threshold-bytes-per-period", "Idle network bytes",
                    "Network bytes per sample period below which a VM counts as idle.", 1048576, 0, 10_000_000_000L),
            integer("Idle detection", "vm.metrics.idle.disk-threshold-bytes-per-period", "Idle disk bytes",
                    "Disk bytes per sample period below which a VM counts as idle.", 1048576, 0, 10_000_000_000L),
            integer("Idle detection", "vm.metrics.idle.minimum-duration-minutes", "Idle for at least (minutes)",
                    "How long a VM must stay quiet to be reported idle.", 30, 5, 10080),

            // Sync and discovery
            flag("Sync and discovery", "vm.state.sync.enabled", "State sync", "Reconcile VM states with the cloud." + JOB_SWITCH, true),
            flag("Sync and discovery", "vm.inventory.sync.enabled", "Inventory sync", "Refresh instance types and volumes." + JOB_SWITCH, true),
            flag("Sync and discovery", "cloudwatch.metric.enable", "Metrics collection", "Collect CloudWatch metrics for running VMs." + JOB_SWITCH, true),
            flag("Sync and discovery", "vm.metrics.archive.enabled", "Metrics archive", "Archive raw metric samples daily." + JOB_SWITCH, true),
            flag("Sync and discovery", "vm.discovery.enabled", "VM discovery", "Discover and register EC2 instances." + JOB_SWITCH, false),
            flag("Sync and discovery", "eks.sync.auto-register.enabled", "EKS auto-registration", "Register newly found EKS clusters." + JOB_SWITCH, false),
            flag("Sync and discovery", "eks.sync.status-refresh.enabled", "EKS status refresh", "Refresh EKS node-group status." + JOB_SWITCH, true),

            // Automation and governance
            flag("Automation and governance", "automation.rules.enabled", "Automation rules", "Run calendar and access-grant automation rules." + JOB_SWITCH, true),
            flag("Automation and governance", "approvals.enabled", "Cost-aware approvals", "Hold starts that match an approval policy.", false),
            flag("Automation and governance", "quotas.enabled", "Team quotas", "Check monthly team quotas on start.", false),
            flag("Automation and governance", "allocation.require-team", "Require a team", "Refuse environments without an owning team.", false),
            flag("Automation and governance", "chat.enabled", "Microsoft Teams messages", "Send notifications to Teams channels.", false),

            // Data retention
            flag("Data retention", "retention.enabled", "Data retention", "Purge old rows daily." + JOB_SWITCH, false),
            integer("Data retention", "retention.audit-log.days", "Audit log days", "Days audit rows are kept.", 365, 30, 3650),
            integer("Data retention", "retention.notification.read-days", "Read notification days", "Days read notifications are kept.", 90, 7, 3650),
            integer("Data retention", "retention.notification.unread-days", "Unread notification days", "Days unread notifications are kept.", 180, 7, 3650),
            integer("Data retention", "retention.state-history.days", "State history days", "Days VM state history is kept.", 180, 30, 3650),
            integer("Data retention", "retention.email-log.days", "Email log days", "Days the email log is kept.", 180, 7, 3650),

            // Tier 2: read-only
            readOnly("Restart required", "entraid.enabled", "Microsoft Entra ID sign-in", "Chooses the security configuration at startup."),
            readOnly("Restart required", "aws.region", "Default AWS region", "Fallback region for AWS calls."),
            readOnly("Restart required", "spring.mail.host", "SMTP host", "Mail server, wired at startup."),
            readOnly("Restart required", "spring.mail.port", "SMTP port", "Mail server port, wired at startup."),
            readOnly("Restart required", "vm.metrics.archive.cron", "Metrics archive schedule", "Cron, read at startup."),
            readOnly("Restart required", "cost.snapshot.cron", "Cost snapshot schedule", "Cron, read at startup."),
            readOnly("Restart required", "cost.tagging.cron", "Tag reconciliation schedule", "Cron, read at startup."),
            readOnly("Restart required", "cost.actuals.cron", "Actual cost schedule", "Cron, read at startup."),
            readOnly("Restart required", "cost.reservations.cron", "Reservation coverage schedule", "Cron, read at startup."),
            readOnly("Restart required", "cost.optimizer.cron", "Compute Optimizer capture schedule", "Cron, read at startup."),
            readOnly("Restart required", "notification.weekly-reports.cron", "Weekly report schedule", "Cron, read at startup."),
            readOnly("Restart required", "retention.cron", "Retention schedule", "Cron, read at startup."),
            readOnly("Restart required", "savings.ledger.cron", "Savings ledger schedule", "Cron, read at startup."),
            readOnly("Restart required", "budgets.evaluation.cron", "Budget evaluation schedule", "Cron, read at startup."),
            readOnly("Restart required", "cost.anomalies.cron", "Cost anomaly schedule", "Cron, read at startup."),
            readOnly("Restart required", "waste.sweep.cron", "Waste sweep schedule", "Cron, read at startup."),
            readOnly("Restart required", "chargeback.cron", "Chargeback schedule", "Cron, read at startup."));

    private static final Map<String, SettingDefinition> BY_KEY = new LinkedHashMap<>();

    static {
        DEFINITIONS.forEach(d -> BY_KEY.put(d.key(), d));
    }

    public static Optional<SettingDefinition> find(String key) {
        return Optional.ofNullable(BY_KEY.get(key));
    }
}
