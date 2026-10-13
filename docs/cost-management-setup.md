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
| Compute Optimizer recommendations | On | `cost.optimizer.enabled` | `COST_OPTIMIZER_ENABLED` | `compute-optimizer:GetEC2InstanceRecommendations` | Account opted in to Compute Optimizer; ~14 days of data | Free; captured daily at `cost.optimizer.cron` (05:45) and stored, the page reads the stored rows |
| AWS cost forecast (E35) | On | `cost.aws-forecast.enabled` | `COST_AWS_FORECAST_ENABLED` | `ce:GetCostForecast` | Enough cost history for AWS to forecast; resources tagged `tcg:managed-by=vmcontrol` (cost tagging) | 2 calls a week (Mondays 06:00 UTC), $0.01 each |
| RI / Savings Plan coverage | On | `cost.reservations.enabled` | `COST_RESERVATIONS_ENABLED` | `ce:GetReservationCoverage`, `ce:GetReservationUtilization`, `ce:GetSavingsPlansCoverage`, `ce:GetSavingsPlansUtilization` | none | 4 calls/day, $0.01 each |
| Cost anomalies (Cost Anomaly Detection) | Off (on in the `cost` profile) | `cost.anomalies.enabled` | `COST_ANOMALIES_ENABLED` | `ce:GetAnomalies` (and `ce:GetAnomalyMonitors` for the admin monitor sync) | Anomaly monitors on the `tcg:environment` tag (create them, or set `COST_ANOMALIES_MANAGE_MONITORS=true`); the tag must be active | 1 call per page per run, about 2-4 calls a day at the default 12-hour cadence, $0.01 each |
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

## FOCUS export (E35)

**Cost Management → Export FOCUS CSV** downloads one month as a FOCUS 1.2 CSV.
`GET /api/v1/cost-management/export/focus?month=yyyy-MM&basis=estimated|actual`, admins only;
the month cannot be in the future, and the current month stops at yesterday.

The header is the 57 FOCUS 1.2 columns in specification order, then two custom columns. Columns
not in the table below are left empty: the platform has no source for them.

| Column | Estimated (one row per resource per day) | Actual (one row per environment per day) |
|---|---|---|
| BilledCost, EffectiveCost, ListCost, ContractedCost | The day's estimate | Cost Explorer's UnblendedCost for the environment tag |
| BillingAccountId / BillingAccountName | The billing account (else the default account) from AWS Accounts | same |
| BillingCurrency, PricingCurrency | `USD` | `USD` |
| BillingPeriodStart / End | First day of the month / first day of the next month (UTC) | same |
| ChargePeriodStart / End | The day (UTC) | The day (UTC) |
| ChargeCategory / ChargeFrequency | `Usage` / `Usage-Based` | same |
| ChargeDescription | Service, instance type and resource | The environment |
| ConsumedQuantity, PricingQuantity / units | Hours the resource ran / `Hours` | empty |
| ListUnitPrice, ContractedUnitPrice | The hourly rate used | empty |
| PricingCategory | `Standard` (on-demand list rate) | empty |
| ProviderName, PublisherName, InvoiceIssuerName | `AWS` | `AWS` |
| RegionId | The resource's region | empty |
| ResourceId / ResourceName / ResourceType | Instance id, node group or `db:`/`cluster:` id / display name / `Instance`, `Node Group`, `DB Instance`, `DB Cluster` | empty |
| ServiceName / ServiceCategory / ServiceSubcategory | `Amazon Elastic Compute Cloud` / `Compute` / `Virtual Machines`; RDS: `Amazon Relational Database Service` / `Databases` / `Relational Databases` | `AWS` / `Other` / `Other (Other)` |
| SkuId, SkuPriceId | The instance type or DB class | empty |
| SubAccountId / SubAccountName | The resource's AWS account | empty |
| Tags | JSON: `tcg:environment`, `tcg:team`, `tcg:cost-center` when set | same, for the environment |
| x_CostBasis | `Estimated` | `Actual (Cost Explorer UnblendedCost by tag)` |
| x_PriceApproximate | `true` when another region's rate was used, `unknown` when no rate exists | `false` |

**Limitations**

- Estimates come from the static pricing reference, not from the bill. There are no discounts,
  commitments, credits, taxes or support charges, so the commitment and capacity reservation
  columns stay empty.
- Actuals are per environment, not per resource: Cost Explorer is read by the `tcg:environment`
  tag. Spend without that tag is not in the file.
- The estimated basis covers resources registered now. A VM removed before the export is left out
  even for days it ran.
- InvoiceId, BillingAccountType, SubAccountType and AvailabilityZone have no source and stay empty.
