# Environment billing and key monitoring — delivery tasks

Status: proposed. Mark a task complete only when its acceptance criteria and validation rules pass. Record an owner, PR, and evidence in the tracking table as work starts.

| ID | Status | Owner | PR / evidence |
| --- | --- | --- | --- |
| A1 | [ ] | — | — |
| A2 | [ ] | — | — |
| A3 | [ ] | — | — |
| A4 | [ ] | — | — |
| B1 | [ ] | — | — |
| B2 | [ ] | — | — |
| B3 | [ ] | — | — |
| B4 | [ ] | — | — |
| B5 | [ ] | — | — |
| C1 | [ ] | — | — |
| C2 | [ ] | — | — |
| C3 | [ ] | — | — |
| C4 | [ ] | — | — |
| C5 | [ ] | — | — |
| C6 | [ ] | — | — |
| D1 | [ ] | — | — |
| D2 | [ ] | — | — |
| D3 | [ ] | — | — |
| D4 | [ ] | — | — |
| E1 | [ ] | — | — |
| E2 | [ ] | — | — |
| E3 | [ ] | — | — |
| E4 | [ ] | — | — |
| F1 | [ ] | — | — |
| F2 | [ ] | — | — |
| F3 | [ ] | — | — |

## Scope and accounting invariant

The environment bill covers NAT Gateway and internet transfer, S3, EBS, AMI storage, EFS, VPC endpoints, load balancers, EKS extended support and worker EC2, ECR, Elastic IP, RDS, EC2, and Bedrock. Bedrock key expiry and IAM access-key age are separate monitoring requirements. Users can assign resources to environments/projects and apply supported cost tags from the application.

For one billing period, cost basis, currency, and account scope: **directly attributed + allocated shared + unallocated = included AWS billed total**. Never silently discard an untagged charge or add a split-cost record on top of its original charge. Display actual billed cost separately from estimates. A resource-level breakdown is shown only where AWS supplies sufficient evidence; service and usage-type charges remain visible otherwise.

## A — Billing definitions and AWS setup

### A1 — Define the billing contract

- **Task:** Agree billing account scope, currency, time zone, cost basis (currently `UnblendedCost`), project identity, period boundaries, and handling of credits, taxes, support, refunds, and adjustments.
- **Acceptance:** Finance/product approve worked examples for direct, shared, and unallocated charges; all reporting surfaces use the same definition.
- **Validation:** Every included charge is counted once. Conflicting currency/account data or an unresolved reconciliation blocks finalization.
- **Draft contract:** [environment-billing-contract.md](environment-billing-contract.md). A1 remains open until its decision and approval checklist is completed.

### A2 — Define the service and usage taxonomy

- **Task:** Version a mapping from AWS service/usage types to all 13 requested cost categories and useful subcategories (storage, requests, throughput, running hours, transfer, support, and so on).
- **Acceptance:** Representative billing lines for every category classify consistently; unknown types appear as `Other/Unclassified`.
- **Validation:** Classification preserves source amount. EKS worker EC2 is shown in the EKS view without doubling the account total. AMI storage maps to the applicable snapshot/storage charge, not an assumed AMI fee.

### A3 — Add billing readiness checks

- **Task:** Extend cost setup checks for payer/member access, Cost Explorer, active allocation tags, resource-level settings, and CUR 2.0/Athena export where detailed lines are needed.
- **Acceptance:** The setup page reports ready, pending, or failed per account, with an actionable reason.
- **Validation:** Missing permission, inactive tags, stale data, and missing export never appear as zero spend.

### A4 — Extend AWS clients and permissions

- **Task:** Add least-privilege account-scoped read/tag APIs for the resource inventory and IAM credential scans.
- **Acceptance:** Each configured account can be checked independently; one failing account does not prevent the others from updating.
- **Validation:** Verify account identity before writes; restrict writable tag keys and resource scope; never store or log credential secrets.

## B — Resource ownership and tagging in the portal

### B1 — Create a common AWS resource registry

- **Task:** Persist account, region, resource type, stable ID/ARN, display name, AWS tags, environment/project ownership, shared status, lifecycle state, and discovery time. Include resources outside the existing VM model.
- **Acceptance:** Current and deleted resources remain identifiable for billing history.
- **Validation:** Unique identity is account + applicable region + type + AWS ID. Environment renames do not rewrite historical ownership.

### B2 — Discover cost-bearing resources

- **Task:** Add inventory adapters for S3, EBS volumes and snapshots/AMIs, EFS, NAT/Internet Gateways, VPC endpoints, load balancers, ECR, Elastic IP, and relevant EKS/RDS/EC2 resources.
- **Acceptance:** Each adapter handles pagination, account/region scope, repeat scans, and partial AWS errors; the UI distinguishes discovered from registered resources.
- **Validation:** Never assign ownership from a similar name alone; retain historical ownership after deletion.

### B3 — Assign resources to environment and project

- **Task:** Allow authorized users to assign/reassign a resource, set an effective date and reason, or mark it shared. Store an audit trail.
- **Acceptance:** Ownership history and the current assignment are visible.
- **Validation:** Environment is active and belongs to an allowed account; project comes from an approved registry/vocabulary; prevent conflicting active direct owners.

