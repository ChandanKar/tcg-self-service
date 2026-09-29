# Production-readiness audit — 14 September 2026

**Verdict: NOT READY for production release.** The current working tree contains credential exposure, authorization gaps, unsafe operation coordination, and a schema conflict. These are release blockers even if all existing tests pass in a Docker-enabled environment.

This assessment concerns the working tree, including changes already present when the review began. Application source and existing migrations were not modified. Audit-only regression checks were written under `build/readiness-audit/`.

**Scope and limits.** The inventory contains 236 main Java files (31,220 lines) and 34 source JavaScript files (17,625 lines), in addition to SQL, HTML, CSS, configuration, and tests. I performed detailed reads and call-chain analysis of authentication, authorization, VM operation planning/execution, locking, access grants, selected cloud and scheduler paths, frontend notification rendering, migrations, deployment configuration, and build wiring. Other modules received targeted searches or test coverage inspection. This is not a claim that every line of every file has been reviewed. No live production database, cloud operation, browser penetration test, load test, recovery drill, or dependency vulnerability scan was performed. Secrets and local data files were excluded from general reads; the migration credential inspection emitted counts, not credential values.

**Verification evidence**

| Check | Result | Interpretation |
| --- | --- | --- |
| `gradlew.bat test --console=plain` | 413 tests: 197 passed, 216 failed, zero skipped | Spring integration contexts failed to initialize; the observed root cause was no valid Docker environment for Testcontainers MySQL. This is not evidence of 216 application bugs. |
| Six audit-only isolated checks | All six failed the required-behavior assertions | Reproduced cross-environment reads/cancellation, foreign-environment targets, incorrect STOP ordering, stale password-session admin privileges, and inactive-admin access. No database or cloud calls. |
| `gradlew.bat bootJar --dry-run --console=plain` | Only compileJava, processResources, classes, resolveMainClassName, bootJar | Neither frontend bundling nor HTML patching participates in the normal JAR task graph. |
| Credential seed count | 680 rows; 680 nonempty non-bcrypt password values | V3 explicitly copies these as plaintext passwords. Their current validity was not tested. |

The audit checks call application controllers/services directly with mocked repositories and collaborators. They confirm the missing checks in those methods; they are not full HTTP exploit tests and do not exercise Spring filters or actual database isolation. Required assertions intentionally fail on this codebase. [Audit test source](D:/workspace/mcube/code/core/tcg-self-service-backup/build/readiness-audit/tests/ProductionReadinessRegressionTest.java), [audit test results](D:/workspace/mcube/code/core/tcg-self-service-backup/build/readiness-audit/test-report/index.html), [original test report](D:/workspace/mcube/code/core/tcg-self-service-backup/build/reports/tests/test/index.html), [preserved original test counts](D:/workspace/mcube/code/core/tcg-self-service-backup/build/readiness-audit/baseline-tests.json).

Reproduce the isolated checks with:

```powershell
.\gradlew.bat -I build/readiness-audit/audit.init.gradle test --tests ProductionReadinessRegressionTest --console=plain
```

The audit harness and reports are build artifacts; a clean build removes them.

**1. Critical — plaintext legacy credentials are embedded in a shipped migration.**

V3 contains 680 nonempty non-bcrypt password values and copies the password column directly into `app_user`. The migration itself identifies the values as plaintext. This source resource is distributed in the application JAR. `AuthenticationService` accepts a plaintext match and hashes it only after successful login. The password endpoint remains available with Entra enabled, so Entra does not eliminate this exposure. Sources: [V3 import](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/db/migration/V3__migrate_external_users.sql:23), [plaintext assignment](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/db/migration/V3__migrate_external_users.sql:736), [authentication comparison](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/AuthenticationService.java:59), [production login allowance](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java:32).

Impact: anyone with access to the affected source or artifact can obtain these legacy credentials; accounts still using them can be compromised. This review did not test whether they remain valid. Required action: invalidate/reset affected credentials, disable unneeded legacy password authentication, and coordinate removal from distributed artifacts and repository history. Preserve released migration integrity through an explicit migration/baseline plan; silently editing V3 is not an adequate remediation. Lazy hashing cannot retract already-exposed credentials.

**2. High — operation read/cancel authorization trusts the URL environment, not the execution's environment.**

The controller checks permission on environment A, then reads or cancels an arbitrary `executionId` without checking its owning environment. `cancelExecution` also has no target-environment authorization. A user authorized in A who obtains an execution ID from B can read its details or cancel its remaining work. Sources: [read endpoint](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/controller/VmOperationsController.java:134), [cancel endpoint](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/controller/VmOperationsController.java:188), [cancel service](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:379).

