# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
./gradlew build              # compile + test + JAR (the JAR serves the bundled frontend)
./gradlew clean build        # clean first

# Run (bootRun defaults to the dev profile, port 8080; serves the bundled frontend)
./gradlew bootRun
./gradlew bootRun -PskipBundle   # serve the individual JS/CSS source files (debugging)

# Production profile
$env:SPRING_PROFILES_ACTIVE="prod"; ./gradlew bootRun

# Tests
./gradlew unitTest                                                  # tests that need no database
./gradlew test                                                      # all tests (needs MySQL, see below)
./gradlew test --tests "com.tcgdigital.vmcontrol.service.EnvironmentServiceTest"  # single class
./gradlew test --tests "*.EnvironmentServiceTest.methodName"        # single method
node --test "src/test/js/**/*.test.mjs"                             # front-end helper tests
```

A `.env` file (based on `.env.example`) must exist in the project root before running the app — it supplies database credentials, AWS keys, and Azure Entra ID config.

## Architecture Overview

Spring Boot 3.5 REST API + vanilla-JS SPA served as static resources. The backend is a classic layered MVC:

```
HTTP → Controllers (/api/v1/**) → Services → Repositories (Spring Data JPA) → MySQL
                                         ↘ AWS SDK (EC2, EKS, CloudWatch, Cost Explorer, Compute Optimizer)
                                         ↘ Scheduled jobs (state sync, metrics, cost, access/lock expiry, …)
```

**Package root**: `com.tcgdigital.vmcontrol`

| Sub-package | Purpose |
|---|---|
| `controller` | 19 REST controllers + `GlobalExceptionHandler` |
| `service` | Business logic, orchestration, cloud integration, schedulers |
| `repository` | Spring Data JPA repositories |
| `model` | JPA entities and enums (~49 classes) |
| `dto` | Request/Response contracts (~70) — never expose entities directly |
| `config` | AWS clients, async executors, app configuration |
| `security` | Security chains (`EntraidSecurityConfig`, `DefaultSecurityConfig`, `SecurityModeGuard`), `CustomOAuth2UserService`, CSRF/CSP/header filters |
| `scheduler` | Scheduled job entry points (each takes a DB job lock) |
| `exception` | Domain exceptions mapped by `GlobalExceptionHandler` |

Controllers: AccessGrant, Audit, Auth, AutomationRule, CostManagement, Dashboard, Directory, Ec2, Environment, EnvironmentAccess, Home, IdleStop, Lock, Monitoring, Notification, User, VmGroup, VmMgmt, VmOperations.

## Key Domain Concepts

- **Environment**: top-level organizational unit; users are granted access per environment (or per group scope).
- **VmGroup**: logical collection of VMs within an environment.
- **Vm**: individual virtual machine; tracks state, dependencies, and cloud provider ID.
- **OperationExecution / OperationDetail**: records a multi-step start/stop operation with dependency-ordered steps.
- **EnvironmentLock**: exclusive mutex on an environment — only one active lock at a time (unique index), optional expiry.
- **AuditLog**: append-only record of every significant action, written through `AuditWriter` (async, after commit).

## Authentication & Authorization

Two mutually exclusive security configs, toggled by `entraid.enabled`:

| `entraid.enabled` | Config class | How it works |
|---|---|---|
| `true` (prod) | `EntraidSecurityConfig` | Microsoft Entra ID OAuth2 PKCE flow; `CustomOAuth2UserService` upserts user on first login. Username/password login stays available (`auth.password-login.enabled`) |
| `false` (dev) | `DefaultSecurityConfig` | Permits all — a dev user is chosen with the `X-User-Id` header. Only loads with the `dev`/`test` profile; `SecurityModeGuard` fails startup otherwise |

**Roles**: `ADMIN` > `ENV_ADMIN` > `USER` > `VIEWER`. Method-level security via `@PreAuthorize`. `SecurityService` (in `service`) provides environment-scoped access checks used throughout the service layer. With Entra on, mutating requests need the `X-XSRF-TOKEN` header.

## Database & Migrations

- **MySQL 8 only** (no H2), configured via `.env`.
- **Flyway** runs `migrate` on startup with `validate-on-migrate=true` and **no automatic repair**: an edited, already-applied migration stops startup. Migrations live in `src/main/resources/db/migration/` (V1–V41, plus the Java migration `V24__secure_legacy_passwords` in `src/main/java/db/migration`). Never edit an existing migration; add the next free version. Manual repair procedure: `docs/runbook-flyway.md`.
- Integration tests (`AbstractIntegrationTest`) use MySQL: a local schema from `TEST_DB_URL` in `.env` (its name must end in `_test`; the tests wipe it) or, when unset, a Testcontainers MySQL (Docker required). `src/test/resources/db/reset-test-data.sql` reseeds before each test. Tests run with `ddl-auto=validate`, so entity/schema drift fails the build.
- `SecuredWebTestBase` runs the real Entra security chain so `@PreAuthorize` rules can be tested per role.

## Multi-Cloud Pattern

`CloudProviderFactory` resolves the `CloudProviderService` implementation by the `CloudProvider` enum. Implemented: `AwsCloudProviderService` (`AWS`, EC2) and `EksCloudProviderService` (`AWS_EKS`, EKS node groups). `AZURE`, `GCP` and `OCI` exist in the enum but have no implementation — the factory rejects them. To add a provider, implement `CloudProviderService` (its `getProvider()` registers it).

## VM Operation Orchestration

`VmOperationsService` builds an ordered list of steps from VM dependencies using `DependencyValidator` (topological sort). Each step is persisted as an `OperationDetail` under an `OperationExecution`. `StateSyncService` (scheduled every 5 min by default) reconciles actual cloud state back into the DB and flags drift. Scheduled jobs take a DB job lock (`ScheduledJobLockService`), and System Health / the dashboard report each job against its own schedule (`SchedulerJobCatalog`).

## Feature switches

Set in `application.properties` (each reads an env var of the same name in upper snake case, e.g. `RETENTION_ENABLED`).

| Property | Default | Effect |
|---|---|---|
| `app.scheduling.enabled` | true | All scheduled jobs |
| `auth.password-login.enabled` | true | Username/password login next to Entra (emergency off switch) |
| `security.env-admin.scope` | global | `assigned` limits ENV_ADMIN to environments they hold admin grants on |
| `security.csp.report-only` | true | CSP is reported, not enforced |
| `vm.state.sync.enabled` / `vm.inventory.sync.enabled` | true | Cloud state and inventory sync |
| `vm.discovery.enabled` | false | Auto-discover EC2 instances |
| `vm.metrics.archive.enabled` | true | Daily archive of raw metric samples |
| `eks.sync.status-refresh.enabled` / `eks.sync.auto-register.enabled` | true / false | EKS node group sync |
| `cost.snapshot.enabled`, `cost.actuals.enabled`, `cost.optimizer.enabled`, `cost.reservations.enabled` | true | Cost features (need the ce:* IAM permissions, see `docs/aws-iam-permissions.md`) |
| `cost.tagging.enabled` | false | Cost-allocation tag reconciliation |
| `locks.expiry.enabled` | false | Expire locks past `expires_at` |
| `automation.rules.enabled` | true | Calendar / access-grant automation rules |
| `automation.idle-stop.enabled` | false | Idle auto-stop evaluation |
| `notification.email.enabled` (+ per-type `notification.email.*.enabled`) | false | Email notifications (needs SMTP) |
| `notification.weekly-reports.enabled` | true | Weekly in-app reports |
| `retention.enabled` | false | Data retention purge (`retention.*`) |
| `graph.directory.enabled` | false | Microsoft Graph user directory lookup |

## Frontend

Vanilla JS module/IIFE singletons — no build framework. Source files are in `src/main/resources/static/js/` and `static/css/`, listed in order in `static/index.html`. Gradle's `buildFrontend` concatenates them (order taken from index.html) into `build/generated/frontend/static/dist/`, and `processResources` packages `dist/app.js`, `dist/app.css` and a patched `index.html` that loads only the bundles — so `bootRun`, `bootJar` and tests all serve the bundle. New scripts only need adding to index.html. Bump the `?v=` asset stamp in index.html on UI changes (the bundle reuses it). `build-frontend.ps1` is just a wrapper around those Gradle tasks. API base URL and feature flags live in `static/js/config.js`. Playwright e2e specs live in `test/`.

## Internal Docs

- `docs/` — runbooks and setup guides (`runbook-flyway.md`, `cost-management-setup.md`, `aws-iam-permissions.md`).
- The `.ai/` directory (local, currently gitignored) holds design documents:
  - `.ai/brain/architecture.md` — data flows and auth flows
  - `.ai/brain/domain-model.md` — entity relationships
  - `.ai/constraints/tech-decisions.md` — why each technology was chosen
  - `.ai/engineering/` — testing, database, security, and pattern guides

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