### B4 — Preview and apply AWS cost tags

- **Task:** Show proposed `tcg:environment`, `tcg:project`, and ownership tag changes; extend `TagReconciliationService` beyond EC2/EKS/RDS using the supported API for each resource type. Support single and batch actions with per-resource results.
- **Acceptance:** The application applies and reads back tags; failures are shown per resource; all changes are audited.
- **Validation:** Restrict writes to authorized admins, preserve unrelated tags, reject invalid values, unsupported resources, account/region mismatches, and unauthorized reassignment. A successful write is not yet proof that billing tags are active.

### B5 — Monitor tag coverage and drift

- **Task:** Compare portal ownership, AWS tags, and billing tag activation on a locked schedule.
- **Acceptance:** Per-account/type counts show correctly tagged, missing, conflicting, unsupported, and pending-activation resources.
- **Validation:** Scan is idempotent; failed or pending tagging is never represented as complete billing coverage.

## C — Actual billing ingestion and attribution

### C1 — Ingest detailed billed charges

- **Task:** Extend current Cost Explorer environment totals with service/usage detail and CUR 2.0 lines where resource IDs and split allocations are necessary. Store day, accounts, service, usage type, resource ID if supplied, tags, amount, currency, basis, and source.
- **Acceptance:** All pages are imported; source records can be traced; provisional billing days can be refreshed.
- **Validation:** Stable source keys make reruns idempotent; malformed rows are isolated and reported; original billing dimensions are preserved.

### C2 — Attribute every charge

- **Task:** Resolve each charge by activated environment tag, verified resource ownership valid on the usage day, or approved shared rule. Record evidence and status (`DIRECT_TAG`, `RESOURCE_MAPPING`, `SHARED_ALLOCATION`, `UNALLOCATED`).
- **Acceptance:** Every included charge has exactly one attribution status; conflicting evidence is reviewable.
- **Validation:** Account alone cannot imply an environment in a shared account; current ownership cannot be applied blindly to past charges; unknowns remain unallocated.

### C3 — Allocate shared resources between environments

- **Task:** Add resource-level allocation rules for shared NAT/networking, load balancers, EFS, S3, EKS infrastructure, and other shared resources. Keep these distinct from existing environment-to-team split rules. Prefer measured usage; support approved fixed/percentage fallback.
- **Acceptance:** Admins preview allocations, effective periods, source charges, and rule evidence before activation.
- **Validation:** Fixed shares total 100%; incompatible periods cannot overlap; rounded allocations sum to the original charge; approvals and changes are audited.

### C4 — Use AWS split-cost data where available

- **Task:** Reuse existing EKS workload ingestion and incorporate CUR split-cost/networking allocation evidence when configured.
- **Acceptance:** Supported shared EKS and networking charges can be allocated by AWS usage evidence; coverage and source are visible.
- **Validation:** Split rows redistribute an original charge and are never added as extra billed cost. Unsupported cases use an approved rule or stay unallocated.

### C5 — Reconcile account and environment totals

- **Task:** Compare detailed imports, allocated environment amounts, existing actual snapshots, and AWS account totals by day/month.
- **Acceptance:** Report billed, direct, shared, unallocated, variance, freshness, and mismatching account/date/category.
- **Validation:** Direct + shared + unallocated equals included billed cost within the approved currency rounding tolerance; material variance blocks finalization.

### C6 — Backfill and correct billing periods

- **Task:** Support bounded history import, recent provisional-day refresh, AWS adjustments, and controlled corrections to finalized periods.
- **Acceptance:** Admin sees progress and failures; reruns do not change totals unless AWS source data changed.
- **Validation:** Limit dates/accounts; report historical tag gaps instead of assuming today's tags applied in the past; audit finalized-period adjustments.

## D — Billing API, UI, and exports

### D1 — Add scoped environment billing APIs

- **Task:** Provide totals and breakdowns by service, category, resource where available, project, account, and day/month.
- **Acceptance:** Responses contain amount, currency, basis, attribution, source, freshness, and drill-down IDs.
- **Validation:** Enforce `SecurityService` environment scope; paginate details; reject invalid ranges; do not reveal other environments' resource IDs.

### D2 — Build the environment billing view

- **Task:** Show direct, allocated shared, and unallocated costs with total-to-service-to-usage-to-resource drill-down for all requested categories.
- **Acceptance:** Shared amounts show their rule; unavailable data is distinct from zero; actual and estimated amounts have clear labels.
- **Validation:** Visible totals match API and export for the same filters and cost basis.

### D3 — Build an ownership and allocation workbench

- **Task:** Surface untagged resources, unallocated charges, tag drift, and shared resources with assignment, tag-preview, and rule-preview actions.
- **Acceptance:** Admin can resolve a charge from the portal and see its effect after the next eligible billing refresh.
- **Validation:** Offer only supported actions; show that tag changes do not rewrite earlier AWS bills.

### D4 — Extend exports and chargeback

