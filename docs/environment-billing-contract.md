# Environment billing contract (A1 draft)

**Status:** Draft for finance/product approval. This document defines the intended meaning of an environment bill; it does not change current application behavior. The implementation tasks are in [the tracker](environment-billing-and-key-monitoring-tasks.md).

## Purpose and boundary

The new bill reports actual AWS charges for every in-scope account and usage period. Every included charge is represented exactly once as directly attributed to an environment, allocated from a shared charge, or unallocated. The report retains source account, service, usage type, currency, cost basis, and attribution evidence. It never substitutes an estimate for a missing actual charge. Existing estimated-cost views may continue separately with an explicit `Estimated` label.

For each account, currency, cost basis, and period:

```text
included AWS billed total = direct environment cost + allocated shared cost + unallocated cost
```

Negative adjustments must retain their sign. Allocation and service/category views are alternate groupings of the same charges, not additional totals. Resource-level detail appears only when the billing source supplies a resource ID or another verified ownership link.

## Current implementation evidence

| Topic | Current behavior | Contract implication |
| --- | --- | --- |
| Billing source | `CostExplorerBillingService` calls `GetCostAndUsage` with daily `UnblendedCost`, grouped by `tcg:environment` and `LINKED_ACCOUNT` via the designated billing account. | Reuse `UnblendedCost` as the initial proposed basis. The current query omits blank tag groups, so its totals cannot establish a complete account bill. |
| Storage | `CostDailySnapshot.actualCost` holds one amount per environment/day when an estimate snapshot already exists; missing rows are skipped. | Detailed billing must have independent source-line storage and must preserve untagged charges. A missing value is not zero. |
| Day boundary | `CostDayBoundary` defaults to UTC through `cost.day-boundary-zone`. Cost Explorer uses daily date intervals with an exclusive end date. | Define billing usage days and month boundaries in UTC; do not use server local time for a cost day. |
| Account scope | `AwsAccount` marks one billing account; `CostExplorerClientProvider` uses it to query linked-account cost. | Specify which linked accounts are included and record account ID on every charge. An account mismatch must be reported, not silently reassigned. |
| Currency | `FocusExportService` writes `USD` for billing and pricing currency. | Proposed report currency is USD. Verify source currency rather than assume conversion or silently combine currencies. |
| Chargeback | `ChargebackService` uses actual cost where present and estimated cost otherwise, then may split an environment's month among teams. It can finalize a completed calendar month. | Existing chargeback is a mixed actual/estimated product. New actual environment billing and resource-to-environment sharing must be distinct; changing chargeback semantics is a later task. |

## Proposed decisions for approval

| Decision | Proposal | Approval question / consequence |
| --- | --- | --- |
| Billing scope | Query the designated payer/delegated billing account and include explicitly registered linked accounts. Show excluded or inaccessible linked accounts in coverage. | Confirm the account list and whether the payer account's own charges belong in scope. |
| Cost basis | Use AWS `UnblendedCost` for the first actual bill, matching the existing importer. Name the basis in every API/export. | Confirm whether finance instead needs amortized/net amortized costs. A different basis requires a separate reconciliation and cannot be mixed in one total. |
| Currency | Preserve AWS source currency; display and reconcile USD only when source lines are USD. | Confirm whether any non-USD billing accounts exist and whether conversion is required. No implicit FX conversion. |
| Usage period | Calendar day and month in UTC; interval start inclusive, end exclusive. Current month remains provisional. | Confirm whether business reporting additionally needs an India-time display; that must not shift AWS billing dates. |
| Project | Assign an immutable internal project ID to an environment; emit `tcg:project` as its billing tag. Record historical changes. | Confirm project registry, owners, and whether one environment may span projects over time. A free-form project name alone is insufficient for stable history. |
| Direct ownership | Prefer active AWS environment cost tag on the source charge. Use resource/account ownership history valid on the usage day only when the billing tag is absent and evidence is unambiguous. | Confirm whether a conflicting AWS tag and portal assignment should always go to review. Proposed answer: yes. |
| Shared ownership | Apply an approved resource-to-environment allocation rule, preferring measured consumption where available. Keep this separate from the current environment-to-team split. | Approve methods and owners for NAT, EKS, and other shared components. No default equal split without approval. |
| Unallocated | Keep untagged, unsupported, unmatched, and conflicting charges in a visible unallocated bucket. | Set the maximum acceptable unallocated amount/percentage for a period to be called complete. |
| Credits, refunds, discounts, taxes, and support | Preserve every source line and type. Proposed default: include AWS usage and usage-related discounts/credits in the reconciled account view; show tax, support, and unrelated account-level adjustments separately until an allocation policy is approved. | Finance must decide which categories belong in the environment bill and how account-wide amounts are distributed. Any excluded amount must still reconcile to a clearly named full-account total. |
| Late corrections | Re-read provisional periods; record source revisions. Do not silently rewrite a finalized statement. | Agree the finalization delay and approval process for post-finalization AWS adjustments. |
| Precision | Store source monetary precision, sum before display rounding, and round to two decimals for USD display/export. | Agree reconciliation tolerance (proposed: at most USD 0.01 per account and day after rounding) and residual-cent allocation rule. |

These are proposals, not approved policy. In particular, AWS `UnblendedCost` may already reflect some discounts and adjustments; the exact included line types must be checked in representative source data before finance signs off.

## Worked examples (illustrative USD, not actual AWS data)

### Direct, shared, and unallocated

| Source charge | Source amount | Attribution | Environment A | Environment B | Unallocated |
| --- | ---: | --- | ---: | ---: | ---: |
| Tagged EC2 usage | 100.00 | Direct tag A | 100.00 | 0.00 | 0.00 |
| EBS storage owned by A on usage day | 20.00 | Verified resource mapping | 20.00 | 0.00 | 0.00 |
| Shared NAT processing | 60.00 | Approved measured split: A 50%, B 50% | 30.00 | 30.00 | 0.00 |
| Untaggable/unmatched transfer | 10.00 | No reliable owner | 0.00 | 0.00 | 10.00 |
| **Total** | **190.00** | | **150.00** | **30.00** | **10.00** |

The account reconciles: 150.00 + 30.00 + 10.00 = 190.00. The unallocated amount is part of the complete accounting view, but A and B cannot claim it as their environment cost.

### Late correction and duplicate prevention

Suppose AWS later changes A's EC2 line from 100.00 to 95.00 for the same source identity and usage day. An idempotent refresh replaces that line, yielding account total 185.00 and A total 145.00. It does not append a second 95.00 charge. If the month was finalized, record a pending adjustment and require the agreed correction process.

If AWS supplies a 60.00 NAT source charge and split-cost evidence of 30.00 for each environment, the account total remains 60.00 for that charge. The split rows explain allocation; adding 60.00 + 30.00 + 30.00 would double count.

## A1 acceptance checklist

- [ ] Billing accounts, project registry/ownership, basis, currency, and UTC periods approved.
- [ ] Inclusion and treatment of every source line type, especially credits/refunds/tax/support, approved.
- [ ] Shared-allocation ownership, unallocated threshold, rounding tolerance, and finalization/correction process approved.
- [ ] Two reviewers independently reproduce the worked totals and explain how missing/conflicting evidence is handled.
- [ ] Tracker A1 marked complete only after the above decisions and reviewer/date are recorded here.

**Approval record:** Pending. Finance reviewer: —. Product reviewer: —. Approved date: —.
