# AWS IAM Permissions

Tracks the IAM actions each feature needs on the AWS credential configured via `AWS_ACCESS_KEY`/`AWS_SECRET_KEY` (see `.env.example`) — currently IAM user `arn:aws:iam::086272791573:user/self-service-user`. This app uses one static long-lived credential for everything — there's no per-feature IAM role — so before enabling any `*.enabled=true` flag, confirm the credential actually has the actions listed for that feature.

## How to verify this yourself (authoritative — better than anything below)

This sandbox has no AWS CLI, so the status column below is based on real evidence gathered during development (see "Evidence" column), not an authoritative audit. The correct way to check any action without risk is IAM's policy simulator, which tests permissions **without executing anything**:

```bash
aws iam simulate-principal-policy \
  --policy-source-arn arn:aws:iam::086272791573:user/self-service-user \
  --action-names ce:GetCostAndUsage ec2:CreateTags eks:TagResource compute-optimizer:GetEC2InstanceRecommendations
```

Run this (or the AWS Console's "Policy simulator" under IAM) before flipping any `*_ENABLED` flag whose actions show "Unverified" below.

## Status legend

| Symbol | Meaning |
|---|---|
| ✅ Granted | Confirmed working via real, successful calls made during this session |
| ❌ Missing | Confirmed denied via a real `AccessDenied`/`UnauthorizedException` captured in the server log |
| ❓ Unverified | Never attempted against real AWS — the feature's flag stayed at its default (`false`) throughout development, so this action was never actually called |

## Full permission table

| Feature | Action | Status | Evidence |
|---|---|---|---|
| EC2 start/stop/discovery | `ec2:DescribeInstances` | ✅ Granted | Used successfully dozens of times this session (VM start/stop, discovery) |
| EC2 start/stop/discovery | `ec2:StartInstances` | ✅ Granted | Real VM starts succeeded live during regression testing |
| EC2 start/stop/discovery | `ec2:StopInstances` | ✅ Granted | Real VM stops succeeded live during regression testing |
| EC2 start/stop/discovery | `ec2:DescribeInstanceStatus` | ✅ Granted | Used successfully as part of the start/stop readiness-polling flow |
| EKS sync/start/stop | `eks:DescribeNodegroup` | ✅ Granted | Used successfully throughout EKS sync testing and the sequence-collision fix work |
| EKS sync/start/stop | `eks:ListNodegroups` | ✅ Granted | Used successfully during EKS sync |
| EKS sync/start/stop | `eks:ListClusters` | ✅ Granted | Used successfully during EKS cluster auto-discovery |
| EKS sync/start/stop | `eks:UpdateNodegroupConfig` | ✅ Granted | Used successfully for EKS node-group start/stop during regression testing |
| CloudWatch metrics | `cloudwatch:GetMetricData` (or `GetMetricStatistics`) | ✅ Granted | The app's scheduled metrics collection has been running and returning real CPU/memory data throughout this session |
| **Phase 1 — cost-allocation tagging** (`COST_TAGGING_ENABLED`, off by default; on with the `cost` profile) | `ec2:CreateTags` | ❓ Unverified | Never called — `cost.tagging.enabled` stayed `false` all session |
| Phase 1 | `ec2:DescribeTags` | ❓ Unverified | Same — never called |
| Phase 1 | `eks:TagResource` | ❓ Unverified | Same — never called |
| Phase 1 | `ce:UpdateCostAllocationTagsStatus` | ❓ Unverified | Same — never called |
| Phase 1 | `ce:ListCostAllocationTags` | ❓ Unverified | Same — never called |
| **Phase 2 — real billing ingestion** (`COST_ACTUALS_ENABLED`, on by default since E08-T10) | `ce:GetCostAndUsage` | ❌ **Missing** | Confirmed via real `CostExplorerException`: *"User: .../self-service-user is not authorized to perform: ce:GetCostAndUsage... because no identity-based policy allows the ce:GetCostAndUsage action"* — captured live when this was briefly (and unintentionally) called during Phase 2 testing |
| Phase 2 | `ce:GetTags` | ❓ Unverified | Never called directly, but given `ce:GetCostAndUsage` is denied, the account likely has **no** `ce:*` permissions at all — a Cost Explorer policy is normally granted as a set, not action-by-action |
| **Phase 3 — AWS-native optimization signals** (`COST_OPTIMIZER_ENABLED` / `COST_RESERVATIONS_ENABLED`, both on by default since E08-T10) | `compute-optimizer:GetEC2InstanceRecommendations` | ❓ Unverified | Never called — `cost.optimizer.enabled` stayed `false`. Note: this one is **not billed per-call** (unlike the `ce:*` actions), so it's safe to test live once you want to enable it |
| Phase 3 | `compute-optimizer:GetEnrollmentStatus` | ❓ Unverified | Not currently called by the code at all (handled instead by catching `OptInRequiredException`) — listed for completeness if you later add an explicit enrollment check |
| Phase 3 | `ce:GetReservationCoverage` | ❓ Unverified | Never called; same reasoning as `ce:GetTags` above — likely missing given `ce:GetCostAndUsage` is denied |
| Phase 3 | `ce:GetReservationUtilization` | ❓ Unverified | Same |
| Phase 3 | `ce:GetSavingsPlansCoverage` | ❓ Unverified | Same |
| Phase 3 | `ce:GetSavingsPlansUtilization` | ❓ Unverified | Same |
| **Commitment recommendations** (`COST_COMMITMENTS_RECOMMENDATIONS_ENABLED`, on by default, E27-T01) | `ce:GetSavingsPlansPurchaseRecommendation` | ❓ Unverified | Mondays, with the coverage snapshot: Compute and EC2 Instance Savings Plans (2 billed calls). Read-only; nothing is purchased |
| Commitment recommendations | `ce:GetReservationPurchaseRecommendation` | ❓ Unverified | Mondays: EC2 Reserved Instances (1 billed call). A denial here leaves the Savings Plan rows intact |
| AWS cost forecast (`COST_AWS_FORECAST_ENABLED`, on by default, E35-T01) | `ce:GetCostForecast` | ❓ Unverified | Mondays: daily 90-day and monthly 18-month forecast of resources tagged `tcg:managed-by=vmcontrol` (2 billed calls) |
| Carbon footprint (`CARBON_ENABLED`, off by default, E37-T01) | `s3:ListBucket` and `s3:GetObject` on the carbon Data Export bucket and prefix (billing account) | ❓ Unverified | Daily: reads new or changed export files; nothing is written to S3 |
| **Phase 4 — anomaly alerts** (E21; tag monitors only return data once the `tcg:environment` cost allocation tag is active, see E08-T02) | `ce:GetAnomalyMonitors` | ❓ Unverified | Called by the admin "Sync monitors" action (`POST /api/v1/cost-anomalies/monitors/sync`) |
| Phase 4 | `ce:CreateAnomalyMonitor` | ❓ Unverified | Only with `COST_ANOMALIES_MANAGE_MONITORS=true` (off by default): one CUSTOM monitor per environment. AWS limits the number of custom monitors per account (check the current Cost Anomaly Detection quota); the sync stops with a warning when it is reached |
| Phase 4 | `ce:GetAnomalies` | ❓ Unverified | Anomaly ingestion (E21-T02) |
| Phase 4 | `ce:ProvideAnomalyFeedback` | ❓ Unverified | Owner feedback on an anomaly (E21-T03) |
| **Waste sweeps** (`WASTE_SWEEP_ENABLED`, off by default, E22) | `ec2:DescribeVolumes` | ❓ Unverified | Read-only daily scan for unattached and gp2 volumes (and the volumes of stopped VMs) |
| Waste sweeps | `ec2:DescribeAddresses` | ❓ Unverified | Unassociated Elastic IPs |
| Waste sweeps | `ec2:DescribeInstances` | ✅ Granted (used by state sync) | Long-stopped instances |
| Waste sweeps | `ec2:DescribeSnapshots`, `ec2:DescribeImages` | ❓ Unverified | Old snapshots, minus those an own AMI uses |
| **Waste fix actions** (`WASTE_ACTIONS_ENABLED`, off by default, E22-T04) | `ec2:CreateSnapshot`, `ec2:DeleteVolume`, `ec2:CreateVolume`, `ec2:CreateTags` | ❓ Unverified | Snapshot-before-delete of an unattached volume, and its rollback; each action needs a second admin's approval |
| Waste fix actions | `ec2:ModifyVolume`, `ec2:ModifySnapshotTier`, `ec2:RestoreSnapshotTier`, `ec2:DescribeAddresses`, `ec2:ReleaseAddress` | ❓ Unverified | gp2 → gp3, snapshot archiving and its restore, Elastic IP release (not reversible) |
| **Safe resize** (`RIGHTSIZING_SAFE_RESIZE_ENABLED`, off by default, E30-T06) | `ec2:ModifyInstanceAttribute`, `ec2:StopInstances`, `ec2:StartInstances` | ❓ Unverified | Stop a running VM, change its instance type, start it again; the old type is put back when the new one will not start |
| Safe resize | `ec2:DescribeInstanceTypeOfferings`, `ec2:DescribeInstanceTypes` | ❓ Unverified | Read-only pre-checks: the type is offered in the VM's zone, runs its architecture, and its ENA requirement |
| **Spot pricing** (`SPOT_PRICING_ENABLED`, off by default, E31-T02) | `ec2:DescribeSpotPriceHistory` | ❓ Unverified | Read-only daily capture of Linux Spot prices for the types Spot VMs and node groups run |
| Spot opportunities (E31-T04) | `ec2:GetSpotPlacementScores` | ❓ Unverified | Read-only 1-10 likelihood of Spot capacity; shown as unknown when denied |
| **Spot conversion** (`SPOT_EKS_CONVERSION_ENABLED`, off by default, E31-T05) | `eks:CreateNodegroup`, `eks:DeleteNodegroup`, `eks:TagResource`, `iam:PassRole` (on the node role) | ❓ Unverified | Creates a SPOT twin of a node group and deletes it when going back to on demand |
| **Provisioning** (`PROVISIONING_ENABLED`, off by default, E32-T02) | `servicecatalog:ProvisionProduct`, `servicecatalog:DescribeRecord`, `servicecatalog:GetProvisionedProductOutputs`, `servicecatalog:TerminateProvisionedProduct` | ❓ Unverified | Builds and destroys environments from Service Catalog products; the product's launch constraint role creates the resources |
| **RDS databases** (`RDS_ENABLED`, off by default, E33-T01) | `rds:StartDBInstance`, `rds:StopDBInstance`, `rds:StartDBCluster`, `rds:StopDBCluster`, `rds:DescribeDBInstances`, `rds:DescribeDBClusters`; with cost tagging also `rds:AddTagsToResource` | ❓ Unverified | Starts and stops RDS DB instances and Aurora clusters registered as AWS_RDS VMs; their CloudWatch metrics need `cloudwatch:GetMetricData` (already listed) |
| **Budgets mirror** (`BUDGETS_AWS_SYNC_ENABLED`, off by default, E20-T06) | `budgets:ViewBudget` | ❓ Unverified | Needed only when mirroring app budgets to AWS Budgets |
| Budgets mirror | `budgets:ModifyBudget` | ❓ Unverified | Create, update and delete of the mirrored budgets (names start with `tcg-`) |
| Phase 4 | `budgets:CreateBudget` / `budgets:DescribeBudgets` | ❓ Unverified | Only needed if real AWS Budget resources are created (optional sub-feature) |
| **EKS metrics** (`EKS_METRICS_ENABLED`, off by default, E26-T01) | `cloudwatch:GetMetricData` | ✅ Granted (used by metrics sync) | AWS/EC2 metrics by AutoScalingGroupName; with `EKS_METRICS_CONTAINER_INSIGHTS_ENABLED` also ContainerInsights `node_memory_utilization` |
| **EKS split cost** (`EKS_SCAD_ENABLED`, off by default, E26-T04) | `athena:StartQueryExecution`, `athena:GetQueryExecution`, `athena:GetQueryResults`, `athena:StopQueryExecution` | ❓ Unverified | Daily query of the CUR 2.0 export's Athena table; needs Split Cost Allocation Data turned on in Data Exports |
| EKS split cost | `glue:GetTable`, `glue:GetDatabase`, `glue:GetPartitions`; `s3:GetObject`, `s3:ListBucket` on the export bucket; `s3:PutObject`, `s3:GetBucketLocation` on the query output location | ❓ Unverified | Athena reads the export through the Glue catalog and writes results to `EKS_SCAD_ATHENA_OUTPUT_LOCATION` (or the workgroup's location) |

## Multi-account (E28)

See [aws-multi-account.md](aws-multi-account.md) for the full setup. The base identity (the platform's key or instance role) needs:

| Action | When |
|---|---|
| `sts:GetCallerIdentity` | Always: resolves the default account's number at startup and verifies accounts |
| `sts:AssumeRole` on `arn:aws:iam::*:role/VmControlAccess` (or your member role name) | `AWS_MULTI_ACCOUNT_ENABLED=true`: reaching member accounts |
| `organizations:ListAccounts` (on the billing account's identity) | `AWS_ORGANIZATIONS_ENABLED=true`: importing member accounts |

Each member account's role needs the EC2, EKS, CloudWatch, tagging and Compute Optimizer actions of the features you use (the member permission set in aws-multi-account.md). Cost Explorer actions are only needed by the billing account.

## Practical takeaway

- **EC2, EKS, CloudWatch: solid** — this is the app's original functionality and it's been exercised continuously and successfully all session.
- **Every `ce:*` (Cost Explorer) action should be treated as missing** until proven otherwise — one is confirmed denied, and Cost Explorer access is normally an all-or-nothing policy grant, not per-action. Since E08-T10 `COST_ACTUALS_ENABLED` and `COST_RESERVATIONS_ENABLED` default to `true`: until the policy below is granted, their daily runs log the denial (denied calls are not billed). Grant the policy, or set both to `false`.
- **`ec2:CreateTags`/`eks:TagResource` (Phase 1) status is a genuine unknown** — these are a different service/action family from the confirmed-denied Cost Explorer actions, so don't assume they're missing too. Run the policy simulator command above before enabling `COST_TAGGING_ENABLED`.
- **`compute-optimizer:GetEC2InstanceRecommendations` is the cheapest one to just try** — it's free per-call, so if you want a real answer fastest, temporarily flip `COST_OPTIMIZER_ENABLED=true`, restart, hit `GET /api/v1/cost-management/rightsizing`, and check the log for a `ComputeOptimizerException` vs. real recommendation data (or an `OptInRequiredException`, which means the permission is fine but the account isn't opted into Compute Optimizer yet).

## Cost features

One policy per feature (E08-T10). Grant only the ones you use; see
[cost-management-setup.md](cost-management-setup.md) for defaults and billing impact.

**Actual costs** (`COST_ACTUALS_ENABLED`, on by default; ~1 billed call a day):

```json
{
  "Version": "2012-10-17",
  "Statement": [{ "Effect": "Allow", "Action": ["ce:GetCostAndUsage"], "Resource": "*" }]
}
```

**Cost-allocation tagging** (`COST_TAGGING_ENABLED`, off by default; writes tags):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["ec2:CreateTags", "eks:TagResource", "ce:ListCostAllocationTags", "ce:UpdateCostAllocationTagsStatus"],
    "Resource": "*"
  }]
}
```

**Compute Optimizer** (`COST_OPTIMIZER_ENABLED`, on by default; not billed):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["compute-optimizer:GetEC2InstanceRecommendations", "compute-optimizer:GetEnrollmentStatus"],
    "Resource": "*"
  }]
}
```