The isolated checks returned HTTP 200 for both mismatched-environment cases instead of rejecting them. Required action: fetch by execution ID plus environment ID, authorize against the actual resource, and define group-scoped cancellation rights. Add HTTP negative tests for cross-environment and cross-group access.

**3. High — VM/group targets can bypass the actual environment's lock and active-operation check.**

`startOperation` validates locks and active executions on the environment in the URL. `resolveTargetVms` then resolves supplied VM/group IDs globally, without requiring them to belong to that environment. Group permissions are checked, so this is not an unrestricted VM-permission bypass. However, a user with access to both A and B can submit B's VM under A to bypass B's lock or active-operation guard and misattribute execution/audit records to A. Sources: [preconditions](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:98), [global target resolution](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:812).

An isolated test accepted a foreign-environment target. Required action: reject mismatched and unknown IDs before creating any execution; deduplicate target IDs; enforce ownership in repository queries or explicit service validation.

**4. High — role revocation and account deactivation do not reliably revoke existing access.**

Password authentication stores a full User object and a fixed authority set in the session. `getCurrentUser` returns that snapshot. Role changes and deactivation update the database without invalidating sessions. For OIDC, database lookup refreshes the User object, but authorities in the OIDC principal still reflect login time, and the authorization helpers do not reject inactive users. An already-authenticated former admin can retain access to endpoints guarded only by `hasRole('ADMIN')`; a disabled user can retain effective permissions. Sources: [session token](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/security/UsernamePasswordAuthenticationToken.java:25), [current-user lookup](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/UserService.java:341), [deactivation](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/UserService.java:297), [effective access](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/SecurityService.java:185), [role-protected mutation](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/controller/UserController.java:219).

Two isolated checks reproduced stale password-admin access and ADMIN-level effective access for an inactive user. Required action: enforce active-account status on every request and automated execution, refresh/version authorization state, and invalidate affected sessions on deactivation or privilege reduction for both login types.

**5. High — STOP uses START dependency ordering; RESTART is not a coordinated graph restart.**

Planning always uses `orderForExecution` and the same prerequisite edges, independent of operation type. If the application depends on a database, STOP stops the database first, then the application. The isolated planner check confirmed this. RESTART stops and starts each dependency VM individually before touching dependents, which can interrupt a database while application VMs still run. Sources: [plan construction](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:123), [dependency edges](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/DependencyValidator.java:213), [per-VM restart](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:624).

Required action: reverse dependency edges for STOP, not just the list order. Implement restart as a reverse-order shutdown phase followed by a forward-order startup phase, or document and explicitly constrain a different supported restart contract. Test actual provider-call order and dependency failure behavior.

**6. High — environment locking and active-operation admission are not atomic.**

Lock acquisition performs an ordinary lookup followed by an insert. There is no unique active-lock constraint, and the pessimistic-lock repository method is not used there. Two transactions can both observe no lock and insert active locks. Operation admission similarly checks for active executions and then inserts without a database mutex or unique active-execution constraint. Even a single process can race under concurrent HTTP requests. Sources: [acquireLock](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/LockService.java:58), [non-unique lock index](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/db/migration/V1__initial_schema.sql:215), [operation admission](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:106).

Required action: serialize admission by locking the stable environment row within the transaction, and add appropriate database uniqueness/invariant enforcement. Locking only an active-lock row cannot reliably protect the case where no row exists. Verify two simultaneous lock requests, simultaneous START/STOP, and lock acquisition racing operation admission on real MySQL.

**7. High — cancellation releases the active-operation guard while cloud work can still run.**

Cancellation immediately sets the execution to CANCELLED. Active-operation queries count only pending/in-progress executions. The worker checks cancellation between waves, not within already-submitted VM tasks, so another operation can begin while the cancelled execution still starts/stops VMs. A cancellation before worker startup can also be overwritten by its unconditional IN_PROGRESS transition. Sources: [immediate cancellation](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:387), [worker startup](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:428), [wave submission/check](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:496), [active query](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/repository/OperationExecutionRepository.java:50).

Required action: retain exclusive ownership in a cancellation-requested state until in-flight work is reconciled; use atomic state transitions; ensure queued steps do not start after cancellation. Do not imply that cancelling a Java future reverses an AWS request already accepted.

**8. High — interrupted executions have no durable recovery path.**

Execution dispatch happens through an in-process after-commit callback. A crash after commit but before dispatch, or during a wave, can leave PENDING/IN_PROGRESS rows indefinitely. No startup/scheduled recovery consumer for active executions was found. VM state reconciliation does not complete or recover these execution records, which continue to block new operations. Sources: [dispatch](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:184), [active execution queries](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/repository/OperationExecutionRepository.java:38).

