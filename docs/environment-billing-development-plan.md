# Environment billing and key monitoring — development plan

Branch: `feature/environment-billing-key-monitoring` (from `main` at `78873af9`). Task definitions and acceptance criteria: [environment-billing-and-key-monitoring-tasks.md](environment-billing-and-key-monitoring-tasks.md).

## UI boundary

Do not modify existing SPA screens, navigation, styles, copy, layout, or workflows while delivering the backend. Expose new functionality through versioned REST DTOs and OpenAPI first. If a browser interface is required, build a separate, isolated page using new assets and a new URL; review its design before connecting it to the existing navigation. Existing UI regression tests must remain unchanged and pass. The backlog's D2/D3 tasks remain open until that separate interface is accepted.

## Delivery order and gates

| Stage | Tasks | Development | Test and validation gate |
| --- | --- | --- | --- |
| 0. Billing contract | A1–A3 | A1 fixes account/project scope, cost basis, treatment of charge types, and reconciliation rules; A2 maps categories and A3 checks billing/CUR prerequisites. | A1 implementation contract is complete. A2/A3 validate source mappings and readiness; finance signs off on a live period at F3. Unknown usage types stay visible. |
| 1. AWS access and inventory | A4, B1–B2 | Use the account registry and AssumeRole pattern. Add read-only inventory adapters, historical resource identity, and scan health. | Mocked AWS pagination/partial-failure tests; MySQL uniqueness and ownership-history tests; permission checks per account. No AWS writes. |
| 2. Ownership and tagging | B3–B5, F1 | Add scoped assignment APIs, tag preview/apply adapters, audit, drift scan, and billing-tag activation status. | Security tests for cross-environment access and forbidden writes; per-resource tag round-trip against a staging account; unrelated tags preserved. Manual tag writes enabled only for a small pilot. |
| 3. Billing ingestion | C1, C6 | Ingest CE summaries and CUR 2.0 detail using daily, idempotent imports. Keep original billing columns and source keys. | Fixture tests for every requested service/usage category; duplicate imports, paging, changed AWS bills, failed pages, and bounded backfill. Compare raw totals with AWS billing exports. |
| 4. Attribution and reconciliation | C2–C5 | Apply tag, historical resource ownership, shared rule, then unallocated. Incorporate existing EKS split-cost data without double counting. | Sum of direct + shared + unallocated equals source total per account/day/currency/basis. Test historical reassignment, conflicting tags, rounding, absent resource IDs, and shared NAT/EKS cases. Finance approves pilot period. |
| 5. APIs and exports | D1, D4 | Add environment-scoped breakdown and ownership workbench APIs; extend exports and chargeback using actual-cost provenance. | Role/scope integration tests, pagination and filter tests, export-to-API equality, CSV injection tests. Existing SPA snapshot/layout tests pass unchanged. |
| 6. Credential monitoring | E1–E4 | Add read-only Bedrock service-specific and IAM access-key inventory, age/expiry scheduler, recipients, deduplication, and health. | Test no secret values are stored/logged; 90-day UTC boundary; expiry thresholds, renewal, inactive/deleted keys, pagination, scan failure, and duplicate alerts. Validate alerts in staging with non-production credentials. |
| 7. Separate interface and rollout | D2–D3, F2–F3 | Design a new isolated billing/tagging page without changing existing UI assets or behavior. Pilot accounts, reconcile a full billing period, and enable switches progressively. | Compare page, API, export, and AWS totals; accessibility/phone/browser checks for the new page; existing UI regression suite unchanged; finance and security sign-off. |

## Data and implementation rules

- Add new Flyway versions; do not edit applied migrations. Use DTOs at API boundaries and `SecurityService` for environment scope.
- Keep billed actuals separate from estimates. Store billing source, currency, cost basis, usage period, resource ID when present, attribution reason, rule version, and import freshness.
- Use immutable environment IDs internally. A mutable display name or tag value must not rewrite historical ownership.
- Treat split-cost rows as allocation evidence, not extra charges. Preserve unallocated and unclassified lines until resolved.
- Use existing scheduler DB locks, settings switches, audit, and notifications. Re-ingest provisional billing days; record failures without publishing a false zero.
- Do not create, reset, deactivate, or delete IAM or Bedrock credentials. This feature reads metadata and alerts only.

## AWS access to request and verify

