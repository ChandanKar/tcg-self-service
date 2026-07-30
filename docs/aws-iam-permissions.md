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
| **Phase 1 — cost-allocation tagging** (`COST_TAGGING_ENABLED`, off by default) | `ec2:CreateTags` | ❓ Unverified | Never called — `cost.tagging.enabled` stayed `false` all session |
| Phase 1 | `ec2:DescribeTags` | ❓ Unverified | Same — never called |
| Phase 1 | `eks:TagResource` | ❓ Unverified | Same — never called |
| Phase 1 | `ce:UpdateCostAllocationTagsStatus` | ❓ Unverified | Same — never called |
| Phase 1 | `ce:ListCostAllocationTags` | ❓ Unverified | Same — never called |
| **Phase 2 — real billing ingestion** (`COST_ACTUALS_ENABLED`, off by default) | `ce:GetCostAndUsage` | ❌ **Missing** | Confirmed via real `CostExplorerException`: *"User: .../self-service-user is not authorized to perform: ce:GetCostAndUsage... because no identity-based policy allows the ce:GetCostAndUsage action"* — captured live when this was briefly (and unintentionally) called during Phase 2 testing |
| Phase 2 | `ce:GetTags` | ❓ Unverified | Never called directly, but given `ce:GetCostAndUsage` is denied, the account likely has **no** `ce:*` permissions at all — a Cost Explorer policy is normally granted as a set, not action-by-action |
| **Phase 3 — AWS-native optimization signals** (`COST_OPTIMIZER_ENABLED` / `COST_RESERVATIONS_ENABLED`, both off by default) | `compute-optimizer:GetEC2InstanceRecommendations` | ❓ Unverified | Never called — `cost.optimizer.enabled` stayed `false`. Note: this one is **not billed per-call** (unlike the `ce:*` actions), so it's safe to test live once you want to enable it |
| Phase 3 | `compute-optimizer:GetEnrollmentStatus` | ❓ Unverified | Not currently called by the code at all (handled instead by catching `OptInRequiredException`) — listed for completeness if you later add an explicit enrollment check |
| Phase 3 | `ce:GetReservationCoverage` | ❓ Unverified | Never called; same reasoning as `ce:GetTags` above — likely missing given `ce:GetCostAndUsage` is denied |
| Phase 3 | `ce:GetReservationUtilization` | ❓ Unverified | Same |
| Phase 3 | `ce:GetSavingsPlansCoverage` | ❓ Unverified | Same |
| Phase 3 | `ce:GetSavingsPlansUtilization` | ❓ Unverified | Same |
| **Phase 4 — budgets + anomaly alerts** (not yet built) | `ce:CreateAnomalyMonitor` | ❓ Unverified | Feature doesn't exist yet |
| Phase 4 | `ce:GetAnomalyMonitors` | ❓ Unverified | Feature doesn't exist yet |
| Phase 4 | `ce:GetAnomalies` | ❓ Unverified | Feature doesn't exist yet |
| Phase 4 | `budgets:CreateBudget` / `budgets:DescribeBudgets` | ❓ Unverified | Only needed if real AWS Budget resources are created (optional sub-feature) |

## Practical takeaway

- **EC2, EKS, CloudWatch: solid** — this is the app's original functionality and it's been exercised continuously and successfully all session.
- **Every `ce:*` (Cost Explorer) action should be treated as missing** until proven otherwise — one is confirmed denied, and Cost Explorer access is normally an all-or-nothing policy grant, not per-action. Don't enable `COST_ACTUALS_ENABLED` or `COST_RESERVATIONS_ENABLED` until this is fixed and re-verified.
- **`ec2:CreateTags`/`eks:TagResource` (Phase 1) status is a genuine unknown** — these are a different service/action family from the confirmed-denied Cost Explorer actions, so don't assume they're missing too. Run the policy simulator command above before enabling `COST_TAGGING_ENABLED`.
- **`compute-optimizer:GetEC2InstanceRecommendations` is the cheapest one to just try** — it's free per-call, so if you want a real answer fastest, temporarily flip `COST_OPTIMIZER_ENABLED=true`, restart, hit `GET /api/v1/cost-management/rightsizing`, and check the log for a `ComputeOptimizerException` vs. real recommendation data (or an `OptInRequiredException`, which means the permission is fine but the account isn't opted into Compute Optimizer yet).