- **Task:** Export detailed environment bills and feed attributable actuals to chargeback while retaining the estimated-cost option.
- **Acceptance:** CSV/Excel includes source, category, available resource ID, allocation method, freshness, and reconciliation status.
- **Validation:** Export totals match UI/API filters; escape spreadsheet formula characters in untrusted text.

## E — Bedrock and IAM credentials

### E1 — Inventory Bedrock API keys

- **Task:** Scan long-term Bedrock service-specific credentials per in-scope account; store identifier/alias, IAM owner, status, creation, and optional expiry date.
- **Acceptance:** Portal distinguishes expiring, expired, inactive, and never-expiring credentials.
- **Validation:** Never fetch/store/display/log key values. Missing expiry means no configured expiry, not a failed scan.

### E2 — Attribute Bedrock usage and cost

- **Task:** Include Bedrock billing in taxonomy and map usage through supported workload/project tags or IAM-principal attribution.
- **Acceptance:** Report states the attribution evidence and distinguishes environment cost from exact per-key cost.
- **Validation:** Do not claim exact per-key cost when a principal uses multiple credentials; leave unmappable charges unallocated.

### E3 — Notify before Bedrock key expiry

- **Task:** Add a locked daily scan and configurable warning thresholds (for example 30/14/7 days and expired), using existing notification channels.
- **Acceptance:** Configured owners/admins receive the expected in-app alert and enabled email/chat notifications.
- **Validation:** Deduplicate by credential + threshold + expiry version. Renewed/deleted/inactive keys stop obsolete warnings. Scan failures are visible.

### E4 — Alert on IAM access-key age

- **Task:** Scan active IAM user keys across accounts, calculate age from AWS creation time, optionally show last use, and notify at the 90-day threshold.
- **Acceptance:** Portal shows account, user, masked key ID, age, status, last use, and alert state; recipients receive deduplicated alerts.
- **Validation:** Calculate age in UTC. Define inactive-key and exception policy. The 90-day rule is an alert threshold, not an AWS expiry date; never handle secret values.

## F — Security, testing, and rollout

### F1 — Authorization, audit, and operational controls

- **Task:** Add scoped reads, restricted writes, audit entries, feature switches, scheduler health, and actionable AWS failure states.
- **Acceptance:** Every assignment, tag/rule change, and manual import is traceable; users see only authorized environment costs.
- **Validation:** Failed AWS writes leave a clear pending/failed state; no secrets or sensitive exports appear in logs.

### F2 — Automated verification

- **Task:** Test taxonomy, attribution priority, historical ownership, allocation rounding, reconciliation, tag writes, partial account failure, key thresholds, permissions, API responses, and UI totals.
- **Acceptance:** Billing fixtures cover every requested category plus Bedrock expiry and IAM age; targeted unit, integration, frontend, and security tests pass.
- **Validation:** Include missing tags/resource IDs, conflicting owners, duplicate imports, late adjustments, and cross-environment access attempts.

### F3 — Pilot and finance acceptance

- **Task:** Pilot selected accounts/environments, compare a complete billing period with AWS Billing/CUR, resolve exceptions, then expand.
- **Acceptance:** Finance approves totals; owners approve tags/shared rules; operations confirms notification delivery and deduplication.
- **Validation:** Agree reconciliation tolerance and maximum unallocated percentage before rollout; do not mark a materially unresolved environment bill complete.

## Dependencies and coverage

Billing path: A1–A4 → B1–B5 and C1 → C2–C6 → D1–D4. Credential tasks E1–E4 can proceed alongside billing. F1–F2 apply throughout; F3 is the release gate.

| Requirement | Tasks |
| --- | --- |
| Tag-based project/environment allocation | A1, A3, B3–B5, C1–C6, D1–D4 |
| 1 NAT/Internet transfer | A2, B2–B4, C1–C4 |
| 2 S3 | A2, B2–B4, C1–C3 |
| 3 EBS | A2, B2–B4, C1–C2 |
| 4 AMI storage | A2, B2–B4, C1–C2 |
| 5 EFS | A2, B2–B4, C1–C3 |
| 6 VPC endpoints | A2, B2–B4, C1–C3 |
| 7 Load balancers | A2, B2–B4, C1–C3 |
| 8 EKS support/infrastructure | A2, B2–B4, C1–C4 |
| 9 ECR | A2, B2–B4, C1–C2 |
| 10 Elastic IP | A2, B2–B4, C1–C2 |
| 11 RDS | A2, B2–B5, C1–C2 |
| 12 EC2 | A2, B2–B5, C1–C2 |
| 13 Bedrock usage/cost/expiry | A2, C1–C2, E1–E2 |
| 14 Bedrock expiry notifications | E3 |
| 15 IAM key age/alerts | E4 |

## Decisions required before implementation

- Which billing accounts and projects are in scope, and who owns each project?
- Which cost basis and treatment of credits, taxes, support, and refunds should the report use?
- Which shared-cost allocation basis is acceptable for each shared resource type?
- Who may assign/tag resources and approve allocation rules, and who receives credential alerts?
- What reconciliation tolerance and unallocated-cost threshold allow a period to be finalized?
- The request mentions utilization monitoring but specifies no new metrics or thresholds. Define those separately if it should extend the existing monitoring features.
