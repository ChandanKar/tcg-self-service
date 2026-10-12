# Spot for non-production and EKS

Spot capacity costs much less than on demand, but AWS can take it back at short notice. This
platform lets the owners of interruptible environments opt in, prices Spot correctly, converts EKS
node groups to Spot with an on-demand standby, falls back to that standby when Spot capacity runs
out, and records every interruption. Everything ships off.

## How it works

1. **Purchase option.** Inventory sync reads each EC2 instance's lifecycle, EKS sync each node
   group's capacity type. Spot VMs show a *Spot* badge.
2. **Spot prices.** A daily job stores the newest Linux Spot price per availability zone for the
   instance types in use. Spot VMs and node groups are priced at it (their zone, else the region
   average, else on demand), and Cost Management shows what Spot saved against on demand.
3. **Opt-in.** An environment's administrators allow Spot on its page (they confirm the
   environment can be interrupted; production environments cannot), and may exclude single VMs.
4. **Opportunities.** Cost Management lists on-demand node groups and VMs of environments that
   allow Spot, with the saving over the last 30 days and AWS's placement score (1–10).
5. **Conversion (EKS only).** A node group's capacity type is fixed when it is created, so
   *Convert to Spot* creates a twin `<name>-spot` with the same subnets, role, labels, taints,
   scaling and launch template, and at least three same-size instance types. Once the twin runs,
   the original is scaled to 0 and kept as its **standby**: starting the environment skips it,
   stopping still includes it. If the twin never becomes ready it is deleted and the original keeps
   running. *Back to on demand* starts the standby first, then stops and deletes the twin.
   EC2 instances cannot change purchase option in place; they are listed for information only.
6. **Fallback.** Every 5 minutes each running twin is checked. Short of nodes, or with capacity or
   launch issues, for longer than the grace period, its standby is started and the environment's
   admins are told. After two healthy checks at full size the standby goes back to 0.
7. **Interruptions.** When state sync finds a running Spot VM stopped or terminated by AWS
   (`Server.SpotInstanceShutdown` / `Server.SpotInstanceTermination`), it records a
   `spot_interruption` state change, an audit entry and a notification to the environment's admins
   and the lock holder. A fallback is recorded the same way. A Spot start refused for capacity
   says so.

## Switches

All are in Settings (Spot group) and in `application.properties`.

| Property | Default | Effect |
|---|---|---|
| `spot.enabled` | false | Environments may opt in; the environment page shows the Spot panel |
| `spot.pricing.enabled` | false | Daily Spot price capture (`spot.pricing.cron`, 04:20) and Spot-rate pricing |
| `spot.eks.conversion.enabled` | false | *Convert to Spot* and *Back to on demand* |
| `spot.eks.min-instance-types` | 3 | Fewest same-size instance types a twin may use |
| `spot.fallback.enabled` | false | On-demand fallback check (`spot.fallback.cron`, every 5 minutes) |
| `spot.fallback.grace-minutes` | 10 | How long a twin may be short of capacity before the standby starts |

## AWS permissions

Read-only: `ec2:DescribeSpotPriceHistory`, `ec2:GetSpotPlacementScores`, `ec2:DescribeInstances`
(state reasons, node counts). Conversion: `eks:CreateNodegroup`, `eks:DeleteNodegroup`,
`eks:TagResource` and `iam:PassRole` on the node role. See `docs/aws-iam-permissions.md`.

## Rollout

1. Turn on `spot.pricing.enabled` and `spot.enabled`; check prices are captured (System Health
   lists the job) and opportunities appear for an environment that allows Spot.
2. Pick one non-production EKS environment. Turn on `spot.eks.conversion.enabled` and convert one
   node group; check its workload runs on the twin and the original is at 0.
3. Turn on `spot.fallback.enabled`. Watch interruptions and fallbacks on the environment page for a
   week before converting more.