Required action: implement persisted work claiming, ownership/lease tracking, and restart reconciliation. Test process termination before dispatch, after an AWS request, and between dependent steps. Recovery must inspect cloud state before retrying effects.

**9. High — the access-grant unique index conflicts with group grants and repeated history.**

V1 creates a unique index on `(environment_id, user_id, status)`. V20 adds group scope and lookup indexes but does not remove or replace that unique index; no later migration removes it. Consequently, two ACTIVE group grants in the same environment for the same user collide. An environment grant plus a group grant also collide. A grant → revoke → grant → revoke sequence can collide on the second REVOKED row. Sources: [original constraint](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/db/migration/V1__initial_schema.sql:70), [scope migration](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/db/migration/V20__add_access_scope.sql:23), [multi-group grant loop](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java:331).

Required action: add a new migration replacing the incompatible constraint with uniqueness for active `(user, scope_type, scope_id)` grants while permitting historical rows, plus transactional handling of concurrent grants. Validate both fresh installation and upgrade on MySQL. This was established from DDL and service code; Docker unavailability prevented executing the MySQL reproduction here.

**10. High — audit and monitoring data are not scoped to the caller's grants.**

Environment audit logs require only authentication and pass any environment ID into the repository. VM history and global recent-state/drift endpoints likewise require only authentication, with no access filtering in `StateSyncService`. Thus even a signed-in user without the relevant environment grant can read its operational history; global feeds do not require knowing an ID first. Sources: [audit endpoint](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/controller/AuditController.java:137), [monitoring endpoints](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/controller/MonitoringController.java:206), [unscoped queries](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java:475).

Required action: authorize individual resources and scope repository queries to visible environments/groups; restrict truly global feeds to authorized global roles. Apply the same rule to counts, exports, and operation detail payloads.

**11. High — direct EC2 mutation endpoints bypass orchestration safeguards.**

`/api/ec2/instances/{instanceId}/start` and `/stop` accept arbitrary provider IDs for ADMIN/ENV_ADMIN and call `Ec2Service` directly. That service has no environment-lock check, dependency planner, persisted execution record, or application audit integration. A privileged user can mutate an instance while another user owns its environment lock or an orchestrated operation is running. These synchronous calls can also outlast Nginx's 90-second read timeout. Sources: [direct endpoints](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/controller/Ec2Controller.java:94), [direct cloud service](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/Ec2Service.java:43), [proxy timeout](D:/workspace/mcube/code/core/tcg-self-service-backup/deployment/nginx/conf.d/default.conf:55).

Required action: route mutations through the coordinated execution service. If emergency bypass is a product requirement, make it explicit, narrowly authorized, conflict-aware, and audited.

**12. High — session-authenticated API mutations bypass CSRF protection.**

