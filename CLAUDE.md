# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

```bash
# Build
./gradlew build              # compile + test + create JAR
./gradlew clean build        # clean first

# Run (dev profile active by default, port 8080)
./gradlew bootRun

# Production profile
$env:SPRING_PROFILES_ACTIVE="prod"; ./gradlew bootRun

# Tests
./gradlew test                                                      # all tests
./gradlew test --tests "com.tcgdigital.vmcontrol.service.EnvironmentServiceTest"  # single class
./gradlew test --tests "*.EnvironmentServiceTest.methodName"        # single method

# Bundle frontend assets (must run after CSS/JS changes to update dist/)
.\build-frontend.ps1
```

A `.env` file (based on `.env.example`) must exist in the project root before running the app — it supplies database credentials, AWS keys, and Azure Entra ID config.

## Architecture Overview

Spring Boot 3.5 REST API + vanilla-JS SPA served as static resources. The backend is a classic layered MVC:

```
HTTP → Controllers (/api/v1/**) → Services → Repositories (Spring Data JPA) → DB
                                         ↘ AWS SDK (EC2 operations)
                                         ↘ Scheduled jobs (state sync, access expiry)
```

**Package root**: `com.tcgdigital.vmcontrol`

| Sub-package | Purpose |
|---|---|
| `controller` | 13 REST controllers + `GlobalExceptionHandler` |
| `service` | Business logic, orchestration, cloud integration |
| `repository` | Spring Data JPA repositories |
| `model` | JPA entities (22 total) |
| `dto` | Request/Response contracts (31 total) — never expose entities directly |
| `config` | Spring Security, AWS, OAuth2, async config |
| `security` | `CustomOAuth2UserService`, `SecurityService` (access checks) |

## Key Domain Concepts

- **Environment**: top-level organizational unit; users are granted access per environment.
- **VmGroup**: logical collection of VMs within an environment.
- **Vm**: individual virtual machine; tracks state, dependencies, and cloud provider ID.
- **OperationExecution / OperationDetail**: records a multi-step start/stop operation with dependency-ordered steps.
- **EnvironmentLock**: exclusive mutex on an environment — only one active lock at a time.
- **AuditLog**: append-only record of every significant action.

## Authentication & Authorization

Two mutually exclusive security configs, toggled by `entraid.enabled`:

| `entraid.enabled` | Config class | How it works |
|---|---|---|
| `true` (prod) | `EntraidSecurityConfig` | Microsoft Entra ID OAuth2 PKCE flow; `CustomOAuth2UserService` upserts user on first login |
| `false` (dev) | `DefaultSecurityConfig` | Permits all — dev username/password validated by `AuthenticationService` |

**Roles**: `ADMIN` > `ENV_ADMIN` > `USER` > `VIEWER`. Method-level security via `@PreAuthorize`. `SecurityService` provides environment-scoped access checks used throughout the service layer.

## Database & Migrations

- **Dev**: H2 file-based (`./data/vmcontrol.mv.db`); H2 console at `/h2-console`.
- **Prod**: MySQL configured via `.env`.
- **Flyway** runs automatically on startup. Migrations live in `src/main/resources/db/migration/` (V1–V5). Never edit existing migration files; add a new versioned file instead.
- Tests use an isolated in-memory H2 instance seeded by `src/test/resources/db/reset-test-data.sql`.

## Multi-Cloud Pattern

`CloudProviderFactory` resolves the correct `CloudProviderService` implementation by `VmCloudProvider` enum (`AWS`, `GCP`, `AZURE`, `OCI`). Only `AwsCloudProviderService` is fully implemented; others are stubs. When adding a new cloud provider, implement the `CloudProviderService` interface and register it in the factory.

## VM Operation Orchestration

`VmOperationsService` builds an ordered list of steps from VM dependencies using `DependencyValidator` (topological sort). Each step is persisted as an `OperationDetail` under an `OperationExecution`. `StateSyncService` (scheduled every 5 min by default) reconciles actual cloud state back into the DB and flags drift.

## Frontend

Vanilla JS module/IIFE singletons — no build framework. Source files are in `src/main/resources/static/js/` and `static/css/`. The `build-frontend.ps1` script concatenates them into `static/dist/app.js` and `static/dist/app.css`. Edit source files, never the `dist/` outputs directly. API base URL and feature flags live in `static/js/config.js`.

## Internal Docs

The `.ai/` directory contains authoritative design documents:
- `.ai/brain/architecture.md` — data flows and auth flows
- `.ai/brain/domain-model.md` — entity relationships
- `.ai/constraints/tech-decisions.md` — why each technology was chosen
- `.ai/engineering/` — testing, database, security, and pattern guides
