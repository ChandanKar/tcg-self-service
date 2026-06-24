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

# Run all tests
.\gradlew test

# Run one test class
.\gradlew test --tests "com.tcgdigital.vmcontrol.service.EnvironmentServiceTest"

# Run one test method
.\gradlew test --tests "*.EnvironmentServiceTest.methodName"

# Bundle frontend assets after CSS/JS changes
.\build-frontend.ps1
```

A root `.env` file based on `.env.example` is required before running the app. It supplies database credentials, AWS keys, and Azure Entra ID configuration.

## Architecture

Backend flow:

```text
HTTP -> Controllers (/api/v1/**) -> Services -> Repositories (Spring Data JPA) -> DB
                                           -> AWS SDK / cloud provider operations
                                           -> scheduled jobs for state sync and access expiry
```

Package root: `com.tcgdigital.vmcontrol`

Important packages:

| Package | Purpose |
| --- | --- |
| `controller` | REST controllers and `GlobalExceptionHandler` |
| `service` | Business logic, orchestration, cloud integration |
| `repository` | Spring Data JPA repositories |
| `model` | JPA entities |
| `dto` | Request/response API contracts; do not expose entities directly |
| `config` | Spring Security, AWS, OAuth2, async configuration |
| `security` | OAuth2 user loading and environment-scoped access checks |

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
| `false` | `DefaultSecurityConfig` | Dev mode; permits all HTTP access while dev username/password validation is handled by `AuthenticationService` |

Roles are ordered as `ADMIN` > `ENV_ADMIN` > `USER` > `VIEWER`.

Method-level security uses `@PreAuthorize`. Use `SecurityService` for environment-scoped access checks in the service layer.

## Database

- Development uses file-based H2 at `./data/vmcontrol.mv.db`.
- The H2 console is available at `/h2-console`.
- Production uses MySQL configured through `.env`.
- Flyway runs automatically on startup.
- Migrations live in `src/main/resources/db/migration/`.
- Do not edit existing migrations. Add a new versioned migration instead.
- Tests use isolated in-memory H2 and seed data from `src/test/resources/db/reset-test-data.sql`.

## Cloud Provider Pattern

`CloudProviderFactory` resolves implementations of `CloudProviderService` by `VmCloudProvider` enum values: `AWS`, `GCP`, `AZURE`, and `OCI`.

Only `AwsCloudProviderService` is fully implemented currently. Other providers are stubs. When adding a provider, implement `CloudProviderService` and register the implementation in the factory.

## VM Operation Orchestration

`VmOperationsService` builds ordered start/stop steps from VM dependencies using `DependencyValidator` topological sorting.

Each step is persisted as an `OperationDetail` under an `OperationExecution`.

`StateSyncService` runs on a schedule, reconciles cloud VM state back into the database, and flags drift.

## Frontend

The frontend is a vanilla-JS SPA served by Spring Boot from `src/main/resources/static/`.

Relevant paths:

- Source JavaScript: `src/main/resources/static/js/`
- Source CSS: `src/main/resources/static/css/`
- Bundled assets: `src/main/resources/static/dist/`
- Main HTML: `src/main/resources/static/index.html`
- API base URL and feature flags: `src/main/resources/static/js/config.js`

Edit source JS/CSS files, not `dist/` outputs. After frontend source changes, run `.\build-frontend.ps1` or the Gradle frontend bundling task so `dist/app.js` and `dist/app.css` are refreshed.

## Internal Docs

The `.ai/` directory contains more detailed design and engineering guidance:

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
