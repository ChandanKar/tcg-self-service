# Environment billing contract (A1)

**Status:** Implementation contract, completed 2026-11-26 under the project owner's instruction to complete A1. This defines the intended meaning of an environment bill; it does not change current application behavior. Finance validation against a real billing period remains the F3 release gate. The implementation tasks are in [the tracker](environment-billing-and-key-monitoring-tasks.md).

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

## Binding implementation decisions

These choices resolve A1 for development. A later policy change requires a versioned contract amendment and a comparison of affected totals; it must not silently change a finalized period.

| Decision | Rule | Validation consequence |
| --- | --- | --- |
| Billing scope | The designated payer/delegated billing account supplies charges for itself and every linked account registered as active for the usage day. Record the effective account set for each period. | Missing or inaccessible accounts make coverage incomplete; do not report their cost as zero. Inactive accounts retain prior periods. |
| Cost basis | Use AWS `UnblendedCost` for this bill, matching the existing importer. Identify the basis in every API/export. | Amortized or net cost may be separate future views; never mix bases in a total. |
| Currency | Preserve the source currency; initially publish USD totals only. | Reject cross-currency summation. A non-USD source remains visible as unsupported until an explicit FX policy is introduced. |
| Usage period | Calendar days and months in UTC; start inclusive, end exclusive. Current month is provisional. | Server or viewer timezone does not shift the AWS usage day. |
| Project | One environment has one project assignment at any instant. Use an immutable internal project ID and a controlled `tcg:project` tag value; retain effective-dated history. Environments without a project are `Project unassigned`. | No free-text or name-based historical ownership inference. A project reassignment does not rewrite prior usage. Project registry and owner data are created in B1/B3. |
| Direct ownership | Use the activated environment billing tag on the charge. If absent, use verified account/resource ownership valid on the usage day. | Conflicting tag and resource evidence goes to review as unallocated; do not choose the convenient result. |
| Shared ownership | Prefer AWS measured split evidence. Otherwise use an explicitly approved, effective-dated percentage rule. No automatic equal split. Keep resource-to-environment rules separate from existing environment-to-team splits. | Shares must sum to 100%; without a valid rule the charge stays unallocated. |
| Unallocated | Keep unmatched, untaggable, unsupported, and conflicting charges in a visible account-level bucket. | A report is `reconciled` when all source charges are accounted for; it is `fully allocated` only when unallocated is zero. Display amount and percentage in both states. No hidden threshold turns unallocated into environment cost. |
| Credits, refunds, discounts, taxes, and support | Include all source line types returned on the chosen basis in the account reconciliation. Directly tagged or otherwise evidenced line items follow the same attribution rule, including negative amounts. Account-wide tax, support, credit, refund, and adjustment lines without reliable environment evidence remain unallocated and are broken out by line type. | No blanket pro-rata distribution or exclusion. Source line types and signed amounts remain visible, so environment totals and the full account total reconcile. |
| Late corrections and finalization | Re-read provisional periods. Finalize only a completed month after a successful source refresh, zero unexplained reconciliation variance, and explicit finance approval of that period. A later AWS change becomes a separately audited adjustment pending re-approval. | No scheduled automatic finalization and no silent rewriting of finalized statements. This applies to the new actual bill; changing existing chargeback finalization is later work. |
| Precision | Retain source decimal precision; sum before USD display rounding to two decimals. Allocate any residual cents deterministically to the largest fractional remainder, then stable environment ID. | Exact source-precision equality is required internally. Displayed account/day totals may differ by no more than USD 0.01 before residual assignment and must match exactly afterward. |

`UnblendedCost` can already reflect discounts and adjustments. Ingestion must classify the source record types actually present rather than invent a second deduction. A complete source-line mapping is validated in A2/C1 and against live data in F3.

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

## A1 acceptance record

- [x] Billing account scope, project assignment rule, cost basis, currency, and UTC periods specified.
- [x] Treatment of usage, credits, refunds, discounts, taxes, support, and adjustments specified.
- [x] Shared allocation, unallocated status, rounding, and finalization/correction rules specified.
- [x] Worked examples reconcile and demonstrate correction and duplicate prevention.
- [x] Tracker A1 closed; live finance validation is retained as the F3 release gate.

**Decision record:** Project owner requested A1 completion on 2026-11-26. No named finance reviewer or pilot-period approval is represented by this record.