Production security uses sessions but ignores CSRF for all `/api/**`. There is no equivalent origin/token check in the inspected application/proxy code. Several POST actions need no JSON body, including cancellation and direct EC2 actions. When the browser sends session cookies on an attacker-originated request, these operations lack CSRF enforcement. SameSite browser behavior can reduce some attack paths; it does not justify a blanket exclusion, especially for same-site sibling origins. Source: [CSRF exclusion](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java:73). [Spring's CSRF guidance](https://docs.spring.io/spring-security/reference/features/exploits/csrf.html) describes the relevant token and SameSite protections.

Required action: enable CSRF protection for browser session mutations and send tokens from the SPA; test missing/invalid/valid tokens. Separately, the custom password login saves a context without invoking session-authentication strategy or explicitly rotating an existing session ID: [login persistence](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/controller/AuthController.java:73). Integrate session-fixation and concurrent-session handling into that custom flow and verify them with HTTP tests. [Spring session-management reference](https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html).

**13. High — shared notification rendering treats untrusted text as HTML.**

`Notifications.show` and `showError` interpolate message text into HTML and append it. The API client forwards server error messages unchanged. Callers also interpolate stored environment names and onboarding display names. This provides an XSS sink despite escaping in many individual feature screens. Sources: [toast rendering](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/static/js/ui/notifications.js:41), [error rendering](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/static/js/ui/notifications.js:138), [API error propagation](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/static/js/core/api-client.js:113), [environment-name caller](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/static/js/features/environments.js:840), [display-name caller](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/resources/static/js/features/user-management.js:810).

Required action: render message/title as text nodes or centrally escape them. If rich content is needed, provide a separate tightly controlled API. Add browser tests for malicious stored names and error messages. This finding is based on source data flow; no browser exploit was executed.

**14. High — automatic Flyway repair hides migration drift.**

`FlywayConfig` unconditionally runs `repair()` before `migrate()` for every profile. Repair changes recorded checksums/descriptions/types to match available migrations, so accidental edits to an already-applied migration can be accepted while the actual schema remains unchanged. Production clean-disabled settings do not prevent repair. Source: [startup strategy](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/config/FlywayConfig.java:11). The behavior is documented in [Flyway Repair](https://documentation.red-gate.com/flyway/reference/commands/repair).

Required action: validate and migrate normally; reserve repair for an explicit investigated maintenance operation. Add an upgrade test proving altered historical checksums fail startup instead of being silently aligned.

**Additional issues and release evidence still needed**

| Priority | Finding | Action |
| --- | --- | --- |
| Medium | Frontend tasks are declared but disconnected from `processResources`/`bootJar`; the HTML patch output is not wired into resources. [build.gradle](D:/workspace/mcube/code/core/tcg-self-service-backup/build.gradle:141) | Wire the task dependencies and patched HTML into packaging, then inspect the actual JAR. This does not by itself prove the source-file-based UI fails. |
| Medium | `continueOnFailure=false` is accepted but explicitly ignored by the execution loop. [worker contract](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java:425) | Honor the flag or remove/change the public contract and UI with a clear supported behavior. |
| Medium | Dependencies outside the selected execution scope are assumed satisfied rather than checked against current state. [dependency map](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/DependencyValidator.java:213) | Check excluded prerequisites for START and live dependents for STOP; fail safely or expand scope only within authorized access. |
| Medium | Failed AWS tag discovery returns an empty successful-looking list; reconciliation flags every missing instance as drift. [provider catch](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java:652), [consumer](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java:255) | Distinguish failed/partial inventory from a successful empty result; skip reconciliation on failure. |
| Medium | Calendar rules fire only when `HH:mm` exactly equals the current tick; missed minutes have no catch-up window. [schedule evaluation](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java:220) | Define misfire/retry policy and persist scheduled instants/idempotency keys. |
| Medium | Static AWS basic credentials are required. [AwsConfig](D:/workspace/mcube/code/core/tcg-self-service-backup/src/main/java/com/tcgdigital/vmcontrol/config/AwsConfig.java:25) | Support workload roles/default credential chain and short-lived credentials; validate least privilege on the deployment. Static credentials alone are not proof of a breach. |
| Validation gap | Test profiles disable Entra and method security; OAuth integration tests call helper methods instead of exercising the production filter chain. [test properties](D:/workspace/mcube/code/core/tcg-self-service-backup/src/test/resources/application.properties:20), [OAuth tests](D:/workspace/mcube/code/core/tcg-self-service-backup/src/test/java/com/tcgdigital/vmcontrol/security/OAuth2IntegrationTest.java:31) | Add production-security HTTP tests and a role × environment × group authorization matrix. |
| Validation gap | No release pipeline, load-test evidence, restore drill, or current dependency vulnerability scan was established in this review. | Produce reproducible CI checks, dependency/SBOM review, backup/restore evidence, and staging load/recovery tests. External infrastructure may provide some of these; verify rather than assume absent. |

**What is already useful**

The application has a coherent MVC/service/repository structure, DTO responses, many controller role checks, group-aware access helpers, persisted operation details, after-commit dispatch, cloud status polling/reconciliation, scheduled-job database locks, and expiry-aware access queries. Deployment includes an HTTPS reverse proxy, health checks, log rotation, production schema validation, and disabled Swagger/H2 console. There are 197 passing tests in this environment. These are meaningful foundations, but do not cancel the release blockers above. Internal documentation is stale in several places: it describes H2 tests and only EC2 support, while the current code uses Testcontainers MySQL and includes AWS_EKS.

**Release requirements, in order**

1. Address exposed legacy credentials and session/authorization defects; verify denied access after deactivation, demotion, and expiry for both authentication modes.
2. Enforce environment/group ownership on all targets, operation IDs, audit/history feeds, and direct cloud mutations.
3. Make admission/cancellation atomic and recoverable; correct STOP/RESTART ordering and dependency validation. Run concurrency and process-crash tests against MySQL with a deterministic fake provider.
4. Add the access-index migration, remove automatic repair, and validate fresh/upgrade schemas against MySQL with representative sanitized data.
5. Fix unsafe HTML rendering and CSRF/session-login handling; exercise the real browser/server flow.
6. Run the full suite with Docker, add the missing negative/security/regression tests, verify the packaged frontend, and perform staging recovery/load/dependency checks before release approval.

No numeric readiness percentage is assigned: a score would hide critical failures. The release should remain blocked until the high-severity findings are resolved and their regression checks pass.

