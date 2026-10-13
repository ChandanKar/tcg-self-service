# Carbon footprint per environment

The platform shows each environment's share of the AWS carbon footprint next to its cost: a line in the environment page's cost card, and a **Carbon footprint** panel on the Cost Management page with an Excel export. The data comes from the AWS Customer Carbon Footprint Tool through a Data Export to S3, and is split between environments by cost share.

It is off by default (`CARBON_ENABLED=false`). While it is off, the endpoints answer 404 and nothing is shown.

## 1. Create the carbon Data Export (AWS)

In the **management (payer) account**, open **Billing and Cost Management → Data Exports → Create**:

- Export type: **Carbon emissions**.
- Format: **CSV**, compression **GZIP**. Parquet is not read.
- Delivery: an S3 bucket and prefix, for example `s3://acme-billing-exports/carbon/`. Choose overwrite or new files as you prefer; the platform reads new and changed files either way.

AWS refreshes the data monthly. A month usually appears several weeks after it ends, and AWS may revise recent months.

## 2. Give the platform read access

The platform reads the bucket with the **billing account's** credentials (the AWS account marked as billing account on the AWS Accounts page, or the default credentials in single-account mode). It needs:

```json
{
  "Effect": "Allow",
  "Action": ["s3:ListBucket"],
  "Resource": "arn:aws:s3:::acme-billing-exports",
  "Condition": { "StringLike": { "s3:prefix": ["carbon/*"] } }
},
{
  "Effect": "Allow",
  "Action": ["s3:GetObject"],
  "Resource": "arn:aws:s3:::acme-billing-exports/carbon/*"
}
```

For attribution against the whole account's cost (recommended) it also uses `ce:GetCostAndUsage`, which the actual-cost feature already needs. See [aws-iam-permissions.md](aws-iam-permissions.md).

## 3. Configure

| Property (environment variable) | Default | |
|---|---|---|
| `carbon.enabled` (`CARBON_ENABLED`) | false | Ingestion job, endpoints and UI |
| `carbon.export.bucket` (`CARBON_EXPORT_BUCKET`) | | The export's bucket |
| `carbon.export.prefix` (`CARBON_EXPORT_PREFIX`) | | The export's prefix, for example `carbon/` |
| `carbon.export.region` (`CARBON_EXPORT_REGION`) | us-east-1 | The bucket's region |
| `carbon.cron` (`CARBON_CRON`) | `0 0 6 * * *` | Daily ingestion, UTC |
| `carbon.attribution.cost-explorer` | true | Use the account's Cost Explorer cost per region and service as the whole |
| `carbon.columns.*` | the carbon emissions table's names | Column names, see below |

The switch can also be changed at runtime on the Settings page. **Settings → Cost Setup** lists the feature with its permissions. To ingest without waiting for the daily run, an admin can call `POST /api/v1/cost-management/carbon/ingest`.

### Columns

The reader maps columns by header name, so a renamed or reordered column is only a property change:

| Property | Default | Meaning |
|---|---|---|
| `carbon.columns.month` | `usage_period_start` | The month (`2026-08`, `2026-08-01` or a timestamp) |
| `carbon.columns.account` | `usage_account_id` | The account the emissions belong to |
| `carbon.columns.region` | `region_code` | Region; empty becomes `global` |
| `carbon.columns.service` | `product_code` | Service, for example `AmazonEC2` |
| `carbon.columns.mbm` / `.lbm` | `total_mbm_emissions_value` / `total_lbm_emissions_value` | Market-based and location-based emissions |
| `carbon.columns.mbm-unit` / `.lbm-unit` | `total_mbm_emissions_unit` / `total_lbm_emissions_unit` | Their unit: `MTCO2e` or `tCO2e` (×1000), `kgCO2e` (×1) |
| `carbon.columns.unit-multiplier` | 1000 | Used when there is no unit column (1000 for metric tons) |

**Check the headers of your first export against these defaults.** AWS has changed the schema before. If the export has neither emissions column, or lacks a month, account, region or service column, ingestion of that file fails with the missing name in the log and is retried on the next run.

## How ingestion works

Every day the platform lists the CSV files under the prefix. A folder whose files all have the same key and ETag as last time is skipped. Otherwise every file in that folder is read again, because the parts of one export share a folder, and the months they contain are replaced in one transaction. Emissions are stored in kg CO₂e per month, account, region and service. Rows that repeat a month, account, region and service are added up.

## How attribution works

AWS reports carbon per account, region and service, not per resource. For each such bucket in a month:

1. **Each environment's cost** in the bucket is the platform's estimate for its VMs that month: EC2 instances and EKS node groups count as `AmazonEC2`, the EKS control-plane fee as `AmazonEKS`, and RDS databases as `AmazonRDS`. If the environment has actual costs from Cost Explorer that month, the estimate is scaled by the environment's actual/estimated ratio.
2. **The bucket's whole cost** is the account's Cost Explorer cost for that service and region. This is one billed call per region the platform uses. If Cost Explorer is not available, the platform's own costs are the whole.
3. **Each environment gets** emissions × its cost ÷ the bucket's cost. What is left is **Unallocated**: other workloads in the account, services the platform does not run, and buckets with no platform cost.

**Coverage** is the attributed share of the emissions of the accounts the platform knows, that is, the AWS accounts with an account number on the AWS Accounts page. If the platform runs in single-account mode without an account number, its costs count for the export's only other account. When the export covers several accounts, set the account numbers.

A month is rewritten whenever its carbon data changes, so revised AWS data replaces the earlier figures.

## Where it shows

- **Environment page, cost card.** Last full month's attributed kg CO₂e (market-based, or location-based when that is all AWS gives) and a 12-month sparkline, for anyone who can view the environment. `GET /api/v1/environments/{id}/carbon`.
- **Cost Management.** Carbon by environment for a month, largest first with Unallocated last, cost and kg per dollar, the coverage, a 12-month trend of attributed and unallocated emissions, and an Excel export. Admins only: `GET /api/v1/cost-management/carbon?month=yyyy-MM`, `GET /api/v1/cost-management/carbon/export?month=yyyy-MM`.

## Limitations

- These are estimates. Cost share is a proxy: two environments with the same cost can have different footprints, for example different instance families or Graviton.
- Data is monthly and arrives weeks late. The current month is never shown.
- VMs removed from the platform no longer count for the months they ran, so their share becomes unallocated.
- Storage, data transfer and other services the platform does not run stay unallocated.
