# AGENTS.md

This file gives Codex project context for this repository. Treat `CLAUDE.md` and the `.ai/` directory as companion references, with `.ai/` holding the deeper design notes.

## Project Summary

This repository is `tcg-self-service-backup`, a Spring Boot 3.5 / Java 17 application for self-service VM control. It exposes REST APIs and serves a vanilla-JS single-page app from static resources.

The main domain is environment-scoped VM operations: users can access environments, manage VM groups and VMs, start/stop VMs with dependency ordering, track operation execution, and audit significant actions.

## Build And Run Commands

```powershell
# Build, compile, test, and create JAR
.\gradlew build

# Clean build
.\gradlew clean build

# Run locally; dev profile is active by default, port 8080
.\gradlew bootRun

# Run with production profile
$env:SPRING_PROFILES_ACTIVE="prod"; .\gradlew bootRun

# Tests that need no database
.\gradlew unitTest

# Run all tests (integration tests need MySQL: TEST_DB_URL in .env or Docker for Testcontainers)
.\gradlew test

# Run one test class
.\gradlew test --tests "com.tcgdigital.vmcontrol.service.EnvironmentServiceTest"

# Run one test method
.\gradlew test --tests "*.EnvironmentServiceTest.methodName"

# Front-end helper tests
node --test "src/test/js/**/*.test.mjs"

# Serve individual JS/CSS files instead of the bundle (debugging)
.\gradlew bootRun -PskipBundle
```

A root `.env` file based on `.env.example` is required before running the app. It supplies database credentials, AWS keys, and Azure Entra ID configuration.

## Architecture

Backend flow:

```text
HTTP -> Controllers (/api/v1/**) -> Services -> Repositories (Spring Data JPA) -> MySQL
                                           -> AWS SDK / cloud provider operations
                                           -> scheduled jobs (state sync, metrics, cost, access/lock expiry, ...)
```

Package root: `com.tcgdigital.vmcontrol`

Important packages:

| Package | Purpose |
| --- | --- |
| `controller` | 19 REST controllers and `GlobalExceptionHandler` |
| `service` | Business logic, orchestration, cloud integration |
| `repository` | Spring Data JPA repositories |
| `model` | JPA entities |
| `dto` | Request/response API contracts; do not expose entities directly |
| `config` | AWS clients, async executors, app configuration |
| `security` | Security chains, `CustomOAuth2UserService`, CSRF/CSP/header filters |
| `scheduler` | Scheduled job entry points (each takes a DB job lock) |

## Key Domain Concepts

- `Environment`: top-level organizational unit; user access is granted per environment.
- `VmGroup`: logical collection of VMs inside an environment.
- `Vm`: individual virtual machine with state, dependencies, and cloud provider identity.
- `OperationExecution` / `OperationDetail`: persisted record of multi-step start/stop workflows.
- `EnvironmentLock`: exclusive environment mutex; only one active lock should exist at a time.
- `AuditLog`: append-only record of significant user/system actions.

## Security

Security is selected by `entraid.enabled`:

| `entraid.enabled` | Config | Behavior |
| --- | --- | --- |
| `true` | `EntraidSecurityConfig` | Microsoft Entra ID OAuth2 PKCE; `CustomOAuth2UserService` upserts users on first login |
| `false` | `DefaultSecurityConfig` | Dev mode (only with the `dev`/`test` profile, enforced by `SecurityModeGuard`); permits all, the dev user is chosen with the `X-User-Id` header |

Roles are ordered as `ADMIN` > `ENV_ADMIN` > `USER` > `VIEWER`.

Method-level security uses `@PreAuthorize`. Use `SecurityService` (in `service`) for environment-scoped access checks in the service layer.

## Database

- MySQL 8 only (no H2), configured through `.env`.
- Flyway runs `migrate` on startup with validation and no automatic repair; an edited applied migration stops startup (see `docs/runbook-flyway.md`).
- Migrations live in `src/main/resources/db/migration/` (V1-V41, plus the Java `V24__secure_legacy_passwords`).
- Do not edit existing migrations. Add a new versioned migration instead.
- Integration tests (`AbstractIntegrationTest`) use a local MySQL schema from `TEST_DB_URL` (name must end in `_test`; it is wiped) or a Testcontainers MySQL (Docker). `reset-test-data.sql` reseeds before each test; tests run with `ddl-auto=validate`.

## Cloud Provider Pattern

`CloudProviderFactory` resolves implementations of `CloudProviderService` by the `CloudProvider` enum. Implemented: `AwsCloudProviderService` (`AWS`, EC2) and `EksCloudProviderService` (`AWS_EKS`, EKS node groups). `AZURE`, `GCP` and `OCI` have no implementation and are rejected by the factory. A new provider implements `CloudProviderService`; its `getProvider()` registers it.

## VM Operation Orchestration

`VmOperationsService` builds ordered start/stop steps from VM dependencies using `DependencyValidator` topological sorting.

Each step is persisted as an `OperationDetail` under an `OperationExecution`.

`StateSyncService` runs on a schedule, reconciles cloud VM state back into the database, and flags drift.

## Frontend

The frontend is a vanilla-JS SPA served by Spring Boot from `src/main/resources/static/`.

Relevant paths:

- Source JavaScript: `src/main/resources/static/js/`
- Source CSS: `src/main/resources/static/css/`
- Bundled assets: built by Gradle into `build/generated/frontend/static/dist/` and packaged by `processResources`
- Main HTML: `src/main/resources/static/index.html`
- API base URL and feature flags: `src/main/resources/static/js/config.js`

Edit source JS/CSS files. The bundle order comes from `index.html`, and `bootRun`, `bootJar` and tests rebuild `dist/app.js` / `dist/app.css` automatically (`.\build-frontend.ps1` is only a wrapper). Bump the `?v=` asset stamp in `index.html` on UI changes. Feature switches are listed in `CLAUDE.md`.

## Internal Docs

The `.ai/` directory (local, currently gitignored) contains more detailed design and engineering guidance:

- `.ai/brain/architecture.md`: data flows and auth flows
- `.ai/brain/domain-model.md`: entity relationships
- `.ai/constraints/tech-decisions.md`: technology decisions
- `.ai/engineering/`: testing, database, security, and implementation patterns

Read the relevant `.ai/` docs before making broad architectural, security, database, or domain-model changes.

## Codex Working Notes

- Prefer existing Spring MVC, service, repository, DTO, and frontend singleton patterns.
- Keep changes scoped to the requested behavior.
- Use DTOs for API boundaries; do not return JPA entities directly.
- Preserve Flyway migration history; add new migration versions for schema changes.
- Run targeted tests for changed backend behavior when practical.
- For frontend edits, verify source files and bundled output expectations.
- Be careful with `.env`, secrets, and local database files; do not print or commit secrets.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
