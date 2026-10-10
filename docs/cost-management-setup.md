# Cost Management setup

Cost Management always shows **estimated** spend, built from VM runtime and the static pricing
file. The AWS-backed features below add real billing data and AWS's own recommendations.

The read-only features are **on by default**. Each one checks for AWS credentials first and does
nothing without them. The two features that change something are **off by default**: tag writes
and report emails. The `cost` profile turns those two on.

## Features

| Feature | Default | Property | Env var | IAM (see [aws-iam-permissions.md](aws-iam-permissions.md#cost-features)) | AWS-side prerequisite | Billing impact |
|---|---|---|---|---|---|---|
| Estimated costs, daily snapshots | On | `cost.snapshot.enabled` | `COST_SNAPSHOT_ENABLED` | none | none | none |
| Actual costs (Cost Explorer) | On | `cost.actuals.enabled` | `COST_ACTUALS_ENABLED` | `ce:GetCostAndUsage` | `tcg:` cost-allocation tags active (up to 24 h after first tagging) | ~1 call/day, $0.01 each, plus 1 per extra page |
| Compute Optimizer recommendations | On | `cost.optimizer.enabled` | `COST_OPTIMIZER_ENABLED` | `compute-optimizer:GetEC2InstanceRecommendations` | Account opted in to Compute Optimizer; ~14 days of data | Free; cached per region for `cost.optimizer.cache-minutes` |
| RI / Savings Plan coverage | On | `cost.reservations.enabled` | `COST_RESERVATIONS_ENABLED` | `ce:GetReservationCoverage`, `ce:GetReservationUtilization`, `ce:GetSavingsPlansCoverage`, `ce:GetSavingsPlansUtilization` | none | 4 calls/day, $0.01 each |
| Weekly reports (bell) | On | `notification.weekly-reports.enabled` | `NOTIFICATION_WEEKLY_REPORTS_ENABLED` | none | none | none |
| Cost-allocation tagging (**writes** tags) | Off | `cost.tagging.enabled` | `COST_TAGGING_ENABLED` | `ec2:CreateTags`, `eks:TagResource`, `ce:ListCostAllocationTags`, `ce:UpdateCostAllocationTagsStatus` | none | Free |
| Weekly report emails | Off | `notification.email.weekly-*-report.enabled` | `NOTIFICATION_EMAIL_WEEKLY_*_REPORT_ENABLED` | none | SMTP configured and `notification.email.enabled=true` | none |

When a permission is missing, the daily job logs the AWS denial and carries on. A denied call is
not billed. Set the feature's env var to `false` to stop it.

## The `cost` profile

```
SPRING_PROFILES_ACTIVE=prod,cost
```

`application-cost.properties` turns on cost-allocation tagging and the three weekly report emails.
The emails also need `notification.email.enabled=true` and SMTP (`spring.mail.*`), or nothing is
sent.

## Lead times

- **Cost-allocation tags**: after the first tagging run, AWS takes up to 24 hours to make the tags
  usable in Cost Explorer. Until then, actual-cost ingestion finds no environments.
- **Compute Optimizer**: opt the account in (AWS console, Compute Optimizer). Recommendations
  appear after about 14 days of metrics. Until then, rightsizing uses the app's CPU rule.
- **Actuals**: Cost Explorer figures stay provisional for about 72 hours. The daily job re-ingests
  the last `cost.actuals.trailing-days` days (default 3), so they correct themselves.

## First day checklist

1. Grant the IAM policies for the features you use (see the link above).
2. Optional: start with the `cost` profile to tag resources, or run **Reconcile Tags Now** on the
   Cost Management page.
3. Wait up to 24 hours for tag activation, then run **Ingest Actual Costs** once.
4. Run **Backfill 30 Days** once so the trend chart has history. After the E08 upgrade, run it
   once anyway: older snapshot rows held partial-day compute.
5. Check the **Cost Setup** page (admin), which tests each permission and feature flag.