**RI / Savings Plan coverage and purchase recommendations** (`COST_RESERVATIONS_ENABLED`, on by default; 4 billed calls a day, plus 3 on Mondays for `COST_COMMITMENTS_RECOMMENDATIONS_ENABLED`):

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["ce:GetReservationCoverage", "ce:GetReservationUtilization", "ce:GetSavingsPlansCoverage", "ce:GetSavingsPlansUtilization",
               "ce:GetSavingsPlansPurchaseRecommendation", "ce:GetReservationPurchaseRecommendation"],
    "Resource": "*"
  }]
}
```

**EKS split cost** (`EKS_SCAD_ENABLED`, off by default; Athena bills per data scanned):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["athena:StartQueryExecution", "athena:GetQueryExecution", "athena:GetQueryResults", "athena:StopQueryExecution"], "Resource": "*" },
    { "Effect": "Allow", "Action": ["glue:GetTable", "glue:GetDatabase", "glue:GetPartitions"], "Resource": "*" },
    { "Effect": "Allow", "Action": ["s3:GetObject", "s3:ListBucket", "s3:PutObject", "s3:GetBucketLocation"], "Resource": ["arn:aws:s3:::<export-bucket>", "arn:aws:s3:::<export-bucket>/*", "arn:aws:s3:::<athena-results-bucket>", "arn:aws:s3:::<athena-results-bucket>/*"] }
  ]
}
```

**Cost Setup pre-flight check** (the admin page that tests the permissions above):

```json
{
  "Version": "2012-10-17",
  "Statement": [{ "Effect": "Allow", "Action": ["sts:GetCallerIdentity", "iam:SimulatePrincipalPolicy"], "Resource": "*" }]
}
```

## Microsoft Graph permissions (not AWS)

Team membership sync from Entra groups (`TEAMS_ENTRA_SYNC_ENABLED`, off by default, E23-T02) uses the
app's existing Graph client (`GRAPH_DIRECTORY_ENABLED`). It needs these **application** permissions,
with tenant admin consent:

- `GroupMember.Read.All` — read a linked group's members (transitive, so nested groups count);
- `Group.Read.All` — search groups by name when linking a team.

Members whose Entra object id has no app user are skipped, not created.
