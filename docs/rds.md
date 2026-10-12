# RDS databases as VMs

RDS DB instances and Aurora clusters can be registered in an environment next to its EC2
instances (E33). They are started and stopped by the same operations, schedules, leases and idle
auto-stop. They follow the dependency order with the other VMs, so app servers stop before their
database and start after it. They are also included in cost, cost-allocation tagging and idle
detection.

## How it works

- **Provider `AWS_RDS`**: the provider VM id is `db:<DBInstanceIdentifier>` or
  `cluster:<DBClusterIdentifier>`.
  - Start and stop call StartDBInstance / StopDBInstance or StartDBCluster / StopDBCluster, then
    poll until the database is `available` or `stopped`. The poll interval is
    `rds.poll-interval-ms` (20 s) and the time-out is `rds.operation-timeout-ms` (30 min).
  - Some databases cannot be stopped, and RDS refuses to stop them:
    - read replicas;
    - instances that belong to an Aurora cluster (register the cluster instead);
    - SQL Server in Multi-AZ;
    - Aurora Serverless v1.

    Each refusal reports the reason.
- **Registering**: in the VM registry, *Register VM* has an **EC2 / RDS** toggle (shown while RDS is
  on).
  - The RDS list shows DB clusters and the DB instances outside a cluster. For each: engine,
    class, status, whether RDS can stop it and why not, and whether it is already registered.
  - Selecting a database fills the form with provider `AWS_RDS` and its id.
- **Inventory and metrics**: the inventory sync stores:
  - the DB class (for a cluster, its writer's class);
  - vCPU and memory, taken from the EC2 type the class is built on;
  - allocated storage, engine and Multi-AZ.

  The metrics sync reads `AWS/RDS`: CPU, `DatabaseConnections` (Maximum), and the network and
  storage throughputs, stored as bytes per period.
- **Seven-day automatic start**: AWS starts a stopped database again after seven days.
  - A database the platform stopped is marked as *kept stopped*. A platform start clears the mark.
  - Every 15 minutes, a database found starting or running near the end of those seven days is
    recorded. The event is audited (`RDS_AUTO_START_DETECTED`) and the environment's admins are
    told.
  - With `rds.keep-stopped.enabled`, the database is stopped again once it is available. This is
    audited as `RDS_RESTOPPED` and restarts the seven days. It does not happen while a platform
    operation runs in the environment, or when a schedule starts the environment within the hour.
  - A database started outside the platform before the seven days are up is left running and is
    no longer kept stopped.
  - The environment page shows under a stopped database when AWS will restart it.
- **Cost**: a database is priced by engine, class and Single- or Multi-AZ for the hours it ran.
  - Its allocated storage is billed for the whole window, because RDS bills storage while a
    database is stopped.
  - With `cost.tagging.enabled`, databases get the environment, team and cost-center tags, so Cost
    Explorer actuals include them.
  - The RDS rates in `pricing/instance-pricing.json` are us-east-1 list prices entered by hand.
    Other regions use them and are flagged approximate until the pricing script runs with `-Rds`
    (parking-lot item PL-14).
- **Idle**: a database is idle when every sample of the window has at most
  `rds.idle.max-connections` (0) connections and CPU below `rds.idle.cpu-threshold-percent` (5).
  - Network is ignored. A sample without connection data is not idle.
  - Idle auto-stop and the idle-waste list use this rule.

## Switches

| Property | Env var | Default | Effect |
|---|---|---|---|
| `rds.enabled` | `RDS_ENABLED` | false | Operate, browse and sync (inventory, metrics, tags) RDS databases. Off means AWS_RDS VMs cannot be started or stopped and nothing reaches RDS |
| `rds.keep-stopped.enabled` | `RDS_KEEP_STOPPED_ENABLED` | false | Stop again databases AWS started after seven days. Off means such starts are only recorded and admins told |
| `rds.keep-stopped.cron` | `RDS_KEEP_STOPPED_CRON` | every 15 min | Keep-stopped pass |
| `rds.idle.max-connections` | `RDS_IDLE_MAX_CONNECTIONS` | 0 | Connections an idle database may have |
| `rds.idle.cpu-threshold-percent` | `RDS_IDLE_CPU_THRESHOLD_PERCENT` | 5 | CPU below which a database without connections is idle |

`rds.enabled`, `rds.keep-stopped.enabled` and the idle thresholds can also be changed in
**Settings → RDS**.

## AWS permissions

The app's role needs:

- `rds:DescribeDBInstances`, `rds:DescribeDBClusters`;
- `rds:StartDBInstance`, `rds:StopDBInstance`, `rds:StartDBCluster`, `rds:StopDBCluster`;
- with cost tagging, `rds:AddTagsToResource`;
- `cloudwatch:GetMetricData` for the metrics.

See `docs/aws-iam-permissions.md`.