Grant per feature and account, after checking existing policies. Payer/billing account reads billing data; member account roles discover/tag resources and read IAM credential metadata. Prefer account/region/resource scoping and tag-key conditions where AWS supports them. A permission check in the application must report denials before enabling a feature.

| Capability | Actions to assess | Where and why |
| --- | --- | --- |
| CE summaries and tag status | `ce:GetCostAndUsage`, `ce:GetCostAndUsageWithResources` if resource-level CE is selected, `ce:GetTags`, `ce:ListCostAllocationTags`; `ce:UpdateCostAllocationTagsStatus` only if activation is managed by the portal | Billing account. `ce:GetCostAndUsage` is recorded as denied for the current credential in `docs/aws-iam-permissions.md`; verify again for the deployment role. |
| CUR 2.0 detail | `s3:ListBucket`, `s3:GetObject` on the export bucket/prefix; `athena:StartQueryExecution`, `athena:GetQueryExecution`, `athena:GetQueryResults`, `athena:StopQueryExecution`; `glue:GetDatabase`, `glue:GetTable`, `glue:GetPartitions`; `s3:PutObject` on the Athena output prefix | Billing account, only if CUR detail is queried through Athena. Existing EKS split-cost permissions can be extended to the billing query paths. |
| Cross-account access | `sts:AssumeRole` on explicit member roles | Existing multi-account pattern; grant the member roles the relevant read/tag actions. |
| Generic tag discovery/writes | `tag:GetResources`, `tag:TagResources`, plus native service tagging actions where required | Member accounts/regions. `GetResources` returns tagged or previously tagged resources, so native listing is still needed to find never-tagged resources. |
| EC2 family inventory/tags | `ec2:DescribeInstances`, `ec2:DescribeVolumes`, `ec2:DescribeSnapshots`, `ec2:DescribeImages`, `ec2:DescribeAddresses`, `ec2:DescribeNatGateways`, `ec2:DescribeInternetGateways`, `ec2:DescribeVpcEndpoints`, `ec2:DescribeTags`, `ec2:CreateTags` | Member accounts. Restrict write targets and keys; EC2 covers instances, EBS, AMIs/snapshots, EIPs, and VPC networking resources. |
| S3 | `s3:ListAllMyBuckets`, `s3:GetBucketLocation`, `s3:GetBucketTagging`, `s3:PutBucketTagging` (or supported S3 resource-tag API) | Member accounts; bucket tagging writes must preserve existing tags. |
| EFS | `elasticfilesystem:DescribeFileSystems`, `elasticfilesystem:ListTagsForResource`, `elasticfilesystem:TagResource` | Member accounts/regions. |
| Load balancers | `elasticloadbalancing:DescribeLoadBalancers`, `elasticloadbalancing:DescribeTags`, `elasticloadbalancing:AddTags` | Member accounts/regions; choose supported ELB API version for the discovered resource. |
| ECR | `ecr:DescribeRepositories`, `ecr:ListTagsForResource`, `ecr:TagResource` | Member accounts/regions. |
| EKS and RDS | Existing `eks:ListClusters`, `eks:ListNodegroups`, `eks:DescribeNodegroup`, `eks:TagResource`; `rds:DescribeDBInstances`, `rds:DescribeDBClusters`, `rds:ListTagsForResource`, `rds:AddTagsToResource` | Extend current tagging and discovery permissions only where missing. |
| Key metadata | `iam:ListUsers`, `iam:ListAccessKeys`, `iam:GetAccessKeyLastUsed` (optional), `iam:ListServiceSpecificCredentials` | Member accounts. Read metadata only; no credential creation, reset, update, or deletion actions. |

Exact IAM resource ARNs and condition keys must be tested against each API before a policy is finalized. Discovery/list APIs often require `Resource: *`; tag writes should use narrower resources and permitted tag keys where supported. The application also needs AWS-side configuration, not just IAM: activate cost-allocation tags, enable the chosen Cost Explorer granularity, and provide a CUR 2.0 export with resource IDs and split-cost data if that path is used.

## Release gate

Do not mark a period complete until source totals reconcile, unallocated and unclassified amounts are visible, AWS permission checks pass, credential scans have a recent success timestamp, and a representative pilot period is accepted. Roll back by disabling the new ingestion/tag-write/scan switches; retain imported billing history and audit records for diagnosis.
