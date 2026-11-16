# VM Self-Service Platform — Delivery Backlog

Combined from the code review and the product gap review (7 October 2026). One developer works through these tasks **in order, top to bottom**. Each task builds only on tasks above it, is mergeable on its own with tests, and takes 0.5–2 days.

**38 epics · 268 tasks · 351.5 estimated developer-days (about 71 weeks at 5 days a week).**

| Phase | Days | Weeks |
| --- | --- | --- |
| Fix | 144 | 29 |
| Now | 45 | 9 |
| Next | 92.5 | 19 |
| Later | 70 | 14 |

Rules: never edit an existing Flyway migration (use the next free `V` number at implementation time); new automation that changes AWS state ships behind a property that defaults to off; finding IDs (C/H/M) refer to the code review, gap IDs (G) to the product gap review.

## Epics

| Epic | Phase | Tasks | Days | Goal |
| --- | --- | --- | --- | --- |
| [E00 Safety net & shared building blocks](#e00) | Fix | 6 | 7 | Every later task can rely on a deterministic, Docker-backed, security-enabled test suite that runs in CI, plus the shared AfterCommit, Paging and error-handling building blocks. |
| [E01 Security emergency](#e01) | Fix | 9 | 9.5 | Leaked legacy passwords no longer open a session, and no user-controlled text can run as script in another user's browser (escaping by default plus an enforced Content-Security-Policy). |
| [E02 Frontend foundation](#e02) | Fix | 9 | 10.5 | The SPA router owns a page lifecycle (teardown, navigation token, route params, page-scoped polling) and every routed page uses it, so no page writes to #content-area after the user has left it and Environment Detail has its own URL. |
| [E03 Authorization hardening](#e03) | Fix | 12 | 13.5 | Every endpoint checks both the caller's role and their rights on the specific environment, group or record in the path, and role or status changes take effect on the next request. |
| [E04 Access & grants correctness](#e04) | Fix | 9 | 9 | Grants, requests and expiry behave exactly as admins and requesters expect: no 500s from stale indexes, correct level checks, bounded durations, true extensions, race-free reviews and an honest My Account view. |
| [E05 VM operations correctness](#e05) | Fix | 9 | 13.5 | Every start/stop/restart touches only the path environment's VMs, runs in the right dependency order, reports an honest final status, survives restarts and concurrent writers, and can be cancelled from the UI by the right people. |
| [E06 Automation & scheduler reliability](#e06) | Fix | 5 | 6.5 | Scheduled and access-triggered rules fire reliably once per window, never roll back the action that triggered them, skip cleanly when they cannot act, and every scheduled job runs on a thread pool under a cluster-wide job lock. |
| [E07 Locks](#e07) | Fix | 5 | 6.5 | An environment can only ever have one active lock, every lock ends on its own (expiry, extension, access loss, deactivation) with an honest history, and the lock UI shows who may do what and how long is left. |
| [E08 Cost data correctness & enablement](#e08) | Fix | 11 | 15.5 | Daily cost rows, actuals, reconciliation and rightsizing are numerically correct and fast, and the read-only AWS cost features are on by default with a setup page that proves the IAM permissions. |
| [E09 Sync & inventory robustness](#e09) | Fix | 10 | 12 | Discovery, state sync, EKS sync and tag reconciliation keep the VM inventory correct under AWS failures, concurrent writers and removed/returning instances, and the registry shows and resolves drift and pending-review VMs. |
| [E10 Environments & groups correctness](#e10) | Fix | 6 | 6 | Creating, editing, deactivating and deleting environments and groups never corrupts dependencies, EKS identity or metadata, and environment pages load with a bounded number of queries. |
| [E11 Audit, logs, notifications & dashboard](#e11) | Fix | 11 | 11 | Every significant change is audited reliably with its real actor, the log pages filter and export correctly, notifications and emails are safe and cheap to fan out, and the dashboard shows only what the user may see. |
| [E12 Metrics pipeline & system health](#e12) | Fix | 6 | 7 | Raw metrics are collected consistently, archived and rolled up without loss at any fleet size, and System Health shows true 24-hour numbers and every scheduled job's real freshness. |
| [E13 UX, mobile & accessibility](#e13) | Fix | 10 | 11 | Phone users can reach every page and read every table, keyboard and screen-reader users can reach every control, and colour, dialogs, toasts and status widgets behave correctly. |
| [E14 Platform hygiene & docs](#e14) | Fix | 8 | 5.5 | The build ships what is tested, migrations are never silently repaired, dead code, tables and junk files are gone, tests validate the schema, and the docs describe the system as it is. |
| [E15 Environment leases](#e15) | Now | 7 | 11.5 | Every opted-in running environment has an end time; the people using it are warned at 60/15/5 minutes and can extend (with owner approval above the maximum); when it ends the environment stops in reverse dependency order, attributed to 'Lease expired'. |
| [E16 Idle auto-stop](#e16) | Now | 5 | 8 | Environments (or groups) whose VMs have all been idle for a configurable time stop themselves in dependency order, after an owner-visible dry run that reports what would have been saved, with snooze and safe exclusions. |
| [E17 Savings ledger & efficiency score](#e17) | Now | 7 | 11 | The platform records the money its schedules, leases, idle stops, manual stops and rightsizing actually saved (net of residual EBS/IPv4 and commitment coverage), shows de-duplicated potential savings and an AWS-style Cost Efficiency %, and exports a monthly ledger for leadership. |
| [E18 Cost for owners](#e18) | Now | 4 | 5.5 | Every environment page and the My Account panel show month-to-date cost, trend, savings and schedule/lease status to the environment's owners and members, through scoped endpoints guarded by SecurityService (today every cost endpoint is ADMIN-only). |
| [E19 Schedule upgrades](#e19) | Now | 5 | 9 | Schedule rules support several windows, holiday calendars and one-off overrides, show their next runs before saving, and can be pre-filled from each environment's real usage pattern. |
| [E20 Budgets that act](#e20) | Next | 6 | 9.5 | Admins and environment owners set a monthly budget per environment or team, get alerts at 50/75/90/100% and on forecast overrun, and can attach an approval-gated freeze (block starts) or stop action, optionally mirrored to AWS Budgets. |
| [E21 Anomaly alerts routed to owners](#e21) | Next | 4 | 5.5 | AWS Cost Anomaly Detection anomalies for each environment (monitored on the tcg:environment cost allocation tag) are ingested, routed to that environment's owners by bell and email, and shown on the environment and admin pages with feedback. |
| [E22 Waste sweeps](#e22) | Next | 5 | 10 | Admins see a daily list of unattached EBS volumes, unassociated public IPv4/EIPs, gp2 volumes, VMs stopped over 30 days and old snapshots with estimated monthly cost, and can fix them through an approved, reversible action flow that is off by default. |
| [E23 Team allocation & chargeback](#e23) | Next | 8 | 10.5 | Every environment belongs to a real team (optionally an Entra group), required allocation tags are enforced at registration, shared costs are split by explicit rules, and finance gets a monthly chargeback statement per team. |
| [E24 Slack/Teams & Jira](#e24) | Next | 7 | 9 | Environment events reach the team's Slack or Microsoft Teams channel with safe one-click actions (extend lease, snooze idle stop, approve request, stop environment), and recommendations and anomalies can become Jira tickets. |
| [E25 Cost-aware approvals & quotas](#e25) | Next | 8 | 10.5 | Users see what a start or extension will cost before confirming, large or long runs need approval routed to the right approvers (any one or all), and teams stay within monthly VM-hour or dollar quotas. |
| [E26 EKS cost & idle coverage](#e26) | Next | 5 | 7.5 | EKS node groups get utilisation metrics so idle detection and rightsizing cover them, their estimated cost includes node disks and the cluster control-plane fee, and pod/namespace cost from Split Cost Allocation Data is shown where the account exports it. |
| [E27 Commitment-aware savings](#e27) | Next | 3 | 4.5 | FinOps can see how schedules interact with Savings Plan/RI coverage, what a schedule-safe commitment floor is, and AWS's own purchase recommendations. The view is display only; nothing is purchased. |
| [E28 Multi-account reach](#e28) | Next | 12 | 15 | The platform controls, discovers and costs EC2/EKS resources across many AWS accounts via an account registry and STS AssumeRole, with IAM-role (default credential chain) support and existing data migrated to a default account. |
| [E29 Help, settings & notification preferences](#e29) | Next | 8 | 10.5 | Admins change safe runtime settings from a Settings page without a restart, every user chooses which notifications reach them in-app or by email, and the in-app Help explains each feature where it is used. |
| [E30 Rightsizing 2.0](#e30) | Later | 7 | 10 | Rightsizing recommendations use memory as well as CPU, cover newer-generation and Graviton types with complete pricing, agree with Compute Optimizer, and can be applied as a safe stop-resize-start with automatic rollback. |
| [E31 Spot for non-prod and EKS](#e31) | Later | 8 | 10 | Owners of interruptible environments can opt in to Spot: EKS node groups move to a Spot node group with automatic on-demand fallback, Spot VMs are priced and reported correctly, and interruptions are detected and surfaced. |
| [E32 Templates & provisioning](#e32) | Later | 8 | 12.5 | Users can request a new environment from an approved template (AWS Service Catalog or Terraform), it is provisioned after approval, registered automatically, and destroyed at the end of its time-to-live through the lease mechanism. |
| [E33 RDS & other services](#e33) | Later | 6 | 9 | RDS DB instances and Aurora clusters are registered, scheduled, started and stopped like VMs through a new AWS_RDS provider, stay stopped despite AWS's 7-day auto-start, and are included in cost, tagging and idle detection. |
| [E34 API tokens, Terraform provider & webhooks](#e34) | Later | 8 | 12 | Teams can automate the platform with scoped personal and service API tokens, browse the OpenAPI spec when authenticated, receive signed outbound webhooks for key events, and have a reviewed plan and API surface for a Terraform provider. |
| [E35 AWS forecast & FOCUS export](#e35) | Later | 3 | 5 | The cost page shows AWS Cost Explorer's forecast (up to 18 months, with its prediction interval) next to the platform's own forecast, and finance can download cost data as a FOCUS 1.2-compatible CSV. |
| [E36 AI assistant / MCP](#e36) | Later | 5 | 7 | Users can ask natural-language questions about their environments, costs, savings and idle resources from an MCP-capable AI client (optionally next to the AWS Billing and Cost Management MCP server), through a read-only MCP server that only returns data the caller may see. |
| [E37 Carbon per environment](#e37) | Later | 3 | 4.5 | Monthly AWS Customer Carbon Footprint data from Data Exports is ingested and attributed to environments by cost share, and shown next to cost on the environment page and in Cost Management. |
| [PL Parking lot](#pl) | Parked | 14 | 4 | Decisions and ops switches deferred by the product owner (2026-10-08). Not scheduled: each item is picked up only after it is discussed. |

## E00

### Safety net & shared building blocks — Fix

Every later task can rely on a deterministic, Docker-backed, security-enabled test suite that runs in CI, plus the shared AfterCommit, Paging and error-handling building blocks.

Sources: H27, M33, M25 · 6 tasks · 7 days

#### E00-T01 — Add AbstractIntegrationTest with Testcontainers MySQL, a global scheduling switch and mocked cloud providers

- [ ] Done · **Type:** test · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H27, M33 · **Depends on:** —

Spring context tests need Docker, run real schedulers during tests and call the real AWS SDK with fake keys, so results depend on order and timing (H27, M33). A single base class fixes this once for every later task.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/Application.java` (modify) — remove @EnableScheduling (keep @EnableAsync and @EnableSpringDataWebSupport)
- `src/main/java/com/tcgdigital/vmcontrol/config/SchedulingConfig.java` (new) — @Configuration @EnableScheduling @ConditionalOnProperty(name="app.scheduling.enabled", havingValue="true", matchIfMissing=true)
- `src/main/resources/application.properties` (modify) — document app.scheduling.enabled=${APP_SCHEDULING_ENABLED:true}
- `src/test/resources/application.properties` (modify) — app.scheduling.enabled=false and every scheduler flag off
- `src/test/resources/application-test.properties` (modify) — same scheduler switches as application.properties
- `src/test/java/com/tcgdigital/vmcontrol/support/AbstractIntegrationTest.java` (new) — base class for every Spring-context test
- `src/test/java/com/tcgdigital/vmcontrol/support/SchedulingDisabledTest.java` (new) — asserts no ScheduledAnnotationBeanPostProcessor and no cloud SDK calls
- `build.gradle` (modify) — add testImplementation 'org.awaitility:awaitility' (version managed by the Spring Boot BOM)

**Steps**

1. Create config/SchedulingConfig.java: an empty @Configuration class annotated @EnableScheduling and @ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true). Remove @EnableScheduling and its import from Application.java.
2. In src/main/resources/application.properties add a commented block: app.scheduling.enabled=${APP_SCHEDULING_ENABLED:true} (master switch; turning it off stops every @Scheduled job, including AccessExpirationScheduler and EksSyncScheduler which have no own flag).
3. In src/test/resources/application.properties and application-test.properties add: app.scheduling.enabled=false, vm.state.sync.enabled=false, vm.inventory.sync.enabled=false, cloudwatch.metric.enable=false, cloudwatch.metric.rollup.enable=false, vm.metrics.archive.enabled=false, cost.snapshot.enabled=false, automation.rules.enabled=false, eks.sync.status-refresh.enabled=false, vm.discovery.enabled=false, cost.tagging.enabled=false, cost.actuals.enabled=false, cost.reservations.enabled=false, notification.weekly-reports.enabled=false, notification.email.enabled=false, graph.directory.enabled=false.
4. Create src/test/java/com/tcgdigital/vmcontrol/support/AbstractIntegrationTest.java: public abstract class annotated @SpringBootTest, @ActiveProfiles("test"), @Tag("db") and @Sql(scripts = "/db/reset-test-data.sql", executionPhase = BEFORE_TEST_METHOD). It relies on the existing jdbc:tc:mysql:8.0 URL from the test properties (no container code needed).
5. In the base class declare protected @MockitoBean fields (org.springframework.test.context.bean.override.mockito.MockitoBean) for CloudProviderFactory, AwsCloudProviderService, EksCloudProviderService, AwsCloudInventoryProviderService, AwsCloudMetricsProviderService, Ec2Service, ComputeOptimizerService, CostExplorerBillingService, CostExplorerTagActivationService, ReservationCoverageService and GraphDirectoryService. CloudProviderFactory is mocked because its constructor calls getProvider() on every CloudProviderService bean.
6. Add a @BeforeEach in the base class: when(cloudProviderFactory.getService(CloudProvider.AWS)).thenReturn(awsCloudProviderService); when(cloudProviderFactory.getService(CloudProvider.AWS_EKS)).thenReturn(eksCloudProviderService); when(cloudProviderFactory.isProviderAvailable(any())).thenReturn(true); stub awsCloudProviderService.getProvider() and eksCloudProviderService.getProvider() to their enum values. Subclasses override behaviour per test.
7. Add protected helpers used by later tasks: newEnvironment(String name), newGroup(Environment env, String name), newVm(VmGroup group, String name, VmStatus status), newUser(String email, boolean admin, boolean envAdmin) and grant(User user, AccessScopeType scope, String scopeId, AccessLevel level) that save through the existing repositories (copy the entity set-up used in VmOperationsServiceTest.setUp and EnvironmentAccessServiceTest).
8. Add a protected awaitAsync(Runnable assertion) helper that wraps Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(...), for code paths on notificationExecutor/vmOperationExecutor.
9. Add testImplementation 'org.awaitility:awaitility' to build.gradle.
10. Write SchedulingDisabledTest extends AbstractIntegrationTest: assert the context has no bean of type ScheduledAnnotationBeanPostProcessor, and that no CloudProviderService mock was invoked after context start (verifyNoInteractions on the provider mocks).

**Acceptance criteria**

- Given the test properties, when any AbstractIntegrationTest subclass starts the context, then no @Scheduled method runs and no real AWS client call is made.
- Given app.scheduling.enabled is unset in production, when the app starts, then all schedulers run exactly as before.
- Given a subclass test, when it calls newEnvironment/newGroup/newVm, then the rows exist and are wiped by reset-test-data.sql before the next test.

**Tests**

- SchedulingDisabledTest: no ScheduledAnnotationBeanPostProcessor bean; no interactions with cloud provider mocks.
- Existing VmcontrolApplicationTests still loads the context (with scheduling enabled default overridden to false only by test properties).

**Notes:** Existing test classes are migrated onto this base in E00-T06; this task only adds the base and switches. Keep @MockBean usages in existing tests working until then.

#### E00-T02 — Add SecuredWebTestBase (method security on, role helpers), a db test tag split and a GitHub Actions CI workflow

- [ ] Done · **Type:** test · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H27 · **Depends on:** E00-T01

Tests run with entraid.enabled=false, so @PreAuthorize is never evaluated and authorization bugs (H3, H5, H16) cannot be caught; and no CI runs the Docker-backed suite (H27).

**Files**

- `src/test/java/com/tcgdigital/vmcontrol/support/SecuredWebTestBase.java` (new) — MockMvc + the real EntraidSecurityConfig chain + role helpers
- `src/test/java/com/tcgdigital/vmcontrol/support/SecuredWebTestBaseSmokeTest.java` (new) — proves 401/403/200 per role on existing endpoints
- `build.gradle` (modify) — add unitTest task (excludeTags 'db'); keep test running everything
- `.github/workflows/ci.yml` (new) — ubuntu-latest, JDK 17, ./gradlew build (Docker is available on GitHub-hosted runners)

**Steps**

1. Create SecuredWebTestBase extends AbstractIntegrationTest, annotated @AutoConfigureMockMvc and @TestPropertySource(properties = {...}) that sets entraid.enabled=true plus dummy OAuth2 registration values so EntraidSecurityConfig (and its @EnableMethodSecurity) loads: spring.security.oauth2.client.registration.azure.client-id=test, ...client-secret=test, ...authorization-grant-type=authorization_code, ...redirect-uri={baseUrl}/login/oauth2/code/{registrationId}, ...scope=openid,profile,email, spring.security.oauth2.client.provider.azure.authorization-uri=http://localhost/authorize, ...token-uri=http://localhost/token, ...jwk-set-uri=http://localhost/keys, ...user-info-uri=http://localhost/userinfo, ...user-name-attribute=name. (The test resources' application.properties shadows the main one, so these registrations are absent today.)
2. In @BeforeEach create (via the base helpers) one user per role with a known azure_ad_object_id: admin (admin=true), envAdmin (envAdmin=true), operator (no global role) and viewer (no global role). Expose them as protected fields.
3. Add protected RequestPostProcessor helpers built on SecurityMockMvcRequestPostProcessors.oidcLogin(): asAdmin(), asEnvAdmin(), asUser(), asViewer(), as(User u) — each sets idToken claim oid = user.getAzureAdObjectId(), email, name, and authorities ROLE_USER plus ROLE_ADMIN / ROLE_ENV_ADMIN matching the user's flags (same mapping as CustomOAuth2UserService.buildAuthorities). anonymous() is plain Spring's.
4. Add helpers grantEnv(User, envId, AccessLevel) and grantGroup(User, groupId, AccessLevel) that delegate to the base grant(...) helper, so operator/viewer get scoped grants per test.
5. Add assertion helpers expectStatus(ResultActions, int) and jsonError(String message) for the error body shape {error,message,timestamp}.
6. Write SecuredWebTestBaseSmokeTest: GET /api/v1/users anonymous → 401 JSON; asUser() → 403; asAdmin() → 200. GET /api/v1/environments asViewer() → 200. This proves method security and principal resolution (UserService.getCurrentUser resolves OidcUser by oid).
7. build.gradle: register tasks.register('unitTest', Test) { useJUnitPlatform { excludeTags 'db' }; testClassesDirs = sourceSets.test.output.classesDirs; classpath = sourceSets.test.runtimeClasspath }. Leave 'test' running all tags.
8. Create .github/workflows/ci.yml: on push and pull_request; runs-on ubuntu-latest; actions/checkout, actions/setup-java (temurin 17, cache gradle); run ./gradlew build --no-daemon; upload build/reports/tests on failure. Do not create a .env (tests use test properties only).
9. Add a short 'Running tests' paragraph to README.md: Docker is required for ./gradlew test; ./gradlew unitTest runs non-DB tests without Docker.

**Acceptance criteria**

- Given a test extending SecuredWebTestBase, when it calls an ADMIN-only endpoint asUser(), then it receives 403 (method security active).
- Given no session, when an /api/** endpoint is called, then 401 with the JSON body from EntraidSecurityConfig is returned.
- Given a pull request, when CI runs, then ./gradlew build executes all tests including Testcontainers tests and fails the check on any failure.
- Given a laptop without Docker, when ./gradlew unitTest runs, then only tests not tagged db run and pass.

**Tests**

- SecuredWebTestBaseSmokeTest: anonymous 401, USER 403, ADMIN 200 on GET /api/v1/users; viewer 200 on GET /api/v1/environments.

**Notes:** If oauth2Login startup needs a reachable issuer, keep issuer-uri unset (only explicit URIs are given), which Spring does not contact at startup.

#### E00-T03 — Create AfterCommit.run(Runnable) for post-commit side effects

- [ ] Done · **Type:** refactor · **Estimate:** 0.5 d · **Risk:** low · **Sources:** — · **Depends on:** E00-T01

Several later epics (C5 automation hooks, notifications, cloud calls) must run side effects only after the surrounding transaction commits; a single helper avoids each one re-implementing TransactionSynchronization.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/support/AfterCommit.java` (new) — static run(Runnable r)
- `src/test/java/com/tcgdigital/vmcontrol/service/support/AfterCommitTest.java` (new) — commit, rollback and no-transaction cases

**Steps**

1. Create final class AfterCommit with private constructor and public static void run(Runnable r).
2. Implementation: if TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive(), register a TransactionSynchronization whose afterCommit() calls r.run() inside try/catch that logs (SLF4J WARN with the exception) and swallows, so a failing side effect never surfaces as a commit failure; otherwise call r.run() immediately (exceptions propagate to the caller in this case).
3. Javadoc: runs on the committing thread; nothing runs on rollback; do not perform DB writes that must be atomic with the transaction inside r (use REQUIRES_NEW if a write is needed).
4. Write AfterCommitTest extends AbstractIntegrationTest using TransactionTemplate: (a) inside a committed transaction the runnable runs once and only after commit (assert a flag is false before the callback returns and true after execute); (b) with setRollbackOnly the runnable never runs; (c) outside a transaction it runs immediately; (d) a throwing runnable after commit does not fail the transaction.

**Acceptance criteria**

- Given an active transaction, when AfterCommit.run(r) is called, then r runs exactly once after commit and never on rollback.
- Given no active transaction, when AfterCommit.run(r) is called, then r runs immediately.

**Tests**

- AfterCommitTest: commit, rollback, no-transaction, throwing-callback cases.

#### E00-T04 — Create Paging.of(page, size, maxSize) for validated page requests

- [ ] Done · **Type:** refactor · **Estimate:** 0.5 d · **Risk:** low · **Sources:** M25 · **Depends on:** E00-T01

Controllers build PageRequest.of(page, size) straight from query params, so negative values return 500 and size is unbounded (M36, M25 scenario). Later epics adopt one validated helper.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/support/Paging.java` (new) — static PageRequest of(int page, int size, int maxSize) and overload with Sort
- `src/test/java/com/tcgdigital/vmcontrol/controller/support/PagingTest.java` (new) — plain JUnit

**Steps**

1. Create final class Paging with public static PageRequest of(int page, int size, int maxSize) and of(int page, int size, int maxSize, Sort sort).
2. Rules: page < 0 → throw ValidationException("page must be >= 0") (already mapped to 400 by GlobalExceptionHandler); size < 1 → ValidationException("size must be >= 1"); size > maxSize → clamp to maxSize; maxSize < 1 → IllegalArgumentException (programming error).
3. Add public static final int DEFAULT_MAX_SIZE = 100 for callers without a specific limit.
4. Do not change existing controllers here; E10/E11/E08 tasks replace PageRequest.of calls (EnvironmentController:113, NotificationController:80-81, CostManagementController:131-154) with Paging.of.

**Acceptance criteria**

- Given page=-1, when Paging.of is called, then a ValidationException is thrown (400 via GlobalExceptionHandler).
- Given size=5000 and maxSize=100, when Paging.of is called, then the PageRequest has size 100.

**Tests**

- PagingTest (no Spring, no db tag): negative page, zero size, clamp, sort preserved.

#### E00-T05 — Map framework errors to 400/404/405/409 with one error body shape

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** M25 · **Depends on:** E00-T01

GlobalExceptionHandler's RuntimeException and Exception catch-alls turn missing resources, wrong methods, type mismatches and integrity violations into 500 with an ERROR log (M25).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/GlobalExceptionHandler.java` (modify) — extend ResponseEntityExceptionHandler; add DataIntegrityViolationException → 409, MethodArgumentTypeMismatchException → 400, ConstraintViolationException → 400
- `src/test/java/com/tcgdigital/vmcontrol/controller/GlobalExceptionHandlerTest.java` (new) — MockMvc assertions per mapping

**Steps**

1. Make GlobalExceptionHandler extend org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler. Add a private static Map<String,Object> body(String error, String message) that returns a LinkedHashMap with error, message, timestamp (Instant.now().toString()) and use it in every handler so all responses share one shape.
2. Remove the now-duplicated @ExceptionHandler methods for MethodArgumentNotValidException, MissingServletRequestParameterException and HttpMessageNotReadableException; override handleMethodArgumentNotValid, handleMissingServletRequestParameter and handleHttpMessageNotReadable instead, keeping today's messages and the 'errors' array.
3. Override handleExceptionInternal(ex, body, headers, statusCode, request) so every other framework exception (NoResourceFoundException → 404, HttpRequestMethodNotSupportedException → 405, HttpMediaTypeNotSupportedException → 415, MissingPathVariable, etc.) returns body(reasonPhrase, short safe message) with the framework's status and logs at WARN, not ERROR.
4. Add @ExceptionHandler(MethodArgumentTypeMismatchException.class) → 400 with message "Invalid value for parameter '<name>'" (never echo the raw value).
5. Add @ExceptionHandler(DataIntegrityViolationException.class) → 409 body("Conflict", "The change conflicts with existing data.") and WARN log including the root cause message.
6. Add @ExceptionHandler(jakarta.validation.ConstraintViolationException.class) → 400 with joined violation messages.
7. Keep the RuntimeException and Exception handlers last as 500s; keep AccessDeniedException as is (its message text is fixed in E03-T09).
8. Write GlobalExceptionHandlerTest extends AbstractIntegrationTest with @AutoConfigureMockMvc: GET /api/v1/environments/page?page=abc → 400; DELETE /api/v1/dashboard/summary → 405; GET /api/v1/does-not-exist → 404; a test-only @RestController (static nested @TestConfiguration) that throws DataIntegrityViolationException → 409; assert every body has error, message, timestamp.

**Acceptance criteria**

- Given a bad enum or number query value, when the endpoint is called, then 400 is returned instead of 500.
- Given a duplicate-key insert, when it reaches the controller, then 409 with error=Conflict is returned.
- Given an unsupported HTTP method, when called, then 405 is returned and nothing is logged at ERROR.

**Tests**

- GlobalExceptionHandlerTest: 400 type mismatch, 404 unknown path, 405 wrong method, 409 integrity violation, body shape.

**Notes:** Check the frontend api client still reads error.message (it does today); the shape is unchanged apart from new statuses.

#### E00-T06 — Move existing Spring tests onto AbstractIntegrationTest, replace sleeps with Awaitility and restore reflected settings

- [ ] Done · **Type:** test · **Estimate:** 2 d · **Risk:** medium · **Sources:** M33, H27 · **Depends on:** E00-T01, E00-T02

Existing tests sleep for async work, call real cloud SDKs and leave ReflectionTestUtils changes behind, so results are order dependent (M33).

**Files**

- `src/test/java/com/tcgdigital/vmcontrol/service/AuditServiceTest.java` (modify) — extend base; replace Thread.sleep(500) at lines 53, 70, 92, 116 with awaitAsync
- `src/test/java/com/tcgdigital/vmcontrol/service/VmOperationsServiceTest.java` (modify) — extend base; stub awsCloudProviderService instead of real SDK
- `src/test/java/com/tcgdigital/vmcontrol/service/StateSyncServiceTest.java` (modify) — extend base; drop own @MockBean fields; restore staleTransitionalMinutes in @AfterEach
- `src/test/java/com/tcgdigital/vmcontrol/controller/MonitoringControllerIntegrationTest.java` (modify) — extend base; drop own @MockBean fields
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessServiceTest.java` (modify) — extend base; restore groupScopeEnabled in @AfterEach instead of try/finally
- `src/test/java/com/tcgdigital/vmcontrol/controller/AuditControllerIntegrationTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/controller/LockControllerIntegrationTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/controller/VmOperationsControllerIntegrationTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentHierarchyIntegrationTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentAccessControllerTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/controller/UserControllerTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/service/LockServiceTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentServiceTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/service/UserServiceTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/service/MyAccountServiceTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/service/DependencyValidatorTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/security/OAuth2IntegrationTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/FlywayMigrationTest.java` (modify) — extend base
- `src/test/java/com/tcgdigital/vmcontrol/AwsConfigurationTest.java` (modify) — extend base or tag db
- `src/test/java/com/tcgdigital/vmcontrol/VmcontrolApplicationTests.java` (modify) — extend base

**Steps**

1. For each @SpringBootTest class listed, replace its class-level @SpringBootTest/@ActiveProfiles/@Sql with 'extends AbstractIntegrationTest'. Keep @AutoConfigureMockMvc and @Transactional where the class has them. Delete per-class @MockBean fields that duplicate the base mocks (MonitoringControllerIntegrationTest lines 52-56, StateSyncServiceTest lines 44-48) and use the inherited fields.
2. Where a test relied on the real CloudProviderFactory/AWS service (VmOperationsServiceTest, VmOperationsControllerIntegrationTest, StateSyncServiceTest), stub the inherited mocks explicitly (e.g. when(awsCloudProviderService.startVm(...)).thenReturn(...)) so no SDK client is built.
3. Replace every Thread.sleep in AuditServiceTest with awaitAsync(() -> assertThat(...)). grep -rn 'Thread.sleep' src/test must return nothing at the end.
4. For Spring-bean fields changed with ReflectionTestUtils.setField (StateSyncServiceTest:319/334, EnvironmentAccessServiceTest:574-579), capture the original value in @BeforeEach and restore it in @AfterEach.
5. Run ./gradlew test twice, once with --tests in reverse alphabetical order via a JUnit ClassOrderer.ClassName property override (junit.jupiter.testclass.order.default in src/test/resources/junit-platform.properties set to org.junit.jupiter.api.ClassOrderer$Random), and fix any failure that depends on order.
6. Commit junit-platform.properties with ClassOrderer$Random so order dependence keeps surfacing in CI.

**Acceptance criteria**

- Given the full suite, when it runs twice with random class order, then both runs pass.
- Given the test sources, when searched for Thread.sleep, then no occurrence remains.
- Given any Spring context test, when it runs, then no real AWS SDK call is attempted.

**Tests**

- All existing Spring tests pass on the shared base; junit-platform.properties enforces random class order.

**Notes:** If a migrated test exposes a real bug, mark it @Disabled with the finding id (e.g. "C4") and leave a TODO; the owning epic re-enables it. Do not fix product bugs here.

## E01

### Security emergency — Fix

Leaked legacy passwords no longer open a session, and no user-controlled text can run as script in another user's browser (escaping by default plus an enforced Content-Security-Policy).

Sources: C1, C2, C3 · 9 tasks · 9.5 days

#### E01-T01 — Turn off password login unless explicitly enabled outside Entra mode, and clear legacy plain-text passwords

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** medium · **Sources:** C1 · **Depends on:** E00-T02

V3 put plain-text passwords for legacy users in git, /api/auth/login is permitted in the Entra chain, and AuthenticationService still accepts plain-text matches (C1).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/AuthController.java` (modify) — reject login (404) unless password login is enabled
- `src/main/java/com/tcgdigital/vmcontrol/service/AuthenticationService.java` (modify) — remove the plain-text branch at lines 175-184; add isPasswordLoginEnabled()
- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — remove "/api/auth/login" from the permitAll list (line 32)
- `src/main/resources/application.properties` (modify) — auth.password-login.enabled=${AUTH_PASSWORD_LOGIN_ENABLED:false}
- `src/main/resources/application-prod.properties` (modify) — auth.password-login.enabled=false
- `src/main/resources/db/migration/V<next>__clear_legacy_plaintext_passwords.sql` (new) — null every non-bcrypt password (next free V number at implementation time)
- `src/test/java/com/tcgdigital/vmcontrol/controller/AuthControllerTest.java` (new) — login disabled/enabled behaviour
- `src/test/java/com/tcgdigital/vmcontrol/service/AuthenticationServiceTest.java` (new) — plain-text password never authenticates

**Steps**

1. Add property auth.password-login.enabled (default false) with a comment: only honoured when entraid.enabled=false; intended for local development only.
2. In AuthenticationService inject @Value("${auth.password-login.enabled:false}") boolean passwordLoginEnabled and @Value("${entraid.enabled:true}") boolean entraidEnabled; add public boolean isPasswordLoginEnabled() { return passwordLoginEnabled && !entraidEnabled; }.
3. In AuthenticationService.authenticateUser: first line throws UnauthorizedException("Invalid credentials or user not found.") when !isPasswordLoginEnabled(). Replace the if/else at lines 172-184 with: authenticated = isBcryptHash(stored) && passwordEncoder.matches(password, stored); delete the lazy plain-text migration and its log line.
4. In AuthController.login: if (!authenticationService.isPasswordLoginEnabled()) return ResponseEntity.notFound().build(); before reading the body.
5. Remove "/api/auth/login" from the requestMatchers(...).permitAll() list in EntraidSecurityConfig (line 32). Anonymous calls now get the JSON 401 from the entry point.
6. Create migration V<next>__clear_legacy_plaintext_passwords.sql (next free V number at implementation time; never edit V3): UPDATE app_user SET password = NULL, password_updated_at = NULL WHERE password IS NOT NULL AND password NOT LIKE '$2%'; Add a header comment that the V3 values are compromised and must never be reused. Do not print or reference the V3 values.
7. AuthenticationServiceTest extends AbstractIntegrationTest with @TestPropertySource(properties={"auth.password-login.enabled=true","entraid.enabled=false"}): a user saved with a plain-text password cannot log in; a bcrypt password can; with auth.password-login.enabled=false (second nested test class) even a correct bcrypt password is rejected.
8. AuthControllerTest extends SecuredWebTestBase (Entra chain): anonymous POST /api/auth/login → 401; authenticated POST → 404. Extend FlywayMigrationTest with an assertion that after migration no app_user.password is non-null without the '$2' prefix.
9. Ops note in the task PR: ask the owner to rotate any credential that matched a V3 value elsewhere; rewriting git history is out of scope.

**Acceptance criteria**

- Given production (entraid.enabled=true), when anyone POSTs to /api/auth/login, then no session is created (401 anonymous, 404 authenticated).
- Given a database migrated from V3, when the new migration runs, then no app_user row keeps a non-bcrypt password.
- Given entraid.enabled=false and auth.password-login.enabled=true, when a user with a bcrypt password logs in, then login still works locally.

**Tests**

- AuthenticationServiceTest: plain-text rejected, bcrypt accepted, flag off rejects.
- AuthControllerTest: 401/404 under the Entra chain.
- FlywayMigrationTest: no non-bcrypt passwords after migrate.

**Notes:** Dev users who used the form must set AUTH_PASSWORD_LOGIN_ENABLED=true with ENTRAID_ENABLED=false and a bcrypt password (AuthenticationService.setUserPassword).

#### E01-T02 — Throttle password login, rotate the session id on login and drop the custom authentication token

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** C1, M22, LOW-SEC-CUSTOM-TOKEN · **Depends on:** E01-T01

Password login has no rate limit or lockout (C1), keeps the pre-login session id (M22, session fixation), and a custom UsernamePasswordAuthenticationToken shadows Spring's and keeps the User entity with its bcrypt hash in the session (low-severity security item).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/LoginThrottleService.java` (new) — in-memory failure counters per username and per client IP
- `src/main/java/com/tcgdigital/vmcontrol/controller/AuthController.java` (modify) — check throttle, 429 + Retry-After, request.changeSessionId(), Spring token
- `src/main/java/com/tcgdigital/vmcontrol/security/UsernamePasswordAuthenticationToken.java` (delete) — replaced by Spring's token
- `src/main/java/com/tcgdigital/vmcontrol/service/UserService.java` (modify) — remove the custom-token branch in getCurrentUser (lines ~351-354) and its import
- `src/main/resources/application.properties` (modify) — auth.login.max-failures-per-user=5, auth.login.max-failures-per-ip=20, auth.login.window-minutes=15
- `src/test/java/com/tcgdigital/vmcontrol/service/LoginThrottleServiceTest.java` (new) — plain JUnit with a fixed Clock
- `src/test/java/com/tcgdigital/vmcontrol/controller/AuthControllerTest.java` (modify) — add throttle and session-id cases (password login enabled profile)

**Steps**

1. Create LoginThrottleService (@Service) with a java.time.Clock (inject Clock.systemUTC() via constructor overload for tests). Keep two ConcurrentHashMap<String, Deque<Instant>> maps (by lower-cased username and by client IP). Methods: Optional<Duration> blockedFor(String username, String ip); void recordFailure(String username, String ip); void recordSuccess(String username) (clears the username entry). Drop timestamps older than the window on each access; cap each map at 10,000 keys by evicting the oldest entry.
2. In AuthController.login (after the enabled check from E01-T01): compute ip = request.getRemoteAddr(); if blockedFor(...) is present return 429 with header Retry-After (seconds) and LoginResponse(false, "Too many failed attempts. Try again later."); on UnauthorizedException call recordFailure; on success call recordSuccess.
3. Before saving the SecurityContext call request.getSession(true) then request.changeSessionId() so the authenticated session gets a new id.
4. Replace the custom token: build org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(user.getUserId(), null, authorities) where authorities are ROLE_USER plus ROLE_ADMIN/ROLE_ENV_ADMIN from the user flags (same mapping as the deleted class). Stop putting userId/username/userEmail attributes on the session (lines 85-88) unless something reads them (grep first; nothing in src/main reads them today).
5. Delete security/UsernamePasswordAuthenticationToken.java and the instanceof branch plus import in UserService.getCurrentUser; the existing 'principal instanceof String' branch resolves the user by id.
6. Log failed attempts at WARN with username and IP only (never the password).
7. Tests: LoginThrottleServiceTest — 5 failures block the username for the window, success clears it, IP limit independent of username, entries expire after the window. AuthControllerTest (nested class with entraid.enabled=false, auth.password-login.enabled=true): 6th bad attempt returns 429 with Retry-After; the JSESSIONID before and after a successful login differ; GET /api/v1/users/me after login returns the user (principal is a String id).

**Acceptance criteria**

- Given 5 failed logins for one username within 15 minutes, when a 6th attempt arrives, then 429 with Retry-After is returned even with the right password.
- Given a successful password login, when the response is returned, then the session id differs from the pre-login one.
- Given a password session, when it is serialised, then it holds no User entity or password hash.

**Tests**

- LoginThrottleServiceTest
- AuthControllerTest throttle + session-fixation cases

**Notes:** Counters are per JVM; acceptable because password login is dev-only after E01-T01. Client IP is only correct behind nginx once forward headers are configured (E03-T10).

#### E01-T03 — Hide the password form unless the server allows it, and move the login page script to a file

- [ ] Done · **Type:** security · **Estimate:** 0.5 d · **Risk:** low · **Sources:** C1, LOW-SEC-LOGIN-CONSOLE · **Depends on:** E01-T02

The login form always shows (C1) and login.html has an inline script that a strict CSP would block and that logs the user object to the console (low-severity sign-in item).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/AuthController.java` (modify) — GET /api/auth/options → {passwordLoginEnabled}
- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — permit GET /api/auth/options
- `src/main/resources/static/login.html` (modify) — form + divider hidden by default; inline <script> replaced by <script src="js/login.js">; inline style attributes replaced by d-none
- `src/main/resources/static/js/login.js` (new) — the moved script, without console.log of the response
- `src/test/java/com/tcgdigital/vmcontrol/controller/AuthControllerTest.java` (modify) — options endpoint cases

**Steps**

1. Add @GetMapping("/options") to AuthController returning Map.of("passwordLoginEnabled", authenticationService.isPasswordLoginEnabled()). Add "/api/auth/options" to the permitAll list in EntraidSecurityConfig (DefaultSecurityConfig already permits all).
2. In login.html add class d-none to #loginForm and .login-divider; replace style="display: none;" on #errorAlert and #logoutMessage with class d-none.
3. Move the whole inline <script> block (lines 87 onward) to static/js/login.js, wrapped in an IIFE. On DOMContentLoaded fetch('/api/auth/options', {credentials:'same-origin'}); when passwordLoginEnabled is true remove d-none from the form and divider; otherwise leave only the Entra button. Toggle visibility with classList instead of style.display.
4. Delete console.log('Login successful', data) and do not read the response body on success; keep the generic error text from the server.
5. Reference the file with <script src="js/login.js"></script> after the bootstrap bundle. login.js is not part of index.html, so build-frontend bundling is unaffected.
6. Tests: GET /api/auth/options anonymous under the Entra chain → 200 {passwordLoginEnabled:false}; with entraid.enabled=false and the flag on → true. Manual check: open /login in both modes; the form appears only in the second.

**Acceptance criteria**

- Given production, when /login loads, then only 'Sign in with Entra ID' is visible and login.html contains no inline script.
- Given local dev with password login enabled, when /login loads, then the username/password form is shown and works.
- Given a successful login, when the browser console is inspected, then no user object is logged.

**Tests**

- AuthControllerTest: options endpoint in both modes.

#### E01-T04 — Make Utils.escapeHtml escape quotes, add Utils.html and Utils.raw, and remove the duplicate escape helpers

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** medium · **Sources:** C2, LOW-FE-ESCAPE-ZERO · **Depends on:** E00-T02

Utils.escapeHtml uses textContent/innerHTML, which does not escape " or ', so values inside title="..." break out of the attribute (C2); eight files carry their own copies with the same flaw, and Utils.escapeHtml(0) returns an empty string.

**Files**

- `src/main/resources/static/js/core/utils.js` (modify) — new escapeHtml (line 133), add html tagged template and raw marker, export them
- `src/main/resources/static/js/features/access-management.js` (modify) — delete local escapeHtml (line 1498); use Utils.escapeHtml
- `src/main/resources/static/js/features/all-logs.js` (modify) — delete local escapeHtml (line 702)
- `src/main/resources/static/js/features/dashboard.js` (modify) — delete local escapeHtml (line 808)
- `src/main/resources/static/js/features/environments.js` (modify) — delete local escapeHtml arrow (line 10)
- `src/main/resources/static/js/features/my-account.js` (modify) — delete esc() (line 743); call Utils.escapeHtml
- `src/main/resources/static/js/features/user-management.js` (modify) — delete local escapeHtml (line 842)
- `src/main/resources/static/js/ui/user-menu.js` (modify) — delete local escapeHtml (line 156)
- `src/main/resources/static/js/core/template-loader.js` (modify) — local escapeHtml (line 104) delegates to Utils.escapeHtml at call time
- `src/test/js/utils-escape.test.mjs` (new) — node --test unit test loading utils.js in a vm context
- `.github/workflows/ci.yml` (modify) — add a step: node --test src/test/js

**Steps**

1. Replace Utils.escapeHtml with a string-replace implementation: if (value === null || value === undefined) return ''; return String(value).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); (0 and false now render as '0' and 'false').
2. Add function raw(value) returning a frozen object { __html: String(value ?? '') } (class RawHtml) and function html(strings, ...values) that concatenates strings with each value converted by: RawHtml → its __html; Array → each element converted by the same rule and joined with ''; null/undefined/false → ''; anything else → escapeHtml(value). html returns a plain string so it can be passed to jQuery .html()/.append(). Document: wrap a fragment returned by another Utils.html call in Utils.raw(...) to embed it.
3. Export escapeHtml, html and raw from the Utils return object (near line 266).
4. Delete the local helpers listed in files and replace their call sites with Utils.escapeHtml (keep call sites otherwise unchanged; e.g. in my-account.js replace esc( with Utils.escapeHtml( ). In template-loader.js make escapeHtml(str) return Utils.escapeHtml(str) (template-loader.js loads before utils.js, so resolve Utils at call time).
5. Add a header comment in utils.js describing the escaping contract: interpolate user or server data only through Utils.escapeHtml or Utils.html; use .text()/.attr() for single values; never place data inside inline event handlers.
6. Create src/test/js/utils-escape.test.mjs using node:test and node:vm: read src/main/resources/static/js/core/utils.js, run it in a context with minimal window/document stubs, read Utils from the context and assert: escapeHtml('<a href="x" title=\'y\'>&') equals the fully escaped form; escapeHtml(0) === '0'; escapeHtml(null) === ''; Utils.html`<p title="${'" onfocus="x'}">${Utils.raw('<b>ok</b>')}</p>` keeps <b> and escapes the quote; arrays are escaped per element.
7. Add a CI step after setup-java: uses actions/setup-node (20) then run node --test src/test/js.
8. Run .\build-frontend.ps1 so dist/ is regenerated (do not edit dist by hand). Manual check: as a user submit an access request whose justification contains '" autofocus onfocus="alert(1)'; as admin open Pending Requests and Access Management; no alert fires and the text shows literally.

**Acceptance criteria**

- Given a justification containing a double quote and an event handler, when an admin opens Pending Requests or Access Management, then the text renders literally and no handler runs.
- Given the codebase, when searched for 'function escapeHtml' or 'function esc(', then only utils.js (and the delegating template-loader shim) define one.
- Given Utils.escapeHtml(0), when called, then it returns '0'.

**Tests**

- src/test/js/utils-escape.test.mjs run by node --test in CI.

**Notes:** Escaping ' as &#39; can show up literally where callers put escaped text into .text() or textContent; grep for Utils.escapeHtml inside .text( calls and remove the double escape.

#### E01-T05 — Make Modals and Notifications treat titles and messages as text unless the caller passes { html: true }

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** medium · **Sources:** C2, C3, LOW-FE-TOAST · **Depends on:** E01-T04

Notifications.show and Modals.show/confirm/prompt insert titles, messages and labels as raw HTML, so server messages and names reach the DOM unescaped (C2 fix: unsafe-by-default rendering; toasts and modals sinks).

**Files**

- `src/main/resources/static/js/ui/notifications.js` (modify) — options param {html}; text by default; delegated dismiss; unique ids; 'danger' alias
- `src/main/resources/static/js/ui/modals.js` (modify) — show(): title and button text escaped unless options.html; confirm()/prompt() text by default; DestructiveConfirm title and impact items as text
- `src/main/resources/static/js/features/access-management.js` (modify) — Modals.confirm at line 1360 passes { html: true } (values already escaped)
- `src/main/resources/static/js/features/cost-management.js` (modify) — Modals.confirm at line 965: escape vmName/currentType/targetType and pass { html: true }
- `src/main/resources/static/js/features/environments.js` (modify) — Modals.confirm at lines 846, 1041, 1169 pass { html: true }
- `src/main/resources/static/js/features/locks.js` (modify) — Modals.confirm at line 74 passes { html: true }
- `src/main/resources/static/js/features/vm-operations.js` (modify) — updateProgressModal error branch (line 226) uses text for the message
- `src/test/js/notifications-text.test.mjs` (new) — node --test: load notifications.js with a minimal jQuery stub (vm context) and assert .text() is used unless {html:true}

**Steps**

1. notifications.js: change show(message, type = 'info', duration = 3000, options = {}) to build the toast with jQuery: create the element skeleton without the message, then set .toast-message via .html(message) only when options.html === true, else .text(message). Do the same for showError title and message (options.html). success/error/warning/info gain an optional options argument passed through.
2. Replace onclick="Notifications.dismiss('...')" on .toast-close with one delegated handler bound in init(): $container.on('click', '.toast-close', e => dismiss($(e.currentTarget).closest('.notification-toast').attr('id'))).
3. Generate ids with a module counter ('notif-' + (++seq)) instead of Date.now(); map type 'danger' to 'error' before looking up ICONS so the icon is never 'fas undefined'.
4. modals.js show(): add option html (default false). Title: html ? title : Utils.escapeHtml(title). Buttons: Utils.escapeHtml(btn.text) unless btn.html. Body stays HTML by contract (it is markup for forms); add a JSDoc line saying body must be built with Utils.html.
5. modals.js confirm(title, message, onConfirm, options): body = `<p>${options.html ? message : Utils.escapeHtml(message)}</p>`; pass { html: options.html } through to show() for the title. prompt(): escape label, placeholder and defaultValue with Utils.escapeHtml.
6. DestructiveConfirm.show(): set the title with a separate text node (innerHTML for the icon, then append document.createTextNode(title)); build impact items with document.createElement('li') + textContent.
7. Update the HTML-passing callers listed in files to pass { html: true } and make sure every interpolated value in those messages already goes through Utils.escapeHtml (cost-management.js:967 does not today: wrap vmName, currentType, targetType).
8. vm-operations.js line 226: build the error line as $statusText.empty().append('<i class="fas fa-times-circle text-danger me-2"></i>').append(document.createTextNode(message)).
9. grep all Notifications.* and Modals.* calls for '<' in the message argument; any other caller that intentionally passes markup gets { html: true } with escaped interpolations. Run .\build-frontend.ps1.
10. Manual checks: trigger a 409 lock conflict whose message contains a user id; trigger a toast with '<img src=x onerror=alert(1)>' via the browser console Notifications.error('<img src=x onerror=alert(1)>'); both render as text.

**Acceptance criteria**

- Given any caller that does not pass { html: true }, when a toast or modal title/message contains markup, then it renders as literal text.
- Given the Revoke Access, Delete Environment, Notify Members, Release Lock and operation confirms, when opened, then their bold names still render as bold (html: true) and the names are escaped.
- Given two toasts in the same millisecond, when both are dismissed, then each closes independently; a 'danger' toast shows the error icon.

**Tests**

- src/test/js/notifications-text.test.mjs (node --test in CI)
- Manual checklist in the PR: 409 lock message, console-injected toast, each html:true confirm still bold.

**Notes:** Keep the existing public signatures; the new options argument is optional, so untouched callers become text-safe automatically.

#### E01-T06 — Escape every remaining data sink in System Health, user menu, operations, logs, user management and environments

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** C3, C2 · **Depends on:** E01-T05

System Health interpolates VM, user and audit names raw (C3, reproduced); the user-menu title, operation history initiator, log environment filter options, user-management error text and environment loading messages do the same.

**Files**

- `src/main/resources/static/js/features/system-health.js` (modify) — escape lines 320 (topEnv), 404, 408, 426, 454-457, 487-489, 508/528 (msg/message)
- `src/main/resources/static/js/ui/user-menu.js` (modify) — title="${user.displayName}" at line 65 → Utils.escapeHtml; initials escaped
- `src/main/resources/static/js/features/vm-operations.js` (modify) — history row initiator (line 670), operationType/status via escapeHtml; titles at 20-71 and 619 now safe through Modals (E01-T05)
- `src/main/resources/static/js/features/all-logs.js` (modify) — environment option names at line 284 and option values escaped
- `src/main/resources/static/js/features/activity-logs.js` (modify) — environment option names at line 156 escaped
- `src/main/resources/static/js/features/user-management.js` (modify) — xhr message at line 70 escaped; confirm at 425 relies on text default
- `src/main/resources/static/js/features/environments.js` (modify) — showLoading/showError messages (lines 271, 286) escaped; envName from lines 83 and 101
- `src/main/resources/static/js/features/vm-registry.js` (modify) — env.description at line 197 escaped

**Steps**

1. system-health.js: wrap every interpolated data value with Utils.escapeHtml: topEnv (line 320, both title and text), c.vmName/c.vmId and c.changedByUsername (404, 408), e.vmName (426), log.userDisplayName/userEmail, log.actionDisplay, log.environmentName/environmentId, log.targetName (454-457), log.environmentId, recipients, log.subject (487-489; replace the manual .replace(/"/g,'&quot;') with Utils.escapeHtml), and the msg/message parameters of the empty/error builders (508, 528). Column headers at 462 are constants; leave them.
2. user-menu.js line 65: title="${Utils.escapeHtml(user.displayName)}"; also escape initials and any displayName/email shown in the dropdown in the same template.
3. vm-operations.js line 670: ${Utils.escapeHtml(exec.initiatedByDisplayName || exec.initiatedBy || '-')}; lines 663/666 escape exec.operationType and exec.status.
4. all-logs.js line 284 and activity-logs.js line 156: escape both the option value (e.id/env.id) and the label (e.name/env.name). Same for the user option value at all-logs.js line 280.
5. user-management.js line 70: escape xhr.responseJSON?.message.
6. environments.js: in the helpers rendering <p>${message}</p> (lines 271 and 286) use Utils.escapeHtml(message); callers at lines 83 and 101 pass raw envName.
7. vm-registry.js line 197: description = env.description ? Utils.escapeHtml(env.description) : '<span class="text-muted">No description</span>'.
8. Run a final grep over static/js for template interpolations of name/description/message/email/reason/title fields not wrapped in Utils.escapeHtml or Utils.html and fix any found; list the grep command in the PR. Run .\build-frontend.ps1.
9. Manual reproduction of C3: register (or rename via DB in dev) a VM named <img src=x onerror=alert(1)>, record a state change, open System Health, Environments, VM Registry, Activity and All Logs as admin; nothing executes.

**Acceptance criteria**

- Given a VM, user, environment or audit target name containing markup, when an admin opens System Health, then it renders as text and no script runs.
- Given an environment named with a quote and markup, when the All Logs or My Activity environment filter opens, then the option label shows the literal name.
- Given a display name containing a quote, when the user menu renders, then the avatar title attribute is not broken.

**Tests**

- Manual XSS checklist in the PR (C3 payload across the listed pages); utils tests from E01-T04 cover the helper.

#### E01-T07 — Replace all inline event handlers with delegated data-action handlers

- [ ] Done · **Type:** security · **Estimate:** 2 d · **Risk:** medium · **Sources:** C2, C3, LOW-FE-INLINE-ONCLICK · **Depends on:** E01-T06

vm-registry.js builds onclick="VmRegistry.manageGroups('id','name')" and deleteVm('id','name'), where HTML escaping does not protect the JavaScript string context (C2/C3 sink); and inline handlers (about 40 in JS plus 4 in index.html) block a strict CSP.

**Files**

- `src/main/resources/static/js/features/vm-registry.js` (modify) — 20 onclick attributes (lines 61-894) → data-action + data-* ids; one delegated listener in init
- `src/main/resources/static/js/features/environments.js` (modify) — onclick at 287, 648, 787, 1244 → delegated
- `src/main/resources/static/js/features/system-health.js` (modify) — onclick at 251, 254, 257, 529 → delegated
- `src/main/resources/static/js/features/dashboard.js` (modify) — onclick at 97, 115
- `src/main/resources/static/js/features/access-requests.js` (modify) — onclick at 139
- `src/main/resources/static/js/features/activity-logs.js` (modify) — onclick at 534
- `src/main/resources/static/js/features/all-logs.js` (modify) — onclick at 735
- `src/main/resources/static/js/features/audit-logs.js` (modify) — onclick at 168
- `src/main/resources/static/js/features/cost-management.js` (modify) — onclick at 205; Pagination.buildSimpleMarkup call at 775-780 passes data instead of JS strings
- `src/main/resources/static/js/ui/pagination.js` (modify) — buildSimpleMarkup: replace prevOnClick/nextOnClick with data-page attributes and an onPage callback
- `src/main/resources/static/js/core/router.js` (modify) — onclick at 196
- `src/main/resources/static/js/app.js` (modify) — onclick at 79
- `src/main/resources/static/index.html` (modify) — onclick at lines 395, 461, 502, 591 → ids bound in VmRegistry/Features init
- `src/main/resources/static/js/features/features.js` (modify) — bind #btnSubmitGroup, #btnFetchEc2, #btnSubmitVm click handlers

**Steps**

1. Pattern: render <button data-action="manage-groups" data-env-id="${Utils.escapeHtml(id)}">; in the module's init bind once: $(document).off('click.vmRegistry').on('click.vmRegistry', '[data-action]', handler) scoped to the module's root container; the handler switches on data-action and reads ids/names from data attributes (or looks the name up from the module's loaded state by id instead of passing it).
2. vm-registry.js: convert all 20 inline handlers; manageGroups and deleteVm read the name from state by id (no name in markup). selectEc2Instance uses the existing data-instance-id attribute.
3. environments.js line 787 onclick="event.stopPropagation()": replace with a delegated handler that calls e.stopPropagation() for [data-stop-propagation]. Line 1244 toggle: data-action="toggle-vm-estimates" handled by a delegated listener.
4. pagination.js buildSimpleMarkup: accept { page, totalPages, totalItems, itemLabel, key } and render data-page-key/data-page attributes; export bindSimple(container, onPage) that delegates clicks to onPage(key, page). Update cost-management.js (line 775) to use it.
5. Retry/Back/Refresh buttons in dashboard, access-requests, activity-logs, all-logs, audit-logs, cost-management, system-health, router and app.js: use data-action="retry" (and "reload", "go-dashboard") with a delegated handler inside each module; router/app use location.reload() in the handler.
6. index.html lines 395, 461, 502, 591: remove onclick; bind by id in VmRegistry.init / Features init with .off('click').on('click', ...).
7. Verify with grep -rnE 'on(click|change|input|submit|error|load)=' src/main/resources/static/js src/main/resources/static/index.html returning nothing. Run .\build-frontend.ps1.
8. Manual regression: click every converted control (registry env/group/VM actions, EC2 picker, pagination on cost tables, retry buttons, estimate toggle).

**Acceptance criteria**

- Given the static sources, when grepped for inline on* handler attributes, then none remain.
- Given an environment display name containing a single quote and markup, when Manage Groups or Remove VM is clicked, then the action works and no script runs.
- Given each converted control, when clicked, then it behaves as before.

**Tests**

- Manual regression checklist in the PR; grep check can be added to CI as a shell step: ! grep -rnE 'on(click|change|input|submit)="' src/main/resources/static/js src/main/resources/static/index.html.

**Notes:** Add the grep guard as a CI step so new inline handlers fail the build before the CSP task lands.

#### E01-T08 — Limit name characters on the server and sanitise names taken from AWS tags

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** C3 · **Depends on:** E00-T06

VM names arrive from EC2 Name tags through discovery and state sync, and admins can type any characters into names, so markup reaches every page that renders them (C3 fix: limit name characters on the server).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/support/NameSanitizer.java` (new) — static String clean(String) removing control characters and < > ` and trimming to 255
- `src/main/java/com/tcgdigital/vmcontrol/dto/RegisterVmDTO.java` (modify) — @Pattern(regexp = "^[^<>`\\p{Cntrl}]*$") on name and displayName
- `src/main/java/com/tcgdigital/vmcontrol/dto/CreateEnvironmentDTO.java` (modify) — same @Pattern on name, displayName
- `src/main/java/com/tcgdigital/vmcontrol/dto/UpdateEnvironmentDTO.java` (modify) — same @Pattern on displayName
- `src/main/java/com/tcgdigital/vmcontrol/dto/CreateVmGroupDTO.java` (modify) — same @Pattern on name, displayName
- `src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java` (modify) — wrap cloudVmName before vm.setName/setDisplayName (lines ~382-383)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — wrap displayName/slug and envName before setName/setDisplayName (lines ~146-147, 196-197, 273-274)
- `src/test/java/com/tcgdigital/vmcontrol/service/support/NameSanitizerTest.java` (new) — plain JUnit

**Steps**

1. Create NameSanitizer.clean(String in): null → null; remove \p{Cntrl} characters and the characters < > `; collapse runs of whitespace; trim; truncate to 255. Apostrophes, quotes and ampersands are kept (legitimate in names; the frontend escapes them).
2. Add the @Pattern(regexp = "^[^<>`\\p{Cntrl}]*$", message = "Name must not contain < > ` or control characters") constraint to the DTO fields listed. Controllers already use @Valid (verify per endpoint; add @Valid where missing) and MethodArgumentNotValidException maps to 400.
3. In StateSyncService where the cloud Name tag is applied, and in VmDiscoveryService where display names and environment names come from tags, pass the value through NameSanitizer.clean before setting it.
4. Tests: NameSanitizerTest (markup removed, apostrophe kept, control chars removed, length capped). Extend an existing controller test (EnvironmentHierarchyIntegrationTest) with POST /api/v1/environments using displayName '<img src=x>' → 400. StateSyncServiceTest: mocked provider returns Name tag '<b>x</b>' → stored display name 'bx'.

**Acceptance criteria**

- Given a create/update request whose name contains < or >, when submitted, then 400 is returned with a validation message.
- Given an EC2 Name tag containing markup, when state sync or discovery stores it, then the stored name has no angle brackets.

**Tests**

- NameSanitizerTest
- EnvironmentHierarchyIntegrationTest 400 case
- StateSyncServiceTest sanitised tag case

**Notes:** Existing rows are not rewritten; frontend escaping (E01-T04..T06) protects against legacy values. M3 name normalisation is handled in E10.

#### E01-T09 — Send an enforced Content-Security-Policy and restore frame protection

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** medium · **Sources:** C2, C3, LOW-SEC-FRAME-H2 · **Depends on:** E01-T03, E01-T07

There is no CSP, so any missed sink is directly exploitable (C2 fix), and frame options are disabled globally for an H2 console the app no longer ships (low-severity item: frame options and H2 console permits).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/security/SecurityHeaders.java` (new) — static Customizer<HeadersConfigurer<HttpSecurity>> apply(String csp) shared by both configs
- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — use SecurityHeaders; remove frameOptions disable (line 84) and /h2-console/** from permitAll (line 30) and CSRF ignore (line 81)
- `src/main/java/com/tcgdigital/vmcontrol/security/DefaultSecurityConfig.java` (modify) — same headers; remove /h2-console/** permit and frameOptions disable
- `src/main/resources/application.properties` (modify) — security.csp.report-only=${SECURITY_CSP_REPORT_ONLY:false}
- `src/test/java/com/tcgdigital/vmcontrol/security/SecurityHeadersTest.java` (new) — asserts headers on /login, /home and /api/v1/environments

**Steps**

1. Policy string (constant in SecurityHeaders): default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'. style-src keeps 'unsafe-inline' because templates and ECharts set style attributes; scripts get no inline allowance.
2. SecurityHeaders.apply(boolean reportOnly) returns headers -> headers.contentSecurityPolicy(c -> { c.policyDirectives(POLICY); if (reportOnly) c.reportOnly(); }).frameOptions(f -> f.deny()).referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)).contentTypeOptions(Customizer.withDefaults()).
3. In EntraidSecurityConfig replace .headers(headers -> headers.frameOptions(frame -> frame.disable())) with .headers(SecurityHeaders.apply(reportOnly)) where reportOnly comes from @Value("${security.csp.report-only:false}"). Remove "/h2-console/**" from permitAll and from csrf ignoringRequestMatchers (the project has no H2 dependency in build.gradle). Do the same in DefaultSecurityConfig.
4. Swagger UI is dev-only (disabled in prod). If it breaks under the policy, exclude /swagger-ui/** and /v3/api-docs/** from the CSP writer with a DelegatingRequestMatcherHeaderWriter (NegatedRequestMatcher) rather than relaxing the policy; verify manually.
5. Add a CI grep guard (from E01-T07) and a check that index.html and login.html contain no inline <script> without src.
6. SecurityHeadersTest extends SecuredWebTestBase: GET /login.html anonymous → Content-Security-Policy contains "script-src 'self'" and X-Frame-Options DENY; GET /api/v1/environments asViewer() has the same headers; with security.csp.report-only=true the header name is Content-Security-Policy-Report-Only.
7. Manual: open every main page with DevTools console open in Chrome; no CSP violation is reported (fix any remaining inline handler or javascript: URL).

**Acceptance criteria**

- Given any response from the app, when inspected, then it carries a Content-Security-Policy without 'unsafe-inline' or 'unsafe-eval' in script-src, and X-Frame-Options: DENY.
- Given an injected inline event handler, when the page renders it, then the browser blocks it and reports a CSP violation.
- Given /h2-console/**, when requested anonymously in Entra mode, then it requires authentication like any other path.

**Tests**

- SecurityHeadersTest: CSP and frame headers on page and API responses, report-only toggle.

**Notes:** Rollout: deploy once with SECURITY_CSP_REPORT_ONLY=true for a day if desired, then enforce (the default).

## E02

### Frontend foundation — Fix

The SPA router owns a page lifecycle (teardown, navigation token, route params, page-scoped polling) and every routed page uses it, so no page writes to #content-area after the user has left it and Environment Detail has its own URL.

Sources: H21, M26, M27, M32 · 9 tasks · 10.5 days

#### E02-T01 — Add router lifecycle (onLeave, token, isCurrent, reload) and page-scoped polling in RealTime

- [ ] Done · **Type:** refactor · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H21 · **Depends on:** E01-T01

Nothing tears a page down on navigation, so timers, listeners and late responses repaint whatever page the user moved to (H21). This task builds the shared contract every later page migration uses.

**Files**

- `src/main/resources/static/js/core/router.js` (modify) — navigation token, onLeave registry run before each load, token()/isCurrent()/reload(), close open slide-outs on route change
- `src/main/resources/static/js/core/realtime.js` (modify) — startPolling(key, fn, ms, { pageScoped, immediate }) registers an onLeave stop; run due callbacks when the tab becomes visible again
- `src/main/resources/static/js/app.js` (modify) — App.refresh() delegates to ContentRouter.reload()
- `test/fixtures/mock-api.ts` (new) — Playwright helpers: delayRoute(page, urlGlob, ms), fulfillJson(page, urlGlob, body), countRequests(page, urlGlob)
- `test/tests/navigation/router-lifecycle.spec.ts` (new) — contract tests for onLeave/token/isCurrent/pageScoped polling

**Steps**

1. router.js: add `let _navToken = 0; let _leaveHandlers = [];` next to `_pendingParams` (L39).
2. Add `function runLeaveHandlers()` that copies and clears `_leaveHandlers`, then calls each fn inside try/catch (console.error on failure, never rethrow).
3. In `loadContent(contentType, params)` (L114-127): call `runLeaveHandlers()` first, then `_navToken++`, then `showLoading()` and the loader. Same-route reloads therefore also tear down first.
4. Add public `token()` (returns `_navToken`), `isCurrent(t)` (returns `t === _navToken`), `onLeave(fn)` (pushes fn when typeof fn === 'function'; returns nothing), and `reload()` (calls `_resolveCurrentHash()`).
5. In `_resolveCurrentHash()` (L133-149), before `loadContent`, close any open slide-out: `if (typeof Slideout !== 'undefined' && Slideout.isOpen()) Slideout.close();` so a VM-details or My Account panel never outlives the page it belongs to.
6. Make `showError(message)` (L189-200) treat `message` as text (use `Utils.escapeHtml`, already safe after E01) and replace inline `onclick="location.reload()"` with a button that calls `ContentRouter.reload()` via a delegated handler bound once in init().
7. Export `token, isCurrent, onLeave, reload` in the return object (L217-225). Add a JSDoc block at the top of router.js describing the page contract: capture `const t = ContentRouter.token()` at the start of a loader; after every await/callback `if (!ContentRouter.isCurrent(t)) return;`; register teardown with `ContentRouter.onLeave(fn)`; namespaced delegated handlers on #content-area must be removed in onLeave.
8. realtime.js `startPolling(key, callback, interval, options)` (L56-70): keep current behaviour; when `options.pageScoped === true` call `ContentRouter.onLeave(() => stopPolling(key))` after creating the timer. Store `{ id, callback, interval, lastRun }` in `timers[key]` instead of a bare interval id and update `stopPolling`/`stopAllPolling` accordingly.
9. realtime.js: in the interval wrapper set `lastRun = Date.now()` when the callback runs. In `handleVisibilityChange` (L41-51) when the page becomes visible, run each timer's callback once if `Date.now() - lastRun >= interval` (replaces the empty `resumeAllPolling`). Delete the no-op `pauseAllPolling`.
10. app.js `refresh()` (L137-145): replace the sidebar-based reload with `ContentRouter.reload()`.
11. Create test/fixtures/mock-api.ts using `page.route` (delay = `await new Promise(r => setTimeout(r, ms)); await route.continue()`), and the lifecycle spec (see tests).

**Acceptance criteria**

- Given a handler registered with ContentRouter.onLeave on page A, when the user navigates to page B, then the handler runs exactly once before B's loader runs.
- Given a token captured on page A, when the user navigates to B, then ContentRouter.isCurrent(token) returns false.
- Given RealTime.startPolling('x', fn, 1000, { pageScoped: true }) on page A, when the user navigates away, then fn is never called again.
- Given a non-page-scoped poll, when the user navigates, then it keeps running.
- Given the tab was hidden longer than a poll interval, when it becomes visible, then the poll runs immediately once.
- Given an onLeave handler that throws, when navigating, then the next page still loads and the error is logged.

**Tests**

- test/tests/navigation/router-lifecycle.spec.ts (NEW): logs in with loginAs; uses page.evaluate to register onLeave and pageScoped polls (counter on window), navigates via sidebar link, asserts counter stops and onLeave ran once; asserts isCurrent(oldToken) === false; asserts a non-scoped poll keeps counting.
- Same spec: register a throwing onLeave and assert the next page's [data-view] or content header renders.

**Notes:** E01 contract (escapeHtml escapes quotes) is assumed done. Specs follow the existing scaffold: they need a running app and TEST_USERNAME/TEST_PASSWORD in test/.env and skip otherwise. Run .\build-frontend.ps1 if dist/ is still produced; index.html loads source files directly.

#### E02-T02 — Scope Pagination click delegation to its own container and remove it on navigation

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** H21 · **Depends on:** E02-T01

Pagination binds `click.pagination` on `.pg-btn` at the bindAncestor (#content-area for Activity Logs, All Logs, Environments, Access Requests), so a later page's pager click runs the old page's onPageChange: clicking page 1 on Users renders Activity Logs page 2 (H21).

**Files**

- `src/main/resources/static/js/ui/pagination.js` (modify) — per-container namespace and selector; onLeave unbind; aria-labels on icon-only pager buttons
- `test/tests/navigation/pagination-scope.spec.ts` (new) — cross-page pager isolation

**Steps**

1. In `renderNumbered` (L195-243) derive `const ns = 'click.pagination-' + containerSelector.replace(/[^a-zA-Z0-9_-]/g, '');` and `const delegate = containerSelector + ' .' + BTN_CLASS;`.
2. Replace L237 binding with `$(bindAncestor).off(ns).on(ns, delegate, handler)` so a handler only reacts to buttons inside its own container, even when bindAncestor is #content-area.
3. When bindAncestor differs from containerSelector, register `ContentRouter.onLeave(() => $(bindAncestor).off(ns))` once per (ancestor, ns) pair: keep a module-level `Set` of registered keys and clear the key inside the onLeave callback.
4. Inside the handler, ignore clicks when `!document.querySelector(containerSelector)` (container gone).
5. Add `aria-label` to the First/Previous/Next/Last icon buttons (L229-233: 'First page', 'Previous page', 'Next page', 'Last page'), `aria-current="page"` on the active numbered button, and `type="button"` on all pager buttons; same `type`/`aria-label` on the two buttons in `buildSimpleMarkup` (L269-274).
6. Update the doc comment (L180-194) to describe the scoped behaviour; callers in features/*.js need no change.

**Acceptance criteria**

- Given Activity Logs rendered a pager bound to #content-area, when the user opens User Management and clicks page 2 of the users pager, then only the users table changes and Activity Logs does not render.
- Given two pagers on one page, when one is clicked, then only its own onPageChange runs.
- Given a screen reader, when focus is on a pager arrow, then it announces 'Next page' etc.

**Tests**

- test/tests/navigation/pagination-scope.spec.ts (NEW, admin creds): open #/activity-logs, then #/user-management, use page.evaluate to count calls by wrapping ActivityLogs render (or assert content header text stays 'User Management' after clicking a .pg-btn); skips when fewer than 2 pages of data unless fulfillJson supplies a 25-row users response.

**Notes:** Partially covers the low-severity 'icon-only buttons without aria-label' item for pagers; the rest is E13-T10.

#### E02-T03 — Add route params and query preservation; give Environment Detail the URL #/environments/<id>

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** M26 · **Depends on:** E02-T01

The environment id is passed in memory and the hash stays #/environment-detail or #/my-environments, so refresh, Back and shared links fail (M26). normalizeHash also drops `?query`, which silently resets Cost Management's per-table pages on refresh.

**Files**

- `src/main/resources/static/js/core/router.js` (modify) — parameterised routes, hashFor(contentType, params), query(), keep ?query in normalizeHash, legacy #/environment-detail redirect
- `src/main/resources/static/js/ui/sidebar.js` (modify) — setActiveItem maps environment-detail to my-environments
- `src/main/resources/static/js/features/environments.js` (modify) — View and Back use ContentRouter.navigate; loadDetail reads params.environmentId from the route
- `src/main/resources/static/js/features/access-management.js` (modify) — openForEnvironment uses ContentRouter.navigate (Router is undefined today)
- `src/main/resources/static/js/features/my-account.js` (modify) — leaveTo('environment-detail') passes environmentId only
- `src/main/resources/static/js/features/cost-management.js` (modify) — restorePageStateFromHash uses ContentRouter.query()
- `test/tests/navigation/route-params.spec.ts` (new) — deep link, refresh, back

**Steps**

1. router.js: add `const PARAM_ROUTES = [{ contentType: 'environment-detail', pattern: /^#\/environments\/([^/?]+)$/, build: p => '#/environments/' + encodeURIComponent(p.environmentId), params: m => ({ environmentId: decodeURIComponent(m[1]) }) }];`. Remove 'environment-detail' from ROUTES (L16).
2. Change `normalizeHash` (L92-107) to return `{ path, query }`-preserving canonical form: trim trailing slashes from the path but keep `?query` untouched; only call `location.replace` when the path part changes.
3. Add `function matchRoute(hashPath)` returning `{ contentType, params }`: exact ROUTES match first, then PARAM_ROUTES regex; unknown paths fall back to dashboard.
4. `_resolveCurrentHash` (L133-149): use matchRoute; merge `_pendingParams` over route params (route params win for environmentId); legacy `#/environment-detail` with no pending environmentId redirects with `location.replace('#/my-environments')`.
5. `navigate(contentType, params)` (L68-79): if a PARAM_ROUTE exists for contentType and params.environmentId is set, use its `build`; otherwise ROUTES. Extend `hashFor(contentType, params)` the same way.
6. Add `query()` returning `Utils.parseQueryString(location.hash.split('?')[1] || '')`; export matchRoute-independent `query`.
7. sidebar.js `setActiveItem` (L153-156): `const key = contentType === 'environment-detail' ? 'my-environments' : contentType;`.
8. environments.js `bindListEvents` view handler (L802-807): replace `loadDetail(...)` with `ContentRouter.navigate('environment-detail', { environmentId: envId })`. Back button markup (L648 `onclick="Environments.loadList()"`): replace with `<a class="btn btn-sm btn-ghost" href="#/my-environments">`.
9. environments.js `loadDetail(params)` (L78-128): keep the name-lookup fallback only for `params.environmentName` without id (hidden Favorites/Recents mock submenu in index.html L136-176 still sends names).
10. access-management.js `openForEnvironment` (L1081-1087): replace the `Router` branch with `ContentRouter.navigate('access-management')`.
11. my-account.js `onAction` 'open-env' (L617-621): pass `{ environmentId: $btn.data('env-id') }`.
12. cost-management.js `restorePageStateFromHash` (L52-59): read `ContentRouter.query()`; `syncHash` (L66-77) keeps using history.replaceState (no hashchange).

**Acceptance criteria**

- Given a user with access to environment E, when they open #/environments/E directly or refresh it, then Environment Detail for E renders.
- Given the user clicks View on the Environments list, then the address bar shows #/environments/<id> and Back returns to #/my-environments.
- Given an id containing characters such as spaces, when navigating, then it is URL-encoded and decoded correctly.
- Given #/environment-detail with no id, when loaded, then the app redirects to #/my-environments instead of showing 'No environment specified'.
- Given #/cost-management?idle=2, when refreshed, then the idle table opens on page 3 (0-indexed 2).
- Given the user clicks 'Manage access' on Environment Detail, then Access Management opens with the grant modal for that environment.

**Tests**

- test/tests/navigation/route-params.spec.ts (NEW): open list, click first View, assert URL matches /#\/environments\/.+/; page.reload() and assert detail header still shows; page.goBack() returns to list; goto '#/environment-detail' redirects; goto '#/cost-management?idle=1' (admin) keeps the query after load.

**Notes:** Environment ids are opaque strings (e.g. env-1); never put display names in the URL.

#### E02-T04 — Route keyboard shortcuts through the router and ignore modified keys

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** M27 · **Depends on:** E02-T01

Shortcuts are defined as 'g e' but keys are recorded as 'ge', so none match; 'r' matches with Ctrl held and blocks Ctrl+R; 'g a' calls the legacy AuditLogs view and other shortcuts bypass the router (M27).

**Files**

- `src/main/resources/static/js/core/keyboard.js` (modify) — sequence keys without spaces, modifier guard, router navigation, single Escape owner
- `test/tests/navigation/keyboard-shortcuts.spec.ts` (new) — shortcut behaviour

**Steps**

1. Replace the `shortcuts` map (L10-18) with entries keyed by sequence without spaces plus a display label: `gd` → `ContentRouter.navigate('dashboard')`, `ge` → 'my-environments', `gr` → 'request-access', `ga` → 'activity-logs', `r` → `ContentRouter.reload()`, `?` → showShortcutsHelp. Store `{ keys: ['g','d'], action, description }` so the help table renders 'g then d'.
2. In `handleKeyDown` (L35-74) return early when `e.ctrlKey || e.metaKey || e.altKey` or `e.defaultPrevented` or `e.isComposing`; keep Shift allowed so '?' works.
3. Also return early when focus is inside `.modal.show` or an open `.slideout-panel` (shortcuts must not fire under a dialog).
4. Remove the Escape branch (L41-45) and `closeActiveModal` (L101-116): Bootstrap closes its own modals and Slideout owns Escape (E13-T03). Remove 'Escape' from the shortcut list.
5. `refreshCurrentView` (L90-96) is deleted in favour of ContentRouter.reload().
6. `showShortcutsHelp` (L121-154): build rows from the new map; use Utils.escapeHtml on descriptions.

**Acceptance criteria**

- Given focus is on the page body, when the user types g then e, then the hash becomes #/my-environments.
- Given the user presses Ctrl+R, then the browser reloads and the app does not intercept it.
- Given the user presses r alone, then the current route reloads through the router (teardown runs).
- Given focus is in an input or an open modal, when the user types g d, then nothing happens.
- Given the user types g a, then Activity Logs (ActivityLogs.loadMyActivityLogs via the router) opens, not the legacy AuditLogs view.

**Tests**

- test/tests/navigation/keyboard-shortcuts.spec.ts (NEW): page.keyboard.press('g'), press('e') → expect URL #/my-environments; type in a search input and assert no navigation; press('?') opens the help modal; register a keydown listener in page.evaluate and assert Ctrl+R event is not defaultPrevented.

#### E02-T05 — Make background polls silent: suppressGlobalError, bell through RealTime, pending badge on .pending-count

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** M32 · **Depends on:** E02-T01

The bell, dashboard and pending-badge polls raise global error toasts and a 401 from a poll redirects to login; the bell polls while hidden and starts before auth; the pending badge targets `.pending-requests-badge` which the sidebar does not use (M32).

**Files**

- `src/main/resources/static/js/core/realtime.js` (modify) — pollRequest helper with suppressGlobalError, stop global polls on 401, updatePendingBadge targets .pending-count, registers bell poll
- `src/main/resources/static/js/ui/notification-bell.js` (modify) — no own setInterval or document.ready init; refreshCount uses suppressGlobalError
- `src/main/resources/static/js/app.js` (modify) — init NotificationBell after Auth; drop duplicate loadPendingRequestsCount
- `test/tests/navigation/background-polls.spec.ts` (new) — poll errors are silent

**Steps**

1. realtime.js: add `function pollGet(url)` = `ApiClient.get(url, { suppressGlobalError: true }).fail(xhr => { if (xhr.status === 401) onSessionLost(); })`. Export it as `RealTime.pollGet` for page polls.
2. `onSessionLost()`: `stopAllPolling()` once and show a single persistent `Notifications.warning('Your session has expired. Reload the page to sign in again.', 0)`; never change location from a poll.
3. `updatePendingBadge` (L115-136): use pollGet; target `$('.pending-count')` (index.html L211) with `.text(count).toggle(count > 0)`; delete the append fallback to `.menu-text`.
4. `init()` (L23-33): keep pending polling for env admins; add `startPolling('notificationCount', () => NotificationBell.refreshCount(), 60000)` (not page scoped). Visibility pausing from E02-T01 now applies to the bell.
5. notification-bell.js: delete `pollTimer`/setInterval (L33) and the initial refreshCount call in init; remove the `$(document).ready` block (L205-207). `refreshCount` (L36-47) uses `RealTime.pollGet(Config.API.notifications.count)`; `loadNotifications`, `markOneRead`, `markAllRead` keep the global error handling (user actions).
6. app.js `init` (L14-54): call `NotificationBell.init()` after `Notifications.init()` and before `RealTime.init()`. In `updateSidebarForRole` (L89-113) replace `loadPendingRequestsCount()` with `RealTime.updatePendingBadge()` and delete `loadPendingRequestsCount` (L118-132).

**Acceptance criteria**

- Given /api/v1/notifications/count returns 500, when the 60s poll runs, then no error toast appears.
- Given the session expired, when a background poll gets 401, then the user stays on the page, all background polls stop, and one 'session expired' notice is shown.
- Given the tab is hidden, then the bell does not call the count endpoint until the tab is visible again.
- Given an env admin with 3 pending requests, when the badge poll runs, then the sidebar 'Pending Requests' badge shows 3; with 0 it is hidden.
- Given the user is not yet authenticated (login redirect), then no bell request is made.

**Tests**

- test/tests/navigation/background-polls.spec.ts (NEW): fulfillJson count endpoint with status 500 and use page.clock (Playwright >=1.45) to fast-forward 61s; assert no `.notification-toast.toast-error`; fulfill 401 and assert URL unchanged and the warning toast is shown once; for env-admin creds fulfill pendingRequests with 3 items and assert `.pending-count` text '3'.

**Notes:** Dashboard's own poll is moved to RealTime.pollGet in E02-T06.

#### E02-T06 — Migrate Dashboard and Cost Management to the router lifecycle

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H21, M32 · **Depends on:** E02-T01, E02-T05

With Auto On the dashboard re-renders over other pages within 30s and wipes its own search box; the cost page's slow Promise.all renders after the user left; both leave charts and window resize handlers alive (H21, M32).

**Files**

- `src/main/resources/static/js/features/dashboard.js` (modify) — token guards, pageScoped auto-refresh, silent poll, onLeave teardown, keep search term
- `src/main/resources/static/js/features/cost-management.js` (modify) — token guards in load/fetchAll/changePage/trigger callbacks, onLeave teardown
- `test/tests/navigation/page-takeover.spec.ts` (new) — dashboard and cost cases

**Steps**

1. dashboard.js `load()` (L14-30): `const t = ContentRouter.token();` after the await `if (!ContentRouter.isCurrent(t)) return;` before render and before showError. Register once per load: `ContentRouter.onLeave(teardown)`.
2. `teardown()`: `disposeCharts()`; `$('#content-area').off('.dashboardJump .dashboardAutoRefresh .dashboardSearch')`; `$(window).off('resize.dashboardCharts')`; dispose Bootstrap tooltips created in initTooltips (L686-695).
3. Move the module-level `$(window).on('resize.dashboardCharts', ...)` (L815) into renderDashboard so it is re-added per visit and removed in teardown.
4. `startAutoRefresh` (L710-717): `RealTime.startPolling('dashboard', silentRefresh, 30000, { immediate: false, pageScoped: true })`; delete the registerDashboardRefresh fallback. Remove the now-unused `registerDashboardRefresh`/`registerEnvironmentRefresh`/`unregister` from realtime.js only if no other caller remains (grep).
5. `silentRefresh` (L32-46): capture token, fetch with `RealTime.pollGet(Config.API.dashboard.summary)` (wrap in a Promise like fetchDashboardData), return if not current; before renderDashboard read `$('#dashboard-env-search').val()` and after render restore it and re-trigger the filter (`.trigger('input')`) so the search box is not wiped.
6. cost-management.js `load()` (L40-48): capture token; pass it to `fetchAll(t)`; in `.then` and `.catch` (L127-151) return when not current. Register `ContentRouter.onLeave(teardown)` with `disposeCharts()`, `$(window).off('resize.costManagementCharts')`, `$('#content-area').off('click.rightsizingApply')`.
7. `changePage` (L154-174) and the trigger callbacks (triggerBackfill, triggerTagReconciliation, triggerActualsIngestion, applyRightsizing: L961-1043) capture the token and skip DOM writes when not current (still show their success/failure toast).

**Acceptance criteria**

- Given Dashboard with Auto On, when the user opens Environments and waits 35 seconds, then Environments stays on screen and no dashboard summary request is made.
- Given the dashboard search box contains 'prod', when a silent refresh with changed data renders, then the box still contains 'prod' and rows are filtered.
- Given Cost Management's requests take 5 seconds, when the user navigates to Dashboard after 1 second, then Dashboard remains and no cost page renders.
- Given the user leaves Cost Management, then no ECharts instance from that page remains (echarts.getInstanceByDom on removed nodes) and window resize no longer calls its resizeCharts.

**Tests**

- test/tests/navigation/page-takeover.spec.ts (NEW): (a) enable Auto On, navigate away, page.clock.fastForward(35000), assert content header and countRequests(dashboard summary) === 0 after leaving; (b) admin: delayRoute('**/api/v1/cost-management/**', 5000), open cost page, navigate to dashboard after 500ms, wait 6s, assert dashboard view visible.

**Notes:** Also removes the chart/resize leak part of the low-severity 'charts survive navigation' item for these two pages; PNG export fix stays with the cost epic.

#### E02-T07 — Migrate Environments (list and detail) and VM operation callbacks to the router lifecycle

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H21 · **Depends on:** E02-T01, E02-T03

The environment-detail 'vm-operation-status' listener and the `.then(() => loadDetail(...))` after an operation replace whatever page is open ~3s later (the captured User Management takeover); list/detail delegated handlers on #content-area leak to other pages (H21).

**Files**

- `src/main/resources/static/js/features/environments.js` (modify) — token guards, namespaced delegated handlers, onLeave removes operation listener/timer/tooltips/handlers
- `src/main/resources/static/js/features/vm-operations.js` (modify) — status polls use suppressGlobalError; publish status unchanged
- `src/main/resources/static/js/features/locks.js` (modify) — lock-change callback guarded by token (caller passes guarded callback)
- `test/tests/navigation/page-takeover.spec.ts` (modify) — environment detail case

**Steps**

1. environments.js `loadList()` (L53-73) and `loadDetail()` (L78-128): capture `const t = ContentRouter.token()` at entry; after each await return when `!ContentRouter.isCurrent(t)` (including before showError). Register `ContentRouter.onLeave(teardownList)` / `onLeave(teardownDetail)`.
2. `bindListEvents` (L800-840): namespace every delegated handler (`click.envList`, `input.envList`) and remove them in teardownList with `$('#content-area').off('.envList')`; also dispose list tooltips (disposeEnvListTooltips L501).
3. `bindDetailEvents` (L868-981): namespace `.group-card-toggle` delegation as `click.envDetail`. teardownDetail: remove `vm-operation-status` listener (`window.removeEventListener('vm-operation-status', operationStatusHandler)`; set to null), `clearTimeout(operationRefreshTimer)`, `$('#content-area').off('.envDetail')`, `$(document).off('click.vmMetricWindow')` (L1566), `disposeAllBootstrapTooltips()`.
4. `bindOperationStatusRefresh` (L983-1012): capture the token when binding; inside the setTimeout callback return when not current; after `fetchEnvironmentDetails` return when not current.
5. Replace every `.then(() => loadDetail({ environmentId: envId }))` (L1020, 1030, 1079, 1092, 1103, 1114) with a helper `reloadDetailIfCurrent(t, envId)` that only calls loadDetail when `ContentRouter.isCurrent(t)`; capture `t` when the operation is confirmed. Same for the Locks.bindLockEvents callback (L876-878) and edit/delete `loadList()` callbacks (L815-817, L854).
6. vm-operations.js `startPolling` poll (L452-545): use `ApiClient.get(Config.API.operations.get(envId, executionId), { suppressGlobalError: true })`; the existing pollFailures logic reports failures in the progress modal. Do not page-scope operation polling: the progress modal is global and the operation must keep being tracked across navigation.

**Acceptance criteria**

- Given an operation was started on Environment Detail and the progress dialog closed, when the user opens User Management, then User Management stays on screen until the user navigates again (no Environment Detail repaint).
- Given the user is still on that environment's detail page, when the operation completes, then the detail page refreshes once with the new VM states.
- Given the user visited Environments and then opens a page with a [data-action="view"] element, when clicking it, then Environments' handler does not run.
- Given a transient 500 on the operation status poll, then no global error toast is shown and the dialog shows the retry state.

**Tests**

- page-takeover.spec.ts (extend): on Environment Detail, page.evaluate dispatches `new CustomEvent('vm-operation-status', { detail: { envId } })` then navigate to #/request-access, wait 2s, assert Request Access header visible and no #env-detail-view.
- Same spec: after visiting #/my-environments then #/user-management (admin), assert `$._data($('#content-area')[0], 'events')` has no handlers in namespace envList (via page.evaluate).

**Notes:** The single global operation poll timer (low-severity item) belongs to the VM operations epic E05; this task only changes error suppression and consumers.

#### E02-T08 — Migrate Activity Logs, All Logs and Access Requests to the router lifecycle

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** H21 · **Depends on:** E02-T01, E02-T02

These pages render after awaits without checking the user is still there and leave delegated handlers on #content-area (e.g. Access Requests' `[data-action="cancel"]` fires on any later page with that attribute) (H21).

**Files**

- `src/main/resources/static/js/features/activity-logs.js` (modify) — token guards in loadMyActivityLogs/loadActivityLogs and filter/page callbacks
- `src/main/resources/static/js/features/all-logs.js` (modify) — token guards in loadAllAuditLogs/loadAllLogs
- `src/main/resources/static/js/features/access-requests.js` (modify) — token guards in both page loaders; namespaced delegated handlers removed on leave
- `test/tests/navigation/page-takeover.spec.ts` (modify) — logs and access requests cases

**Steps**

1. activity-logs.js `loadMyActivityLogs` (L41-75): capture token; after `await Promise.all` return when not current. `loadActivityLogs` (L375-400): capture token in the caller, guard both `.then` blocks.
2. all-logs.js `loadAllAuditLogs` (L51-90) and `loadAllLogs` (L554-580): same pattern.
3. access-requests.js `loadRequestAccessPage` (L19-46) and `loadPendingRequestsPage` (L48-68): same pattern.
4. access-requests.js handlers (L478-497): change to namespaces `input.reqAccess`/`click.reqAccess` and add `ContentRouter.onLeave(() => $('#content-area').off('.reqAccess'))` where they are bound.
5. Any other `$('#content-area').on(...)` in these three files (grep) gets a page namespace and the same onLeave removal. Pagination already self-scopes after E02-T02.

**Acceptance criteria**

- Given Activity Logs requests take 5s, when the user navigates to Dashboard after 1s, then Dashboard stays.
- Given the user visited Request Access, when a later page contains a [data-action="cancel"] button and it is clicked, then no access request is cancelled.
- Given the user is on All Logs and changes page, then only All Logs re-renders.

**Tests**

- page-takeover.spec.ts (extend): delayRoute('**/api/v1/audit/**', 4000) on Activity Logs then navigate; assert dashboard; after visiting #/request-access assert no `.reqAccess` handlers remain on #content-area once on #/dashboard.

#### E02-T09 — Migrate VM Registry, System Health, My Account and the remaining admin pages; add a cross-route takeover guard test

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H21 · **Depends on:** E02-T01, E02-T08

System Health re-runs load() 2-3s after a sync trigger regardless of the current page; VM Registry, User Management, Access Management and Automation Rules render after awaits and User Management binds `[data-action="deactivate"]` etc. on document, leaking to every later page (H21).

**Files**

- `src/main/resources/static/js/features/vm-registry.js` (modify) — token guard in load/loadEnvironmentsData
- `src/main/resources/static/js/features/system-health.js` (modify) — token guard in load and the setTimeout reloads after triggers
- `src/main/resources/static/js/features/user-management.js` (modify) — token guard in fetchUsers; document handlers namespaced and removed on leave
- `src/main/resources/static/js/features/access-management.js` (modify) — token guard in fetchInitialData and reloads
- `src/main/resources/static/js/features/automation-rules.js` (modify) — token guard in fetchInitialData/refreshRules
- `src/main/resources/static/js/features/my-account.js` (modify) — reset state on slide-out close
- `src/main/resources/static/js/ui/slideout.js` (modify) — trigger 'slideout:closed' event in close()
- `test/tests/navigation/page-takeover.spec.ts` (modify) — generic all-routes guard

**Steps**

1. vm-registry.js `load()` (L33-44) / `loadEnvironmentsData()` (L132-): capture token in load and pass it; after the await return when not current.
2. system-health.js `load()` (L136-175): capture token, return after `await Promise.all` when not current. In `triggerSync` (L178-195) and `triggerEksSync` (L197-215) replace `setTimeout(() => load(), N)` with `const t = ContentRouter.token(); const id = setTimeout(() => { if (ContentRouter.isCurrent(t)) load(); }, N); ContentRouter.onLeave(() => clearTimeout(id));`.
3. user-management.js `load`/`fetchUsers` (L28-78): token guard. Handlers at L407-438 bound on `$(document)`: change to `$('#content-area')` with namespace `click.userMgmt` and remove them via onLeave.
4. access-management.js `fetchInitialData` (L71-100) and the reload paths that await (L873, L1273): token guards. Keep the existing document outside-click handler (L1176) but add onLeave `$(document).off(outsideClickNamespace)`.
5. automation-rules.js `fetchInitialData` (L47-70) and `refreshRules`: token guards.
6. slideout.js `close()` (L92-103): after hiding, `$(document).trigger('slideout:closed')`. my-account.js `init()`: on 'slideout:closed' set `state = null` so late responses (already owner-guarded) cannot re-render a stale panel.
7. Grep `features/*.js` for `$('#content-area').html(` inside `.done`, `.then`, `await` continuations or timers not covered above (audit-logs.js, features.js shims) and add the token guard; record any intentionally unguarded write in a code comment.
8. Add a generic spec: for each route in a list, delayRoute('**/api/v1/**', 3000), open the route, after 300ms navigate to #/dashboard (with dashboard summary not delayed via a more specific route), wait 3.5s and assert the dashboard header.

**Acceptance criteria**

- Given the user clicked 'Trigger State Sync' on System Health, when they open Dashboard within 3s, then Dashboard stays.
- Given the user visited User Management, when another page has a [data-action="deactivate"] element and it is clicked, then User Management's handler does not run.
- Given any routed page is still loading, when the user navigates away, then that page's response never writes to #content-area.
- Given My Account is closed while its requests are in flight, then no error or re-render occurs when they return.

**Tests**

- page-takeover.spec.ts: parametrised 'late response never takes over' test over ['my-environments','request-access','pending-requests','activity-logs','user-management','access-management','vm-registry','automation-rules','audit-logs-all','cost-management','system-health'] (admin creds; skips routes not visible to the role).

**Notes:** After this task every routed page follows the contract documented in router.js; new pages must too (add to .ai/engineering pattern guide in E14).

## E03

### Authorization hardening — Fix

Every endpoint checks both the caller's role and their rights on the specific environment, group or record in the path, and role or status changes take effect on the next request.

Sources: H3, H5, H10, H13, H16, H17, M15, M22, LOW-SEC-USERS-ID-SPEL, LOW-SEC-MAX-SESSIONS, LOW-SEC-403-TEXT, LOW-SEC-UNUSED-HELPERS, INV-ENV-ADMIN-SCOPE, LOW-INV-REGISTRY-PATH-ENV · 12 tasks · 13.5 days

#### E03-T01 — Add SecurityService assert helpers, canAdministerEnvironment and isCurrentUser; delete the unused helpers

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-SEC-UNUSED-HELPERS, INV-ENV-ADMIN-SCOPE · **Depends on:** E00-T02

Controllers repeat ad-hoc checks, five SecurityService helpers are unused and treat ENV_ADMIN inconsistently (low-severity item), and later tasks need one contract: assertCanView, assertCanOperate, assertSameEnvironment (brief contract) plus an environment-admin check.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/SecurityService.java` (modify) — add helpers; delete hasEnvironmentAccess, hasEnvironmentAccessLevel, canReviewAccessRequests, canPerformOperations (both overloads), getAccessLevel; rewrite canManageEnvironmentAccess on effectiveEnvLevel
- `src/main/resources/application.properties` (modify) — security.env-admin.scope=${SECURITY_ENV_ADMIN_SCOPE:global}
- `src/test/java/com/tcgdigital/vmcontrol/service/SecurityServiceTest.java` (new) — matrix of roles x grants for each helper

**Steps**

1. Add public void assertCanView(String envId): throw new UnauthorizedException("You do not have access to this environment") unless canViewEnvironment(envId) (UnauthorizedException already maps to 403 in GlobalExceptionHandler).
2. Add public void assertCanOperate(String envId): throw UnauthorizedException("You cannot start, stop or lock in this environment") unless canOperateInEnvironment(envId).
3. Add public void assertSameEnvironment(String actualEnvId, String pathEnvId): if actualEnvId == null or !actualEnvId.equals(pathEnvId) throw new ResourceNotFoundException("Resource not found in environment " + pathEnvId) (404, so ids in other environments are not confirmed).
4. Add @Value("${security.env-admin.scope:global}") String envAdminScope and public boolean canAdministerEnvironment(String envId): true for global ADMIN; for a user with envAdmin=true return true when envAdminScope is 'global', otherwise ("assigned") only when they hold an active ENVIRONMENT-scope ADMIN grant on envId; for other users true when their active ENVIRONMENT grant on envId is ADMIN. Implement the grant lookup with accessService.getActiveGrant(userId, AccessScopeType.ENVIRONMENT, envId) and an ordinal comparison (do not call accessService.hasAccessLevel, which compares strings alphabetically; H6 is fixed in E04). Add assertCanAdminister(String envId) throwing UnauthorizedException.
5. Rewrite canManageEnvironmentAccess(envId) to return canAdministerEnvironment(envId) (same semantics, no alphabetical comparison). canManageGroupAccess keeps resolving the group's environment and delegating.
6. Add public boolean isCurrentUser(String userId) (current user non-null and ids equal) for SpEL use as @securityService.isCurrentUser(#userId).
7. Delete the unused helpers listed (verified: no callers in src/main or src/test). Keep isAdmin/isEnvAdmin (used by EnvironmentController and AccessGrantController).
8. Document security.env-admin.scope in application.properties: 'global' (default, today's documented behaviour per docs/access-grants-requirement.md section 5) or 'assigned' (ENV_ADMIN administers only environments where they hold an ENVIRONMENT ADMIN grant, matching EnvironmentAccessService.getAdministeredEnvironmentIds).
9. SecurityServiceTest extends AbstractIntegrationTest: for admin, envAdmin (scope global and assigned, via ReflectionTestUtils with restore in @AfterEach), env ADMIN grantee, env USER grantee, group USER grantee, viewer and no-grant users assert canView/canOperate/canAdminister and the assert* exceptions; assertSameEnvironment throws ResourceNotFoundException on mismatch and null.

**Acceptance criteria**

- Given a VIEWER grant only, when assertCanOperate is called, then UnauthorizedException (403) is thrown.
- Given security.env-admin.scope=assigned and an ENV_ADMIN with no ADMIN grant on env X, when canAdministerEnvironment(X) is called, then it returns false; with scope=global it returns true.
- Given an id belonging to env B, when assertSameEnvironment(B, A) is called, then a 404 ResourceNotFoundException is thrown.

**Tests**

- SecurityServiceTest role/grant matrix.

**Notes:** Decision for the product owner: switch production to security.env-admin.scope=assigned once every ENV_ADMIN holds ADMIN grants on the environments they run (run the query in E03-T05 notes first).

#### E03-T02 — Require operate rights to acquire a lock, view rights to read it, admin rights to break it, and refuse locks on inactive environments

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** H3 · **Depends on:** E03-T01

Lock status, acquire and history only require isAuthenticated() and LockService never consults SecurityService, so a viewer with no grant can lock production and block every operator (H3, authorization part; expiry is E07).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/LockController.java` (modify) — inject SecurityService; checks in getLockStatus, acquireLock, breakLock, getLockHistory
- `src/main/java/com/tcgdigital/vmcontrol/service/LockService.java` (modify) — acquireLock rejects inactive environments (ValidationException)
- `src/test/java/com/tcgdigital/vmcontrol/controller/LockControllerSecurityTest.java` (new) — extends SecuredWebTestBase
- `src/test/java/com/tcgdigital/vmcontrol/service/LockServiceTest.java` (modify) — inactive environment case

**Steps**

1. LockController: add SecurityService to the constructor. getLockStatus and getLockHistory call securityService.assertCanView(environmentId) first. acquireLock calls securityService.assertCanOperate(environmentId). breakLock keeps @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')") and adds securityService.assertCanAdminister(environmentId). releaseLock is unchanged (LockService already restricts it to the holder).
2. LockService.acquireLock: after loading the environment, if (!Boolean.TRUE.equals(environment.getIsActive())) throw new ValidationException("Environment is inactive and cannot be locked").
3. LockControllerSecurityTest: viewer with no grant → acquire 403, status 403, history 403; viewer with VIEWER grant → status 200, acquire 403; operator with USER env grant → acquire 200; operator with USER on one group only → acquire 200 (canOperateInEnvironment includes group grants); env ADMIN grantee → break 403 unless ENV_ADMIN/ADMIN role (role gate stays); ADMIN → break 200.
4. LockServiceTest: acquire on an environment with isActive=false throws ValidationException.
5. Check the frontend lock buttons: the 403 is already surfaced through ApiClient; no UI change needed beyond the message (E13 handles the lock 403 toast wording).

**Acceptance criteria**

- Given a signed-in user with no grant on an environment, when they POST /environments/{id}/lock/acquire, then 403 is returned and no lock is created.
- Given a VIEWER-only user, when they read lock status, then 200 is returned; when they acquire, then 403.
- Given an inactive environment, when anyone acquires its lock, then 400 is returned.

**Tests**

- LockControllerSecurityTest
- LockServiceTest inactive case

**Notes:** Lock expiry and the DB guard for one active lock are E07 (H4, M1).

#### E03-T03 — Scope the environment audit log, monitoring history, start-order and access-request reads

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** H5, M15 · **Depends on:** E03-T01

GET /audit/logs/environment/{id} only needs a session (H5); monitoring VM history, state changes and drift events, the group start-order endpoint and GET /access-requests/{id} have no environment or ownership check (M15).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/AuditController.java` (modify) — getLogsForEnvironment (lines 131-145): hasAnyRole('ADMIN','ENV_ADMIN') + assertCanAdminister
- `src/main/java/com/tcgdigital/vmcontrol/controller/MonitoringController.java` (modify) — vms/{vmId}/history: group-visibility check; state-changes, drift-events, drift-events/count: hasAnyRole('ADMIN','ENV_ADMIN')
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmGroupController.java` (modify) — start-order: assertCanView + filter to visible groups
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentAccessController.java` (modify) — getAccessRequest: requester or reviewer with canManageEnvironmentAccess on the request's environment
- `src/test/java/com/tcgdigital/vmcontrol/controller/ScopedReadsSecurityTest.java` (new) — extends SecuredWebTestBase

**Steps**

1. AuditController.getLogsForEnvironment: change @PreAuthorize to "hasAnyRole('ADMIN', 'ENV_ADMIN')" and inject SecurityService to call assertCanAdminister(environmentId). The only UI caller (Config.API.audit.byEnvironment) is on admin pages.
2. MonitoringController.getVmStateHistory: load the VM via VmRepository.findById (404 if absent) and require securityService.hasGroupAccess(vm.getGroup().getGroupId()); otherwise throw UnauthorizedException.
3. MonitoringController getRecentStateChanges, getDriftEvents and countDriftEvents: change @PreAuthorize to "hasAnyRole('ADMIN', 'ENV_ADMIN')"; their only UI caller is system-health.js (admin page).
4. VmGroupController.getGroupsInStartOrder: call securityService.assertCanView(environmentId) and filter the result to securityService.getVisibleGroupIds(environmentId), as listGroups already does.
5. EnvironmentAccessController.getAccessRequest: after loading, allow when request.getRequester().getUserId() equals the current user id, or securityService.canManageEnvironmentAccess(request.getEnvironment().getEnvironmentId()); otherwise throw ResourceNotFoundException("AccessRequest", requestId) (do not confirm existence).
6. ScopedReadsSecurityTest: viewer of env A gets 403 on /audit/logs/environment/B and on /monitoring/vms/{vmInB}/history; USER on group G1 gets start-order with only G1; plain user gets 403 on /monitoring/state-changes; another user's access-request id → 404, own → 200, ENV_ADMIN → 200.

**Acceptance criteria**

- Given a viewer of one environment, when they request another environment's audit log or a VM history there, then 403 is returned.
- Given a non-admin, when they call state-changes or drift-events, then 403 is returned.
- Given someone else's access-request id, when a non-reviewer fetches it, then 404 is returned.

**Tests**

- ScopedReadsSecurityTest

#### E03-T04 — Bind group and VM registry endpoints to the {environmentId} in the path and to environment-admin rights

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-INV-REGISTRY-PATH-ENV, INV-ENV-ADMIN-SCOPE · **Depends on:** E03-T03

Registry mutation endpoints ignore the {environmentId} in the path (low-severity inventory item): updateGroup/deleteGroup/getGroup act on any group id, registerVm uses dto.groupId from any environment, update/delete/acknowledge act on any VM id; and ENV_ADMIN mutations are not limited to environments they administer.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/VmGroupController.java` (modify) — getGroup, createGroup, updateGroup, deleteGroup: assertSameEnvironment + assertCanAdminister for mutations
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — getVm, inventory, metrics, utilization reads: assertSameEnvironment; registerVm, updateVm, deleteVm, acknowledgeVm, refreshVmInventory: assertSameEnvironment + assertCanAdminister
- `src/test/java/com/tcgdigital/vmcontrol/controller/RegistryPathScopeSecurityTest.java` (new) — extends SecuredWebTestBase

**Steps**

1. Add a private helper in each controller: String envOfGroup(String groupId) → groupService.getGroupById(groupId).getEnvironment().getEnvironmentId(); String envOfVm(String vmId) → vmService.getVmById(vmId).getGroup().getEnvironment().getEnvironmentId() (both throw ResourceNotFoundException when missing).
2. VmGroupController: getGroup → securityService.assertSameEnvironment(envOfGroup(groupId), environmentId) before the existing access check. createGroup → securityService.assertCanAdminister(environmentId). updateGroup/deleteGroup → assertSameEnvironment(envOfGroup(groupId), environmentId) then assertCanAdminister(environmentId). Keep the @PreAuthorize role gates.
3. VmMgmtController: for every /{vmId} endpoint call assertSameEnvironment(envOfVm(vmId), environmentId) first. registerVm → assertSameEnvironment(envOfGroup(dto.getGroupId()), environmentId) and assertCanAdminister(environmentId). updateVm additionally rejects a dto.getGroupId() in another environment with the same helper. deleteVm, acknowledgeVm, refreshVmInventory → assertCanAdminister(environmentId).
4. Do not change service signatures; the checks live in the controllers (SecurityService cannot be injected into EnvironmentAccessService-dependent services without a cycle).
5. RegistryPathScopeSecurityTest: ADMIN PUT /environments/A/groups/{groupInB} → 404 and the group is unchanged; POST /environments/A/vms with groupId in B → 404; DELETE /environments/A/vms/{vmInB} → 404; GET /environments/A/vms/{vmInB} → 404; with security.env-admin.scope=assigned an ENV_ADMIN without an ADMIN grant on A gets 403 on createGroup in A and 201 after an ADMIN grant on A is added.

**Acceptance criteria**

- Given a group or VM id from environment B, when it is used under /environments/A/..., then 404 is returned and nothing changes.
- Given scope=assigned and an ENV_ADMIN without an ADMIN grant on A, when they create, edit or delete groups or VMs in A, then 403 is returned.

**Tests**

- RegistryPathScopeSecurityTest

**Notes:** Frontend always sends the matching environment id, so no UI change is expected; verify the VM Registry pages after the change.

#### E03-T05 — Limit environment-admin actions on environments, access, automation rules and syncs to environments the caller administers

- [ ] Done · **Type:** security · **Estimate:** 2 d · **Risk:** medium · **Sources:** INV-ENV-ADMIN-SCOPE · **Depends on:** E03-T04

ENV_ADMIN (and per-environment ADMIN grantees) mutations are gated only by role, not by the environment in the path (inventory finding), e.g. environment edit/deactivate, legacy access endpoints, request approval, automation rules and per-environment sync.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — updateEnvironment, deactivateEnvironment, reactivateEnvironment, notifyStop: assertCanAdminister(environmentId)
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentAccessController.java` (modify) — listEnvironmentAccess, grantAccess, revokeAccess, listEnvironmentAccessRequests: assertCanAdminister(environmentId); approve/deny: assertCanAdminister(request env); listPendingRequests filtered to administrable environments
- `src/main/java/com/tcgdigital/vmcontrol/controller/AutomationRuleController.java` (modify) — inject SecurityService; get/create/update/setEnabled/delete check assertCanAdminister(rule or dto environment); list filtered
- `src/main/java/com/tcgdigital/vmcontrol/controller/MonitoringController.java` (modify) — sync/environment/{id} and sync/eks/{id}: assertCanAdminister
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvAdminScopeSecurityTest.java` (new) — scope=assigned and scope=global matrices

**Steps**

1. EnvironmentController: inject usage of the existing securityService field; call assertCanAdminister(environmentId) at the top of updateEnvironment, deactivateEnvironment, reactivateEnvironment and notifyStop. createEnvironment stays role-gated (a new environment has no grants yet); after creation, when the caller is not a global ADMIN, grant them ENVIRONMENT ADMIN on it through EnvironmentAccessService.grantScoped so 'assigned' mode keeps them in control (actor = caller, initiation as an admin grant).
2. EnvironmentAccessController: call securityService.assertCanAdminister(environmentId) in listEnvironmentAccess, grantAccess, revokeAccess and listEnvironmentAccessRequests. approveRequest/denyRequest: load the request with accessService.getAccessRequest(requestId) and assertCanAdminister(request.getEnvironment().getEnvironmentId()) before delegating. listPendingRequests: filter the list to requests whose environment passes canAdministerEnvironment (one call per distinct environment id).
3. AutomationRuleController: inject SecurityService. create: assertCanAdminister(dto environment id); get/update/setEnabled/delete: read automationRuleService.getRule(ruleId).getEnvironmentId() (AutomationRuleDTO already carries it) and assertCanAdminister; update also checks the target environment if it changes. list: when environmentId is given assertCanAdminister(environmentId); when absent filter the DTOs by canAdministerEnvironment.
4. MonitoringController.syncEnvironment and triggerEksSyncForEnvironment: inject SecurityService and assertCanAdminister(environmentId). Fleet-wide endpoints (sync, sync/eks, inventory/sync, metrics/*) stay ADMIN/ENV_ADMIN role-gated; E06/E12 own their locking.
5. Add to the PR a SQL query for ops to run before switching to scope=assigned: users with env_admin=1 and their active ENVIRONMENT ADMIN grants, so missing grants can be added first.
6. EnvAdminScopeSecurityTest (SecuredWebTestBase, @TestPropertySource security.env-admin.scope=assigned): ENV_ADMIN without a grant on A → 403 on PUT /environments/A, POST /environments/A/access, approve of a request on A, POST /automation-rules for A, POST /monitoring/sync/environment/A; with an ADMIN grant on A → allowed; pending list excludes requests on B. A nested class with scope=global asserts today's behaviour is unchanged.

**Acceptance criteria**

- Given scope=assigned, when an ENV_ADMIN acts on an environment where they hold no ADMIN grant, then every environment-specific mutation returns 403.
- Given scope=global (default), when an ENV_ADMIN acts on any environment, then behaviour is unchanged.
- Given an ENV_ADMIN creates an environment in assigned mode, when it is created, then they hold an ENVIRONMENT ADMIN grant on it.

**Tests**

- EnvAdminScopeSecurityTest (assigned and global)

**Notes:** Ships with security.env-admin.scope=global (no behaviour change) so it can merge safely; switching to assigned is a configuration decision after grants are backfilled.

#### E03-T06 — Stop non-global admins from granting, extending or approving access for themselves

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** H16 · **Depends on:** E03-T05

assertCanManage checks the environment but not whether the grant is the caller's own, so an environment admin can PATCH their own grant with clearExpiry=true and become permanent (H16); the same applies to direct grants and request approval.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/AccessGrantController.java` (modify) — grant: reject target == caller; update: reject own grant (lines 97-105, assertCanManage at 115-123)
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentAccessController.java` (modify) — grantAccess: reject self; approveRequest: reject own request
- `src/test/java/com/tcgdigital/vmcontrol/controller/AccessGrantControllerSecurityTest.java` (new) — extends SecuredWebTestBase

**Steps**

1. Add a private assertNotSelf(String targetUserId) in AccessGrantController: if (!securityService.isAdmin() && securityService.isCurrentUser(targetUserId)) throw new UnauthorizedException("You cannot change your own access; ask another administrator").
2. AccessGrantController.update: after loading the grant call assertNotSelf(grant.getUser().getUserId()). revoke stays allowed (removing your own access is harmless).
3. AccessGrantController.grant: when userEmail is given, resolve it with userService.getUserByEmail(email) and call assertNotSelf on the found id (directory onboarding of oneself is impossible since the caller already exists).
4. EnvironmentAccessController.grantAccess (legacy endpoint): resolve dto's target user the same way and call the same self check (copy the helper into a small package-private AccessSelfCheck utility in controller/support if both controllers need it).
5. EnvironmentAccessController.approveRequest: load the request; if (!securityService.isAdmin() && request.getRequester().getUserId().equals(currentUserId)) throw UnauthorizedException("You cannot approve your own request"). Deny of one's own request stays allowed (equivalent to cancel).
6. AccessGrantControllerSecurityTest: env ADMIN grantee PATCH own grant clearExpiry → 403 and expiry unchanged; PATCH someone else's grant → 200; ENV_ADMIN approving own request → 403; global ADMIN doing the same → 200; POST /access-grants targeting own email → 403.

**Acceptance criteria**

- Given an environment admin who is not a global ADMIN, when they extend, clear the expiry of or change the level of their own grant, then 403 is returned and the grant is unchanged.
- Given an ENV_ADMIN with a pending request, when they approve it themselves, then 403 is returned.
- Given a global ADMIN, when they change their own grant, then it is allowed (break-glass).

**Tests**

- AccessGrantControllerSecurityTest

**Notes:** H15 (editing any grant silently makes it permanent) and M19 (extend semantics) are E04.

#### E03-T07 — Remove the raw EC2 start, stop and status endpoints

- [ ] Done · **Type:** security · **Estimate:** 0.5 d · **Risk:** low · **Sources:** H13 · **Depends on:** E00-T05

/api/ec2/instances/{id}/start|stop let ENV_ADMIN start or stop any instance in the AWS account, registered or not, with no lock, audit, registry update or active-operation check; the UI never calls them (H13).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/Ec2Controller.java` (modify) — delete startInstance (line 94), stopInstance (137), getInstanceStatus (180) endpoints
- `src/main/java/com/tcgdigital/vmcontrol/service/Ec2Service.java` (modify) — delete startInstance (44), stopInstance (153), getInstanceStatus (219) and their private helpers
- `src/main/java/com/tcgdigital/vmcontrol/dto/Ec2InstanceActionResponse.java` (delete) — unused after removal
- `src/main/java/com/tcgdigital/vmcontrol/dto/Ec2InstanceStatus.java` (delete) — unused after removal (verify with grep)
- `src/test/java/com/tcgdigital/vmcontrol/controller/Ec2ControllerTest.java` (modify) — delete start/stop/status tests (from line 279); add 404/405 assertions

**Steps**

1. Delete the three endpoint methods from Ec2Controller; keep GET /registered-ids and GET /instances (used by vm-registry.js EC2 picker).
2. Delete Ec2Service.startInstance, stopInstance, getInstanceStatus and any private helpers only they use; keep listInstances.
3. grep -rn 'Ec2InstanceActionResponse\|Ec2InstanceStatus' src; delete the DTOs if no other reference remains.
4. Make the class-level @PreAuthorize on Ec2Controller "hasAnyRole('ADMIN', 'ENV_ADMIN')" stay as is (listing instances is a registry action).
5. Update Ec2ControllerTest: remove the start/stop/status tests and add one asserting POST /api/ec2/instances/i-123/start no longer maps (404 or 405, per E00-T05 mapping).
6. Note in the PR that VM start/stop goes only through /api/v1/environments/{id}/operations (VmOperationsService), which enforces locks, audit and dependency order.

**Acceptance criteria**

- Given any user, when they POST /api/ec2/instances/{id}/start or /stop, then no AWS call is made and 404/405 is returned.
- Given the VM Registry EC2 picker, when it lists instances, then it still works.

**Tests**

- Ec2ControllerTest updated

#### E03-T08 — Stop email-based account takeover at Entra login and limit initial-admin promotion to an empty admin set

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** medium · **Sources:** H17 · **Depends on:** E00-T06

When no row matches the token's oid, findOrCreateUser adopts the row with the same email and overwrites its oid even if it already holds another one, and the initial-admin email re-promotes on every login (H17).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/UserService.java` (modify) — findOrCreateUser email fallback (line 92 onward) only adopts rows with null oid; initial-admin promotion only when countByAdminTrueAndIsActiveTrue() == 0 (lines 79, 102, 116)
- `src/main/java/com/tcgdigital/vmcontrol/security/CustomOAuth2UserService.java` (modify) — map the new conflict exception to OAuth2AuthenticationException with a clear error code
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — add logLoginConflict(email, existingUserId) (or reuse a generic security audit action if one exists)
- `src/test/java/com/tcgdigital/vmcontrol/service/UserServiceFindOrCreateTest.java` (modify) — takeover and promotion cases

**Steps**

1. In the email fallback branch: if userByEmail is present and its azureAdObjectId is non-null and differs from the token oid, do not link. Throw a new AccountConflictException (exception package, RuntimeException) with the user id; log WARN and audit it. Only when azureAdObjectId is null link it as today.
2. In the first branch (oid match) keep the email refresh logic but skip it when another row holds the email (already handled).
3. Initial admin: replace the three promotion blocks with one private method maybePromoteInitialAdmin(User user, String email) that promotes only when initialAdminEmail matches AND userRepository.countByAdminTrueAndIsActiveTrue() == 0; audit the promotion via auditService.logUserRoleChanged("system", userId, "admin", true).
4. CustomOAuth2UserService.loadUser: catch AccountConflictException and throw new OAuth2AuthenticationException(new OAuth2Error("account_conflict"), "This email belongs to a different directory account. Contact an administrator."). The login page already shows a failure on /login?error.
5. UserServiceFindOrCreateTest: row with oid X and email e; login with oid Y and email e → AccountConflictException and the row's oid remains X; row with null oid and email e → linked to Y; initial-admin email login when another active admin exists → not promoted; when no admins → promoted; a demoted initial admin logging in again → stays demoted.

**Acceptance criteria**

- Given an existing user linked to Entra oid X, when a different Entra account Y with the same email signs in, then sign-in fails with account_conflict and no row changes.
- Given app.initial-admin-email is set and at least one active admin exists, when that user signs in, then their admin flag is not changed.

**Tests**

- UserServiceFindOrCreateTest new cases

**Notes:** A legitimate leaver/joiner re-use now needs an admin to clear the old row's oid or deactivate it; document this in the PR.

#### E03-T09 — Reload the user on every request, refuse inactive users, add an absolute session lifetime and drop the ineffective maximumSessions

- [ ] Done · **Type:** security · **Estimate:** 2 d · **Risk:** high · **Sources:** H10, LOW-SEC-MAX-SESSIONS · **Depends on:** E03-T08, E01-T02

Role authorities are copied into the session at login, getCurrentUser never checks isActive, the bell poll keeps sessions alive and there is no session lifetime, so demoted or deactivated users keep their rights (H10); maximumSessions(1) never matches OIDC principals (low-severity item).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/security/CurrentUserRefreshFilter.java` (new) — OncePerRequestFilter rebuilding authorities from the DB
- `src/main/java/com/tcgdigital/vmcontrol/security/CustomOAuth2UserService.java` (modify) — extract buildAuthorities (lines 132-150) into a public static RoleAuthorities.forUser(User) used by both
- `src/main/java/com/tcgdigital/vmcontrol/security/RoleAuthorities.java` (new) — ROLE_USER + ROLE_ADMIN/ROLE_ENV_ADMIN from User flags
- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — addFilterBefore(CurrentUserRefreshFilter, AuthorizationFilter.class); remove maximumSessions(1)/maxSessionsPreventsLogin (lines 76-77)
- `src/main/java/com/tcgdigital/vmcontrol/service/UserService.java` (modify) — getCurrentUser returns null for inactive users (lines 341-379)
- `src/main/resources/application.properties` (modify) — security.session.absolute-timeout=${SECURITY_SESSION_ABSOLUTE_TIMEOUT:PT12H}; server.servlet.session.timeout=${SESSION_IDLE_TIMEOUT:60m}
- `src/test/java/com/tcgdigital/vmcontrol/security/CurrentUserRefreshFilterTest.java` (new) — extends SecuredWebTestBase

**Steps**

1. Create RoleAuthorities.forUser(User u): Set<GrantedAuthority> with ROLE_USER, plus ROLE_ENV_ADMIN when u.isEnvAdmin(), plus ROLE_ADMIN when u.isAdmin(). Use it in CustomOAuth2UserService.buildAuthorities (keep the OIDC token's own authorities) and in AuthController (E01-T02 token).
2. Create CurrentUserRefreshFilter(UserRepository repo, Duration absoluteTimeout). For each request with an authenticated Authentication: resolve the user (OidcUser → repo.findByAzureAdObjectId(oid); String principal → repo.findById). If not found or !isActive: invalidate the session, clear the SecurityContextHolder, and for /api/** write the same 401 JSON as the entry point, otherwise redirect to /login?expired. If found: compute fresh authorities; if they differ from authentication.getAuthorities() (ignoring OIDC_*/SCOPE_* authorities, which are kept), replace the context authentication: for OAuth2AuthenticationToken build new DefaultOidcUser(fresh+oidc authorities, idToken, userInfo) and new OAuth2AuthenticationToken(user, authorities, registrationId); for the password token build UsernamePasswordAuthenticationToken.authenticated(id, null, fresh). Save it to the session via HttpSessionSecurityContextRepository so later requests start current.
3. Absolute lifetime: on first authenticated request store session attribute auth.issuedAt (epoch millis) if absent (set it at login in AuthController and in an AuthenticationSuccessHandler for oauth2Login: .successHandler wrapping the existing defaultSuccessUrl("/home", true) behaviour via SavedRequestAwareAuthenticationSuccessHandler with alwaysUseDefaultTargetUrl). When now - issuedAt > absoluteTimeout, treat as expired (same as inactive path).
4. Register in EntraidSecurityConfig: http.addFilterBefore(new CurrentUserRefreshFilter(userRepository, absoluteTimeout), AuthorizationFilter.class). Inject UserRepository and @Value("${security.session.absolute-timeout:PT12H}") Duration.
5. Remove .maximumSessions(1).maxSessionsPreventsLogin(false) from sessionManagement (it never matched OIDC principals because DefaultOidcUser has no stable equals); keep sessionCreationPolicy(IF_REQUIRED) and add .sessionFixation(f -> f.changeSessionId()).
6. UserService.getCurrentUser: after resolving, return null when the user is not active (callers already treat null as unauthenticated; getCurrentUserId throws UnauthorizedException).
7. CurrentUserRefreshFilterTest: session established as ADMIN (MockMvc with a MockHttpSession and oidcLogin), then set admin=false in the DB, next GET /api/v1/users → 403; deactivate the user, next GET /api/v1/users/me → 401 and session invalidated; promote a USER to ADMIN in the DB, next ADMIN-only call → 200 without re-login; a session whose auth.issuedAt is older than the timeout → 401.

**Acceptance criteria**

- Given an open session of a user who is demoted, when they call an ADMIN-only endpoint, then 403 is returned on the very next request.
- Given an open session of a deactivated user, when any request arrives, then the session is invalidated and 401 (API) or a login redirect (page) is returned.
- Given a session older than security.session.absolute-timeout, when a request arrives (including the bell poll), then it is rejected as expired.

**Tests**

- CurrentUserRefreshFilterTest

**Notes:** Adds one indexed user lookup per authenticated request (azure_ad_object_id is looked up today by getCurrentUser anyway). Roll out with the idle timeout first if session expiry complaints are a concern.

#### E03-T10 — Turn on CSRF protection for the API, secure the session cookie behind nginx and bind dev security to the dev profile

- [ ] Done · **Type:** security · **Estimate:** 1.5 d · **Risk:** high · **Sources:** M22 · **Depends on:** E03-T09, E01-T09

CSRF is ignored for /api/** while auth is a session cookie; behind nginx the cookie lacks Secure and there is no HSTS; and DefaultSecurityConfig (matchIfMissing=true) permits everything and accepts an X-User-Id header on any box with ENTRAID_ENABLED=false (M22; session-id rotation on password login is done in E01-T02).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — csrf with CookieCsrfTokenRepository.withHttpOnlyFalse() and CsrfTokenRequestAttributeHandler; no /api/** ignore; HSTS
- `src/main/java/com/tcgdigital/vmcontrol/security/CsrfCookieFilter.java` (new) — touches the deferred CsrfToken so the XSRF-TOKEN cookie is always issued
- `src/main/resources/static/js/core/api-client.js` (modify) — send X-XSRF-TOKEN header (read from the XSRF-TOKEN cookie) on POST/PUT/PATCH/DELETE
- `src/main/resources/static/js/login.js` (modify) — send the header on the password login POST
- `src/main/java/com/tcgdigital/vmcontrol/security/DefaultSecurityConfig.java` (modify) — @Profile({"dev","test"}) in addition to entraid.enabled=false; X-User-Id filter only in those profiles
- `src/main/java/com/tcgdigital/vmcontrol/security/SecurityModeGuard.java` (new) — fails startup when entraid.enabled=false outside dev/test profiles
- `src/main/resources/application-prod.properties` (modify) — server.forward-headers-strategy=native, server.servlet.session.cookie.secure=true, ...same-site=lax, ...http-only=true
- `src/main/resources/application-dev.properties` (new) — marks the dev profile (entraid default false is set via .env); document in README
- `src/test/java/com/tcgdigital/vmcontrol/security/CsrfSecurityTest.java` (new) — extends SecuredWebTestBase
- `src/test/java/com/tcgdigital/vmcontrol/security/SecurityModeGuardTest.java` (new) — ApplicationContextRunner cases

**Steps**

1. EntraidSecurityConfig: replace .csrf(csrf -> csrf.ignoringRequestMatchers(...)) with csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()).csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()); add .addFilterAfter(new CsrfCookieFilter(), BasicAuthenticationFilter.class). Add headers.httpStrictTransportSecurity(h -> h.includeSubDomains(true).maxAgeInSeconds(31536000)) inside the SecurityHeaders customizer from E01-T09.
2. api-client.js request(): for methods other than GET/HEAD read document.cookie XSRF-TOKEN (decodeURIComponent) and set ajaxOptions.headers = { 'X-XSRF-TOKEN': token }. template-loader.js only issues GETs. The cost-management Excel export links are GETs and unaffected.
3. login.js: include the X-XSRF-TOKEN header on the POST (only relevant if password login is ever enabled under the Entra chain; harmless otherwise).
4. application-prod.properties: server.forward-headers-strategy=native (deployment/nginx/conf.d/default.conf already sets X-Forwarded-Proto https and X-Forwarded-For), server.servlet.session.cookie.secure=true, server.servlet.session.cookie.same-site=lax, server.servlet.session.cookie.http-only=true.
5. DefaultSecurityConfig: add @Profile({"dev", "test"}) next to the existing @ConditionalOnProperty, and change matchIfMissing to false. Create SecurityModeGuard (@Component, @ConditionalOnProperty(name="entraid.enabled", havingValue="false")) that in @PostConstruct throws IllegalStateException("entraid.enabled=false is only allowed with the dev or test profile") unless Environment.acceptsProfiles(Profiles.of("dev","test")).
6. Make sure test runs keep working: AbstractIntegrationTest uses @ActiveProfiles("test") (E00-T01); tests that send X-User-Id (LockControllerIntegrationTest, VmOperationsControllerIntegrationTest) run under the test profile. Update README/CLAUDE.md run instructions: local runs need SPRING_PROFILES_ACTIVE=dev when ENTRAID_ENABLED=false.
7. CsrfSecurityTest: asAdmin() POST /api/v1/environments without a token → 403; with .with(csrf()) → 201; a GET response sets the XSRF-TOKEN cookie; SecurityModeGuardTest: context with entraid.enabled=false and no profile fails to start, with profile dev starts.

**Acceptance criteria**

- Given a cross-site page posting to an /api/** endpoint with the victim's cookie, when no X-XSRF-TOKEN header matches, then 403 is returned.
- Given production behind nginx, when a session cookie is set, then it has Secure, HttpOnly and SameSite=Lax, and responses carry HSTS.
- Given a non-dev deployment with ENTRAID_ENABLED=false, when the app starts, then startup fails instead of permitting all requests.

**Tests**

- CsrfSecurityTest
- SecurityModeGuardTest
- manual: every mutating UI action still works (token header sent)

**Notes:** Deploy together with the frontend bundle (E01 build-frontend output); a stale cached app.js without the header would get 403 on every mutation, so bump the ?v= cache-bust value in index.html.

#### E03-T11 — Fix the GET /users/{id} self-access expression and make 403 messages truthful

- [ ] Done · **Type:** security · **Estimate:** 0.5 d · **Risk:** low · **Sources:** LOW-SEC-USERS-ID-SPEL, LOW-SEC-403-TEXT · **Depends on:** E03-T01

GET /users/{id} compares the app UUID with the Entra oid, so non-admins never read their own record and password sessions get a 500; every 403 says "Required role: ADMIN or ENV_ADMIN" even on ADMIN-only endpoints (low-severity items).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/UserController.java` (modify) — @PreAuthorize("hasRole('ADMIN') or @securityService.isCurrentUser(#userId)") at line 153
- `src/main/java/com/tcgdigital/vmcontrol/controller/GlobalExceptionHandler.java` (modify) — AccessDeniedException message (line 140) generic
- `src/test/java/com/tcgdigital/vmcontrol/controller/UserControllerSecurityTest.java` (new) — extends SecuredWebTestBase

**Steps**

1. UserController.getUserById: change the expression to hasRole('ADMIN') or @securityService.isCurrentUser(#userId) (bean name securityService is the default for SecurityService).
2. GlobalExceptionHandler.handleAccessDenied: message "You do not have permission to perform this action." (matches EntraidSecurityConfig's accessDeniedHandler text). UnauthorizedException keeps its specific message.
3. UserControllerSecurityTest: USER GET /api/v1/users/{ownId} → 200; another user's id → 403 with the generic message; ADMIN → 200 for any id; the 403 body never contains 'Required role'.

**Acceptance criteria**

- Given a non-admin, when they GET their own /api/v1/users/{userId}, then 200 is returned; for someone else 403.
- Given any AccessDeniedException, when returned, then the message does not claim a specific role.

**Tests**

- UserControllerSecurityTest

#### E03-T12 — Sign the user out of Microsoft Entra ID on logout

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** medium · **Sources:** LOW-SEC-LOGOUT-ENTRA · **Depends on:** E03-T10

Logout clears only the app session (HomeController /logout + SecurityContextLogoutHandler), so the Entra session survives and "Sign in with Entra ID" logs the same person straight back in without a prompt, which is a risk on shared machines. Code-review low item: logout does not sign out of Entra.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — logout(): use OidcClientInitiatedLogoutSuccessHandler with postLogoutRedirectUri {baseUrl}/login?logout=true; logout via POST /logout
- `src/main/java/com/tcgdigital/vmcontrol/controller/HomeController.java` (modify) — remove the GET /logout handler (Spring LogoutFilter owns /logout)
- `src/main/resources/static/js/core/auth.js` (modify) — logout() submits a POST form to /logout with the CSRF token instead of navigating with GET
- `src/main/resources/application-prod.properties` (modify) — document that the Entra app registration needs the front-channel logout / post-logout redirect URI
- `src/test/java/com/tcgdigital/vmcontrol/security/EntraLogoutTest.java` (new) — MockMvc tests for the logout flow

**Steps**

1. In EntraidSecurityConfig, inject ClientRegistrationRepository and build an OidcClientInitiatedLogoutSuccessHandler with setPostLogoutRedirectUri("{baseUrl}/login?logout=true").
2. Replace logoutSuccessUrl("/login?logout=true") with logoutSuccessHandler(handler); keep invalidateHttpSession(true), clearAuthentication(true) and deleteCookies("JSESSIONID").
3. Remove the GET /logout mapping from HomeController and "/logout" from the permitAll list if it is only needed for GET.
4. In auth.js logout(), create and submit a hidden form (method POST, action Config.AUTH.logoutUrl || "/logout") carrying the CSRF token from E03-T10; keep RealTime/VmOperations stopAllPolling before submitting.
5. In the dev profile (DefaultSecurityConfig) keep the plain local logout so password/dev login still works.
6. Add the post-logout redirect URI to the Entra app registration (document the exact value in application-prod.properties comments and docs/).

**Acceptance criteria**

- Given an Entra-signed-in user, when they click Logout, then the app session is invalidated and the browser is redirected to the Entra end_session endpoint and then back to /login?logout=true.
- Given the user has logged out, when they click Sign in with Entra ID, then Entra asks them to pick or enter an account instead of signing them in silently.
- Given a GET request to /logout, when it is sent cross-site, then it does not log the user out (405 or 404).
- Given dev mode (entraid.enabled=false), when the user logs out, then they return to the login page without any call to Entra.

**Tests**

- EntraLogoutTest: POST /logout with CSRF token as an OIDC user returns a redirect whose Location starts with the provider end_session endpoint and carries post_logout_redirect_uri and id_token_hint.
- EntraLogoutTest: GET /logout does not invalidate the session.
- EntraLogoutTest: POST /logout without a CSRF token is rejected (403).

**Notes:** Requires the Entra app registration to allow the post-logout redirect URI; coordinate with whoever administers the tenant before deploying. Ship together with the frontend bundle that sends POST logout.

## E04

### Access & grants correctness — Fix

Grants, requests and expiry behave exactly as admins and requesters expect: no 500s from stale indexes, correct level checks, bounded durations, true extensions, race-free reviews and an honest My Account view.

Sources: C4, H6, H15, M18, M19, M20, M21, LOW-ACC-1, LOW-ACC-2, LOW-ACC-3, LOW-ACC-4 · 9 tasks · 9 days

#### E04-T01 — Drop the V1 unique index on environment_access and expire grants one row per transaction

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** C4 · **Depends on:** E00-T01, E00-T03

C4: V1 created UNIQUE idx_environment_access_env_user(environment_id, user_id, status), which V20 says must not exist; it makes an env grant + group grant in the same environment, a second revoke, and any collision in the nightly expiry batch fail with a 500 / whole-batch rollback.

**Files**

- `src/main/resources/db/migration/V<next>__drop_environment_access_env_user_unique.sql` (new) — DROP INDEX idx_environment_access_env_user ON environment_access
- `src/main/java/com/tcgdigital/vmcontrol/service/AccessExpiryProcessor.java` (new) — @Service with @Transactional(propagation = REQUIRES_NEW) expireOne(String accessId)
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — processExpiredAccess() (line ~747) loops ids and calls the processor per row, catching per-row failures
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentAccessRepository.java` (modify) — add findExpiredAccessIds(Timestamp now) returning List<String>
- `src/test/java/com/tcgdigital/vmcontrol/FlywayMigrationTest.java` (modify) — assert index is gone
- `src/test/java/com/tcgdigital/vmcontrol/service/AccessExpiryIntegrationTest.java` (new) — non-@Transactional integration test for per-row expiry
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessServiceTest.java` (modify) — grant/revoke history regression tests

**Steps**

1. Create the migration (next free V number at implementation time) containing only: DROP INDEX idx_environment_access_env_user ON environment_access; Add a header comment referencing V1:70 and V20:23. The FKs stay covered: environment_id by idx_environment_access_env, user_id by idx_environment_access_user (both from V1) - confirm with SHOW INDEX FROM environment_access on a local MySQL before merging.
2. In EnvironmentAccessRepository add @Query("SELECT ea.accessId FROM EnvironmentAccess ea WHERE ea.status = 'ACTIVE' AND ea.expiresAt IS NOT NULL AND ea.expiresAt <= :now") List<String> findExpiredAccessIds(@Param("now") Timestamp now).
3. Create AccessExpiryProcessor (separate bean so the REQUIRES_NEW proxy applies). expireOne(accessId): load by id with user+environment (findById is fine, LAZY loads happen inside the tx); if status != ACTIVE or expiresAt is null or in the future return false (another node or an extension won); set status EXPIRED, save, log; register the ACCESS_EXPIRED notification with AfterCommit.run (E00-T03) wrapped in try/catch, using the same arguments as today (scope label: move EnvironmentAccessService.scopeLabel to a package-private static helper or duplicate the small lookup via VmGroupRepository); return true.
4. Rewrite EnvironmentAccessService.processExpiredAccess(): remove @Transactional; for each id from findExpiredAccessIds(now) call processor.expireOne(id) inside try/catch(Exception) -> log.warn with the id and continue; return the number of rows actually expired.
5. Leave processExpiringAccessWarnings() as is here (E04-T05 changes its de-duplication).
6. Keep AccessExpirationScheduler unchanged (E06 wraps every scheduler in runLocked later).

**Acceptance criteria**

- Given a user with an active ENVIRONMENT grant on env A, when an admin grants the same user a GROUP grant on a group in env A, then both rows are ACTIVE and the call returns 200.
- Given a user whose grant on env A was revoked once and later re-granted, when the admin revokes it again, then the revoke succeeds and two REVOKED rows exist.
- Given three expired ACTIVE grants where processing the second throws, when processExpiredAccess runs, then the first and third are EXPIRED, the second stays ACTIVE and is retried next run.
- Given the migration has run, when information_schema.statistics is queried, then no index named idx_environment_access_env_user exists on environment_access.

**Tests**

- FlywayMigrationTest: query information_schema.statistics for idx_environment_access_env_user -> count 0.
- EnvironmentAccessServiceTest: grantScoped env + group in same env both ACTIVE; revoke -> grant -> revoke same user/env succeeds.
- AccessExpiryIntegrationTest (extends AbstractIntegrationTest, NOT @Transactional because REQUIRES_NEW cannot see uncommitted test data; clean up in @AfterEach): two expired grants -> both EXPIRED; a grant extended between id-load and expireOne is left ACTIVE.
- EnvironmentAccessServiceExpiryUnitTest (Mockito): processor mock throws for id #2 -> ids #1 and #3 still processed, return value 2.

**Notes:** Migration is a pure DROP INDEX; rollback = recreate the index only if no duplicate (environment_id,user_id,status) rows exist, which after this change they will. Ship together with the per-row job.

#### E04-T02 — Compare access levels by rank, not alphabetically, in EnvironmentAccessRepository

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** H6 · **Depends on:** E00-T01

H6: findByUserWithMinAccessLevel and hasAccessLevel use ea.accessLevel >= :minLevel on a VARCHAR enum name, so 'USER' and 'VIEWER' sort after 'ADMIN' and every level counts as ADMIN; env admins with VIEWER get admin emails, weekly reports and My Account says they administer the environment.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/model/AccessLevel.java` (modify) — add static List<AccessLevel> atLeast(AccessLevel min)
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentAccessRepository.java` (modify) — lines 104-113 and 210-221: use ea.accessLevel IN :levels; keep old names as default methods
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessServiceTest.java` (modify) — level-rank tests
- `src/test/java/com/tcgdigital/vmcontrol/service/MyAccountServiceTest.java` (modify) — administered list excludes VIEWER/USER envs

**Steps**

1. AccessLevel: add public static List<AccessLevel> atLeast(AccessLevel min) { return Arrays.stream(values()).filter(l -> l.hasAtLeast(min)).toList(); } (ordinal order VIEWER < USER < ADMIN is already relied on by hasAtLeast).
2. Repository: rename the JPQL query at line 104 to findByUserWithAccessLevelIn(userId, @Param("levels") Collection<AccessLevel> levels, now) with 'AND ea.accessLevel IN :levels'. Add default List<EnvironmentAccess> findByUserWithMinAccessLevel(String userId, AccessLevel minLevel, Timestamp now) { return findByUserWithAccessLevelIn(userId, AccessLevel.atLeast(minLevel), now); } so callers (EnvironmentAccessService.getAdministeredEnvironmentIds:677, NotificationService.resolveAdministeringEnvAdmins:464) and the NotificationServiceTest stub keep compiling.
3. Same for hasAccessLevel (line 210): JPQL hasAccessLevelIn(... Collection<AccessLevel> levels ...) plus default hasAccessLevel(environmentId, userId, requiredLevel, now) delegating with atLeast(requiredLevel).
4. grep the repository package for any other '>= :' comparison on accessLevel and fix the same way (none expected besides these two).
5. EnvironmentAccessService.hasAccessLevel (ordinal compare in Java) is already correct; leave it.

**Acceptance criteria**

- Given an env-admin user holding only VIEWER on DEV, when getAdministeredEnvironmentIds runs, then DEV is not returned, My Account 'You administer' does not list DEV, and they receive no DEV ACCESS_EXPIRING/weekly-report emails as an administering env admin.
- Given a user with USER on DEV, when repository hasAccessLevel(DEV, user, USER) and (DEV, user, ADMIN) are called, then they return true and false respectively.
- Given a user with ADMIN on DEV, when findByUserWithMinAccessLevel(user, ADMIN) runs, then DEV is returned.

**Tests**

- EnvironmentAccessServiceTest: matrix VIEWER/USER/ADMIN x min VIEWER/USER/ADMIN for both repository queries against real MySQL (existing test at line 115 updated).
- MyAccountServiceTest: profile.administered excludes an env where the user is VIEWER.
- NotificationServiceTest: still green with the default-method stub.

**Notes:** WeeklyReportService.java:105/281 and MyAccountService.java:81 are fixed transitively through getAdministeredEnvironmentIds.

#### E04-T03 — Bound durationDays on every grant and request body and validate review bodies

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** M18 · **Depends on:** E00-T02, E00-T05

M18: no @Min/@Max on durationDays and approve/deny bodies are not @Valid; -1 creates an already-expired grant, 5,000 days overflows MySQL TIMESTAMP (2038) with a 500, and requesters can ask for 3,650 days.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/CreateAccessRequestDTO.java` (modify) — @Min(1) @Max(AccessDurations.HARD_MAX_DAYS) on durationDays (line 25)
- `src/main/java/com/tcgdigital/vmcontrol/dto/GrantAccessDTO.java` (modify) — same on durationDays (line 22)
- `src/main/java/com/tcgdigital/vmcontrol/dto/AccessGrantRequestDTO.java` (modify) — same on durationDays (line 50)
- `src/main/java/com/tcgdigital/vmcontrol/dto/UpdateAccessGrantDTO.java` (modify) — same on durationDays (line 14)
- `src/main/java/com/tcgdigital/vmcontrol/dto/ReviewAccessRequestDTO.java` (modify) — same on durationDays (line 13)
- `src/main/java/com/tcgdigital/vmcontrol/dto/OnboardUserDTO.java` (modify) — same on InitialGrant.durationDays (line 111)
- `src/main/java/com/tcgdigital/vmcontrol/dto/AccessDurations.java` (new) — public static final int HARD_MAX_DAYS = 1825
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentAccessController.java` (modify) — @Valid on approve (line 303) and deny (line 331) bodies
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — configured maxima: access.grant.max-duration-days / access.request.max-duration-days
- `src/main/resources/application.properties` (modify) — add the two properties
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentAccessControllerTest.java` (modify) — 400 cases

**Steps**

1. Add dto/AccessDurations with HARD_MAX_DAYS = 1825 (5 years keeps expires_at well inside TIMESTAMP range) and a short comment why.
2. Annotate every durationDays listed in files with @Min(value = 1, message = "durationDays must be at least 1") and @Max(value = AccessDurations.HARD_MAX_DAYS, message = ...). Ensure OnboardUserDTO.initialGrant is annotated @Valid so the nested constraint fires.
3. EnvironmentAccessController: change approveRequest/denyRequest params to @Valid @RequestBody(required = false) ReviewAccessRequestDTO dto.
4. application.properties: access.grant.max-duration-days=${ACCESS_GRANT_MAX_DURATION_DAYS:365} and access.request.max-duration-days=${ACCESS_REQUEST_MAX_DURATION_DAYS:180}.
5. EnvironmentAccessService: inject both with @Value; in createAccessRequest throw ValidationException("You can request at most N days") when dto.durationDays > request max; in grantAccess, grantScoped, updateGrant and approveRequest (reviewer override) throw when > grant max. Expose getters so the UI config endpoint (MyProfileDTO already carries extensionWindowDays) can show them - add requestMaxDurationDays to MyProfileDTO and MyAccountService.getProfile.
6. Frontend: in my-account.js EXTENSION_DAYS and in access-requests.js approve modal, drop options above the configured max (read from the profile / hard-code 365 for admins until E29 settings).

**Acceptance criteria**

- Given POST /environments/{id}/access-requests with durationDays=-1, when submitted, then 400 with a field error on durationDays and no request row is created.
- Given POST /access-requests/{id}/approve with durationDays=5000, when submitted, then 400 (not 500) and the request stays PENDING.
- Given access.request.max-duration-days=180, when a requester asks for 365 days, then 400 'You can request at most 180 days'.
- Given a valid 30-day approval, when submitted, then 200 and expires_at is about now+30 days.

**Tests**

- EnvironmentAccessControllerTest: -1, 0, 5000 on create-request, approve, grant and PATCH grant -> 400 with consistent error body (E00-T05).
- EnvironmentAccessServiceTest: request over configured max -> ValidationException; grant over grant max -> ValidationException; boundary values accepted.
- UserControllerTest: onboard with initialGrant.durationDays=0 -> 400.

**Notes:** Defaults chosen to match the existing UI options (approve modal max 365; My Account extension options).

#### E04-T04 — Make request review race-free and move access notifications after commit

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** LOW-ACC-1, LOW-ACC-2 · **Depends on:** E00-T01, E00-T03, E00-T05

Low items: two reviewers can approve the same request at once (no @Version / conditional update), and several notification calls in approve/deny/revoke are unwrapped, so a notification failure (or a repository exception marking the tx rollback-only) rolls back the approve or revoke.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentAccessRequestRepository.java` (modify) — add @Modifying transitionFromPending(...) conditional update
- `src/main/java/com/tcgdigital/vmcontrol/exception/ConflictException.java` (new) — RuntimeException for 409
- `src/main/java/com/tcgdigital/vmcontrol/controller/GlobalExceptionHandler.java` (modify) — map ConflictException to 409 with the standard body
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — approveRequest/denyRequest/cancelRequest use the conditional update; all notifications + access-granted automation via AfterCommit
- `src/test/java/com/tcgdigital/vmcontrol/service/AccessReviewConcurrencyIntegrationTest.java` (new) — two threads approve the same request
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessServiceTest.java` (modify) — notification failure does not roll back

**Steps**

1. EnvironmentAccessRequestRepository: add @Modifying(clearAutomatically = true, flushAutomatically = true) @Query("UPDATE EnvironmentAccessRequest r SET r.status = :newStatus, r.reviewedBy = :reviewer, r.reviewedAt = :now, r.reviewDecisionNotes = :notes, r.updatedAt = :now WHERE r.requestId = :id AND r.status = com.tcgdigital.vmcontrol.model.AccessRequestStatus.PENDING") int transitionFromPending(...). For cancel pass reviewer = null.
2. Add exception/ConflictException(String message) and a GlobalExceptionHandler @ExceptionHandler returning 409 in the same shape as LockAlreadyHeldException's handler (line 54).
3. approveRequest: replace request.approve()+save with rows = transitionFromPending(id, APPROVED, reviewer, now, notes); if rows == 0 throw new ConflictException("This request was already reviewed"); then re-read the request (findById) and continue with applyGrant. Same for denyRequest (DENIED) and cancelRequest (CANCELLED), keeping the existing ownership check for cancel before the update.
4. Wrap every user-facing side effect in EnvironmentAccessService with AfterCommit.run(() -> runNotificationSideEffect(...)): approveRequest (notifyAccessRequestApproved, line 229), denyRequest (262), revokeGrantById (416), revokeAccess (617), notifyGrantOutcome (447/449), createAccessRequest (154), processExpiringAccessWarnings (780), and fireAccessGrantedAutomation (455). Capture ids/names into final locals before the lambda (no lazy entity access after commit).
5. Do the same in LockService? No - lock side effects are handled in E07-T01; leave LockService untouched here.

**Acceptance criteria**

- Given one PENDING request, when two reviewers approve it at the same moment, then exactly one call returns 200, the other returns 409 'This request was already reviewed', and exactly one ACTIVE grant and one ACCESS_GRANTED audit row exist.
- Given NotificationService.notifyAccessRequestApproved throws, when a reviewer approves, then the response is 200, the request is APPROVED and the grant is ACTIVE.
- Given notifyAccessRevoked throws, when an admin revokes a grant, then the grant is REVOKED.
- Given a request already DENIED, when a reviewer approves it, then 409.

**Tests**

- AccessReviewConcurrencyIntegrationTest (AbstractIntegrationTest, not @Transactional): two threads with a CountDownLatch call approveRequest; assert one success + one ConflictException; count grants = 1.
- EnvironmentAccessServiceTest with @MockitoSpyBean/@MockBean NotificationService throwing RuntimeException: approve, deny, revoke still commit (use TestTransaction or a non-transactional variant to observe commit).
- Unit test: AfterCommit callbacks are registered (side effects not invoked before commit).

**Notes:** Chose a conditional update over @Version: no migration and the existing entity flow stays. If E00-T05 already introduced a generic 409 exception, reuse it instead of ConflictException.

#### E04-T05 — Extend from the later of now and current expiry and re-warn after every extension

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** M19 · **Depends on:** E04-T04

M19: approving 'extend by N days' sets expiry to now+N, so a late approval shortens the extension; expiry warnings de-duplicate on the grant id, which an extension keeps, so no warning precedes the new expiry.

**Files**

- `src/main/resources/db/migration/V<next>__add_access_request_extension_flag.sql` (new) — ALTER TABLE environment_access_request ADD COLUMN is_extension BOOLEAN NOT NULL DEFAULT FALSE
- `src/main/java/com/tcgdigital/vmcontrol/model/EnvironmentAccessRequest.java` (modify) — boolean extension field + getter/setter
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — createAccessRequest sets extension; GrantSpec gets extendFromCurrent; applyExpiry (line 521) uses max(now, current)
- `src/main/java/com/tcgdigital/vmcontrol/repository/NotificationRepository.java` (modify) — existsByUserIdAndTypeAndEntityTypeAndEntityIdAndCreatedAtAfter
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyAccessExpiring (line 240) takes a dedupSince timestamp
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentAccessRequestDTO.java` (modify) — expose extension
- `src/main/resources/static/js/features/my-account.js` (modify) — extension form shows the resulting new expiry date
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessServiceTest.java` (modify) — extension math tests
- `src/test/java/com/tcgdigital/vmcontrol/service/NotificationServiceTest.java` (modify) — re-warn after extension

**Steps**

1. Migration (next free V number): add is_extension BOOLEAN NOT NULL DEFAULT FALSE to environment_access_request.
2. EnvironmentAccessRequest: @Column(name = "is_extension", nullable = false) private boolean extension; getter isExtension()/setter.
3. createAccessRequest (line 121-132): when an active grant exists and passes isExtendable, call request.setExtension(true) before save.
4. GrantSpec: add final boolean extendFromCurrent; fromApprovedRequest passes request.isExtension(); all other factories pass false.
5. applyExpiry: if durationDays != null: LocalDateTime base = now; if (spec.extendFromCurrent && access.getExpiresAt() != null && access.getExpiresAt().toLocalDateTime().isAfter(now)) base = access.getExpiresAt().toLocalDateTime(); setExpiresAt(Timestamp.valueOf(base.plusDays(durationDays))). Direct admin grants keep 'N days from today' semantics (labelled as such in E04-T06).
6. NotificationRepository: add boolean existsByUserIdAndTypeAndEntityTypeAndEntityIdAndCreatedAtAfter(String userId, NotificationType type, String entityType, String entityId, Timestamp since).
7. NotificationService.notifyAccessExpiring: add parameter Timestamp dedupSince; use a new createIfAbsentSince(...) that checks the new repository method instead of createIfAbsent. In EnvironmentAccessService.processExpiringAccessWarnings pass dedupSince = expiresAt minus expiryWarningDays (the start of the current warning window). Because extensions are at least 1 day and the default window is 1 day, the old warning always predates the new window.
8. EnvironmentAccessRequestDTO.fromEntity: add boolean extension.
9. my-account.js extendFormHtml (line 386): under the select add a live line 'New expiry if approved: <date>' computed as max(now, grant.expiresAt) + days; update it in the existing 'input change' handler (line 580).

**Acceptance criteria**

- Given a grant expiring in 6 days and a 7-day extension request, when it is approved 5 days later, then the new expiry is the old expiry + 7 days (not approval time + 7).
- Given a grant that already expired-but-not-yet-flipped when its extension is approved, then the new expiry is approval time + N days.
- Given a user was warned for expiry E1 and the grant is extended to E2, when the warning job runs inside E2's window, then a second ACCESS_EXPIRING notification is created.
- Given the job runs twice inside the same window, then only one warning exists for that window.
- Given a non-extension request (no active grant), when approved with 30 days, then expiry = approval time + 30 days.

**Tests**

- EnvironmentAccessServiceTest: extension request sets is_extension; approval math for future expiry, past expiry, and non-extension.
- NotificationServiceTest: notifyAccessExpiring with dedupSince before/after an existing notification's createdAt.
- FlywayMigrationTest: column exists with default false.

**Notes:** The is_extension flag is reused by E04-T07 (reviewer view) and E04-T08 (My Account EXTENDED events).

#### E04-T06 — Keep the current expiry by default when editing a grant

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** H15 · **Depends on:** E01, E04-T05

H15: the edit modal resets Duration to 'Permanent' and sends clearExpiry=true when nothing is chosen, so editing only the notes of a 30-day grant makes it permanent.

**Files**

- `src/main/resources/static/js/features/access-management.js` (modify) — #grant-duration options (line ~446), showGrantAccessModal (line 1052), handleGrantAccess (line 1292-1326)
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessServiceTest.java` (modify) — lock in server-side keep-expiry behaviour

**Steps**

1. Change the select options to: <option value="keep" data-edit-only>Keep current expiry</option>, <option value="permanent">Permanent (no expiry)</option>, 7/30/90/180/365 labelled 'N days from today'.
2. Add under the select a <div class="form-text" id="grant-current-expiry"></div>.
3. showGrantAccessModal: when editing, show the 'keep' option, select it, and set #grant-current-expiry text to 'Currently expires <date>' or 'Currently permanent' from editAccess.expiresAt (use Utils.escapeHtml/Utils.html from E01). When creating, hide/remove the 'keep' option and select 'permanent' (today's default for a new grant).
4. handleGrantAccess: compute durationDays = /^\d+$/.test(v) ? parseInt(v,10) : null and clearExpiry = (v === 'permanent'). Editing body: { accessLevel, durationDays, clearExpiry: clearExpiry || null, notes }. Creating body: unchanged except durationDays from the new parsing.
5. Run build-frontend.ps1 to refresh dist/ (per CLAUDE.md).

**Acceptance criteria**

- Given a grant expiring in 30 days, when an admin opens Edit, changes only the notes and saves, then the PATCH body has durationDays=null and clearExpiry=null and the grant still expires on the same date.
- Given the edit modal, when it opens, then 'Keep current expiry' is selected and the current expiry date is shown.
- Given the admin picks 'Permanent', when saved, then clearExpiry=true is sent and expires_at becomes null.
- Given the admin picks '30 days from today', when saved, then expires_at = now + 30 days.

**Tests**

- EnvironmentAccessServiceTest: updateGrant with only notes keeps expiresAt; with clearExpiry=true clears it; with durationDays=30 sets now+30.
- Manual QA checklist in the PR: the four acceptance cases in the browser.

**Notes:** No JS test harness exists in the repo; frontend behaviour is verified manually.

#### E04-T07 — Show reviewers scope, duration and current access, and confirm every approval

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** M20 · **Depends on:** E01, E04-T03, E04-T05

M20: pending views hide scope, requested duration and whether it is an extension, and Access Management's Approve is one click with no notes or duration override, so a 3,650-day or group request is approved blind.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentAccessRequestDTO.java` (modify) — add currentAccessLevel, currentExpiresAt
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentAccessController.java` (modify) — toRequestDtos (line 64) fills current access for PENDING requests
- `src/main/java/com/tcgdigital/vmcontrol/dto/ReviewAccessRequestDTO.java` (modify) — add Boolean clearExpiry (approve with no expiry)
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — approveRequest accepts clearExpiry; GrantSpec.fromApprovedRequest passes it
- `src/main/resources/static/js/features/access-requests.js` (modify) — pending table columns (line 403-432); showApproveModal(request, onDone) exported
- `src/main/resources/static/js/features/access-management.js` (modify) — buildPendingRow (line 705) shows scope/duration/current; Approve opens AccessRequests.showApproveModal
- `src/main/resources/static/css/features/access-requests.css` (modify) — styles for scope chip / extension pill
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentAccessControllerTest.java` (modify) — DTO fields

**Steps**

1. EnvironmentAccessRequestDTO: add AccessLevel currentAccessLevel and Timestamp currentExpiresAt (+ getters/setters).
2. toRequestDtos: for each PENDING request call accessService.getActiveGrant(requesterId, scopeType, scopeId) and set the two fields (lists are small; one query per pending row is acceptable - note it in a comment).
3. ReviewAccessRequestDTO: add Boolean clearExpiry. approveRequest(requestId, reviewer, notes, durationDays, clearExpiry): pass clearExpiry into GrantSpec.fromApprovedRequest (replace the hard-coded false at line 587). Controller passes dto.getClearExpiry().
4. access-requests.js buildPendingRequestsPageHtml: add columns Scope (Whole environment / Group <scopeName>), Duration ('N days requested' / 'No end date requested'), Current access ('None' or '<LEVEL> until <date>' with an 'Extension' pill when req.extension). Use Utils.html for all interpolations.
5. Refactor showApproveModal(requestId) to showApproveModal(request, onDone): header summary (requester, environment, scope, level, requested duration, current access); select with options 'As requested (N days)' (default, sends durationDays=null), 7/30/90/180/365 (capped by the configured max from E04-T03), 'No expiry' (sends clearExpiry=true); notes textarea; on success call onDone(). Export it in the public API (line 998). Update bindPendingRequestsEvents to look up the request object by id.
6. access-management.js: buildPendingRow shows the same scope/duration/current-access info; the approve click handler (line 967) finds the request in pendingRequests and calls AccessRequests.showApproveModal(request, refreshAfterApprove) where refreshAfterApprove holds the existing refresh code from handleApproveRequest (line 1395-1414); delete the one-click POST.
7. Map a 409 from E04-T04 to the toast 'Someone else already reviewed this request' and refresh the list.
8. Run build-frontend.ps1.

**Acceptance criteria**

- Given a pending GROUP request for 'web' in DEV for 30 days by a user who holds USER on it until next week, when a reviewer opens Pending Requests or Access Management, then the row shows 'Group web', '30 days requested', 'USER until <date>' and an Extension pill.
- Given a reviewer clicks Approve in Access Management, then a dialog with the summary, a duration choice and notes opens and nothing is approved until Confirm.
- Given the reviewer chooses 'No expiry', when confirmed, then the grant has expires_at = null even though the request asked for 30 days.
- Given the reviewer leaves 'As requested', when confirmed, then expiry follows E04-T05 rules for N requested days.

**Tests**

- EnvironmentAccessControllerTest: GET pending returns currentAccessLevel/currentExpiresAt/extension for an extension request and nulls for a new request.
- EnvironmentAccessServiceTest: approve with clearExpiry=true on a request with durationDays=30 -> expiresAt null.
- Manual QA: both screens, keyboard focus lands in the dialog, Escape cancels.

**Notes:** The approve modal's current 'Never expires' option actually means 'as requested' (null falls back to request.durationDays); this task fixes the label/semantics.

#### E04-T08 — My Account: keep the original granter, show updates and extensions, and request the same scope again

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** LOW-ACC-3, LOW-ACC-4 · **Depends on:** E04-T05, E04-T07

Low items: My Account 'Access granted by' shows whoever last edited the grant (applyGrant overwrites grantedBy), extensions never appear as events, and 'Request again' on a group grant opens a whole-environment request.

**Files**

- `src/main/resources/db/migration/V<next>__add_environment_access_last_modified.sql` (new) — last_modified_by_user_id VARCHAR(36) NULL (FK app_user), last_modified_at TIMESTAMP NULL
- `src/main/java/com/tcgdigital/vmcontrol/model/EnvironmentAccess.java` (modify) — lastModifiedBy (ManyToOne LAZY), lastModifiedAt
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — applyGrant (line 477-486): do not overwrite grantedBy; set lastModifiedBy/At
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentAccessDTO.java` (modify) — lastModifiedByUserName, lastModifiedAt
- `src/main/java/com/tcgdigital/vmcontrol/service/MyAccountService.java` (modify) — getActivity: EXTENDED for approved extension requests, UPDATED for direct edits
- `src/main/resources/static/js/features/my-account.js` (modify) — grantCardHtml (line 350) 'Updated by'; activity labels; request-again passes scope (line 453, 621)
- `src/main/resources/static/js/features/access-requests.js` (modify) — showRequestAccessModal(envId, envName, preset) preselects scope/group/level
- `src/main/resources/static/css/features/my-account.css` (modify) — style for updated line if needed
- `src/test/java/com/tcgdigital/vmcontrol/service/MyAccountServiceTest.java` (modify) — events
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessServiceTest.java` (modify) — grantedBy preserved

**Steps**

1. Migration (next free V number): ALTER TABLE environment_access ADD COLUMN last_modified_by_user_id VARCHAR(36) NULL, ADD COLUMN last_modified_at TIMESTAMP NULL, ADD CONSTRAINT fk_env_access_last_modified_by FOREIGN KEY (last_modified_by_user_id) REFERENCES app_user(user_id).
2. EnvironmentAccess: add the two mapped fields with getters/setters.
3. applyGrant existing branch: remove access.setGrantedBy(spec.actor); add access.setLastModifiedBy(spec.actor); access.setLastModifiedAt(now). New grants leave them null.
4. EnvironmentAccessDTO.fromEntity: map lastModifiedByUserName (display name fallback email) and lastModifiedAt.
5. MyAccountService.getActivity: when building the APPROVED item for a request with r.isExtension() emit event 'EXTENDED' instead of 'APPROVED'; for grants with initiation DIRECT and lastModifiedAt != null add an 'UPDATED' item (actor = lastModifiedBy, occurredAt = lastModifiedAt). Note that findRecentGrantsByUser orders by grantedAt - add a second repository query findRecentlyModifiedGrantsByUser(userId, Pageable) ordered by lastModifiedAt DESC where lastModifiedAt IS NOT NULL.
6. my-account.js: grantCardHtml shows 'Granted by X' (original) and, when lastModifiedAt is set, ' · Updated by Y on <date>'. Add activity labels/icons for EXTENDED and UPDATED next to the existing event label map.
7. Request again: endedSectionHtml adds data-scope-type, data-group-id and data-level; onAction 'request-again' calls AccessRequests.showRequestAccessModal(envId, envName, { scopeType, groupId, accessLevel }).
8. access-requests.js showRequestAccessModal: accept optional preset; in onShow, if preset.scopeType === 'GROUP' check #reqScopeGroup, trigger change, and after the groups load select preset.groupId; if preset.accessLevel set #accessLevel.
9. Run build-frontend.ps1.

**Acceptance criteria**

- Given Alice granted Bob access and Carol later changed only the notes, when Bob opens My Account, then the card says 'Granted by Alice' and 'Updated by Carol on <date>'.
- Given Bob's extension request was approved, when he opens My Account activity, then an 'Extended' event with the reviewer's name appears.
- Given Bob's ended grant was on group 'web' in DEV, when he clicks Request again, then the request dialog opens with 'One group' and 'web' preselected and the same level.
- Given an existing grant row from before the migration, then it shows 'Granted by <stored granter>' and no 'Updated' line.

**Tests**

- EnvironmentAccessServiceTest: updateGrant by a different actor keeps grantedBy and sets lastModifiedBy/At.
- MyAccountServiceTest: EXTENDED event for an approved extension request; UPDATED event for a direct edit; ordering by occurredAt.
- FlywayMigrationTest: new columns exist and are nullable.

**Notes:** Historical rows already overwritten cannot be repaired; the audit log keeps the true history.

#### E04-T09 — List inactive users in User Management so Reactivate is reachable

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** M21 · **Depends on:** E00-T02

M21: user-management.js fetchUsers never sends includeInactive, so the Inactive filter is always empty while the onboarding error tells admins to reactivate from the list.

**Files**

- `src/main/resources/static/js/config.js` (modify) — add API.users.listAll = `${API_BASE_URL}/users?includeInactive=true`
- `src/main/resources/static/js/features/user-management.js` (modify) — fetchUsers (line 55) uses listAll; default status filter 'active'
- `src/test/java/com/tcgdigital/vmcontrol/controller/UserControllerTest.java` (modify) — includeInactive=true returns inactive users; default excludes them

**Steps**

1. config.js users block (line 34): add listAll.
2. user-management.js fetchUsers: ApiClient.get(Config.API.users.listAll).
3. Default currentStatusFilter to 'active' on first load so the default view is unchanged; the Inactive option now shows deactivated users and the stats row 'Total' counts all.
4. After reactivateUser/deactivateUser success, call fetchUsers() (already the case - verify) so the row moves between filters.
5. Run build-frontend.ps1.

**Acceptance criteria**

- Given a deactivated user, when an admin selects Status = Inactive, then the user is listed with a Reactivate button.
- Given the admin clicks Reactivate, then the user moves to Active without a page reload.
- Given the page first loads, then only active users are shown (same as today).

**Tests**

- UserControllerTest: GET /users?includeInactive=true as ADMIN includes an inactive user; GET /users excludes it.
- Manual QA: filter + reactivate flow.

## E05

### VM operations correctness — Fix

Every start/stop/restart touches only the path environment's VMs, runs in the right dependency order, reports an honest final status, survives restarts and concurrent writers, and can be cancelled from the UI by the right people.

Sources: H1, H2, H11, H12, H14, M8, M9, M10, LOW-OPS-403-TOAST, LOW-OPS-POLL-TIMER, LOW-OPS-DEACTIVATED-DEP, LOW-OPS-RESIZE, LOW-OPS-CONTINUE-ON-FAILURE, LOW-OPS-RESTART-ORDER · 9 tasks · 13.5 days

#### E05-T01 — Bind operation targets, estimates and execution GET/cancel to the path environment; restrict cancel to initiator, lock holder or admins

- [ ] Done · **Type:** security · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H1, H11 · **Depends on:** E00-T01, E00-T02, E03-T01

resolveTargetVms accepts vmIds/groupIds from any environment, so a user can stop VMs in a locked environment B by posting to A (H1); GET and cancel load executions by id alone and cancel has no initiator/lock-holder check (H11).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — resolveTargetVms (L858-897) rejects foreign VMs/groups; getOperationEstimate (L243-378) validates groupId/vmId; new getExecutionForEnvironment(envId, execId); cancelExecution(envId, execId, userId) with actor check
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmOperationsController.java` (modify) — getOperation (L134-147) and cancelOperation (L188-202) pass environmentId through; estimates validated
- `src/test/java/com/tcgdigital/vmcontrol/controller/VmOperationsControllerIntegrationTest.java` (modify) — cross-environment and cancel-permission cases
- `src/test/java/com/tcgdigital/vmcontrol/service/VmOperationsServiceTest.java` (modify) — foreign vmIds/groupIds rejected

**Steps**

1. In VmOperationsService.resolveTargetVms: for dto.vmIds, load each VM with vmRepository.findById; if missing OR vm.getGroup().getEnvironment().getEnvironmentId() differs from environment.getEnvironmentId(), throw ResourceNotFoundException("VM", vmId) (404, do not reveal the other environment). Keep the existing inactive-VM skip.
2. For dto.groupIds: load each group with groupRepository.findById; if missing or group.getEnvironment().getEnvironmentId() differs, throw ResourceNotFoundException("VmGroup", groupId) before loading its VMs.
3. In getOperationEstimate: when vmId is given, load the VM and call securityService.assertSameEnvironment(vm.getGroup().getEnvironment().getEnvironmentId(), environmentId); same for groupId via the group's environment. Remove the 'vm != null ? ... : vmId' fallback (unknown id is now 404).
4. Add public OperationExecution getExecutionForEnvironment(String environmentId, String executionId): load via executionRepository.findByIdWithEnvironment (L56-57), 404 if absent, then securityService.assertSameEnvironment(execution.getEnvironment().getEnvironmentId(), environmentId). Make getExecutionWithDetails take (environmentId, executionId) and use it.
5. Change cancelExecution signature to (String environmentId, String executionId, String userId). After loading via getExecutionForEnvironment, allow when userId equals execution.getInitiatedByUserId(), OR lockService.getCurrentLock(environmentId) holder equals userId, OR securityService.isAdmin(), OR securityService.hasEnvironmentAccessLevel(environmentId, AccessLevel.ENV_ADMIN); otherwise throw UnauthorizedException("Only the person who started this operation, the lock holder or an admin can cancel it").
6. Controller: getOperation calls operationsService.getExecutionWithDetails(environmentId, executionId); cancelOperation calls cancelExecution(environmentId, executionId, userId). Keep the existing canViewEnvironment/canOperateInEnvironment checks (or replace them with E03-T01 assertCanView/assertCanOperate if E03 already did so).
7. Write an audit entry for cancel: reuse auditService.logEnvironmentAction(userId, AuditAction.OPERATION_CANCELLED (already exists, unused), envId, envName, "operation", executionId, operationType, "Cancelled").

**Acceptance criteria**

- Given a user with USER on A and B, when they POST groupIds of a group in B to /environments/A/operations, then the response is 404 and no execution row is created.
- Given a user with access to A only, when they GET /environments/A/operations/{execution of B}, then the response is 404.
- Given a time-estimate request for A with vmId of a VM in B, then the response is 404.
- Given an execution started by user X with no lock held, when user Y (USER, not admin, not lock holder) cancels it, then the response is 403 and the execution keeps running; when X or an ADMIN cancels, then it is CANCELLED and an audit row exists.

**Tests**

- VmOperationsControllerIntegrationTest (extends SecuredWebTestBase): cross-environment POST 404; cross-environment GET/cancel 404; cancel by non-initiator USER 403; by initiator 200; by ADMIN 200; by lock holder 200.
- VmOperationsServiceTest: resolveTargetVms rejects foreign vmIds and groupIds; estimate rejects foreign vmId/groupId.

**Notes:** Uses SecurityService.assertSameEnvironment from E03-T01 (404 on mismatch). No migration.

#### E05-T02 — Make the execution state machine race-free: @Version on OperationExecution, conditional status transitions, atomic counters, skipped count, cancel during PENDING, all-failed = FAILED

- [ ] Done · **Type:** fix · **Estimate:** 2 d · **Risk:** medium · **Sources:** M8 · **Depends on:** E05-T01

executeOperation sets IN_PROGRESS without checking CANCELLED, any failure yields PARTIAL_SUCCESS even when nothing succeeded, and skipped steps are counted as failures (M8). The brief assigns @Version on OperationExecution to E05.

**Files**

- `src/main/resources/db/migration/V<next>__operation_execution_version_and_skipped.sql` (new) — ALTER TABLE operation_execution ADD version BIGINT NOT NULL DEFAULT 0, ADD skipped_targets INT NOT NULL DEFAULT 0
- `src/main/java/com/tcgdigital/vmcontrol/model/OperationExecution.java` (modify) — @Version Long version; Integer skippedTargets + getter/setter
- `src/main/java/com/tcgdigital/vmcontrol/repository/OperationExecutionRepository.java` (modify) — transitionStatus(...) and incrementCounters(...) @Modifying queries
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — executeOperation (L437-545), cancelExecution (L388-412), updateExecutionCounters (L899-908), skipDetail (L564-572), markExecution* (L910-999)
- `src/main/java/com/tcgdigital/vmcontrol/dto/OperationExecutionDTO.java` (modify) — expose skippedTargets
- `src/test/java/com/tcgdigital/vmcontrol/service/VmOperationsServiceTest.java` (modify) — state machine cases

**Steps**

1. Migration (next free V number at implementation time): add `version BIGINT NOT NULL DEFAULT 0` and `skipped_targets INT NOT NULL DEFAULT 0` to operation_execution.
2. OperationExecution: add `@Version @Column(name = "version", nullable = false) private Long version = 0L;` and `skippedTargets` (column skipped_targets) with getter/setter.
3. OperationExecutionRepository: add `@Modifying(clearAutomatically = true) @Transactional @Query("UPDATE OperationExecution e SET e.status = :to, e.completedAt = :completedAt, e.errorMessage = COALESCE(:error, e.errorMessage), e.version = e.version + 1 WHERE e.executionId = :id AND e.status IN :from") int transitionStatus(id, List<String> from, String to, Timestamp completedAt, String error)` (status values are the lower-case strings OperationExecution stores, e.g. 'pending', 'in_progress').
4. Add `@Modifying @Transactional @Query("UPDATE OperationExecution e SET e.completedTargets = e.completedTargets + :c, e.failedTargets = e.failedTargets + :f, e.skippedTargets = e.skippedTargets + :s WHERE e.executionId = :id") int incrementCounters(id, int c, int f, int s)`. Replace the synchronized read-modify-write updateExecutionCounters(executionId, success) with calls to incrementCounters; skipDetail increments skipped (not failed).
5. executeOperation: replace `execution.setStatus(IN_PROGRESS); save` with `transitionStatus(id, [pending], in_progress, null, null)`; if it returns 0, log 'cancelled before start' and return without touching details.
6. cancelExecution: replace the entity save with transitionStatus(id, [pending, in_progress], cancelled, now, "Cancelled by user: " + userId); if 0 rows, throw ValidationException("Operation already finished"). Then mark pending details cancelled as today.
7. Replace markExecutionCompleted / markExecutionPartialSuccess with one finishExecution(executionId): reload counters; status = COMPLETED if failed==0 && skipped==0; FAILED if completed==0 (all steps failed or skipped); otherwise PARTIAL_SUCCESS. Apply with transitionStatus(id, [in_progress], <status>, now, <message>) and only run the audit/notification side effects when it returned 1. For FAILED use auditService.logOperationFailed / notifyOperationFailedForEnvironment with message "All N steps failed" (N = total).
8. markExecutionFailed (used by executeOperationAsync catch): use transitionStatus(id, [pending, in_progress], failed, ...) so it never overwrites CANCELLED.
9. OperationExecutionDTO.fromEntity*: add skippedTargets.

**Acceptance criteria**

- Given an execution still PENDING, when it is cancelled before the async worker starts, then the worker does not set IN_PROGRESS, no VM is touched and the final status is CANCELLED.
- Given 12 VMs whose provider calls all fail, when the run ends, then the status is FAILED (not PARTIAL_SUCCESS) and failedTargets=12.
- Given 3 VMs where one fails and its two dependents are skipped, then completedTargets=0, failedTargets=1, skippedTargets=2 and status is FAILED; given one success plus one failure, status is PARTIAL_SUCCESS.
- Given a cancel arrives while a wave finishes, then the final status stays CANCELLED and no 'completed' notification is sent.

**Tests**

- VmOperationsServiceTest (AbstractIntegrationTest, mocked CloudProviderService): cancel-in-PENDING; all-failed → FAILED; mixed → PARTIAL_SUCCESS; skipped counted separately; cancel race leaves CANCELLED.
- OperationExecutionRepository test: transitionStatus returns 0 when status not in 'from' and bumps version when it updates.

**Notes:** Bulk JPQL updates bump version explicitly so any stale entity save elsewhere fails fast with ObjectOptimisticLockingFailureException.

#### E05-T03 — Order STOP in reverse dependency order, run RESTART as stop-then-start phases, honour continueOnFailure, and ignore deactivated dependencies

- [ ] Done · **Type:** fix · **Estimate:** 2 d · **Risk:** medium · **Sources:** H2, LOW-OPS-RESTART-ORDER, LOW-OPS-CONTINUE-ON-FAILURE, LOW-OPS-DEACTIVATED-DEP · **Depends on:** E05-T02

The same dependency order is used for every operation, so Stop All stops the DB before the app and skips dependents with 'failed to start' (H2). Low items: RESTART runs in start order, continueOnFailure is ignored, a deactivated VM-level dependency blocks starts forever.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/DependencyValidator.java` (modify) — operation-aware buildScopedDependencyMap/orderForExecution (L178-275); validateLiveDependencies (L216-248) ignores inactive deps
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — startOperation detail creation (L128-174), RESTART two-phase details, executeVmOperation uses detail action (L598-713), skip messages (L564-572), continueOnFailure in wave loop
- `src/main/java/com/tcgdigital/vmcontrol/dto/StartOperationDTO.java` (modify) — continueOnFailure default true + javadoc
- `src/test/java/com/tcgdigital/vmcontrol/service/DependencyValidatorTest.java` (modify) — reverse order cases
- `src/test/java/com/tcgdigital/vmcontrol/service/VmOperationsServiceTest.java` (modify) — stop order, restart phases, continueOnFailure

**Steps**

1. DependencyValidator: add `buildScopedDependencyMap(List<Vm> vms, OperationType op)`. For START keep today's map (vm -> in-scope prerequisites). For STOP invert it: vm -> in-scope VMs that depend on it (its dependents must stop first). Keep the one-arg method delegating with START so other callers are unchanged.
2. Add `orderForExecution(List<Vm> vms, OperationType op)` that runs the existing Kahn-style loop over the map from step 1; keep the one-arg version delegating with START.
3. validateLiveDependencies: skip a VM-level dependency whose Vm has isActive=false (log at debug) instead of throwing; group dependencies already use active-only findByGroupId.
4. VmOperationsService.startOperation: for START and STOP build details from orderForExecution(targetVms, op) and buildScopedDependencyMap(orderedVms, op); detail.action = "start"/"stop".
5. RESTART: create two details per VM. First all 'stop' details in STOP order with STOP-map dependencies; then all 'start' details in START order where start(v) depends on stop(v) plus start(d) for each in-scope prerequisite d. totalTargets = number of details. Run validateLiveDependencies for START and RESTART.
6. executeVmOperation: choose the provider call from detail.getAction() ("start"→startVm, "stop"→stopVm) instead of the execution OperationType; delete the RESTART branch with Thread.sleep(5000) (L633-646). Stage labels follow the detail action.
7. skipDetail: message depends on the blocked detail's action — start: "Skipped: dependency '<name>' did not start"; stop: "Skipped: '<name>' depends on this VM and did not stop".
8. continueOnFailure: change the StartOperationDTO default to true (today's effective behaviour; the UI never sends it). In the wave loop, if continueOnFailure is false and any detail in the finished wave failed, mark every remaining pending detail skipped with "Skipped: an earlier step failed and continue-on-failure is off" and stop the loop. Remove the 'retained for API compatibility' javadoc.

**Acceptance criteria**

- Given app VMs depending on a DB group, when Stop All runs, then every app VM reaches a terminal state before the DB stop is attempted.
- Given the app stop fails, when Stop All runs, then the DB VM is skipped with "'app' depends on this VM and did not stop" and is left running.
- Given RESTART on app+DB, then the order is stop app, stop DB, start DB, start app.
- Given continueOnFailure=false and the first wave has a failure, then no later wave runs and the remaining steps are SKIPPED.
- Given VM A depends on VM B and B is deactivated, when A is started, then the start is not rejected for B.

**Tests**

- DependencyValidatorTest: STOP ordering reverses START for VM-level and group-level edges; orderForExecution(START) unchanged.
- VmOperationsServiceTest: stop order via a mock provider recording call order; failed app stop skips DB with the stop message; restart call sequence; continueOnFailure=false halts; inactive dependency ignored.

**Notes:** Out of scope: blocking a STOP of a dependency whose out-of-scope dependents are still running (product decision; mention in release notes).

#### E05-T04 — Add @Version to Vm and VmRepository.updateStatusIfCurrent; write VM status and EKS metadata with targeted updates

- [ ] Done · **Type:** fix · **Estimate:** 2 d · **Risk:** high · **Sources:** H14 · **Depends on:** E05-T03, E00-T05

executeVmOperation saves a VM loaded before the stop, overwriting the EKS node-group sizes the provider saved on another copy, so node groups come back with one node (H14). The brief makes E05 own @Version on Vm and the updateStatusIfCurrent contract.

**Files**

- `src/main/resources/db/migration/V<next>__vm_version.sql` (new) — ALTER TABLE vm ADD version BIGINT NOT NULL DEFAULT 0
- `src/main/java/com/tcgdigital/vmcontrol/model/Vm.java` (modify) — @Version Long version
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — updateStatusIfCurrent, updateMetadata, markTagsSynced, findStatusById
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — markVmTransitioning (L728-732) and final writes (L661-680) use targeted updates
- `src/main/java/com/tcgdigital/vmcontrol/service/EksCloudProviderService.java` (modify) — saveScalingConfigToMetadata (L361-397) uses updateMetadata; startVm restores desiredSize
- `src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java` (modify) — applySyncedStatus (L268-343) status writes via updateStatusIfCurrent
- `src/main/java/com/tcgdigital/vmcontrol/service/TagReconciliationService.java` (modify) — markTagged (L162-167) uses markTagsSynced
- `src/main/java/com/tcgdigital/vmcontrol/controller/GlobalExceptionHandler.java` (modify) — ObjectOptimisticLockingFailureException -> 409
- `src/test/java/com/tcgdigital/vmcontrol/service/EksCloudProviderServiceTest.java` (modify) — metadata survives stop/start

**Steps**

1. Migration (next free V): `ALTER TABLE vm ADD COLUMN version BIGINT NOT NULL DEFAULT 0;`. Vm: `@Version @Column(name = "version", nullable = false) private Long version = 0L;`.
2. VmRepository: `@Modifying(clearAutomatically = true) @Transactional @Query("UPDATE Vm v SET v.status = :newStatus, v.lastStateSyncAt = :syncedAt, v.updatedAt = :syncedAt, v.version = v.version + 1 WHERE v.vmId = :vmId AND v.status = :expectedStatus") int updateStatusIfCurrent(@Param String vmId, @Param VmStatus expectedStatus, @Param VmStatus newStatus, @Param Timestamp syncedAt);` plus `@Query("SELECT v.status FROM Vm v WHERE v.vmId = :vmId") Optional<VmStatus> findStatusById(String vmId)`, `updateMetadata(vmId, metadata)` (sets metadata, updatedAt, version+1) and `markTagsSynced(List<String> vmIds, Timestamp at)`.
3. VmOperationsService: add private boolean writeStatus(String vmId, VmStatus newStatus) that reads findStatusById and calls updateStatusIfCurrent, retrying up to 3 times when it returns 0. Use it in markVmTransitioning and for the final status / restore-previous-status writes; never call vmRepository.save(vm) on the copy loaded at the start of executeVmOperation. recordStateChange keeps receiving the loaded vm for history only.
4. EksCloudProviderService.saveScalingConfigToMetadata: replace vm.setMetadata + vmRepository.save with vmRepository.updateMetadata(vm.getVmId(), metaJson), merging into any existing metadata JSON keys rather than replacing them. startVm: read both minSize and desiredSize (new readSavedScaling helper next to readSavedMinSize) and send desiredSize = max(savedDesired, savedMin).
5. StateSyncService.applySyncedStatus: write drift/inactive status through updateStatusIfCurrent(vm.getVmId(), currentStatus, cloudStatus, now) (plus a targeted update for isActive/stateDriftDetected, or reload-then-save); if it returns 0, skip the drift audit/notification for that VM (an operation changed it meanwhile). The 'no drift' branch uses a targeted update of lastStateSyncAt/stateDriftDetected.
6. TagReconciliationService.markTagged: call vmRepository.markTagsSynced(ids, now) instead of saveAll on stale entities.
7. EksSyncService (L405-411, L476-483), VmDiscoveryService and VmService saves: leave as entity saves, but each EKS/discovery per-VM save catches ObjectOptimisticLockingFailureException, logs and continues. GlobalExceptionHandler maps ObjectOptimisticLockingFailureException to 409 "This item was changed by someone else. Reload and try again." (consistent error body from E00-T05).

**Acceptance criteria**

- Given an EKS node group at min 3 / desired 5, when it is stopped and then started through an operation, then Vm.metadata still holds minSize=3, desiredSize=5 and the start requests min 3 / desired 5.
- Given a state sync and an operation write the same VM, when the sync's copy is stale, then the operation's status wins and no false drift is recorded.
- Given two admins edit the same VM concurrently, when the second saves, then the API returns 409, not 500.
- Given updateStatusIfCurrent with a wrong expectedStatus, then it returns 0 and the row is unchanged.

**Tests**

- VmRepository test (AbstractIntegrationTest): updateStatusIfCurrent returns 1/0 and bumps version.
- EksCloudProviderServiceTest: metadata written during stop is still present after VmOperationsService finishes the step (mock EksClient); start uses saved desiredSize.
- StateSyncServiceTest: drift skipped when status changed between load and write.
- TagReconciliationServiceTest: markTagged no longer fails when a VM was modified mid-run.

**Notes:** Adding @Version turns silent lost updates into exceptions; step 7 keeps every existing save path green. E09 may later refine sync writes on top of updateStatusIfCurrent.

#### E05-T05 — Report provider timeouts and EKS errors as failures and fail fast when a starting instance falls back to stopped

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** M9 · **Depends on:** E05-T04

A 3-minute stop timeout returns success with STOPPING, EKS returns success with ERROR, and start keeps polling 15 minutes after the instance fell back to stopped (M9).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CloudProviderService.java` (modify) — VmOperationResult.timedOut(message, lastStatus) factory + isTimedOut()
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — stopVm (L163-260) returns timedOut when waitForStopped ends not STOPPED; waitForStartReadiness (L276-351) fails fast on STOPPED/TERMINATED
- `src/main/java/com/tcgdigital/vmcontrol/service/EksCloudProviderService.java` (modify) — startVm/stopVm (L80-200) return failure for ERROR or non-target final status
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — isAcceptablePostFailureState (L779-796): STOP only accepts STOPPED; stage label only on real completion
- `src/test/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderServiceTest.java` (modify) — timeout and fail-fast cases
- `src/test/java/com/tcgdigital/vmcontrol/service/EksCloudProviderServiceTest.java` (modify) — ERROR → failure

**Steps**

1. CloudProviderService.VmOperationResult: add `public static VmOperationResult timedOut(String message, VmStatus lastStatus)` creating success=false with resultStatus=lastStatus and a new `timedOut` flag (add a constructor arg; keep the existing 4-arg constructor delegating with false).
2. AwsCloudProviderService.stopVm: wherever finalStatus from waitForStopped is used, return success only when finalStatus == STOPPED; otherwise return VmOperationResult.timedOut("Instance still " + finalStatus + " after " + STOP_TIMEOUT_MS/1000 + "s", finalStatus).
3. waitForStartReadiness: track whether a non-STOPPED state (STARTING/RUNNING) was seen; if lastKnownStatus becomes STOPPED or TERMINATED after it was seen, or stays STOPPED for more than `aws.start.stopped-grace-ms` (new @Value, default 60000) from the start, return immediately with ready=false; toStartResult then returns failure("Instance returned to " + status + " while starting").
4. EksCloudProviderService.startVm/stopVm: after waitForNodegroupActive, return success only when the status is RUNNING (start) or STOPPED (stop); ERROR → failure("Node group entered error state"); other → timedOut(...).
5. VmOperationsService.isAcceptablePostFailureState: STOP accepts only STOPPED (not STOPPING); skip reconcileCloudStateAfterFailure for results where isTimedOut() and lastStatus is STOPPING; record the timed-out message on the detail as failed. Only set stage label 'Stop completed'/'Start completed' when the step really completed.

**Acceptance criteria**

- Given an EC2 stop that is still STOPPING after the timeout, then the step is FAILED with "Instance still STOPPING after 180s" and the bar does not show 100% 'Stop completed'.
- Given an EKS node group ends in CREATE_FAILED, then the step is FAILED.
- Given an instance that goes pending → stopped during a start (e.g. capacity error), then the step fails within one poll interval of seeing STOPPED, not after 15 minutes.

**Tests**

- AwsCloudProviderServiceTest: mocked Ec2Client sequences for stop timeout, pending→stopped fail fast, normal start still succeeds.
- EksCloudProviderServiceTest: ERROR and timeout map to failure.
- VmOperationsServiceTest: timed-out stop is not reconciled into 'completed'.

**Notes:** Use small poll/timeout values via ReflectionTestUtils as the existing tests do.

#### E05-T06 — Recover orphaned executions on startup and ignore stale ones in the active-operation check

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H12 · **Depends on:** E05-T05

Executions left PENDING/IN_PROGRESS after a restart block the environment with 'already in progress' forever (H12).

**Files**

- `src/main/resources/db/migration/V<next>__operation_execution_heartbeat.sql` (new) — ADD last_heartbeat_at TIMESTAMP NULL, executor_id VARCHAR(128) NULL
- `src/main/java/com/tcgdigital/vmcontrol/model/OperationExecution.java` (modify) — lastHeartbeatAt, executorId fields
- `src/main/java/com/tcgdigital/vmcontrol/repository/OperationExecutionRepository.java` (modify) — touchHeartbeat, findActive... queries
- `src/main/java/com/tcgdigital/vmcontrol/service/OperationRecoveryService.java` (new) — ApplicationReadyEvent listener + failStale(envId)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — startOperation active check (L109) uses recovery; heartbeat in executeOperation and updateOperationProgress
- `src/main/resources/application.properties` (modify) — vm.operations.stale-after-minutes=30
- `src/test/java/com/tcgdigital/vmcontrol/service/OperationRecoveryServiceTest.java` (new) — recovery cases

**Steps**

1. Migration (next free V): add `last_heartbeat_at TIMESTAMP NULL` and `executor_id VARCHAR(128) NULL` to operation_execution; map both in OperationExecution.
2. In executeOperation, right after the PENDING→IN_PROGRESS transition, set executorId = scheduledJobLockService.getOwnerId() (existing 'hostname-pid' id, ScheduledJobLockService L81-91) and lastHeartbeatAt = now via a new `@Modifying touchHeartbeat(id, ts, executorId)` query that does NOT bump version.
3. Call touchHeartbeat at each wave start and inside updateOperationProgress (each provider poll tick).
4. OperationRecoveryService.failStale(String environmentIdOrNull): find executions in pending/in_progress whose COALESCE(lastHeartbeatAt, startedAt) is older than now - vm.operations.stale-after-minutes (default 30, longer than the 15-minute provider timeouts); for each, transitionStatus([pending,in_progress] → failed, "Interrupted: no progress for N minutes (application restart?)"), mark its pending/in_progress details failed with the same message, write an audit row via auditService.logOperationFailed.
5. OperationRecoveryService @EventListener(ApplicationReadyEvent.class): additionally fail, regardless of age, in-flight executions whose executorId starts with this host's name but has a different pid (same host restarted); then call failStale(null). Guard with property vm.operations.recovery.enabled (default true; set false in AbstractIntegrationTest properties only if it interferes).
6. VmOperationsService.startOperation: call operationRecoveryService.failStale(environmentId) before executionRepository.hasActiveOperations(environmentId).

**Acceptance criteria**

- Given an execution IN_PROGRESS whose executor was this host with a different pid, when the app starts, then it is FAILED with 'Interrupted' and the environment accepts a new operation.
- Given an execution whose last heartbeat is 45 minutes old, when a user starts a new operation, then the old one is FAILED first and the new one starts.
- Given an execution heartbeating normally on another node, then neither startup recovery nor the active check touches it.

**Tests**

- OperationRecoveryServiceTest (AbstractIntegrationTest): same-host-different-pid recovered on ApplicationReadyEvent (publish event manually); stale heartbeat recovered on startOperation; fresh heartbeat untouched; details marked failed.

**Notes:** No scheduler is added here (E06-T01 creates runLocked later); recovery runs at startup and lazily on each start. The UI Cancel (E05-T08) also clears a stuck run immediately.

#### E05-T07 — Limit concurrency per execution and give EKS operations a dedicated executor

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** M10 · **Depends on:** E05-T06

All environments share one 10-thread vmOperationExecutor, so a 40-VM Start All delays a single-VM start elsewhere, and EKS work runs on the common ForkJoinPool (M10).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/config/AsyncConfig.java` (modify) — new eksOperationExecutor bean; document per-execution limit
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — wave submission (L505-529) capped per execution
- `src/main/java/com/tcgdigital/vmcontrol/service/EksCloudProviderService.java` (modify) — supplyAsync(..., eksOperationExecutor)
- `src/main/resources/application.properties` (modify) — vm.operations.parallelism default 20, vm.operations.per-execution-parallelism=5, eks.operations.parallelism=10

**Steps**

1. VmOperationsService: add `@Value("${vm.operations.per-execution-parallelism:5}") int perExecutionParallelism`. In the wave loop, split `runnable` into chunks of at most perExecutionParallelism and submit/join each chunk in turn (or use a Semaphore per execution around each runAsync). This keeps one big run from occupying every vmOperationExecutor thread.
2. Raise the default of vm.operations.parallelism to 20 in application.properties so 4 concurrent executions can each use their per-execution share; document the relation in AsyncConfig.vmOperationExecutor javadoc.
3. AsyncConfig: add `@Bean(name = "eksOperationExecutor") Executor eksOperationExecutor(@Value("${eks.operations.parallelism:10}") int n)` (ThreadPoolTaskExecutor, prefix 'eks-op-', queue 200, CallerRunsPolicy, waitForTasksToCompleteOnShutdown).
4. EksCloudProviderService: inject `@Qualifier("eksOperationExecutor") Executor` via constructor and pass it to both CompletableFuture.supplyAsync calls (startVm L81, stopVm). Update EksCloudProviderServiceTest construction (pass a direct executor Runnable::run).

**Acceptance criteria**

- Given a 40-VM Start All is running, when another environment starts one VM, then that VM's provider call begins within one poll interval (not after the big run's waves).
- Given an EKS start, then the work runs on an 'eks-op-' thread, not ForkJoinPool.commonPool.

**Tests**

- VmOperationsServiceTest: with per-execution-parallelism=2 and a provider mock that records concurrent calls, max concurrency for one execution is 2.
- EksCloudProviderServiceTest: provided executor is used (capture thread name).

#### E05-T08 — Operations UI: Cancel in progress modal and history, one poll timer per execution, server reasons in error toasts, honest Failed/skipped display

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** H12, M8, LOW-OPS-403-TOAST, LOW-OPS-POLL-TIMER · **Depends on:** E05-T07, E01, E02

The cancel endpoint exists but no screen calls it (H12); one global poll timer drops tracking of a first operation when a second starts; the 403 toast replaces the server's reason and the 409 shows a raw lock-holder id (low items); all-failed runs should say Failed (M8 UI).

**Files**

- `src/main/resources/static/js/features/vm-operations.js` (modify) — Cancel button, per-execution timers (L11, L452-562), error messages (L103-130), history actions (L640-700)
- `src/main/java/com/tcgdigital/vmcontrol/service/LockService.java` (modify) — verifyLockPermission (L224-243) message uses display name
- `src/main/resources/static/css/main.css` (modify) — cancel button / skipped badge styles if needed

**Steps**

1. Replace the module-level `pollInterval` with `const pollTimers = new Map()` keyed by executionId; startPolling(envId, executionId, ...) only clears its own timer; stopPolling(executionId) clears one; a stopAllPolling() is used on logout. Do not register these as pageScoped timers (operations must keep tracking after navigation); the progress modal reads the execution it was opened for.
2. Add a 'Cancel operation' button to the progress modal footer, visible while status is PENDING/IN_PROGRESS. On click use Modals.confirm (text) 'Cancel this operation? VMs already started or stopped stay that way.'; then ApiClient.post(Config.API.operations.cancel(envId, execId)); disable the button while in flight; on 403 show the server message.
3. In the history modal (loadHistoryContent) add an Actions column with Cancel for rows in PENDING/IN_PROGRESS (same handler) and render status labels via a map (COMPLETED→'Completed', PARTIAL_SUCCESS→'Completed with failures', FAILED→'Failed', CANCELLED→'Cancelled') instead of raw enum text; show 'skipped N' when skippedTargets > 0. Build rows with Utils.html (E01).
4. executeOperation .fail: for 400/403/409 use `xhr.responseJSON?.message` when present and fall back to the current text only when absent.
5. LockService.verifyLockPermission: build the message with the holder's display name (userRepository/userService lookup, fallback 'another user') instead of the raw user id; keep LockAlreadyHeldException.getLockedByUserId() unchanged for callers.
6. Final-state rendering (updateProgressModal L300-340 and polling L500-520): FAILED shows 'Failed — all N steps failed'; PARTIAL_SUCCESS shows 'Completed with failures (x failed, y skipped)'.
7. Run .\build-frontend.ps1 so dist/ is regenerated (if E14 has removed dist bundling, skip).

**Acceptance criteria**

- Given an operation in progress, when the initiator clicks Cancel and confirms, then the modal shows 'Operation cancelled' within one poll interval.
- Given two operations started in two environments, then both progress views keep updating until each finishes.
- Given the server returns 403 'You cannot operate on VM group(s): Payments', then that text is shown, not 'You may need to acquire a lock first'.
- Given the environment is locked by Priya, when another user starts an operation, then the toast says 'locked by another user: Priya', not a UUID.
- Given all steps failed, then the modal and history say 'Failed'.

**Tests**

- LockServiceTest: verifyLockPermission message contains the display name.
- Manual/Playwright smoke (if E00 added Playwright): start two operations, cancel one from history, check both timers.

**Notes:** E07 (Locks) later edits LockService; this task only changes the message text.

#### E05-T09 — Resize: check live state and active operations, and never report failure after the instance type was changed

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** LOW-OPS-RESIZE · **Depends on:** E05-T04

Resize trusts the database status and can report failure after the type was already changed (low item, VM operations).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/VmResizeService.java` (modify) — applyInstanceTypeChange live checks and post-change bookkeeping
- `src/test/java/com/tcgdigital/vmcontrol/service/VmResizeServiceTest.java` (new) — resize cases

**Steps**

1. Before changing: reject with ValidationException if executionRepository.hasActiveOperations(environmentId) ('An operation is running in this environment').
2. Replace the DB status check with awsCloudProviderService.getVmStatus(vm.getProviderVmId(), vm.getRegion()); require STOPPED; if the DB disagrees, update it via vmRepository.updateStatusIfCurrent (E05-T04).
3. Wrap only awsCloudProviderService.changeInstanceType in the failure try/catch. After it succeeds, upsert VmInventorySnapshot (create when missing instead of throwing ResourceNotFoundException) inside its own try/catch that logs a warning; always write VM_RESIZE_COMPLETED.
4. If changeInstanceType throws, re-read the live instance type (describe call already used by inventory, or add a small getInstanceType helper in AwsCloudProviderService); if it equals the target, treat as success.

**Acceptance criteria**

- Given the DB says STOPPED but AWS says RUNNING, when an admin applies a resize, then it is rejected with 'VM must be stopped' and the DB status is corrected.
- Given an operation is running in the environment, then resize is rejected.
- Given the type change succeeded but no inventory snapshot exists, then the API returns success, a snapshot row is created and VM_RESIZE_COMPLETED is audited.

**Tests**

- VmResizeServiceTest (Mockito): live-state rejection, active-operation rejection, snapshot upsert, post-change error not reported as failure.

## E06

### Automation & scheduler reliability — Fix

Scheduled and access-triggered rules fire reliably once per window, never roll back the action that triggered them, skip cleanly when they cannot act, and every scheduled job runs on a thread pool under a cluster-wide job lock.

Sources: C5, C6, H26, M6, M16, LOW-AUTO-DST, LOW-AUTO-DAY-NAMES, LOW-AUTO-EQUAL-TIMES, LOW-AUTO-DELETED-SCOPE, LOW-AUTO-LASTRUNDETAIL, UX-AUTO-SAVE-DOUBLE-CLICK, UX-AUTO-NOTIFIED-WORDING, UX-AUTO-SKIPPED-LOCKED-COUNT, UX-AUTO-TIMEZONE-DEFAULT · 5 tasks · 6.5 days

#### E06-T01 — Create ScheduledJobLockService.runLocked and a scheduler thread pool; migrate all 14 schedulers to runLocked

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** M6, H26 · **Depends on:** E00-T01

All @Scheduled jobs share one thread so a long job makes automation miss its minute (H26); state sync, discovery and EKS sync skip the job lock so two servers double-audit drift and race on unique indexes (M6). The brief's contract requires runLocked and a pool used by every scheduler.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/ScheduledJobLockService.java` (modify) — add boolean runLocked(String jobName, Duration ttl, Runnable job)
- `src/main/resources/application.properties` (modify) — spring.task.scheduling.pool.size=${SCHEDULER_POOL_SIZE:6}, thread-name-prefix, per-job TTL properties
- `src/test/resources/application.properties` (modify) — same pool size
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/AccessExpirationScheduler.java` (modify) — inject lock service; runLocked('access_expiration', 30m)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ActualCostIngestionScheduler.java` (modify) — runLocked
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/AutomationRuleScheduler.java` (modify) — runLocked('automation_rules', 5m)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/CostSnapshotScheduler.java` (modify) — runLocked
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/EksSyncScheduler.java` (modify) — inject lock service; runLocked('eks_sync', 30m)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ReservationCoverageScheduler.java` (modify) — runLocked
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/StateSyncScheduler.java` (modify) — inject lock service; runLocked('vm_state_sync', 30m)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/TagReconciliationScheduler.java` (modify) — runLocked
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/VmDiscoveryScheduler.java` (modify) — inject lock service; runLocked('vm_discovery', 30m)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/VmInventoryScheduler.java` (modify) — runLocked
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/VmMetricRollupScheduler.java` (modify) — runLocked
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/VmMetricsArchiveScheduler.java` (modify) — runLocked
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/VmMetricsScheduler.java` (modify) — runLocked
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/WeeklyReportScheduler.java` (modify) — runLocked
- `src/test/java/com/tcgdigital/vmcontrol/service/ScheduledJobLockServiceTest.java` (new) — runLocked semantics

**Steps**

1. ScheduledJobLockService: add `public boolean runLocked(String jobName, Duration ttl, Runnable job)` (not @Transactional, so the job does not run inside the lock transaction): if (!tryAcquire(jobName, ttl.toMillis())) { log.debug(...); return false; } try { job.run(); } catch (RuntimeException e) { log.error("Scheduled job {} failed", jobName, e); } finally { release(jobName); } return true. Release only when acquired (today's schedulers call release in finally even when tryAcquire returned false).
2. application.properties: `spring.task.scheduling.pool.size=${SCHEDULER_POOL_SIZE:6}` and `spring.task.scheduling.thread-name-prefix=sched-`. Boot's auto-configured ThreadPoolTaskScheduler uses these because no custom TaskScheduler bean exists (Application.java only has @EnableScheduling; confirm no other TaskScheduler bean). Add the same to src/test/resources/application.properties.
3. Migrate all 14 classes in scheduler/: keep the `enabled` flag check outside, then `lockService.runLocked(LOCK_NAME, ttl, () -> { ...existing body... })`. Keep existing lock names (vm_metrics_sync, vm_inventory_sync, vm_metrics_archive are read by DashboardSummaryService.buildSchedulerHealth L345-361). New names: access_expiration, vm_state_sync, vm_discovery, eks_sync. TTL from `scheduler.lock.ttl.<job>-ms`, default scheduler.lock.default-ttl-ms (15m); state sync, discovery and EKS default 30m (longer than a worst-case run).
4. StateSyncScheduler still calls stateSyncService.syncAllVmStates() (its AtomicBoolean keeps protecting manual 'sync now' in one JVM). AccessExpirationScheduler.triggerManualExpiration stays unlocked.
5. PR check: `grep -L runLocked src/main/java/com/tcgdigital/vmcontrol/scheduler/*.java` prints nothing.

**Acceptance criteria**

- Given two app instances, when the state-sync tick fires on both, then only one runs and each drift is audited once.
- Given the metrics sync runs from 19:59:40 to 20:01:10, when the automation tick is due, then it runs on another scheduler thread on time.
- Given a job throws, then its lock is released and the next tick runs.
- Given another instance holds the lock, then runLocked returns false and does not release that lock.

**Tests**

- ScheduledJobLockServiceTest (AbstractIntegrationTest): runs when free; returns false when a row is held by another owner; releases after exception.
- Context test: TaskScheduler bean is a ThreadPoolTaskScheduler with pool size 6.
- Unit tests for StateSync, Discovery, EKS and AccessExpiration schedulers with a mocked ScheduledJobLockService verifying runLocked is used.

**Notes:** Brief contract. Every later scheduler (E12, E16, E19 ...) must use runLocked.

#### E06-T02 — Run lock-acquire and access-approved automation hooks after commit and treat 'nothing to do' as a normal result

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** C5 · **Depends on:** E06-T01, E00-T03, E05-T02

acquireLock and the grant methods call rule hooks inside their transaction; when startOperation throws ('No VMs to operate on') the shared transaction is marked rollback-only and the lock or grant is lost with a 500 (C5).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/LockService.java` (modify) — acquireLock hook (L108-109) via AfterCommit.run
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — fireAccessGrantedAutomation (~L453-456) via AfterCommit.run
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — new startOperationIfNeeded returning Optional<OperationExecution>
- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — fireOperation (L243-284) runs in REQUIRES_NEW and returns FireOutcome
- `src/test/java/com/tcgdigital/vmcontrol/service/AutomationRuleHookTransactionTest.java` (new) — lock/grant survive hook failures

**Steps**

1. LockService.acquireLock: wrap the existing runNotificationSideEffect("trigger lock-acquire automation rules", ...) call in `AfterCommit.run(() -> ...)` (E00-T03).
2. EnvironmentAccessService.fireAccessGrantedAutomation: same wrapping.
3. VmOperationsService: move the body of startOperation into `Optional<OperationExecution> startOperationIfNeeded(envId, userId, dto)` that returns Optional.empty() where it now throws ValidationException("No VMs to operate on"); startOperation delegates and still throws for the REST API.
4. AutomationRuleService: inject PlatformTransactionManager, build a TransactionTemplate with PROPAGATION_REQUIRES_NEW; fireOperation calls startOperationIfNeeded inside it and returns enum FireOutcome {STARTED, NOTHING_TO_DO, LOCKED, BUSY, FAILED}: empty → NOTHING_TO_DO (recordRun SKIPPED "Nothing to do: all targets already in the requested state"); LockAlreadyHeldException → LOCKED; ValidationException 'already in progress' → BUSY; anything else → FAILED. recordRun and audit run outside the REQUIRES_NEW block so they persist when the operation transaction rolls back.
5. notifyAutomationRuleSkipped stays only for LOCKED.

**Acceptance criteria**

- Given a lock-acquire rule and all VMs already running, when a user acquires the lock, then the lock is created (200) and the rule shows last run SKIPPED 'Nothing to do'.
- Given an access-approved rule whose start fails, when an admin approves a request, then the approval and grant are committed.
- Given the acquiring transaction rolls back, then no automation operation starts.

**Tests**

- AutomationRuleHookTransactionTest (AbstractIntegrationTest): lock acquired with rule + all VMs RUNNING; approval kept when provider throws; hook not fired on rollback.

#### E06-T03 — Evaluate schedule rules in their own transaction with the environment fetched, catch up missed minutes, mark fired only on success, handle DST gaps

- [ ] Done · **Type:** fix · **Estimate:** 2 d · **Risk:** medium · **Sources:** C6, H26, LOW-AUTO-DST · **Depends on:** E06-T02

evaluateSchedules runs without a session so environment.getName() throws LazyInitializationException and rules probably never fire (C6); rules fire only when now equals HH:mm and lastFiredOn is set even when skipped or failed (H26); times in a DST gap never fire (low item).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/repository/AutomationRuleRepository.java` (modify) — findByIdFetchEnvironment, findEnabledIdsByTriggerType, JOIN FETCH variant of the access-grant finder
- `src/main/java/com/tcgdigital/vmcontrol/service/ScheduleCalculator.java` (new) — pure due-time logic (zone, DST, catch-up)
- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — evaluateSchedules / evaluateAndFireOneSchedule (L188-235)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/AutomationRuleScheduler.java` (modify) — javadoc
- `src/main/resources/application.properties` (modify) — automation.rules.catch-up-minutes=15
- `src/test/java/com/tcgdigital/vmcontrol/service/ScheduleCalculatorTest.java` (new) — due/catch-up/DST
- `src/test/java/com/tcgdigital/vmcontrol/service/AutomationRuleScheduleIntegrationTest.java` (new) — rule fires end to end

**Steps**

1. Repository: `@Query("SELECT r FROM AutomationRule r JOIN FETCH r.environment WHERE r.ruleId = :id") Optional<AutomationRule> findByIdFetchEnvironment(@Param("id") String id)`; `@Query("SELECT r.ruleId FROM AutomationRule r WHERE r.enabled = true AND r.triggerType = :t") List<String> findEnabledIdsByTriggerType(@Param("t") AutomationTriggerType t)`; JOIN FETCH variant of findByEnabledTrueAndTriggerTypeAndAccessGrantModeAndEnvironment_EnvironmentId for the hooks.
2. ScheduleCalculator (@Component, takes a java.time.Clock bean; add one in a config class if E00 has not): `ZonedDateTime dueInstant(LocalDate day, LocalTime time, ZoneId zone)` = ZonedDateTime.of(day, time, zone) (a gap time moves forward, e.g. 02:30 → 03:30; overlaps use the earlier offset); `boolean isDue(ZonedDateTime now, LocalTime time, Set<DayOfWeek> days, LocalDate lastFiredOn, Duration catchUp)` true when the day is selected, lastFiredOn != today and dueInstant <= now < dueInstant + catchUp.
3. evaluateSchedules: load ids via findEnabledIdsByTriggerType(SCHEDULE); for each id run a REQUIRES_NEW TransactionTemplate that loads findByIdFetchEnvironment and evaluates; log and continue on exceptions.
4. Use isDue for stop and start with automation.rules.catch-up-minutes (default 15). Set lastStopFiredOn/lastStartFiredOn only for FireOutcome STARTED or NOTHING_TO_DO. LOCKED/BUSY/FAILED leave it unset so the next tick retries; recordRun only when status/detail changed, and send the skip notification only on the first LOCKED of the window.
5. When now >= dueInstant + catchUp and today's fire never succeeded, record final SKIPPED/FAILED 'Missed window: <last reason>' and set lastFiredOn = today.
6. Update the AutomationRuleScheduler javadoc.

**Acceptance criteria**

- Given a weekday 'Stop 20:00' rule, when the tick runs at 20:00:40, then the stop operation starts and no LazyInitializationException is logged.
- Given the first tick after 20:00 is 20:03, then the stop fires at 20:03; given it is 20:20 with a 15-minute window, then it does not fire and the run is recorded as missed.
- Given the environment is locked at 20:00 and released at 20:05, then the rule fires at the 20:05 tick and lastStopFiredOn is today.
- Given a 02:30 rule in Europe/London on the spring-forward day, then it fires at 03:30 local.

**Tests**

- ScheduleCalculatorTest: exact minute, catch-up inside/outside, already fired, unselected day, DST gap and overlap.
- AutomationRuleScheduleIntegrationTest (AbstractIntegrationTest with a fixed Clock): fires outside a web session; locked-then-free retry; lastFiredOn unset on LOCKED.

**Notes:** ScheduleCalculator is reused by E19 (windows, holidays, overrides, preview).

#### E06-T04 — Skip and disable rules for inactive environments, inactive creators or deleted scopes; validate day names, equal times and run-detail length; store a run reason code

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** M16, LOW-AUTO-DAY-NAMES, LOW-AUTO-EQUAL-TIMES, LOW-AUTO-DELETED-SCOPE, LOW-AUTO-LASTRUNDETAIL · **Depends on:** E06-T03

Rules keep firing for deactivated environments and offboarded creators (M16); day names are not validated, equal start/stop are accepted, rules on deleted groups fail silently every day, and lastRunDetail can exceed its 500-char column (low items). A reason code lets the UI count lock skips correctly.

**Files**

- `src/main/resources/db/migration/V<next>__automation_rule_run_reason.sql` (new) — ADD last_run_reason VARCHAR(30) NULL, disabled_reason VARCHAR(255) NULL
- `src/main/java/com/tcgdigital/vmcontrol/model/AutomationRule.java` (modify) — lastRunReason (EnumType.STRING), disabledReason
- `src/main/java/com/tcgdigital/vmcontrol/model/AutomationRunReason.java` (new) — OK, NOTHING_TO_DO, LOCKED, OPERATION_IN_PROGRESS, ENVIRONMENT_INACTIVE, CREATOR_INACTIVE, SCOPE_MISSING, ERROR, MISSED_WINDOW
- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — preflight, validateTriggerFields (L329-358), applyRequestFields (L374-399), recordRun (L286-291), setEnabled (L135-143)
- `src/main/java/com/tcgdigital/vmcontrol/dto/AutomationRuleDTO.java` (modify) — lastRunReason, disabledReason
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyAutomationRuleDisabled to admins
- `src/test/java/com/tcgdigital/vmcontrol/service/AutomationRuleServiceTest.java` (new) — validation and preflight

**Steps**

1. Migration (next free V): `ALTER TABLE automation_rule ADD COLUMN last_run_reason VARCHAR(30) NULL, ADD COLUMN disabled_reason VARCHAR(255) NULL;`. Map both; recordRun(rule, status, reason, detail).
2. preflight(rule) before every fire (schedule and both hooks): environment.getIsActive() false → enabled=false, disabledReason 'Environment deactivated', recordRun SKIPPED/ENVIRONMENT_INACTIVE; creator User missing, isActive false, or neither admin nor envAdmin → disable 'Rule creator is no longer active or no longer an admin' and notify ADMIN users (new notifyAutomationRuleDisabled, reuse NotificationType.AUTOMATION_RULE_SKIPPED); GROUP/VM scope target missing or inactive → disable 'Target group/VM no longer exists' (SCOPE_MISSING). Each disable writes auditService.logAutomationRuleUpdated with the reason.
3. validateTriggerFields: each day must be MON..SUN (case-insensitive) else 400 'Unknown day: X'; applyRequestFields dedupes and stores in week order. Reject stopTime equal to startTime ('Start and stop times must differ').
4. recordRun truncates detail to 500 chars (497 + '...').
5. setEnabled(true) re-runs preflight and rejects with the reason if still invalid; on success clears disabledReason.
6. AutomationRuleDTO.fromEntity: add lastRunReason and disabledReason.

**Acceptance criteria**

- Given a rule on a deactivated environment, when its time comes, then no operation starts, the rule is disabled with 'Environment deactivated' and audited.
- Given the creator of an 08:00 start rule was deactivated, then the rule is disabled and admins are notified.
- Given a GROUP rule whose group was deleted, then the rule is disabled with a reason instead of failing daily.
- Given daysOfWeek ['MON','FUNDAY'], then 400 'Unknown day: FUNDAY'; given start 08:00 and stop 08:00, then 400.
- Given a 2,000-character error, then recordRun stores 500 characters without failing.

**Tests**

- AutomationRuleServiceTest (AbstractIntegrationTest): each preflight branch, day validation, equal times, truncation, re-enable blocked while invalid.

**Notes:** No separate service actor: rules still act as the creator, but only while the creator is valid.

#### E06-T05 — Automation rules UI: prevent double Save, correct conflict wording, count only lock skips, default timezone to the browser's

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** UX-AUTO-SAVE-DOUBLE-CLICK, UX-AUTO-NOTIFIED-WORDING, UX-AUTO-SKIPPED-LOCKED-COUNT, UX-AUTO-TIMEZONE-DEFAULT · **Depends on:** E06-T04, E01

Save can be double-clicked; the modal says 'the lock holder is notified' while the rule creator is; 'LAST RUN SKIPPED (LOCKED)' counts every skip; the timezone defaults to the first list entry (review screen table: Automation rules).

**Files**

- `src/main/resources/static/js/features/automation-rules.js` (modify) — submitForm (L645-690), renderStatCards (L212-236), intro text (L85), conflict text (L571), tz options (L461-463)

**Steps**

1. submitForm: return early if #ar-save-btn is disabled; disable it with 'Saving…'; re-enable in a finally path (including validation errors).
2. Replace text at L85 and L571 with: 'If the environment is locked by another user or an operation is running, the rule retries until its catch-up window ends, then is skipped. The rule's creator is notified. Rules never break an active lock.'
3. renderStatCards: skippedLocked counts lastRunStatus === 'SKIPPED' && lastRunReason === 'LOCKED'; add a 'DISABLED AUTOMATICALLY' card counting rules with disabledReason.
4. New rule: preselect Intl.DateTimeFormat().resolvedOptions().timeZone when it is in Config.IANA_TIMEZONES, else 'UTC'.
5. Show disabledReason under the rule name and the run reason in the last-run cell, escaped (Utils.html from E01).
6. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given a double click on Create Rule, then one POST is sent and one rule exists.
- Given one rule skipped for a lock and one for nothing-to-do, then the LOCKED card shows 1.
- Given a browser in Asia/Kolkata, when the create modal opens, then Asia/Kolkata is selected.

**Tests**

- Manual checklist in the PR; Playwright smoke (if present from E00) for double click → one request.

## E07

### Locks — Fix

An environment can only ever have one active lock, every lock ends on its own (expiry, extension, access loss, deactivation) with an honest history, and the lock UI shows who may do what and how long is left.

Sources: H4, M1, G3, LOW-LOCK-1, LOW-LOCK-2 · 5 tasks · 6.5 days

#### E07-T01 — Enforce one active lock per environment in the database and return 409 on a lost race

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** M1 · **Depends on:** E00-T01, E00-T03, E00-T05, E03

M1: LockService.acquireLock (line 58-93) reads then inserts with no DB guard (V1:212-215 only has a non-unique index), so two simultaneous acquires create two active locks and every later findBy...IsActiveTrue call fails with a non-unique result.

**Files**

- `src/main/resources/db/migration/V<next>__environment_lock_single_active.sql` (new) — deactivate duplicate active locks, add generated active_environment_id + UNIQUE index
- `src/main/java/com/tcgdigital/vmcontrol/model/EnvironmentLock.java` (modify) — map active_environment_id read-only (insertable=false, updatable=false)
- `src/main/java/com/tcgdigital/vmcontrol/service/LockService.java` (modify) — saveAndFlush + catch DataIntegrityViolationException -> LockAlreadyHeldException; side effects via AfterCommit
- `src/test/java/com/tcgdigital/vmcontrol/service/LockConcurrencyIntegrationTest.java` (new) — two threads acquire at once
- `src/test/java/com/tcgdigital/vmcontrol/FlywayMigrationTest.java` (modify) — unique index exists

**Steps**

1. Migration (next free V number at implementation time). First repair data: UPDATE environment_lock l JOIN (SELECT environment_id, MAX(locked_at) keep_at FROM environment_lock WHERE is_active = TRUE GROUP BY environment_id HAVING COUNT(*) > 1) d ON d.environment_id = l.environment_id SET l.is_active = FALSE, l.released_at = NOW(), l.break_reason = 'Duplicate active lock removed by migration' WHERE l.is_active = TRUE AND l.locked_at < d.keep_at; (if two rows share the same locked_at, also tie-break on lock_id in a second statement).
2. Same migration: ALTER TABLE environment_lock ADD COLUMN active_environment_id VARCHAR(36) GENERATED ALWAYS AS (CASE WHEN is_active THEN environment_id ELSE NULL END) STORED; CREATE UNIQUE INDEX ux_environment_lock_one_active ON environment_lock(active_environment_id); (MySQL allows many NULLs in a unique index.)
3. EnvironmentLock: add @Column(name = "active_environment_id", insertable = false, updatable = false) private String activeEnvironmentId; (getter only) so Hibernate never writes the generated column.
4. LockService.acquireLock: replace lockRepository.save(lock) with saveAndFlush inside try; catch DataIntegrityViolationException -> re-read the active lock and throw LockAlreadyHeldException("Environment was just locked by another user", environmentId, holderId). Note: catching inside the same @Transactional leaves the tx rollback-only; therefore move the insert into a small helper bean method annotated @Transactional(propagation = REQUIRES_NEW) (e.g. LockWriter.insertActiveLock) or let the exception propagate and translate it in the controller advice. Preferred: LockWriter REQUIRES_NEW insert, outer method translates the exception.
5. Replace runNotificationSideEffect(...) calls in acquireLock/releaseLock/breakLock with AfterCommit.run(() -> runNotificationSideEffect(...)) so a failed notification or automation never rolls back a lock change (same pattern as E04-T04).

**Acceptance criteria**

- Given an unlocked environment, when two users acquire its lock at the same moment, then exactly one gets 200, the other gets 409 with the holder's id, and exactly one active lock row exists.
- Given a database that already holds two active locks for one environment, when the migration runs, then only the newest remains active.
- Given the same user acquires twice, then the existing lock is returned (unchanged behaviour).

**Tests**

- LockConcurrencyIntegrationTest (AbstractIntegrationTest, not @Transactional, CountDownLatch with 2 threads): one success, one LockAlreadyHeldException; count active = 1.
- FlywayMigrationTest: ux_environment_lock_one_active exists and is unique.
- LockServiceTest: notification throwing does not prevent the lock from being committed.

**Notes:** LockAlreadyHeldException already maps to 409 in GlobalExceptionHandler (line 54).

#### E07-T02 — Give locks an expiry and auto-release them with an EXPIRED history event

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H4, G3 · **Depends on:** E06-T01, E07-T01

H4/G3: expectedDurationMinutes is stored but never read and nothing writes LockAction.EXPIRED, although the acquire dialog says the lock auto-releases; a user who picks 1 hour and goes home blocks the environment until an admin breaks the lock.

**Files**

- `src/main/resources/db/migration/V<next>__environment_lock_expires_at.sql` (new) — expires_at TIMESTAMP NULL + backfill + index
- `src/main/java/com/tcgdigital/vmcontrol/model/EnvironmentLock.java` (modify) — expiresAt field
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentLockRepository.java` (modify) — findExpiredActiveLockIds(now)
- `src/main/java/com/tcgdigital/vmcontrol/service/LockService.java` (modify) — acquire sets expiresAt; expireLock(lockId) REQUIRES_NEW via LockWriter; processExpiredLocks()
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/LockExpiryScheduler.java` (new) — fixed-rate sweep wrapped in ScheduledJobLockService.runLocked
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — logLockExpired (AuditAction.LOCK_EXPIRED already exists)
- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — add LOCK_EXPIRED
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyLockExpiredForEnvironment
- `src/main/java/com/tcgdigital/vmcontrol/dto/LockStatusDTO.java` (modify) — expiresAt
- `src/main/resources/application.properties` (modify) — locks.expiry.* properties
- `src/test/java/com/tcgdigital/vmcontrol/service/LockServiceTest.java` (modify) — expiry tests

**Steps**

1. Migration (next free V number): ALTER TABLE environment_lock ADD COLUMN expires_at TIMESTAMP NULL; UPDATE environment_lock SET expires_at = TIMESTAMPADD(MINUTE, expected_duration_minutes, locked_at) WHERE is_active = TRUE AND expected_duration_minutes IS NOT NULL; CREATE INDEX ix_environment_lock_active_expires ON environment_lock(is_active, expires_at);
2. EnvironmentLock: @Column(name = "expires_at") private Timestamp expiresAt; getter/setter. AcquireLockDTO: add @Max(value = 1440) to expectedDurationMinutes.
3. application.properties: locks.expiry.enabled=${LOCKS_EXPIRY_ENABLED:false}, locks.expiry.interval-ms=${LOCKS_EXPIRY_INTERVAL_MS:60000}, locks.max-duration-minutes=${LOCKS_MAX_DURATION_MINUTES:1440}.
4. LockService.acquireLock: when expectedDurationMinutes != null set expiresAt = lockedAt + minutes (compute from Instant.now(), not from the DB default). 'Until manually released' keeps expiresAt null.
5. EnvironmentLockRepository: @Query("SELECT l.lockId FROM EnvironmentLock l WHERE l.isActive = true AND l.expiresAt IS NOT NULL AND l.expiresAt <= :now") List<String> findExpiredActiveLockIds(Timestamp now).
6. LockWriter (from E07-T01) gets @Transactional(REQUIRES_NEW) boolean expire(String lockId): load lock with environment; return false unless still active and expiresAt <= now; set isActive=false, releasedAt=now, releasedByUserId=null; LockService.recordLockHistory(lock, LockAction.EXPIRED, lock.getLockedByUserId() /* performed_by is NOT NULL */, "Expired after " + minutes + " minutes"); auditService.logLockExpired(holderId, envId, envName); AfterCommit.run(notifyLockExpiredForEnvironment(envId, envName, holderId)).
7. LockService.processExpiredLocks(): for each id call lockWriter.expire(id) in try/catch per id; return count.
8. LockExpiryScheduler: @ConditionalOnProperty(name = "locks.expiry.enabled", havingValue = "true"); @Scheduled(fixedDelayString = "${locks.expiry.interval-ms:60000}", initialDelayString = "60000") void run() { jobLockService.runLocked("lock-expiry", Duration.ofMinutes(5), lockService::processExpiredLocks); }. Because the sweep reads all overdue rows, locks that expired while the app was down are released on the first run after restart.
9. NotificationService.notifyLockExpiredForEnvironment: broadcastToEnvironment with actor title 'Your lock expired: <env>' and other title '<holder>'s lock expired: <env>'.
10. LockStatusDTO.fromEntity: copy expiresAt (frontend uses it in E07-T05).

**Acceptance criteria**

- Given locks.expiry.enabled=true and a lock acquired for 30 minutes, when 30 minutes pass, then within one sweep interval the lock is inactive, a lock_history row with action EXPIRED exists, a LOCK_EXPIRED audit row exists and environment users get a notification.
- Given a lock acquired 'until manually released', when the sweep runs, then it stays active.
- Given the app was down when a lock expired, when it starts, then the lock is released on the first sweep (within about 2 minutes).
- Given two app instances, when both schedulers fire, then the lock is expired once (runLocked + REQUIRES_NEW re-check).
- Given locks.expiry.enabled=false, then no lock is ever auto-released (today's behaviour).

**Tests**

- LockServiceTest: acquire with 30 min sets expiresAt; processExpiredLocks expires only overdue active locks and writes EXPIRED history; expire() is idempotent.
- LockExpirySchedulerTest (unit): delegates through runLocked with job name 'lock-expiry'.
- FlywayMigrationTest: expires_at column and index exist; backfill computes locked_at + duration.

**Notes:** Flag defaults off per brief; turn on per environment rollout (recommend enabling in prod together with E07-T05 so users see the countdown). EnvironmentLock.expiresAt is the contract name used by E15.

#### E07-T03 — Warn lock holders before expiry and let them extend the lock

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G3, H4 · **Depends on:** E07-T02, E04-T05, E00-T02

G3/H4: an expiring lock must warn its holder and offer an extension, otherwise auto-release surprises people mid-deployment.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/model/LockAction.java` (modify) — add EXTENDED
- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — add LOCK_EXPIRING
- `src/main/java/com/tcgdigital/vmcontrol/service/LockService.java` (modify) — extend(envId, userId, minutes); processExpiringLockWarnings()
- `src/main/java/com/tcgdigital/vmcontrol/dto/ExtendLockDTO.java` (new) — @NotNull @Min(15) @Max(480) Integer minutes
- `src/main/java/com/tcgdigital/vmcontrol/controller/LockController.java` (modify) — POST /api/v1/environments/{environmentId}/lock/extend
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyLockExpiring(holderId, envId, envName, lockId, expiresAt) with windowed de-dup
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/LockExpiryScheduler.java` (modify) — also run warnings
- `src/main/resources/static/js/config.js` (modify) — API.locks.extend
- `src/test/java/com/tcgdigital/vmcontrol/controller/LockControllerIntegrationTest.java` (modify) — extend endpoint
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — add LOCK_EXTENDED
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — logLockExtended

**Steps**

1. LockService.extend(String environmentId, String userId, int minutes): @Transactional; load active lock (findByEnvironmentIdWithLock - PESSIMISTIC_WRITE already exists, line 33); only the holder may extend (else UnauthorizedException); base = max(now, expiresAt or now); new expiry = base + minutes; reject with ValidationException when new expiry - lockedAt > locks.max-duration-minutes; save; history EXTENDED with notes 'Extended by N minutes to <time>'; audit: add AuditAction.LOCK_EXTENDED and AuditService.logLockExtended. Return the lock.
2. Extending a lock with no expiry is a no-op returning 400 'This lock has no expiry'.
3. LockController: @PostMapping("/extend") with @Valid @RequestBody ExtendLockDTO; authorization identical to release (E03 rules) - the holder only; returns LockStatusDTO.
4. Warnings: properties locks.expiry.warning-minutes=${LOCKS_EXPIRY_WARNING_MINUTES:15,5}. LockService.processExpiringLockWarnings(): for each warning threshold T, find active locks with expiresAt in (now, now+T]; call notificationService.notifyLockExpiring(...) which de-duplicates on (user, LOCK_EXPIRING, 'LOCK', lockId) created after (expiresAt - T) using the repository method added in E04-T05 (existsBy...CreatedAtAfter), so an extension re-arms the warnings.
5. Message: 'Your lock on <env> expires at <HH:mm>. Extend it from the environment page.' entityType 'ENVIRONMENT', entityId envId so the bell links to the environment (keep LOCK de-dup key by passing lockId through a dedicated createIfAbsentSince overload).
6. LockExpiryScheduler.run: runLocked job runs processExpiringLockWarnings() then processExpiredLocks().
7. config.js: locks.extend: (envId) => `${API_BASE_URL}/environments/${envId}/lock/extend`.

**Acceptance criteria**

- Given a lock expiring in 14 minutes, when the sweep runs, then the holder gets exactly one 'expires at' notification; at 5 minutes they get one more.
- Given the holder extends by 60 minutes, then expiresAt = old expiry + 60, an EXTENDED history row exists, and the 15/5-minute warnings fire again before the new expiry.
- Given a non-holder calls extend, then 403.
- Given an extension that would exceed locks.max-duration-minutes since acquisition, then 400 with the limit in the message.

**Tests**

- LockServiceTest: extend math (future expiry, already-overdue-but-not-swept), max-duration rejection, non-holder rejection, warning de-dup across thresholds and after extension.
- LockControllerIntegrationTest (SecuredWebTestBase): holder 200, other USER 403, VIEWER 403, body minutes=0 -> 400.

**Notes:** LockService.extend(envId, userId, minutes) is the contract name used by E15.

#### E07-T04 — Release a user's locks when they are deactivated or lose access to the environment

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** H4 · **Depends on:** E07-T01, E04-T01

H4 fix: locks must not outlive their holder's right to hold them; today a deactivated user or one whose access was revoked or expired keeps the environment locked until an admin breaks it.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/LockService.java` (modify) — releaseLocksForUser(userId, reason) and releaseIfHolderLostAccess(envId, userId, reason)
- `src/main/java/com/tcgdigital/vmcontrol/service/UserService.java` (modify) — deactivateUser (line 297) calls releaseLocksForUser
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — revokeGrantById, revokeAccess call releaseIfHolderLostAccess
- `src/main/java/com/tcgdigital/vmcontrol/service/AccessExpiryProcessor.java` (modify) — after expiring a grant call releaseIfHolderLostAccess
- `src/test/java/com/tcgdigital/vmcontrol/service/LockServiceTest.java` (modify) — auto-release tests
- `src/test/java/com/tcgdigital/vmcontrol/service/UserServiceTest.java` (modify) — deactivation releases locks

**Steps**

1. LockService.releaseLocksForUser(String userId, String reason): for each lock in findByLockedByUserIdAndIsActiveTrue(userId) set inactive, releasedAt=now, releasedByUserId=null; history RELEASED performed by the holder with notes 'Auto-released: ' + reason; audit logLockReleased with userId 'system' (AuditService treats 'system' as a system actor, line 410); AfterCommit notifyLockReleasedForEnvironment.
2. LockService.releaseIfHolderLostAccess(String envId, String userId, String reason): if the active lock on envId is held by userId and !securityService-equivalent check (use EnvironmentAccessService-free check to avoid a cycle: inject SecurityService and call canPerformOperations(envId, userId), SecurityService.java:126) then release as in step 1. Inject SecurityService with @Lazy if a cycle appears at startup.
3. UserService.deactivateUser: after save call lockService.releaseLocksForUser(userId, "user deactivated"). Inject LockService (@Lazy if needed).
4. EnvironmentAccessService.revokeGrantById and revokeAccess: after the revoke call lockService.releaseIfHolderLostAccess(envId, targetUserId, "access revoked").
5. AccessExpiryProcessor.expireOne (E04-T01): after expiring call lockService.releaseIfHolderLostAccess(envId, userId, "access expired").
6. A group-only grant holder who still can operate some group keeps the lock (canPerformOperations decides).

**Acceptance criteria**

- Given Bob holds the lock on DEV, when an admin deactivates Bob, then the lock is released, history shows RELEASED 'Auto-released: user deactivated' and DEV users are notified.
- Given Bob holds the lock on DEV, when his only DEV grant is revoked or expires, then the lock is released in the same transaction.
- Given Bob holds the lock on DEV and also holds ADMIN on DEV via another grant, when one of his grants is revoked, then the lock stays.
- Given Bob holds no lock, when he is deactivated, then nothing changes and no error occurs.

**Tests**

- LockServiceTest: releaseLocksForUser releases all of a user's locks; releaseIfHolderLostAccess respects canPerformOperations.
- UserServiceTest: deactivateUser releases locks (integration against MySQL).
- EnvironmentAccessServiceTest: revokeGrantById releases the holder's lock.

**Notes:** Watch for bean cycles (LockService <-> AutomationRuleService already uses @Lazy at LockService.java:44).

#### E07-T05 — Lock UI: per-viewer actions, countdown, extend, 'held by another user' styling and an accurate history

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** LOW-LOCK-1, LOW-LOCK-2, G3 · **Depends on:** E07-T03, E02, E01, E03

Low items: lock history shows the acquire time on every row (locks.js:195 uses entry.acquiredAt) and hides the break reason (LockHistoryDTO.getReason prefers lockReason); Acquire is shown to viewers; there is no countdown or 'held by another user' styling. G3 needs an Extend button.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/LockHistoryDTO.java` (modify) — getReason(): BROKEN -> lock breakReason/notes; ACQUIRED -> lockReason; others -> notes; add breakReason
- `src/main/java/com/tcgdigital/vmcontrol/dto/LockStatusDTO.java` (modify) — canAcquire, canRelease, canBreak, canExtend booleans
- `src/main/java/com/tcgdigital/vmcontrol/controller/LockController.java` (modify) — getLockStatus/acquire fill the flags from SecurityService
- `src/main/resources/static/js/features/locks.js` (modify) — history uses performedAt; banner uses flags; countdown; extend dialog; acquire dialog copy
- `src/main/resources/static/js/features/environments.js` (modify) — pass lockStatus flags; stop countdown on leave
- `src/main/resources/static/css/components/locks.css` (modify) — .lock-banner.locked.other, .lock-countdown, expiring state
- `src/test/java/com/tcgdigital/vmcontrol/controller/LockControllerIntegrationTest.java` (modify) — flags per role; history reason

**Steps**

1. LockHistoryDTO: add breakReason copied from history.getLock().getBreakReason(); getReason() returns: action BROKEN -> breakReason (fallback notes); ACQUIRED -> lockReason; EXPIRED/RELEASED/EXTENDED -> notes. Keep the 'timestamp' alias = performedAt.
2. LockStatusDTO: add booleans canAcquire, canRelease, canBreak, canExtend with getters; LockController computes for the current user: canAcquire = !locked && securityService.canPerformOperations(envId); canRelease = canExtend(if expiresAt != null) = locked && holder == me; canBreak = locked && holder != me && (securityService.isAdmin() || securityService.canManageEnvironmentAccess(envId)) - match whatever rule E03 put on POST /break.
3. locks.js loadLockHistory: first column uses Utils.formatRelativeTime(entry.performedAt || entry.timestamp) with an absolute-time title; Reason column shows entry.reason; duration only on RELEASED/BROKEN/EXPIRED rows.
4. locks.js buildLockBanner: drive buttons only from the flags (no Auth.isEnvAdmin guess); when locked by someone else add class 'other' and text 'Held by <name> - operations are blocked for you'; when locked by me add class 'mine'. If lockStatus.expiresAt show <span class="lock-countdown" data-expires-at="...">Auto-releases in 42 min</span>; add class 'expiring' under 15 minutes.
5. Countdown: export Locks.startCountdown(rootSelector) that updates every 30 s; start it from environments.js after rendering the detail and stop it via ContentRouter.onLeave (E02) - or register with RealTime.startPolling('lock-countdown', fn, 30000, { pageScoped: true }). When the countdown reaches 0 re-fetch status.
6. Extend: when canExtend show an 'Extend' button that opens a Modals.show dialog with 30/60/120 minute options and calls Config.API.locks.extend; on 400 show the server message.
7. Acquire dialog: change the help text to 'The lock is released automatically at the end of this time. You will be warned 15 and 5 minutes before.' and only show it when locks expiry is enabled (expose locks.expiry.enabled through the existing config/features endpoint used by js/config.js FEATURES; if none exists, return a lockExpiryEnabled flag in LockStatusDTO).
8. Use Utils.html / escapeHtml (E01) for every interpolation; run build-frontend.ps1.

**Acceptance criteria**

- Given a VIEWER opens an unlocked environment, then no Acquire button is shown; given a USER, then Acquire is shown.
- Given Alice holds the lock, when Bob (USER) opens the page, then the banner is styled as 'held by another user', says operations are blocked, and shows no Release/Extend; an env admin additionally sees Break.
- Given Alice's lock expires in 42 minutes, when she opens the page, then she sees 'Auto-releases in 42 min' updating and an Extend button.
- Given a lock was acquired at 10:00, extended at 10:40 and broken at 11:00 with reason 'stuck deploy', when history is opened, then the rows show 10:00, 10:40 and 11:00 and the BROKEN row's reason is 'stuck deploy'.

**Tests**

- LockControllerIntegrationTest: GET /lock flags for VIEWER, USER, holder, ENV_ADMIN, ADMIN; GET /history returns per-row timestamp and break reason for BROKEN.
- Manual QA: banner states, countdown stops after navigating away (no console errors), extend flow.

## E08

### Cost data correctness & enablement — Fix

Daily cost rows, actuals, reconciliation and rightsizing are numerically correct and fast, and the read-only AWS cost features are on by default with a setup page that proves the IAM permissions.

Sources: H7, H18, M11, M12, G2, LOW-COST-STORAGE-LIFETIME, LOW-COST-TIMEZONE, LOW-COST-UNBOUNDED-PARAMS, LOW-COST-TAG-ACTIVATION-ONCE, LOW-COST-CE-PAGINATION, LOW-COST-CHART-LEAK, LOW-COST-PNG-RESERVATIONS, LOW-COST-WEEKLY-IDLE-LABEL, LOW-COST-PRICE-FALLBACK, LOW-COST-RIGHTSIZING-AFTER-RESIZE, LOW-COST-DEAD-ENDPOINTS · 11 tasks · 15.5 days

#### E08-T01 — Capture the daily cost snapshot for yesterday (UTC) and limit storage to the VM's lifetime

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H7, LOW-COST-STORAGE-LIFETIME, LOW-COST-TIMEZONE · **Depends on:** E00-T01

The 03:00 job captures today, so compute is clamped to about 3 hours while storage is charged for the whole day, which makes compute about 8x too low (H7). Storage is also charged before a VM existed, and day boundaries follow the server timezone, while Cost Explorer uses UTC (low items).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CostSnapshotService.java` (modify) — captureDailySnapshot (line 49-51) captures yesterday (UTC) and recomputes a trailing window; backfill ends yesterday; UTC window bounds in captureForDate
- `src/main/java/com/tcgdigital/vmcontrol/service/VmCostCalculator.java` (modify) — new storageWindowDays(vmCreatedAt, windowStart, windowEnd) clamped to creation and now
- `src/main/java/com/tcgdigital/vmcontrol/service/EstimatedCostProvider.java` (modify) — prorate storage per VM with storageWindowDays instead of the shared windowDays (lines 116, 137, 140)
- `src/main/java/com/tcgdigital/vmcontrol/service/CostDayBoundary.java` (new) — @Component: zone from cost.day-boundary-zone (default UTC), today(), yesterday(), startOf(LocalDate) -> Timestamp
- `src/main/resources/application.properties` (modify) — add cost.day-boundary-zone=UTC and cost.snapshot.recompute-days=3
- `src/test/java/com/tcgdigital/vmcontrol/service/VmCostCalculatorTest.java` (modify) — storage proration cases
- `src/test/java/com/tcgdigital/vmcontrol/service/CostSnapshotServiceTest.java` (new) — date selection, UTC bounds and upsert
- `src/test/java/com/tcgdigital/vmcontrol/service/EstimatedCostProviderTest.java` (modify) — storage limited to VM lifetime

**Steps**

1. Create service/CostDayBoundary.java (@Component). Read @Value("${cost.day-boundary-zone:UTC}") into a ZoneId and take a java.time.Clock (Clock.systemUTC() by default, with a package-private constructor that accepts a Clock for tests). Methods: LocalDate today() = LocalDate.now(clock.withZone(zone)); LocalDate yesterday() = today().minusDays(1); Timestamp startOf(LocalDate d) = Timestamp.from(d.atStartOfDay(zone).toInstant()).
2. CostSnapshotService: inject CostDayBoundary and @Value("${cost.snapshot.recompute-days:3}") int recomputeDays. captureDailySnapshot(): for i = recomputeDays down to 1, call captureForDate(boundary.today().minusDays(i)). This captures yesterday plus re-captures the earlier days, picking up late vm_state_history rows. The upsert (findByEnvironmentEnvironmentIdAndSnapshotDate) already exists.
3. backfillHistoricalSnapshots(days): loop from today-days to today-1, never today. Update the Javadoc ('ending yesterday').
4. captureForDate: replace Timestamp.valueOf(date.atStartOfDay()) and Timestamp.valueOf(date.plusDays(1).atStartOfDay()) with boundary.startOf(date) and boundary.startOf(date.plusDays(1)).
5. VmCostCalculator: add public BigDecimal storageWindowDays(Timestamp vmCreatedAt, Timestamp windowStart, Timestamp windowEnd). It clamps the start with clampToCreation and the end with clampToNow (both exist), returns BigDecimal.ZERO if start >= end, else windowDays(start, end). estimateCost keeps its signature: callers pass the per-VM storage days as windowDays.
6. EstimatedCostProvider.estimateCosts: inside the per-VM loop compute BigDecimal vmStorageDays = vmCostCalculator.storageWindowDays(vm.getCreatedAt(), windowStart, windowEnd). Use it in both the priced branch (estimateCost(..., vmStorageDays)) and the storage-only branch (replace windowDays).
7. In CostEstimationService.buildRightsizingCandidates, the 'after' cost uses BigDecimal.valueOf(COST_WINDOW_DAYS). That is intentional (a 30-day projection): add a one-line comment saying so.
8. Add the two properties to application.properties under 'Cost Management Configuration', with comments.
9. Release note in the PR: existing rows were written with partial-day compute. After deploy an admin runs 'Backfill 30 Days' (POST /api/v1/cost-management/snapshots/backfill?days=30) once to rewrite them.

**Acceptance criteria**

- Given a VM at $0.10/h that ran all of yesterday and the job runs at 03:00, when captureDailySnapshot runs, then yesterday's row holds 24h x $0.10 = $2.40 compute plus 1/30 of monthly storage, and no row is written for today.
- Given a VM created at 18:00 UTC yesterday with 30 GiB, when yesterday is captured, then storage is prorated for 6 hours, not 24.
- Given the server runs in Asia/Kolkata, when the snapshot for 2026-10-06 is computed, then the window is 2026-10-06T00:00Z to 2026-10-07T00:00Z.
- Given recompute-days=3, when the job runs, then rows for D-1, D-2 and D-3 are upserted, not duplicated.

**Tests**

- VmCostCalculatorTest: storageWindowDays returns 0.25 for a VM created 18h into a 24h window, 0 for a VM created after the window, and stops at now for a window that ends in the future.
- EstimatedCostProviderTest: a VM created mid-window gets prorated storage; an unpriced VM's storage-only cost is also prorated.
- CostSnapshotServiceTest (Mockito, fixed Clock): captureDailySnapshot captures D-3..D-1 with UTC bounds (assert the windowStart/windowEnd passed to CostDataProvider.estimateCosts); backfill(30) never writes today; an existing row is reused.

**Notes:** Changes the meaning of each cost_daily_snapshot date: rows are now always complete UTC days. No migration. Run the one-time backfill after deploy.

#### E08-T02 — Re-ingest a trailing window of actuals with pagination via one shared Cost Explorer client, and retry tag activation

- [ ] Done · **Type:** fix · **Estimate:** 2 d · **Risk:** medium · **Sources:** H18, LOW-COST-CE-PAGINATION, LOW-COST-TAG-ACTIVATION-ONCE · **Depends on:** E08-T01

Actuals are taken once at 05:00 from provisional Cost Explorer figures, and any existing value freezes that date forever (H18). GetCostAndUsage pagination is ignored, and tag activation is attempted only once per process even when it fails (low items). Three classes build identical us-east-1 clients that tests cannot mock.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerClientProvider.java` (new) — @Component that lazily builds and holds the single us-east-1 CostExplorerClient; isConfigured()
- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerBillingService.java` (modify) — use the provider; ingestWindow(start,end) with a nextPageToken loop; overwrite inside the trailing window; remove the permanent skip at line 100
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ActualCostIngestionScheduler.java` (modify) — call ingestTrailingWindow(cost.actuals.trailing-days)
- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerTagActivationService.java` (modify) — use the provider; check ListCostAllocationTags; mark done only on success or already active; retry after a backoff
- `src/main/java/com/tcgdigital/vmcontrol/service/ReservationCoverageService.java` (modify) — use the provider instead of its private getClient()
- `src/main/java/com/tcgdigital/vmcontrol/repository/CostDailySnapshotRepository.java` (modify) — remove existsBySnapshotDateAndActualCostIsNotNull once unused
- `src/main/resources/application.properties` (modify) — cost.actuals.trailing-days=3, cost.tagging.activation-retry-minutes=360
- `src/test/java/com/tcgdigital/vmcontrol/service/CostExplorerBillingServiceTest.java` (modify) — pagination, overwrite, window
- `src/test/java/com/tcgdigital/vmcontrol/service/CostExplorerTagActivationServiceTest.java` (new) — retry semantics

**Steps**

1. Create CostExplorerClientProvider (@Component) holding aws.access-key and aws.secret-key. It exposes boolean isConfigured() and CostExplorerClient client(), which builds the client lazily with double-checked locking and the same timeouts as today. Add a package-private constructor that accepts a prebuilt client for tests.
2. Replace the private getClient() and the accessKey/secretKey fields in CostExplorerBillingService, CostExplorerTagActivationService and ReservationCoverageService with the provider. isAvailable() delegates to provider.isConfigured(), plus each class's own enabled flag where it has one.
3. CostExplorerBillingService: add IngestResult ingestWindow(LocalDate startInclusive, LocalDate endExclusive). It sends ONE GetCostAndUsage request for the whole range (DAILY, UnblendedCost, GroupBy TAG tcgPrefix+environment) and loops while response.nextPageToken() != null, passing nextPageToken. Parse each ResultByTime on its own, using its timePeriod().start() as the date. Add a package-private parseCostByDateAndEnvironmentTag(List<ResultByTime>) -> Map<LocalDate, Map<String,BigDecimal>> that reuses the existing per-group parsing in parseCostByEnvironmentTag.
4. Call applyActualCosts once per date. Remove the 'already ingested' guard: values inside the window are always overwritten, so provisional numbers get corrected.
5. Add ingestTrailingWindow(int days) = ingestWindow(boundary.today().minusDays(days), boundary.today()), using CostDayBoundary from E08-T01. Keep ingestDailyActualCosts(date) as a thin wrapper that calls ingestWindow(date, date.plusDays(1)) so existing callers compile.
6. Rewrite backfillActualCosts(days) as a single ingestWindow(today-days, today): one billed call per page instead of one per day.
7. ActualCostIngestionScheduler: inject @Value("${cost.actuals.trailing-days:3}") and call ingestTrailingWindow(trailingDays) instead of ingestDailyActualCosts(LocalDate.now().minusDays(1)). Fix the class Javadoc ('re-ingests the last N days because Cost Explorer figures stay provisional for about 72h').
8. Delete CostDailySnapshotRepository.existsBySnapshotDateAndActualCostIsNotNull if a grep shows nothing else uses it.
9. CostExplorerTagActivationService: replace AtomicBoolean activationRequested with volatile boolean activated and volatile Instant lastAttempt. In ensureTagKeysActivated: return if activated, and return if lastAttempt is within cost.tagging.activation-retry-minutes. Otherwise call ListCostAllocationTags(tagKeys = keys); if every key is Active, set activated=true and return. Otherwise call UpdateCostAllocationTagsStatus: if response.errors() is empty set activated=true; on an exception or errors, log a warning and leave activated=false so the next attempt after the backoff retries.
10. Add the new properties with comments.

**Acceptance criteria**

- Given yesterday's prod actual was stored as $40 and Cost Explorer now reports $95, when the 05:00 job runs within the trailing window, then the row is updated to $95.
- Given Cost Explorer returns two pages for a 30-day backfill, when backfillActualCosts(30) runs, then groups from both pages are applied and exactly two GetCostAndUsage calls are made.
- Given UpdateCostAllocationTagsStatus threw AccessDenied, when tag reconciliation runs after the retry interval, then activation is attempted again; given the tag is already Active, then no Update call is made.

**Tests**

- CostExplorerBillingServiceTest, with a mocked CostExplorerClient through the provider: (a) two pages are merged and the second request carries the token; (b) each ResultByTime's date comes from timePeriod.start; (c) an existing actual inside the window is overwritten; (d) a disabled or unconfigured service makes zero calls.
- CostExplorerTagActivationServiceTest: after a failure it does not retry before the backoff but does after it; ListCostAllocationTags showing all keys active skips Update; after a success later calls are no-ops.

**Notes:** The 05:00 job still makes 1 billed call per day, plus 1 per extra page. No migration.

#### E08-T03 — Compare estimated and actual cost over the same days and report actuals coverage

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** H18 · **Depends on:** E08-T02

Reconciliation sums estimates for every day in the window but actuals only for ingested days, so variance reads about +800% when actuals cover 10 of 90 days (H18). The weekly cost report has the same mismatch.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CostReconciliationService.java` (modify) — accumulate the estimate on actual-covered days separately (lines 49-51); coverage counts
- `src/main/java/com/tcgdigital/vmcontrol/dto/CostReconciliationRowDTO.java` (modify) — append estimatedCostOnActualDays, daysWithActuals, daysInWindow
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — reconciliation export adds coverage columns
- `src/main/java/com/tcgdigital/vmcontrol/repository/CostDailySnapshotRepository.java` (modify) — sumByEnvironmentBetween/EnvironmentCostTotal add estimatedOnActualDays and actualDays
- `src/main/java/com/tcgdigital/vmcontrol/service/WeeklyReportService.java` (modify) — weekly cost workbook shows 'Actual (n/7 days)' and a like-for-like estimate column
- `src/main/java/com/tcgdigital/vmcontrol/dto/WeeklyCostReportRowDTO.java` (modify) — append actualDays and estimatedOnActualDays
- `src/test/java/com/tcgdigital/vmcontrol/service/CostReconciliationServiceTest.java` (modify) — matched-days variance and coverage
- `src/test/java/com/tcgdigital/vmcontrol/service/WeeklyReportServiceTest.java` (modify) — actualDays column

**Steps**

1. CostReconciliationService.Accumulator: add BigDecimal estimatedOnActualDays, int daysWithActuals and Set<Date> days. For every snapshot add its date to days. When actualCost != null, also add its estimate to estimatedOnActualDays and increment daysWithActuals.
2. variancePercent = (estimatedOnActualDays - actualTotal) / actualTotal x 100, only when actualTotal > 0. Keep estimatedCost as the full-window total for the 'Estimated' column.
3. CostReconciliationRowDTO: append BigDecimal estimatedCostOnActualDays, int daysWithActuals and int daysInWindow (days.size()). Update every constructor call (grep 'new CostReconciliationRowDTO').
4. Controller exportReconciliation: header becomes Environment, Estimated Cost (window), Estimated Cost (days with actuals), Actual Cost, Variance %, Actual coverage (days).
5. CostDailySnapshotRepository.sumByEnvironmentBetween: add 'SUM(CASE WHEN c.actualCost IS NOT NULL THEN c.estimatedCost ELSE 0 END) AS estimatedOnActualDays' and 'SUM(CASE WHEN c.actualCost IS NOT NULL THEN 1 ELSE 0 END) AS actualDays', and add both getters to EnvironmentCostTotal.
6. WeeklyReportService.buildCostRows: fill the new WeeklyCostReportRowDTO fields. In buildCostWorkbook, render the actual as '<amount> (n/7 days)' when actualDays < 7, and add a column 'Estimated (days with actuals)'.
7. The UI for the new reconciliation fields comes in E08-T09. The existing table keeps working because fields are only appended.

**Acceptance criteria**

- Given 90 days of estimates at $100/day and actuals of $100/day for only the last 10 days, when reconciliation is requested for 90 days, then variance is 0.0% and coverage is 10/90.
- Given no actuals, when reconciliation runs, then actualCost and variancePercent are null and daysWithActuals is 0.
- Given actuals for 3 of the 7 report days, when the weekly cost workbook is built, then the Actual column reads '<sum> (3/7 days)'.

**Tests**

- CostReconciliationServiceTest: matched-days variance, null when there are no actuals, coverage counts.
- WeeklyReportServiceTest: buildCostRows sets actualDays; the workbook rows contain the coverage text (assert on the Object[] rows captured from a mocked ExcelExportService).

**Notes:** The E17 savings ledger later reuses estimatedOnActualDays to check itself against actuals.

#### E08-T04 — Build the cost model once per request burst: batch the seed query, cache bundles and Compute Optimizer results, align KPI formulas

- [ ] Done · **Type:** fix · **Estimate:** 2 d · **Risk:** medium · **Sources:** M12 · **Depends on:** E08-T01

Each of six endpoints rebuilds per-VM bundles, with one seed-status query per VM and uncached, unpaginated Compute Optimizer calls: about 3,500 queries and 8 serial AWS calls for 500 VMs. Two KPI tiles also disagree with their tables (M12).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/repository/VmStateHistoryRepository.java` (modify) — new findLatestBeforeForVms(vmIds, before)
- `src/main/java/com/tcgdigital/vmcontrol/service/EstimatedCostProvider.java` (modify) — resolveSeedStatus reads from one batched query instead of a per-VM findTopBy... call
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — short-lived bundle memo plus invalidateBundles(); KPI alignment in getSummary (lines 102-144)
- `src/main/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerService.java` (modify) — nextToken pagination; per-region cache with TTL
- `src/main/java/com/tcgdigital/vmcontrol/service/CostSnapshotService.java` (modify) — backfill calls costEstimationService.invalidateBundles()
- `src/main/java/com/tcgdigital/vmcontrol/dto/CostSummaryDTO.java` (modify) — append scaleUpCandidateCount; Javadoc for the aligned formulas
- `src/main/resources/application.properties` (modify) — cost.bundle-cache-seconds=60, cost.optimizer.cache-minutes=60
- `src/test/java/com/tcgdigital/vmcontrol/service/CostEstimationServiceTest.java` (modify) — cache and KPI alignment
- `src/test/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerServiceTest.java` (modify) — pagination and cache
- `src/test/java/com/tcgdigital/vmcontrol/service/EstimatedCostProviderTest.java` (modify) — single seed query

**Steps**

1. VmStateHistoryRepository: add @Query("SELECT h FROM VmStateHistory h WHERE h.vm.vmId IN :vmIds AND h.changedAt = (SELECT MAX(h2.changedAt) FROM VmStateHistory h2 WHERE h2.vm = h.vm AND h2.changedAt < :before)") List<VmStateHistory> findLatestBeforeForVms(@Param("vmIds") List<String> vmIds, @Param("before") Timestamp before). Check V1 for an index on vm_state_history(vm_id, created_at); if there is none, add one in a new migration (next free V number at implementation time).
2. EstimatedCostProvider.estimateCosts: before the loop, call findLatestBeforeForVms once for the VMs that need a lookback (no in-window transitions, or a first transition with a null previousStatus) and build a Map<vmId, VmStatus>; on ties keep the last row. resolveSeedStatus reads the map and falls back to vm.getStatus(). Remove the per-VM findTopByVmVmIdAndChangedAtLessThanOrderByChangedAtDesc call (keep the repository method only if a grep shows other users).
3. CostEstimationService: add private record BundleCache(Instant builtAt, List<VmCostBundle> bundles), a private volatile BundleCache field and @Value("${cost.bundle-cache-seconds:60}") long cacheSeconds. buildBundles() returns the cached list if it is younger than the TTL; otherwise it rebuilds inside a synchronized block that re-checks first. Add public void invalidateBundles(). Call it from CostSnapshotService.backfillHistoricalSnapshots (and from VmResizeService in E08-T05).
4. ComputeOptimizerService.getEc2Recommendations(region): loop GetEc2InstanceRecommendations while response.nextToken() != null, passing nextToken. Cache the parsed map per region in a ConcurrentHashMap<String, CachedRecs(Instant fetchedAt, Map<String,Recommendation>)> with TTL cost.optimizer.cache-minutes. Never cache failures: return Map.of() without storing.
5. KPI alignment in getSummary: idleWasteMonthlyCost = the sum of monthlyIdleCost(b) over idle bundles, the same number as the table's 'Monthly Idle Cost' column. rightsizingPotentialSavings = the sum of estimatedMonthlySavings where direction == SCALE_DOWN and savings > 0, and rightsizingCandidateCount = the count of those. Append int scaleUpCandidateCount to CostSummaryDTO for the remaining candidates.
6. Make sure getSummary builds bundles once: it already reuses the bundle VM list for the previous-period estimate, so just verify there is no second buildBundles call.

**Acceptance criteria**

- Given 500 active VMs, when the cost page calls the six bundle-backed endpoints within 60 s, then buildBundles queries the database once and the seed lookup is a single query.
- Given Compute Optimizer returns 2 pages for a region, when recommendations are requested twice within the cache TTL, then exactly 2 AWS calls are made in total.
- Given idle VMs whose table rows show 'Monthly Idle Cost' totalling $120, when the summary is requested, then the Idle Waste KPI is $120.
- Given 3 scale-down candidates saving $10 each and 1 scale-up costing $15 more, when the summary is requested, then Rightsizing Potential is $30 with 3 candidates and scaleUpCandidateCount = 1.

**Tests**

- EstimatedCostProviderTest: findLatestBeforeForVms is called once and the per-VM findTopBy... is never called.
- CostEstimationServiceTest: two getSummary calls build bundles once (verify vmRepository.findByIsActiveTrueFetchGroupAndEnvironment times(1)); invalidateBundles forces a rebuild; the KPI sums match the table rows.
- ComputeOptimizerServiceTest: pages are merged, a second call within the TTL hits the cache, and a failure is not cached.

**Notes:** The cache is per JVM; up to 60 s of staleness is acceptable for an estimates page.

#### E08-T05 — Validate rightsizing Apply on the server and only trust cheaper OVERPROVISIONED Compute Optimizer options

- [ ] Done · **Type:** security · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** M11 · **Depends on:** E08-T04, E00-T02, E00-T05

targetInstanceType is only checked as not blank, so a replayed request can resize a VM to p4d.24xlarge. The first Compute Optimizer option is used whatever its finding, so an under-provisioned VM labelled 'Scale Down' is enlarged (M11).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerService.java` (modify) — parseRecommendations keeps only Finding.OVERPROVISIONED and all options in rank order
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — use a CO option only if its rate is below the current rate (lines 387-403); drop SCALE_DOWN rows with no saving; new findCandidate(vmId)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmResizeService.java` (modify) — require target == the current recommendation before calling AWS; invalidate bundles after success
- `src/main/java/com/tcgdigital/vmcontrol/dto/ApplyRightsizingRequestDTO.java` (modify) — @Pattern on targetInstanceType
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — Javadoc on applyRightsizing (lines 142-147) for the 400 outcomes; no logic change
- `src/test/java/com/tcgdigital/vmcontrol/service/VmResizeServiceTest.java` (new) — a mismatch is rejected, a match proceeds
- `src/test/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerServiceTest.java` (modify) — finding filter
- `src/test/java/com/tcgdigital/vmcontrol/controller/CostManagementControllerSecurityTest.java` (new) — ADMIN-only and validation 400

**Steps**

1. ComputeOptimizerService.Recommendation becomes record Recommendation(String instanceId, String finding, List<String> optionTypesByRank). In parseRecommendations, skip any rec where rec.finding() != Finding.OVERPROVISIONED (software.amazon.awssdk.services.computeoptimizer.model.Finding), and collect recommendationOptions sorted by rank.
2. CostEstimationService.buildRightsizingCandidates: for SCALE_DOWN with a recommendation, walk optionTypesByRank and pick the first type whose pricingReferenceService.lookupHourlyRate(provider, type, region) is known and below b.estimate().hourlyRate(). If none is cheaper, fall back to suggestSmallerType (source cpu-threshold-rule). After computing savings, skip SCALE_DOWN candidates whose savings is null or <= 0.
3. CostEstimationService: add public Optional<RightsizingCandidateDTO> findCandidate(String vmId). It builds candidates from the cached bundles (E08-T04) and returns the one for that vmId.
4. VmResizeService.applyInstanceTypeChange: inject CostEstimationService (no cycle: CostEstimationService does not depend on VmResizeService). After the STOPPED and lock checks, if findCandidate(vmId) is empty or its suggestedInstanceType does not equal targetInstanceType, throw new ValidationException("Target instance type does not match the current recommendation for this VM") before any audit or AWS call. Call costEstimationService.invalidateBundles() after a successful change.
5. ApplyRightsizingRequestDTO: add @Pattern(regexp = "^[a-z0-9-]+\\.[a-z0-9]+$") to targetInstanceType.
6. Javadoc on applyRightsizing: 400 for an invalid or non-matching type; the existing lock and status checks still apply.

**Acceptance criteria**

- Given a VM whose current recommendation is t3.small, when an admin posts targetInstanceType=p4d.24xlarge, then the response is 400 and no AWS call is made.
- Given Compute Optimizer marks a VM UNDERPROVISIONED, when candidates are built, then no compute-optimizer 'Scale Down' row exists for it.
- Given the top Compute Optimizer option costs more than the current type, when candidates are built, then the next cheaper option or the rule-based suggestion is used, and no SCALE_DOWN row shows a negative saving.
- Given a USER, when they POST /api/v1/cost-management/rightsizing/apply, then 403.

**Tests**

- VmResizeServiceTest (Mockito): a mismatch throws ValidationException and never calls AwsCloudProviderService.changeInstanceType; a match calls it once and calls invalidateBundles.
- ComputeOptimizerServiceTest: OPTIMIZED and UNDERPROVISIONED findings are excluded; rank order is kept.
- CostEstimationServiceTest: a CO option dearer than the current type is skipped; a zero-saving scale-down is dropped.
- CostManagementControllerSecurityTest (SecuredWebTestBase): USER -> 403 on apply; ADMIN with a malformed type -> 400.

#### E08-T06 — Stop re-recommending a resize based on metrics from before the last instance-type change

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-COST-RIGHTSIZING-AFTER-RESIZE · **Depends on:** E08-T05

After an Apply, the scale-down rule keeps reading the previous days' CPU from the old size and recommends shrinking again (low item: rightsizing re-recommends after a resize).

**Files**

- `src/main/resources/db/migration/V<next>__add_inventory_instance_type_changed_at.sql` (new) — ALTER TABLE vm_inventory_snapshot ADD COLUMN instance_type_changed_at TIMESTAMP NULL (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/VmInventorySnapshot.java` (modify) — instanceTypeChangedAt field and accessors
- `src/main/java/com/tcgdigital/vmcontrol/service/VmInventoryService.java` (modify) — saveInventory (line ~120) stamps instanceTypeChangedAt when the type differs from the stored one
- `src/main/java/com/tcgdigital/vmcontrol/service/VmResizeService.java` (modify) — stamp instanceTypeChangedAt=now on success
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmInventorySnapshotRepository.java` (modify) — InstanceTypeProjection adds getInstanceTypeChangedAt
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — ignore daily rows and CO recommendations from before the change; cooldown property
- `src/main/resources/application.properties` (modify) — rightsizing.post-resize-cooldown-days=14
- `src/test/java/com/tcgdigital/vmcontrol/service/CostEstimationServiceTest.java` (modify) — cooldown cases
- `src/test/java/com/tcgdigital/vmcontrol/service/VmInventoryServiceTest.java` (new) — type-change stamping

**Steps**

1. Add the migration (next free V number) and the entity field @Column(name = "instance_type_changed_at") private Timestamp instanceTypeChangedAt with getter and setter.
2. VmInventoryService.saveInventory: before overwriting instanceType, if snapshot.getInstanceType() != null and it differs from data.getInstanceType(), set instanceTypeChangedAt = now. VmResizeService sets it to now after a successful changeInstanceType.
3. VmInventorySnapshotRepository.findInstanceTypesByVmIds: add instanceTypeChangedAt to the select and to the InstanceTypeProjection interface.
4. CostEstimationService: carry instanceTypeChangedAt in VmCostBundle. In computeScaleDownCandidates, drop daily rows whose bucketDate is on or before the change date before applying the consecutive-days rule. In buildRightsizingCandidates, ignore a Compute Optimizer recommendation while instanceTypeChangedAt is after now minus rightsizing.post-resize-cooldown-days (default 14, because Compute Optimizer needs about 14 days of new data).
5. Add the property with a comment.

**Acceptance criteria**

- Given a VM was resized from m5.xlarge to m5.large yesterday, when candidates are built today, then it is not a scale-down candidate until it has scaleDownConsecutiveDays full days of post-resize metrics.
- Given the instance type was changed outside the app, when inventory sync next runs, then instanceTypeChangedAt is set.

**Tests**

- CostEstimationServiceTest: pre-change daily rows are ignored; a Compute Optimizer recommendation inside the cooldown is ignored and the rule applies.
- VmInventoryServiceTest (Mockito): a type change stamps the timestamp; an unchanged type keeps the previous value.

#### E08-T07 — Flag any-region price fallbacks as approximate and correct the weekly idle-waste label

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-COST-PRICE-FALLBACK, LOW-COST-WEEKLY-IDLE-LABEL · **Depends on:** E08-T06

PricingReferenceService.lookupHourlyRate silently uses another region's rate when the VM's region has none. The weekly idle report calls a 30-day capped figure 'this week' (low items).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/PricingReferenceService.java` (modify) — PriceLookupResult gains an approximate flag
- `src/main/java/com/tcgdigital/vmcontrol/service/CostDataProvider.java` (modify) — VmCostEstimate gains priceApproximate
- `src/main/java/com/tcgdigital/vmcontrol/service/EstimatedCostProvider.java` (modify) — propagate the flag
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmCostDetailDTO.java` (modify) — append priceApproximate
- `src/main/java/com/tcgdigital/vmcontrol/dto/RightsizingCandidateDTO.java` (modify) — append priceApproximate
- `src/main/java/com/tcgdigital/vmcontrol/dto/CostSummaryDTO.java` (modify) — append approximatePriceVmCount
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — fill the new fields
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — VM detail export adds a 'Price basis' column
- `src/main/java/com/tcgdigital/vmcontrol/service/WeeklyReportService.java` (modify) — idle text and headers say '30 days'
- `src/test/java/com/tcgdigital/vmcontrol/service/PricingReferenceServiceTest.java` (new) — exact vs fallback

**Steps**

1. PricingReferenceService.PriceLookupResult becomes (boolean priceKnown, BigDecimal hourlyRate, boolean approximate). An exact provider:type:region hit gives approximate=false; the ratesByTypeAnyRegion tier gives approximate=true; unknown() gives false. Fix every caller (grep lookupHourlyRate).
2. CostDataProvider.VmCostEstimate: append boolean priceApproximate. EstimatedCostProvider sets it from rate.approximate().
3. Append Boolean priceApproximate to VmCostDetailDTO and RightsizingCandidateDTO (for a candidate, true if the current or the suggested rate is approximate), and int approximatePriceVmCount to CostSummaryDTO. Fill them in CostEstimationService.
4. Controller exportVmCostDetail: add a 'Price basis' column with 'Exact region' or 'Other-region rate (approx.)'.
5. WeeklyReportService.sendWeeklyIdleWasteReport: change the bell and email text to 'Idle cost over the last 30 days: <total>' and the workbook headers to 'Cost (30 days)' and 'Idle cost (30 days, capped)'. Keep the week-over-week delta suffix.
6. The UI badge comes in E08-T09.

**Acceptance criteria**

- Given the pricing file has m5.large only for us-east-1 and a VM runs m5.large in ap-south-1, when VM detail is requested, then costKnown=true and priceApproximate=true.
- Given the weekly idle report runs, then the bell message says 'over the last 30 days', not 'this week'.

**Tests**

- PricingReferenceServiceTest: an exact region gives approximate=false, the fallback gives true, unknown gives priceKnown=false.
- EstimatedCostProviderTest: priceApproximate is propagated.
- WeeklyReportServiceTest: verify the notifyWeeklyReportSent message text.

#### E08-T08 — Bound admin cost parameters, remove the dead spend-by-team endpoint and populate covered_cost

- [ ] Done · **Type:** cleanup · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-COST-UNBOUNDED-PARAMS, LOW-COST-DEAD-ENDPOINTS · **Depends on:** E08-T02, E00-T04, E00-T05

days, forecastDays and size have no bounds, so one request can trigger hundreds of billed Cost Explorer calls. GET /spend-by-team is never called by the UI. reservation_coverage_snapshot.covered_cost is always null (low items).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — @Validated with @Min/@Max on days params; Paging.of for page/size; delete getSpendByTeam
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — delete getSpendByTeam (keep getSpendTrendByTeam)
- `src/main/resources/static/js/config.js` (modify) — remove costManagement.spendByTeam (line 197)
- `src/main/java/com/tcgdigital/vmcontrol/service/ReservationCoverageService.java` (modify) — set coveredCost from SP spendCoveredBySavingsPlans; snapshotDate = the day covered (yesterday)
- `src/main/java/com/tcgdigital/vmcontrol/dto/ReservationCoverageSnapshotDTO.java` (modify) — Javadoc for coveredCost
- `src/test/java/com/tcgdigital/vmcontrol/controller/CostManagementControllerSecurityTest.java` (modify) — out-of-range params -> 400
- `src/test/java/com/tcgdigital/vmcontrol/service/ReservationCoverageServiceTest.java` (new) — covered-cost parsing and date

**Steps**

1. Annotate CostManagementController with @Validated. Bounds: spend-trend and spend-trend-by-team days @Min(1) @Max(400); reconciliation and its export days @Min(1) @Max(400); snapshots/backfill days @Min(1) @Max(90); actuals/backfill days @Min(1) @Max(90); reservations/coverage days @Min(1) @Max(400); forecast historyDays @Min(14) @Max(400) and forecastDays @Min(1) @Max(90).
2. Replace PageRequest.of(page, size) on idle-waste, rightsizing and vm-detail with Paging.of(page, size, 100) (E00-T04).
3. Confirm that GlobalExceptionHandler (after E00-T05) maps ConstraintViolationException and HandlerMethodValidationException to 400; add the mapping here if it is missing.
4. Delete GET /spend-by-team, CostEstimationService.getSpendByTeam and config.js costManagement.spendByTeam. Keep /tags/status and /reservations/utilization: E08-T11 uses them.
5. ReservationCoverageService.captureDailySnapshot: let coveredDay = boundary.yesterday() (CostDayBoundary). Use it as the snapshotDate and as the period start, with end = coveredDay+1. In captureSavingsPlansCoverage also parse coverage.spendCoveredBySavingsPlans() into snapshot.setCoveredCost. Update the DTO Javadoc: coveredCost = On-Demand-equivalent spend covered by Savings Plans that day.

**Acceptance criteria**

- Given an admin calls POST /actuals/backfill?days=500, then 400 and no Cost Explorer call is made.
- Given size=100000 on /vm-detail, then the page size is clamped to 100.
- Given GET /api/v1/cost-management/spend-by-team, then 404.
- Given Savings Plans coverage returns spendCoveredBySavingsPlans=12.34, when the snapshot is captured, then covered_cost=12.34 and snapshot_date is yesterday.

**Tests**

- CostManagementControllerSecurityTest: out-of-range days -> 400 for backfill, forecast and trend; size clamped (assert the page size in the response).
- ReservationCoverageServiceTest, with a mocked client through CostExplorerClientProvider: coveredCost is parsed and the date is yesterday.

#### E08-T09 — Make the cost page resilient: per-panel loading, chart lifecycle without leaks, full PNG export, coverage and approximate-price display

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** M12, H18, LOW-COST-CHART-LEAK, LOW-COST-PNG-RESERVATIONS · **Depends on:** E08-T03, E08-T07, E08-T08, E01, E02

One failed request blanks the whole cost page (M12, cost-management.js:115-144). Charts are re-initialised without disposal and the window resize handler survives navigation; the PNG export skips the reservations chart (low items). The new coverage (E08-T03) and approximate-price (E08-T07) fields need display.

**Files**

- `src/main/resources/static/js/features/cost-management.js` (modify) — allSettled with per-panel error and retry; chart() disposes the existing instance; ContentRouter.onLeave teardown; PNG export includes reservations; coverage column; approximate badge; KPI subtitles
- `src/main/resources/static/css/features/cost-management.css` (modify) — panel error state and approximate badge
- `src/main/resources/static/dist/app.js` (modify) — regenerated by build-frontend.ps1 (never hand-edit)
- `src/main/resources/static/dist/app.css` (modify) — regenerated by build-frontend.ps1

**Steps**

1. fetchAll(): replace Promise.all with Promise.allSettled over a keyed list ([{key:'summary', url: ...}, ...]). For a fulfilled result, assign state[key] (mapPage/paginateArray as today). For a rejected one, set state.errors[key] = true. Capture const token = ContentRouter.token() before the requests and return early if !ContentRouter.isCurrent(token) (E02). Then render() once.
2. Each panel builder (KPI strip, breakdown charts, trend, reservations, reconciliation, idle, rightsizing, detail) renders an error card when state.errors[key] is set, built with Utils.html: a div.cost-panel-error with role=alert, the text "Couldn't load <label>." and a Retry button.cost-retry carrying data-key. A delegated click handler (namespace click.costRetry) refetches only that key and re-renders that panel (charts: re-run chart(id, option)).
3. chart(id, option): if chartRegistry.has(id), dispose that instance before echarts.init. This fixes the leak in triggerBackfill, which re-creates 'cost-chart-trend'.
4. bindEvents: register ContentRouter.onLeave(() => { disposeCharts(); $(window).off('resize.costManagementCharts'); $('#content-area').off('click.rightsizingApply click.costRetry'); }).
5. exportPng: add 'cost-chart-reservations' to the list of chart ids swapped for static images.
6. Reconciliation table: add a 'Coverage' column showing daysWithActuals/daysInWindow days. When variance is null show an em dash with title 'No actual billing data yet'. Add a note under the header: 'Variance compares only days that have actual billing data.'
7. In VM detail and rightsizing cost cells, when priceApproximate is true append a span.badge.badge-approx '≈' with title 'Rate from another region — approximate'. The 'Pricing Coverage' KPI subtitle adds '<n> approximate' when approximatePriceVmCount > 0.
8. The Rightsizing KPI subtitle becomes '<rightsizingCandidateCount> saving candidate(s)', plus ' · <scaleUpCandidateCount> need more capacity' when that is > 0.
9. Run .\build-frontend.ps1 and commit the regenerated dist files.

**Acceptance criteria**

- Given /forecast returns 500, when the cost page loads, then every other panel renders, the trend panel shows an error with Retry, and Retry refetches only /forecast.
- Given the admin clicks 'Backfill 30 Days' twice, then the trend container holds exactly one ECharts instance and one SVG.
- Given the admin navigates from Cost Management to Dashboard, then window resizes no longer reach cost charts and every cost chart is disposed.
- Given Export PNG, then the image contains the reservations chart.

**Tests**

- No JS unit-test harness covers features today, so attach a manual checklist to the PR covering the four acceptance criteria at 1440px and 390px. If the test/ Playwright harness can reach the page, add a spec that stubs /forecast with a 500 and asserts the other panels render.

#### E08-T10 — Turn on the safe cost features by default, add a 'cost' configuration profile and document setup

- [ ] Done · **Type:** enable · **Estimate:** 1 d · **Risk:** medium · **Sources:** G2 · **Depends on:** E08-T02, E08-T08, E06-T01, E00-T01

Actuals, Compute Optimizer, RI/SP coverage and weekly reports exist but ship switched off, so a default deployment runs only estimates (G2).

**Files**

- `src/main/resources/application.properties` (modify) — defaults: cost.actuals.enabled, cost.optimizer.enabled, cost.reservations.enabled and notification.weekly-reports.enabled become true; tagging stays false
- `src/main/resources/application-cost.properties` (new) — opt-in profile enabling tagging and the weekly report emails
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ActualCostIngestionScheduler.java` (modify) — @ConditionalOnProperty matchIfMissing=true; @Value default true
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ReservationCoverageScheduler.java` (modify) — same
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/WeeklyReportScheduler.java` (modify) — same
- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerBillingService.java` (modify) — @Value default true
- `src/main/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerService.java` (modify) — @Value default true; class Javadoc
- `src/main/java/com/tcgdigital/vmcontrol/service/ReservationCoverageService.java` (modify) — @Value default true
- `src/test/resources/application.properties` (modify) — explicit false for actuals, optimizer, reservations and weekly-reports so tests never touch AWS
- `docs/aws-iam-permissions.md` (modify) — per-feature IAM policy JSON, billed-call notes, default-state column
- `docs/cost-management-setup.md` (new) — how to enable each feature, the 'cost' profile, tag activation lead time, Compute Optimizer opt-in
- `src/test/java/com/tcgdigital/vmcontrol/config/CostDefaultsTest.java` (new) — asserts property defaults and the profile

**Steps**

1. application.properties: change the env-var fallbacks to COST_ACTUALS_ENABLED:true, COST_OPTIMIZER_ENABLED:true, COST_RESERVATIONS_ENABLED:true and NOTIFICATION_WEEKLY_REPORTS_ENABLED:true. Keep cost.tagging.enabled=false (it writes AWS tags) and every notification.email.* flag false. Rewrite the comments to explain why each default is safe: the calls are read-only, each service checks that AWS credentials exist, Cost Explorer costs about 3 billed calls a day, and weekly reports only create bell notifications unless email is enabled.
2. Make the code defaults match: @Value("${cost.actuals.enabled:true}") etc., and @ConditionalOnProperty(..., matchIfMissing = true) on ActualCostIngestionScheduler, ReservationCoverageScheduler and WeeklyReportScheduler. Update their Javadoc ('Disabled by default' becomes 'Enabled by default; read-only').
3. Create application-cost.properties (activate with SPRING_PROFILES_ACTIVE=prod,cost). It sets cost.tagging.enabled=true and the three notification.email.weekly-*-report.enabled flags to true. Its header comment says the email flags also need notification.email.enabled=true and SMTP.
4. src/test/resources/application.properties: add explicit false values for the four flags, alongside E00-T01's scheduler-disable properties.
5. docs/aws-iam-permissions.md: add a 'Cost features' section with one IAM policy JSON per feature. Actuals: ce:GetCostAndUsage. Tagging: ec2:CreateTags, eks:TagResource, ce:UpdateCostAllocationTagsStatus, ce:ListCostAllocationTags. Optimizer: compute-optimizer:GetEC2InstanceRecommendations, compute-optimizer:GetEnrollmentStatus. Reservations: ce:GetReservationCoverage, ce:GetReservationUtilization, ce:GetSavingsPlansCoverage, ce:GetSavingsPlansUtilization. Pre-flight: sts:GetCallerIdentity, iam:SimulatePrincipalPolicy. Update the existing table's default-state wording.
6. docs/cost-management-setup.md: a table of features (default, property, env var, IAM, AWS-side prerequisite, billing impact). Prerequisites: cost allocation tags take up to 24h to activate; Compute Optimizer needs opt-in and about 14 days of data. End with a 'first day' checklist that points to the Cost Setup page (E08-T11).

**Acceptance criteria**

- Given a fresh deployment with AWS credentials and no cost env vars, when the app starts, then the actuals, reservations and weekly-report schedulers are registered and Compute Optimizer recommendations are requested, while tag writes stay disabled.
- Given a deployment without AWS credentials, when those schedulers fire, then they log and make no AWS calls.
- Given SPRING_PROFILES_ACTIVE=prod,cost, then cost.tagging.enabled resolves to true.

**Tests**

- CostDefaultsTest: load application.properties through a StandardEnvironment with no env vars and assert the four flags are true and tagging is false; with the 'cost' profile tagging is true.
- Existing integration tests stay green with the test overrides.

**Notes:** Explicit G2 product decision: read-only AWS cost features default to on. Writing tags stays opt-in, per the brief's safe-default rule. Release note: about $0.03/day of Cost Explorer charges.

#### E08-T11 — Add an admin Cost Setup page with a pre-flight IAM and feature check

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G2 · **Depends on:** E08-T10, E06-T01, E01, E02

Admins cannot see which cost features are on, whether IAM allows them, or whether tags are active. The current doc lists most permissions as unverified (G2).

**Files**

- `build.gradle` (modify) — add software.amazon.awssdk:iam (BOM-managed)
- `src/main/java/com/tcgdigital/vmcontrol/service/CostSetupService.java` (new) — feature flag status, IAM simulation and free live probes
- `src/main/java/com/tcgdigital/vmcontrol/dto/CostSetupStatusDTO.java` (new) — features[] {key, label, enabled, property, envVar, lastRunAt, checks[] {action, result, hint}}
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /setup/status and POST /setup/check (ADMIN)
- `src/main/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerService.java` (modify) — getEnrollmentStatus(region)
- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerTagActivationService.java` (modify) — public tagKeyStatus(keys) via ListCostAllocationTags
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — canTagInstance(region, instanceId): CreateTags with dryRun(true)
- `src/main/resources/static/js/features/cost-setup.js` (new) — CostSetup page module
- `src/main/resources/static/css/features/cost-setup.css` (new) — status list styles
- `src/main/resources/static/js/core/router.js` (modify) — 'cost-setup' route and handler
- `src/main/resources/static/index.html` (modify) — admin-only sidebar link after Cost Management; css and script tags
- `src/main/resources/static/js/config.js` (modify) — costManagement.setupStatus and setupCheck
- `build-frontend.ps1` (modify) — include cost-setup.js/css in the bundle
- `src/test/java/com/tcgdigital/vmcontrol/service/CostSetupServiceTest.java` (new) — status and simulation mapping
- `src/test/java/com/tcgdigital/vmcontrol/controller/CostManagementControllerSecurityTest.java` (modify) — setup endpoints are ADMIN-only

**Steps**

1. build.gradle: add implementation 'software.amazon.awssdk:iam' next to the other AWS modules.
2. CostSetupService.getStatus() makes NO AWS calls. For features snapshot, actuals, tagging, optimizer, reservations and weeklyReports it returns: enabled flag (via @Value), property and env var names, whether credentials are configured, and the last run of each job's lock row from scheduled_job_lock (the column E06-T01 records). For tagging it also returns the tagged/untagged counts from vmRepository.countByIsActiveTrueAndTagsSyncedAtIsNotNull/IsNull; /tags/status stays as is.
3. CostSetupService.runChecks() runs only from an explicit button. (a) STS GetCallerIdentity gives the principal ARN. (b) IAM SimulatePrincipalPolicy(policySourceArn=principal, actionNames=every action listed in E08-T10's doc) maps each EvaluationResult to ALLOWED or DENIED; if the simulate call itself is denied, mark those rows UNKNOWN with the hint 'grant iam:SimulatePrincipalPolicy or verify manually'. (c) Free live probes: ComputeOptimizerService.getEnrollmentStatus (Active or Inactive); AwsCloudProviderService.canTagInstance with dryRun(true) on one managed instance per region (DryRunOperation means allowed, UnauthorizedOperation means denied); CostExplorerTagActivationService.tagKeyStatus for the tcg:environment, tcg:team and tcg:managed-by keys (one billed call, labelled in the UI). Never call GetCostAndUsage here.
4. Controller: GET /api/v1/cost-management/setup/status and POST /api/v1/cost-management/setup/check, both @PreAuthorize("hasRole('ADMIN')"). runChecks caches its result for 60 s and returns the cached result to a repeat call.
5. cost-setup.js, an IIFE singleton like the other features, using Utils.html for every interpolation: a features table (On/Off pill, property and env var, last run, credentials), a 'Run pre-flight check' button, and a permission column filled from the check. A DENIED row shows the missing action and a copy button for its policy snippet. The Reservations row shows the latest GET /reservations/utilization values. Inline a short summary of docs/cost-management-setup.md (the docs folder is not served).
6. router.js: add 'cost-setup': '#/cost-setup' and a handler () => window.CostSetup?.load?.(). index.html: an admin-only 'Cost Setup' item after cost-management-link, plus css/js tags with the current ?v= stamp. Add a 'Setup' link to the Cost Management header toolbar.
7. Add cost-setup.js/css to build-frontend.ps1 and run .\build-frontend.ps1.

**Acceptance criteria**

- Given an admin opens Cost Setup, then each feature shows whether it is enabled, its last run and the property that controls it, without any AWS call.
- Given the credential lacks ce:GetCostAndUsage, when the admin runs the pre-flight check, then Actuals shows DENIED for ce:GetCostAndUsage with a policy snippet.
- Given iam:SimulatePrincipalPolicy is denied, then the results show UNKNOWN with a hint and the live probes still report.
- Given a USER, when they call GET /setup/status, then 403.

**Tests**

- CostSetupServiceTest (mocked Iam, Sts, ComputeOptimizer and Ec2 clients): simulate results map to ALLOWED/DENIED; a simulate AccessDenied maps to UNKNOWN; a DryRunOperation exception maps to ALLOWED; getStatus makes no client calls.
- CostManagementControllerSecurityTest: both setup endpoints are ADMIN-only; two POSTs within 60 s call the probes once.

**Notes:** Every check is read-only (an EC2 DryRun never changes state). At most one billed Cost Explorer call per check.

## E09

### Sync & inventory robustness — Fix

Discovery, state sync, EKS sync and tag reconciliation keep the VM inventory correct under AWS failures, concurrent writers and removed/returning instances, and the registry shows and resolves drift and pending-review VMs.

Sources: H8, H23, H24, M5, M7, M34, M35, EKS-sync mediums (UPDATING->STARTING, no fresh-transitional guard), inventory lows (VM name uniqueness, name-pattern discovery, tag discovery region, tag reconciliation scope/batch, long transactions, registry page size) · 10 tasks · 12 days

#### E09-T01 — Number discovered VMs from MAX(sequence_position) over all rows and isolate per-instance failures

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** H8 · **Depends on:** E00-T01

H8: VmDiscoveryService uses countByGroupGroupId (active VMs only) + 1, but idx_vm_group_sequence (V1 line 150) covers inactive rows, so one deactivated VM makes every later registration collide forever and the exception aborts the whole environment.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — add findMaxSequencePositionByGroupId(groupId) over ALL rows (active and inactive)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — registerInstanceByTag/registerInstanceByName use max+1; per-instance try/catch in discoverEnvironmentVms and registerInstanceGroup
- `src/test/java/com/tcgdigital/vmcontrol/service/VmDiscoveryServiceTest.java` (new) — Mockito unit tests for numbering and isolation

**Steps**

1. In VmRepository add: @Query("SELECT MAX(v.sequencePosition) FROM Vm v WHERE v.group.groupId = :groupId") Integer findMaxSequencePositionByGroupId(@Param("groupId") String groupId); (mirror VmGroupRepository.findMaxSequencePositionByEnvironmentId). Do NOT change countByGroupGroupId (other callers want active-only counts).
2. Add private int nextSequencePosition(String groupId) in VmDiscoveryService: Integer max = vmRepository.findMaxSequencePositionByGroupId(groupId); return (max == null ? 0 : max) + 1.
3. registerInstanceByTag (line ~269): replace (int) vmRepository.countByGroupGroupId(...) + 1 with nextSequencePosition(group.getGroupId()).
4. registerInstanceByName (lines ~188-191): keep the Name-tag number when it is free (existsByGroupGroupIdAndSequencePosition is a derived query and already covers inactive rows), otherwise use nextSequencePosition.
5. In discoverEnvironmentVms and registerInstanceGroup wrap each registerInstanceBy*(...) call in try/catch(Exception): log.error with instance id + environment, increment a local failed counter, continue. Only count registered++ on success.
6. Keep flagMissingVms and reconcileTagsSafely running after the loop even if some instances failed.

**Acceptance criteria**

- Given the discovery group holds VMs at positions 1,2,3 and VM 2 is inactive, when a new tagged instance is discovered, then it is saved with sequence_position 4 and no constraint violation is logged.
- Given two new instances where saving the first throws, when discovery runs, then the second is still registered and the method returns 1.
- Given a name-pattern instance 'app-7' and position 7 is free, when it is registered, then its sequence_position is 7.

**Tests**

- VmDiscoveryServiceTest (new, Mockito: VmRepository, VmGroupRepository, EnvironmentRepository, AwsCloudProviderService, AuditService, TagReconciliationService mocked; ObjectMapper real): asserts max+1 numbering with an inactive row present, per-instance isolation, Name-tag number reuse when free.

**Notes:** No migration. VmDiscoveryServiceTest is created here and extended by E09-T02, T03, T06 and T09.

#### E09-T02 — Make AWS discovery calls report failure and skip missing-VM flagging when they fail

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** H24, M5 · **Depends on:** E09-T01

H24: discoverTaggedInstances/discoverInstancesByNamePrefix catch every exception and return List.of(); flagMissingVms then treats 'nothing is live' as truth and flags every discovered VM as drifted.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — discoverTaggedInstances (~650-682) and discoverInstancesByNamePrefix (~615-644) rethrow as DiscoveryFailedException
- `src/main/java/com/tcgdigital/vmcontrol/exception/DiscoveryFailedException.java` (new) — RuntimeException(region, cause)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — catch DiscoveryFailedException per environment/strategy; skip registration, flagMissingVms and tag reconciliation
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — add @Modifying markDriftIfActive(vmId, syncedAt) narrow update
- `src/test/java/com/tcgdigital/vmcontrol/service/VmDiscoveryServiceTest.java` (modify) — failure-path tests
- `src/test/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderServiceTest.java` (modify) — assert exception propagates

**Steps**

1. Create exception/DiscoveryFailedException extends RuntimeException with constructor (String message, Throwable cause).
2. In both AwsCloudProviderService discovery methods replace `return java.util.List.of();` in the catch with `throw new DiscoveryFailedException("EC2 discovery failed in " + region, e);` (keep the log.error). Leave discoverInstanceIds unchanged (not used by discovery).
3. VmDiscoveryService.discoverEnvironmentVms: wrap the awsService.discoverTaggedInstances call; on DiscoveryFailedException log.warn and return 0 BEFORE findOrCreateDiscoveryGroup/flagMissingVms/reconcileTagsSafely.
4. discoverByNamePattern: wrap discoverInstancesByNamePrefix; on failure log.warn and return 0 (no environment iteration, so no flagMissingVms).
5. Add to VmRepository: @Modifying @Transactional @Query("UPDATE Vm v SET v.stateDriftDetected = true, v.lastStateSyncAt = :syncedAt WHERE v.vmId = :vmId AND v.isActive = true") int markDriftIfActive(...). In flagMissingVms replace vm.set...+vmRepository.save(vm) with markDriftIfActive(vm.getVmId(), now) so discovery never writes a stale whole row (M5).
6. Add a counter of failed environments to the 'VM discovery (tag) complete' log line.

**Acceptance criteria**

- Given AWS throws a throttling error for an environment's DescribeInstances, when discovery runs, then no VM in that environment gets state_drift_detected=true and no VM is registered.
- Given AWS succeeds and returns no instance for a previously discovered active VM, when discovery runs, then that VM is flagged drifted via the narrow update and its other columns (status, name) are untouched.
- Given environment A fails and environment B succeeds, when discovery runs, then B's new instances are registered.

**Tests**

- VmDiscoveryServiceTest: awsService.discoverTaggedInstances throws DiscoveryFailedException -> verify(vmRepository, never()).markDriftIfActive(any(), any()) and never save(); success with empty list -> markDriftIfActive called for each active discovered VM.
- AwsCloudProviderServiceTest: mocked Ec2Client paginator throwing SdkClientException -> assertThrows(DiscoveryFailedException).

#### E09-T03 — Harden discovery strategies: no empty name prefix, no inactive environments, region fallback for legacy environments

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** inventory-low: name-pattern empty prefix and inactive environments, inventory-low: tag discovery skips 161 legacy envs without region · **Depends on:** E09-T02

Inventory lows: name-pattern discovery defaults to an empty prefix (scans every instance) and registers into/creates environments with no review, including inactive ones; tag discovery skips all 161 legacy environments that have no region in metadata.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — prefix guard, inactive-env skip, new env created inactive pending review, region fallback from VM rows
- `src/main/resources/application.properties` (modify) — document vm.discovery.name-pattern.prefix (required) and vm.discovery.name-pattern.auto-activate-environments=false
- `src/test/java/com/tcgdigital/vmcontrol/service/VmDiscoveryServiceTest.java` (modify) — strategy guard tests

**Steps**

1. discoverByNamePattern: if namePrefix is blank, log.warn("name-pattern discovery requires vm.discovery.name-pattern.prefix; skipping") and return 0.
2. registerInstanceGroup: when environmentRepository.findByName(envName) returns an environment with isActive=false, log.info and return 0 (do not register into it).
3. Add @Value("${vm.discovery.name-pattern.auto-activate-environments:false}") boolean autoActivateEnvironments. When creating a new environment set isActive = autoActivateEnvironments and description 'Auto-discovered via naming pattern - pending admin review'; audit with AuditAction.ENVIRONMENT_CREATED (not SCHEDULED_JOB_EXECUTED) and details stating it is inactive until an admin reactivates it. An inactive environment is skipped on later runs (step 2), so no VMs are registered until reactivated.
4. Tag strategy region fallback: replace the `region == null -> return 0` branch in discoverEnvironmentVms with resolveRegions(env): metadata region if set; else the distinct regions of the environment's active VMs from vmRepository.findDistinctRegionsGroupedByEnvironment(List.of(envId)); else defaultRegion (aws.region). Run discovery once per region; flagMissingVms only with the union of live ids across all regions and only when every region call succeeded.
5. Log at info once per run how many environments used a fallback region.

**Acceptance criteria**

- Given strategy=name-pattern and an empty prefix, when discovery runs, then no AWS call is made and 0 is returned.
- Given an inactive environment 'tcg-backend' and instances 'tcg-backend-1', when name-pattern discovery runs, then nothing is registered.
- Given a legacy EC2 environment with metadata null whose VMs are in ap-south-1, when tag discovery runs, then DescribeInstances is called for ap-south-1 with that environment's tag value.
- Given auto-activate-environments=false, when a new name-pattern environment is created, then it is saved with is_active=false and an ENVIRONMENT_CREATED audit row.

**Tests**

- VmDiscoveryServiceTest: blank prefix -> verifyNoInteractions(awsService); inactive env skip; region fallback order (metadata > VM regions > default) with a two-region case where one region fails -> markDriftIfActive never called.

**Notes:** Behaviour change for name-pattern users: new environments now need admin reactivation (flag vm.discovery.name-pattern.auto-activate-environments restores old behaviour). Reactivation uses the existing environments reactivate endpoint.

#### E09-T04 — Scope tag reconciliation to untagged VMs after discovery and isolate bad instances in a CreateTags batch

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** inventory-low: tag reconciliation re-tags whole fleet / one bad instance fails a batch, M5 · **Depends on:** E09-T03

Inventory lows + M5: reconcileEnvironment loads the whole fleet and re-tags every VM of the environment on every 10-minute discovery run; one invalid instance id fails its whole 20-id CreateTags chunk; markTagged saves whole stale Vm rows.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/TagReconciliationService.java` (modify) — reconcileNewInEnvironment, per-id fallback, narrow timestamp update
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — tagInstances returns Set<String> of tagged ids; on chunk failure retries ids one by one
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — findActiveByEnvironmentIdFetchGroupAndEnvironment(envId, onlyUntagged) and @Modifying updateTagsSyncedAt(List<String> vmIds, Timestamp)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — reconcileTagsSafely calls reconcileNewInEnvironment
- `src/test/java/com/tcgdigital/vmcontrol/service/TagReconciliationServiceTest.java` (modify) — new cases

**Steps**

1. VmRepository: add @Query("SELECT v FROM Vm v JOIN FETCH v.group g JOIN FETCH g.environment e WHERE e.environmentId = :envId AND v.isActive = true AND (:onlyUntagged = false OR v.tagsSyncedAt IS NULL)") List<Vm> findActiveByEnvironmentIdFetchGroupAndEnvironment(@Param("envId") String envId, @Param("onlyUntagged") boolean onlyUntagged).
2. VmRepository: add @Modifying @Transactional @Query("UPDATE Vm v SET v.tagsSyncedAt = :ts WHERE v.vmId IN :vmIds") int updateTagsSyncedAt(...). Replace markTagged's saveAll with this (no whole-row save).
3. TagReconciliationService: keep reconcileEnvironment(env) for manual full sweeps but load via findActiveByEnvironmentIdFetchGroupAndEnvironment(envId, false); add reconcileNewInEnvironment(env) using onlyUntagged=true. VmDiscoveryService.reconcileTagsSafely calls reconcileNewInEnvironment.
4. AwsCloudProviderService.tagInstances: change return type to Set<String>; per 20-id chunk try createTags; on Ec2Exception retry each id of that chunk individually, collecting successes and logging failures; return the successful ids. Update callers (only TagReconciliationService).
5. In reconcile(), mark only the returned ids as tagged and count failed = group.size() - tagged.size().
6. Leave the nightly reconcileAll (full sweep) as is: it is the drift-repair path.

**Acceptance criteria**

- Given an environment with 50 tagged VMs and 1 newly discovered VM, when discovery reconciles tags, then CreateTags is called with only the new instance id.
- Given a chunk of 20 ids where one id no longer exists, when tags are applied, then the other 19 are tagged and get tags_synced_at set, and Result.failed == 1.
- Given state sync changed a VM's status after tag reconciliation loaded it, when markTagged runs, then the VM's status is not overwritten.

**Tests**

- TagReconciliationServiceTest: reconcileNewInEnvironment passes only untagged ids; tagInstances partial success marks only returned ids; verify updateTagsSyncedAt used and save/saveAll never called.
- AwsCloudProviderServiceTest: createTags chunk failure falls back to per-id calls and returns the successful subset.

**Notes:** Tagging stays behind cost.tagging.enabled=false.

#### E09-T05 — Load VMs with group and environment for state sync and apply results with conditional updates

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H23, M5 · **Depends on:** E00-T01, E05 (Vm @Version + VmRepository.updateStatusIfCurrent), E09-T04

H23: syncAllVmStates loads VMs without fetch join or transaction and reads vm.getGroup().getEnvironment() on worker threads, so the drift audit and notification throw after the status save. M5: sync does whole-row saves of entities loaded minutes earlier and can overwrite a user's STARTING with STOPPED.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java` (modify) — fetch-join load, environment snapshot record, conditional updates in applySyncedStatus and syncVmNameIfNeeded
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — ordered fetch-join query + markSyncedIfStatus + updateNamesIfUnchanged
- `src/test/java/com/tcgdigital/vmcontrol/service/StateSyncServiceTest.java` (modify) — unit cases for conditional update
- `src/test/java/com/tcgdigital/vmcontrol/service/StateSyncDriftIntegrationTest.java` (new) — scheduled-path drift test on AbstractIntegrationTest

**Steps**

1. VmRepository: add findActiveForSync(): "SELECT v FROM Vm v JOIN FETCH v.group g JOIN FETCH g.environment e WHERE v.isActive = true AND e.isActive = true ORDER BY e.environmentId, g.sequencePosition, v.sequencePosition". Use it in syncAllVmStates instead of findByIsActiveTrue.
2. Before going async, map each Vm to a private record SyncTarget(String vmId, String name, String displayName, CloudProvider provider, String region, String providerVmId, VmStatus status, Timestamp updatedAt, String envId, String envName). Worker threads only see SyncTarget, never the entity.
3. Rewrite applySyncedStatus(SyncTarget t, VmStatus cloud): status change -> int changed = vmRepository.updateStatusIfCurrent(t.vmId(), t.status(), cloud, now) (E05). If changed == 0, log.debug('status changed concurrently, skipping') and return false (no history, audit or notification). If changed == 1: set drift flag via the same update or a follow-up narrow update, then recordStateChange (pass vmRepository.getReferenceById(t.vmId())), auditService.logEnvironmentAction(... t.envId(), t.envName() ...) and the notification.
4. No-drift path: add VmRepository @Modifying @Query("UPDATE Vm v SET v.stateDriftDetected = false, v.lastStateSyncAt = :ts WHERE v.vmId = :vmId AND v.status = :expected") int markSyncedIfStatus(...) and use it instead of save(vm).
5. syncVmNameIfNeeded: replace save(vm) with @Modifying updateNamesIfUnchanged(vmId, oldName, newName, newDisplayName) (WHERE v.name = :oldName).
6. Keep the NOT_FOUND/TERMINATED branch working with SyncTarget (it is reworked in E09-T06); use updateStatusIfCurrent plus a narrow deactivate update instead of save.
7. syncVmState(Vm) / syncEnvironmentVmStates keep their public signatures; convert the Vm to SyncTarget internally (environment is loaded in E09-T07's fetch-join query).

**Acceptance criteria**

- Given a RUNNING VM stopped from the AWS console, when the scheduled sync runs, then status becomes STOPPED, a STATE_DRIFT_DETECTED audit row with the environment id/name exists and notifyStateDriftDetected was called.
- Given sync loaded a VM as STOPPED and a user start set it to STARTING before apply, when sync applies RUNNING=false/STOPPED, then the VM stays STARTING and no drift audit is written.
- Given no drift, when sync runs, then only state_drift_detected and last_state_sync_at change and updated_at is not used to re-trigger the transitional guard.

**Tests**

- StateSyncDriftIntegrationTest (new, extends AbstractIntegrationTest): seed env/group/VM RUNNING, mock CloudProviderFactory -> provider.getVmStatusBatch returns STOPPED; call syncAllVmStates(); assert DB status STOPPED, audit_log row STATE_DRIFT_DETECTED with environment_id, NotificationService (spy/mock) called once.
- StateSyncServiceTest: updateStatusIfCurrent returns 0 -> verify no auditService/notificationService interaction.

**Notes:** Check whether E05's updateStatusIfCurrent also sets updated_at; isFreshTransitional relies on updated_at reflecting the last status change, so the status update must bump it and the narrow sync updates must not.

#### E09-T06 — Require N consecutive NOT_FOUND results before deactivating, and add a VM reactivate path

- [ ] Done · **Type:** fix · **Estimate:** 2 d · **Risk:** medium · **Sources:** M7 · **Depends on:** E00-T02, E03-T01, E09-T05

M7: one NOT_FOUND (e.g. after a wrong region edit) deactivates a VM permanently; inactive VMs are never synced, and registration refuses the same instance id because idx_vm_provider_id covers inactive rows.

**Files**

- `src/main/resources/db/migration/V<next>__add_vm_not_found_count.sql` (new) — ALTER TABLE vm ADD COLUMN not_found_count INT NOT NULL DEFAULT 0 (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/Vm.java` (modify) — notFoundCount field + accessors
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — add VM_REACTIVATED, VM_DEACTIVATED
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — incrementNotFound, resetNotFound, deactivateIfActive narrow updates
- `src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java` (modify) — threshold logic in NOT_FOUND branch; reset on any found status
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — reactivateVm(vmId, userId)
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — POST /{vmId}/reactivate
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — auto-reactivate sync-deactivated VMs found live again
- `src/test/java/com/tcgdigital/vmcontrol/service/StateSyncServiceTest.java` (modify) — threshold tests
- `src/test/java/com/tcgdigital/vmcontrol/controller/VmReactivateControllerTest.java` (new) — role and flow tests

**Steps**

1. Migration (next free V number): ALTER TABLE vm ADD COLUMN not_found_count INT NOT NULL DEFAULT 0;
2. Vm: @Column(name = "not_found_count", nullable = false) private Integer notFoundCount = 0; getters/setters.
3. StateSyncService: @Value("${vm.state.sync.not-found-threshold:3}") int notFoundThreshold. NOT_FOUND branch: int count = vmRepository.incrementNotFound(vmId) then read back (or use returned value from a SELECT); if count < threshold: set state_drift_detected=true via narrow update, log.warn, no deactivation, no history row; if count >= threshold: deactivate as today (history, audit STATE_DRIFT_DETECTED + VM_DEACTIVATED, notification). TERMINATED keeps immediate deactivation. Any other found status calls resetNotFound(vmId) when the count is > 0.
4. VmRepository: @Modifying incrementNotFound(vmId) (SET notFoundCount = notFoundCount + 1), resetNotFound(vmId), deactivateIfActive(vmId, status, syncedAt) (SET isActive=false, status=:status, stateDriftDetected=true WHERE isActive=true).
5. VmService.reactivateVm(String vmId, String userId): load VM (must be isActive=false, else ValidationException); outside any transaction fetch cloud status via CloudProviderFactory.getService(provider).getVmStatus(providerVmId, region); if NOT_FOUND/TERMINATED throw ValidationException('Instance not found in <region> - fix the region first'); else in a transaction set isActive=true, notFoundCount=0, stateDriftDetected=false, status=cloud status, save; audit VM_REACTIVATED via auditService.logEnvironmentAction(userId, ...). (VmService does not currently inject CloudProviderFactory - add it.)
6. VmMgmtController: @PostMapping("/{vmId}/reactivate") @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')"); call securityService.assertSameEnvironment(vm env id, environmentId) (E03-T01) then vmService.reactivateVm; return VmDTO.
7. VmDiscoveryService: replace existsByProviderAndProviderVmId(...) skip with findByProviderAndProviderVmId; if the row exists, isActive=false, status NOT_FOUND and (after E09-T09) not discoveryIgnored, and it belongs to this environment -> reactivate it (isActive=true, notFoundCount=0, status UNKNOWN, discoveryPending=true) and audit VM_REACTIVATED with actor null and details 'instance found again by discovery'. Otherwise skip as today.
8. Document vm.state.sync.not-found-threshold in application.properties.

**Acceptance criteria**

- Given threshold 3 and a VM whose instance is reported NOT_FOUND, when sync runs twice, then the VM stays active with state_drift_detected=true and not_found_count=2.
- Given the third consecutive NOT_FOUND, when sync runs, then the VM is inactive with status NOT_FOUND and a VM_DEACTIVATED audit row.
- Given a VM with not_found_count=2, when sync next reports RUNNING, then not_found_count is 0.
- Given an inactive VM whose region was corrected and the instance exists, when an ENV_ADMIN calls POST /api/v1/environments/{envId}/vms/{vmId}/reactivate, then it returns 200, the VM is active with the cloud status, and VM_REACTIVATED is audited.
- Given a USER role, when calling reactivate, then 403.

**Tests**

- StateSyncServiceTest: threshold counting, TERMINATED immediate, reset on found.
- VmReactivateControllerTest (SecuredWebTestBase): ADMIN/ENV_ADMIN 200, USER/VIEWER 403, wrong environment path 404, instance still missing 400.
- VmDiscoveryServiceTest: inactive NOT_FOUND row found live -> reactivated; active row -> skipped.

**Notes:** Migration: next free V number at implementation time. Registry UI for reactivate is E09-T10.

#### E09-T07 — Fix EKS sync status mapping, add the transitional guard, and keep AWS calls out of DB transactions

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** EKS-sync: UPDATING mapped to STARTING, EKS-sync: no fresh-transitional guard, inventory-low: long transactions across AWS calls · **Depends on:** E09-T06

EKS-sync mediums: UPDATING is mapped to STARTING even for running node groups doing a config/version update, and upsertNodeGroup has no fresh-transitional guard so it overwrites an in-flight start/stop and records false drift. Inventory low: transactions span AWS calls (upsertNodeGroup REQUIRES_NEW wraps describeNodegroup; autoDiscoverClusters @Transactional wraps listClusters; syncEnvironmentVmStates @Transactional wraps per-VM AWS calls).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/VmTransitionalGuard.java` (new) — @Component with isFreshTransitional(VmStatus, Timestamp updatedAt) reading vm.state.sync.stale-transitional-minutes
- `src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java` (modify) — use VmTransitionalGuard; syncEnvironmentVmStates not @Transactional, fetch-join load
- `src/main/java/com/tcgdigital/vmcontrol/service/EksCloudProviderService.java` (modify) — add mapNodegroupForSync(Nodegroup, VmStatus current)
- `src/main/java/com/tcgdigital/vmcontrol/service/EksSyncService.java` (modify) — describe before transaction, guard, drop @Transactional on autoDiscoverClusters
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — findByEnvironmentIdFetchGroupAndEnvironment(envId)
- `src/test/java/com/tcgdigital/vmcontrol/service/EksSyncServiceTest.java` (modify) — UPDATING and guard cases
- `src/test/java/com/tcgdigital/vmcontrol/service/EksCloudProviderServiceTest.java` (modify) — mapping table test

**Steps**

1. Create VmTransitionalGuard (move the logic of StateSyncService.isFreshTransitional lines ~251-262 and the staleTransitionalMinutes @Value into it). StateSyncService delegates to it.
2. EksCloudProviderService: add VmStatus mapNodegroupForSync(Nodegroup ng, VmStatus current): UPDATING -> desired == 0 ? STOPPING : (current == STOPPED || current == STARTING ? STARTING : RUNNING); every other status -> mapNodegroupToVmStatus(ng). Do not change mapNodegroupToVmStatus (operation polling uses it).
3. EksSyncService.syncEksEnvironment: for each node group call eksService.describeNodegroup(...) in the loop BEFORE self.upsertNodeGroup, and change upsertNodeGroup's signature to accept the Nodegroup (nullable). No AWS call inside the REQUIRES_NEW method.
4. In upsertNodeGroup: if the existing Vm is fresh-transitional (guard), keep its status and drift flag, still refresh metadata/lastStateSyncAt, and skip the drift audit. Otherwise use mapNodegroupForSync(nodegroup, currentStatus).
5. Remove @Transactional from autoDiscoverClusters (environmentRepository.save is transactional on its own).
6. StateSyncService.syncEnvironmentVmStates: remove @Transactional; load with new VmRepository.findByEnvironmentIdFetchGroupAndEnvironment(envId) (JOIN FETCH group and environment, active VMs, same ORDER BY as findByEnvironmentId) and run the E09-T05 apply path per VM.
7. Grep for other @Transactional service methods in sync/discovery classes that call AWS (EksSyncService, StateSyncService, VmDiscoveryService, TagReconciliationService) and confirm none remain; list any left in the PR description.

**Acceptance criteria**

- Given a RUNNING node group whose EKS status is UPDATING with desired 3, when EKS sync runs, then the Vm stays RUNNING and no drift is recorded.
- Given a STOPPED node group being scaled up (UPDATING, desired 2), when sync runs, then the Vm is STARTING.
- Given a user started a node group 2 minutes ago (STARTING, fresh), when EKS sync sees ACTIVE desired 0, then the status stays STARTING and no STATE_DRIFT_DETECTED audit is written.
- Given describeNodegroup takes 20 s, when sync runs, then no DB connection is held during the call (upsertNodeGroup receives the Nodegroup already fetched).

**Tests**

- EksCloudProviderServiceTest: table test for mapNodegroupForSync (UPDATING x current status x desired).
- EksSyncServiceTest: fresh transitional guard; describeNodegroup invoked before upsertNodeGroup (InOrder); UPDATING on running group -> no drift.
- StateSyncServiceTest: syncEnvironmentVmStates uses the fetch-join query.

#### E09-T08 — Add registry review listing and move-to-group endpoints; normalise VM names before uniqueness checks; clamp registry paging

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** M34, inventory-low: VM name uniqueness raw vs normalised, inventory-low: unbounded registry page size · **Depends on:** E00-T04, E03-T01, E09-T07

M34 (backend): nothing lists drifted, pending-review or inactive VMs across an environment and there is no way to move a discovered VM out of 'Auto-Discovered'. Inventory lows: VmService checks name uniqueness on the raw name but stores the normalised one (500 on idx_vm_group_name); GET .../vms/{groupId}/page accepts any size.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — findReviewPage(envId, state, Pageable)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — normalizeName helper used before checks, getReviewPage, moveVm
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — GET /review, PUT /{vmId}/move, Paging.of in listVmsPage
- `src/main/java/com/tcgdigital/vmcontrol/dto/MoveVmDTO.java` (new) — { @NotBlank targetGroupId, Integer sequencePosition (optional) }
- `src/test/java/com/tcgdigital/vmcontrol/controller/VmRegistryReviewControllerTest.java` (new) — endpoint tests
- `src/test/java/com/tcgdigital/vmcontrol/service/VmServiceTest.java` (new) — name normalisation + move rules

**Steps**

1. VmService: add private static String normalizeName(String raw) = raw.trim().toLowerCase().replaceAll("\\s+", "-"). In registerVm and updateVm compute normalised name first and use it for existsByGroupGroupIdAndName and the 'changed' comparison (updateVm: !vm.getName().equals(normalised)).
2. VmRepository: @Query with JOIN FETCH group: WHERE env id = :envId AND ((:state = 'DRIFT' AND v.isActive = true AND v.stateDriftDetected = true) OR (:state = 'PENDING' AND v.isActive = true AND v.discoveryPending = true) OR (:state = 'INACTIVE' AND v.isActive = false)) ORDER BY v.updatedAt DESC, with a separate countQuery; returns Page<Vm>.
3. VmMgmtController: @GetMapping("/review") @PreAuthorize("hasAnyRole('ADMIN', 'ENV_ADMIN')") params state (DRIFT|PENDING|INACTIVE, 400 otherwise), page, size; securityService.assertCanView(environmentId) (E03-T01); Paging.of(page, size, 100) (E00-T04). Response Page<VmDTO>. Also add counts endpoint GET /review/counts returning {drift, pending, inactive} via three count queries.
4. VmService.moveVm(vmId, MoveVmDTO, userId): target group must exist and be in the same environment (else ValidationException); refuse for AWS_EKS VMs; refuse when any VM in the source group lists this VM in its dependencies; set group, sequencePosition = dto value if free else findMaxSequencePositionByGroupId(target)+1 (E09-T01), clear its own dependencies, set discoveryPending=false; audit VM_UPDATED via auditService.logEnvironmentAction with old/new group names.
5. VmMgmtController: @PutMapping("/{vmId}/move") ADMIN/ENV_ADMIN, assertSameEnvironment (E03-T01), @Valid MoveVmDTO, returns VmDTO.
6. listVmsPage: replace raw page/size with Paging.of(page, size, 100) and pass the Pageable through a new VmService.getVmsByGroupIdPaged(groupId, Pageable) overload.

**Acceptance criteria**

- Given a VM named 'web-1' in a group, when registering 'Web 1' in the same group, then 400 'already exists' (not 500).
- Given 3 drifted and 2 pending VMs in an environment, when ENV_ADMIN calls GET .../vms/review?state=DRIFT, then 3 VmDTOs are returned; counts endpoint returns {drift:3,pending:2,inactive:n}.
- Given a pending VM in Auto-Discovered, when moved to group 'web', then it is in 'web' with the next free sequence, discovery_pending=false and an audit row exists.
- Given a target group in another environment, when moving, then 400 and nothing changes.
- Given size=10000 or page=-1 on .../page, then size is clamped to 100 / 400 is returned.

**Tests**

- VmServiceTest (Mockito): normalisation before checks, move validations, sequence assignment.
- VmRegistryReviewControllerTest (SecuredWebTestBase + AbstractIntegrationTest): role matrix (ADMIN/ENV_ADMIN 200, USER/VIEWER 403), state validation, paging clamp.

#### E09-T09 — Make VM delete a soft delete with an ignore marker and audit it

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** M35 · **Depends on:** E09-T08

M35: VmService.deleteVm hard-deletes (cascading history and metrics), writes no audit, and discovery re-adds a still-tagged instance 10 minutes later with no history.

**Files**

- `src/main/resources/db/migration/V<next>__add_vm_soft_delete.sql` (new) — ADD discovery_ignored BOOLEAN NOT NULL DEFAULT FALSE, deleted_at TIMESTAMP NULL, deleted_by VARCHAR(36) NULL (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/Vm.java` (modify) — discoveryIgnored, deletedAt, deletedBy
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — deleteVm(vmId, userId) soft delete + audit; reactivateVm clears ignore marker; registerVm message for removed instance
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — pass current user id; update OpenAPI text
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — never reactivate discoveryIgnored rows
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmDTO.java` (modify) — expose isActive, discoveryIgnored, deletedAt
- `src/test/java/com/tcgdigital/vmcontrol/service/VmServiceTest.java` (modify) — soft delete cases

**Steps**

1. Migration (next free V number): ALTER TABLE vm ADD COLUMN discovery_ignored BOOLEAN NOT NULL DEFAULT FALSE; ADD COLUMN deleted_at TIMESTAMP NULL; ADD COLUMN deleted_by VARCHAR(36) NULL;
2. Vm: add the three fields with accessors.
3. VmService.deleteVm(String vmId, String userId): keep the dependents check; then set isActive=false, discoveryIgnored=true, deletedAt=now, deletedBy=userId, discoveryPending=false, save; auditService.logEnvironmentAction(userId, AuditAction.VM_DELETED, envId, envName, "vm", vmId, displayName, "VM removed from platform (soft delete; discovery will ignore instance <providerVmId>)"). Remove vmRepository.delete(vm).
4. VmMgmtController.deleteVm: resolve userService.getCurrentUserId() and call the new signature; update @Operation description to 'Removes a VM from the platform; history is kept and discovery ignores the instance'.
5. VmService.reactivateVm (E09-T06): also clear discoveryIgnored, deletedAt, deletedBy.
6. VmDiscoveryService auto-reactivate branch (E09-T06 step 7): skip rows with discoveryIgnored=true.
7. VmService.registerVm: when existsByProviderAndProviderVmId is true and that row is soft-deleted, throw ValidationException('Instance <id> was removed from the platform - reactivate it from the registry instead').
8. Ensure all read paths already filter isActive=true (findByEnvironmentId, findByGroupGroupIdAndIsActiveTrue..., findActiveForSync); add VmDTO fields isActive, discoveryIgnored, deletedAt.

**Acceptance criteria**

- Given a registered VM with state history and metrics, when an ENV_ADMIN deletes it, then the row remains with is_active=false and discovery_ignored=true, history rows remain, and a VM_DELETED audit row names the actor.
- Given the deleted VM's instance is still tagged, when discovery runs, then it is not re-registered or reactivated.
- Given a soft-deleted VM, when an admin reactivates it, then discovery_ignored is false and it is active again.
- Given another VM depends on it, when deleting, then 400 as today.

**Tests**

- VmServiceTest: soft delete fields + audit; dependents check retained; register of removed instance message.
- VmDiscoveryServiceTest: ignored row never reactivated.
- FlywayMigrationTest still passes.

**Notes:** Soft-deleted rows still occupy idx_vm_group_name/idx_vm_group_sequence; the register form (E09-T10) proposes MAX+1 for sequence so this does not block new registrations.

#### E09-T10 — Registry UI: drift and pending-review badges, review filters, Acknowledge/Move/Reactivate actions, Edit on any page, clear delete wording

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** M34, M35 · **Depends on:** E01 (Utils.html, text-by-default Modals/Notifications), E09-T09

M34: VmDTO carries discoveryPending and stateDriftDetected and an acknowledge endpoint exists, but no screen uses them, so discovered VMs pile up in Auto-Discovered. M35: Edit only searches the first page's cache ('VM not found' on page 2).

**Files**

- `src/main/resources/static/js/features/vm-registry.js` (modify) — badges in buildVmRows, review panel with filter chips, actions, editVm fetches the VM, delete wording
- `src/main/resources/static/js/config.js` (modify) — Config.API.vms.review/reviewCounts/move/reactivate/acknowledge
- `src/main/resources/static/index.html` (modify) — Move VM modal (target group select) inside the registry modals block
- `src/main/resources/static/css/components/badges.css` (modify) — drift / pending-review badge variants using existing tokens

**Steps**

1. config.js under vms: review(envId, state, page, size), reviewCounts(envId), move(envId, vmId), reactivate(envId, vmId), acknowledge(envId, vmId) (PUT .../acknowledge already exists).
2. buildVmRows: after the status badge add 'Drift' (warning) when vm.stateDriftDetected and 'Pending review' (info) when vm.discoveryPending; add an Acknowledge button (pending only) and a Move button (EC2 only). Build markup with Utils.html (E01).
3. In renderGroupsModal add a 'Needs attention' strip above the group cards: chips Drift (n) / Pending review (n) / Removed or inactive (n) from reviewCounts; clicking a chip loads review?state=... into a paged table (reuse buildVmRows plus Reactivate button for INACTIVE rows).
4. editVm(vmId): replace the currentGroupsWithVms lookup with `await ApiClient.get(Config.API.vms.get(envId, vmId))` and look up the group from currentGroups by vm.groupId; keep the EKS readonly logic.
5. Move: open the Move VM modal listing the environment's other groups (currentGroups minus the VM's group); submit PUT move; on success refreshGroupsModal().
6. Acknowledge: PUT acknowledge then refresh counts and the row. Reactivate: POST reactivate; show the server message on 400 (instance not found).
7. deleteVm confirm text: 'Remove <name> from the platform? Its history is kept and discovery will ignore this instance. It can be reactivated from Removed/inactive.' Button label 'Remove'.
8. openVmForm: default sequence to (max sequence of loaded VMs in the group)+1 instead of 1.
9. Run .\build-frontend.ps1 so dist/ is updated.

**Acceptance criteria**

- Given a VM on page 2 of a group, when Edit is clicked, then the edit modal opens with that VM's values.
- Given 2 pending VMs, when the registry modal opens, then 'Pending review (2)' shows and each pending row has a 'Pending review' badge and an Acknowledge button.
- Given a pending VM, when Move to 'web' is confirmed, then it disappears from Auto-Discovered and appears in 'web' without a full page reload.
- Given an inactive VM in the Removed/inactive list, when Reactivate succeeds, then it moves back to its group list; when the instance is still missing, the server reason is shown.
- Given a VIEWER, then none of these buttons render (registry is admin-only today).

**Tests**

- Manual test script in the PR (no JS test harness exists): page-2 edit, acknowledge, move, reactivate, delete wording, at 1440px and 390px.
- Backend endpoints are covered by E09-T06/T08/T09 tests.

**Notes:** Follow the My Account panel pattern for loading/error states (review report calls it the pattern to copy).

## E10

### Environments & groups correctness — Fix

Creating, editing, deactivating and deleting environments and groups never corrupts dependencies, EKS identity or metadata, and environment pages load with a bounded number of queries.

Sources: M2, M3, M4, M36, env lows (description cannot be cleared; insights merges same-name groups; dependencies show raw ids; deactivation leaves lock/ignores running ops; delete wording says permanent; lists show slug and search ignores display name) · 6 tasks · 6 days

#### E10-T01 — Make group delete safe: count all VMs, strip dangling dependencies, clean group-scoped grants, requests and rules

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** M2 · **Depends on:** E00-T01, E09-T10

M2: VmGroupService.deleteGroup counts only active VMs then the FK cascade (V1 line 144 ON DELETE CASCADE) hard-deletes inactive VMs with their history; other groups keep the deleted id so DependencyValidator.topologicalSort reports 'Circular dependency' for the whole environment; GROUP-scoped grants and automation rules are left pointing at a missing group.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/VmGroupService.java` (modify) — deleteGroup(groupId, userId): all-VM count, strip dependents, scope cleanup, audit
- `src/main/java/com/tcgdigital/vmcontrol/service/DependencyValidator.java` (modify) — topologicalSort ignores dependency ids not in the group set (lines ~79-129)
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — countAllByGroupId (active + inactive)
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentAccessRepository.java` (modify) — reuse findActiveByScopeTypeAndScopeId (line ~172) for revocation
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentAccessRequestRepository.java` (modify) — findPendingByScope(scopeType, scopeId) if no existing method fits
- `src/main/java/com/tcgdigital/vmcontrol/repository/AutomationRuleRepository.java` (modify) — findByScopeTypeAndScopeId
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmGroupController.java` (modify) — pass current user id to deleteGroup
- `src/test/java/com/tcgdigital/vmcontrol/service/DependencyValidatorTest.java` (modify) — dangling id case
- `src/test/java/com/tcgdigital/vmcontrol/service/VmGroupServiceTest.java` (new) — delete rules

**Steps**

1. VmRepository: @Query("SELECT COUNT(v) FROM Vm v WHERE v.group.groupId = :groupId") long countAllByGroupId(@Param("groupId") String groupId); plus countByGroupGroupId stays for active.
2. deleteGroup: active = countByGroupGroupId, all = countAllByGroupId. If active > 0 -> ValidationException('Remove VMs first'). If all > active -> ValidationException('Group has N removed VMs whose history would be lost; move them to another group first') (no hard delete of history).
3. In the same transaction: for every other group in the environment whose getDependencies() contains groupId, setDependencies(list without it) and save; audit GROUP_DEPENDENCIES_UPDATED per changed group.
4. Revoke ACTIVE grants with scopeType GROUP and scopeId = groupId (status REVOKED, existing revoke fields), cancel PENDING requests with that scope (status CANCELLED), and set enabled=false on AutomationRule rows with scopeType GROUP and scopeId = groupId. Notification side effects must be wrapped in try/catch (do not roll back the delete).
5. Audit GROUP_DELETED via auditService.logEnvironmentAction(userId, ...) with counts of grants/requests/rules cleaned.
6. DependencyValidator.topologicalSort: when building the graph skip depId values not in groupMap (log.warn 'ignoring dangling dependency'), so a legacy dangling id no longer causes a false cycle.

**Acceptance criteria**

- Given group B depends on A, when A (no VMs) is deleted, then B's dependencies no longer contain A and start-order for the environment succeeds.
- Given a group with only inactive VMs, when deleting, then 400 and the VMs and their history remain.
- Given an active GROUP grant, a pending GROUP request and a GROUP automation rule on the group, when it is deleted, then the grant is REVOKED, the request CANCELLED and the rule disabled.
- Given a group row whose dependencies already contain a missing id, when topologicalSort runs, then no CircularDependencyException is thrown.

**Tests**

- DependencyValidatorTest: dangling dependency ignored; real cycle still detected.
- VmGroupServiceTest (new, Mockito): active/inactive refusal, dependents stripped, grants/requests/rules cleaned, audit called.

**Notes:** Removed VMs can be moved with E09-T08's move endpoint before deleting the group.

#### E10-T02 — Normalise names before duplicate checks and store the EKS cluster name in its own field

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** M3 · **Depends on:** E10-T01

M3: EnvironmentService.createEnvironment (lines ~175-181) and VmGroupService.createGroup (~76-94) check the raw name but save the lowercased slug, so 'My Env' next to 'my-env' returns 500; EKS sync uses Environment.name as the case-sensitive cluster name, so 'MyCluster' saved as 'mycluster' silently finds nothing.

**Files**

- `src/main/resources/db/migration/V<next>__add_environment_eks_cluster_name.sql` (new) — ADD eks_cluster_name VARCHAR(255) NULL; UPDATE environment SET eks_cluster_name = name WHERE service_type = 'EKS' (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/Environment.java` (modify) — eksClusterName field + getEffectiveClusterName()
- `src/main/java/com/tcgdigital/vmcontrol/dto/CreateEnvironmentDTO.java` (modify) — optional eksClusterName
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — expose eksClusterName
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentRepository.java` (modify) — existsByEksClusterName
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — normalise then check; EKS stores exact cluster name
- `src/main/java/com/tcgdigital/vmcontrol/service/VmGroupService.java` (modify) — normalise then check in createGroup
- `src/main/java/com/tcgdigital/vmcontrol/service/EksSyncService.java` (modify) — use getEffectiveClusterName() in syncEksEnvironment, getUnregisteredEksClusters, autoDiscoverClustersInRegion
- `src/main/resources/static/js/ui/modals.js` (modify) — EKS create sends eksClusterName = picked cluster (data-cluster)
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentServiceTest.java` (modify) — normalisation and EKS cases
- `src/test/java/com/tcgdigital/vmcontrol/service/EksSyncServiceTest.java` (modify) — mixed-case cluster

**Steps**

1. Migration as above. Environment: @Column(name = "eks_cluster_name") String eksClusterName; @Transient getEffectiveClusterName() returns eksClusterName != null ? eksClusterName : name.
2. Add a shared static helper NameNormalizer.slug(String) (new class in service/support or reuse in each service) = trim().toLowerCase().replaceAll("\\s+", "-"). EnvironmentService.createEnvironment: slug first, existsByName(slug) -> ValidationException. For serviceType EKS: eksClusterName = dto.getEksClusterName() != null ? dto.getEksClusterName().trim() : dto.getName().trim(); reject if existsByEksClusterName.
3. VmGroupService.createGroup: slug first, existsByEnvironmentEnvironmentIdAndName(envId, slug).
4. EksSyncService: clusterName = environment.getEffectiveClusterName() everywhere it uses environment.getName() for AWS calls/providerVmId; autoDiscover: existsByEksClusterName(clusterName) || existsByName(slug(clusterName)) to skip; new env name = slug(clusterName), eksClusterName = clusterName, displayName = clusterName.
5. modals.js EKS create flow: include eksClusterName from the selected cluster row's data-cluster attribute in the POST body.
6. Expose eksClusterName in EnvironmentDTO (read-only).

**Acceptance criteria**

- Given environment 'my-env' exists, when creating 'My Env', then 400 'already exists' (not 500).
- Given an EKS cluster 'MyCluster' registered from the picker, when EKS sync runs, then ListNodegroups is called with 'MyCluster' and node groups are synced.
- Given existing EKS environments, after migration, then eks_cluster_name equals their previous name and sync behaviour is unchanged.

**Tests**

- EnvironmentServiceTest: duplicate after normalisation; EKS cluster name kept exact.
- EksSyncServiceTest: listNodegroups called with eksClusterName when name differs in case.
- FlywayMigrationTest passes.

**Notes:** Migration: next free V number at implementation time.

#### E10-T03 — Merge metadata on environment/group edit, make group names immutable, allow clearing descriptions

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** M4, env-low: description cannot be cleared · **Depends on:** E10-T02

M4: the edit-environment modal (modals.js ~580-588) replaces metadata with {ownerTeam, defaultCloudProvider}, dropping the EKS region; VmGroupService.updateGroup (~132-137) re-normalises the name and nulls metadata, so renaming an EKS node group creates a phantom group on the next sync. Env low: description cannot be cleared (empty -> null -> ignored).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — updateEnvironment merges metadata JSON; blank description clears
- `src/main/java/com/tcgdigital/vmcontrol/service/VmGroupService.java` (modify) — updateGroup rejects name change, merges metadata, null description clears only when sent as empty
- `src/main/java/com/tcgdigital/vmcontrol/service/support/JsonMetadataMerger.java` (new) — merge(existingJson, patchJson): patch keys override, null-valued keys removed, invalid JSON -> ValidationException
- `src/main/resources/static/js/ui/modals.js` (modify) — edit environment sends description '' when cleared and only the edited metadata keys
- `src/main/resources/static/js/features/vm-registry.js` (modify) — openGroupForm: name readonly in edit mode; send metadata only if changed
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentServiceTest.java` (modify) — merge + clear tests
- `src/test/java/com/tcgdigital/vmcontrol/service/VmGroupServiceTest.java` (modify) — immutable name, metadata kept
- `src/test/java/com/tcgdigital/vmcontrol/service/support/JsonMetadataMergerTest.java` (new) — merge semantics

**Steps**

1. Create JsonMetadataMerger (static, uses a shared ObjectMapper): merge(String existing, String patch) -> String.
2. EnvironmentService.updateEnvironment: if dto.getMetadata() != null -> environment.setMetadata(JsonMetadataMerger.merge(environment.getMetadata(), dto.getMetadata())). If dto.getDescription() != null -> setDescription(dto.getDescription().isBlank() ? null : dto.getDescription().trim()). Do not allow serviceType change when the environment has groups (ValidationException).
3. VmGroupService.updateGroup: compute slug(dto.getName()) (E10-T02 helper); if it differs from group.getName() -> ValidationException('Group name cannot be changed; edit the display name'). Remove the setName call. Metadata: if dto.getMetadata() != null merge, else keep. Description: blank -> null, null -> unchanged.
4. modals.js edit environment: description: trimmed value (send '' when cleared, not null); metadata: JSON.stringify({ownerTeam, defaultCloudProvider}) is now a patch and the server keeps region and other keys.
5. vm-registry.js openGroupForm(groupId): set $('#groupName').prop('readonly', !!groupId); in submitGroup send metadata only when the textarea value differs from the loaded one.
6. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given an EKS environment with metadata {region: eu-west-1}, when its description is edited, then metadata still contains region eu-west-1 and EKS sync uses eu-west-1.
- Given an environment with a description, when the description field is emptied and saved, then the description is null.
- Given an EKS node-group group, when its display name is edited, then its name is unchanged and the next sync updates the same group (no new group).
- Given a PUT that changes a group's name, then 400.

**Tests**

- JsonMetadataMergerTest: override, removal via null, invalid JSON.
- EnvironmentServiceTest: metadata merge keeps region; blank description clears.
- VmGroupServiceTest: name change rejected; metadata null keeps existing.

#### E10-T04 — Remove N+1 queries from environment insights and key groups by id

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** M36, env-low: insights merges groups with same display name · **Depends on:** E09-T07

M36: EnvironmentInsightsService.getInsights (~112-185) runs five queries per VM (inventory, volumes, latest sample, idle summary, 1-hour series), about 1,000 queries for a 200-VM environment. Env low: rowsByGroupId is keyed by group display name, so groups that share a display name are merged.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentInsightsService.java` (modify) — batch loads into maps; group rows by groupId
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmMetricSampleRepository.java` (modify) — findLatestByVmIds(vmIds)
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentInsightsDTO.java` (modify) — VmInsightRowDTO gains groupId
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentInsightsServiceTest.java` (new) — batching and grouping tests

**Steps**

1. VmMetricSampleRepository: @Query("SELECT s FROM VmMetricSample s WHERE s.vm.vmId IN :vmIds AND s.sampleTime = (SELECT MAX(s2.sampleTime) FROM VmMetricSample s2 WHERE s2.vm.vmId = s.vm.vmId)") List<VmMetricSample> findLatestByVmIds(@Param("vmIds") List<String> vmIds).
2. getInsights: load VMs with E09-T07's findByEnvironmentIdFetchGroupAndEnvironment. Before the loop, if vmIds is non-empty, build maps: inventoryRepository.findByVmVmIdIn, volumeRepository.findByVmVmIdIn (group by vmId, sort by deviceName), idleSummaryRepository.findByVmVmIdIn, findLatestByVmIds (dedupe per vm keeping max), sampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc (group by vmId). Chunk vmIds by 500 for IN lists.
3. Replace per-VM repository calls inside the loop with map lookups.
4. Add String groupId as the first group field of VmInsightRowDTO (record: vmId, name, groupId, groupName, ...); fill from vm.getGroup().getGroupId(); group rowsByGroupId by groupId and look up by group.getGroupId() in buildGroupInsight.
5. Update frontend consumers of vm rows only if they index by position (they use JSON field names, so adding a field is safe; grep environments.js for groupName).

**Acceptance criteria**

- Given an environment with 200 VMs, when insights are requested, then the number of repository calls does not grow with the VM count (at most ~10 queries).
- Given two groups with the same display name, when insights are built, then each group's insight lists only its own VMs.
- Given an environment with no VMs, then insights return zero counts without issuing IN () queries.

**Tests**

- EnvironmentInsightsServiceTest (Mockito): 3 VMs -> each batch repository method called once and per-VM methods never called; two same-name groups kept separate; empty environment.

#### E10-T05 — Batch lock status and group counts into list endpoints, clamp paging, show display names and search them

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** M36, env-low: lists show slug not display name and search ignores display name · **Depends on:** E00-T04, E02 (router lifecycle for list pages), E10-T04

M36: environments.js enrichWithLockStatus fetches lock status once per row; VmGroupController list/start-order call getVmCount + getRunningVmCount per group; EnvironmentController /page uses PageRequest.of(page,size) unclamped (negative -> 500). Env lows: lists render the slug (env.name) and searchActive/searchAll/findDistinctActiveEnvironmentsForUser ignore displayName.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentLockRepository.java` (modify) — findActiveByEnvironmentIdIn(ids)
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — getBatchCounts adds lock summary
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — lock summary fields (locked, lockedByUserId, lockedByDisplayName, lockedAt)
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — Paging.of in listEnvironmentsPaged; lock summary in toDtoWithCounts
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmGroupController.java` (modify) — use vmService.getVmCountsByGroupForEnvironment for list and start-order
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentRepository.java` (modify) — searchActive/searchAll match displayName
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentAccessRepository.java` (modify) — findDistinctActiveEnvironmentsForUser matches displayName
- `src/main/resources/static/js/features/environments.js` (modify) — drop per-row fetchLockStatus in list; render displayName with slug as secondary
- `src/main/resources/static/js/features/vm-registry.js` (modify) — buildEnvironmentRow shows displayName; filterEnvironments matches displayName
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentListControllerTest.java` (new) — lock fields, paging clamp, search

**Steps**

1. EnvironmentLockRepository: @Query("SELECT l FROM EnvironmentLock l WHERE l.environment.environmentId IN :ids AND l.isActive = true") List<EnvironmentLock> findActiveByEnvironmentIdIn(@Param("ids") List<String> ids). If E07 added expiresAt, also filter out expired locks the same way LockService does.
2. EnvironmentCounts gains a LockSummary (or a separate Map returned by getBatchCounts); resolve display names of lock holders with one userRepository.findAllById call.
3. EnvironmentDTO.fromEntityWithCounts gets the lock summary; EnvironmentController.toDtoWithCounts passes it.
4. listEnvironmentsPaged: Pageable pageable = Paging.of(page, size, 100) (E00-T04).
5. VmGroupController: in list (line ~64) and start-order (~132) build counts once via vmService.getVmCountsByGroupForEnvironment(environmentId) (inject VmService if needed) and read from the map.
6. The three search queries add OR LOWER(e.displayName) LIKE LOWER(CONCAT('%', :search, '%')); order by displayName.
7. environments.js: in enrichWithLockStatus use env.locked/env.lockedBy* from the payload (no ApiClient call); the detail page keeps its single fetchLockStatus. Row title cell shows displayName (fallback name) with the slug in a small muted line; data-env-name uses displayName.
8. vm-registry.js buildEnvironmentRow (line ~199) shows displayName; filterEnvironments (~271) also matches displayName.
9. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given 10 environments on a page, when the environments list loads, then no /locks/{id}/status request is made and locked rows show the holder.
- Given page=-1 or size=5000 on /environments/page, then 400 / size clamped to 100.
- Given an environment with displayName 'Payments QA' and name 'pay-qa', when searching 'payments', then it is returned and the row title reads 'Payments QA'.
- Given 30 groups, when GET /groups is called, then VM counts come from one grouped query.

**Tests**

- EnvironmentListControllerTest (AbstractIntegrationTest + SecuredWebTestBase): lock fields present, paging clamp, displayName search for admin and regular user.
- Update existing EnvironmentHierarchyIntegrationTest assertions if it checks DTO shape.

#### E10-T06 — Block deactivation during running operations, release the lock on deactivation, fix delete wording and dependency names

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** env-low: deactivating leaves its lock and ignores running operations, env-low: delete wording says permanent, env-low: dependencies show raw ids · **Depends on:** E01 (Utils.html), E07 (LockService changes), E10-T05

Env lows: deactivateEnvironment ignores running operations and leaves the active lock in place; the delete dialogs say 'permanently delete' / 'cannot be undone' although DELETE is a soft deactivate; the environment detail page shows dependency group ids ('Depends: env-1-g1').

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — deactivateEnvironment checks hasActiveOperations and releases lock
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — OpenAPI 409/400 doc for deactivate
- `src/main/resources/static/js/ui/modals.js` (modify) — DestructiveConfirm.confirmDeleteEnvironment wording (~line 794)
- `src/main/resources/static/js/features/environments.js` (modify) — deleteEnvironment wording (~849); dependsText maps ids to group display names (~683-689)
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentServiceTest.java` (modify) — deactivation rules

**Steps**

1. EnvironmentService: inject OperationExecutionRepository and LockService. deactivateEnvironment: if operationExecutionRepository.hasActiveOperations(environmentId) -> throw ValidationException('Environment has running operations; wait for them to finish or cancel them').
2. If lockService.getCurrentLock(environmentId) is present, call lockService.breakLock(environmentId, currentUserId, "Environment deactivated") (current user from userService.getCurrentUserId()); wrap its notification side effects so they cannot roll back the deactivation.
3. confirmDeleteEnvironment: title 'Deactivate environment', message 'This will deactivate "<name>". It disappears from lists and schedules, and can be reactivated later.', impact lines: 'N VM(s) stop being synced and operated', 'Active lock is released', 'Access grants stay but cannot be used while inactive'; confirm button 'Deactivate'.
4. environments.js deleteEnvironment: same wording, no 'cannot be undone'; build the message with Utils.html (E01).
5. environments.js dependsText: map each id via the groups array already loaded for the detail page (group.groupId -> displayName || name), falling back to 'removed group'.
6. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given an in-progress operation, when an admin deactivates the environment, then 400 with the running-operations message and the environment stays active.
- Given a locked environment with no running operations, when deactivated, then the lock is inactive with break reason 'Environment deactivated' and a lock history row exists.
- Given the delete dialog, then it says deactivate and mentions reactivation, never 'permanent'.
- Given group B depends on A, then the detail page shows 'Depends: <A display name>'.

**Tests**

- EnvironmentServiceTest: running operation blocks; lock broken on deactivate; no lock -> breakLock not called.

## E11

### Audit, logs, notifications & dashboard — Fix

Every significant change is audited reliably with its real actor, the log pages filter and export correctly, notifications and emails are safe and cheap to fan out, and the dashboard shows only what the user may see.

Sources: H9, H19, H20, M13, M14, M31, M37, M38, LOW-logs-dashboard-notifications · 11 tasks · 11 days

#### E11-T01 — Move audit writes into a proxied AuditWriter bean and record the real actor for environment create/deactivate/reactivate

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** H9 · **Depends on:** E00-T01, E00-T03

AuditService helpers call this.logEnvironmentAction/this.logAction, so @Async and REQUIRES_NEW never apply and audit rows roll back with the caller; EnvironmentService passes the actor "system", which violates the audit_log.user_id FK on deactivate/reactivate (H9).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/AuditWriter.java` (new) — @Service with write(AuditLog) (@Async("notificationExecutor") + @Transactional(REQUIRES_NEW)) and writeSync(AuditLog) (REQUIRES_NEW only)
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — logAction/logEnvironmentAction/logChange/logFailure/logEnvironmentFailure (lines 57-200) build the AuditLog and delegate to AuditWriter; success rows via AfterCommit.run, failure rows immediately; logActionSync delegates to writeSync
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — createEnvironment, deactivateEnvironment, reactivateEnvironment take String actorUserId instead of hard-coded "system" (lines ~192, 235, 248)
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — POST /, DELETE /{environmentId}, POST /{environmentId}/reactivate pass userService.getCurrentUserId()
- `src/test/java/com/tcgdigital/vmcontrol/service/AuditServiceTest.java` (modify) — verify delegation to a mocked AuditWriter
- `src/test/java/com/tcgdigital/vmcontrol/service/AuditWriterIntegrationTest.java` (new) — commit/rollback behaviour against MySQL

**Steps**

1. Create AuditWriter (constructor-injected AuditLogRepository). write(AuditLog entry): @Async("notificationExecutor") @Transactional(propagation = Propagation.REQUIRES_NEW); set logId if null, save, catch and log exceptions exactly as today. writeSync(AuditLog entry): @Transactional(REQUIRES_NEW), returns the saved entity or null.
2. AuditService: inject AuditWriter. Rewrite the five write methods to build the AuditLog as today, then: success rows -> AfterCommit.run(() -> auditWriter.write(entry)); failure rows (logFailure, logEnvironmentFailure) -> auditWriter.write(entry) directly so the failure record survives a caller rollback. Remove @Async/@Transactional from these AuditService methods. logActionSync -> auditWriter.writeSync(builder.build()).
3. Keep every public signature so the ~60 existing callers compile unchanged.
4. Apply auditUserId() inside the five base write methods too (not only in the convenience helpers), so any remaining "system"/blank id is stored as NULL instead of failing the FK.
5. EnvironmentService: add String actorUserId to createEnvironment(CreateEnvironmentDTO), deactivateEnvironment(String), reactivateEnvironment(String); pass it to logEnvironmentCreated / logEnvironmentAction. EnvironmentController (lines ~247, 287, 308) passes userService.getCurrentUserId(); fix any other callers and tests found by grep.
6. Run graphify update . after the change.

**Acceptance criteria**

- Given an admin deactivates an environment, when the request completes, then an audit_log row ENVIRONMENT_DEACTIVATED exists with user_id = that admin's id.
- Given a service method logs a success audit and then throws (rollback), when it runs, then no success row is persisted.
- Given a service method logs a failure audit and then throws, when it runs, then the failure row is persisted.
- Given a caller passes "system" as user id, when the row is written, then user_id is NULL and no FK error is logged.

**Tests**

- AuditWriterIntegrationTest (extends AbstractIntegrationTest): a @Transactional helper bean calls auditService.logEnvironmentAction then throws -> Awaitility asserts 0 rows; with logEnvironmentFailure -> 1 row with action_status 'failed'; committed call -> 1 row.
- EnvironmentServiceTest: create/deactivate/reactivate pass the actor id to AuditService (Mockito verify).
- AuditServiceTest: mocks AuditWriter; "system" maps to null user id in the built entity.

**Notes:** AfterCommit.run runs immediately when no transaction is active, so scheduler callers are unaffected. If E10 changed EnvironmentService signatures, add the actor parameter to the current methods.

#### E11-T02 — Snapshot actor name and email on audit rows so hard-deleted users keep readable history

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** LOW-logs-actor-names · **Depends on:** E11-T01

audit_log.user_id is ON DELETE SET NULL (V1) and names are resolved only at read time from app_user, so rows lose their actor when a user is hard-deleted (logs low item).

**Files**

- `src/main/resources/db/migration/V<next>__audit_log_actor_snapshot.sql` (new) — add actor_name, actor_email; backfill from app_user
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditLog.java` (modify) — map actor_name/actor_email; Builder methods actorName/actorEmail
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — stamp actor snapshot when building rows; enrichUserInfo falls back to the snapshot

**Steps**

1. New migration (next free V number at implementation time): ALTER TABLE audit_log ADD COLUMN actor_name VARCHAR(255) NULL; ALTER TABLE audit_log ADD COLUMN actor_email VARCHAR(255) NULL; UPDATE audit_log a JOIN app_user u ON u.user_id = a.user_id SET a.actor_name = COALESCE(NULLIF(u.display_name,''), u.email), a.actor_email = u.email WHERE a.actor_name IS NULL;
2. AuditLog: add the two @Column fields, getters/setters and Builder methods.
3. AuditService: private void stampActor(AuditLog entry) loads the user once when userId is non-null and sets actorName/actorEmail; call it in each base write method before handing off to AuditWriter.
4. enrichUserInfo: when the user is not found, set userDisplayName/userEmail from actorName/actorEmail. Check AuditLogDTO.fromEntity reads those transients (adjust if not).

**Acceptance criteria**

- Given an audit row written by user U, when U's app_user row is deleted, then GET /api/v1/audit/logs still shows U's name and email on that row.
- Given rows created before the migration, when it runs, then rows whose user still exists get actor_name/actor_email filled.

**Tests**

- AuditWriterIntegrationTest: write a row, delete the user via JdbcTemplate, read through AuditService.getRecentLogs -> userDisplayName equals the snapshot.
- FlywayMigrationTest passes with the new migration.

#### E11-T03 — Audit group, VM and environment edits and per-VM start/stop results

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** M31 · **Depends on:** E11-T01, E09-T09

Group create/update/delete, VM register/update and environment update write no audit row, and VM_START/STOP_COMPLETED/FAILED are never written (logVmStarted etc. have no callers), so the 'VM Start/Stop' filter and /audit/report/vm-operations are always empty (M31).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — add logGroupUpdated, logGroupDeleted, logVmUpdated, logEnvironmentUpdated
- `src/main/java/com/tcgdigital/vmcontrol/service/VmGroupService.java` (modify) — inject AuditService; createGroup/updateGroup/deleteGroup take actorUserId and audit
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — registerVm/updateVm take actorUserId and audit
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — updateEnvironment takes actorUserId and audits changed fields
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — executeVmOperation writes per-VM completed/failed audit rows
- `src/main/java/com/tcgdigital/vmcontrol/repository/AuditLogRepository.java` (modify) — findVmOperationsInRange also includes VM_RESTART_REQUESTED
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmGroupController.java` (modify) — POST, PUT /{groupId}, DELETE /{groupId} pass current user id
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — POST and PUT /{vmId} pass current user id
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — PUT /{environmentId} passes current user id

**Steps**

1. AuditService: add helpers modelled on logGroupCreated: logGroupUpdated(userId, envId, envName, groupId, groupName, changes), logGroupDeleted(userId, envId, envName, groupId, groupName), logVmUpdated(userId, envId, envName, vmId, vmName, changes), logEnvironmentUpdated(userId, envId, envName, changes) using GROUP_UPDATED, GROUP_DELETED, VM_UPDATED, ENVIRONMENT_UPDATED (already in AuditAction). 'changes' is a 'field: old -> new; ...' string built by the caller, truncated to 500 chars; never include raw metadata JSON.
2. VmGroupService: inject AuditService via constructor; add String actorUserId to createGroup, updateGroup, deleteGroup; call logGroupCreated (exists, unused today), logGroupUpdated (diff name, displayName, sequencePosition, dependencies), logGroupDeleted. Update VmGroupController and every other caller (grep; system callers pass null).
3. VmService.registerVm/updateVm: add actorUserId; registerVm -> logVmRegistered; updateVm -> logVmUpdated with the diff. VM delete auditing is done in E09-T09: verify it exists, do not duplicate.
4. EnvironmentService.updateEnvironment: add actorUserId; call logEnvironmentUpdated with changed displayName/description/isActive/serviceType.
5. VmOperationsService.executeVmOperation (detail completed ~line 655, failed ~674 and the catch blocks): call auditService.logVmStarted/logVmStopped on success and logVmStartFailed/logVmStopFailed on failure, with vm.getGroup().getEnvironment().getEnvironmentId() and initiatedByUserId. RESTART logs as start completed/failed.
6. AuditLogRepository.findVmOperationsInRange: add 'VM_RESTART_REQUESTED' to the IN list.
7. Update existing tests for the new parameters.

**Acceptance criteria**

- Given an ENV_ADMIN edits a group, when saved, then a GROUP_UPDATED row exists with their user id and changed fields in details.
- Given a start operation on 2 VMs that both succeed, when it completes, then 2 VM_START_COMPLETED rows exist and GET /api/v1/audit/report/vm-operations for today returns them.
- Given a VM stop fails, when the step ends, then a VM_STOP_FAILED row with action_status 'failed' and the error is written.
- Given an environment display name change, then an ENVIRONMENT_UPDATED row records old and new value.

**Tests**

- VmGroupServiceTest (new, Mockito): create/update/delete call the matching AuditService helper with the actor.
- VmOperationsServiceTest: success and failure paths verify logVmStarted / logVmStopFailed.
- AuditControllerIntegrationTest: the vm-operations report returns rows produced by the operation path.

**Notes:** These services were already changed in E05, E09 and E10; add parameters to the current method shapes.

#### E11-T04 — Replace the one-filter-at-a-time audit query with one combined filtered query and a matching stats endpoint

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** H19, M38, LOW-logs-stats, LOW-logs-envadmin · **Depends on:** E11-T02, E00-T02, E00-T04, E03-T01

AuditController.getAuditLogs (lines 68-88) applies only the first non-null filter and has no success parameter, so 'Last 24h + user' returns all-time rows and 'Failure' includes successes; stat cards mix totalElements with page-only counts; dates are server-zone days (H19, M38, logs low items).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/AuditFilter.java` (new) — record(allowedEnvironmentIds, environmentId, userId, action, success, from, to, text)
- `src/main/java/com/tcgdigital/vmcontrol/repository/AuditLogRepository.java` (modify) — combined searchLogs JPQL plus count/failure/top-user/top-environment aggregates with the same WHERE
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — search(AuditFilter, Pageable), stats(AuditFilter)
- `src/main/java/com/tcgdigital/vmcontrol/dto/AuditStatsDTO.java` (new) — total, failures, successRate, topUser{id,name,count}, topEnvironment{id,name,count}
- `src/main/java/com/tcgdigital/vmcontrol/controller/AuditController.java` (modify) — GET /logs uses the combined query; new GET /logs/stats; from/to Instant params; ENV_ADMIN scoping

**Steps**

1. Create record AuditFilter(Collection<String> allowedEnvironmentIds /* null = unrestricted */, String environmentId, String userId, String action, Boolean success, Timestamp from, Timestamp to, String text).
2. AuditLogRepository: @Query searchLogs: WHERE (:restricted = false OR a.environmentId IN :allowedEnvIds) AND (:environmentId IS NULL OR a.environmentId = :environmentId) AND (:userId IS NULL OR a.userId = :userId) AND (:action IS NULL OR a.action = :action) AND (:status IS NULL OR a.actionStatus = :status) AND (:from IS NULL OR a.createdAt >= :from) AND (:to IS NULL OR a.createdAt < :to) AND (:text IS NULL OR LOWER(a.targetName) LIKE :text OR LOWER(a.details) LIKE :text) ORDER BY a.createdAt DESC. success=true -> status 'succeeded', false -> 'failed'. Pass a one-element placeholder list when unrestricted (an empty IN list is invalid).
3. Same WHERE for countByFilter, countFailuresByFilter, topUserByFilter (GROUP BY a.userId ORDER BY COUNT(a) DESC, Pageable.ofSize(1)), topEnvironmentByFilter.
4. AuditService.search(filter, pageable) -> enrichEnvironmentInfo(page); stats(filter) -> AuditStatsDTO with names resolved; successRate = round((total - failures) * 100 / total), 0 when total = 0.
5. AuditController GET /logs: params environmentId, userId, action, success (Boolean), from, to (@DateTimeFormat(iso = DATE_TIME) Instant), search, page, size. Keep startDate/endDate (ISO DATE) as deprecated fallbacks. Default range: last 7 days. Page via Paging.of(page, size, 500).
6. Scoping: ADMIN -> allowedEnvironmentIds = null; ENV_ADMIN -> environmentAccessService.getAdministeredEnvironmentIds(currentUserId) (EnvironmentAccessService ~line 675); an explicit environmentId outside that list -> 404 (ResourceNotFoundException). Keep any stricter rule E03 put on this endpoint.
7. New GET /logs/stats, @PreAuthorize("hasAnyRole('ADMIN','ENV_ADMIN')"), same params and scoping, returns AuditStatsDTO.
8. Delete getLogsByAction/searchLogs/getLogsInDateRange service methods only if no other caller remains (report endpoint still uses getLogsInDateRange).

**Acceptance criteria**

- Given rows from users A and B over 3 days, when GET /audit/logs?userId=A&from=<now-24h>&to=<now>, then only A's rows from the last 24 hours are returned.
- Given success=false, then every returned row has success=false.
- Given filters matching 120 rows of which 7 failed, when GET /audit/logs/stats with the same filters, then total=120 and failures=7 regardless of page size.
- Given an ENV_ADMIN administering only E1, when listing without environmentId, then no row from another environment is returned; with environmentId=E2 they get 404.
- Given size=5000, then the page size is clamped to 500.

**Tests**

- AuditControllerIntegrationTest (SecuredWebTestBase on AbstractIntegrationTest): seed rows via JdbcTemplate; assert combined filters, success filter, stats totals, ENV_ADMIN scoping and the size clamp.
- Repository test: ENV_ADMIN with no administered environment gets an empty page, not an error.

#### E11-T05 — Global Audit page: send combined filters as instants, use server stats, load actions from /audit/actions, work for ENV_ADMIN, and neutralise CSV formulas

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** H19, M14, M31, M38, LOW-logs-stats, LOW-logs-envadmin · **Depends on:** E11-T04, E02-T08

all-logs.js sends UTC calendar dates (formatDateForApi uses toISOString) and a success flag the old backend ignored, computes stats from one page (calculateStats), hard-codes action options that are never written, loads users from the ADMIN-only /users endpoint (403 for ENV_ADMIN), and exports cells starting with = + - @ unescaped (H19, M14, M31, M38, logs low items).

**Files**

- `src/main/resources/static/js/core/utils.js` (modify) — add Utils.csvCell(value) and Utils.downloadCsv(fileName, headers, rows)
- `src/main/resources/static/js/features/all-logs.js` (modify) — filters as instants, server stats, action dropdown from API, role-aware user filter, paged export
- `src/main/resources/static/js/config.js` (modify) — add Config.API.audit.stats

**Steps**

1. utils.js: csvCell(v): s = String(v ?? ''); if /^[=+\-@\t\r]/.test(s) prefix "'"; wrap in double quotes with internal quotes doubled. downloadCsv(fileName, headers, rows): build CSV with a UTF-8 BOM and trigger the download (move the Blob/link code from all-logs.js exportAuditLogs).
2. fetchAllAuditLogs (lines ~98-125): send from/to as ISO instants (start.toISOString(), now.toISOString()); send success only when resultStatus is 'true' or 'false'. Presets (applyTimeRangeFilter) compute from = now - 24h/7d/30d exactly. Remove formatDateForApi when unused.
3. Replace calculateStats(logs) with a request to Config.API.audit.stats using the same params; render cards from the response. Logs and stats load in parallel; page changes skip stats.
4. Action dropdown: fetch Config.API.audit.actions once per page load and render options with a readable label (underscores to spaces, title case); delete the hard-coded <option> block (lines ~289-312).
5. User filter: ADMIN keeps Config.API.users.list; ENV_ADMIN uses a type-ahead on /api/v1/users/search (allowed for ADMIN and ENV_ADMIN) and never calls /users, so no 403 toast.
6. exportAuditLogs (lines ~572-610): page through /audit/logs with size=500 until the last page or 10,000 rows, then Utils.downloadCsv; every cell via Utils.csvCell.
7. Use the E02 lifecycle helpers (ContentRouter.token/isCurrent) added in E02-T08 to drop stale responses.

**Acceptance criteria**

- Given a user in IST at 02:00 picks 'Last 24h', then rows from the last 24 hours including today 00:00-02:00 are shown.
- Given 'Failure' is selected, then only failed rows are shown and the Failures card equals Total.
- Given an ENV_ADMIN opens Global Audit, then the page loads without a 403 toast and all filters work.
- Given a row whose target name is =HYPERLINK("x"), when exported and opened in Excel, then the cell shows the text and no formula runs.
- Given the action dropdown, then it lists every value from GET /audit/actions.

**Tests**

- PR manual QA checklist covering the five acceptance cases at 1440px and 390px.
- If the Playwright project in test/ runs in CI, add test/tests/all-logs.spec.ts asserting request params (route interception) and CSV apostrophe prefixing.

#### E11-T06 — My Activity: drop the LIKE fallback, cap page size, send instants, and delete the legacy audit-logs.js module and dead export URLs

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** M37, M38, M14 · **Depends on:** E11-T05

findUserActivity (AuditLogRepository lines 35-48) ORs a LIKE on the TEXT details column, forcing a full scan and listing rows where the user is only the subject; size is uncapped (export asks for 10,000); dates are UTC days; legacy audit-logs.js reads non-existent fields and config.js lists export URLs that do not exist (M37, M38, M14).

**Files**

- `src/main/resources/db/migration/V<next>__backfill_audit_actor.sql` (new) — backfill user_id on legacy rows whose details name the actor by id
- `src/main/java/com/tcgdigital/vmcontrol/repository/AuditLogRepository.java` (modify) — findUserActivity without LIKE; remove unused findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc, findByEnvironmentIdAndCreatedAtBetweenOrderByCreatedAtDesc, findTop50ByEnvironmentIdOrderByCreatedAtDesc
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — getUserActivityLogs takes Timestamp from/to and Pageable
- `src/main/java/com/tcgdigital/vmcontrol/controller/AuditController.java` (modify) — GET /logs/my: from/to Instant params, Paging.of(page, size, 500)
- `src/main/resources/static/js/features/activity-logs.js` (modify) — instants, actions from /audit/actions, paged CSV export via Utils.csvCell/downloadCsv
- `src/main/resources/static/js/features/audit-logs.js` (delete) — legacy module
- `src/main/resources/static/index.html` (modify) — remove the audit-logs.js script tag (line ~725)
- `src/main/resources/static/js/config.js` (modify) — remove audit.export and audit.exportMy (lines ~155-156)
- `src/main/resources/static/js/core/keyboard.js` (modify) — 'g a' (line 14) navigates to activity-logs via the router if E02-T04 has not already changed it

**Steps**

1. On a prod-like copy run SELECT COUNT(*) FROM audit_log WHERE user_id IS NULL AND details LIKE '%by user: %'; put the count in the PR.
2. Migration (next free V number): UPDATE audit_log a JOIN app_user u ON a.details LIKE CONCAT('%by user: ', u.user_id, '%') SET a.user_id = u.user_id WHERE a.user_id IS NULL; (explicit actor pattern only, never the subject).
3. findUserActivity: WHERE a.userId = :userId plus the existing optional from/to/environment/action clauses; delete the LOWER(a.details) LIKE clause so idx_audit_log_user(user_id, created_at DESC) serves it.
4. getMyLogs: from/to Instants (LocalDate params kept as deprecated fallback, as in E11-T04); Paging.of(page, size, 500).
5. activity-logs.js: from/to via toISOString(); action options from Config.API.audit.actions (remove the hard-coded list at lines ~159-170); export pages through /audit/logs/my (size 500) up to 10,000 rows with Utils.downloadCsv.
6. Delete js/features/audit-logs.js and its script tag; remove Config.API.audit.export/exportMy; grep for 'AuditLogs.' and fix keyboard.js.
7. Remove the three unused AuditLogRepository methods (grep first).

**Acceptance criteria**

- Given 1M audit rows, when GET /audit/logs/my runs, then EXPLAIN shows idx_audit_log_user, not a full scan.
- Given user U is only the subject of an ACCESS_GRANTED row written by admin A, when U opens My Activity, then that row is not listed.
- Given size=10000, then at most 500 rows are returned.
- Given the user presses g then a, then Activity Logs opens and no action shows as 'Unknown'.
- Given a grep for 'audit/logs/export' under static/, then nothing is found.

**Tests**

- AuditControllerIntegrationTest: /logs/my returns only rows with user_id = caller, honours from/to, clamps size.
- FlywayMigrationTest: backfill sets user_id only for rows with the 'by user: <id>' pattern.

#### E11-T07 — Add retention jobs for audit_log, notification, vm_state_history and email_log

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** medium · **Sources:** M37, LOW-logs-retention · **Depends on:** E06-T01, E11-T06

Nothing prunes these tables (no delete-by-age code exists), so they grow without bound and slow every log query (M37, logs low item).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/DataRetentionService.java` (new) — per-table batched deletes older than N days
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/DataRetentionScheduler.java` (new) — @Scheduled(cron = "${retention.cron:0 0 3 * * *}") through ScheduledJobLockService.runLocked("data_retention", ...)
- `src/main/java/com/tcgdigital/vmcontrol/repository/AuditLogRepository.java` (modify) — @Modifying native deleteOlderThan(cutoff, limit)
- `src/main/java/com/tcgdigital/vmcontrol/repository/NotificationRepository.java` (modify) — deleteReadOlderThan / deleteOlderThan
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmStateHistoryRepository.java` (modify) — deleteOlderThan
- `src/main/java/com/tcgdigital/vmcontrol/repository/EmailLogRepository.java` (modify) — deleteOlderThan
- `src/main/resources/application.properties` (modify) — retention.* properties
- `.env.example` (modify) — document RETENTION_* variables

**Steps**

1. Properties: retention.enabled=${RETENTION_ENABLED:false}, retention.cron=${RETENTION_CRON:0 0 3 * * *}, retention.audit-log.days=365, retention.notification.read-days=90, retention.notification.unread-days=180, retention.state-history.days=180, retention.email-log.days=180, retention.batch-size=5000.
2. Each repository: @Modifying @Query(nativeQuery = true, value = "DELETE FROM <table> WHERE <ts> < :cutoff ORDER BY <ts> LIMIT :limit") int deleteOlderThan(Timestamp cutoff, int limit). Timestamp columns: audit_log.created_at, notification.created_at (read variant adds is_read = TRUE), email_log.sent_at, vm_state_history: use the column mapped by VmStateHistory.changedAt (check the entity).
3. DataRetentionService.purgeAll(): per table, loop deleteOlderThan inside TransactionTemplate (REQUIRES_NEW) until fewer than batch-size rows are deleted; log counts; write one SCHEDULED_JOB_EXECUTED audit row (targetType 'job', targetId 'data_retention') with the totals.
4. DataRetentionScheduler: @ConditionalOnProperty(name = "retention.enabled", havingValue = "true"), calls scheduledJobLockService.runLocked("data_retention", Duration.ofMinutes(30), service::purgeAll) (E06-T01).

**Acceptance criteria**

- Given retention.enabled=false (default), then no rows are deleted.
- Given retention.audit-log.days=365 and rows aged 400 and 10 days, when the job runs, then only the 400-day row is deleted.
- Given 12,000 expired read notifications and batch-size 5000, when the job runs, then all are removed in 3 short transactions.
- Given two app instances, then only one runs the purge per schedule.

**Tests**

- DataRetentionServiceIntegrationTest (AbstractIntegrationTest): seed old/new rows in all four tables, run purgeAll, assert remaining counts including the unread-notification rule.
- ApplicationContextRunner test: no DataRetentionScheduler bean when retention.enabled=false.

**Notes:** Ships off. Enable per environment after the compliance owner confirms retention periods. V1 also seeded system_config rows for notification/audit retention that nothing reads; they are removed with the table in E14-T05.

#### E11-T08 — Fix the notification read 500, the null-actor crash and N+1 fan-out

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-notif-500, LOW-notif-n+1-null-actor · **Depends on:** E00-T05, E04-T02

NotificationService.markAsRead throws RuntimeException (500) for another user's id; resolveUserDisplayName(null) calls userRepository.findById(null) for system-initiated operations; resolveAdministeringEnvAdmins runs one grant query per env-admin and create() saves one row at a time (logs low items).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — 404 on foreign/missing id; null-safe actor name; single-query env-admin resolution; saveAll
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentAccessRepository.java` (modify) — query: active env-admin users holding an ADMIN grant on an environment
- `src/test/java/com/tcgdigital/vmcontrol/service/NotificationServiceTest.java` (modify) — new cases
- `src/test/java/com/tcgdigital/vmcontrol/controller/NotificationControllerIntegrationTest.java` (new) — 404 for another user's notification

**Steps**

1. markAsRead (line ~84): throw new ResourceNotFoundException("Notification", notificationId) for both missing and foreign ids.
2. resolveUserDisplayName (line ~469): return "Automation" when userId is null or blank, before touching the repository.
3. EnvironmentAccessRepository: add findActiveEnvAdminGrantHolders(environmentId, now) selecting DISTINCT users with an active ENVIRONMENT-scope ADMIN grant on the environment, user.envAdmin = true and user.isActive = true (use the rank comparison from E04-T02 if levels are compared by rank). resolveAdministeringEnvAdmins uses it.
4. broadcastToEnvironment and notifyStopEnvironment: build a List<Notification> and call notificationRepository.saveAll once (extract a newNotification(...) factory from create()).
5. notifyOperationCompleted / notifyOperationFailed (lines ~268-282) pass the environment id as entityId instead of null when the caller has it (needed by E11-T10).

**Acceptance criteria**

- Given user B, when PATCH /api/v1/notifications/{A's id}/read, then 404 with the standard error body.
- Given an automation-triggered operation with a null actor completes, then notifications are created naming 'Automation' and no exception is logged.
- Given an environment with 20 env-admins, when a broadcast is sent, then recipient resolution uses a constant number of queries (Hibernate statistics).

**Tests**

- NotificationServiceTest: foreign id -> ResourceNotFoundException; null actor; saveAll called once per broadcast.
- NotificationControllerIntegrationTest (SecuredWebTestBase): 404 for another user's notification.

#### E11-T09 — Escape email bodies and stop exposing broadcast recipients

- [ ] Done · **Type:** security · **Estimate:** 0.5 d · **Risk:** low · **Sources:** M13 · **Depends on:** E11-T08

EmailTemplates.eventEmail (lines 15-20) escapes the title but inserts the body raw (lock reasons, names), and EmailService line ~78 puts every recipient in To:, so a stop notice can carry a phishing link to every member with the full list visible (M13).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/EmailTemplates.java` (modify) — eventEmail(title, bodyText) escapes the body and turns newlines into <br>; escape() also handles single quotes
- `src/main/java/com/tcgdigital/vmcontrol/service/EmailService.java` (modify) — multi-recipient sends use Bcc with To = from address
- `src/test/java/com/tcgdigital/vmcontrol/service/EmailServiceTest.java` (modify) — assert To/Bcc headers
- `src/test/java/com/tcgdigital/vmcontrol/service/EmailTemplatesTest.java` (new) — escaping tests

**Steps**

1. EmailTemplates.eventEmail: rename the parameter to bodyText and emit "<p>" + escape(bodyText).replace("\n", "<br>") + "</p>". Add .replace("'", "&#39;") to escape(). Confirm callers (NotificationService ~565, ~580) pass plain text.
2. EmailService.sendHtml: one address -> helper.setTo(address); several -> helper.setTo(fromAddress) and helper.setBcc(addresses). email_log still records all recipients.
3. Do not auto-link URLs in bodies.

**Acceptance criteria**

- Given a stop-notice reason '<a href="http://evil">Click</a>', when the email is sent, then the body shows the literal text and contains no <a> element.
- Given a broadcast to 15 users, then the MIME message has To = the from address and 15 Bcc recipients.
- Given a single-recipient email, then To = that recipient.

**Tests**

- EmailTemplatesTest: tag/quote escaping and newline -> <br>.
- EmailServiceTest: capture the MimeMessage from a mocked JavaMailSender and assert To/Bcc.

#### E11-T10 — Make bell notifications clickable and give every notification type an icon

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** LOW-notif-bell · **Depends on:** E11-T08, E02-T03

Bell items cannot be clicked to reach their entity, and ACCESS_LEVEL_CHANGED, AUTOMATION_RULE_SKIPPED, the three WEEKLY_*_REPORT types and ENVIRONMENT_STOP_NOTICE fall back to fa-bell in notification-bell.js typeIcon (logs low items).

**Files**

- `src/main/resources/static/js/ui/notification-bell.js` (modify) — entity link mapping, click/Enter marks read and navigates, complete icon and tone maps
- `src/main/resources/static/css/components/notification-bell.css` (modify) — pointer, hover and focus-visible styles for linked items

**Steps**

1. Add linkFor(n): entityType ENVIRONMENT with entityId -> '#/environments/' + entityId (route from E02-T03); ACCESS -> '#/my-environments'; ACCESS_REQUEST -> '#/pending-requests'; VM -> '#/vm-registry'; type WEEKLY_COST_REPORT -> '#/cost-management'; otherwise null.
2. buildItem: when linkFor(n) is set, make the item focusable (tabindex=0, role=menuitem); on click or Enter mark it read (markOneRead), close the dropdown and navigate through ContentRouter.
3. typeIcon additions: ACCESS_LEVEL_CHANGED 'fa-user-shield', AUTOMATION_RULE_SKIPPED 'fa-forward', WEEKLY_COST_REPORT 'fa-file-invoice-dollar', WEEKLY_IDLE_WASTE_REPORT 'fa-bed', WEEKLY_RIGHTSIZING_REPORT 'fa-compress-arrows-alt', ENVIRONMENT_STOP_NOTICE 'fa-stop-circle'. typeTone: ENVIRONMENT_STOP_NOTICE and AUTOMATION_RULE_SKIPPED -> warning.
4. Replace the local escHtml with Utils.escapeHtml (E01).

**Acceptance criteria**

- Given an OPERATION_COMPLETED notification for environment E, when clicked, then it is marked read and the URL becomes #/environments/E.
- Given each NotificationType value, then the bell shows a type-specific icon.
- Given keyboard focus on a linked item, when Enter is pressed, then it navigates.

**Tests**

- PR manual QA list; Playwright spec in test/ if that project runs in CI (intercept /notifications).

#### E11-T11 — Limit dashboard VMs to visible groups, search by display name and fix the lock badge colour

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** H20, LOW-dashboard · **Depends on:** E03-T01, E02-T06

DashboardSummaryService.buildSummary (lines ~69-74) loads every VM in each reachable environment, including groups a group-grant user cannot access (H20); dashboard search matches only the slug (dashboard.js line ~279) and the Locked/Open badge colour comes from running counts (line ~277) (dashboard low items).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/DashboardSummaryService.java` (modify) — non-admins: filter VMs by SecurityService.getVisibleGroupIds per environment
- `src/main/resources/static/js/features/dashboard.js` (modify) — search on display name + slug; badge colour from lock state
- `src/test/java/com/tcgdigital/vmcontrol/service/DashboardSummaryServiceTest.java` (new) — group-grant scoping

**Steps**

1. Inject SecurityService into DashboardSummaryService. In getSummary for non-admins, load the current user once and build visibleGroupIds = union over environments of securityService.getVisibleGroupIds(user, envId).
2. buildSummary takes Set<String> visibleGroupIds (null for admins) and filters vms by vm.getGroup().getGroupId() before computing ids, counts, idle, inventory, volumes and samples.
3. dashboard.js: data-name = lower-cased displayName + ' ' + name; the search handler matches substrings of it.
4. dashboard.js: badge Locked -> 'bg-warning text-dark', Open -> 'bg-success'; show the running/total colour on the running-count cell instead.

**Acceptance criteria**

- Given a USER with only a GROUP grant on 'web' in an environment with groups web and db, when GET /api/v1/dashboard/summary, then totals, top VMs and utilization include only web's VMs.
- Given an ADMIN, then the summary is unchanged.
- Given display name 'Payments QA' with slug 'pay-qa', when typing 'payments' in dashboard search, then the row stays visible.
- Given an unlocked environment with all VMs stopped, then its badge reads Open in green.

**Tests**

- DashboardSummaryServiceTest (AbstractIntegrationTest): environment with two groups and a group grant; assert totalVms and top-VM ids.
- Manual QA for search and badge.

## E12

### Metrics pipeline & system health — Fix

Raw metrics are collected consistently, archived and rolled up without loss at any fleet size, and System Health shows true 24-hour numbers and every scheduled job's real freshness.

Sources: M17, metrics lows (mixed-timestamp sample; gaps never backfilled; one chunk exception aborts the rest; memoryUtilization not exposed; unused VmMetricHourly; period param ignored), system-health lows (24h tables not 24h; success rate from a sample; danger toast; admin-only buttons; scheduler health 4 of 14 jobs and archive mislabelled; lockedBy shown to all) · 6 tasks · 7 days

#### E12-T01 — Archive metric samples in a loop of short batches with day-aligned cutoff and archive retention; stop the rollup re-aggregating partially archived days

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** M17 · **Depends on:** E00-T01, E06-T01

M17: VmMetricsArchiveService.archiveOldRawSamples (lines 40-67) moves at most one 10,000-row batch per day (about 34 always-on VMs), has no archive retention, and its cutoff is now-30d, while VmMetricRollupService.rollupDailyMetrics (69-113) re-aggregates day 30 after part of it was archived, overwriting a full-day aggregate with a partial one.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricsArchiveService.java` (modify) — loop batches in own transactions, day-aligned cutoff, batch existence check, retention purge
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmMetricSampleArchiveRepository.java` (modify) — findExistingKeys for a batch; deleteOlderThan batch (native DELETE ... LIMIT)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricRollupService.java` (modify) — lookback = rawToArchiveDays - 1; never downgrade an existing row's sampleCount
- `src/main/resources/application.properties` (modify) — vm.metrics.archive.max-batches-per-run=200, vm.metrics.archive.retention-days=365
- `src/test/java/com/tcgdigital/vmcontrol/service/VmMetricsArchiveServiceTest.java` (new) — loop/cutoff/retention
- `src/test/java/com/tcgdigital/vmcontrol/service/VmMetricRollupServiceTest.java` (new) — oldest-day protection

**Steps**

1. Cutoff = LocalDate.now().minusDays(rawToArchiveDays).atStartOfDay() (server zone, same as the rollup's day boundaries) as Timestamp.
2. Remove @Transactional from archiveOldRawSamples; inject TransactionTemplate and run each batch (archiveBatch(cutoff)) in its own transaction; loop while the batch returned batchSize rows and batches < maxBatchesPerRun; return total.
3. Replace the per-sample existsByVmVmIdAndSampleTimeAndPeriodSeconds call with one query per batch: SELECT a.originalMetricSampleId FROM VmMetricSampleArchive a WHERE a.originalMetricSampleId IN :ids; skip those when copying.
4. Retention: after archiving, if retention-days > 0 delete archive rows with sample_time < now - retention-days in loops of 10,000 using a native @Modifying query (DELETE FROM vm_metric_sample_archive WHERE sample_time < :cutoff LIMIT 10000) each in its own transaction.
5. VmMetricRollupService: loop daysAgo from 0 to lookbackDays - 1 (days fully inside the hot window given the day-aligned cutoff). In rollupForDate, when an existing row has sampleCount greater than the new aggregate's count, keep the existing row (log.debug).
6. Log totals: archived, deleted from hot, purged from archive, batches.

**Acceptance criteria**

- Given 45,000 samples older than the cutoff and batch-size 10,000, when the archive job runs once, then all 45,000 are moved in 5 batches.
- Given archive rows older than retention-days, when the job runs, then they are deleted.
- Given day D-30 had a full rollup row with 288 samples, when the rollup runs after archiving, then the row still shows 288 samples.
- Given a failure in batch 3, then batches 1-2 stay committed.

**Tests**

- VmMetricsArchiveServiceTest (AbstractIntegrationTest, small batch size via @TestPropertySource): loops until empty, respects max-batches, day-aligned cutoff, retention purge.
- VmMetricRollupServiceTest (Mockito): lookback excludes the boundary day; existing higher sampleCount not overwritten.

**Notes:** First run on a backlog can be long; max-batches-per-run bounds it and the scheduler lock TTL (E06-T01 runLocked) must exceed the run time.

#### E12-T02 — Take the scheduler job lock in manual metrics/inventory/sync endpoints

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** M17 · **Depends on:** E06-T01, E12-T01

M17: MonitoringController.triggerMetricsSync and triggerMetricsArchive (lines ~178-204) call the services directly, bypassing the job lock, so a manual run can overlap the scheduled one (duplicate archive copies, double CloudWatch cost).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/MonitoringController.java` (modify) — metrics/sync, metrics/archive, inventory/sync run through ScheduledJobLockService; 409 when held
- `src/main/java/com/tcgdigital/vmcontrol/service/ScheduledJobLockService.java` (modify) — add <T> Optional<T> tryRunLocked(String jobName, Duration ttl, Supplier<T> job) if E06-T01's runLocked cannot report that the job was skipped
- `src/test/java/com/tcgdigital/vmcontrol/controller/MonitoringControllerIntegrationTest.java` (modify) — 409 when lock held; normal path still 200

**Steps**

1. Use the exact job names the schedulers pass to runLocked after E06-T01 (today: vm_metrics_sync, vm_metrics_archive, vm_inventory_sync).
2. Add tryRunLocked (returns Optional.empty() when the lock is held) next to E06-T01's runLocked, sharing its acquire/release code.
3. MonitoringController: inject ScheduledJobLockService; triggerMetricsSync -> tryRunLocked("vm_metrics_sync", ttl, metricsService::syncRunningVmMetrics); empty -> 409 {status:'busy', message:'Metrics sync already running'}. Same for archive and inventory sync.
4. Leave POST /monitoring/sync (state sync) as is: it already returns 409 through isSyncInProgress, and E06-T01 makes its scheduler locked.

**Acceptance criteria**

- Given the scheduled archive holds vm_metrics_archive, when an admin POSTs /monitoring/metrics/archive, then 409 and the service is not invoked.
- Given no job is running, when POSTing /monitoring/metrics/sync, then 200 with samplesSaved and the lock is released afterwards.

**Tests**

- MonitoringControllerIntegrationTest: pre-insert an unexpired scheduled_job_lock row -> 409; no row -> 200 and lock row released.

#### E12-T03 — Collect metrics per CloudWatch timestamp, backfill gaps, isolate chunk failures and keep AWS calls out of transactions

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** metrics-low: sample mixes timestamps, metrics-low: gaps never backfilled, metrics-low: one chunk exception aborts the rest · **Depends on:** E12-T02

Metrics lows: AwsCloudMetricsProviderService.fetchLatestMetrics keeps only the first datapoint of each metric and overwrites sampleTime per metric, so one stored sample can mix values from different timestamps; older datapoints in the window are dropped so gaps are never backfilled; one try/catch around all 80-instance chunks means one chunk exception aborts the rest; VmMetricsService.syncRunningVmMetrics is @Transactional across CloudWatch calls.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CloudMetricsProviderService.java` (modify) — add fetchMetricSeries(ids, region, start, end, period) -> Map<String, List<VmMetricData>> (one entry per timestamp)
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudMetricsProviderService.java` (modify) — implement series: align values by timestamp, follow NextToken, per-chunk try/catch
- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricsService.java` (modify) — window from last stored sample (capped), save every missing timestamp, no class-level transaction
- `src/main/resources/application.properties` (modify) — vm.metrics.backfill-max-hours=3
- `src/test/java/com/tcgdigital/vmcontrol/service/AwsCloudMetricsProviderServiceTest.java` (new) — alignment, pagination, chunk isolation
- `src/test/java/com/tcgdigital/vmcontrol/service/VmMetricsServiceTest.java` (new) — backfill and idempotency

**Steps**

1. AwsCloudMetricsProviderService.fetchMetricSeries: for each 80-id chunk (keep 6 queries per id, under the 500-query limit) call getMetricData in a loop while response.nextToken() != null; wrap each chunk in try/catch(Exception) logging the chunk's ids and continuing.
2. For each MetricDataResult iterate timestamps()/values() pairwise and put into Map<instanceId, TreeMap<Instant, VmMetricData>>, so all metrics of one sample share one timestamp. Return as lists sorted by time.
3. Re-implement fetchLatestMetrics as the last element of fetchMetricSeries (keep it for other callers, e.g. idle checks).
4. VmMetricsService.syncRunningVmMetrics: remove @Transactional. Window start per (provider, region) group = min over its VMs of (latest stored sampleTime from sampleRepository.findTopByVmVmIdOrderBySampleTimeDesc, batched if needed) but no earlier than now - backfill-max-hours; end = now.
5. For each VM save every returned datapoint not already stored (existing uniqueness check + uq_vm_metric_sample catch), each save in its own short transaction via TransactionTemplate, then updateIdleSummary once per VM.
6. Return the number of samples saved.

**Acceptance criteria**

- Given CloudWatch returns CPU for 10:00 and network for 09:55, when samples are stored, then the 10:00 sample has CPU and the 09:55 sample has network; no sample mixes them.
- Given the job missed 30 minutes, when it next runs, then the six missing 5-minute samples are stored.
- Given the second chunk of three throws, then samples from chunks 1 and 3 are stored.
- Given a response with NextToken, then the next page is requested.

**Tests**

- AwsCloudMetricsProviderServiceTest (mock CloudWatchClient): timestamp alignment, NextToken loop, chunk isolation.
- VmMetricsServiceTest (Mockito): backfill window capped, duplicates skipped, idle summary updated once per VM.

**Notes:** CloudWatch GetMetricData cost grows with datapoints returned; the backfill cap keeps it bounded.

#### E12-T04 — Expose memory utilisation, honour the metrics period parameter and remove the unused VmMetricHourly entity

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** metrics-low: memoryUtilization not exposed, metrics-low: period param ignored, metrics-low: unused VmMetricHourly · **Depends on:** E12-T03

Metrics lows: memoryUtilization is stored but VmMetricsDTO.SampleDTO omits it; GET .../vms/{vmId}/metrics accepts period but VmMetricsService.getMetrics only echoes it; VmMetricHourly/VmMetricHourlyRepository are never used.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/VmMetricsDTO.java` (modify) — SampleDTO.memoryUtilization; from() accepts already-bucketed samples
- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricsService.java` (modify) — getMetrics validates period and buckets samples
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — document allowed period values (line ~229)
- `src/main/java/com/tcgdigital/vmcontrol/model/VmMetricHourly.java` (delete) — unused entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmMetricHourlyRepository.java` (delete) — unused repository
- `src/main/resources/static/js/features/environments.js` (modify) — VM metrics view shows memory series when present (request near line ~1528)
- `src/test/java/com/tcgdigital/vmcontrol/service/VmMetricsServiceTest.java` (modify) — bucketing and validation

**Steps**

1. SampleDTO: add BigDecimal memoryUtilization and map it in from().
2. VmMetricsService.getMetrics: allowed periods {300, 900, 3600}; otherwise throw ValidationException (400). When period > stored period, group samples by floor(sampleTime / period) and produce one SampleDTO per bucket: avg CPU and memory, sum network/disk bytes, bucket start as sampleTime. DTO periodSeconds = effective period.
3. Delete VmMetricHourly.java and VmMetricHourlyRepository.java (grep confirms no other references). Leave the vm_metric_hourly table: unused-table drops belong to E14's schema hygiene.
4. environments.js: if any sample has memoryUtilization, render a Memory % line next to CPU; otherwise show 'Memory needs the CloudWatch agent'. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given samples with memory values, when GET .../metrics?window=1h, then each series item includes memoryUtilization.
- Given window=24h&period=3600, then at most 24 points are returned with periodSeconds 3600.
- Given period=7, then 400.
- Given the build, then no class references VmMetricHourly.

**Tests**

- VmMetricsServiceTest: bucketing math (avg vs sum), invalid period, memory mapped.

#### E12-T05 — Report health for every scheduled job with per-job expected intervals and hide lock owners from non-admins

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** system-health-low: scheduler health covers 4 of 14 jobs and mislabels the archive, system-health-low: lockedBy shown to all users · **Depends on:** E06-T01, E12-T04

System-health lows: DashboardSummaryService.buildSchedulerHealth (~345-361) covers 4 of 14 jobs, judges the daily archive with 15-minute/2-hour thresholds so it always shows STALE, and returns lock.getLockedBy() (host/instance id) to every user.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/SchedulerJobCatalog.java` (new) — list of {jobName, label, expectedInterval} built from the same properties each scheduler uses
- `src/main/java/com/tcgdigital/vmcontrol/service/DashboardSummaryService.java` (modify) — buildSchedulerHealth iterates the catalog; per-job thresholds; owner only for admins
- `src/main/resources/static/js/features/dashboard.js` (modify) — buildSchedulerHealthPanel renders all rows, interval hint, owner only when present
- `src/test/java/com/tcgdigital/vmcontrol/service/DashboardSummaryServiceSchedulerHealthTest.java` (new) — thresholds and owner visibility

**Steps**

1. SchedulerJobCatalog (@Component): one entry per scheduler class in scheduler/ (14: AccessExpiration, ActualCostIngestion, AutomationRule, CostSnapshot, EksSync, ReservationCoverage, StateSync, TagReconciliation, VmDiscovery, VmInventory, VmMetricRollup, VmMetricsArchive, VmMetrics, WeeklyReport). jobName = the name each passes to runLocked after E06-T01; expectedInterval from its fixedRate property or, for cron jobs, the period of the cron (daily = 24h, weekly = 7d) via CronExpression next-two-fires difference; enabled flag from its enable property (disabled jobs report DISABLED).
2. buildSchedulerHealth: for each catalog entry read the scheduled_job_lock row: RUNNING if lockedUntil > now; NEVER if no row; HEALTHY if age <= 1.5 x interval; LATE if <= 3 x; else STALE. Label the archive 'Metrics archive (daily)'. Drop the special state-sync path that derived freshness from Vm.lastStateSyncAt once state sync uses runLocked.
3. owner = securityService.isAdmin() ? lock.getLockedBy() : null (inject SecurityService).
4. dashboard.js: render every row; show the expected interval ('every 5 min', 'daily') under the name; omit the owner column when all owners are null. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given the archive ran 20 hours ago, then its status is HEALTHY (daily job), not STALE.
- Given all 14 schedulers, then the dashboard lists 14 rows (disabled ones as DISABLED).
- Given a USER views the dashboard, then no row includes an owner; an ADMIN sees owners.

**Tests**

- DashboardSummaryServiceSchedulerHealthTest (Mockito): status thresholds for 5-minute and daily jobs, NEVER/RUNNING/DISABLED, owner hidden for non-admin.

#### E12-T06 — System Health: true last-24-hour windows, server-computed success rate, role-aware buttons, working error toast

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** system-health-low: 24h tables not 24h, system-health-low: success rate from a sample, system-health-low: danger toast, system-health-low: admin-only buttons hidden for other roles · **Depends on:** E01 (escaping in system-health.js), E11 (audit/log fixes to AuditController), E12-T05

System-health lows: system-health.js sends yesterday..today as dates so '24h' tables cover up to 48 hours, and Drift Events (24h) uses the unfiltered drift list; the success rate is computed from the 20-row recentLogs sample; Notifications.show(..., 'danger') renders 'fas undefined' because ICONS has no 'danger'; Trigger Sync / EKS Sync buttons show for users who get 403.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/AuditController.java` (modify) — /report, /report/locks, /report/vm-operations accept optional from/to ISO date-times; report fills successfulActions/failedActions
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — Timestamp-range overloads of the report queries; countByStatusInRange
- `src/main/java/com/tcgdigital/vmcontrol/repository/AuditLogRepository.java` (modify) — countByActionStatusAndCreatedAtBetween
- `src/main/java/com/tcgdigital/vmcontrol/controller/MonitoringController.java` (modify) — /drift-events accepts optional from/to
- `src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java` (modify) — getDriftEvents(from, to, page, size)
- `src/main/resources/static/js/features/system-health.js` (modify) — send from/to = now-24h..now; use server success counts; hide admin buttons and admin-only sections by role
- `src/main/resources/static/js/ui/notifications.js` (modify) — treat 'danger' as 'error' in show()
- `src/test/java/com/tcgdigital/vmcontrol/controller/AuditControllerIntegrationTest.java` (modify) — from/to window and success counts

**Steps**

1. AuditController report endpoints: add @RequestParam(required=false) @DateTimeFormat(iso = DATE_TIME) Instant from, to; when both present use them, else keep the date behaviour (backward compatible). Make startDate/endDate required=false and 400 if neither pair is given.
2. AuditService: add Timestamp-based overloads for getActionCountsByType/User/Environment, getLogsInDateRange, getLockOperationsReport, getVmOperationsReport; the LocalDate versions delegate to them.
3. Report: successfulActions = count(action_status = 'succeeded'), failedActions = count(action_status = 'failed') in range (check AuditService.logFailure for the exact failure value).
4. MonitoringController /drift-events: optional from/to -> StateSyncService uses stateHistoryRepository.findDriftEventsInRange with paging (add a Pageable variant).
5. system-health.js: const to = new Date(); const from = new Date(to - 86400000); pass from/to as toISOString() to report, locks, vm-operations and drift-events; successRate = successfulActions / (successfulActions + failedActions); Successes/Failures cards use the server values.
6. Only call /audit/report* and render the audit/lock/vm-ops sections when Auth.isEnvAdmin() (which includes ADMIN); show Trigger Sync and EKS Sync buttons only for Auth.isEnvAdmin().
7. notifications.js show(): `if (type === 'danger') type = 'error';` before using ICONS/classes.
8. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given audit rows at now-30h and now-2h, when System Health loads, then the 24h counts and tables include only the now-2h row.
- Given 100 actions in 24h with 7 failures, then the success rate shows 93% regardless of the 20-row sample.
- Given Notifications.show('x', 'danger'), then the toast has the error icon and styling.
- Given a USER opens System Health, then no Trigger Sync/EKS Sync buttons render and no 403 requests are made.

**Tests**

- AuditControllerIntegrationTest: from/to window boundaries; successfulActions/failedActions; legacy date params still work.
- MonitoringControllerIntegrationTest: drift-events from/to filter.

**Notes:** Escaping of every value on this page is done in E01 (skipped here).

## E13

### UX, mobile & accessibility — Fix

Phone users can reach every page and read every table, keyboard and screen-reader users can reach every control, and colour, dialogs, toasts and status widgets behave correctly.

Sources: H22, H25, M28, M29, M30, LOW-FE-sync-pill-banner, LOW-FE-admin-collapsed, LOW-FE-danger-toast, LOW-FE-escapeHtml-0, LOW-FE-toast-id, LOW-FE-modal-instances, LOW-FE-group-headers-keyboard, LOW-FE-icon-aria-label, LOW-FE-touch-targets, LOW-FE-automation-keyboard · 10 tasks · 11 days

#### E13-T01 — Make phone navigation work: real bottom-bar links, hamburger opens the drawer, active state synced

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** H22 · **Depends on:** E02-T03

Bottom-bar links are href="#" (the router sends them to Dashboard) and the hamburger only toggles .collapsed while the sidebar stays off-screen below 768px, so a phone user can only reach the Dashboard (H22).

**Files**

- `src/main/resources/static/index.html` (modify) — mobile nav (L647-664) real #/ hrefs, More as a button with aria-controls/aria-expanded; hamburger (L54) gets aria-controls="sidebar" aria-expanded
- `src/main/resources/static/js/ui/sidebar.js` (modify) — toggleSidebar opens/closes the mobile drawer below 768px; overlay, Escape and navigation close it; setActiveItem syncs .mobile-nav-item
- `src/main/resources/static/css/layout/mobile.css` (modify) — drawer state, neutralise desktop .collapsed below 768px, button reset for More, 48px items
- `test/tests/mobile/phone-navigation.spec.ts` (new) — 390px viewport navigation

**Steps**

1. index.html L648-659: set href to `#/dashboard`, `#/my-environments`, `#/activity-logs` and remove their `data-content` (no JS needed; the router handles hashchange). Replace the 'More' anchor (L660-663) with `<button type="button" class="mobile-nav-item" id="mobile-more-btn" aria-controls="sidebar" aria-expanded="false">`.
2. sidebar.js: add `const isPhone = () => window.matchMedia('(max-width: 768px)').matches;` and `openDrawer(opener)`/`closeDrawer()` that toggle `#sidebar.mobile-open` and `#mobile-overlay.show`, keep aria-expanded on #toggleSidebar and #mobile-more-btn in sync, focus the first visible `.sidebar-menu-link` on open and return focus to the opener on close.
3. `toggleSidebar` (L62-65): if isPhone() toggle the drawer, else keep the current collapse behaviour.
4. bindEvents (L20-41): click `#mobile-more-btn` toggles the drawer; click `#mobile-overlay` closes it; Escape keydown closes it when open; `handleNavigation` (L117-131) calls closeDrawer() when isPhone(); a matchMedia change listener closes the drawer when the viewport grows past 768px.
5. `setActiveItem` (L153-156): also clear `.mobile-nav-item.active` and `aria-current`, then mark the bottom item whose href equals `ContentRouter.hashFor(key)` (key already mapped for environment-detail by E02-T03); if none matches, mark #mobile-more-btn active.
6. mobile.css: below 768px make `.sidebar.collapsed` behave like the hidden drawer (no narrow rail); `.mobile-nav-item { min-height: 48px; }`; reset button styles (background none, border 0, font inherit) for #mobile-more-btn.

**Acceptance criteria**

- Given a 390px viewport, when the user taps Environments in the bottom bar, then #/my-environments opens and that item is active.
- Given a 390px viewport, when the user taps the hamburger or More, then the sidebar drawer slides in over a dimmed overlay and Request Access and Admin links are reachable.
- Given the drawer is open, when the user taps a link, taps the overlay or presses Escape, then the drawer closes and focus returns to the opener.
- Given a desktop viewport, when the hamburger is clicked, then the existing collapse behaviour is unchanged.

**Tests**

- test/tests/mobile/phone-navigation.spec.ts (NEW, viewport 390x844): tap each bottom item and assert URL and active class; open the drawer via More, click 'Request Access', assert URL and drawer closed; Escape closes the drawer.

#### E13-T02 — Make sidebar section headers and the user menu keyboard-operable buttons; expand the section holding the active page

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** H25, LOW-FE-admin-collapsed · **Depends on:** E13-T01

Section titles are divs with click handlers and the user-menu trigger is a div without tabindex, role or aria-expanded, so keyboard users never reach Admin links, My Account or Logout (H25). The Admin section starts collapsed and hides the active page (low-severity).

**Files**

- `src/main/resources/static/index.html` (modify) — section titles (L110, L184, L225) become <button type=button aria-expanded aria-controls>
- `src/main/resources/static/js/ui/sidebar.js` (modify) — aria-expanded kept in sync; setActiveItem expands the containing section
- `src/main/resources/static/js/ui/user-menu.js` (modify) — trigger is a button with aria-haspopup/aria-expanded/aria-controls; Escape closes and returns focus; arrow keys move between items
- `src/main/resources/static/css/layout/sidebar.css` (modify) — button reset for .sidebar-section-title (L55-85), :focus-visible outline
- `src/main/resources/static/css/layout/topnav.css` (modify) — button reset and focus ring for .user-profile-trigger (near L119-156)
- `test/tests/a11y/keyboard-reach.spec.ts` (new) — Tab reaches Admin links, My Account and Logout

**Steps**

1. index.html: replace each `<div class="sidebar-section-title ..." data-section=...>` with `<button type="button" class="sidebar-section-title" data-section=... aria-expanded="true|false" aria-controls="workspace-menu|operations-menu|admin-menu">`, keeping ids and inner markup.
2. sidebar.css: `width:100%; background:none; border:0; text-align:left; font:inherit; color:inherit;` plus a `:focus-visible` outline in the primary colour.
3. sidebar.js `handleSectionClick` (L70-88), `expandSection` (L161-167), `collapseAllSections` (L172-175): set aria-expanded alongside the collapsed class.
4. sidebar.js `setActiveItem`: after marking the link, if its `.sidebar-menu` is hidden call `expandSection(menuId)`, so the Admin section opens for admin pages (refresh and deep links included).
5. user-menu.js `updateUserDisplay` (L296-343): the trigger becomes `<button type="button" class="user-profile-trigger" aria-haspopup="menu" aria-expanded="false" aria-controls="user-dropdown">`; the dropdown gets `id="user-dropdown" role="menu"`; items get `role="menuitem"`. Replace the local escapeHtml (L402-407) with Utils.escapeHtml and escape the avatar title (L311).
6. user-menu.js bindEvents (L265-291): bind toggling to `.user-profile-trigger`; on open focus the first menuitem; on the dropdown, ArrowDown/ArrowUp cycle items, Escape closes and focuses the trigger, Tab closes. `toggleDropdown`/`closeDropdown` (L370-388) update aria-expanded.

**Acceptance criteria**

- Given an admin using only the keyboard, when tabbing from the top of the page, then focus reaches the Admin section button, Enter expands it, and its links are reachable.
- Given focus on the user-menu trigger, when Enter is pressed, then the menu opens with aria-expanded=true and focus on My Account; Escape closes it and returns focus to the trigger.
- Given the user loads #/vm-registry, then the Admin section is expanded and VM Registry is marked active.
- Given a screen reader, then each section header announces its expanded or collapsed state.

**Tests**

- test/tests/a11y/keyboard-reach.spec.ts (NEW, admin creds): press Tab up to 60 times collecting document.activeElement text and assert the Admin button, 'User Management' link and user-menu trigger are reached; Enter/Escape on the menu; goto #/vm-registry and assert #admin-menu visible.

#### E13-T03 — Turn slide-out panels into accessible modal dialogs with focus management and safe Escape

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** M28 · **Depends on:** E02-T04, E02-T09, E13-T02

Panels lack role=dialog/aria-modal, focus stays on the page, Tab leaves the panel, closed panels stay in the Tab order, and Escape closes the panel while typing, losing the My Account extension draft (M28).

**Files**

- `src/main/resources/static/js/ui/slideout.js` (modify) — dialog semantics, focus in/trap/restore, inert when closed, Escape ignored in text fields
- `src/main/resources/static/index.html` (modify) — static #runningPanel/#lockedPanel (L296-379): role=dialog, aria-modal, aria-labelledby, inert; close buttons type=button aria-label="Close"
- `test/tests/a11y/slideout-dialog.spec.ts` (new) — focus and Escape behaviour

**Steps**

1. `openDynamic` (L61-87): panel markup gets `role="dialog" aria-modal="true" aria-labelledby="dynamicSlideoutTitle" inert`; the h3 gets `id="dynamicSlideoutTitle"`; close button `type="button"`. Set the title with `.text(title)` unless `options.html === true` (E01 text-by-default convention; check callers that pass HTML titles, e.g. environments.js buildVmInsightsTitle, and pass `{ html: true }`).
2. `open(panelId)` (L42-59): remember `lastFocus = document.activeElement`; remove `inert` from the opened panel; set `inert` on `#mainContent`, `.top-navbar`, `#sidebar`, `#mobile-nav`; after the next animation frame focus the first focusable element in the panel content, else the close button.
3. Add a keydown handler on panels for Tab/Shift+Tab that wraps focus between the first and last focusable elements (a[href], button:not([disabled]), input, select, textarea, [tabindex]:not([tabindex="-1"])).
4. `close()` (L92-103): add `inert` to all `.slideout-panel`, remove `inert` from the page regions, restore focus to `lastFocus` if it is still connected.
5. Escape handler (L27-31): ignore when `e.target` is input/textarea/select/contentEditable or when a Bootstrap `.modal.show` exists; otherwise close only if `isOpen()`. (Keyboard.js no longer handles Escape after E02-T04.)
6. `init()`: add `inert` to every `.slideout-panel`; if `!('inert' in HTMLElement.prototype)` additionally set aria-hidden=true on closed panels.

**Acceptance criteria**

- Given My Account opens, then focus moves into the panel, Tab cycles only within it, and a screen reader announces a dialog named 'My Account'.
- Given the user is typing an extension reason, when Escape is pressed, then the panel stays open and the draft is kept.
- Given focus is on a panel button, when Escape is pressed, then the panel closes and focus returns to the element that opened it.
- Given no panel is open, when tabbing through the page, then no element inside a closed panel receives focus.

**Tests**

- test/tests/a11y/slideout-dialog.spec.ts (NEW): open My Account via the user menu, assert activeElement inside #dynamicSlideoutPanel and role=dialog; Tab 30 times stays inside; open the Access tab extend form, type in the textarea, press Escape, assert panel visible and value kept; focus a panel button, press Escape, assert closed and focus on .user-profile-trigger.

#### E13-T04 — Fix failing colour contrast: amber log badges, small captions, progress label, yellow Edit buttons

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** M29 · **Depends on:** E02-T09

White on amber log badges, #94a3b8 captions (about 2.6:1), the white progress label on a light track, and the light icon on yellow Edit buttons fail WCAG AA (M29).

**Files**

- `src/main/resources/static/css/main.css` (modify) — add --text-caption: #64748b token next to --text-secondary (L22)
- `src/main/resources/static/js/features/activity-logs.js` (modify) — action badge class helper (~L470-480, 'bg-warning' at L478) returns text-bg-warning
- `src/main/resources/static/js/features/all-logs.js` (modify) — getActionBadgeClass (L661, L667) same
- `src/main/resources/static/css/components/operations.css` (modify) — #operationProgressModal #progress-percent dark label (near L79-91); .operation-status-cancelled colour
- `src/main/resources/static/css/components/buttons.css` (modify) — .btn-action.btn-warning and .btn-tonal.btn-warning (L123, L148, L201, L226) use dark amber text #92400e
- `src/main/resources/static/css/layout/content.css` (modify) — small caption text uses var(--text-caption)
- `test/package.json` (modify) — add devDependency @axe-core/playwright
- `test/tests/a11y/contrast.spec.ts` (new) — axe color-contrast on key pages

**Steps**

1. Add `--text-caption: #64748b;` to :root in main.css.
2. Grep `#94a3b8` in css/ and in js/features/environments.js and cost-management.js; where it colours text smaller than 1rem (captions, KPI hints, table meta) replace with var(--text-caption) or #64748b; keep it for borders, decorative icons and chart gridlines. List each changed file in the PR.
3. Log badges: return `text-bg-warning` instead of `bg-warning`; confirm the vendored Bootstrap (vendor/bootstrap) is >=5.2, else use `bg-warning text-dark`.
4. Progress label: `#operationProgressModal #progress-percent { color:#0f172a; text-shadow:0 0 2px #fff; }` so it reads on both the filled and empty track.
5. VM Registry Edit buttons (vm-registry.js L216, L538, L544 use `btn-warning btn-action`) get dark text via the buttons.css rules.
6. Add the axe spec.

**Acceptance criteria**

- Given Activity Logs with Lock Acquire/Release rows, then their badges have contrast >= 4.5:1.
- Given KPI hints and table captions, then their text contrast is >= 4.5:1.
- Given the operation progress dialog at any percentage, then the label contrast is >= 4.5:1.
- Given axe color-contrast runs on Dashboard, Activity Logs, VM Registry and Environment Detail, then it reports no violations.

**Tests**

- test/tests/a11y/contrast.spec.ts (NEW): for each page, `new AxeBuilder({ page }).withRules(['color-contrast']).analyze()` and expect violations to equal [].

#### E13-T05 — Fit the top bar and user dropdown to phone width and add the table-to-cards pattern on Environments list and detail

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** M30 · **Depends on:** E13-T01, E02-T07

At 390px the top bar is 544px wide, the user dropdown runs off-screen, and fixed-width tables overlap and clip Start/Stop on Environment Detail (M30).

**Files**

- `src/main/resources/static/css/layout/topnav.css` (modify) — below 768px hide .logo text, .user-info, .dropdown-arrow and .sync-time; dropdown constrained to viewport
- `src/main/resources/static/css/components/tables.css` (modify) — new .table-cards responsive pattern using td[data-label]
- `src/main/resources/static/js/features/environments.js` (modify) — buildListHtml/buildEnvRows (L298-450) and VM rows in buildGroupCard (L671-798): table-cards class, data-label on every td, td-actions on action cells
- `src/main/resources/static/css/features/environments.css` (modify) — scope fixed widths to min-width:768px
- `test/tests/mobile/phone-layout.spec.ts` (new) — no horizontal overflow, actions visible

**Steps**

1. topnav.css: `@media (max-width: 767.98px) { .top-navbar .logo, .top-navbar .user-info, .top-navbar .dropdown-arrow, .sync-indicator .sync-time { display:none; } }` (merge with the existing rule near L336).
2. topnav.css user dropdown below 768px: `position: fixed; right: 8px; left: auto; max-width: calc(100vw - 16px);` with top equal to the navbar height.
3. tables.css `.table-cards` below 768px: hide thead; `tr { display:block; border:1px solid var(--border-color); border-radius:8px; margin-bottom:.75rem; padding:.5rem; }`; `td { display:flex; justify-content:space-between; gap:.5rem; border:0; white-space:normal; }`; `td::before { content: attr(data-label); font-weight:600; color: var(--text-secondary); }`; `td.td-actions::before { content:none; }`; `td.td-actions { justify-content:flex-end; flex-wrap:wrap; }`; make `.table-responsive` around a .table-cards table `overflow: visible`.
4. environments.js: add `table-cards`, `data-label` (escaped header text) and `td-actions` in the list and VM tables.
5. environments.css: wrap fixed column widths/min-widths in `@media (min-width: 768px)`.

**Acceptance criteria**

- Given a 390px viewport, then document.documentElement.scrollWidth <= 390 on Dashboard, Environments and Environment Detail.
- Given the Environments list at 390px, then each environment is a card with labelled fields and readable lock status.
- Given Environment Detail at 390px, then every VM's Start and Stop buttons are fully visible and tappable.
- Given the user menu opens at 390px, then the dropdown lies fully within the viewport.
- Given a desktop viewport, then the tables look unchanged.

**Tests**

- test/tests/mobile/phone-layout.spec.ts (NEW, 390x844): assert scrollWidth <= 390 per page; boundingBox of each [data-action="start-vm"]/[data-action="stop-vm"] within the viewport; user dropdown boundingBox right edge <= 390.

#### E13-T06 — Apply the table-to-cards pattern to Access Management, Access Requests, User Management, logs and VM Registry

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** M30 · **Depends on:** E13-T05

The remaining data tables overlap and clip action columns on phones (M30).

**Files**

- `src/main/resources/static/js/features/access-management.js` (modify) — renderAccessTable/renderPendingRequestsTable/renderActivityLogsTable (L573-800): table-cards, data-label, td-actions
- `src/main/resources/static/js/features/access-requests.js` (modify) — environment table (renderEnvTable L302) and pending requests table
- `src/main/resources/static/js/features/user-management.js` (modify) — renderTable (L198-293)
- `src/main/resources/static/js/features/activity-logs.js` (modify) — log rows
- `src/main/resources/static/js/features/all-logs.js` (modify) — log rows
- `src/main/resources/static/js/features/vm-registry.js` (modify) — renderEnvironmentsList rows (L158-240)
- `src/main/resources/static/css/features/access-management.css` (modify) — scope fixed widths to desktop
- `src/main/resources/static/css/features/all-logs.css` (modify) — disable sticky header below 768px
- `test/tests/mobile/phone-layout.spec.ts` (modify) — add these pages

**Steps**

1. For each table listed add `table-cards` to the <table>, a `data-label` on every <td> equal to its header text, and `td-actions` on action cells.
2. Grep `min-width`/`width:` in css/features/access-management.css, user-management.css, all-logs.css, activity-logs.css, vm-registry.css and access-requests.css; wrap table/column widths in `@media (min-width: 768px)`.
3. Disable sticky thead rules below 768px (thead is hidden in card mode).

**Acceptance criteria**

- Given each listed page at 390px, then there is no horizontal page scroll and every row action button is fully visible.
- Given a desktop viewport, then the tables look unchanged.

**Tests**

- phone-layout.spec.ts (extend, admin creds): #/access-management, #/request-access, #/pending-requests, #/user-management, #/activity-logs, #/audit-logs-all, #/vm-registry.

#### E13-T07 — Wire the top-bar sync pill to real sync status and the operation banner to running operations

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-FE-sync-pill-banner · **Depends on:** E02-T05, E02-T07

The 'Just now' sync pill (index.html L66-70) and the operation progress banner (L666-685) are static markup with no code behind them (low-severity).

**Files**

- `src/main/resources/static/js/core/realtime.js` (modify) — global 'syncStatus' poll (60s) via pollGet updating #sync-indicator
- `src/main/resources/static/js/features/vm-operations.js` (modify) — show #operation-banner while an operation runs and the progress modal is hidden; Details re-opens the progress modal
- `src/main/resources/static/index.html` (modify) — sync pill hidden until first response; Details button type=button
- `test/tests/navigation/status-widgets.spec.ts` (new) — pill and banner

**Steps**

1. realtime.js init: `startPolling('syncStatus', updateSyncIndicator, 60000)` (global, visibility-aware); updateSyncIndicator uses `pollGet(Config.API.monitoring.syncStatus)` (GET /api/v1/monitoring/sync-status, StateSyncStatusDTO: lastSyncTime, inProgress, errorCount).
2. Render: `#sync-time` = `Utils.formatRelativeTime(lastSyncTime)` or 'Never'; toggle `.syncing` when inProgress, `.stale` when older than 15 minutes, `.error` when errorCount > 0 (classes exist in topnav.css L294-336); title = absolute time. On 403/404 hide the pill (E03 may restrict monitoring endpoints).
3. vm-operations.js: store the latest execution in the activeOperations entry on each poll. In the progress modal onHide (L208-212) and on each later poll, if `hasRunningOperation()` show #operation-banner with the title, `#operation-progress-bar` width and aria-valuenow from execution.progressPercentage, and `#operation-count` from completed/total steps; hide it when nothing runs.
4. Add `reopenProgress()` that calls `showProgressModal(title)` then `updateProgressModal('running', null, latestExecution)`; bind `#view-operation-details` to it and hide the banner while the modal is shown.

**Acceptance criteria**

- Given the last sync was 7 minutes ago, then the pill shows the relative time and no stale style.
- Given the last sync is older than 15 minutes, then the pill shows the stale style.
- Given the user closes the progress dialog while an operation runs, then the banner shows progress and Details re-opens the dialog; when the operation ends the banner disappears.
- Given the sync-status endpoint returns 403, then the pill is hidden and no error toast appears.

**Tests**

- test/tests/navigation/status-widgets.spec.ts (NEW): fulfillJson sync-status with lastSyncTime = now-7min and assert the text; now-20min asserts .stale; fulfillJson operations create/get with a running execution, close the modal, assert the banner, click Details, assert the modal.

**Notes:** If product prefers, deleting both elements is an acceptable alternative; either way no static fake status remains.

#### E13-T08 — Fix toast and modal plumbing: danger type, unique toast ids, escapeHtml(0), reuse Bootstrap modal instances

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** LOW-FE-danger-toast, LOW-FE-toast-id, LOW-FE-escapeHtml-0, LOW-FE-modal-instances · **Depends on:** E01-T01

A 'danger' toast renders `fas undefined` (system-health.js uses it); toast ids use Date.now() and collide; Utils.escapeHtml(0) returns ''; features call `new bootstrap.Modal` on every open, stacking instances and backdrops (low-severity).

**Files**

- `src/main/resources/static/js/ui/notifications.js` (modify) — type aliases, counter ids, delegated close, role=alert for errors
- `src/main/resources/static/js/core/utils.js` (modify) — escapeHtml treats only null/undefined as empty (if E01 did not already)
- `src/main/resources/static/js/ui/modals.js` (modify) — L131, L743 use bootstrap.Modal.getOrCreateInstance; dispose on hidden for removed modals
- `src/main/resources/static/js/features/access-management.js` (modify) — L977, L1074 getOrCreateInstance
- `src/main/resources/static/js/features/automation-rules.js` (modify) — L435 getOrCreateInstance
- `src/main/resources/static/js/features/vm-registry.js` (modify) — L359, L627, L747, L792 getOrCreateInstance
- `test/tests/navigation/toasts-modals.spec.ts` (new) — behaviour checks

**Steps**

1. notifications.js `show` (L36-56): `const ALIASES = { danger: 'error' }; type = ALIASES[type] || type; if (!ICONS[type]) type = 'info';`. Replace `'notif-' + Date.now()` with a module counter. Replace the inline onclick on .toast-close with one delegated click handler on #notification-container (bound in init). Use `role="alert"` for error and warning.
2. utils.js `escapeHtml` (L133-138): if it still starts with `if (!text) return ''`, change to `if (text == null) return ''; text = String(text);`.
3. Replace each listed `new bootstrap.Modal(el)` with `bootstrap.Modal.getOrCreateInstance(el)`; in modals.js, for modals removed from the DOM on `hidden.bs.modal`, call `instance.dispose()` before removal.

**Acceptance criteria**

- Given Notifications.show('x', 'danger'), then the toast shows the error icon and styling.
- Given two toasts created in the same millisecond, when one is dismissed, then the other stays.
- Given Utils.escapeHtml(0), then it returns '0'.
- Given the Register VM modal is opened and closed five times, then one backdrop exists while open and none after close.

**Tests**

- test/tests/navigation/toasts-modals.spec.ts (NEW): page.evaluate calls Notifications.show twice synchronously and dismisses one; asserts icons; evaluates Utils.escapeHtml(0); admin: open/close the VM Registry register modal 5 times and assert `.modal-backdrop` count.

#### E13-T09 — Give environment group headers and automation day pills/trigger cards keyboard semantics

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-FE-group-headers-keyboard, LOW-FE-automation-keyboard · **Depends on:** E02-T07, E13-T05

Group headers are role=button divs without tabindex or key handling, and automation day pills (spans) and trigger cards (divs) cannot be reached or toggled by keyboard (low-severity).

**Files**

- `src/main/resources/static/js/features/environments.js` (modify) — buildGroupCard header (L779-781) becomes <button type=button aria-expanded aria-controls>; toggle handler (L947-966) updates aria-expanded
- `src/main/resources/static/js/features/automation-rules.js` (modify) — day pills (L457-458, handler L596, read L667) as toggle buttons with aria-pressed; trigger cards (L488-494) as a radio group
- `src/main/resources/static/css/features/environments.css` (modify) — button reset and focus ring for .group-card-toggle
- `src/main/resources/static/css/features/automation-rules.css` (modify) — button reset and focus ring for .ar-day-pill and .ar-choice-card
- `test/tests/a11y/keyboard-controls.spec.ts` (new) — keyboard toggling

**Steps**

1. environments.js: render `<button type="button" class="group-card-toggle ..." data-collapse-target="${collapseId}" aria-expanded="${expanded}" aria-controls="${collapseId}">`; keep group action buttons outside the toggle button (move to a sibling if nested). The handler sets aria-expanded on all toggles after collapsing/expanding.
2. automation-rules.js day pills: `<button type="button" class="ar-day-pill" aria-pressed="true|false" data-day=...>` inside `<div role="group" aria-label="Days of week">`; the click handler toggles `on` and aria-pressed (save logic at L667 unchanged).
3. Trigger cards: container `role="radiogroup" aria-label="Trigger type"`; each card `<button type="button" role="radio" aria-checked=... tabindex="0|-1">`; ArrowLeft/ArrowRight move and select (roving tabindex); reuse the existing selection logic.
4. CSS resets so visuals are unchanged, plus :focus-visible outlines.

**Acceptance criteria**

- Given Environment Detail, when a keyboard user tabs to a group header and presses Enter or Space, then the group expands and aria-expanded becomes true.
- Given the automation rule modal, when the user tabs to Monday and presses Space, then it toggles, aria-pressed reflects it, and the saved rule includes MONDAY.
- Given focus in the trigger type group, when ArrowRight is pressed, then the next trigger type is selected and its fields show.

**Tests**

- test/tests/a11y/keyboard-controls.spec.ts (NEW): focus the first .group-card-toggle, press Enter, assert aria-expanded=true; admin: open a new automation rule, focus a day pill, press Space, assert aria-pressed; ArrowRight in the radiogroup changes aria-checked.

#### E13-T10 — Label icon-only buttons, raise touch targets to 44px, and keep the caret in My Account's reason box across re-renders

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-FE-icon-aria-label, LOW-FE-touch-targets · **Depends on:** E13-T03, E13-T04, E13-T06

Icon-only buttons (VM Registry inline edit/delete, Environment Detail and admin row actions, bell mark-read) rely on title only; text links, small buttons and the 36px My Account filter pills are under 44px; a full My Account re-render on each data load moves the caret in the reason box (low-severity).

**Files**

- `src/main/resources/static/js/features/vm-registry.js` (modify) — aria-label and type=button on icon-only action buttons (e.g. L216, L538, L544)
- `src/main/resources/static/js/features/environments.js` (modify) — aria-label on icon-only list/VM action buttons
- `src/main/resources/static/js/features/access-management.js` (modify) — aria-label on icon-only row actions
- `src/main/resources/static/js/features/user-management.js` (modify) — aria-label on icon-only row actions
- `src/main/resources/static/js/ui/notification-bell.js` (modify) — .notification-read-btn aria-label="Mark as read" type=button
- `src/main/resources/static/js/features/my-account.js` (modify) — render() (L164-181) restores selectionStart/End of the focused field
- `src/main/resources/static/css/features/my-account.css` (modify) — .ma-filter min-height 44px (L627-628); .ma-link hit area (L96-110)
- `src/main/resources/static/css/layout/mobile.css` (modify) — touch-target block (L173-240) adds .btn-link, .ma-link, .notification-read-btn, .toast-close, .close-btn
- `test/tests/a11y/names-and-targets.spec.ts` (new) — axe button-name and target-size checks

**Steps**

1. Grep features/*.js and ui/*.js for `<button` whose content is only an `<i class="fas ...">`; add `aria-label` equal to its title (escaped), `type="button"`, and `aria-hidden="true"` on the icon.
2. mobile.css touch-target block: add the listed selectors with `min-height:44px; min-width:44px;` (inline-flex alignment for links).
3. my-account.css: `.ma-filter` min-height 44px on all widths.
4. my-account.js `render()`: when the focused element is an input/textarea, capture selectionStart/selectionEnd before re-rendering and call setSelectionRange after re-focusing.

**Acceptance criteria**

- Given axe 'button-name' runs on Environment Detail, VM Registry, Access Management, User Management and the open bell dropdown, then no violations are reported.
- Given a 390px viewport, then every button and link in My Account and the bell is at least 44x44 CSS px.
- Given the user is typing in the extension reason while My Account data reloads, then the caret stays where it was.

**Tests**

- test/tests/a11y/names-and-targets.spec.ts (NEW): AxeBuilder.withRules(['button-name','link-name']) per page; at 390px getBoundingClientRect of `.ma-filter, .ma-link, .notification-read-btn` >= 44; type in the reason box, call MyAccount render via a data reload, assert selectionStart unchanged.

## E14

### Platform hygiene & docs — Fix

The build ships what is tested, migrations are never silently repaired, dead code, tables and junk files are gone, tests validate the schema, and the docs describe the system as it is.

Sources: M23, M24, LOW-hygiene · 8 tasks · 5.5 days

#### E14-T01 — Stop running Flyway repair on every startup

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** medium · **Sources:** M23 · **Depends on:** E00-T01

config/FlywayConfig.java registers a FlywayMigrationStrategy that calls repair() then migrate() on every boot in every profile, so an edited, already-applied migration is accepted silently and environments drift (M23).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/config/FlywayConfig.java` (delete) — remove the repair-then-migrate strategy (Spring Boot default migrate applies)
- `src/main/resources/application.properties` (modify) — spring.flyway.validate-on-migrate=true explicitly
- `repair_flyway.sql` (delete) — tracked one-off repair script at repo root
- `docs/runbook-flyway.md` (new) — how to run a manual repair (flyway CLI or a one-off Gradle task) after an approved checksum change
- `src/test/java/com/tcgdigital/vmcontrol/FlywayMigrationTest.java` (modify) — assert no FlywayMigrationStrategy bean and that a checksum mismatch fails startup

**Steps**

1. Before merging, on every deployed environment run SELECT version, checksum, success FROM flyway_schema_history and compare with a fresh `flyway info` of this build; if any checksum differs, fix it with a deliberate manual repair first (record in the PR).
2. Delete FlywayConfig.java (its only bean is repairThenMigrate). Spring Boot's FlywayAutoConfiguration then runs migrate() with validation.
3. Set spring.flyway.validate-on-migrate=true in application.properties; keep clean-disabled=true in prod.
4. Delete repair_flyway.sql from the repo root; write docs/runbook-flyway.md with the manual repair procedure.
5. FlywayMigrationTest: add a test that the context has no FlywayMigrationStrategy bean.

**Acceptance criteria**

- Given an applied migration whose file content changed, when the app starts, then startup fails with a Flyway validation error instead of rewriting the checksum.
- Given a clean database, when the app starts, then all migrations apply as before.
- Given the code base, when searching for '.repair(', then nothing is found under src/main.

**Tests**

- FlywayMigrationTest: no FlywayMigrationStrategy bean; migrations apply on Testcontainers MySQL.

**Notes:** Coordinate the rollout with whoever deploys; a hidden checksum drift would now block startup, which is the intended behaviour.

#### E14-T02 — Wire the frontend bundle and patched index.html into processResources so the JAR serves dist/

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** medium · **Sources:** M24 · **Depends on:** E00-T02

build.gradle defines buildFrontend and patchIndexHtml but nothing depends on them; deploy depends only on bootJar and static/dist is gitignored, so production serves ~35 JS and ~33 CSS files one by one and build-frontend.ps1 changes nothing that ships (M24).

**Files**

- `build.gradle` (modify) — buildFrontend writes to build/generated/frontend/static/dist; processResources depends on patchIndexHtml and copies dist + patched index.html over static/; -PskipBundle for local debugging
- `build-frontend.ps1` (modify) — becomes a thin wrapper around ./gradlew buildFrontend patchIndexHtml (or delete it and update docs)
- `.github/workflows/<ci file from E00-T02>` (modify) — after build, assert the JAR contains static/dist/app.js and static/index.html references dist/app.js

**Steps**

1. In buildFrontend change distDir to layout.buildDirectory.dir('generated/frontend/static/dist') (stop writing into src/main/resources/static/dist). Keep reading the order from src index.html.
2. patchIndexHtml already writes build/generated/patched-static/index.html; keep it.
3. processResources: dependsOn patchIndexHtml (which depends on buildFrontend); add from(layout.buildDirectory.dir('generated/frontend')) { into '.' } and from(layout.buildDirectory.dir('generated/patched-static')) { into 'static' }; set duplicatesStrategy = DuplicatesStrategy.INCLUDE with the generated sources declared last so the patched index.html wins. Wrap these in if (!project.hasProperty('skipBundle')).
4. Exclude static/dist/** (stale local output) from the src copy.
5. Replace the body of build-frontend.ps1 with a call to .\gradlew.bat buildFrontend patchIndexHtml, or delete it; keep CLAUDE.md consistent (E14-T08).
6. CI: after ./gradlew build run `jar tf build/libs/*.jar | grep static/dist/app.js` and `unzip -p build/libs/*-SNAPSHOT.jar BOOT-INF/classes/static/index.html | grep dist/app.js`.
7. Smoke-test the built JAR locally: open the app, check the network tab shows dist/app.js and dist/app.css only, and click through Dashboard, Environments, Logs.

**Acceptance criteria**

- Given ./gradlew bootJar, when the JAR is inspected, then BOOT-INF/classes/static/dist/app.js and app.css exist and static/index.html loads dist/app.js and dist/app.css instead of individual files.
- Given ./gradlew bootRun -PskipBundle, then index.html loads individual source files.
- Given a CSS or JS edit, when ./gradlew bootJar runs, then the bundle is rebuilt (task not UP-TO-DATE).
- Given src/main/resources/static/dist exists locally, then it is not packaged.

**Tests**

- CI step asserting JAR contents; manual smoke test of the bundled JAR noted in the PR.

**Notes:** Script order comes from index.html, so later tasks that add scripts need no build change. Cache-busting query strings are stripped by the existing regex; consider adding a version query to dist references.

#### E14-T03 — Delete dead frontend assets: TemplateLoader and templates/, home.html, home-original.html, old_backup/ and the My Workspace mock panels

- [ ] Done · **Type:** cleanup · **Estimate:** 0.5 d · **Risk:** low · **Sources:** LOW-hygiene-dead-frontend · **Depends on:** E14-T02

js/core/template-loader.js and static/templates/** are only referenced by each other; home.html and home-original.html are mockups loading CDN scripts without integrity hashes; old_backup/ is gitignored but still packaged from src/main/resources; index.html keeps a hidden 'MY WORKSPACE' section with hard-coded counts and the runningPanel/lockedPanel mock slide-outs (hygiene low items).

**Files**

- `src/main/resources/static/js/core/template-loader.js` (delete) — unused TemplateLoader
- `src/main/resources/static/templates/` (delete) — common.html, features/*.html, partials/*.html
- `src/main/resources/static/home.html` (delete) — mockup
- `src/main/resources/static/home-original.html` (delete) — mockup
- `src/main/resources/static/old_backup/` (delete) — local ignored backups (delete locally; also excluded from processResources)
- `src/main/resources/static/index.html` (modify) — remove template-loader.js script tag (line ~701), #workspace-section (lines ~108-~180), #runningPanel and #lockedPanel slide-outs (lines ~295-~380) if still present
- `src/main/resources/static/js/ui/sidebar.js` (modify) — remove handlePanelOpen and the [data-panel] binding (lines ~39-56)
- `build.gradle` (modify) — processResources exclude 'static/old_backup/**'

**Steps**

1. grep -rn "TemplateLoader\|template-loader\|templates/" src/main/resources/static --include=*.js --include=*.html (excluding the files being deleted) must return nothing; then delete template-loader.js, its script tag and static/templates/.
2. grep for links to home.html/home-original.html (HomeController, login.html, nginx default.conf); there should be none; delete both files.
3. Delete static/old_backup locally and add processResources { exclude 'static/old_backup/**' } so a stray local copy never ships.
4. If E13 has not already removed them: delete the #workspace-section block in index.html, the #runningPanel and #lockedPanel slide-outs, and sidebar.js handlePanelOpen plus its binding. Keep the shared #slideoutOverlay if other panels use it (grep Slideout.open).
5. Rebuild with ./gradlew bootJar (bundle derives order from index.html) and click through every sidebar route.

**Acceptance criteria**

- Given the built JAR, then it contains no static/templates/, home.html, home-original.html or old_backup entries.
- Given index.html, then it has no element with id workspace-section, runningPanel or lockedPanel.
- Given every route in router.js, when opened, then no console error references TemplateLoader or Slideout panels.

**Tests**

- CI JAR-content assertion from E14-T02 extended with the absent paths; manual route walk.

#### E14-T04 — Remove dead backend code: H2MysqlCompatibilityPostProcessor, the Azure BOM and unused repository methods

- [ ] Done · **Type:** cleanup · **Estimate:** 0.5 d · **Risk:** low · **Sources:** LOW-hygiene-dead-backend · **Depends on:** E14-T03

H2MysqlCompatibilityPostProcessor is an empty no-op still registered in META-INF; build.gradle imports the spring-cloud-azure-dependencies BOM although the Azure starter is commented out; about two dozen repository methods have no callers (hygiene low items).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/config/H2MysqlCompatibilityPostProcessor.java` (delete) — no-op EnvironmentPostProcessor
- `src/main/resources/META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor` (delete) — its only line registers the class above
- `build.gradle` (modify) — remove ext springCloudAzureVersion, the dependencyManagement mavenBom for spring-cloud-azure-dependencies, and the commented Azure/redis lines
- `src/main/java/com/tcgdigital/vmcontrol/repository/*.java` (modify) — remove methods with no callers (list in steps)

**Steps**

1. Delete H2MysqlCompatibilityPostProcessor.java and the META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor file (check it has no other entries first).
2. build.gradle: delete set('springCloudAzureVersion', ...), the dependencyManagement { imports { mavenBom "com.azure.spring:..." } } block and the commented-out Azure/redis dependency lines. Run ./gradlew dependencies --configuration runtimeClasspath before and after; diff must show no version change for kept libraries (if one changes, pin it explicitly).
3. Re-run the unused-method scan at implementation time (for each repository method, grep -rnw '<name>' src/main/java src/test/java excluding its own file). Candidates found during planning: EnvironmentAccessRepository.countActiveAccessByEnvironment, findActiveByScopeTypeAndScopeId, findExpiredAccess; EnvironmentAccessRequestRepository.hasPendingRequest; EnvironmentLockRepository.findByEnvironmentIdWithLock; LockHistoryRepository.findByEnvironmentIdOrderByPerformedAtDesc, findByLockLockIdOrderByPerformedAtDesc; OperationDetailRepository.countByExecutionExecutionIdAndStatus, findByTargetIdOrderBySequencePositionAsc, findMaxSequencePosition; OperationExecutionRepository.findActiveExecutions, findActiveExecutionsByEnvironmentId, findByEnvironmentEnvironmentIdOrderByStartedAtDesc; UserRepository.existsByAzureAdObjectId, existsByEmail, existsByLegacyUserId, findByLegacyUserId; VmMetricDailyRepository.findByVmVmIdAndBucketDate; VmMetricHourlyRepository.findByVmVmIdAndBucketStart; VmRepository.findAllByEnvironmentId, findAllByGroupGroupIdOrderBySequencePositionAsc, findInactiveVms; VmStateHistoryRepository.findAllByOrderByChangedAtDesc, findLastStateChangeByVmId. Remove only those still unused after E00-E13 (several may have gained callers).
4. ./gradlew build must pass; run graphify update .

**Acceptance criteria**

- Given the repository, then no class named H2MysqlCompatibilityPostProcessor and no Azure BOM import remain.
- Given each removed repository method, then grep finds no reference in src/.
- Given ./gradlew build, then compilation and all tests pass and the runtime classpath versions are unchanged.

**Tests**

- Existing suite (VmcontrolApplicationTests context load) proves nothing depended on the removed code.

#### E14-T05 — Drop unused V1 tables, V4 temp tables and redundant indexes in a new migration

- [ ] Done · **Type:** cleanup · **Estimate:** 1 d · **Risk:** medium · **Sources:** LOW-hygiene-schema · **Depends on:** E14-T01, E04-T01

Six V1 tables have no entity or caller (vm_provider_details, login_session, system_config, cloud_provider_credential, scheduled_job, job_execution_log); V4 creates temp_vm_master and temp_instance_parsed and never drops them; vm_provider_details holds swapped region columns (region_code='unknown', region_name='ap-south-1'); several indexes duplicate a UNIQUE key or a leftmost prefix of another index (hygiene low items).

**Files**

- `src/main/resources/db/migration/V<next>__drop_unused_tables_and_indexes.sql` (new) — DROP TABLE IF EXISTS for 8 tables; DROP INDEX for redundant indexes
- `src/test/resources/db/reset-test-data.sql` (modify) — remove DELETEs for dropped tables (lines 3-7)
- `src/test/java/com/tcgdigital/vmcontrol/FlywayMigrationTest.java` (modify) — remove system_config/scheduled_job seed assertions (lines ~105-119); assert the tables are gone

**Steps**

1. On a prod copy, confirm the tables are unused: SELECT COUNT(*) per table and check MySQL performance_schema/table_io_waits for reads since last restart; record in the PR. Take a mysqldump of these tables as a rollback artefact.
2. Migration (next free V number): DROP TABLE IF EXISTS job_execution_log; DROP TABLE IF EXISTS scheduled_job; DROP TABLE IF EXISTS login_session; DROP TABLE IF EXISTS cloud_provider_credential; DROP TABLE IF EXISTS system_config; DROP TABLE IF EXISTS vm_provider_details; DROP TABLE IF EXISTS temp_instance_parsed; DROP TABLE IF EXISTS temp_vm_master;  (dropping vm_provider_details removes the swapped region columns; vm.region already holds the region).
3. Keep user_notification_preference: E29-T05 maps it for per-user notification preferences.
4. Redundant indexes (verify each with SHOW INDEX on a prod copy and drop only those still present and redundant): idx_user_email and idx_user_azure_ad_object_id (duplicate the UNIQUE columns of app_user), idx_environment_name (duplicates UNIQUE name), idx_group_env (prefix of idx_group_env_name), idx_notification_user (prefix of idx_notification_unread), idx_vm_volume_vm (prefix of uq_vm_volume), idx_vm_metric_vm_time (prefix of uq_vm_metric_sample), idx_vm_metric_archive_vm_time (prefix of uq_vm_metric_sample_archive), idx_automation_rule_env (prefix of idx_automation_rule_env_trigger_mode). Use DROP INDEX <name> ON <table>; do not touch environment_access indexes (E04-T01 owns them).
5. Update reset-test-data.sql and FlywayMigrationTest; run the full suite on Testcontainers MySQL.

**Acceptance criteria**

- Given the migration has run, then information_schema.tables has none of the 8 dropped tables and user_notification_preference still exists.
- Given the migration has run, then each listed redundant index is absent and every FK still has a supporting index (migration does not fail).
- Given the test suite, when run, then it passes on a fresh database.

**Tests**

- FlywayMigrationTest: asserts dropped tables are absent and key queries (vm by group, notifications by user) still use an index (EXPLAIN key not null).

**Notes:** Irreversible; keep the dump from step 1. MySQL refuses to drop an index an FK needs unless another index has the FK column leftmost, which each listed case satisfies.

#### E14-T06 — Clean repo hygiene: gitignore the graphify cache, remove junk and duplicate deployment properties, version the .ai docs

- [ ] Done · **Type:** cleanup · **Estimate:** 0.5 d · **Risk:** low · **Sources:** LOW-hygiene-repo · **Depends on:** E14-T01

315 graphify-out files are tracked (306 under cache/), junk files sit in the root (Z'AS'px, puttygen-*.tmp untracked; generate_v4_migration.py tracked), deployment/app/application-prod.properties duplicates deployment/config/application-prod.properties which docker-compose actually mounts, and .ai/ is gitignored so other clones never get the design docs (hygiene low items).

**Files**

- `.gitignore` (modify) — add graphify-out/cache/ and graphify-out/20*/; remove the **/.ai rule
- `graphify-out/cache/` (delete) — git rm -r --cached (files stay locally)
- `generate_v4_migration.py` (delete) — one-off generator for an applied migration
- `data/parse-vm-data.py` (delete) — one-off legacy data script (data/ is otherwise ignored); move to docs/legacy/ if the team wants to keep it
- `deployment/app/application-prod.properties` (delete) — duplicate; compose mounts ./config/application-prod.properties
- `deployment/.gitignore` (modify) — ignore app/*.properties
- `.ai/` (new) — start tracking the existing .ai docs (content refreshed in E14-T08)

**Steps**

1. .gitignore: add 'graphify-out/cache/' and 'graphify-out/20*/'; then git rm -r --cached graphify-out/cache. Keep graph.json, GRAPH_REPORT.md and manifest.json tracked (CLAUDE.md tooling reads them) unless the team prefers regenerating them.
2. Delete untracked junk locally (Z'AS'px, puttygen-error.tmp, puttygen-output.tmp) and add '*.tmp' to .gitignore.
3. git rm generate_v4_migration.py and data/parse-vm-data.py (confirm with the team; V4 is applied and must never change).
4. Compare deployment/app/application-prod.properties with deployment/config/application-prod.properties key by key (do not paste values into the PR); merge any key that exists only in app/ into config/, then git rm the app/ copy and ignore app/*.properties.
5. Remove '**/.ai' from .gitignore and git add .ai/ after checking it holds no secrets (grep for password, secret, key=).
6. Run graphify update . so the local graph reflects the deletions.

**Acceptance criteria**

- Given git ls-files graphify-out, then no path under graphify-out/cache is listed.
- Given the repo root, then Z'AS'px, puttygen-*.tmp, repair_flyway.sql and generate_v4_migration.py are gone.
- Given deployment/, then exactly one application-prod.properties remains and docker-compose still mounts it.
- Given a fresh clone, then .ai/ docs are present.

**Tests**

- None (no code change); CI build stays green.

#### E14-T07 — Run tests with ddl-auto=validate so entity/schema drift fails the build

- [ ] Done · **Type:** test · **Estimate:** 0.5 d · **Risk:** medium · **Sources:** LOW-hygiene-tests-validate · **Depends on:** E14-T05

src/test/resources/application.properties and application-test.properties set spring.jpa.hibernate.ddl-auto=none, so an entity that does not match the migrated MySQL schema only fails in production, where ddl-auto=validate (hygiene low item).

**Files**

- `src/test/resources/application.properties` (modify) — spring.jpa.hibernate.ddl-auto=validate
- `src/test/resources/application-test.properties` (modify) — spring.jpa.hibernate.ddl-auto=validate; replace deprecated MySQL8Dialect with org.hibernate.dialect.MySQLDialect or drop the line
- `src/main/java/com/tcgdigital/vmcontrol/model/*.java` (modify) — fix any column type/length mismatches validate reports (only if found)

**Steps**

1. Set ddl-auto=validate in both test property files (and in any @TestPropertySource or AbstractIntegrationTest property from E00-T01).
2. Run ./gradlew test; for each SchemaManagementException fix the entity mapping (columnDefinition/length/type) to match the migration, or add a migration when the schema is wrong. Never change an existing migration.
3. Remove spring.jpa.database-platform=org.hibernate.dialect.MySQL8Dialect from application-test.properties (Hibernate 6 auto-detects; the class is deprecated).

**Acceptance criteria**

- Given an entity column renamed without a migration, when tests run, then the context fails to start with a schema validation error.
- Given the current code base, when ./gradlew test runs, then all tests pass with validate.

**Tests**

- Whole suite under validate; add one negative check in FlywayMigrationTest's notes (manual) rather than a deliberately broken entity.

#### E14-T08 — Rewrite CLAUDE.md, AGENTS.md, copilot instructions and .ai docs to match the real system

- [ ] Done · **Type:** docs · **Estimate:** 1 d · **Risk:** low · **Sources:** LOW-hygiene-docs · **Depends on:** E14-T02, E14-T06, E14-T07

CLAUDE.md and the .ai/ docs describe H2 dev and test databases, migrations V1-V5, 13 controllers, 22 entities, 31 DTOs and GCP/Azure/OCI stub providers; the code has MySQL only, Testcontainers tests, migrations past V23, 19 controllers, 44 model classes, 61 DTOs and only AWS EC2 and EKS providers (hygiene low item).

**Files**

- `CLAUDE.md` (modify) — MySQL + Testcontainers, current migration range, real controller/provider list, bundling via Gradle, schedulers and feature flags
- `AGENTS.md` (modify) — same corrections
- `.github/copilot-instructions.md` (modify) — same corrections
- `.ai/brain/architecture.md` (modify) — remove H2; describe AuditWriter, retention, router lifecycle
- `.ai/engineering/database.md` (modify) — MySQL only, Flyway without auto-repair, ddl validate
- `.ai/engineering/testing.md` (modify) — AbstractIntegrationTest, SecuredWebTestBase, Testcontainers, Docker required
- `.ai/constraints/tech-decisions.md` (modify) — drop H2 rationale
- `README.md` (modify) — build/run instructions consistent with the above

**Steps**

1. CLAUDE.md: replace the Database section (dev/test use MySQL via .env or Testcontainers; no H2 console), say 'migrations V1..V<current>; add the next free number', list the 19 controllers and the real providers (AwsCloudProviderService, EksCloudProviderService behind CloudProviderFactory; CloudProvider enum values without implementations are unsupported), replace the build-frontend.ps1 instruction with the Gradle bundling from E14-T02, mention Docker is required for tests.
2. Mirror the same facts in AGENTS.md and .github/copilot-instructions.md.
3. Grep .ai/ for H2, h2-console, V5, GCP, OCI, Azure provider claims and fix each file listed; keep design intent text.
4. Document the feature flags introduced in the Fix phase (retention.*, automation, idle, email) in one table in CLAUDE.md.
5. Run graphify update . so docs nodes refresh.

**Acceptance criteria**

- Given grep -ri 'h2' CLAUDE.md AGENTS.md .ai/, then no statement says the app or tests use H2.
- Given CLAUDE.md, then the controller count, migration range and provider list match the source tree on the merge date.
- Given a new developer follows README/CLAUDE.md, then ./gradlew test and ./gradlew bootRun work with Docker and a .env.

**Tests**

- Docs only; a reviewer follows the README on a clean machine.

## E15

### Environment leases — Now

Every opted-in running environment has an end time; the people using it are warned at 60/15/5 minutes and can extend (with owner approval above the maximum); when it ends the environment stops in reverse dependency order, attributed to 'Lease expired'.

Sources: G4 · 7 tasks · 11.5 days

#### E15-T01 — Add the lease data model, platform limits, per-environment policy and the master feature flag

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G4 · **Depends on:** E03-T01, E00-T02, E07-T05

G4 / spec-1 scope: default and maximum lease duration per environment set by the owner within platform limits; leases are opt-in (production opt-in only) and the whole feature ships off.

**Files**

- `src/main/resources/db/migration/V<next>__add_environment_leases.sql` (new) — tables environment_lease, environment_lease_participant; lease policy columns on environment
- `src/main/java/com/tcgdigital/vmcontrol/model/EnvironmentLease.java` (new) — entity with @Version
- `src/main/java/com/tcgdigital/vmcontrol/model/LeaseStatus.java` (new) — ACTIVE, ENDED, EXPIRED
- `src/main/java/com/tcgdigital/vmcontrol/model/LeaseSource.java` (new) — START, LOCK
- `src/main/java/com/tcgdigital/vmcontrol/model/EnvironmentLeaseParticipant.java` (new) — entity (lease_id, user_id, joined_at, left_at, left_reason)
- `src/main/java/com/tcgdigital/vmcontrol/model/Environment.java` (modify) — leaseEnabled, leaseDefaultMinutes, leaseMaxMinutes
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentLeaseRepository.java` (new) — findActiveByEnvironmentId, findOverdueIds(now), findActiveEndingBetween(start, end)
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentLeaseParticipantRepository.java` (new) — active participants per lease / per user
- `src/main/java/com/tcgdigital/vmcontrol/service/LeaseService.java` (new) — isEnabledFor(env), getActiveLease, getPolicy, updatePolicy, effectiveDefault/effectiveMax
- `src/main/java/com/tcgdigital/vmcontrol/dto/LeaseDTO.java` (new) — leaseId, environmentId, status, source, startedBy, startedAt, endsAt, participants, pendingExtension, permissions
- `src/main/java/com/tcgdigital/vmcontrol/dto/LeasePolicyDTO.java` (new) — enabled, defaultMinutes, maxMinutes, platform limits (read-only)
- `src/main/java/com/tcgdigital/vmcontrol/controller/LeaseController.java` (new) — GET /api/v1/environments/{id}/lease, GET/PUT /api/v1/environments/{id}/lease-policy
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — Boolean leaseEnabled (null when feature off)
- `src/main/resources/application.properties` (modify) — lease.* properties
- `src/test/java/com/tcgdigital/vmcontrol/service/LeaseServiceTest.java` (new) — policy validation
- `src/test/java/com/tcgdigital/vmcontrol/controller/LeaseControllerIntegrationTest.java` (new) — authorization + flag

**Steps**

1. Migration (next free V number at implementation time): CREATE TABLE environment_lease (lease_id VARCHAR(36) PK, environment_id VARCHAR(36) NOT NULL FK environment ON DELETE CASCADE, status VARCHAR(20) NOT NULL, source VARCHAR(20) NOT NULL, lock_id VARCHAR(36) NULL FK environment_lock, started_by_user_id VARCHAR(36) NOT NULL FK app_user, started_at TIMESTAMP NOT NULL, ends_at TIMESTAMP NOT NULL, ended_at TIMESTAMP NULL, end_reason VARCHAR(255) NULL, extended_minutes_total INT NOT NULL DEFAULT 0, stop_execution_id VARCHAR(36) NULL, stop_attempts INT NOT NULL DEFAULT 0, version BIGINT NOT NULL DEFAULT 0, active_environment_id VARCHAR(36) GENERATED ALWAYS AS (CASE WHEN status = 'ACTIVE' THEN environment_id END) STORED); CREATE UNIQUE INDEX ux_environment_lease_one_active ON environment_lease(active_environment_id); CREATE INDEX ix_environment_lease_status_ends ON environment_lease(status, ends_at).
2. Same migration: CREATE TABLE environment_lease_participant (lease_id VARCHAR(36) NOT NULL FK environment_lease ON DELETE CASCADE, user_id VARCHAR(36) NOT NULL FK app_user, joined_at TIMESTAMP NOT NULL, left_at TIMESTAMP NULL, left_reason VARCHAR(100) NULL, PRIMARY KEY (lease_id, user_id)); ALTER TABLE environment ADD COLUMN lease_enabled BOOLEAN NOT NULL DEFAULT FALSE, ADD COLUMN lease_default_minutes INT NULL, ADD COLUMN lease_max_minutes INT NULL.
3. Entities/enums/repositories as listed; map active_environment_id read-only (insertable=false, updatable=false) as in E07-T01.
4. application.properties: lease.enabled=${LEASE_ENABLED:false}; lease.platform.default-minutes=${LEASE_PLATFORM_DEFAULT_MINUTES:240}; lease.platform.max-minutes=${LEASE_PLATFORM_MAX_MINUTES:720}; lease.platform.absolute-max-minutes=${LEASE_PLATFORM_ABSOLUTE_MAX_MINUTES:4320} (ceiling even for approved extensions); lease.warning-minutes=${LEASE_WARNING_MINUTES:60,15,5}; lease.lock-release-grace-minutes=${LEASE_LOCK_RELEASE_GRACE_MINUTES:30}; lease.participant-end-grace-minutes=${LEASE_PARTICIPANT_END_GRACE_MINUTES:15}; lease.sweep.interval-ms=${LEASE_SWEEP_INTERVAL_MS:60000}.
5. LeaseService: isEnabledFor(Environment env) = lease.enabled && env.leaseEnabled; effectiveDefault(env) = env.leaseDefaultMinutes ?? platform default; effectiveMax(env) = min(env.leaseMaxMinutes ?? platform max, platform max). updatePolicy validates 15 <= default <= max <= platform max and writes an ENVIRONMENT_UPDATED audit row with old/new values.
6. LeaseController: GET lease -> LeaseDTO or 204, guarded by securityService.assertCanView(envId) (E03-T01). GET lease-policy for viewers; PUT lease-policy only for ADMIN or an env admin holding ADMIN on the environment (securityService.canManageEnvironmentAccess(envId)). When lease.enabled=false every lease endpoint throws ResourceNotFoundException('Leases', 'feature disabled') -> 404 (same pattern as EnvironmentAccessService.assertGroupScopeEnabled).
7. EnvironmentDTO: add Boolean leaseEnabled, filled by EnvironmentController GET /{environmentId} only when lease.enabled.

**Acceptance criteria**

- Given lease.enabled=false, when any /lease or /lease-policy endpoint is called, then 404 and no lease scheduler bean exists.
- Given lease.enabled=true and an env admin of DEV, when they PUT {enabled:true, defaultMinutes:240, maxMinutes:720}, then it is saved and audited; with maxMinutes 900 (above platform 720) then 400.
- Given a USER on DEV, when they PUT the policy, then 403; when they GET it, then 200.
- Given the migration has run, then inserting a second ACTIVE lease for the same environment fails on the unique index.

**Tests**

- LeaseServiceTest: effective default/max resolution and validation bounds.
- LeaseControllerIntegrationTest (SecuredWebTestBase): flag off -> 404; role matrix for GET/PUT policy.
- FlywayMigrationTest: tables, generated column and unique index exist.

**Notes:** Production environments are unaffected: leases are opt-in per environment (lease_enabled default FALSE) on top of the global flag. There is no production marker on Environment today, so opt-in is the safeguard.

#### E15-T02 — Open or join a lease when an environment is started

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G4 · **Depends on:** E15-T01, E05-T03, E00-T03

G4 / spec-1: 'Lease on start' - a START on a lease-enabled environment gives it an end time from the owner's default; the starter becomes a participant.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/LeaseService.java` (modify) — validateRequestedMinutes, openOrJoinOnStart, endOnFullStop
- `src/main/java/com/tcgdigital/vmcontrol/dto/StartOperationDTO.java` (modify) — optional @Min(15) @Max(4320) Integer leaseMinutes
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — startOperation (line 100) validates and registers lease hooks via AfterCommit
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — LEASE_STARTED, LEASE_EXTENDED, LEASE_ENDED, LEASE_EXPIRED, LEASE_EXTENSION_REQUESTED, LEASE_EXTENSION_DECIDED
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — logLeaseStarted/Extended/Ended helpers
- `src/test/java/com/tcgdigital/vmcontrol/service/LeaseServiceTest.java` (modify) — open/join cases
- `src/test/java/com/tcgdigital/vmcontrol/service/VmOperationsServiceTest.java` (modify) — hook registration

**Steps**

1. LeaseService.validateRequestedMinutes(Environment env, Integer minutes): no-op when disabled; ValidationException when minutes > effectiveMax(env).
2. LeaseService.openOrJoinOnStart(String envId, String userId, Integer requestedMinutes): return if !isEnabledFor(env); minutes = requestedMinutes ?? effectiveDefault. No ACTIVE lease: insert (source START, endsAt = now + minutes) in a REQUIRES_NEW helper and on DataIntegrityViolationException (concurrent start hit the unique index) fall through to the join path. ACTIVE lease exists: upsert the user as participant (clear left_at) and if now + minutes > endsAt move endsAt there, capped at startedAt + effectiveMax. Audit LEASE_STARTED or LEASE_EXTENDED.
3. LeaseService.endOnFullStop(envId, userId): a user STOP of the whole environment (no vmIds/groupIds) sets the active lease ENDED, ended_at=now, end_reason 'Stopped by <name>', audit LEASE_ENDED. Partial stops do not end the lease.
4. VmOperationsService.startOperation: before creating the execution, for START/RESTART call leaseService.validateRequestedMinutes(environment, dto.getLeaseMinutes()); after the execution is saved register AfterCommit.run(() -> leaseService.openOrJoinOnStart(...)) for START/RESTART, or endOnFullStop for a whole-environment STOP. Automation-rule starts (AutomationRuleService.fireOperation) open leases too, attributed to the acting user - intended.
5. Wrap hook calls in try/catch with log.warn so a lease error never affects the operation.

**Acceptance criteria**

- Given DEV has leases enabled with default 240 minutes, when Alice starts DEV, then an ACTIVE lease ending in 4 hours exists with Alice as participant and a LEASE_STARTED audit row.
- Given an active lease ending in 1 hour, when Bob starts a group with leaseMinutes=120, then Bob joins and endsAt moves to now+2h (never beyond the maximum).
- Given leaseMinutes above the maximum, when Alice starts, then 400 and no operation is created.
- Given leases are disabled for DEV, when Alice starts it, then no lease is created.
- Given Alice stops the whole environment, then the lease becomes ENDED.

**Tests**

- LeaseServiceTest: open, join, extend-on-join capped at max, concurrent open resolved via the unique index (two threads, AbstractIntegrationTest).
- VmOperationsServiceTest: START registers the lease hook after commit; validation failure prevents execution creation; whole-env STOP ends the lease, partial STOP does not.

#### E15-T03 — Derive leases from lock duration and end a user's part when their lock is released or their access ends

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G4 · **Depends on:** E15-T02, E07-T04

G4 / spec-1: lock duration becomes the lease; releasing the lock ends the lease after a grace period; access expiry ends that user's part of the lease.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/LeaseService.java` (modify) — onLockAcquired, onLockExtended, onLockEnded, onUserAccessEnded
- `src/main/java/com/tcgdigital/vmcontrol/service/LockService.java` (modify) — call LeaseService hooks via AfterCommit from acquire, extend, release, break, expire, auto-release
- `src/main/java/com/tcgdigital/vmcontrol/service/AccessExpiryProcessor.java` (modify) — call leaseService.onUserAccessEnded after expiring a grant
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — revokeGrantById/revokeAccess call leaseService.onUserAccessEnded
- `src/test/java/com/tcgdigital/vmcontrol/service/LeaseLockIntegrationTest.java` (new) — lock-lease interplay

**Steps**

1. onLockAcquired(lock): if enabled and lock.getExpiresAt() != null (E07-T02): no active lease -> create one with source LOCK, lock_id, endsAt = lock.expiresAt capped at now + effectiveMax; active lease -> set lock_id and endsAt = max(endsAt, lock.expiresAt) capped at startedAt + effectiveMax. The holder becomes a participant. A lock 'until manually released' does not create a lease.
2. onLockExtended(lock) (from LockService.extend, E07-T03): same max-and-cap rule.
3. onLockEnded(lock, reason) (release, break, expiry, auto-release from E07-T04): if the active lease has this lock_id, clear lock_id and set endsAt = min(endsAt, now + lease.lock-release-grace-minutes); mark the holder's participant row left_at=now, left_reason=reason.
4. onUserAccessEnded(envId, userId, reason): if securityService.canPerformOperations(envId, userId) is now false, mark the participant left; when no participant remains active set endsAt = min(endsAt, now + lease.participant-end-grace-minutes).
5. Inject LeaseService into LockService/EnvironmentAccessService with @Lazy if a bean cycle appears; every call goes through AfterCommit.run and catches its own exceptions so lease changes never roll back a lock or access change.
6. All hooks are no-ops when the feature or the environment's lease is disabled.

**Acceptance criteria**

- Given DEV has leases enabled and no lease, when Alice locks DEV for 2 hours, then a LOCK lease ending at the lock's expiry exists.
- Given that lease, when Alice releases the lock after 30 minutes, then the lease now ends 30 minutes (grace) later unless someone extends it.
- Given Bob is the only participant, when his access expires, then the lease end moves to at most 15 minutes from now and he is marked as left.
- Given Alice and Bob are participants, when Bob's access is revoked, then the end time does not change.

**Tests**

- LeaseLockIntegrationTest (AbstractIntegrationTest): acquire -> lease; extend lock -> lease end moves; release, break and lock expiry -> grace.
- LeaseServiceTest: onUserAccessEnded with one vs several participants; disabled environment is a no-op.

#### E15-T04 — Warn participants at 60, 15 and 5 minutes and support extend, with owner approval above the maximum

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G4 · **Depends on:** E15-T03, E04-T04, E04-T05

G4 / spec-1: warnings at 60/15/5 minutes in-app and by email with one-click extend; extending beyond the owner's maximum routes to the owner for approval.

**Files**

- `src/main/resources/db/migration/V<next>__add_lease_extension_request.sql` (new) — lease_extension_request table
- `src/main/java/com/tcgdigital/vmcontrol/model/LeaseExtensionRequest.java` (new) — request_id, lease_id, requester_user_id, minutes, reason, status PENDING/APPROVED/DENIED/EXPIRED, reviewed_by_user_id, reviewed_at, created_at
- `src/main/java/com/tcgdigital/vmcontrol/repository/LeaseExtensionRequestRepository.java` (new) — pending by lease; @Modifying transitionFromPending
- `src/main/java/com/tcgdigital/vmcontrol/repository/OperationExecutionRepository.java` (modify) — findInitiatorIdsSince(environmentId, since)
- `src/main/java/com/tcgdigital/vmcontrol/service/LeaseService.java` (modify) — extend, decideExtension, processLeaseWarnings
- `src/main/java/com/tcgdigital/vmcontrol/dto/ExtendLeaseDTO.java` (new) — @NotNull @Min(15) @Max(1440) minutes, @Size(max=500) reason
- `src/main/java/com/tcgdigital/vmcontrol/controller/LeaseController.java` (modify) — POST /lease/extend; GET /lease/extension-requests; POST /api/v1/lease-extension-requests/{id}/approve|deny
- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — LEASE_EXPIRING, LEASE_EXPIRED, LEASE_EXTENSION_REQUESTED, LEASE_EXTENSION_DECIDED
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyLeaseExpiring (windowed de-dup), notifyLeaseExtensionRequested, notifyLeaseExtensionDecided
- `src/main/java/com/tcgdigital/vmcontrol/service/EmailTemplates.java` (modify) — lease warning email with link to the environment page
- `src/main/resources/application.properties` (modify) — notification.email.lease-expiring.enabled=${NOTIFICATION_EMAIL_LEASE_EXPIRING_ENABLED:false}
- `src/test/java/com/tcgdigital/vmcontrol/service/LeaseServiceTest.java` (modify) — extend and warning cases
- `src/test/java/com/tcgdigital/vmcontrol/controller/LeaseControllerIntegrationTest.java` (modify) — extend/approve endpoints

**Steps**

1. Migration (next free V number): lease_extension_request with FK lease_id -> environment_lease ON DELETE CASCADE and user FKs to app_user; index (lease_id, status).
2. LeaseService.extend(envId, userId, minutes, reason): caller must pass securityService.canPerformOperations(envId, userId). newEnd = max(now, endsAt) + minutes. If newEnd - startedAt <= effectiveMax: apply (optimistic @Version; on OptimisticLockException retry once), add caller as participant, extended_minutes_total += minutes, audit LEASE_EXTENDED, return 200 + LeaseDTO. Else if newEnd - startedAt > lease.platform.absolute-max-minutes: 400. Else create a PENDING LeaseExtensionRequest (one pending per lease, else ConflictException from E04-T04 -> 409), notify the environment's reviewers (same set NotificationService.resolveEnvironmentReviewers builds: ADMIN grant holders, global admins, administering env admins), audit LEASE_EXTENSION_REQUESTED, return 202 + request.
3. decideExtension(requestId, reviewerId, approve, notes): reviewer must pass securityService.canManageEnvironmentAccess(envId); conditional update PENDING -> APPROVED/DENIED (0 rows -> 409); on approve apply newEnd = max(now, endsAt) + minutes, ignoring the owner maximum but not the absolute maximum; notify the requester; audit LEASE_EXTENSION_DECIDED.
4. processLeaseWarnings(): for each threshold T in lease.warning-minutes, find ACTIVE leases with endsAt in (now, now+T]; recipients = active participants + current lock holder + operationExecutionRepository.findInitiatorIdsSince(envId, lease.startedAt) (NEW @Query selecting DISTINCT initiatedByUserId) filtered to users who can still operate; notifyLeaseExpiring de-duplicates on (user, LEASE_EXPIRING, 'LEASE', leaseId) created after endsAt - T using the repository method from E04-T05, so each threshold fires once and an extension re-arms them. Email only when notification.email.lease-expiring.enabled.
5. Warning text: '<env> stops at 18:30 (in 15 min). Extend it from the environment page.' with entityType 'ENVIRONMENT' and entityId envId so the bell opens the environment page, which opens the extend dialog when the route has extendLease=1 (E15-T06).
6. Controller endpoints as listed; approve/deny bodies are @Valid with notes @Size(max=500).

**Acceptance criteria**

- Given a running environment with a 4-hour lease, when 3 h 45 min have passed, then every active participant has received the 60-minute and 15-minute warnings exactly once each, with a link that opens Extend.
- Given an owner maximum of 12 hours and a lease started 11 hours ago, when a user extends by 2 hours, then the response is 202, the owner is notified and endsAt is unchanged until approval.
- Given the owner approves, then endsAt = max(now, endsAt) + 2h and the requester is notified; given two owners approve at once, then one gets 409.
- Given a lease extended by 60 minutes after its 15-minute warning, then the warnings fire again before the new end.

**Tests**

- LeaseServiceTest: within-max extend applies; above-max creates exactly one pending request; above absolute max -> 400; approve/deny transitions; warning thresholds and de-dup; recipients include lock holder and recent operators.
- LeaseControllerIntegrationTest: extend 200/202 and VIEWER 403; approve by owner 200, by USER 403, double approve 409.

#### E15-T05 — End overdue leases by stopping the environment in reverse dependency order, including after a restart

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G4 · **Depends on:** E15-T04, E05-T03, E05-T06, E06-T01, E07-T04

G4 / spec-1: when the lease ends with no extension the environment stops dependents first, the stop is attributed to 'Lease expired' with a LEASE_EXPIRED audit event, and overdue leases are processed within 5 minutes of a platform restart.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — startSystemOperation(envId, attributedUserId, dto, trigger) that skips lock and group-scope checks
- `src/main/java/com/tcgdigital/vmcontrol/service/LeaseExpiryProcessor.java` (new) — @Transactional(REQUIRES_NEW) expire(leaseId)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/LeaseScheduler.java` (new) — runLocked('lease-sweep'): warnings, overdue leases, stale extension requests
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — logLeaseExpired
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyLeaseExpiredForEnvironment
- `src/test/java/com/tcgdigital/vmcontrol/service/LeaseExpiryIntegrationTest.java` (new) — end-to-end expiry with mocked cloud provider
- `src/test/java/com/tcgdigital/vmcontrol/service/VmOperationsServiceTest.java` (modify) — system operation path

**Steps**

1. VmOperationsService: extract the body of startOperation (line 100) into a private method with a boolean systemInitiated flag; startOperation passes false; new public startSystemOperation(String envId, String attributedUserId, StartOperationDTO dto, String trigger) passes true, which skips lockService.verifyLockPermission and enforceGroupScopeAccess, sets reason = trigger and keeps initiated_by_user_id = attributedUserId (the column is NOT NULL with an FK to app_user, so use the lease starter). STOP ordering comes from E05-T03 (reverse dependency order).
2. LeaseExpiryProcessor.expire(leaseId): reload; return unless ACTIVE and endsAt <= now. If executionRepository.hasActiveOperations(envId): stop_attempts += 1 and return (retried next sweep); after 10 attempts mark EXPIRED with end_reason 'Could not stop: operation in progress' and notify owners. Otherwise: release an active lock on the env via the E07-T04 helper with reason 'lease expired'; if any VM in the env is RUNNING or STARTING call startSystemOperation(envId, lease.startedBy, STOP whole environment, skipAlreadyInTargetState=true, continueOnFailure=true, 'Lease expired') and store stop_execution_id; mark the lease EXPIRED, ended_at=now, end_reason 'Lease expired'; mark its PENDING extension requests EXPIRED; auditService.logLeaseExpired('system', envId, envName, leaseId, executionId) with action LEASE_EXPIRED; AfterCommit notify participants and owners: '<env> lease expired - stopping. Start it again from the environment page.'
3. LeaseScheduler: @ConditionalOnProperty(name = 'lease.enabled', havingValue = 'true'); @Scheduled(fixedDelayString = '${lease.sweep.interval-ms:60000}', initialDelayString = '60000'); body: jobLockService.runLocked('lease-sweep', Duration.ofMinutes(5), () -> { leaseService.processLeaseWarnings(); for each id in leaseRepository.findOverdueIds(now) try processor.expire(id) catch log.warn; }). Overdue rows are selected by endsAt <= now, so leases that ended while the platform was down are processed on the first run after start (about 1-2 minutes).
4. Restart interplay: E05-T06 recovers orphaned executions on startup; the sweep skips an env while hasActiveOperations is true and retries each minute.
5. No AWS call happens unless lease.enabled and the environment's lease_enabled are both true.

**Acceptance criteria**

- Given a lease that reaches its end with no extension, then within one sweep a STOP execution exists whose details stop dependents before their dependencies, and the audit log shows LEASE_EXPIRED and OPERATION_STARTED with reason 'Lease expired'.
- Given the lease starter has since lost access and someone else holds the lock, when the lease expires, then the stop still runs (system-initiated) and the lock is released with a history note.
- Given the platform was down at the lease end, when it restarts, then the overdue lease is processed within 5 minutes.
- Given an operation is in progress at the lease end, then the sweep retries each minute and stops the environment once the operation finishes.
- Given every VM is already stopped, then the lease is marked EXPIRED without creating an operation.

**Tests**

- LeaseExpiryIntegrationTest (AbstractIntegrationTest, CloudProviderService mocked): overdue lease -> STOP execution with reverse-order details, lease EXPIRED, audit rows; operation in progress -> retry; two processors racing expire once (@Version + status re-check).
- VmOperationsServiceTest: startSystemOperation skips lock and group checks while startOperation still enforces them.
- LeaseSchedulerTest (unit): uses runLocked('lease-sweep'); one failing lease does not stop the others.

**Notes:** First feature that stops AWS instances on its own; keep lease.enabled=false in prod until one pilot environment has run with it for a week.

#### E15-T06 — Lease UI on the environment page: end time, countdown, extend, pending approval and owner policy

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** G4 · **Depends on:** E15-T05, E02, E01

G4 / spec-1: users see when the environment ends and extend with one click; owners set defaults and maximums and approve over-maximum extensions.

**Files**

- `src/main/resources/static/js/features/leases.js` (new) — Leases IIFE: buildLeaseBanner, bindLeaseEvents, showExtendDialog, showPolicyDialog, approve/deny
- `src/main/resources/static/css/features/leases.css` (new) — lease banner, countdown, ending-soon state
- `src/main/resources/static/js/features/environments.js` (modify) — buildDetailHtml (line ~557) renders the lease banner below the lock banner; start dialog gets 'Run for' when leaseEnabled
- `src/main/resources/static/js/config.js` (modify) — API.leases.* endpoints
- `src/main/resources/static/index.html` (modify) — script/stylesheet tags for leases if index.html lists source files
- `build-frontend.ps1` (modify) — add leases.js and leases.css to the bundle order

**Steps**

1. config.js: leases: { get: env => `${API_BASE_URL}/environments/${env}/lease`, policy: env => `${API_BASE_URL}/environments/${env}/lease-policy`, extend: env => `${API_BASE_URL}/environments/${env}/lease/extend`, extensionRequests: env => `${API_BASE_URL}/environments/${env}/lease/extension-requests`, approve: id => `${API_BASE_URL}/lease-extension-requests/${id}/approve`, deny: id => `${API_BASE_URL}/lease-extension-requests/${id}/deny` }.
2. leases.js buildLeaseBanner(lease, policy): when env.leaseEnabled and a lease is ACTIVE show 'Running until 18:30 (in 2 h 14 min)' and participants; 'ending-soon' style under 15 minutes; Extend button (30/60/120/240 minutes; when the result exceeds the owner max show 'Needs owner approval' and require a reason); 'Pending approval' pill when a request is open; for owners a list of pending extension requests with Approve/Deny. Buttons follow the permissions in LeaseDTO, never client-side role guesses.
3. Policy dialog (owners only): enable toggle, default and max selects limited to the platform limits from GET lease-policy.
4. environments.js start dialog: when env.leaseEnabled add a 'Run for' select (default = policy default, options up to max) and send leaseMinutes in the START body.
5. Countdown every 30 s and lease re-fetch every 60 s via RealTime.startPolling('lease', fn, ms, { pageScoped: true }) (E02).
6. When the route carries extendLease=1 (from the warning notification) open the extend dialog after render.
7. Render with Utils.html; GET lease with suppressGlobalError and hide the banner on 404 (feature off).
8. Run build-frontend.ps1.

**Acceptance criteria**

- Given DEV has an active lease ending in 2 h 14 min, when a USER opens DEV, then the banner shows the end time, a live countdown and an Extend button; a VIEWER sees no Extend.
- Given the user extends within the maximum, then the end time updates immediately; above the maximum the banner shows 'Pending approval' and owners see Approve/Deny on the same page.
- Given an owner opens Lease settings, then default and maximum can only be set within the platform limits.
- Given lease.enabled=false, then the environment page looks exactly as before.

**Tests**

- LeaseControllerIntegrationTest: GET lease payload includes endsAt, participants, pendingExtension and permissions per role.
- Manual QA script in the PR: the four acceptance cases, 375px width, keyboard-only extend, countdown stops after navigating away.

#### E15-T07 — Show the user's running leases in My Account and document the rollout

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G4 · **Depends on:** E15-T06, E04-T08

G4 / spec-1 UI: users need one place to see which environments they are keeping alive and when each ends; operators need the flags and rollout steps.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/MyAccountService.java` (modify) — getMyLeases(userId)
- `src/main/java/com/tcgdigital/vmcontrol/controller/UserController.java` (modify) — GET /api/v1/users/me/leases (404 when lease.enabled=false)
- `src/main/resources/static/js/features/my-account.js` (modify) — 'Running environments' section in the Access tab with end time and Extend (reuses Leases.showExtendDialog)
- `src/main/resources/static/css/features/my-account.css` (modify) — lease rows
- `src/main/resources/static/js/config.js` (modify) — API.users.myLeases
- `src/test/java/com/tcgdigital/vmcontrol/service/MyAccountServiceTest.java` (modify) — getMyLeases
- `src/test/java/com/tcgdigital/vmcontrol/controller/UserControllerTest.java` (modify) — /users/me/leases

**Steps**

1. MyAccountService.getMyLeases(userId): active leases where the user is an active participant (left_at IS NULL) or the starter; map to LeaseDTO with the environment display name; sort by endsAt.
2. UserController: @GetMapping("/me/leases") @PreAuthorize("isAuthenticated()") returning the list.
3. my-account.js: load leases alongside the existing loads (same state-owner guard pattern as onExtendSubmit, line 697); render 'Running environments' above the grants: 'DEV - stops at 18:30 (in 2 h)' with an Extend button calling Leases.showExtendDialog(envId, lease, onDone); hide the section when the call returns 404 or an empty list.
4. Document in application.properties comments and the PR: lease.enabled, per-environment opt-in, platform limits, email flag, pilot procedure (one environment, one week, then widen).
5. Run build-frontend.ps1.

**Acceptance criteria**

- Given Alice participates in DEV's active lease ending at 18:30, when she opens My Account, then 'Running environments' lists DEV with its end time and an Extend button.
- Given Alice left the lease (lock released or access ended), then DEV is not listed.
- Given lease.enabled=false, then the section is absent and nothing is logged to the console.

**Tests**

- MyAccountServiceTest: getMyLeases includes started and participating leases, excludes left and ended.
- UserControllerTest: /users/me/leases 200 for an authenticated user, 404 when the feature is off.

## E16

### Idle auto-stop — Now

Environments (or groups) whose VMs have all been idle for a configurable time stop themselves in dependency order, after an owner-visible dry run that reports what would have been saved, with snooze and safe exclusions.

Sources: G5 · 5 tasks · 8 days

#### E16-T01 — Add idle-stop rule, snooze and event tables, a production flag, and the rule CRUD API

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** G5 · **Depends on:** E06-T01, E03-T01, E00-T02

G5 spec #2: a rule per environment or group with idle minutes (default 60), thresholds, dry-run vs enforce mode, snooze and recorded idle evidence. Nothing marks an environment as production today (VmType has only DEV/TEST/STAGING), but production must be excluded.

**Files**

- `src/main/resources/db/migration/V<next>__idle_auto_stop.sql` (new) — idle_stop_rule, idle_stop_snooze, idle_stop_event; environment.is_production
- `src/main/java/com/tcgdigital/vmcontrol/model/IdleStopRule.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/model/IdleStopMode.java` (new) — DRY_RUN, ENFORCE
- `src/main/java/com/tcgdigital/vmcontrol/model/IdleStopSnooze.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/model/IdleStopEvent.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/model/IdleStopOutcome.java` (new) — WOULD_STOP, STOPPED, SKIPPED, SNOOZED
- `src/main/java/com/tcgdigital/vmcontrol/model/Environment.java` (modify) — isProduction (if not already added by E15)
- `src/main/java/com/tcgdigital/vmcontrol/repository/IdleStopRuleRepository.java` (new) — findByEnabledTrue (JOIN FETCH environment), findByEnvironment
- `src/main/java/com/tcgdigital/vmcontrol/repository/IdleStopSnoozeRepository.java` (new) — findActive(envId, now)
- `src/main/java/com/tcgdigital/vmcontrol/repository/IdleStopEventRepository.java` (new) — by environment and time range
- `src/main/java/com/tcgdigital/vmcontrol/dto/IdleStopRuleDTO.java` (new) — response
- `src/main/java/com/tcgdigital/vmcontrol/dto/IdleStopRuleRequestDTO.java` (new) — request with bean validation
- `src/main/java/com/tcgdigital/vmcontrol/service/IdleStopRuleService.java` (new) — CRUD + validation + audit
- `src/main/java/com/tcgdigital/vmcontrol/controller/IdleStopController.java` (new) — /api/v1/environments/{environmentId}/idle-stop/rules
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — IDLE_STOP_RULE_CHANGED, IDLE_STOP_TRIGGERED, IDLE_STOP_SNOOZED
- `src/test/java/com/tcgdigital/vmcontrol/controller/IdleStopControllerTest.java` (new) — CRUD + authorization

**Steps**

1. Migration (next free V): idle_stop_rule(rule_id VARCHAR(36) PK, environment_id FK ON DELETE CASCADE, scope_type VARCHAR(20) 'ENVIRONMENT'|'GROUP', group_id VARCHAR(36) NULL, idle_minutes INT NOT NULL DEFAULT 60, cpu_max_percent DECIMAL(5,2) NOT NULL DEFAULT 5.00, network_mb_per_day DECIMAL(10,2) NOT NULL DEFAULT 5.00, mode VARCHAR(20) NOT NULL DEFAULT 'DRY_RUN', enabled BOOLEAN NOT NULL DEFAULT TRUE, dry_run_started_at TIMESTAMP NULL, created_by_user_id VARCHAR(36) NOT NULL, created_at, updated_at, version BIGINT NOT NULL DEFAULT 0, UNIQUE(environment_id, group_id)). idle_stop_snooze(snooze_id PK, environment_id FK, snoozed_until TIMESTAMP NOT NULL, snoozed_by_user_id, reason VARCHAR(255) NULL, created_at; INDEX(environment_id, snoozed_until)). idle_stop_event(event_id PK, rule_id FK, environment_id, evaluated_at, outcome VARCHAR(20), reason VARCHAR(255), idle_since TIMESTAMP NULL, evidence_json TEXT, projected_saving_per_hour DECIMAL(12,4) NULL, execution_id VARCHAR(36) NULL; INDEX(environment_id, evaluated_at)). If E15 has not already added a production marker, add `ALTER TABLE environment ADD COLUMN is_production BOOLEAN NOT NULL DEFAULT FALSE` (otherwise reuse E15's).
2. Entities/enums/repositories as listed, following AutomationRule's style (Timestamp fields, @CreationTimestamp/@UpdateTimestamp, LAZY environment).
3. IdleStopRuleService: create/update/delete/list for one environment; validate idle_minutes 30..1440, cpu 0.5..20, network 0.1..1000; GROUP scope group must belong to the environment (reuse the check style in AutomationRuleService.validateScope L307-327); reject create when environment.isProduction. New rules always start in DRY_RUN with dry_run_started_at = now. Switching to ENFORCE requires dry_run_started_at older than `automation.idle-stop.min-dry-run-days` (default 14) unless the caller is ADMIN. Audit IDLE_STOP_RULE_CHANGED.
4. IdleStopController under /api/v1/environments/{environmentId}/idle-stop: GET rules (assertCanView), POST/PUT/DELETE rules (ADMIN or ENV_ADMIN on that environment via SecurityService.canManageEnvironmentAccess or E03 helpers), all paths bound with assertSameEnvironment.
5. Admin-only PATCH /api/v1/environments/{environmentId}/production {production: true|false} (or extend UpdateEnvironmentDTO if E10 prefers) to set the flag; audited.
6. Feature flag `automation.idle-stop.enabled=false` in application.properties; when false the controller still allows configuring rules (dry run is visible later) but nothing is evaluated (T03).

**Acceptance criteria**

- Given an ENV_ADMIN of env A, when they create an idle rule for A with defaults, then it is saved in DRY_RUN with idle_minutes 60 and audited.
- Given a USER on A, when they POST a rule, then 403; given a group of env B in the request, then 404.
- Given a rule 3 days into dry run, when an ENV_ADMIN switches it to ENFORCE, then 400 'Run in dry-run mode for 14 days first'; an ADMIN can override.
- Given env A is marked production, then creating an idle rule for A returns 400.

**Tests**

- IdleStopControllerTest (SecuredWebTestBase): CRUD by ENV_ADMIN/ADMIN, 403 for USER/VIEWER, cross-environment 404, enforce gating, production rejection.
- FlywayMigrationTest still passes with the new migration.

**Notes:** Ships dark: automation.idle-stop.enabled=false.

#### E16-T02 — Build IdleEvaluator: per-VM idle evidence over the rule window from vm_metric_sample, aligned with the Compute Optimizer idle definition

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G5 · **Depends on:** E16-T01

G5 requires thresholds aligned with AWS Compute Optimizer (CPU < 5%, network < 5 MB/day). VmIdleSummary cannot be used as is: VmMetricsService.updateIdleSummary (L146-184) resets idleSince to the oldest sample of a fixed 30-minute window every run, so idle duration never grows past the minimum.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/idle/IdleEvaluator.java` (new) — evaluate(List<Vm>, IdleStopRule, Instant now) -> EnvironmentIdleResult
- `src/main/java/com/tcgdigital/vmcontrol/service/idle/VmIdleEvidence.java` (new) — record: vmId, idle, idleSince, maxCpu, networkBytes, diskBytes, sampleCount, coveragePercent, reason
- `src/main/java/com/tcgdigital/vmcontrol/service/idle/EnvironmentIdleResult.java` (new) — record: allIdle, idleSince (latest of VM idleSince), List<VmIdleEvidence>
- `src/test/java/com/tcgdigital/vmcontrol/service/idle/IdleEvaluatorTest.java` (new) — threshold and coverage cases

**Steps**

1. For each RUNNING active VM in scope, load samples with VmMetricSampleRepository.findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc(vmIds, now - idleMinutes, now) (one query for all VMs).
2. A VM is idle when: samples cover at least 80% of the window (sum of periodSeconds / window seconds), every sample has cpuUtilization < cpu_max_percent, and every sample's (networkIn + networkOut) < network_mb_per_day * 1,048,576 * periodSeconds / 86,400 (the per-day budget scaled to the sample period). Disk IO is recorded in evidence but not required (Compute Optimizer's idle definition is CPU + network).
3. idleSince per VM = start of the longest trailing run of idle samples within a lookback of max(idleMinutes, 24h) (second query bounded by that lookback); environment idleSince = the latest VM idleSince; allIdle = every in-scope RUNNING VM idle and idleSince <= now - idleMinutes.
4. VMs that are STOPPED are ignored; VMs with no metrics (e.g. EKS node groups, G15) make allIdle false with reason 'No metrics' (EKS excluded per spec).
5. Pure logic: no writes; unit-testable with in-memory sample lists (inject the repository, test with Mockito).

**Acceptance criteria**

- Given two running VMs with 70 minutes of samples all at CPU 1% and 10 KB network per 5-minute sample, when evaluated with idle_minutes 60, then allIdle is true and idleSince is about 70 minutes ago.
- Given one sample at CPU 12% 20 minutes ago, then allIdle is false and that VM's evidence names the CPU reason.
- Given only 30 minutes of samples in a 60-minute window, then allIdle is false with 'Insufficient metric coverage'.

**Tests**

- IdleEvaluatorTest: thresholds, network scaling for 60s vs 300s periods, coverage, stopped VMs ignored, EKS no-metrics, idleSince computation.

**Notes:** Spec dependency: the metrics archive must scale past ~34 always-on VMs (E12). Evaluator only reads vm_metric_sample within 24h.

#### E16-T03 — Add IdleStopService and scheduler: exclusions, dry-run events with projected savings, and enforced dependency-ordered stops

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G5 · **Depends on:** E16-T02, E05-T03, E06-T02, E06-T04

G5 acceptance: dry run never stops VMs and records would-have-saved; enforcement stops the environment in dependency order with idle evidence recorded; locked environments, active operations and production are skipped with a logged reason.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/idle/IdleStopService.java` (new) — evaluateAll(), evaluateRule(ruleId)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/IdleStopScheduler.java` (new) — @Scheduled every 5 min via runLocked('idle_auto_stop')
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — accept a trigger label/reason for audit (reuse StartOperationDTO.reason)
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyIdleStopForEnvironment
- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — IDLE_AUTO_STOPPED
- `src/main/resources/application.properties` (modify) — automation.idle-stop.enabled=false, interval, min-dry-run-days
- `src/test/java/com/tcgdigital/vmcontrol/service/idle/IdleStopServiceTest.java` (new) — exclusions, dry run, enforce

**Steps**

1. IdleStopScheduler: @ConditionalOnProperty(automation.idle-stop.enabled, havingValue true, matchIfMissing false); @Scheduled(fixedRateString="${automation.idle-stop.interval:300000}") → scheduledJobLockService.runLocked("idle_auto_stop", Duration.ofMinutes(10), idleStopService::evaluateAll).
2. evaluateAll: for each enabled rule (JOIN FETCH environment) run evaluateRule in its own REQUIRES_NEW TransactionTemplate.
3. Exclusions in order, each recording an idle_stop_event SKIPPED (or SNOOZED) with reason, at most one per rule per hour for the same reason: environment inactive; environment.isProduction; active snooze (IdleStopSnoozeRepository.findActive); lockService.isEnvironmentLocked(envId) → 'Locked by <display name>'; executionRepository.hasActiveOperations(envId) → 'Operation in progress'; no RUNNING VMs in scope → no event.
4. Run IdleEvaluator; if not allIdle, no event. If allIdle: projected_saving_per_hour = sum over running in-scope VMs of hourly rate (VmInventorySnapshotRepository.findByVmVmId(..).getInstanceType() + PricingReferenceService.lookupHourlyRate(provider, instanceType, region), as EstimatedCostProvider does around L101; unknown price counts 0 and is flagged in evidence).
5. DRY_RUN: write one WOULD_STOP event per idle episode (skip when an event for the same idleSince already exists) with evidence_json (per-VM VmIdleEvidence) and the saving per hour; never call VmOperationsService.
6. ENFORCE: build StartOperationDTO(STOP, groupIds = [rule.groupId] when GROUP scope, reason 'Idle auto-stop: idle since <time>', skipAlreadyInTargetState true) and call vmOperationsService.startOperationIfNeeded(envId, rule.createdByUserId, dto) (E06-T02) inside the REQUIRES_NEW block; STOP order is reverse dependency order from E05-T03. Write STOPPED event with execution_id and evidence; audit IDLE_STOP_TRIGGERED with the evidence summary; notify environment members via new notifyIdleStopForEnvironment (type IDLE_AUTO_STOPPED, message 'Stopped after N minutes idle', link to the environment page where Start is available).
7. If the rule creator is inactive or no longer admin/env-admin, disable the rule (same preflight rules as E06-T04) instead of acting.

**Acceptance criteria**

- Given dry-run mode and an environment idle for 60 minutes, when the scheduler runs, then no VM stops and a WOULD_STOP event with projected saving per hour is stored once for that idle episode.
- Given enforcement and all VMs idle for 60 minutes, then a STOP execution starts with dependents first, the event holds the execution id and idle evidence, and members get a notification linking to the environment.
- Given the environment is locked by someone, then no stop happens and a SKIPPED event says 'Locked by <name>'.
- Given automation.idle-stop.enabled=false (default), then the scheduler bean is absent and nothing is evaluated.

**Tests**

- IdleStopServiceTest (AbstractIntegrationTest, mocked CloudProviderService, seeded vm_metric_sample rows): dry run no operation; enforce creates execution with reverse order; each exclusion; duplicate episode not re-recorded; inactive creator disables rule.

**Notes:** Changes AWS state only when the flag is on AND a rule is in ENFORCE after its dry run. Roll out: enable flag in prod with all rules in DRY_RUN for 14 days.

#### E16-T04 — Add snooze (1/4/8 h) and a 14-day would-have-saved summary API

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G5 · **Depends on:** E16-T03

G5: anyone using the environment can snooze from the environment page or a notification, snoozes are audited, and owners see projected savings from dry run before enforcing.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/idle/IdleStopSnoozeService.java` (new) — snooze, cancelSnooze, getActive
- `src/main/java/com/tcgdigital/vmcontrol/service/idle/IdleStopSummaryService.java` (new) — summary over last N days
- `src/main/java/com/tcgdigital/vmcontrol/dto/IdleStopStatusDTO.java` (new) — rule, mode, snoozedUntil, latest event, wouldHaveSaved14d, stoppedCount14d, savedEstimate14d
- `src/main/java/com/tcgdigital/vmcontrol/controller/IdleStopController.java` (modify) — GET status, POST snooze, DELETE snooze
- `src/test/java/com/tcgdigital/vmcontrol/service/idle/IdleStopSummaryServiceTest.java` (new) — saving math

**Steps**

1. POST /api/v1/environments/{environmentId}/idle-stop/snooze {hours: 1|4|8, reason?}: requires assertCanOperate (USER level) on the environment; rejects other durations with 400; creates or extends the active snooze (snoozed_until = max(existing, now + hours)); audit IDLE_STOP_SNOOZED with hours and reason. DELETE ends the active snooze (initiator, env admin or admin).
2. Would-have-saved for a WOULD_STOP event = projected_saving_per_hour × hours until the environment next had activity (next non-idle sample or next manual/scheduled start found in vm_state_history for those VMs), capped at 24 h per episode; computed in IdleStopSummaryService for events in the last `days` (default 14, max 31).
3. Saved estimate for STOPPED events = saving per hour × hours stopped, from vm_state_history STOPPED→RUNNING transitions after the event (reuse VmCostCalculator.computeRuntimeHours style; cap at now).
4. GET /api/v1/environments/{environmentId}/idle-stop/status (assertCanView) returns IdleStopStatusDTO including whether the feature flag is on.
5. Record the hours-avoided per STOPPED event in the response (idleStopEventId, hours, amount) so E17's savings ledger can attribute 'idle stop' savings later.

**Acceptance criteria**

- Given a user with USER access snoozes for 4 hours, then no idle stop happens for that environment until the snooze ends, and an audit row records the snooze.
- Given a snooze request of 3 hours, then 400.
- Given three WOULD_STOP episodes in the last 14 days on a $0.50/h environment each followed by 6 h of inactivity, then wouldHaveSaved14d is $9.00.
- Given a VIEWER, when they POST snooze, then 403.

**Tests**

- IdleStopSummaryServiceTest: episode capping, next-activity detection, stopped-hours math.
- IdleStopControllerTest: snooze authorization, durations, extend, delete.

#### E16-T05 — Environment page idle panel: status, dry-run savings, snooze buttons and rule settings

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G5 · **Depends on:** E16-T04, E01, E02

G5 acceptance: the would-have-saved amount appears on the environment page; snooze from the environment page; owners turn rules on and choose thresholds.

**Files**

- `src/main/resources/static/js/features/idle-stop.js` (new) — IdleStop module: renderPanel(env), openRuleModal(env), snooze handlers
- `src/main/resources/static/js/features/environments.js` (modify) — buildDetailHtml (L558) adds a panel container; bindDetailEvents (L868) calls IdleStop.renderPanel
- `src/main/resources/static/js/config.js` (modify) — API.idleStop endpoints
- `src/main/resources/static/index.html` (modify) — script tag for idle-stop.js

**Steps**

1. Config.API.idleStop: status(envId), rules(envId), rule(envId, ruleId), snooze(envId).
2. IdleStop.renderPanel(env): GET status; when the feature flag is off show nothing; otherwise a card 'Idle auto-stop' with mode badge (Dry run / On / Off), 'Idle since' text, 'Would have saved $X in the last 14 days' (dry run) or 'Saved about $X in 14 days' (enforce), snooze state 'Snoozed until 18:40 by Priya' with an End snooze button, and buttons 'Snooze 1 h / 4 h / 8 h' for users who can operate. Use Utils.html for all interpolation (E01); register any refresh with RealTime.startPolling(key, fn, 60000, {pageScoped: true}) (E02).
3. Rule settings modal (ENV_ADMIN/ADMIN only): scope (environment or group), idle minutes (default 60), CPU % (default 5), network MB/day (default 5) with helper text 'Defaults follow AWS Compute Optimizer's idle definition', mode (Dry run / Enforce; Enforce disabled with a tooltip until 14 days of dry run), enabled toggle; Save disables itself while in flight.
4. Show the 'Production' badge and hide the rule button when env.isProduction.
5. The IDLE_AUTO_STOPPED notification carries the environment id; making bell items clickable is owned by E11 — if E11 has shipped click-through, verify it opens #/environments/<id>; otherwise the message text names the environment.
6. Run .uild-frontend.ps1 (it bundles js/ files without a manual list; verify idle-stop.js is included).

**Acceptance criteria**

- Given a rule in dry run with WOULD_STOP events, when a user opens the environment page, then the panel shows the 14-day would-have-saved amount.
- Given a USER clicks 'Snooze 4 h', then the panel shows 'Snoozed until <time>' and the buttons change to End snooze.
- Given a VIEWER, then no snooze or settings buttons are shown.
- Given the flag is off, then no panel is rendered.

**Tests**

- Manual checklist; Playwright smoke if available: snooze flow and dry-run savings text with mocked API.

**Notes:** Chat snooze (Slack/Teams) is E24.

## E17

### Savings ledger & efficiency score — Now

The platform records the money its schedules, leases, idle stops, manual stops and rightsizing actually saved (net of residual EBS/IPv4 and commitment coverage), shows de-duplicated potential savings and an AWS-style Cost Efficiency %, and exports a monthly ledger for leadership.

Sources: G6, spec-3 · 7 tasks · 11 days

#### E17-T01 — Persist why each VM stopped: link state history to its operation and record the operation's trigger source

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G6, spec-3 · **Depends on:** E00-T01, E05, E15, E16

Attribution by cause (schedule, lease, idle stop, manual stop) needs to know which operation stopped a VM. VmStateHistory.operationId is @Transient today, and OperationExecution has no trigger field (G6, spec-3 scope).

**Files**

- `src/main/resources/db/migration/V<next>__add_stop_cause_tracking.sql` (new) — ALTER vm_state_history ADD operation_id VARCHAR(36) NULL + index (vm_id, created_at); ALTER operation_execution ADD trigger_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL' (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/OperationTriggerSource.java` (new) — enum MANUAL, SCHEDULE, ACCESS_AUTOMATION, LEASE, IDLE_STOP, BUDGET, SYSTEM
- `src/main/java/com/tcgdigital/vmcontrol/model/VmStateHistory.java` (modify) — operationId becomes @Column(name="operation_id") instead of @Transient
- `src/main/java/com/tcgdigital/vmcontrol/model/OperationExecution.java` (modify) — @Enumerated(STRING) triggerSource, default MANUAL
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — startOperation(environmentId, userId, dto, OperationTriggerSource) overload; the existing 3-arg method delegates with MANUAL
- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — fireOperation passes SCHEDULE for SCHEDULE rules and ACCESS_AUTOMATION for ACCESS_GRANT rules
- `src/main/java/com/tcgdigital/vmcontrol/dto/OperationExecutionDTO.java` (modify) — expose triggerSource
- `src/test/java/com/tcgdigital/vmcontrol/service/VmOperationsServiceTest.java` (modify) — trigger source persisted; history carries operation id
- `src/test/java/com/tcgdigital/vmcontrol/service/AutomationRuleServiceTest.java` (new) — SCHEDULE rule passes SCHEDULE

**Steps**

1. Migration (next free V number): ALTER TABLE vm_state_history ADD COLUMN operation_id VARCHAR(36) NULL; CREATE INDEX idx_vm_state_history_vm_created ON vm_state_history(vm_id, created_at) (skip it if E08-T04 already added an equivalent); ALTER TABLE operation_execution ADD COLUMN trigger_source VARCHAR(20) NOT NULL DEFAULT 'MANUAL'.
2. Create the OperationTriggerSource enum. In OperationExecution add @Enumerated(EnumType.STRING) @Column(name="trigger_source", nullable=false, length=20) private OperationTriggerSource triggerSource = OperationTriggerSource.MANUAL, with accessors.
3. VmStateHistory: replace '@Transient private String operationId' with @Column(name = "operation_id", length = 36). The builder already sets it, and StateSyncService.recordStateChange (line 455) already passes executionId from VmOperationsService (line 663).
4. VmOperationsService: add startOperation(String environmentId, String userId, StartOperationDTO dto, OperationTriggerSource source), which sets execution.setTriggerSource(source) before saving. The existing 3-arg method delegates with MANUAL. Do not add triggerSource to StartOperationDTO: clients must not choose it.
5. AutomationRuleService.fireOperation: pass rule.getTriggerType() == AutomationTriggerType.SCHEDULE ? SCHEDULE : ACCESS_AUTOMATION.
6. Retrofit the earlier epics: grep for callers of vmOperationsService.startOperation added by E15 (lease expiry stop) and E16 (idle auto-stop) and pass LEASE or IDLE_STOP. If E16 stops VMs through another path (for example a direct provider call), record the cause on that path's VmStateHistory.details as JSON {"cause":"IDLE_STOP"} and document it in the PR.
7. OperationExecutionDTO: add triggerSource so the operation history UI can show 'Scheduled' or 'Manual'.

**Acceptance criteria**

- Given a SCHEDULE automation rule stops an environment, when the operation completes, then operation_execution.trigger_source = 'SCHEDULE' and each STOPPED vm_state_history row carries that execution's id in operation_id.
- Given a user clicks Stop, then trigger_source = 'MANUAL'.
- Given existing rows, after migration they read MANUAL and operation_id NULL with no error.

**Tests**

- VmOperationsServiceTest: the 4-arg overload persists the source; the 3-arg call defaults to MANUAL.
- AutomationRuleServiceTest (Mockito): a SCHEDULE rule calls startOperation with SCHEDULE; an ACCESS_GRANT rule with ACCESS_AUTOMATION.
- Integration (AbstractIntegrationTest): recordStateChange with an operationId round-trips the column.

**Notes:** E15 and E16 run earlier in global order, so this task retrofits their stop calls. It does not change their behaviour.

#### E17-T02 — Add the savings ledger table and a pure calculator for avoided hours, gross and net saving per VM-day

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G6, spec-3 · **Depends on:** E17-T01, E08-T01

Realised savings = stopped hours inside a baseline x On-Demand rate, minus what stopped VMs still cost (EBS, public IPv4) and minus commitment coverage, attributed to the stop's cause (G6, spec-3).

**Files**

- `src/main/resources/db/migration/V<next>__add_savings_ledger.sql` (new) — savings_ledger_entry table (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/SavingsCause.java` (new) — enum SCHEDULE, LEASE, IDLE_STOP, MANUAL_STOP, RIGHTSIZING, OTHER
- `src/main/java/com/tcgdigital/vmcontrol/model/SavingsLedgerEntry.java` (new) — JPA entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/SavingsLedgerEntryRepository.java` (new) — finders by date range, environment; sums by cause/environment/team
- `src/main/java/com/tcgdigital/vmcontrol/service/SavingsCalculator.java` (new) — pure @Component: avoided intervals by cause; money math
- `src/main/resources/application.properties` (modify) — savings.baseline.hours-per-day=24, savings.baseline.days=MON-SUN, savings.ipv4-hourly-rate=0.005
- `src/test/java/com/tcgdigital/vmcontrol/service/SavingsCalculatorTest.java` (new) — spec acceptance numbers

**Steps**

1. Migration: CREATE TABLE savings_ledger_entry (entry_id VARCHAR(36) PK, ledger_date DATE NOT NULL, vm_id VARCHAR(36) NOT NULL, environment_id VARCHAR(36) NOT NULL, team VARCHAR(100), cause VARCHAR(20) NOT NULL, avoided_hours DECIMAL(8,3) NOT NULL, hourly_rate DECIMAL(10,5), gross_saving DECIMAL(12,2) NOT NULL, residual_storage_cost DECIMAL(12,2) NOT NULL DEFAULT 0, residual_ipv4_cost DECIMAL(12,2) NOT NULL DEFAULT 0, commitment_coverage_percent DECIMAL(5,2), commitment_adjustment DECIMAL(12,2) NOT NULL DEFAULT 0, net_saving DECIMAL(12,2) NOT NULL, price_approximate BOOLEAN NOT NULL DEFAULT FALSE, note VARCHAR(255), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, FKs to vm and environment ON DELETE CASCADE, UNIQUE (vm_id, ledger_date, cause), INDEX (ledger_date), INDEX (environment_id, ledger_date)).
2. SavingsCause enum. Map OperationTriggerSource to cause: SCHEDULE->SCHEDULE, LEASE->LEASE, IDLE_STOP->IDLE_STOP, MANUAL->MANUAL_STOP, ACCESS_AUTOMATION/BUDGET/SYSTEM->OTHER. A state_sync STOPPED transition (stopped outside the app) maps to OTHER.
3. SavingsCalculator (no DB access, like VmCostCalculator): avoidedByCause(Timestamp dayStart, Timestamp dayEnd, VmStatus seed, SavingsCause seedCause, List<TransitionWithCause> transitions, BaselinePolicy baseline) -> Map<SavingsCause, BigDecimal hours>. It walks the transitions like computeRuntimeHours and, for each non-RUNNING interval (STOPPED/STOPPING), intersects it with the baseline hours of that day and credits the cause of the transition that started it. BaselinePolicy is built from savings.baseline.hours-per-day and savings.baseline.days; the default is 24x7 = 168 h/week.
4. Money: gross = hours x hourlyRate. residualStorage = storageGib x storageGbMonthRate / 30 x (hours/24): the EBS a stopped VM keeps paying. residualIpv4 = hours x savings.ipv4-hourly-rate when the VM's inventory has a public IP. commitmentAdjustment = gross x coveragePercent/100. net = gross - residualStorage - residualIpv4 - commitmentAdjustment, floored at 0. When coverage >= 100 set note 'Compute covered by Savings Plans/RIs; stopping does not reduce the bill'.
5. Entity and repository: findByLedgerDateBetween, findByEnvironmentIdAndLedgerDateBetween, plus projections sumNetByCause(start,end), sumNetByEnvironment(start,end), sumNetByTeam(start,end) and an optional environmentId filter.
6. Add the properties with comments ('the baseline is what the VM would have run without the platform; 24x7 is the AWS Instance Scheduler convention').

**Acceptance criteria**

- Given a VM at $0.10/hour on a weekday 10-hour schedule with a 168-hour baseline, when a week of ledger days is calculated, then avoided hours total 118 and gross saving is $11.80, and net = 11.80 - EBS - IPv4 for those hours.
- Given 100% Savings Plan coverage, then net compute saving is 0 and the note explains why.
- Given a VM stopped by an idle stop at 14:00 and started by a user at 18:00, then 4 hours are credited to IDLE_STOP.

**Tests**

- SavingsCalculatorTest: the spec's 118 h / $11.80 week; 100% coverage gives 0 with the note; an interval split across two causes; the baseline excludes weekends when configured MON-FRI; residual EBS and IPv4 subtracted; net floored at 0.

#### E17-T03 — Write ledger entries daily for every VM from state history, pricing, storage and reservation coverage

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G6, spec-3 · **Depends on:** E17-T02, E06-T01, E08-T04, E08-T07, E08-T08

The ledger must accumulate automatically and correct itself when late history arrives. vm_state_history gives runtime and reservation_coverage_snapshot gives the commitment side (G6, spec-3 data section).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/SavingsLedgerService.java` (new) — captureForDate(date), captureTrailing(days), backfill(days)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/SavingsLedgerScheduler.java` (new) — daily 03:30 UTC via ScheduledJobLockService.runLocked
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmStateHistoryRepository.java` (modify) — fetch transitions with operation trigger source in one query
- `src/main/java/com/tcgdigital/vmcontrol/repository/OperationExecutionRepository.java` (modify) — findTriggerSourcesByIds(ids) projection
- `src/main/resources/application.properties` (modify) — savings.ledger.enabled=true, savings.ledger.cron, savings.ledger.recompute-days=3
- `src/test/java/com/tcgdigital/vmcontrol/service/SavingsLedgerServiceTest.java` (new) — Mockito orchestration
- `src/test/java/com/tcgdigital/vmcontrol/service/SavingsLedgerServiceIntegrationTest.java` (new) — Testcontainers end-to-end for one day

**Steps**

1. SavingsLedgerService.captureForDate(LocalDate date) (@Transactional): window = CostDayBoundary.startOf(date)..startOf(date+1) (E08-T01). Load the active VMs with group and environment (vmRepository.findByIsActiveTrueFetchGroupAndEnvironment). In batches, load: transitions (findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc), the seed via findLatestBeforeForVms (E08-T04), trigger sources for the transitions' operation ids (new OperationExecutionRepository projection), instance types and public IPs (VmInventorySnapshotRepository), storage (VmVolumeSnapshotRepository.sumSizeGibByVmIds), the rate via PricingReferenceService.lookupHourlyRate (keep the approximate flag from E08-T07), and reservationCoverageSnapshotRepository.findBySnapshotDate(date), where coverage = sp_coverage_percent if present, else coverage_percent, else 0.
2. For the seed state, the cause comes from the seed transition's operation (or OTHER). Call SavingsCalculator.avoidedByCause and upsert one row per (vm, date, cause) with hours > 0. Delete existing rows for that vm/date whose cause no longer appears. Team comes from TeamResolver.resolveTeam(environment.getMetadata()).
3. Skip VMs with provider AWS_EKS (node-group scaling is out of scope; document it) and VMs whose price is unknown (record nothing; count them in the log).
4. captureTrailing(n) captures D-n..D-1. Add backfill(days) bounded 1..90 for the API in E17-T06.
5. SavingsLedgerScheduler: @ConditionalOnProperty("savings.ledger.enabled", matchIfMissing=true), @Scheduled(cron="${savings.ledger.cron:0 30 3 * * *}", zone="UTC"). It calls lockService.runLocked("savings_ledger", Duration.ofMinutes(30), () -> captureTrailing(recomputeDays)) (E06-T01). It writes only to the app DB, so on by default is safe.
6. Add the properties with comments.

**Acceptance criteria**

- Given a VM stopped by a schedule from 19:00 to 09:00 next day, when the job runs, then yesterday's entry for cause SCHEDULE has the hours that fall in yesterday's UTC window.
- Given the job runs twice for the same date, then the row count is unchanged and values are recomputed.
- Given no reservation snapshot exists for the date, then commitment_coverage_percent is null and no adjustment is applied.

**Tests**

- SavingsLedgerServiceTest (Mockito): causes are resolved from operation trigger sources; EKS and unpriced VMs are skipped; stale causes are deleted.
- SavingsLedgerServiceIntegrationTest (AbstractIntegrationTest, Testcontainers MySQL): seed a VM, a SCHEDULE operation and STOPPED/RUNNING history rows; captureForDate writes the expected avoided_hours and net_saving.

#### E17-T04 — Record resize events and credit realised rightsizing savings in the ledger

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G6, spec-3 · **Depends on:** E17-T03, E08-T06

Rightsizing is one of the five causes. Its saving is the rate difference times the hours the VM actually ran after the resize (G6, spec-3).

**Files**

- `src/main/resources/db/migration/V<next>__add_vm_resize_event.sql` (new) — vm_resize_event(event_id, vm_id, old_instance_type, new_instance_type, source APPLY|DETECTED, changed_at, changed_by_user_id) (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/VmResizeEvent.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmResizeEventRepository.java` (new) — latest event per VM before a date
- `src/main/java/com/tcgdigital/vmcontrol/service/VmResizeService.java` (modify) — insert an event on success
- `src/main/java/com/tcgdigital/vmcontrol/service/VmInventoryService.java` (modify) — insert a DETECTED event when sync sees a type change (same spot as E08-T06)
- `src/main/java/com/tcgdigital/vmcontrol/service/SavingsLedgerService.java` (modify) — RIGHTSIZING rows
- `src/test/java/com/tcgdigital/vmcontrol/service/SavingsLedgerServiceTest.java` (modify) — rightsizing credit

**Steps**

1. Add the migration, entity and repository (findTopByVmVmIdAndChangedAtLessThanOrderByChangedAtDesc, findByVmVmIdIn...).
2. VmResizeService: after a successful changeInstanceType, save VmResizeEvent(old, new, APPLY, now, userId). VmInventoryService.saveInventory: where E08-T06 stamps instanceTypeChangedAt, also save VmResizeEvent(old, new, DETECTED, now, null).
3. SavingsLedgerService.captureForDate: for each VM whose latest resize event is within savings.rightsizing.credit-days (default 365) before the day, take oldRate and newRate from PricingReferenceService. If oldRate > newRate, write a RIGHTSIZING row: avoided_hours = the hours the VM was RUNNING that day (VmCostCalculator.computeRuntimeHours), hourly_rate = oldRate - newRate, gross = hours x delta, commitment adjustment as in E17-T02, no residual EBS/IPv4 (the VM is running). Upsizes (newRate > oldRate) write nothing.
4. Add savings.rightsizing.credit-days to application.properties.

**Acceptance criteria**

- Given a VM resized from m5.xlarge ($0.192/h) to m5.large ($0.096/h) that ran 10 hours yesterday, when the ledger runs, then a RIGHTSIZING entry of 10 h x $0.096 = $0.96 gross is written.
- Given an upsize, then no RIGHTSIZING entry is written.

**Tests**

- SavingsLedgerServiceTest: rightsizing credit, upsize ignored, credit window respected.
- VmResizeServiceTest: an event is saved on success and not on failure.

#### E17-T05 — Compute de-duplicated potential savings and a daily Cost Efficiency % snapshot

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G6, spec-3 · **Depends on:** E17-T03, E08-T05

Leadership needs one efficiency number: Cost Efficiency % = 1 - potential savings / optimisable spend, as AWS defines it, with potential savings de-duplicated so an idle VM's stop and rightsizing are not both counted (G6, spec-3).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/PotentialSavingsService.java` (new) — per-VM max of open recommendations; optimisable spend; efficiency
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — expose package/public read of per-VM bundle facts needed (monthly cost, idle monthly cost, rightsizing saving) via a new method listVmOpportunities()
- `src/main/resources/db/migration/V<next>__add_efficiency_snapshot.sql` (new) — efficiency_snapshot(snapshot_date, environment_id NULL for fleet, team, potential_savings, optimisable_spend, efficiency_percent) unique(snapshot_date, environment_id) (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/EfficiencySnapshot.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/EfficiencySnapshotRepository.java` (new) — trend queries
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/SavingsLedgerScheduler.java` (modify) — also capture today's efficiency snapshot after the ledger
- `src/test/java/com/tcgdigital/vmcontrol/service/PotentialSavingsServiceTest.java` (new) — de-dup and formula

**Steps**

1. CostEstimationService: add public List<VmOpportunity> listVmOpportunities() built from the cached bundles (E08-T04). Each record holds vmId, environmentId, team, monthlyCost, idle (boolean), monthlyIdleCost, rightsizingSaving (positive SCALE_DOWN only, after E08-T05) and the scheduleCoverage flag (whether any enabled SCHEDULE automation rule covers the VM's environment/group/VM; read AutomationRuleRepository once).
2. PotentialSavingsService.compute(Optional<String> environmentId): per VM, potential = max(idle ? monthlyIdleCost : 0, rightsizingSaving, unscheduledOffHours), where unscheduledOffHours = monthlyCost x savings.potential.off-hours-share (default 0.5, documented as an assumption) only for VMs with no schedule or lease and VmType non-PROD. Taking the max per VM is the de-duplication. optimisableSpend = the sum of monthlyCost for AWS VMs with a known price. efficiency = optimisable > 0 ? 1 - potential/optimisable : null. Return totals plus a per-cause breakdown.
3. Migration, entity and repository for efficiency_snapshot. The scheduler writes one fleet row (environment_id NULL) and one row per environment each day after the ledger.
4. Check that VmType has a PROD value; if VmType has no prod marker, treat every VM as eligible and say so in the Javadoc (the gap review notes all migrated VMs are typed DEV).

**Acceptance criteria**

- Given an idle VM with $50 monthly idle cost and a $20 rightsizing saving, when potential is computed, then that VM contributes $50, not $70.
- Given optimisable spend $1,000 and potential $170, then Cost Efficiency % = 83.0.
- Given the job ran on 30 days, when the trend is read, then 30 fleet points exist.

**Tests**

- PotentialSavingsServiceTest: per-VM max de-dup, formula, null when optimisable is 0, environment filter.

**Notes:** The off-hours share is an explicit assumption from the gap review; keep it configurable and show it in the UI tooltip.

#### E17-T06 — Expose savings and efficiency APIs with a monthly Excel ledger export and an actuals cross-check

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G6, spec-3 · **Depends on:** E17-T05, E08-T03, E03-T01, E00-T02

FinOps needs monthly reporting and an export; success means realised savings are within 15% of an actuals-based check (G6, spec-3 acceptance: 'ledger total by cause matches the sum of entries and exports to Excel').

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/SavingsController.java` (new) — /api/v1/savings/** (ADMIN) and /api/v1/environments/{id}/savings (scoped)
- `src/main/java/com/tcgdigital/vmcontrol/service/SavingsReportService.java` (new) — monthly summaries, by cause/env/team, export rows, actuals check
- `src/main/java/com/tcgdigital/vmcontrol/dto/SavingsSummaryDTO.java` (new) — month, realisedNet, realisedGross, residuals, byCause[], potential, efficiencyPercent, actualsCheck
- `src/main/java/com/tcgdigital/vmcontrol/dto/SavingsBreakdownRowDTO.java` (new) — key, label, net, gross, hours
- `src/main/java/com/tcgdigital/vmcontrol/dto/EfficiencyTrendPointDTO.java` (new) — date, efficiencyPercent, potential, optimisable
- `src/test/java/com/tcgdigital/vmcontrol/controller/SavingsControllerSecurityTest.java` (new) — authorisation
- `src/test/java/com/tcgdigital/vmcontrol/service/SavingsReportServiceTest.java` (new) — totals and export

**Steps**

1. SavingsReportService.summary(YearMonth month, Optional<String> environmentId): sum the ledger by cause for the month (repository projections from E17-T02), plus potential and efficiency from PotentialSavingsService (current) and the efficiency snapshot at month end (past months). Actuals check: for environments and days where cost_daily_snapshot.actual_cost exists (E08-T03), compare the estimated cost of those days with the actual and report the variance %. Label it 'Estimate quality' with the target 'within 15%'.
2. byEnvironment(month), byTeam(month), efficiencyTrend(from, to, environmentId).
3. exportMonth(YearMonth): ExcelExportService.toWorkbook('Savings Ledger <yyyy-MM>', headers [Date, Environment, Team, VM, Cause, Avoided hours, Rate, Gross, Residual EBS, Residual IPv4, Commitment coverage %, Commitment adj., Net, Approximate price, Note], rows). Make the total row equal the sum of the entries. If ExcelExportService supports only one sheet, add a 'Totals by cause' block after a blank row.
4. SavingsController: GET /api/v1/savings/summary?month=yyyy-MM, /by-cause, /by-environment, /by-team, /efficiency/trend?days=, /export?month=, plus POST /backfill?days= (1..90), all @PreAuthorize("hasRole('ADMIN')"). Scoped: GET /api/v1/environments/{environmentId}/savings?month= calls securityService.assertCanView(environmentId) (E03-T01) and returns that environment's summary (consumed by E18). Validate month with YearMonth.parse (400 on a bad format) and bound days with @Min/@Max.
5. Wrap the ledger repository calls so an empty month returns zeros, not 404.

**Acceptance criteria**

- Given a closed month, when the export is downloaded, then the totals by cause equal the sum of the exported entries.
- Given a USER with VIEWER access to env A, when they GET /api/v1/environments/A/savings, then 200; for env B without access, 404 (assertCanView); for /api/v1/savings/summary, 403.
- Given actuals cover 20 days of the month, then the summary's actualsCheck reports the variance over those 20 days only.

**Tests**

- SavingsReportServiceTest: by-cause sums, export rows and total row, empty month.
- SavingsControllerSecurityTest (SecuredWebTestBase): ADMIN/ENV_ADMIN/USER/VIEWER/anonymous matrix for the fleet and scoped endpoints; bad month -> 400.

#### E17-T07 — Build the Savings leadership page: realised vs potential, efficiency trend, breakdowns and export

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** G6, spec-3 · **Depends on:** E17-T06, E08-T09, E02, E01

Leadership and FinOps need a view of realised savings by cause, environment and team, next to potential and the efficiency score (G6, spec-3 'monthly ledger export and a leadership view').

**Files**

- `src/main/resources/static/js/features/savings.js` (new) — Savings page module
- `src/main/resources/static/css/features/savings.css` (new) — page styles using existing tokens
- `src/main/resources/static/js/core/router.js` (modify) — 'savings' route
- `src/main/resources/static/index.html` (modify) — admin-only sidebar item 'Savings' under Cost Management; css and script tags
- `src/main/resources/static/js/config.js` (modify) — API.savings.*
- `build-frontend.ps1` (modify) — bundle savings.js/css

**Steps**

1. Page layout, following the Cost Management patterns (dashboard-kpi-grid, dashboard-chart-panel). A month picker (default: the current month, with prior months). KPI tiles: Realised savings (net), Potential savings, Cost Efficiency % (tooltip: '1 - potential / optimisable spend; AWS median 83'), Estimate quality (actuals check).
2. Charts (ECharts, registered with dispose on ContentRouter.onLeave like E08-T09): a stacked bar of net savings by cause per day of the month, and a line of efficiency % over 90 days.
3. Tables: by environment and by team (net, gross, hours), sortable client-side. Each environment row links to #/environments/<id> (E02 route params).
4. Commitment note: when any entry in the month has coverage >= 100, show an info banner: 'Some scheduled hours show no compute saving because Savings Plans/RIs already cover them.'
5. 'Export month' downloads GET /api/v1/savings/export?month=. 'Backfill 30 days' calls POST /backfill?days=30, with a confirmation.
6. Use Promise.allSettled with per-panel errors (the same helper style as E08-T09) and Utils.html for every interpolation. Make it work at 390px: tiles stack, tables become cards below 768px per the E13 pattern if it has landed, else horizontal scroll inside the card.
7. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given ledger data for the month, when an admin opens #/savings, then the KPI tiles, the cause chart and the environment/team tables show totals that match /api/v1/savings/summary.
- Given a non-admin, then the Savings link is hidden and the route shows Access denied.
- Given the admin clicks Export month, then an .xlsx for that month downloads.

**Tests**

- Manual checklist in the PR (1440px and 390px; empty month; one failing endpoint shows a panel error). If the test/ Playwright harness can reach the page, add a smoke spec with stubbed API responses.

## E18

### Cost for owners — Now

Every environment page and the My Account panel show month-to-date cost, trend, savings and schedule/lease status to the environment's owners and members, through scoped endpoints guarded by SecurityService (today every cost endpoint is ADMIN-only).

Sources: G7 · 4 tasks · 5.5 days

#### E18-T01 — Add a scoped environment cost endpoint: month-to-date, trend, forecast, top VMs, savings and schedule/lease status

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G7 · **Depends on:** E03-T01, E08-T01, E08-T03, E17-T06, E15, E00-T02

Owners make start/stop decisions on the environment page but cannot see cost, because CostManagementController is hasRole('ADMIN') throughout (G7).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentCostService.java` (new) — per-environment cost view built from cost_daily_snapshot + today's live estimate
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentCostDTO.java` (new) — environmentId, monthToDateEstimated, monthToDateActual, actualDays, previousMonthSameDays, changePercent, forecastMonthEnd, daily[] {date, estimated, actual}, topVms[] {vmId, name, mtdCost, status}, savingsMtd, scheduleStatus {ruleCount, nextStop, nextStart}, leaseStatus {endsAt} | null, scope FULL|GROUPS
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — GET /api/v1/environments/{environmentId}/cost
- `src/main/java/com/tcgdigital/vmcontrol/repository/CostDailySnapshotRepository.java` (modify) — findByEnvironmentEnvironmentIdAndSnapshotDateBetweenOrderBySnapshotDateAsc
- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — public nextScheduledFirings(environmentId, ZonedDateTime from) for enabled SCHEDULE rules
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentCostServiceTest.java` (new) — MTD math, group scoping
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentCostControllerSecurityTest.java` (new) — role matrix

**Steps**

1. CostDailySnapshotRepository: add List<CostDailySnapshot> findByEnvironmentEnvironmentIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(String environmentId, Date from, Date to).
2. EnvironmentCostService.getCost(String environmentId, String userId): (a) monthStart = the first day of CostDayBoundary.today()'s month (E08-T01). (b) Complete days come from the snapshots for monthStart..yesterday; today's partial comes from costDataProvider.estimateCosts(envVms, startOf(today), now). (c) monthToDateActual = the sum of actual_cost where present, with actualDays = the count (the coverage semantics of E08-T03). (d) previousMonthSameDays = the snapshots for the same day-of-month range last month; changePercent is null when the previous value is 0. (e) forecastMonthEnd = MTD + (average of the last 7 complete days x days remaining), labelled as a run-rate. (f) daily = the last 30 complete days. (g) topVms = the 5 highest MTD estimates from one estimateCosts call over monthStart..now. (h) savingsMtd = SavingsReportService.summary(month, env).realisedNet (E17-T06). (i) scheduleStatus comes from AutomationRuleService.nextScheduledFirings. (j) leaseStatus comes from E15's lease service (use the read method E15 created for the environment page; null when there is no lease).
3. Group scoping: if securityService.getVisibleGroupIds(user, environmentId) is not all of the environment's groups (a group-only grant), set scope=GROUPS: compute MTD and topVms only from VMs in the visible groups via estimateCosts, and leave out actuals, the snapshot-based trend and forecast (those are environment-level). Otherwise scope=FULL.
4. AutomationRuleService.nextScheduledFirings(environmentId, from): for enabled SCHEDULE rules of the environment, compute the next stop and start instants from daysOfWeek, stopTime, startTime and timezone using the same parsing as the evaluator (extract a shared private helper rather than duplicating it). Return the earliest of each.
5. EnvironmentController: @GetMapping("/{environmentId}/cost") @PreAuthorize("isAuthenticated()"). It calls securityService.assertCanView(environmentId) (E03-T01) and then the service. VIEWER level is enough because cost is read-only information.
6. Keep all CostManagementController endpoints ADMIN-only; this endpoint is the only scoped door.

**Acceptance criteria**

- Given a USER with an environment-scope grant on env A, when they GET /api/v1/environments/A/cost on the 7th, then monthToDateEstimated = the sum of the 1st-6th snapshots + today's live estimate, and daily has up to 30 points.
- Given a user with only a GROUP grant on group G1 of env A, then scope=GROUPS, the totals include only G1's VMs, and actual/forecast are null.
- Given a user with no access to env B, when they GET /api/v1/environments/B/cost, then 404 (assertCanView), with no cost data in the body.
- Given env A has a weekday 19:00 stop rule in Asia/Kolkata, then scheduleStatus.nextStop is the next weekday 19:00 IST as an ISO instant.

**Tests**

- EnvironmentCostServiceTest (Mockito): MTD = complete days + partial; actual coverage; group scoping drops actuals; forecast run-rate; the previous-month comparison is null when there is no data.
- AutomationRuleService test: nextScheduledFirings for weekday rules, timezone, and no rules -> empty.
- EnvironmentCostControllerSecurityTest (SecuredWebTestBase): ADMIN any env 200; ENV_ADMIN with an ADMIN grant 200; USER and VIEWER with a grant 200; no grant 404; anonymous 401.

**Notes:** The live partial-day estimate reuses E08-T04's batched seed query, so the cost is one estimateCosts call per request for one environment's VMs.

#### E18-T02 — Add a 'my environments cost' endpoint for the My Account panel

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G7 · **Depends on:** E18-T01

Owners and members want one place that lists the month-to-date cost and trend of every environment they can see (G7).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentCostService.java` (modify) — getMyEnvironmentsCost(userId)
- `src/main/java/com/tcgdigital/vmcontrol/dto/MyEnvironmentCostDTO.java` (new) — environmentId, name, myLevel, isOwner, monthToDateEstimated, changePercent, sparkline[14], runningVmCount, totalVmCount, scheduleSummary, leaseEndsAt
- `src/main/java/com/tcgdigital/vmcontrol/controller/UserController.java` (modify) — GET /api/v1/users/me/cost
- `src/main/java/com/tcgdigital/vmcontrol/repository/CostDailySnapshotRepository.java` (modify) — sum by environment ids between dates (batched)
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentCostServiceTest.java` (modify) — list view

**Steps**

1. CostDailySnapshotRepository: add a query that returns (environmentId, snapshotDate, estimatedCost) for environmentId IN :ids AND snapshotDate BETWEEN :from AND :to (one query for all the user's environments).
2. EnvironmentCostService.getMyEnvironmentsCost(userId): list the environments the user can view. Admins get all active environments, capped at the 50 highest MTD (add a note in the DTO). Others get the environment ids from environmentAccessService.getAccessForUser(userId), environment and group scope. Mark isOwner when the effective environment level is ADMIN (securityService.effectiveEnvLevel), not via the alphabetical query flagged in H6. For each environment, MTD and the 14-day sparkline come from snapshots only (no live estimate in the list, so the call stays one query); runningVmCount/totalVmCount come from VmRepository.countVmsGroupedByEnvironment(environmentIds, VmStatus.RUNNING) (one query).
3. Group-only grants: show the environment with scope=GROUPS and MTD=null with the hint 'Cost is shown per environment; you have access to some groups only'. That avoids leaking environment-level totals.
4. UserController: @GetMapping("/me/cost") returning that list, with @Operation docs like the neighbouring /me/profile.

**Acceptance criteria**

- Given a user who owns env A and is a VIEWER on env B, when they GET /api/v1/users/me/cost, then both are listed, A with isOwner=true, each with MTD and a 14-point sparkline.
- Given a group-only grant on env C, then C is listed with MTD null and the hint.
- Given 10 environments, then the endpoint runs a constant number of queries (no per-environment query).

**Tests**

- EnvironmentCostServiceTest: owner flag from the effective level, group-only masking, batched query (verify the repository is called once).
- Security test: anonymous 401; any authenticated user gets only their own list.

#### E18-T03 — Show a cost card on the environment detail page

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G7 · **Depends on:** E18-T01, E02, E01

Month-to-date cost, trend and schedule/lease status belong where people decide to start or stop (G7).

**Files**

- `src/main/resources/static/js/features/environments.js` (modify) — buildDetailHtml (line ~558) adds a cost card; async load after the detail renders; sparkline via ECharts
- `src/main/resources/static/css/features/environments.css` (modify) — cost card styles
- `src/main/resources/static/js/config.js` (modify) — API.environments.cost(id), API.users.myCost
- `src/main/resources/static/dist/app.js` (modify) — regenerated by build-frontend.ps1 (never hand-edit)

**Steps**

1. config.js: add environments.cost: (id) => `${API_BASE_URL}/environments/${encodeURIComponent(id)}/cost` and users.myCost: `${API_BASE_URL}/users/me/cost`.
2. environments.js loadDetail(params): after the detail renders, fetch the cost with a ContentRouter.token() guard (E02) and render into a placeholder <section id="env-cost-card"> added by buildDetailHtml. Use the loading, error and retry pattern from my-account.js (loadingHtml/errorHtml).
3. Card content (built with Utils.html): MTD estimated (large) with the change vs the same days last month; 'Actual (n days)' when actualDays > 0; run-rate forecast labelled 'If the current rate continues'; a 30-day sparkline (ECharts line, disposed on ContentRouter.onLeave); top 5 VMs; a schedule line ('Stops weekdays 19:00 · next stop in 3h') or 'No schedule — set one up' linking to the automation rules page; a lease line from E15 ('Lease ends in 2 days'); savings MTD ('Saved $X this month', linking to #/savings for admins). For scope=GROUPS, show 'Cost for your groups only' and hide the trend.
4. Keep the card compact: below 768px it is full width above the groups list.
5. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given a member opens #/environments/A, then a cost card shows the MTD cost, trend, schedule and lease status without blocking the rest of the page.
- Given the cost request fails, then the card shows an error with Retry and the rest of the page is unaffected.
- Given the user navigates away before the cost loads, then nothing is rendered into the next page.

**Tests**

- Manual checklist (1440px and 390px; FULL vs GROUPS scope; no schedule; lease present). If the test/ Playwright harness can reach the page, add a smoke spec with a stubbed /cost response.

#### E18-T04 — Add a Cost tab to My Account listing my environments' month-to-date cost

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G7 · **Depends on:** E18-T02, E18-T03

Owners need a cross-environment view of what they are responsible for, in the panel they already use (G7).

**Files**

- `src/main/resources/static/js/features/my-account.js` (modify) — TABS adds ['cost','Cost']; load('cost', Config.API.users.myCost); costHtml() renders rows with sparklines
- `src/main/resources/static/css/features/my-account.css` (modify) — cost list rows
- `src/main/resources/static/dist/app.js` (modify) — regenerated by build-frontend.ps1 (never hand-edit)

**Steps**

1. my-account.js: append ['cost', 'Cost'] to TABS (line 17). Load it lazily on first switch to the tab via the existing load(key, url) helper, keeping its stale-response guard.
2. costHtml(): owners first, then by MTD descending. Each row: the environment name linking to #/environments/<id>, an 'Owner' badge, MTD, change %, a tiny inline SVG sparkline (no ECharts in the slide-out; build the polyline from the 14 values) with an aria-label summarising the trend, running/total VMs, and the schedule/lease summary. Group-only rows show the hint text.
3. Empty state: 'You don't have access to any environments yet' with a link to request access (the existing request flow).
4. Keep the existing keyboard tab pattern (onTabKeydown) working with the new tab.
5. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given a user with two environments, when they open My Account > Cost, then both are listed with MTD and a sparkline, and owners are listed first.
- Given the Cost tab request fails, then the tab shows the panel's standard error with Retry, and the other tabs still work.
- Given keyboard navigation, then ArrowRight from Activity moves focus to Cost.

**Tests**

- Manual checklist (keyboard tabs, empty state, group-only hint, 390px).

## E19

### Schedule upgrades — Now

Schedule rules support several windows, holiday calendars and one-off overrides, show their next runs before saving, and can be pre-filled from each environment's real usage pattern.

Sources: G8 · 5 tasks · 9 days

#### E19-T01 — Support multiple time windows per schedule rule

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G8 · **Depends on:** E06-T05

G8: multiple windows per rule (e.g. weekdays 08:00-20:00 and Saturday 10:00-14:00). Today a rule has one days_of_week/start_time/stop_time set on automation_rule (V15).

**Files**

- `src/main/resources/db/migration/V<next>__automation_rule_windows.sql` (new) — automation_rule_window table + copy existing schedule columns
- `src/main/java/com/tcgdigital/vmcontrol/model/AutomationRuleWindow.java` (new) — entity: windowId, rule, daysOfWeek, startTime, stopTime, lastStartFiredOn, lastStopFiredOn, position
- `src/main/java/com/tcgdigital/vmcontrol/model/AutomationRule.java` (modify) — @OneToMany(mappedBy='rule', cascade ALL, orphanRemoval) windows
- `src/main/java/com/tcgdigital/vmcontrol/dto/AutomationRuleRequestDTO.java` (modify) — List<WindowDTO> windows (legacy single fields still accepted)
- `src/main/java/com/tcgdigital/vmcontrol/dto/AutomationRuleDTO.java` (modify) — windows[]
- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — validate/apply windows; evaluate per window
- `src/main/resources/static/js/features/automation-rules.js` (modify) — window list editor in buildModalHtml (L446-584) and submitForm
- `src/test/java/com/tcgdigital/vmcontrol/service/AutomationRuleWindowTest.java` (new) — multi-window evaluation and migration

**Steps**

1. Migration (next free V): CREATE TABLE automation_rule_window(window_id VARCHAR(36) PK, rule_id VARCHAR(36) NOT NULL FK → automation_rule ON DELETE CASCADE, position INT NOT NULL, days_of_week VARCHAR(30) NOT NULL, start_time VARCHAR(5) NULL, stop_time VARCHAR(5) NULL, last_start_fired_on DATE NULL, last_stop_fired_on DATE NULL, INDEX(rule_id)). INSERT one window per existing SCHEDULE rule from its days_of_week/start_time/stop_time/last_*_fired_on, generating window_id with UUID() (MySQL). Keep the old columns (nullable) for one release; stop writing them.
2. Request DTO: `windows` list (1..7 entries) each {daysOfWeek, startTime, stopTime}; when `windows` is absent but the legacy fields are present, convert them into one window (backward compatible API). Validation per window reuses E06-T04 day/time checks; windows on the same day must not overlap and a window may omit start or stop (stop-only rules).
3. Evaluation (E06-T03 code): iterate rule.getWindows(); call ScheduleCalculator.isDue per window and per action using the window's own last_*_fired_on; FireOutcome handling unchanged. Fetch windows with the rule (JOIN FETCH or @BatchSize) inside the per-rule transaction.
4. AutomationRuleDTO: windows[]; triggerSummary in the table shows 'Mon-Fri 08:00-20:00; Sat 10:00-14:00'.
5. UI: replace the single day pills/time inputs with a repeatable window row (days, start, stop, remove) and 'Add window' (max 7); day pills become real buttons with aria-pressed (keyboard accessible).
6. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given an existing single-window rule, after the migration, then it has one window with the same days/times and keeps firing as before.
- Given windows Mon-Fri 08:00-20:00 and Sat 10:00-14:00, then the rule starts at 10:00 and stops at 14:00 on Saturday and does not fire on Sunday.
- Given two overlapping windows on Monday, then saving returns 400 'Windows overlap on MON'.
- Given an API client that still posts daysOfWeek/startTime/stopTime, then the rule is created with one window.

**Tests**

- AutomationRuleWindowTest (AbstractIntegrationTest + fixed Clock): two windows fire independently; per-window fired-on dates; overlap validation; legacy payload.
- FlywayMigrationTest: data copy creates one window per SCHEDULE rule.

#### E19-T02 — Add holiday calendars and let schedule rules skip starts on holidays

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** G8 · **Depends on:** E19-T01, E00-T04

G8: holiday calendars, so environments are not started on public holidays.

**Files**

- `src/main/resources/db/migration/V<next>__holiday_calendars.sql` (new) — holiday_calendar, holiday_date; automation_rule.holiday_calendar_id, holiday_behavior
- `src/main/java/com/tcgdigital/vmcontrol/model/HolidayCalendar.java` (new) — entity: calendarId, name, timezone, description
- `src/main/java/com/tcgdigital/vmcontrol/model/HolidayDate.java` (new) — entity: calendar, date, label
- `src/main/java/com/tcgdigital/vmcontrol/repository/HolidayCalendarRepository.java` (new) — CRUD
- `src/main/java/com/tcgdigital/vmcontrol/repository/HolidayDateRepository.java` (new) — existsByCalendarCalendarIdAndDate, range query
- `src/main/java/com/tcgdigital/vmcontrol/service/HolidayCalendarService.java` (new) — CRUD, CSV import, isHoliday(calendarId, date)
- `src/main/java/com/tcgdigital/vmcontrol/controller/HolidayCalendarController.java` (new) — /api/v1/holiday-calendars (ADMIN write, ADMIN/ENV_ADMIN read)
- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — holiday check before START/STOP
- `src/main/resources/static/js/features/automation-rules.js` (modify) — calendar select + 'Holiday calendars' admin tab
- `src/test/java/com/tcgdigital/vmcontrol/service/HolidayCalendarServiceTest.java` (new) — CRUD, import, holiday evaluation

**Steps**

1. Migration (next free V): holiday_calendar(calendar_id PK, name VARCHAR(100) UNIQUE NOT NULL, description VARCHAR(500), created_by_user_id, created_at, updated_at); holiday_date(holiday_id PK, calendar_id FK ON DELETE CASCADE, holiday_date DATE NOT NULL, label VARCHAR(100), UNIQUE(calendar_id, holiday_date)); ALTER automation_rule ADD holiday_calendar_id VARCHAR(36) NULL (FK ON DELETE SET NULL), ADD holiday_behavior VARCHAR(20) NOT NULL DEFAULT 'SKIP_START' ('SKIP_START' | 'SKIP_ALL').
2. HolidayCalendarService: create/rename/delete calendar; add/remove dates; importCsv(calendarId, MultipartFile) accepting 'yyyy-MM-dd,label' lines (max 500 rows, report invalid lines, skip duplicates); isHoliday(calendarId, LocalDate). Audit changes (add HOLIDAY_CALENDAR_CHANGED to AuditAction).
3. Controller: GET list/detail (ADMIN or ENV_ADMIN), POST/PUT/DELETE and import (ADMIN). Use Paging.of (E00-T04) for date lists.
4. AutomationRuleService: request/DTO fields holidayCalendarId (must exist) and holidayBehavior. In evaluation, the holiday check uses the rule's local date: SKIP_START skips start actions (record SKIPPED reason HOLIDAY with the label and mark the window fired for the day); SKIP_ALL also skips stops. Add HOLIDAY to AutomationRunReason (E06-T04).
5. UI: rule modal gets a 'Holiday calendar' select (None + calendars) and behavior radio ('Don't start on holidays' / 'Skip all actions on holidays'); admins get a 'Holiday calendars' tab on the Automation page listing calendars with add-date and CSV import.
6. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given a rule linked to 'India 2026' containing 2026-10-02 with SKIP_START, when 08:00 on 2026-10-02 comes, then no start operation runs and the rule shows 'Skipped: holiday (Gandhi Jayanti)'; the 20:00 stop still runs.
- Given a CSV with one bad date line, then the import adds the valid dates and reports line 3 as invalid.
- Given an ENV_ADMIN, when they POST a calendar, then 403.

**Tests**

- HolidayCalendarServiceTest: CRUD, CSV import validation/duplicates, isHoliday.
- AutomationRuleScheduleIntegrationTest: holiday skips start, keeps stop; SKIP_ALL skips both.
- HolidayCalendarController authorization test (SecuredWebTestBase).

#### E19-T03 — Add per-rule overrides (skip or snooze a rule for a date range)

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G8 · **Depends on:** E19-T02

G8: overrides and snooze per rule (Instance Scheduler / Turbonomic style), e.g. 'keep running tonight for a release' without disabling and later forgetting to re-enable the rule.

**Files**

- `src/main/resources/db/migration/V<next>__automation_rule_override.sql` (new) — automation_rule_override table
- `src/main/java/com/tcgdigital/vmcontrol/model/AutomationRuleOverride.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/AutomationRuleOverrideRepository.java` (new) — findActive(ruleId, instant), findUpcoming(ruleId)
- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — override CRUD + check in evaluation
- `src/main/java/com/tcgdigital/vmcontrol/controller/AutomationRuleController.java` (modify) — GET/POST/DELETE /{ruleId}/overrides
- `src/main/resources/static/js/features/automation-rules.js` (modify) — Overrides section + 'Skip tonight's stop' quick action
- `src/test/java/com/tcgdigital/vmcontrol/service/AutomationRuleOverrideTest.java` (new) — override evaluation

**Steps**

1. Migration (next free V): automation_rule_override(override_id PK, rule_id FK ON DELETE CASCADE, starts_at TIMESTAMP NOT NULL, ends_at TIMESTAMP NOT NULL, skip_action VARCHAR(10) NOT NULL ('START'|'STOP'|'ALL'), reason VARCHAR(255) NOT NULL, created_by_user_id VARCHAR(36) NOT NULL, created_at; INDEX(rule_id, ends_at)).
2. Endpoints on AutomationRuleController: GET /api/v1/automation-rules/{ruleId}/overrides (upcoming + active), POST {startsAt, endsAt, skipAction, reason} (ends after starts, max 30 days, reason required), DELETE /{overrideId}. Allowed for ADMIN/ENV_ADMIN (existing @PreAuthorize) and, for POST with endsAt <= now + 24h, any USER on the rule's environment (a 'snooze'), checked via SecurityService.canOperateInEnvironment.
3. Evaluation: before firing an action, if an override covering the due instant exists for that action, record SKIPPED reason OVERRIDE ('Skipped by override: <reason> (<user>)') and mark the window fired for the day. Add OVERRIDE to AutomationRunReason. Audit override create/delete via logAutomationRuleUpdated.
4. UI: rule row action 'Skip next stop' (creates STOP override from the next stop's due time ± 1 minute, asks for a reason) and an Overrides section in the rule modal listing upcoming overrides with delete.
5. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given a STOP override for tonight 19:00-23:00 with reason 'release', when 20:00 comes, then the environment is not stopped and the rule shows 'Skipped by override: release (Priya)'; tomorrow's stop runs normally.
- Given a USER posts a 3-day override, then 403; a 12-hour one is accepted.
- Given an override whose end precedes its start, then 400.

**Tests**

- AutomationRuleOverrideTest (AbstractIntegrationTest, fixed Clock): action-specific skip, ALL, expiry, authorization.

#### E19-T04 — Preview the next runs of a rule in the rule modal and show 'Next run' in the rules table

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G8 · **Depends on:** E19-T03

G8: preview of next runs in the rule modal; the review also asks to show the next run time on the Automation page.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/ScheduleCalculator.java` (modify) — nextRuns(definition, from, limit, holidayLookup, overrides)
- `src/main/java/com/tcgdigital/vmcontrol/dto/ScheduleRunPreviewDTO.java` (new) — at (ISO with offset), action, windowIndex, skipped, skipReason
- `src/main/java/com/tcgdigital/vmcontrol/controller/AutomationRuleController.java` (modify) — POST /api/v1/automation-rules/preview (draft body), GET /{ruleId}/next-runs
- `src/main/java/com/tcgdigital/vmcontrol/dto/AutomationRuleDTO.java` (modify) — nextRunAt, nextRunAction
- `src/main/resources/static/js/features/automation-rules.js` (modify) — preview list in modal (debounced), Next run column
- `src/test/java/com/tcgdigital/vmcontrol/service/ScheduleCalculatorTest.java` (modify) — nextRuns cases

**Steps**

1. ScheduleCalculator.nextRuns: iterate days from 'from' in the rule timezone for up to 60 days, for each window/action produce dueInstant (same DST handling as isDue), mark skipped entries for holidays (holiday behavior) and overrides; return the first `limit` (default 10, max 20) in time order.
2. POST /preview takes an AutomationRuleRequestDTO (validated exactly like create but not saved) and returns List<ScheduleRunPreviewDTO>; GET /{ruleId}/next-runs for saved rules. Same @PreAuthorize as the controller.
3. AutomationRuleDTO gets nextRunAt/nextRunAction (first non-skipped run) computed in toDto (one calculator call per rule; fine at current rule counts).
4. UI: the modal shows 'Next runs' (up to 10 rows: 'Mon 13 Oct, 20:00 IST — Stop'; skipped ones struck through with the reason), refreshed 400 ms after any change to windows/timezone/holiday fields; the table gets a 'Next run' column.
5. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given a draft Mon-Fri 20:00 stop rule in Asia/Kolkata on a Friday at 21:00, then the preview's first entry is Monday 20:00 IST.
- Given a holiday on Monday with SKIP_ALL, then Monday's entry is shown as skipped with the holiday label and the next real run is Tuesday.
- Given the DST change in Europe/London, then a 02:30 entry on that day is shown at 03:30.

**Tests**

- ScheduleCalculatorTest: nextRuns ordering across windows, holidays, overrides, DST, limit.
- AutomationRuleController preview test: invalid draft returns 400 with the same messages as create.

#### E19-T05 — Suggest a schedule from each environment's usage pattern

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** G8 · **Depends on:** E19-T04, E16-T02

G8: a suggested schedule from each environment's usage pattern, using vm_state_history runtime and vm_metric_sample activity.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/ScheduleSuggestionService.java` (new) — suggest(environmentId, ZoneId, weeks)
- `src/main/java/com/tcgdigital/vmcontrol/dto/ScheduleSuggestionDTO.java` (new) — windows[], activityByHourOfWeek[168], confidence, currentRunningHoursPerWeek, suggestedHoursPerWeek, estimatedWeeklySaving
- `src/main/java/com/tcgdigital/vmcontrol/controller/AutomationRuleController.java` (modify) — GET /api/v1/automation-rules/suggestion?environmentId=&timezone=&weeks=
- `src/main/resources/static/js/features/automation-rules.js` (modify) — 'Suggest from usage' button prefilling windows, with a 7x24 heat strip
- `src/test/java/com/tcgdigital/vmcontrol/service/ScheduleSuggestionServiceTest.java` (new) — pattern cases

**Steps**

1. Data: for active VMs in the environment, load transitions with VmStateHistoryRepository.findByVmVmIdInAndChangedAtBetweenOrderByVmVmIdAscChangedAtAsc (L74-75) for the last `weeks` (default 4, max 8) plus the seed state via findTopByVmVmIdAndChangedAtLessThanOrderByChangedAtDesc (L81); load activity from vm_metric_sample (sample counted active when CPU >= 5% or network above the E16 idle budget) with findByVmVmIdInAndSampleTimeBetweenOrderBySampleTimeAsc. Bound by weeks × VMs; read only.
2. Build a 168-slot hour-of-week matrix in the requested timezone: activity[h] = share of weeks in which any VM was active (metrics) in that hour; fall back to 'running' (state history) where no metrics exist and flag lower confidence.
3. Propose windows: hours with activity >= 0.25 are 'on'; fill single-hour gaps; per day take the first and last on hour, pad by 30 minutes (start 30 min early, stop 30 min after), round to :00/:30; merge days with identical windows into one window (Mon-Fri). Days with no activity get no window (the rule stops and does not start).
4. Compute currentRunningHoursPerWeek from state history (reuse VmCostCalculator.computeRuntimeHours, L31), suggestedHoursPerWeek from the windows, and estimatedWeeklySaving using hourly rates the same way as E16-T03 (inventory snapshot instance type + PricingReferenceService.lookupHourlyRate).
5. Endpoint: GET suggestion for one environment (ADMIN/ENV_ADMIN as the controller; also assertCanView on that environment). Returns 422-style 400 'Not enough history (need 2 weeks)' when less than 14 days of data.
6. UI: 'Suggest from usage' button next to the windows editor (enabled once an environment is chosen) shows the heat strip and proposed windows with 'Use these windows'; applying fills the editor and refreshes the E19-T04 preview. Nothing is saved until the user clicks Save.
7. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given 4 weeks where the environment was used 09:00-18:00 Mon-Fri and never on weekends, then the suggestion is one window Mon-Fri 08:30-18:30 and the projected saving is shown.
- Given only 5 days of history, then the API returns 400 'Not enough history (need 2 weeks)'.
- Given the user clicks 'Use these windows', then the modal windows and preview update but no rule is saved until Save.

**Tests**

- ScheduleSuggestionServiceTest: synthetic histories for weekday office hours, 24x7 usage (no stop suggested), sparse usage, timezone shift, metrics-missing fallback.

**Notes:** Uses the same activity thresholds as the E16 idle definition so idle stop and suggested schedules agree.

## E20

### Budgets that act — Next

Admins and environment owners set a monthly budget per environment or team, get alerts at 50/75/90/100% and on forecast overrun, and can attach an approval-gated freeze (block starts) or stop action, optionally mirrored to AWS Budgets.

Sources: G9 · 6 tasks · 9.5 days

#### E20-T01 — Add budget entities and a CRUD API scoped to environment owners and admins

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G9 · **Depends on:** E18-T01, E08-T03, E03-T01, E00-T02

There is no budget concept today; G9 needs a monthly budget per environment and per team with thresholds and an optional action.

**Files**

- `src/main/resources/db/migration/V<next>__add_budgets.sql` (new) — budget and budget_alert tables (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/Budget.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/model/BudgetScopeType.java` (new) — ENVIRONMENT, TEAM
- `src/main/java/com/tcgdigital/vmcontrol/model/BudgetActionType.java` (new) — NONE, FREEZE_STARTS, STOP_ENVIRONMENT
- `src/main/java/com/tcgdigital/vmcontrol/model/BudgetAlert.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/BudgetRepository.java` (new) — finders by scope, enabled
- `src/main/java/com/tcgdigital/vmcontrol/repository/BudgetAlertRepository.java` (new) — exists by (budget, month, threshold, kind)
- `src/main/java/com/tcgdigital/vmcontrol/dto/BudgetDTO.java` (new) — response incl. current MTD, percent, forecast
- `src/main/java/com/tcgdigital/vmcontrol/dto/BudgetRequestDTO.java` (new) — validated create/update body
- `src/main/java/com/tcgdigital/vmcontrol/service/BudgetService.java` (new) — CRUD + authorization + spend calculation
- `src/main/java/com/tcgdigital/vmcontrol/controller/BudgetController.java` (new) — /api/v1/budgets
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — BUDGET_CREATED, BUDGET_UPDATED, BUDGET_DELETED
- `src/test/java/com/tcgdigital/vmcontrol/service/BudgetServiceTest.java` (new) — spend + validation
- `src/test/java/com/tcgdigital/vmcontrol/controller/BudgetControllerSecurityTest.java` (new) — role matrix

**Steps**

1. Migration: budget (budget_id VARCHAR(36) PK, scope_type VARCHAR(20) NOT NULL, scope_id VARCHAR(100) NOT NULL (an environment id, or the team name as TeamResolver returns it), monthly_amount DECIMAL(12,2) NOT NULL, thresholds VARCHAR(50) NOT NULL DEFAULT '50,75,90,100', forecast_alert BOOLEAN NOT NULL DEFAULT TRUE, action_type VARCHAR(20) NOT NULL DEFAULT 'NONE', enabled BOOLEAN NOT NULL DEFAULT TRUE, aws_budget_name VARCHAR(100) NULL, created_by_user_id VARCHAR(36) NOT NULL, created_at, updated_at, UNIQUE(scope_type, scope_id)). Also budget_alert (alert_id PK, budget_id FK ON DELETE CASCADE, budget_month CHAR(7) NOT NULL, threshold INT NOT NULL, kind VARCHAR(10) NOT NULL (ACTUAL|FORECAST), spend DECIMAL(12,2), forecast DECIMAL(12,2), fired_at TIMESTAMP NOT NULL, UNIQUE(budget_id, budget_month, threshold, kind)).
2. Entities, enums and repositories as listed.
3. BudgetService.currentSpend(Budget, YearMonth): per day, take actual_cost when present, else estimated_cost, from cost_daily_snapshot (complete days, E08-T01). For TEAM scope, sum over the active environments whose TeamResolver.resolveTeam(metadata) equals scope_id. forecastMonthEnd uses the same run-rate as E18-T01: extract it from EnvironmentCostService into a shared package-private helper CostRunRate.forecast(dailyValues, daysRemaining) and reuse it.
4. Authorization: ADMIN can manage any budget. For ENVIRONMENT budgets, ENV_ADMIN/USER need an effective ADMIN level on that environment (securityService.effectiveEnvLevel(envId) == ADMIN). TEAM budgets are ADMIN only. Anyone who can view the environment (assertCanView) can read its budget.
5. BudgetRequestDTO validation: monthlyAmount > 0 and <= 10,000,000; thresholds are 1..4 unique integers 1..200, sorted on save; actionType may be FREEZE_STARTS or STOP_ENVIRONMENT only for ENVIRONMENT scope.
6. BudgetController: GET /api/v1/budgets (an admin sees all; others see the budgets of environments they can view), GET /{id}, POST, PUT /{id}, DELETE /{id}, and GET /api/v1/environments/{envId}/budget (via EnvironmentController or BudgetController with an absolute path). Audit create, update and delete with the new AuditAction values via auditService.logEnvironmentAction.

**Acceptance criteria**

- Given an environment owner of env A, when they POST a $500 budget for A, then 201 and GET shows percentUsed computed from actual-or-estimated daily spend.
- Given a USER with only VIEWER on A, when they POST a budget for A, then 403; GET /api/v1/environments/A/budget returns 200.
- Given a team budget, when an ENV_ADMIN posts it, then 403.
- Given thresholds '90,50,50', then 400.

**Tests**

- BudgetServiceTest: actual-preferred daily sum; team aggregation across environments; validation rules.
- BudgetControllerSecurityTest (SecuredWebTestBase): ADMIN/owner/member/viewer/anonymous matrix.

#### E20-T02 — Evaluate budgets daily and alert owners at each threshold and on forecast overrun

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G9 · **Depends on:** E20-T01, E06-T01, E11

Budgets only help if owners hear about them: alerts at 50/75/90/100% and a forecast-based alert (G9).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/BudgetEvaluationService.java` (new) — evaluateAll(): thresholds + forecast, idempotent per month
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/BudgetEvaluationScheduler.java` (new) — daily after actuals, runLocked
- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — BUDGET_THRESHOLD, BUDGET_FORECAST
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyBudgetThreshold / notifyBudgetForecast to environment reviewers (owners) + admins
- `src/main/java/com/tcgdigital/vmcontrol/service/EmailTemplates.java` (modify) — budgetAlert template
- `src/main/resources/application.properties` (modify) — budgets.evaluation.enabled=true, budgets.evaluation.cron, notification.email.budget-alert.enabled=false
- `src/test/java/com/tcgdigital/vmcontrol/service/BudgetEvaluationServiceTest.java` (new) — threshold crossing + idempotency

**Steps**

1. BudgetEvaluationService.evaluateAll(): for each enabled budget, compute spend and forecast (BudgetService, E20-T01) for the current month. For each threshold t with spend >= amount x t/100 and no budget_alert row (budget, month, t, ACTUAL), insert the row and notify. If forecast_alert is set and forecast > amount with no FORECAST row for the month (threshold=100), insert it and notify. The unique key makes a re-run safe; catch DataIntegrityViolationException as 'already fired'.
2. NotificationService: add notifyBudgetThreshold(String environmentId, String environmentName, int threshold, BigDecimal spend, BigDecimal amount) and notifyBudgetForecast(...). Recipients: reuse the private resolveEnvironmentReviewers(environmentId) (environment ADMIN-grant holders, admins, administering env admins). For TEAM budgets, add notifyTeamBudget(...) that sends to admins and to the reviewers of each environment in the team, de-duplicated. Use entityType 'ENVIRONMENT' or 'BUDGET'. Email via EmailService when notification.email.enabled and notification.email.budget-alert.enabled are both true (follow the existing per-type toggle pattern), using a new EmailTemplates.budgetAlert(...).
3. Wrap each notification in try/catch so one failure never stops the evaluation (the low-severity item says notification failures must not roll back callers).
4. BudgetEvaluationScheduler: @ConditionalOnProperty("budgets.evaluation.enabled", matchIfMissing=true), @Scheduled(cron="${budgets.evaluation.cron:0 30 5 * * *}", zone="UTC") (after the 05:00 actuals), body lockService.runLocked("budget_evaluation", Duration.ofMinutes(10), evaluationService::evaluateAll). It writes only app state and notifications, so on by default is acceptable; email stays off by default.
5. Add NotificationType values; make sure the notification bell renders unknown types generically (check notification-bell.js and add icons for the two new types).

**Acceptance criteria**

- Given a $1,000 budget and MTD spend reaching $760, when evaluation runs, then one BUDGET_THRESHOLD notification for 75% is sent to the owners (50% was sent earlier) and none for 90%.
- Given evaluation runs twice the same day, then no duplicate notifications are sent.
- Given MTD $400 on the 10th of a 30-day month at $40/day, then forecast is $1,200 > $1,000 and one BUDGET_FORECAST alert fires.
- Given a new month starts, then thresholds can fire again.

**Tests**

- BudgetEvaluationServiceTest (Mockito): crossing multiple thresholds at once fires each once; idempotency; forecast alert; team recipients de-duplicated; a notification exception does not abort the loop.
- NotificationServiceTest: recipients for notifyBudgetThreshold are the reviewers plus admins.

#### E20-T03 — Add approval-gated budget actions and the start freeze

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G9 · **Depends on:** E20-T02, E05, E17-T01

G9 asks for an optional freeze (block starts) when a budget is exhausted, and it must need approval before it takes effect.

**Files**

- `src/main/resources/db/migration/V<next>__add_budget_action_request.sql` (new) — budget_action_request table + environment.start_frozen_until (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/BudgetActionRequest.java` (new) — entity: budget, month, action, status PENDING|APPROVED|REJECTED|EXPIRED, requested_at, decided_by, decided_at, reason
- `src/main/java/com/tcgdigital/vmcontrol/repository/BudgetActionRequestRepository.java` (new) — pending by environment
- `src/main/java/com/tcgdigital/vmcontrol/model/Environment.java` (modify) — startFrozenUntil Timestamp
- `src/main/java/com/tcgdigital/vmcontrol/service/BudgetActionService.java` (new) — raise on 100%, approve, reject, lift freeze
- `src/main/java/com/tcgdigital/vmcontrol/service/BudgetEvaluationService.java` (modify) — raise an action request when 100% crossed and action != NONE
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — reject START/RESTART while frozen unless ADMIN override
- `src/main/java/com/tcgdigital/vmcontrol/controller/BudgetController.java` (modify) — GET /actions, POST /actions/{id}/approve, /reject, POST /environments/{envId}/freeze/lift
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — BUDGET_ACTION_REQUESTED, BUDGET_ACTION_APPROVED, BUDGET_ACTION_REJECTED, BUDGET_FREEZE_LIFTED, BUDGET_FREEZE_OVERRIDDEN
- `src/main/resources/application.properties` (modify) — budgets.actions.enabled=false
- `src/test/java/com/tcgdigital/vmcontrol/service/BudgetActionServiceTest.java` (new) — workflow
- `src/test/java/com/tcgdigital/vmcontrol/service/VmOperationsServiceTest.java` (modify) — frozen start rejected

**Steps**

1. Migration: budget_action_request (request_id PK, budget_id FK, budget_month CHAR(7), action_type VARCHAR(20), status VARCHAR(20), requested_at, decided_by_user_id NULL, decided_at NULL, decision_reason VARCHAR(500) NULL, UNIQUE(budget_id, budget_month, action_type)). Also ALTER TABLE environment ADD COLUMN start_frozen_until TIMESTAMP NULL.
2. BudgetEvaluationService: when the 100% ACTUAL alert fires, budgets.actions.enabled=true and the budget's action_type != NONE, call budgetActionService.raise(budget, month). raise() inserts a PENDING request (unique per month), notifies reviewers with the new NotificationType BUDGET_ACTION_PENDING (entityType 'BUDGET_ACTION', entityId=requestId) and audits it. It never acts by itself.
3. approve(requestId, userId, reason): the caller must be ADMIN or have an effective ADMIN level on the environment, and must not be the user who created the budget unless ADMIN (four-eyes where possible). Set APPROVED. For FREEZE_STARTS, set environment.startFrozenUntil = the first instant of next month (CostDayBoundary zone) and notify the environment members via broadcastToEnvironment-style fan-out (a new public NotificationService method). reject() sets REJECTED. Unapproved requests expire at month end (set EXPIRED in the evaluation job).
4. liftFreeze(envId, userId, reason): same authorization; clears startFrozenUntil; audits.
5. VmOperationsService.startOperation: at the top, if operationType is START or RESTART and the environment's startFrozenUntil is after now, then for non-admins throw ValidationException("Starts are frozen until <date> because the environment's budget is exhausted. Ask an owner to lift the freeze."). For ADMIN, allow it but audit BUDGET_FREEZE_OVERRIDDEN. STOP is always allowed. Automation-fired starts (AutomationRuleService) hit the same check, so their existing ValidationException catch records SKIPPED.
6. Controller endpoints as listed, each with @PreAuthorize("isAuthenticated()") plus service-level checks.

**Acceptance criteria**

- Given budgets.actions.enabled=false, when a budget passes 100%, then only alerts are sent and no action request is created.
- Given actions are enabled, FREEZE_STARTS is configured and 100% is crossed, then a PENDING request exists and nothing is frozen until an owner approves.
- Given an approved freeze, when a USER starts a VM in that environment, then 400 with the freeze message; a STOP still succeeds; an ADMIN start succeeds and is audited as an override.
- Given a SCHEDULE automation start during a freeze, then the rule's last run is SKIPPED with the freeze reason.

**Tests**

- BudgetActionServiceTest: raise is idempotent per month; approval authorization incl. four-eyes; freeze-until computed at next month start; expiry.
- VmOperationsServiceTest: frozen START rejected for USER, allowed and audited for ADMIN, STOP unaffected.

**Notes:** Behind budgets.actions.enabled=false by default, because it changes what users can do.

#### E20-T04 — Execute the approved 'stop environment' budget action through the normal operation pipeline

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** high · **Sources:** G9 · **Depends on:** E20-T03

G9's second action is to stop an over-budget environment, again only after approval.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/BudgetActionService.java` (modify) — on approval of STOP_ENVIRONMENT start a STOP operation with trigger BUDGET
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — reuse notifyStopEnvironment (line 542) for the pre-stop notice
- `src/test/java/com/tcgdigital/vmcontrol/service/BudgetActionServiceTest.java` (modify) — stop path

**Steps**

1. In approve(): for STOP_ENVIRONMENT, build a StartOperationDTO(operationType=STOP, reason="Budget exhausted: <budget> (approved by <name>)", skipAlreadyInTargetState=true) and call vmOperationsService.startOperation(envId, approverUserId, dto, OperationTriggerSource.BUDGET) (E17-T01 overload). Stop order follows the existing dependency handling.
2. If the environment is locked by someone else (LockAlreadyHeldException), keep the request APPROVED, record decision_reason 'Stop pending: environment locked by <user>', notify the approver, and retry in the next evaluation run (BudgetEvaluationService picks up APPROVED STOP requests without an execution id). Add execution_id VARCHAR(36) NULL to budget_action_request in this task's migration (next free V number at implementation time).
3. Before stopping, call notificationService.notifyStopEnvironment(envId, envName, approverUserId, reason) so members are told.
4. Ledger effect: E17 maps BUDGET to cause OTHER. No change needed; mention it in the PR.

**Acceptance criteria**

- Given an approved STOP_ENVIRONMENT action, then a STOP operation runs with trigger_source=BUDGET and members receive the stop notice.
- Given the environment is locked by another user, then no stop runs, the approver is told why, and the next evaluation retries.

**Tests**

- BudgetActionServiceTest: the stop path calls startOperation with BUDGET; LockAlreadyHeldException leaves it pending and retried; the execution id is stored.

**Notes:** Still behind budgets.actions.enabled=false.

#### E20-T05 — Build the Budgets UI: admin list, budget form, environment budget bar and action approvals

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** G9 · **Depends on:** E20-T04, E18-T03, E13, E02, E01

Owners need to set budgets, see progress and approve actions where they work (G9).

**Files**

- `src/main/resources/static/js/features/budgets.js` (new) — Budgets page (list, create/edit slide-out, pending actions)
- `src/main/resources/static/css/features/budgets.css` (new) — styles
- `src/main/resources/static/js/features/environments.js` (modify) — budget progress bar in the E18 cost card; freeze banner; 'Set budget' for owners
- `src/main/resources/static/js/core/router.js` (modify) — 'budgets' route
- `src/main/resources/static/index.html` (modify) — sidebar 'Budgets' (admins and env admins); css/js tags
- `src/main/resources/static/js/config.js` (modify) — API.budgets.*
- `src/main/resources/static/js/ui/notification-bell.js` (modify) — BUDGET_* icons; BUDGET_ACTION_PENDING links to #/budgets
- `build-frontend.ps1` (modify) — bundle budgets.js/css

**Steps**

1. budgets.js: a table of budgets (scope, amount, MTD, % bar coloured at 75/90/100, forecast, action). Create/edit happens in a slide-out using the accessible dialog pattern from E13 if it has landed. Fields: scope (environment picker limited to environments the user owns; team for admins), amount, thresholds chips, forecast alert toggle, and action select (disabled with a note when the server reports budgets.actions.enabled=false; expose that flag via GET /api/v1/budgets/config).
2. Pending actions section: approve/reject buttons with a reason textarea and a type-to-confirm for STOP_ENVIRONMENT (follow the E13 destructive-confirm policy).
3. Environment page (E18-T03 card): a budget bar ('$640 of $1,000 · forecast $1,150'). When startFrozenUntil is set, show a banner 'Starts are frozen until <date> (budget exhausted)' with 'Lift freeze' for owners. Start buttons stay enabled for admins and show a tooltip for others.
4. Use Utils.html for every interpolation, ContentRouter.onLeave for teardown, and allSettled per panel.
5. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given an owner, when they create a budget from the environment page, then the bar appears with current spend.
- Given a pending STOP action, when an owner approves it after typing the environment name, then the request becomes APPROVED and the operation starts.
- Given a frozen environment, then members see the banner and owners see 'Lift freeze'.

**Tests**

- Manual checklist (owner vs viewer, actions disabled flag, 390px, keyboard). Playwright smoke spec if the test/ harness covers the page.

#### E20-T06 — Optionally mirror budgets to AWS Budgets

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G9 · **Depends on:** E20-T05, E00-T03, E08-T02

G9 says to consider AWS Budgets: mirroring gives AWS-native alerting even when the app is down. It is optional and off by default because it changes AWS state.

**Files**

- `build.gradle` (modify) — add software.amazon.awssdk:budgets
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsBudgetSyncService.java` (new) — create/update/delete AWS budget per app budget
- `src/main/java/com/tcgdigital/vmcontrol/service/BudgetService.java` (modify) — call sync after commit (AfterCommit.run)
- `src/main/resources/application.properties` (modify) — budgets.aws-sync.enabled=false, budgets.aws-sync.account-id
- `docs/aws-iam-permissions.md` (modify) — budgets:ViewBudget, budgets:ModifyBudget
- `src/test/java/com/tcgdigital/vmcontrol/service/AwsBudgetSyncServiceTest.java` (new) — request mapping

**Steps**

1. Add the budgets SDK module. AwsBudgetSyncService uses BudgetsClient (global endpoint, us-east-1) with the existing static credentials.
2. upsert(Budget): name = 'tcg-' + scope + '-' + scopeId (truncated to 100 chars, stored in aws_budget_name), BudgetType COST, TimeUnit MONTHLY, BudgetLimit = monthly_amount USD, CostFilters {"TagKeyValue": ["user:tcg:environment$<env name>"]} for ENVIRONMENT or "user:tcg:team$<team>" for TEAM. Use createBudget when aws_budget_name is null, else updateBudget. delete(Budget) calls deleteBudget. No AWS-side notifications (the app routes alerts itself).
3. BudgetService create/update/delete call AfterCommit.run(() -> awsBudgetSyncService.upsert/delete(...)) (E00-T03) when budgets.aws-sync.enabled=true. Failures are logged and shown as 'AWS sync failed' on the budget (add a transient field to BudgetDTO from an in-memory last-error map; no schema change).
4. Document the IAM permissions.

**Acceptance criteria**

- Given aws-sync is disabled (default), when a budget is saved, then no AWS call is made.
- Given it is enabled, when a $500 environment budget is created, then createBudget is called with a monthly COST budget filtered on user:tcg:environment$<name>.
- Given AWS rejects the call, then the app budget is still saved and shows 'AWS sync failed'.

**Tests**

- AwsBudgetSyncServiceTest (mocked BudgetsClient): create vs update vs delete request mapping; the disabled flag makes no calls.

**Notes:** Requires cost allocation tags active (E08-T02 retry, E08-T11 check).

## E21

### Anomaly alerts routed to owners — Next

AWS Cost Anomaly Detection anomalies for each environment (monitored on the tcg:environment cost allocation tag) are ingested, routed to that environment's owners by bell and email, and shown on the environment and admin pages with feedback.

Sources: G10 · 4 tasks · 5.5 days

#### E21-T01 — Map AWS anomaly monitors to environments and optionally provision one tag-based monitor per environment

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G10 · **Depends on:** E08-T02, E08-T11

To route an anomaly to an owner, each anomaly must map to one environment. A CUSTOM monitor filtered on tcg:environment=<name> makes that mapping deterministic (G10).

**Files**

- `src/main/resources/db/migration/V<next>__add_cost_anomaly_monitor.sql` (new) — cost_anomaly_monitor(monitor_arn PK, environment_id FK NULL, monitor_name, managed BOOLEAN, created_at) (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/CostAnomalyMonitor.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/CostAnomalyMonitorRepository.java` (new) — finders
- `src/main/java/com/tcgdigital/vmcontrol/service/CostAnomalyMonitorService.java` (new) — list AWS monitors (GetAnomalyMonitors, paginated), link/unlink, provision missing (CreateAnomalyMonitor) when enabled
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostAnomalyController.java` (new) — ADMIN: GET /api/v1/cost-anomalies/monitors, POST /monitors/sync, PUT /monitors/{arn}/environment
- `src/main/resources/application.properties` (modify) — cost.anomalies.manage-monitors=false
- `docs/aws-iam-permissions.md` (modify) — ce:GetAnomalyMonitors, ce:CreateAnomalyMonitor, ce:GetAnomalies, ce:ProvideAnomalyFeedback
- `src/test/java/com/tcgdigital/vmcontrol/service/CostAnomalyMonitorServiceTest.java` (new) — mapping + provisioning

**Steps**

1. Migration, entity and repository as listed.
2. CostAnomalyMonitorService uses CostExplorerClientProvider (E08-T02). refreshFromAws(): GetAnomalyMonitors with a nextPageToken loop, upserting rows. Auto-link a monitor to an environment when its MonitorSpecification is a Tags expression with key '<prefix>environment' and exactly one value equal to an environment name (EnvironmentRepository.findByName).
3. provisionMissing() runs only when cost.anomalies.manage-monitors=true. For each active environment without a linked monitor, CreateAnomalyMonitor(MonitorType CUSTOM, MonitorName 'tcg-env-<name>', MonitorSpecification Expression{Tags{Key '<prefix>environment', Values [name], MatchOptions [EQUALS]}}) and store it with managed=true. Stop with a clear log once the account's monitor quota is hit (catch LimitExceededException); the docs state the quota (check the current AWS limit at implementation time).
4. Admin endpoints: list monitors with their linked environment, POST /monitors/sync (refresh + provision if enabled), PUT /monitors/{arn}/environment to link manually (for monitors created by FinOps). All @PreAuthorize("hasRole('ADMIN')").
5. Document the IAM actions and that tag activation (E08-T02) must be complete before tag monitors return data.

**Acceptance criteria**

- Given an existing CUSTOM monitor on tcg:environment=prod-01, when an admin syncs, then it is linked to environment prod-01 automatically.
- Given manage-monitors=false (default), when sync runs, then no CreateAnomalyMonitor call is made.
- Given manage-monitors=true and 3 environments without monitors, then 3 monitors are created and linked.

**Tests**

- CostAnomalyMonitorServiceTest (mocked client): pagination, auto-link parsing, provisioning gated by the flag, quota error handled.

**Notes:** Creating monitors changes AWS state, so it is behind cost.anomalies.manage-monitors=false.

#### E21-T02 — Ingest anomalies from Cost Anomaly Detection on a schedule

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G10 · **Depends on:** E21-T01, E06-T01

Anomalies must be pulled into the app before they can be routed and displayed (G10).

**Files**

- `src/main/resources/db/migration/V<next>__add_cost_anomaly.sql` (new) — cost_anomaly table (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/CostAnomaly.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/CostAnomalyRepository.java` (new) — by environment, unnotified, date range
- `src/main/java/com/tcgdigital/vmcontrol/service/CostAnomalyService.java` (new) — ingest(lookbackDays): GetAnomalies per linked monitor, paginated, upsert
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/CostAnomalyScheduler.java` (new) — every 6h via runLocked
- `src/main/resources/application.properties` (modify) — cost.anomalies.enabled=false, cost.anomalies.cron, cost.anomalies.lookback-days=7
- `src/main/resources/application-cost.properties` (modify) — cost.anomalies.enabled=true
- `src/test/java/com/tcgdigital/vmcontrol/service/CostAnomalyServiceTest.java` (new) — parsing/upsert

**Steps**

1. Migration: cost_anomaly (anomaly_id VARCHAR(100) PK (the AWS AnomalyId), monitor_arn VARCHAR(255), environment_id VARCHAR(36) NULL FK ON DELETE SET NULL, anomaly_start DATE, anomaly_end DATE NULL, total_impact DECIMAL(12,2), total_expected DECIMAL(12,2) NULL, max_score DECIMAL(6,2), root_causes TEXT (JSON: service, region, usageType, linkedAccount), feedback VARCHAR(20) NULL, first_seen_at TIMESTAMP, last_seen_at TIMESTAMP, notified_at TIMESTAMP NULL, INDEX(environment_id, anomaly_start)).
2. CostAnomalyService.ingest(): call GetAnomalies ONCE without MonitorArn (DateInterval start = today - lookbackDays, end = today) with a nextPageToken loop, and map each anomaly to an environment by its monitorArn through CostAnomalyMonitorRepository; anomalies from unknown monitors are stored with environment_id NULL. Upsert by anomalyId; keep notified_at and feedback on update. Store environment_id from the monitor's link. Serialise rootCauses with the app ObjectMapper.
3. Billing: one billed Cost Explorer call per page per run, so about 2-4 calls a day at the default 12-hour cadence. Document this in docs/cost-management-setup.md.
4. CostAnomalyScheduler: @ConditionalOnProperty("cost.anomalies.enabled") (default false, true in the 'cost' profile), @Scheduled(cron="${cost.anomalies.cron:0 0 */12 * * *}", zone="UTC"), lockService.runLocked("cost_anomaly_ingest", Duration.ofMinutes(10), ...).

**Acceptance criteria**

- Given two anomalies returned across two pages for linked monitors, when ingest runs, then two rows exist with the right environment ids and a second run updates rather than duplicates them.
- Given an anomaly for an unlinked monitor, then it is stored with environment_id NULL (visible to admins only).

**Tests**

- CostAnomalyServiceTest (mocked client via CostExplorerClientProvider): pagination, monitor-to-environment mapping, upsert keeps notified_at and feedback.

#### E21-T03 — Route new anomalies to environment owners and accept owner feedback

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G10 · **Depends on:** E21-T02, E11

Alerts are only useful when they reach the person who can act: the environment owner, not a shared inbox (G10).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — COST_ANOMALY
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyCostAnomaly(environmentId, envName, impact, summary, anomalyId)
- `src/main/java/com/tcgdigital/vmcontrol/service/CostAnomalyService.java` (modify) — routeNew(): notify once per anomaly above the threshold; feedback(anomalyId, userId, value)
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostAnomalyController.java` (modify) — POST /api/v1/cost-anomalies/{id}/feedback
- `src/main/resources/application.properties` (modify) — cost.anomalies.min-impact=10, cost.anomalies.send-feedback-to-aws=false, notification.email.cost-anomaly.enabled=false
- `src/test/java/com/tcgdigital/vmcontrol/service/CostAnomalyServiceTest.java` (modify) — routing + feedback

**Steps**

1. CostAnomalyService.routeNew(): after ingest, for rows with notified_at NULL, total_impact >= cost.anomalies.min-impact and environment_id not null, call notificationService.notifyCostAnomaly(...) and set notified_at. Rows without an environment notify admins only (notifyCostAnomalyUnmapped to userRepository.findByAdminTrueAndIsActiveTrue).
2. NotificationService.notifyCostAnomaly: recipients = the environment reviewers (owners), plus admins and administering env admins, via the existing private resolveEnvironmentReviewers. Message: '<env>: unexpected spend of $X since <date> (top cause: <service>/<usageType>)'. Use entityType 'COST_ANOMALY' and entityId=anomalyId. Email when notification.email.enabled and notification.email.cost-anomaly.enabled are both true, using EmailTemplates.eventEmail. Wrap each send in try/catch.
3. feedback(anomalyId, userId, value in {YES, NO, PLANNED_ACTIVITY}): the caller must have an effective ADMIN level on the anomaly's environment, or be ADMIN. Store feedback and audit it. If cost.anomalies.send-feedback-to-aws=true, also call ProvideAnomalyFeedback(anomalyId, feedback) (it changes AWS state, so the flag defaults to false).
4. Controller: POST /api/v1/cost-anomalies/{id}/feedback with a body {value}.

**Acceptance criteria**

- Given a new $250 anomaly on env A, when routing runs, then A's owners and admins get one COST_ANOMALY bell notification and a second run sends nothing.
- Given a $5 anomaly with min-impact=10, then no notification is sent and the row stays visible on the pages.
- Given an owner marks it PLANNED_ACTIVITY, then the feedback is stored and AWS is not called while send-feedback-to-aws=false.

**Tests**

- CostAnomalyServiceTest: threshold, once-only, unmapped goes to admins, feedback authorization, AWS feedback gated by the flag.
- NotificationServiceTest: notifyCostAnomaly recipients.

#### E21-T04 — Show anomalies on the environment page and in an admin Cost Management panel

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G10 · **Depends on:** E21-T03, E18-T03, E08-T09

Owners and admins need to see open anomalies in context and give feedback (G10).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/CostAnomalyController.java` (modify) — GET /api/v1/cost-anomalies (ADMIN, filters, Paging.of), GET /api/v1/environments/{envId}/cost-anomalies (assertCanView)
- `src/main/java/com/tcgdigital/vmcontrol/dto/CostAnomalyDTO.java` (new) — id, environment, dates, impact, expected, score, topCause, feedback
- `src/main/resources/static/js/features/environments.js` (modify) — anomaly list inside the E18 cost card
- `src/main/resources/static/js/features/cost-management.js` (modify) — Anomalies table panel with feedback buttons and monitor link status
- `src/main/resources/static/js/config.js` (modify) — API.costAnomalies.*
- `src/main/resources/static/js/ui/notification-bell.js` (modify) — COST_ANOMALY icon, link to #/environments/<id>
- `src/test/java/com/tcgdigital/vmcontrol/controller/CostAnomalyControllerSecurityTest.java` (new) — role matrix

**Steps**

1. Admin list: GET /api/v1/cost-anomalies?days=30&environmentId=&page=&size= (days @Min(1) @Max(365); Paging.of(page,size,100)), newest first.
2. Scoped: GET /api/v1/environments/{envId}/cost-anomalies?days=90 with securityService.assertCanView(envId).
3. Environment page: under the E18 cost card, list the last 90 days' anomalies (impact, dates, top cause), each with feedback buttons for owners.
4. Cost Management: an 'Anomalies' panel loaded with the page's allSettled pattern (E08-T09): a table with an environment filter, a CSV/Excel export via the existing excel pattern if requested, and a header line 'N monitors linked / M environments' from GET /monitors with a link to Cost Setup.
5. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given an anomaly on env A, then members of A see it on the environment page and non-members get 404 from the scoped endpoint.
- Given an admin opens Cost Management, then the Anomalies panel lists anomalies across environments, and a failure in that panel does not blank the page.

**Tests**

- CostAnomalyControllerSecurityTest: admin list ADMIN-only; scoped list follows assertCanView; feedback by owner 200, by viewer 403.
- Manual UI checklist.

## E22

### Waste sweeps — Next

Admins see a daily list of unattached EBS volumes, unassociated public IPv4/EIPs, gp2 volumes, VMs stopped over 30 days and old snapshots with estimated monthly cost, and can fix them through an approved, reversible action flow that is off by default.

Sources: G11 · 5 tasks · 10 days

#### E22-T01 — Add the waste-finding store, region scanner and unattached EBS volume sweep

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** G11 · **Depends on:** E00-T01, E12-T06

G11: no scan exists for account-level waste; unattached EBS volumes are the largest item ($0.10 per GB-month for gp2).

**Files**

- `src/main/resources/db/migration/V<next>__add_waste_finding.sql` (new) — waste_finding table (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/WasteFinding.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/model/WasteFindingType.java` (new) — UNATTACHED_VOLUME, UNASSOCIATED_IP, GP2_VOLUME, LONG_STOPPED_VM, OLD_SNAPSHOT
- `src/main/java/com/tcgdigital/vmcontrol/model/WasteFindingStatus.java` (new) — OPEN, DISMISSED, RESOLVED, ACTION_PENDING
- `src/main/java/com/tcgdigital/vmcontrol/repository/WasteFindingRepository.java` (new) — findByTypeAndRegionAndResourceId, findOpenNotSeenSince
- `src/main/java/com/tcgdigital/vmcontrol/service/WastePricing.java` (new) — monthly cost from waste.pricing.* properties
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsWasteScanService.java` (new) — per-region Ec2Client cache (same pattern as AwsCloudProviderService.getEc2Client) + scanUnattachedVolumes(region)
- `src/main/java/com/tcgdigital/vmcontrol/service/WasteSweepService.java` (new) — orchestrates scans, upserts findings, resolves findings no longer seen
- `src/main/resources/application.properties` (modify) — waste.sweep.enabled=false, waste.sweep.regions=, waste.sweep.unattached-days=32, waste.pricing.ebs.* rates
- `src/test/java/com/tcgdigital/vmcontrol/service/WasteSweepServiceTest.java` (new) — upsert/resolve logic

**Steps**

1. Migration: CREATE TABLE waste_finding (finding_id VARCHAR(36) PK, finding_type VARCHAR(30) NOT NULL, region VARCHAR(30) NOT NULL, resource_id VARCHAR(255) NOT NULL, resource_name VARCHAR(255), environment_id VARCHAR(36) NULL, vm_id VARCHAR(36) NULL, details TEXT, est_monthly_cost DECIMAL(12,2), status VARCHAR(20) NOT NULL, first_seen_at TIMESTAMP NOT NULL, last_seen_at TIMESTAMP NOT NULL, eligible_since TIMESTAMP NULL, dismissed_by VARCHAR(36), dismissed_reason VARCHAR(500), created_at, updated_at); UNIQUE (finding_type, region, resource_id); index (status, finding_type).
2. WastePricing: BigDecimal ebsMonthly(String volumeType, int sizeGib) from waste.pricing.ebs.<type> per GB-month (defaults gp2 0.10, gp3 0.08, io1 0.125, io2 0.125, st1 0.045, sc1 0.015, standard 0.05), snapshotMonthly(gib) (0.05), publicIpv4Monthly() (3.65). Region multipliers are out of scope; label UI values 'estimated'.
3. AwsWasteScanService.scanUnattachedVolumes(region): DescribeVolumes paginator with filter status=available; map to a ScannedResource record (id, name tag, type, size, createTime, tags). Throw on API failure (do not return empty, same lesson as H24).
4. WasteSweepService.sweep(): if !waste.sweep.enabled return. Regions = waste.sweep.regions (comma list) or aws.region. Per region and per scan type in try/catch: upsert findings (new -> OPEN with first_seen_at=now; existing -> last_seen_at=now, cost refreshed; DISMISSED stays dismissed). eligible_since for volumes = existing first_seen_at or volume createTime (lower bound; UI says 'unattached at least since'). Only report UNATTACHED_VOLUME when eligible_since <= now - unattached-days. After a successful region+type scan, mark OPEN findings of that type/region not seen this run as RESOLVED.
5. Link to inventory where possible: match tag tcg:environment to Environment.name to fill environment_id.

**Acceptance criteria**

- Given waste.sweep.enabled=false, when sweep() runs, then no AWS call is made.
- Given an available 100 GiB gp2 volume first seen 40 days ago, when the sweep runs, then an OPEN UNATTACHED_VOLUME finding with est_monthly_cost 10.00 exists.
- Given the volume is attached later, when the next sweep succeeds, then the finding is RESOLVED.
- Given DescribeVolumes fails in one region, then findings in that region are left unchanged.

**Tests**

- WasteSweepServiceTest (Mockito AwsWasteScanService): flag off, upsert, eligibility threshold, resolve-on-absence only after success, dismissed stays dismissed.
- AwsWasteScanServiceTest (mock Ec2Client): filter and pagination.

**Notes:** Read-only AWS calls (ec2:DescribeVolumes); document the IAM permission in the PR. Scheduler comes in E22-T02.

#### E22-T02 — Add sweeps for unassociated public IPs, gp2 volumes, long-stopped VMs and old snapshots, and schedule the daily sweep

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** G11 · **Depends on:** E22-T01

G11: the remaining waste categories (public IPv4 at $3.65/month, gp2 -> gp3 saving $0.02/GB-month, VMs stopped over 30 days still paying for disks and IPs, old snapshots).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/AwsWasteScanService.java` (modify) — scanUnassociatedAddresses, scanGp2Volumes, scanStoppedInstances, scanOldSnapshots
- `src/main/java/com/tcgdigital/vmcontrol/service/WasteSweepService.java` (modify) — wire the four scans; long-stopped uses VmStateHistory for registered VMs
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmStateHistoryRepository.java` (modify) — latest transition per VM to STOPPED (batch)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/WasteSweepScheduler.java` (new) — @ConditionalOnProperty(waste.sweep.enabled) cron waste.sweep.cron=0 0 4 * * *, uses ScheduledJobLockService.runLocked("waste_sweep", ...)
- `src/main/java/com/tcgdigital/vmcontrol/service/SchedulerJobCatalog.java` (modify) — register waste_sweep (daily)
- `src/main/resources/application.properties` (modify) — waste.sweep.cron, waste.sweep.stopped-days=30, waste.sweep.snapshot-age-days=90
- `src/test/java/com/tcgdigital/vmcontrol/service/WasteSweepServiceTest.java` (modify) — new categories

**Steps**

1. scanUnassociatedAddresses: DescribeAddresses; keep addresses with associationId == null; cost = publicIpv4Monthly().
2. scanGp2Volumes: DescribeVolumes filter volume-type=gp2 (any state); saving = size x (gp2 - gp3 rate); store saving as est_monthly_cost and current cost in details.
3. scanStoppedInstances: DescribeInstances filter instance-state-name=stopped; stopped-since from the registered VM's latest VmStateHistory transition to STOPPED when the instance is registered, else parse StateTransitionReason 'User initiated (YYYY-MM-DD HH:MM:SS GMT)'; report when older than stopped-days; cost = attached EBS (from BlockDeviceMappings -> DescribeVolumes sizes, or VmVolumeSnapshot rows for registered VMs) + public IPs; set vm_id/environment_id when registered.
4. scanOldSnapshots: DescribeSnapshots OwnerIds=self, startTime older than snapshot-age-days; exclude snapshots referenced by DescribeImages(Owners=self) block device mappings; cost = snapshotMonthly(volumeSize) (upper bound; label as such).
5. WasteSweepScheduler wraps WasteSweepService.sweep() in runLocked("waste_sweep", Duration.ofHours(1), ...) (E06-T01) and is registered in SchedulerJobCatalog (E12-T05).
6. Audit SCHEDULED_JOB_EXECUTED with counts per type and total estimated monthly cost.

**Acceptance criteria**

- Given an EIP with no association, then an UNASSOCIATED_IP finding of 3.65/month exists.
- Given an attached 500 GiB gp2 volume, then a GP2_VOLUME finding shows 10.00/month saving.
- Given a registered VM stopped 45 days ago, then a LONG_STOPPED_VM finding links to its environment and VM.
- Given a 120-day-old snapshot used by an AMI, then no OLD_SNAPSHOT finding is created for it.

**Tests**

- AwsWasteScanServiceTest: each scan's filters, StateTransitionReason parsing, AMI exclusion.
- WasteSweepServiceTest: thresholds, cost math, one failing scan does not stop the others.

#### E22-T03 — Expose waste findings through an admin API and a Waste page with totals, filters and dismiss

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** G11 · **Depends on:** E00-T04, E02 (router lifecycle), E22-T02

G11: findings must be visible with an estimated monthly cost to act on.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/WasteController.java` (new) — /api/v1/waste: GET findings (paged), GET summary, POST {id}/dismiss, POST {id}/reopen, POST sweep (manual, job lock), GET findings/export
- `src/main/java/com/tcgdigital/vmcontrol/dto/WasteFindingDTO.java` (new) — DTO
- `src/main/java/com/tcgdigital/vmcontrol/dto/WasteSummaryDTO.java` (new) — count and monthly total per type
- `src/main/java/com/tcgdigital/vmcontrol/service/WasteSweepService.java` (modify) — query, dismiss/reopen with audit
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — WASTE_FINDING_DISMISSED, WASTE_FINDING_REOPENED
- `src/main/resources/static/js/features/waste.js` (new) — Waste page module (IIFE singleton)
- `src/main/resources/static/css/features/waste.css` (new) — page styles using tokens
- `src/main/resources/static/js/core/router.js` (modify) — 'waste': '#/waste' route -> Waste.load()
- `src/main/resources/static/js/config.js` (modify) — Config.API.waste.*
- `src/main/resources/static/index.html` (modify) — admin-only sidebar link next to Cost Management; script and stylesheet tags (bundled by build-frontend.ps1 from index.html)
- `src/test/java/com/tcgdigital/vmcontrol/controller/WasteControllerTest.java` (new) — role matrix and filters

**Steps**

1. WasteController (@PreAuthorize("hasRole('ADMIN')") on all): GET /findings?type&region&status&environmentId&page&size with Paging.of(page, size, 200); GET /summary (OPEN only: count + sum est_monthly_cost per type and total); POST /findings/{id}/dismiss {reason} (required, max 500) and /reopen; POST /sweep -> tryRunLocked("waste_sweep") (E12-T02), 409 when busy, 503 when waste.sweep.enabled=false; GET /findings/export CSV via ExcelExportService pattern used by cost exports.
2. waste.js: summary cards per type with monthly totals, filter bar (type, region, status), paged table (resource, region, environment, age, est. monthly cost, status, actions: Dismiss/Reopen), 'Run sweep now' button, empty and disabled states ('Waste sweeps are turned off: set waste.sweep.enabled'). Use Utils.html, ContentRouter.token()/isCurrent (E02) to drop stale renders.
3. Router + sidebar entry (admin-only class like #cost-management-link), index.html script/link tags, run .\build-frontend.ps1.

**Acceptance criteria**

- Given 3 open findings totalling 25.30/month, when an ADMIN opens #/waste, then the summary shows 25.30 and the table lists 3 rows.
- Given a finding, when dismissed with a reason, then it shows DISMISSED, a WASTE_FINDING_DISMISSED audit row exists, and later sweeps keep it dismissed.
- Given an ENV_ADMIN or USER, then the API returns 403 and the sidebar link is hidden.

**Tests**

- WasteControllerTest (SecuredWebTestBase): role matrix, paging clamp, dismiss requires reason, sweep 409/503.

#### E22-T04 — Add the approved fix-action workflow and delete unattached volumes with snapshot-before-delete and rollback

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G11 · **Depends on:** E22-T03

G11: fixes must be behind a flag and approval, snapshot before delete, and be reversible.

**Files**

- `src/main/resources/db/migration/V<next>__add_waste_action.sql` (new) — waste_action table (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/WasteAction.java` (new) — entity with action_type, status, proposed_by, approved_by, rollback_data JSON, error
- `src/main/java/com/tcgdigital/vmcontrol/repository/WasteActionRepository.java` (new) — repository
- `src/main/java/com/tcgdigital/vmcontrol/service/WasteActionService.java` (new) — propose/approve/reject/execute/rollback state machine
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsWasteScanService.java` (modify) — createSnapshotAndWait, deleteVolume, createVolumeFromSnapshot
- `src/main/java/com/tcgdigital/vmcontrol/controller/WasteController.java` (modify) — action endpoints
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — WASTE_ACTION_PROPOSED/APPROVED/REJECTED/EXECUTED/FAILED/ROLLED_BACK
- `src/main/resources/application.properties` (modify) — waste.actions.enabled=false
- `src/test/java/com/tcgdigital/vmcontrol/service/WasteActionServiceTest.java` (new) — state machine and AWS call order

**Steps**

1. Migration: CREATE TABLE waste_action (action_id VARCHAR(36) PK, finding_id VARCHAR(36) NOT NULL REFERENCES waste_finding, action_type VARCHAR(30) NOT NULL, status VARCHAR(20) NOT NULL, proposed_by VARCHAR(36) NOT NULL, proposed_at TIMESTAMP, approved_by VARCHAR(36), approved_at TIMESTAMP, executed_at TIMESTAMP, rolled_back_at TIMESTAMP, rollback_data TEXT, error_message TEXT, version BIGINT NOT NULL DEFAULT 0).
2. WasteActionService: propose(findingId, type, userId) -> PROPOSED (finding -> ACTION_PENDING); approve(actionId, approverId) requires approver != proposer (ValidationException) and @Version guards double approval; reject -> REJECTED (finding back to OPEN). All refuse with 503-style ValidationException when waste.actions.enabled=false.
3. execute (runs after approval on a background executor, never inside a DB transaction): DELETE_VOLUME: re-describe the volume and abort (FAILED) unless still 'available'; CreateSnapshot with tags {tcg:waste-action=<id>, original volume tags, source volume id}; wait with the EC2 snapshot-completed waiter (timeout 30 min); store {snapshotId, az, volumeType, size, iops, throughput, tags} in rollback_data; DeleteVolume; status EXECUTED, finding RESOLVED.
4. rollback(actionId, userId) for EXECUTED DELETE_VOLUME: CreateVolume(snapshotId, az, type, size, iops/throughput, tags) and record the new volume id; status ROLLED_BACK.
5. Endpoints (ADMIN): POST /api/v1/waste/findings/{id}/actions {type}, POST /actions/{id}/approve, /reject, /rollback, GET /actions?status.
6. Audit every transition with the actor.

**Acceptance criteria**

- Given waste.actions.enabled=false, when proposing, then 4xx and nothing is stored.
- Given admin A proposes deleting a volume, when A approves, then 400; when admin B approves, then a snapshot is created and completed before DeleteVolume is called.
- Given the volume was attached after the proposal, when executing, then it is not deleted and the action is FAILED.
- Given an executed delete, when rolled back, then a volume is created from the snapshot in the same AZ with the original type and tags.

**Tests**

- WasteActionServiceTest (Mockito Ec2 calls, InOrder): createSnapshot -> waiter -> deleteVolume; same-user approval rejected; state re-check; rollback args; flag off.

**Notes:** Changes AWS state: ships with waste.actions.enabled=false. Needs ec2:CreateSnapshot, ec2:DeleteVolume, ec2:CreateVolume, ec2:CreateTags. E25 (cost-aware approvals) may later reuse this approval pattern.

#### E22-T05 — Add gp2-to-gp3 migration, snapshot archiving and EIP release actions, and action buttons in the Waste page

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G11 · **Depends on:** E22-T04

G11: the remaining fix actions with rollback where AWS allows it.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/WasteActionService.java` (modify) — MIGRATE_GP3, ARCHIVE_SNAPSHOT, RELEASE_IP
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsWasteScanService.java` (modify) — modifyVolumeType, modifySnapshotTier/restoreSnapshotTier, releaseAddress
- `src/main/resources/static/js/features/waste.js` (modify) — Propose/Approve/Reject/Rollback buttons and action history
- `src/test/java/com/tcgdigital/vmcontrol/service/WasteActionServiceTest.java` (modify) — new types

**Steps**

1. MIGRATE_GP3: ModifyVolume(volumeType=gp3, iops=max(3000, current baseline), throughput=125); rollback_data {previousType: gp2}; rollback = ModifyVolume back to gp2 (warn in UI: AWS allows one modification per volume every 6 hours).
2. ARCHIVE_SNAPSHOT (instead of delete, so it stays reversible): ModifySnapshotTier(storageTier=archive); rollback = RestoreSnapshotTier(permanentRestore=true). Show that archive tier has a 90-day minimum charge.
3. RELEASE_IP: re-check the address is still unassociated, store {publicIp, allocationId, tags} in rollback_data, ReleaseAddress. Mark as not reversible in the API (rollbackSupported=false) and require typing the IP to confirm in the UI.
4. LONG_STOPPED_VM findings get no destructive action; the UI offers 'Open VM' and 'Dismiss' only.
5. waste.js: per finding show allowed actions; pending actions list with Approve/Reject for admins other than the proposer; Rollback when supported; action history drawer. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given an approved MIGRATE_GP3, when executed, then ModifyVolume is called with gp3 and the finding is RESOLVED after the next sweep.
- Given an executed ARCHIVE_SNAPSHOT, when rolled back, then RestoreSnapshotTier is called.
- Given RELEASE_IP, then the UI requires typing the IP and no Rollback button is shown.
- Given the proposer views their own pending action, then no Approve button is shown.

**Tests**

- WasteActionServiceTest: each type's AWS call and rollback, RELEASE_IP rollback refused.

**Notes:** All behind waste.actions.enabled=false. Extra IAM: ec2:ModifyVolume, ec2:ModifySnapshotTier, ec2:RestoreSnapshotTier, ec2:ReleaseAddress.

## E23

### Team allocation & chargeback — Next

Every environment belongs to a real team (optionally an Entra group), required allocation tags are enforced at registration, shared costs are split by explicit rules, and finance gets a monthly chargeback statement per team.

Sources: G12 · 8 tasks · 10.5 days

#### E23-T01 — Introduce a Team entity and link environments to it, backfilled from metadata ownerTeam

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G12 · **Depends on:** E14-T08, E08-T08

Team is free text in environment metadata (TeamResolver reads JSON key ownerTeam; modals.js inputs cem-ownerTeam/editEnvOwnerTeam), so spelling variants split costs and nothing can be routed or charged per team (G12).

**Files**

- `src/main/resources/db/migration/V<next>__add_team.sql` (new) — team table, environment.team_id FK, backfill from metadata
- `src/main/java/com/tcgdigital/vmcontrol/model/Team.java` (new) — entity: teamId, name (unique slug), displayName, costCenter, entraGroupId (unique, nullable), isActive, timestamps
- `src/main/java/com/tcgdigital/vmcontrol/repository/TeamRepository.java` (new) — findByName, findByEntraGroupId, findByIsActiveTrueOrderByDisplayName
- `src/main/java/com/tcgdigital/vmcontrol/model/Environment.java` (modify) — @ManyToOne(fetch = LAZY) Team team (team_id)
- `src/main/java/com/tcgdigital/vmcontrol/service/TeamService.java` (new) — CRUD, deactivate (only when no active environment uses it), audit TEAM_* actions
- `src/main/java/com/tcgdigital/vmcontrol/controller/TeamController.java` (new) — /api/v1/teams GET (authenticated), POST/PUT/DELETE (ADMIN)
- `src/main/java/com/tcgdigital/vmcontrol/dto/TeamDTO.java` (new) — response/request DTO
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — TEAM_CREATED, TEAM_UPDATED, TEAM_DEACTIVATED
- `src/main/java/com/tcgdigital/vmcontrol/service/TeamResolver.java` (modify) — add resolveTeam(Environment): team.displayName, else metadata ownerTeam, else Unassigned
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — getSpendTrendByTeam calls resolveTeam(Environment) (getSpendByTeam is deleted by E08-T08)
- `src/main/java/com/tcgdigital/vmcontrol/service/TagReconciliationService.java` (modify) — buildTags uses resolveTeam(Environment)

**Steps**

1. Migration (next free V number): CREATE TABLE team (team_id VARCHAR(36) PK, name VARCHAR(100) NOT NULL UNIQUE, display_name VARCHAR(255) NOT NULL, cost_center VARCHAR(100) NULL, entra_group_id VARCHAR(64) NULL UNIQUE, is_active BOOLEAN NOT NULL DEFAULT TRUE, created_at, updated_at); ALTER TABLE environment ADD COLUMN team_id VARCHAR(36) NULL, ADD CONSTRAINT fk_environment_team FOREIGN KEY (team_id) REFERENCES team(team_id); CREATE INDEX ix_environment_team ON environment(team_id).
2. Backfill in the same migration: INSERT INTO team (...) SELECT UUID(), LOWER(REGEXP_REPLACE(TRIM(t), '[^A-Za-z0-9]+', '-')), MIN(TRIM(t)), ... FROM (SELECT JSON_UNQUOTE(JSON_EXTRACT(metadata, '$.ownerTeam')) t FROM environment WHERE JSON_VALID(metadata)) x WHERE t IS NOT NULL AND t <> '' GROUP BY 2; then UPDATE environment e JOIN team tm ON tm.name = <same slug expression> SET e.team_id = tm.team_id. Leave metadata untouched.
3. Entity, repository, DTO, TeamService (create/update validate unique name; deactivate refuses when active environments reference the team), TeamController with @PreAuthorize as listed. Audit via AuditService.logAction(actor, TEAM_*, "team", id, name, details).
4. Environment: add the relation plus getTeamId() convenience; EnvironmentDTO exposes teamId and teamName.
5. TeamResolver.resolveTeam(Environment env): env.getTeam() != null ? displayName : resolveTeam(env.getMetadata()). Switch the remaining callers listed to the Environment overload (fetch team in the existing queries with JOIN FETCH where they iterate many environments to avoid N+1).
6. Run graphify update .

**Acceptance criteria**

- Given environments with ownerTeam 'Platform Team' and 'Platform Team ' (trailing space), when the migration runs, then one team 'platform-team' exists and both environments reference it.
- Given a USER, when GET /api/v1/teams, then active teams are listed; when POST /api/v1/teams, then 403.
- Given an environment linked to team T, when GET /api/v1/cost-management/spend-trend-by-team, then its spend is reported under T's display name.
- Given a team with an active environment, when an admin deactivates it, then 409/400 with a clear message.

**Tests**

- TeamServiceTest (Mockito): unique name, deactivate guard, audit calls.
- TeamControllerIntegrationTest (SecuredWebTestBase): role matrix for each endpoint.
- FlywayMigrationTest: backfill groups trimmed/case variants into one team.
- TeamResolverTest: Environment overload precedence (team > metadata > Unassigned).

#### E23-T02 — Link teams to Entra groups and sync team membership from Microsoft Graph

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G12 · **Depends on:** E23-T01, E06-T01

G12 asks for teams from Entra groups so membership is not maintained by hand; GraphDirectoryService already has an app-only Graph RestClient (graph.directory.enabled) but only reads users.

**Files**

- `src/main/resources/db/migration/V<next>__add_team_member.sql` (new) — team_member(team_id, user_id, source ENTRA|MANUAL, synced_at) PK(team_id, user_id), FKs ON DELETE CASCADE
- `src/main/java/com/tcgdigital/vmcontrol/model/TeamMember.java` (new) — entity with composite id
- `src/main/java/com/tcgdigital/vmcontrol/repository/TeamMemberRepository.java` (new) — findByTeamId, findTeamIdsByUserId, deleteByTeamIdAndSourceAndUserIdNotIn
- `src/main/java/com/tcgdigital/vmcontrol/service/GraphDirectoryService.java` (modify) — searchGroups(query, top) and listGroupMemberObjectIds(groupId) with paging (@odata.nextLink)
- `src/main/java/com/tcgdigital/vmcontrol/service/TeamMembershipSyncService.java` (new) — for each team with entraGroupId: fetch members, map to app_user by azure_ad_object_id, upsert ENTRA members, remove stale ENTRA rows
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/TeamMembershipSyncScheduler.java` (new) — cron ${teams.entra-sync.cron:0 15 * * * *}, runLocked("team_membership_sync", ...)
- `src/main/java/com/tcgdigital/vmcontrol/controller/TeamController.java` (modify) — GET /teams/entra-groups?query= (ADMIN), PUT /teams/{id}/entra-group, GET/POST/DELETE /teams/{id}/members (manual), POST /teams/{id}/sync (ADMIN)
- `src/main/resources/application.properties` (modify) — teams.entra-sync.enabled=${TEAMS_ENTRA_SYNC_ENABLED:false}, teams.entra-sync.cron
- `docs/aws-iam-permissions.md` (modify) — note: Graph application permission GroupMember.Read.All (and Group.Read.All for search) needed, admin consent

**Steps**

1. Migration and TeamMember entity/repository as listed.
2. GraphDirectoryService: searchGroups uses GET /groups?$search="displayName:<q>"&$select=id,displayName,mail&$top=n with ConsistencyLevel: eventual; listGroupMemberObjectIds uses GET /groups/{id}/transitiveMembers/microsoft.graph.user?$select=id&$top=999 following @odata.nextLink. Same error mapping as search() (DirectoryLookupException.disabled/unavailable).
3. TeamMembershipSyncService.syncTeam(teamId) and syncAll(): members whose object id has no app_user are skipped (counted, not created). Each team in its own transaction; one failure does not stop others; audit SCHEDULED_JOB_EXECUTED with totals.
4. Scheduler conditional on teams.entra-sync.enabled=true and graph.directory.enabled=true; uses ScheduledJobLockService.runLocked (E06-T01).
5. Controller endpoints; linking a group triggers an immediate syncTeam.
6. Add TeamService.getTeamIdsForUser(userId) for later epics (quotas, routing).

**Acceptance criteria**

- Given a team linked to an Entra group with 3 members of whom 2 have app_user rows, when sync runs, then team_member has those 2 with source ENTRA.
- Given a member removed from the Entra group, when sync runs again, then their ENTRA row is deleted and MANUAL rows are kept.
- Given teams.entra-sync.enabled=false, then no Graph calls are made and manual membership still works.
- Given Graph returns 503 for one team, then other teams still sync and the failure is logged.

**Tests**

- GraphDirectoryServiceTest: MockRestServiceServer for group search and paged transitiveMembers.
- TeamMembershipSyncServiceTest (AbstractIntegrationTest + mocked GraphDirectoryService): add/remove/skip-unknown cases.

**Notes:** Requires tenant admin consent for GroupMember.Read.All; ships off.

#### E23-T03 — Teams admin page and team picker in environment create/edit

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G12 · **Depends on:** E23-T02

Admins need to manage teams and pick a team instead of typing ownerTeam free text in modals.js (cem-ownerTeam, cem-eks-ownerTeam, editEnvOwnerTeam) (G12).

**Files**

- `src/main/resources/static/js/features/teams.js` (new) — Teams page: list, create/edit modal, Entra group search/link, members list, Sync now
- `src/main/resources/static/css/features/teams.css` (new) — page styles using existing tokens
- `src/main/resources/static/js/core/router.js` (modify) — route 'teams' -> '#/teams' (ADMIN)
- `src/main/resources/static/index.html` (modify) — Admin sidebar link, script and css tags
- `src/main/resources/static/js/config.js` (modify) — Config.API.teams.*
- `src/main/resources/static/js/ui/modals.js` (modify) — replace ownerTeam text inputs with a team <select> (teamId); keep showing legacy ownerTeam read-only when no team is set
- `src/main/java/com/tcgdigital/vmcontrol/dto/CreateEnvironmentDTO.java` (modify) — add teamId
- `src/main/java/com/tcgdigital/vmcontrol/dto/UpdateEnvironmentDTO.java` (modify) — add teamId
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — set team from teamId (404 when unknown/inactive)

**Steps**

1. Backend: teamId on create/update DTOs; EnvironmentService resolves it via TeamRepository; include the change in the ENVIRONMENT_UPDATED audit details (E11-T03).
2. teams.js following the My Account page patterns (router lifecycle, Utils.html escaping, loading/error/retry states): table of teams with environment count and member count; create/edit modal (name, display name, cost center); 'Link Entra group' typeahead calling GET /teams/entra-groups; members tab; 'Sync now' button.
3. modals.js: in create/edit environment, load GET /api/v1/teams once and render a select (with 'Unassigned'); send teamId; stop writing ownerTeam into metadata.
4. Sidebar link under Admin (ADMIN only), route registration, Config URLs, index.html tags (bundle order comes from index.html).

**Acceptance criteria**

- Given an ADMIN on #/teams, when they create a team and link an Entra group, then the team shows the group name and member count after sync.
- Given the environment edit modal, when a team is selected and saved, then GET /environments/{id} returns that teamId and the cost page groups spend under it.
- Given a non-admin, then the Teams link is not shown and #/teams shows the standard access-denied state.
- Given a 390px viewport, then the Teams table renders as cards without horizontal scroll.

**Tests**

- EnvironmentServiceTest: teamId set/cleared/unknown -> 404.
- Manual QA checklist; Playwright spec if the test/ project runs in CI.

#### E23-T04 — Enforce required allocation fields at registration and tag them on AWS

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G12 · **Depends on:** E23-T03

G12 requires allocation tags at registration; today an environment or discovered VM can exist with no team or cost center, so its spend lands in 'Unassigned'.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/config/AllocationProperties.java` (new) — @ConfigurationProperties("allocation"): requireTeam (false), requiredMetadataKeys (List<String>, empty), tagCostCenter (true)
- `src/main/java/com/tcgdigital/vmcontrol/service/AllocationValidator.java` (new) — validate(Environment) -> list of missing fields; throws ValidationException listing them
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — call AllocationValidator on create/update
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — registerVm rejects when the target environment fails validation
- `src/main/java/com/tcgdigital/vmcontrol/service/TagReconciliationService.java` (modify) — buildTags adds <prefix>cost-center from team.costCenter; activation list includes it
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — GET /environments/allocation-gaps (ADMIN): environments failing validation
- `src/main/resources/static/js/ui/modals.js` (modify) — mark required fields; show server field errors
- `src/main/resources/application.properties` (modify) — allocation.require-team=${ALLOCATION_REQUIRE_TEAM:false}, allocation.required-metadata-keys=

**Steps**

1. AllocationProperties + AllocationValidator: missing team when requireTeam; missing/blank metadata keys from requiredMetadataKeys (parse metadata JSON with ObjectMapper).
2. EnvironmentService create/update: validate after applying the DTO; 400 with message 'Missing allocation fields: team, costCenter'. Discovery-created environments are not blocked: they stay discoveryPending (existing flag) and appear in allocation-gaps.
3. VmService.registerVm: if the group's environment fails validation, 400 with the same message.
4. TagReconciliationService.buildTags: add tagKeyPrefix + "cost-center" when team has costCenter; include it in ensureTagKeysActivated. Still gated by cost.tagging.enabled (default false).
5. GET /environments/allocation-gaps for admins; show a banner on the Teams page listing environments with gaps and an Edit link.
6. modals.js: required markers driven by a new GET /api/v1/config/allocation (or include the flags in an existing config endpoint) so the UI matches the server rules.

**Acceptance criteria**

- Given allocation.require-team=true, when creating an environment without teamId, then 400 naming 'team'.
- Given required-metadata-keys=costCenter and an environment without it, when registering a VM into it, then 400.
- Given both flags off (default), then behaviour is unchanged.
- Given cost.tagging.enabled=true and a team with cost center CC-12, when reconciliation runs, then instances get tcg:cost-center=CC-12.

**Tests**

- AllocationValidatorTest: matrix of flags and metadata.
- EnvironmentControllerIntegrationTest: 400 body lists missing fields.
- TagReconciliationServiceTest: cost-center tag added and activated.

**Notes:** Turn require-team on after the allocation-gaps list is empty.

#### E23-T05 — Add shared-cost split rules

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G12 · **Depends on:** E23-T01

Shared environments (CI, shared databases) must be charged to the teams that use them; G12 asks for shared-cost split rules.

**Files**

- `src/main/resources/db/migration/V<next>__add_cost_split_rule.sql` (new) — cost_split_rule(rule_id, environment_id UNIQUE, method EVEN|FIXED_PERCENT|PROPORTIONAL_DIRECT, effective_from DATE, created_by, timestamps); cost_split_share(rule_id, team_id, percent DECIMAL(5,2)) PK(rule_id, team_id)
- `src/main/java/com/tcgdigital/vmcontrol/model/CostSplitRule.java` (new) — entity with @OneToMany shares
- `src/main/java/com/tcgdigital/vmcontrol/model/CostSplitShare.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/CostSplitRuleRepository.java` (new) — findByEnvironmentId, findAllWithShares
- `src/main/java/com/tcgdigital/vmcontrol/service/CostSplitService.java` (new) — CRUD + validation + split(BigDecimal amount, rule, Map<teamId, BigDecimal> directByTeam) -> Map<teamId, BigDecimal>
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostSplitRuleController.java` (new) — /api/v1/cost-management/split-rules CRUD (ADMIN)
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — COST_SPLIT_RULE_CREATED/UPDATED/DELETED

**Steps**

1. Migration and entities as listed.
2. Validation: FIXED_PERCENT shares sum to exactly 100.00; EVEN and PROPORTIONAL_DIRECT need at least one team (percent ignored); teams must be active; one rule per environment.
3. split(): EVEN -> amount / n with the rounding remainder added to the first team (scale 2, HALF_UP); FIXED_PERCENT -> amount * percent / 100 with remainder to the largest share; PROPORTIONAL_DIRECT -> by each listed team's direct cost that month (fallback EVEN when all zero). Result always sums exactly to amount.
4. Controller CRUD with audit rows; GET returns rules with environment and team names.

**Acceptance criteria**

- Given a FIXED_PERCENT rule 60/40 on a shared environment costing 100.01, when split, then teams get 60.01 and 40.00 (sum 100.01).
- Given shares summing to 90, when saving, then 400.
- Given a USER, when calling any split-rule endpoint, then 403.

**Tests**

- CostSplitServiceTest: all three methods, rounding, zero-direct fallback.
- CostSplitRuleControllerIntegrationTest: validation and role checks.

#### E23-T06 — Compute the monthly chargeback per team and expose it as JSON, CSV and XLSX

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G12 · **Depends on:** E23-T05

Finance needs a monthly per-team statement (G12): direct environment cost plus allocated shared cost, with the data source stated.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/ChargebackService.java` (new) — compute(YearMonth) from cost_daily_snapshot (actual_cost when present else estimated_cost) grouped by environment team, then apply split rules
- `src/main/java/com/tcgdigital/vmcontrol/dto/ChargebackRowDTO.java` (new) — teamId, teamName, costCenter, directCost, sharedAllocated, total, actualCoveragePct, environments[]
- `src/main/java/com/tcgdigital/vmcontrol/repository/CostDailySnapshotRepository.java` (modify) — findBySnapshotDateBetween with environment and team fetched
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /chargeback?month=YYYY-MM, GET /chargeback/export?month=&format=xlsx|csv (ADMIN)

**Steps**

1. ChargebackService.compute(YearMonth month): load snapshots for the month; per environment cost = SUM(COALESCE(actual_cost, estimated_cost)); track actual coverage = days with actual / days in data.
2. Environments without a rule add to their team's direct cost ('Unassigned' bucket when no team). Environments with a split rule distribute their cost through CostSplitService.split (PROPORTIONAL_DIRECT uses this month's direct totals).
3. Rows sorted by total desc plus a totals row; grand total must equal the sum of all environment costs (assert in code, log error if not).
4. Export: xlsx via ExcelExportService.toWorkbook("Chargeback " + month, headers, rows); csv with formula-neutralised cells (same rule as Utils.csvCell, implemented server side). File name chargeback-YYYY-MM.xlsx/csv.
5. month must be a past or current month; future -> 400.

**Acceptance criteria**

- Given September with env A (team X) 300.00 and shared env S 100.00 split 50/50 between X and Y, when GET /api/v1/cost-management/chargeback?month=2026-09, then X total 350.00, Y 50.00 and grand total 400.00.
- Given days where actual_cost is null, then estimated cost is used and actualCoveragePct reflects it.
- Given format=xlsx, then a workbook with one row per team plus totals downloads.

**Tests**

- ChargebackServiceTest (AbstractIntegrationTest with seeded snapshots): direct, split, Unassigned, coverage, totals invariant.
- CostManagementController integration test: role check and export content type.

**Notes:** Relies on the corrected daily snapshots from E08.

#### E23-T07 — Freeze monthly chargeback statements and email them to finance

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G12 · **Depends on:** E23-T06

A chargeback that changes after month end cannot be invoiced; finance needs a fixed statement delivered on a schedule (G12).

**Files**

- `src/main/resources/db/migration/V<next>__add_chargeback_statement.sql` (new) — chargeback_statement(statement_id, month CHAR(7) UNIQUE, generated_at, generated_by, payload JSON/TEXT, total DECIMAL(14,2), status DRAFT|FINAL)
- `src/main/java/com/tcgdigital/vmcontrol/model/ChargebackStatement.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/ChargebackStatementRepository.java` (new) — findByMonth
- `src/main/java/com/tcgdigital/vmcontrol/service/ChargebackService.java` (modify) — finalize(month, actor): compute and store FINAL; get(month) returns stored FINAL when present
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ChargebackScheduler.java` (new) — cron ${chargeback.cron:0 0 6 5 * *} finalizes the previous month and emails the xlsx
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — POST /chargeback/{month}/finalize (ADMIN); GET returns status
- `src/main/resources/application.properties` (modify) — chargeback.enabled=false, chargeback.cron, chargeback.recipients=

**Steps**

1. Migration/entity/repository.
2. finalize: refuses the current month and an already FINAL month (409); stores the JSON payload of ChargebackRowDTO list; audit CHARGEBACK_FINALIZED (add to AuditAction).
3. GET /chargeback returns the stored FINAL statement when one exists, else a live DRAFT computation, with status in the response.
4. Scheduler (chargeback.enabled=true) runs on day 5 by default via runLocked, finalizes last month and sends the xlsx with EmailService.sendHtml to chargeback.recipients (Bcc behaviour from E11-T09), recording email_log type CHARGEBACK_STATEMENT.

**Acceptance criteria**

- Given September is finalized, when October snapshots for late September actuals arrive, then GET ?month=2026-09 still returns the frozen totals.
- Given finalize is called twice, then the second call returns 409.
- Given chargeback.enabled=false, then no statement is generated or emailed.

**Tests**

- ChargebackStatementIntegrationTest: finalize freezes, re-finalize 409, current month 400.
- Scheduler test with mocked EmailService verifying attachment name and recipients.

#### E23-T08 — Chargeback and split-rule screens

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G12 · **Depends on:** E23-T07

Admins and finance need to see the monthly statement, manage split rules and download exports without API calls (G12).

**Files**

- `src/main/resources/static/js/features/chargeback.js` (new) — #/chargeback page: month picker, statement table with status DRAFT/FINAL, Finalize button, CSV/XLSX download, split-rules tab with editor
- `src/main/resources/static/css/features/chargeback.css` (new) — styles
- `src/main/resources/static/js/core/router.js` (modify) — route 'chargeback'
- `src/main/resources/static/index.html` (modify) — Admin sidebar link next to Cost Management; script/css tags
- `src/main/resources/static/js/config.js` (modify) — Config.API.costManagement.chargeback*, splitRules*

**Steps**

1. Page skeleton using the router lifecycle and Utils.html; month picker defaults to the previous month.
2. Statement table: team, cost center, direct, shared allocated, total, actual coverage %; totals row; expandable environments per team.
3. Finalize button (type-to-confirm per the E13 destructive-action policy) only for DRAFT past months.
4. Split-rules tab: list rules; editor modal choosing shared environment, method and team shares with live 'sum = 100%' validation.
5. Download buttons call the export endpoint and save the file.

**Acceptance criteria**

- Given September is DRAFT, when the admin finalizes it, then the badge shows FINAL and the button disappears.
- Given a FIXED_PERCENT rule with shares summing to 95, then Save stays disabled with an inline message.
- Given 390px width, then the statement renders as cards.

**Tests**

- Manual QA checklist; Playwright spec if test/ runs in CI.

## E24

### Slack/Teams & Jira — Next

Environment events reach the team's Slack or Microsoft Teams channel with safe one-click actions (extend lease, snooze idle stop, approve request, stop environment), and recommendations and anomalies can become Jira tickets.

Sources: G13 · 7 tasks · 9 days

#### E24-T01 — Add a provider-neutral chat notification interface with Slack and Microsoft Teams webhook implementations

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G13 · **Depends on:** E14-T08

The team has not chosen Slack or Teams, so outbound chat must sit behind one interface with both implementations (G13).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatMessage.java` (new) — record(title, text, severity, List<Field> fields, List<ChatAction> actions, String linkUrl)
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatAction.java` (new) — record(ChatActionType type, String label, String url, String style)
- `src/main/java/com/tcgdigital/vmcontrol/model/ChatActionType.java` (new) — EXTEND_LEASE, SNOOZE_IDLE_STOP, APPROVE_ACCESS_REQUEST, STOP_ENVIRONMENT
- `src/main/java/com/tcgdigital/vmcontrol/model/ChatProvider.java` (new) — SLACK, TEAMS
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatSender.java` (new) — interface: ChatProvider provider(); void send(String webhookUrl, ChatMessage message) throws ChatDeliveryException
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/SlackWebhookSender.java` (new) — Block Kit JSON (header, section, fields, actions with url buttons) POSTed to an incoming webhook
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/TeamsWebhookSender.java` (new) — Adaptive Card 1.4 inside a message attachment (Action.OpenUrl buttons) POSTed to a Teams Workflows/incoming webhook
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatSenderRegistry.java` (new) — resolves the sender by ChatProvider (same pattern as CloudProviderFactory)
- `src/main/java/com/tcgdigital/vmcontrol/config/ChatConfig.java` (new) — RestClient bean 'chatRestClient' with 5s connect/read timeouts
- `src/main/resources/application.properties` (modify) — chat.enabled=${CHAT_ENABLED:false}, chat.public-base-url=${APP_PUBLIC_BASE_URL:}

**Steps**

1. Create the records/enums listed. ChatMessage text is plain text; senders escape for their format (Slack mrkdwn: escape &, <, >; Teams: TextBlock plain).
2. SlackWebhookSender: build blocks [header(title), section(text), section(fields), actions(buttons with url = action.url)], POST JSON; treat non-2xx or body != 'ok' as failure.
3. TeamsWebhookSender: {"type":"message","attachments":[{"contentType":"application/vnd.microsoft.card.adaptive","content":{AdaptiveCard v1.4 with TextBlock title, TextBlock text, FactSet fields, actions: Action.OpenUrl}}]}; non-2xx = failure.
4. Both: one retry on 429/5xx honouring Retry-After (max 5s), never log the webhook URL (log provider + channel label only).
5. ChatSenderRegistry and ChatConfig. Nothing calls the senders yet.

**Acceptance criteria**

- Given a ChatMessage with two actions, when sent through SlackWebhookSender to a mock server, then the JSON contains an actions block with two url buttons.
- Given the same message through TeamsWebhookSender, then the payload is an Adaptive Card with two Action.OpenUrl entries.
- Given the endpoint returns 429 then 200, then the message is delivered with one retry.
- Given any failure, then the webhook URL does not appear in logs.

**Tests**

- SlackWebhookSenderTest and TeamsWebhookSenderTest with MockRestServiceServer: payload shape (JSON path asserts), escaping, retry, failure exception.

#### E24-T02 — Map environments to chat channels with encrypted webhook storage and a test-message endpoint

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G13 · **Depends on:** E24-T01, E03-T05

G13 asks for per-environment channel mapping; webhook URLs are bearer secrets and must not be stored or returned in clear.

**Files**

- `src/main/resources/db/migration/V<next>__add_chat_channel.sql` (new) — chat_channel(channel_id, environment_id NULL = global default, provider, label, webhook_url_enc TEXT, event_types TEXT (comma list), is_active, created_by, timestamps); index on environment_id
- `src/main/java/com/tcgdigital/vmcontrol/model/ChatChannel.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/ChatChannelRepository.java` (new) — findByEnvironmentIdAndIsActiveTrue, findByEnvironmentIdIsNullAndIsActiveTrue
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/SecretCipher.java` (new) — AES-GCM encrypt/decrypt with key from chat.secret-key (base64, 32 bytes); fails startup when chat.enabled=true and key missing
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatChannelService.java` (new) — CRUD, URL validation (https only; hooks.slack.com or *.webhook.office.com / *.logic.azure.com host allow-list configurable), sendTest
- `src/main/java/com/tcgdigital/vmcontrol/controller/ChatChannelController.java` (new) — /api/v1/chat-channels CRUD + POST /{id}/test; ADMIN, or environment admin for their environment (SecurityService)
- `src/main/java/com/tcgdigital/vmcontrol/dto/ChatChannelDTO.java` (new) — never includes the URL; shows masked host only
- `src/main/resources/static/js/features/environments.js` (modify) — Integrations section on environment detail (admins): list/add/remove channel, event checkboxes, Send test
- `src/main/resources/static/js/config.js` (modify) — Config.API.chatChannels.*

**Steps**

1. Migration, entity, repository.
2. SecretCipher with random 12-byte IV per value, stored as base64(iv|ciphertext). Property chat.secret-key=${CHAT_SECRET_KEY:}.
3. ChatChannelService.create/update validate provider, https URL and host allow-list (chat.allowed-webhook-hosts), event types from NotificationType names; audit CHAT_CHANNEL_CREATED/UPDATED/DELETED (add to AuditAction) without the URL.
4. Controller: environment-scoped channels require SecurityService.assertCanAdministerEnvironment-style check from E03-T01/E03-T05; global default channel (environmentId null) ADMIN only.
5. POST /{id}/test sends 'Test message from VM Control' via ChatSenderRegistry and returns delivered true/false with the error class (no URL).
6. UI section on environment detail for users who can administer it.

**Acceptance criteria**

- Given an environment admin of E, when they add a Teams channel for E, then GET returns the channel with a masked host and no URL, and the DB value is ciphertext.
- Given an http:// or non-allow-listed URL, then 400.
- Given a USER, when calling POST /api/v1/chat-channels, then 403.
- Given 'Send test', then the channel receives the message and the UI shows Delivered.

**Tests**

- SecretCipherTest: round trip, tamper detection.
- ChatChannelControllerIntegrationTest (SecuredWebTestBase): role matrix, URL validation, masked output, test endpoint with mocked sender.

#### E24-T03 — Fan out selected notification events to mapped chat channels

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G13 · **Depends on:** E24-T02, E11-T07, E21

Owners should see operation failures, lock breaks, stop notices, access requests, lease and idle warnings, budget alerts and anomalies where they work (G13).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatDispatchService.java` (new) — dispatch(environmentId, NotificationType, ChatMessage): resolve channels (environment, else global default) subscribed to the type; send async; record delivery
- `src/main/resources/db/migration/V<next>__add_chat_delivery_log.sql` (new) — chat_delivery_log(delivery_id, channel_id, notification_type, environment_id, success, error, sent_at) + index on sent_at
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — after creating bell notifications for environment-wide events, call chatDispatchService.dispatch via AfterCommit
- `src/main/java/com/tcgdigital/vmcontrol/service/DataRetentionService.java` (modify) — purge chat_delivery_log with the email-log retention period

**Steps**

1. ChatDispatchService: if chat.enabled=false return. Channels = active environment channels subscribed to the type; if none, the global default channel when subscribed. Send each on the notificationExecutor; catch everything; write chat_delivery_log.
2. NotificationService: add a private toChat(type, envId, envName, title, message, link) helper building a ChatMessage with linkUrl = chat.public-base-url + '#/environments/' + envId; call it (AfterCommit.run) from notifyOperationFailedForEnvironment, notifyLockBrokenForEnvironment, notifyStopEnvironment, notifyAccessRequestedForReviewers, notifyStateDriftDetected, and the lease/idle/budget/anomaly notify methods added by E15, E16, E20, E21.
3. Actions are added in E24-T04/E24-T05; this task sends messages with only an 'Open in VM Control' link.
4. Use the 'other' (third-person) wording, never 'You ...'.

**Acceptance criteria**

- Given chat.enabled=true and environment E mapped to a Slack channel subscribed to OPERATION_FAILED, when an operation in E fails, then one Slack message with the failure reason and an Open link is sent after commit.
- Given the operation transaction rolls back, then no chat message is sent.
- Given the webhook is down, then the operation and bell notifications are unaffected and a failed chat_delivery_log row exists.
- Given chat.enabled=false, then no HTTP call is made.

**Tests**

- ChatDispatchServiceTest: channel resolution (env, global fallback, unsubscribed type), failure isolation.
- NotificationServiceTest: dispatch invoked for the listed types with third-person wording.

#### E24-T04 — Add signed one-click action links with a confirm page for approve request and stop environment

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G13 · **Depends on:** E24-T03, E03-T06, E03-T10, E02-T03

Chat buttons must act safely for both Slack and Teams without a bot install: a signed, expiring, single-use link that still requires the user's own SSO session and normal authorization (G13).

**Files**

- `src/main/resources/db/migration/V<next>__add_chat_action_token.sql` (new) — chat_action_token(jti PK, action_type, environment_id, target_id, created_at, expires_at, used_at NULL, used_by_user_id NULL)
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatActionTokenService.java` (new) — issue(type, envId, targetId, ttl) -> token = base64url(payload).base64url(HMAC-SHA256(chat.action-signing-key)); verify(token) checks signature, expiry, unused
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatActionService.java` (new) — preview(token, user) and execute(token, user): dispatch per ChatActionType to existing services with the user's own permission checks
- `src/main/java/com/tcgdigital/vmcontrol/controller/ChatActionController.java` (new) — GET /api/v1/chat-actions/{token} (preview), POST /api/v1/chat-actions/{token} (execute); authenticated
- `src/main/resources/static/js/features/chat-action.js` (new) — #/chat-action/<token> page: shows what will happen, Confirm/Cancel, result
- `src/main/resources/static/js/core/router.js` (modify) — route chat-action with token param (E02-T03 route params)
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — access-request and stop-notice chat messages carry action buttons

**Steps**

1. Token payload: {jti, type, envId, targetId, exp}. Signing key chat.action-signing-key (32+ bytes, required when chat.enabled). TTL chat.action-ttl-hours=24. Persist jti on issue; execute marks used_at in the same transaction (UPDATE ... WHERE used_at IS NULL, rows==1).
2. The token never grants rights: execute loads the current session user and calls the existing service methods, which run their own checks: APPROVE_ACCESS_REQUEST -> EnvironmentAccessService.approveRequest(requestId, userId, notes "via chat", ...) (self-approval rules from E03-T06 apply); STOP_ENVIRONMENT -> VmOperationsService.startOperation(envId, userId, STOP for the whole environment) (lock and operate checks apply). EXTEND_LEASE and SNOOZE_IDLE_STOP return 400 'not yet supported' until E24-T05.
3. Unauthenticated GET/POST follow the normal login redirect and return to #/chat-action/<token> afterwards (store the hash before redirect; reuse the login return-URL mechanism if one exists, else add it).
4. Preview returns a description ('Approve Alice's request for Start & stop on Payments QA for 7 days') so the user confirms knowingly; POST requires the CSRF token from E03-T10.
5. Audit every execute (action type, token jti, outcome) with the user as actor and details 'via chat'.
6. NotificationService: access-request chat message gets an 'Approve' button (url = public base + '#/chat-action/' + token) and 'Review' link; stop notice to admins gets 'Stop now' only when chat.actions.stop-enabled=true (default false).

**Acceptance criteria**

- Given a valid token for an access request and a signed-in reviewer, when they confirm, then the request is approved exactly as from the Pending Requests page and audited 'via chat'.
- Given the same token used twice, then the second POST returns 409.
- Given an expired or tampered token, then 400 and nothing happens.
- Given a user without rights on the environment, when they open a STOP_ENVIRONMENT link, then 403 from the normal permission check.
- Given the user is not signed in, when they click the button, then after SSO they land on the confirm page.

**Tests**

- ChatActionTokenServiceTest: sign/verify, expiry, tamper, single use under two concurrent executes (AbstractIntegrationTest).
- ChatActionControllerIntegrationTest (SecuredWebTestBase): approve path, 403 path, CSRF required.

**Notes:** Default chat.actions.stop-enabled=false; enable after a security review of the flow.

#### E24-T05 — Add extend-lease and snooze-idle-stop chat actions

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** medium · **Sources:** G13 · **Depends on:** E24-T04, E15, E16

The most useful chat actions are extending an expiring lease and snoozing an idle auto-stop (G13), which need the lease (E15) and idle auto-stop (E16) services.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatActionService.java` (modify) — EXTEND_LEASE -> lease extend service from E15 with the default extension; SNOOZE_IDLE_STOP -> idle snooze from E16
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — lease-expiring and idle-stop-pending chat messages carry 'Extend 2h' / 'Snooze 4h' buttons
- `src/main/resources/static/js/features/chat-action.js` (modify) — preview text for the new types, optional duration choice within limits

**Steps**

1. Issue tokens with targetId = lease id / environment id when E15/E16 send their warnings; TTL = time until the lease end or stop (min 5 minutes).
2. Execute calls the E15 extend method and the E16 snooze method as the signed-in user; their own limits (maximum lease, who may extend) apply.
3. Preview shows the new end time and the extra estimated cost when E25-T01 is available (else omit).

**Acceptance criteria**

- Given a 15-minute lease warning in Teams, when the holder clicks 'Extend 2h' and confirms, then the lease end moves by 2h and the environment is not stopped.
- Given an extension beyond the environment maximum, then the confirm page shows the limit error and nothing changes.
- Given a snooze click after the stop already ran, then the page says the environment was already stopped.

**Tests**

- ChatActionServiceTest with mocked lease and idle services: success, limit, stale cases.

#### E24-T06 — Add a Jira client and create tickets from rightsizing/idle recommendations and cost anomalies

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G13 · **Depends on:** E24-T01, E23-T01, E21

G13 asks to turn recommendations and anomalies into tickets so the work is tracked where engineering plans it.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/jira/JiraClient.java` (new) — POST /rest/api/3/issue with ADF description, basic auth email + API token (Jira Cloud) or bearer PAT (Data Center); timeouts; error mapping
- `src/main/java/com/tcgdigital/vmcontrol/service/jira/JiraTicketService.java` (new) — createForRecommendation(type, vmId), createForAnomaly(anomalyId); idempotent via external_ticket
- `src/main/resources/db/migration/V<next>__add_external_ticket.sql` (new) — external_ticket(ticket_id, source_type RIGHTSIZING|IDLE|ANOMALY, source_id, system 'JIRA', issue_key, url, created_by, created_at), UNIQUE(source_type, source_id, system)
- `src/main/java/com/tcgdigital/vmcontrol/model/ExternalTicket.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/ExternalTicketRepository.java` (new) — findBySourceTypeAndSourceId
- `src/main/java/com/tcgdigital/vmcontrol/controller/IntegrationController.java` (new) — POST /api/v1/integrations/jira/tickets {sourceType, sourceId} (ADMIN or environment admin of the source's environment); GET /api/v1/integrations/jira/status
- `src/main/resources/application.properties` (modify) — jira.enabled=false, jira.base-url, jira.email, jira.api-token (env only), jira.project-key, jira.issue-type=Task, jira.labels=vmcontrol,finops, jira.project-by-team (optional map teamName->projectKey)

**Steps**

1. JiraClient with RestClient; never log the token; 401/403 -> IntegrationException 'Jira credentials rejected'; 400 -> field errors passed through.
2. Ticket content: summary '[VM Control] Rightsize <vm> (<from> -> <to>), est. $X/month' / '[VM Control] Idle <vm>: $X/month wasted' / '[VM Control] Cost anomaly in <env>: $X since <date>'; description with environment, team (E23), evidence (CPU, days), link back to the app; labels from config; project chosen by team mapping else jira.project-key.
3. Idempotency: if external_ticket exists for the source, return it (200) instead of creating another.
4. Rightsizing/idle source data from the existing CostEstimationService rows (E08-corrected); anomaly data from E21's anomaly entity.
5. Audit JIRA_TICKET_CREATED with issue key.

**Acceptance criteria**

- Given jira.enabled=true, when an admin creates a ticket for a rightsizing candidate, then a Jira issue exists with the summary format above and the response contains its key and URL.
- Given the same source again, then the existing key is returned and no new issue is created.
- Given bad credentials, then 502 with 'Jira credentials rejected' and no external_ticket row.
- Given jira.enabled=false, then the endpoint returns 409 'Jira integration is disabled'.

**Tests**

- JiraClientTest (MockRestServiceServer): payload/ADF shape, auth header, error mapping.
- JiraTicketServiceIntegrationTest: idempotency and project-by-team selection.

#### E24-T07 — Add 'Create Jira ticket' buttons to cost recommendations and anomalies, and an anomaly chat button

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G13 · **Depends on:** E24-T06, E24-T05, E21-T04

Users need the ticket action where they read the recommendation or anomaly, and chat anomaly alerts should offer the same (G13).

**Files**

- `src/main/resources/static/js/features/cost-management.js` (modify) — row action 'Create Jira ticket' / 'View PROJ-123' in idle and rightsizing tables (renderIdleTable, renderRightsizingTable)
- `src/main/resources/static/js/features/environments.js` (modify) — same action on the anomaly list from E21-T04
- `src/main/java/com/tcgdigital/vmcontrol/dto/IdleWasteRowDTO.java` (modify) — jiraKey, jiraUrl (nullable)
- `src/main/java/com/tcgdigital/vmcontrol/dto/RightsizingCandidateDTO.java` (modify) — jiraKey, jiraUrl (nullable)
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — fill jira fields with one batched external_ticket lookup per page
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — anomaly chat message gets an 'Open' link and, when Jira is enabled, a 'Create ticket' link to the confirm page

**Steps**

1. Batch lookup ExternalTicketRepository.findBySourceTypeAndSourceIdIn for the page's VM ids; set jiraKey/jiraUrl.
2. UI button only when GET /integrations/jira/status says enabled; after creation replace the button with a link to the issue.
3. Chat anomaly message: add a ChatAction CREATE_JIRA_TICKET (extend ChatActionType) executed through ChatActionService with the same token rules as E24-T04.

**Acceptance criteria**

- Given Jira is enabled, when an admin clicks 'Create Jira ticket' on an idle VM row, then the row shows the issue key linking to Jira.
- Given Jira is disabled, then no ticket buttons render.
- Given an anomaly alert in Slack, when the owner clicks 'Create ticket' and confirms, then the issue is created once.

**Tests**

- CostEstimationService test for batched jira enrichment; manual QA for UI; ChatActionService test for CREATE_JIRA_TICKET.

## E25

### Cost-aware approvals & quotas — Next

Users see what a start or extension will cost before confirming, large or long runs need approval routed to the right approvers (any one or all), and teams stay within monthly VM-hour or dollar quotas.

Sources: G14 · 8 tasks · 10.5 days

#### E25-T01 — Add a cost preview for starting VMs and extending runtime

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G14 · **Depends on:** E14-T08, E08-T07, E03-T01

The start dialog shows only a time estimate (GET /environments/{id}/operations/time-estimates); G14 asks to show the cost of a start or extension before confirming.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/OperationCostPreviewService.java` (new) — preview(envId, scope groupId/vmId, hours) -> per-VM hourly rate and total
- `src/main/java/com/tcgdigital/vmcontrol/dto/CostPreviewDTO.java` (new) — hours, hourlyTotal, estimatedTotal, currency, vms[{vmId, name, instanceType, hourlyRate, priceKnown}], unknownPriceCount, approximate
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmOperationsController.java` (modify) — GET /cost-preview?groupId=&vmId=&hours= (view rights on the environment)
- `src/main/resources/application.properties` (modify) — cost.preview.default-hours=${COST_PREVIEW_DEFAULT_HOURS:8}, cost.preview.max-hours=720

**Steps**

1. Resolve target VMs with the same scoping as getOperationEstimate (VmOperationsService ~line 243) and group visibility (SecurityService.getVisibleGroupIds); skip VMs already RUNNING (they add no new cost).
2. Hourly rate per VM: instance type from VmInventorySnapshot (inventoryRepository.findByVmVmIdIn), then PricingReferenceService.lookupHourlyRate(provider, instanceType, region); priceKnown=false when unknown; include the 'approximate' flag that E08-T07 adds for region fallbacks. EKS node groups: desired node count x node instance rate when inventory has them, else unknown.
3. hours: request param, default = the environment's default lease duration from E15 when present, else cost.preview.default-hours; clamp to [1, max-hours].
4. estimatedTotal = hourlyTotal x hours (scale 2). Return currency 'USD' (pricing data is USD).
5. Endpoint is read-only and needs view rights (SecurityService.assertCanView from E03-T01).

**Acceptance criteria**

- Given a group with two stopped m5.large VMs priced at $0.096/h, when GET cost-preview?groupId=g&hours=8, then hourlyTotal=0.19 and estimatedTotal=1.54 (rounded per VM then summed, documented in the DTO).
- Given a VM whose instance type has no price, then priceKnown=false, unknownPriceCount=1 and it adds 0 to totals.
- Given a user without view rights, then 404/403 as for other environment reads.
- Given hours=10000, then it is clamped to 720.

**Tests**

- OperationCostPreviewServiceTest: running VMs excluded, unknown prices, clamping, EKS node count.
- VmOperationsControllerIntegrationTest: permission and response shape.

#### E25-T02 — Show the cost preview in the start dialog and the lease-extension dialog

- [ ] Done · **Type:** feature · **Estimate:** 0.5 d · **Risk:** low · **Sources:** G14 · **Depends on:** E25-T01, E15

Users must see the cost before confirming (G14); the start confirm modal in environments.js (~line 1160) currently shows only the time estimate.

**Files**

- `src/main/resources/static/js/features/environments.js` (modify) — load cost preview in parallel with the time estimate; render 'About $X for N h ($Y/h)' with unknown-price note
- `src/main/resources/static/js/config.js` (modify) — Config.API.operations.costPreview(envId, scope, hours)
- `src/main/resources/static/js/features/my-account.js` (modify) — extension form shows the extra cost for the chosen extension (uses hours = extension length)

**Steps**

1. In the start/stop confirm flow, for START and RESTART only, call costPreview with the same scope; place the result under the time estimate in #estimate-placeholder area (separate placeholder id).
2. Format money with Intl.NumberFormat('en-US', {style:'currency', currency:'USD'}); show 'Price unknown for N VM(s)' when unknownPriceCount > 0 and 'approximate' when flagged.
3. Lease extension (E15 UI) and the My Account extension form: call costPreview with hours = chosen extension and show 'Extra ~ $X'.
4. Use ContentRouter.isCurrent to ignore late responses; failures hide the cost line silently.

**Acceptance criteria**

- Given a user opens Start for a group, then within the same dialog they see the time estimate and 'About $1.54 for 8 h ($0.19/h)'.
- Given the preview call fails, then the dialog still works without a cost line.
- Given an extension of 2 h, then the extension form shows the extra cost for 2 h.

**Tests**

- Manual QA checklist; Playwright spec if test/ runs in CI (intercept cost-preview).

#### E25-T03 — Add approval policies and a policy evaluator for large instance sizes, long runtimes and high estimated cost

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G14 · **Depends on:** E25-T01, E23-T02

G14 requires approval for large instance sizes or long runtimes; nothing today can hold a start for review.

**Files**

- `src/main/resources/db/migration/V<next>__add_approval_policy.sql` (new) — approval_policy(policy_id, environment_id NULL = global, team_id NULL, name, instance_type_patterns TEXT, min_vcpu INT NULL, max_runtime_hours INT NULL, max_estimated_cost DECIMAL(12,2) NULL, approver_mode ANY|ALL, is_active, created_by, timestamps); approval_policy_approver(policy_id, approver_type USER|TEAM|ENV_ADMINS, approver_id NULL)
- `src/main/java/com/tcgdigital/vmcontrol/model/ApprovalPolicy.java` (new) — entity + approvers collection
- `src/main/java/com/tcgdigital/vmcontrol/repository/ApprovalPolicyRepository.java` (new) — findActiveForEnvironment(envId, teamId) = global + environment + team policies
- `src/main/java/com/tcgdigital/vmcontrol/service/ApprovalPolicyService.java` (new) — CRUD + evaluate(ApprovalContext) -> ApprovalDecision(ALLOW | REQUIRE_APPROVAL(list of matched policies + reasons))
- `src/main/java/com/tcgdigital/vmcontrol/controller/ApprovalPolicyController.java` (new) — /api/v1/approval-policies CRUD (ADMIN; environment admins for their environment's policies) and POST /evaluate (dry run)
- `src/main/resources/application.properties` (modify) — approvals.enabled=${APPROVALS_ENABLED:false}

**Steps**

1. Migration, entities, repository.
2. ApprovalContext(envId, teamId, requesterId, operationType, List<Vm> targets with instance types, requestedHours, CostPreviewDTO preview). Only START/RESTART and runtime extensions are evaluated; STOP never needs approval.
3. Matching: instance_type_patterns are comma-separated globs (e.g. '*.4xlarge,*.8xlarge,p*,g*'); min_vcpu compares inventory vCPU when available; max_runtime_hours compares requestedHours; max_estimated_cost compares preview.estimatedTotal. Any matching condition in an active policy triggers it; reasons list each match ('m5.8xlarge matches *.8xlarge').
4. Global ADMINs bypass approval only when approvals.admin-bypass=true (default false), so the control is real.
5. Dry-run endpoint returns the decision for a given scope/hours for the UI and for testing policies.
6. Audit APPROVAL_POLICY_CREATED/UPDATED/DELETED.

**Acceptance criteria**

- Given a global policy '*.8xlarge' and a start that includes an m5.8xlarge, when evaluated, then REQUIRE_APPROVAL with that reason.
- Given max_runtime_hours=12 and a request for 24 h, then REQUIRE_APPROVAL.
- Given no matching policy, then ALLOW.
- Given approvals.enabled=false, then evaluate always returns ALLOW.

**Tests**

- ApprovalPolicyServiceTest: glob matching, vCPU, runtime, cost thresholds, environment/team/global layering, disabled flag.
- ApprovalPolicyControllerIntegrationTest: role matrix and dry run.

#### E25-T04 — Hold starts that need approval and route them to approvers with any-one or all-approvers rules

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G14 · **Depends on:** E25-T03, E24-T04

When a policy matches, the start must wait for approval and execute only after the configured approvers agree (G14).

**Files**

- `src/main/resources/db/migration/V<next>__add_approval_request.sql` (new) — approval_request(request_id, request_type START|EXTEND, environment_id, requester_user_id, payload TEXT (JSON), estimated_cost, reasons TEXT, approver_mode, status PENDING|APPROVED|DENIED|EXPIRED|EXECUTED|FAILED, created_at, expires_at, execution_id NULL, version); approval_decision(request_id, approver_user_id, decision, reason, decided_at) PK(request_id, approver_user_id); approval_request_approver(request_id, user_id) snapshot of eligible approvers
- `src/main/java/com/tcgdigital/vmcontrol/model/ApprovalRequest.java` (new) — entity with @Version
- `src/main/java/com/tcgdigital/vmcontrol/service/ApprovalRequestService.java` (new) — create(ctx, decision, dto), approve, deny, list for approver/requester; ANY/ALL completion; execute on completion
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmOperationsController.java` (modify) — POST start: evaluate first; REQUIRE_APPROVAL -> 202 {approvalRequestId, reasons, approvers}
- `src/main/java/com/tcgdigital/vmcontrol/controller/ApprovalRequestController.java` (new) — /api/v1/approval-requests GET (mine/to-review), POST /{id}/approve, POST /{id}/deny, POST /{id}/cancel (requester)
- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — APPROVAL_REQUESTED, APPROVAL_GRANTED, APPROVAL_DENIED
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notify approvers and requester (bell, email flag, chat via E24-T03)

**Steps**

1. Start path: VmOperationsController.startOperation resolves targets and preview, calls ApprovalPolicyService.evaluate; on REQUIRE_APPROVAL create an ApprovalRequest storing the StartOperationDTO JSON, requested hours and preview, snapshot eligible approvers (USER ids; TEAM -> team_member users from E23-T02; ENV_ADMINS -> administering env admins), excluding the requester; if no eligible approver remains fall back to global ADMINs.
2. approve(requestId, userId): user must be in approval_request_approver; record decision; ANY -> complete on first approval; ALL -> complete when every snapshotted approver approved. deny by any approver -> DENIED. Conditional update on status (optimistic @Version) so two approvals cannot execute twice.
3. On completion, execute via VmOperationsService.startOperation(envId, requesterId, dto) inside AfterCommit; the requester's lock and permission checks still apply at execution time; store execution_id; on failure set FAILED with the reason and notify the requester.
4. Expiry: requests expire after approvals.request-ttl-hours (default 24) via a scheduled job (runLocked) -> EXPIRED + notify.
5. Notifications with entityType 'APPROVAL_REQUEST' and entityId; audit APPROVAL_REQUESTED/APPROVED/DENIED/EXPIRED (add to AuditAction).
6. Add ChatActionType APPROVE_OPERATION handled by ChatActionService (E24-T04) calling approve() as the signed-in user.

**Acceptance criteria**

- Given a policy match with mode ANY and approvers A and B, when the requester starts, then the API returns 202, no VM starts, and A and B are notified.
- Given A approves, then the operation starts once as the requester and the request is EXECUTED with the execution id.
- Given mode ALL, when only A approves, then nothing starts until B also approves.
- Given B denies, then the request is DENIED and the requester is notified with the reason.
- Given the requester tries to approve their own request, then 403.
- Given A and B approve at the same moment in ANY mode, then exactly one execution is created.

**Tests**

- ApprovalRequestServiceIntegrationTest (AbstractIntegrationTest, cloud mocked): ANY, ALL, deny, self-approval, concurrent approvals, expiry.
- VmOperationsControllerIntegrationTest: 202 path vs normal path with approvals disabled.

**Notes:** Ships with approvals.enabled=false; enable after the policies are reviewed with environment owners.

#### E25-T05 — Apply approval policies to lease extensions

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** medium · **Sources:** G14 · **Depends on:** E25-T04, E15

Long runtimes also arise from extensions, so extending a lease beyond policy limits must go through the same approval routing (G14).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/ApprovalRequestService.java` (modify) — request_type EXTEND: payload {leaseId, extensionHours}; on completion call the E15 extend method as the requester
- `src/main/java/com/tcgdigital/vmcontrol/controller/LockController.java` (modify) — or the E15 lease controller: evaluate before extending; REQUIRE_APPROVAL -> 202

**Steps**

1. In the E15 extend endpoint, build ApprovalContext with requestedHours = total runtime after extension and preview for the extension hours; evaluate.
2. REQUIRE_APPROVAL -> create EXTEND request (reuse E25-T04 flow) and return 202; the lease warning flow (E15) states 'extension pending approval'.
3. If the lease ends before approval, the request expires with reason 'lease ended'.

**Acceptance criteria**

- Given max_runtime_hours=12 and a lease already at 10 h, when the holder extends by 4 h, then 202 and the lease is unchanged until approval.
- Given approval, then the lease end moves by 4 h.
- Given the lease ends first, then the request becomes EXPIRED.

**Tests**

- ApprovalRequestServiceIntegrationTest: EXTEND type happy path and lease-ended expiry (lease service from E15).

#### E25-T06 — Approvals UI: pending approvals, request status for requesters, and policy management

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G14 · **Depends on:** E25-T05, E25-T02

Approvers need a queue and requesters need to see why their start is waiting; admins need to manage policies (G14).

**Files**

- `src/main/resources/static/js/features/access-requests.js` (modify) — Pending Requests page gains an 'Operation approvals' tab (approve/deny with reason, shows scope, VMs, hours, estimated cost, reasons, other approvers' decisions)
- `src/main/resources/static/js/features/environments.js` (modify) — handle 202 from start: info toast + banner 'Waiting for approval by ...' with Cancel
- `src/main/resources/static/js/features/approval-policies.js` (new) — #/approval-policies admin page: list, editor (patterns, vCPU, hours, cost, approvers, ANY/ALL), dry-run tester
- `src/main/resources/static/js/core/router.js` (modify) — route approval-policies
- `src/main/resources/static/index.html` (modify) — Admin link and script tag
- `src/main/resources/static/js/ui/notification-bell.js` (modify) — icons and link for APPROVAL_* types (entityType APPROVAL_REQUEST -> #/pending-requests)

**Steps**

1. Approvals tab lists GET /approval-requests?role=approver; decisions with a required reason for deny; confirm dialog shows estimated cost.
2. Start flow: on 202 show the banner on environment detail with approver names and Cancel (POST /cancel); poll status with RealTime.startPolling(..., {pageScoped:true}) until EXECUTED/DENIED/EXPIRED.
3. Policy page per the E13 page-header pattern; dry run calls POST /approval-policies/evaluate.
4. Pending badge count includes operation approvals.

**Acceptance criteria**

- Given an approver opens Pending Requests, then they see the operation approval with VMs, hours, cost and reasons, and can approve or deny.
- Given a requester's start returned 202, then the environment page shows the waiting banner and updates to 'Started' after approval without reload.
- Given an admin edits a policy and runs a dry run for a group, then the decision and reasons are shown.

**Tests**

- Manual QA checklist; Playwright spec if test/ runs in CI.

#### E25-T07 — Add per-team monthly quotas on running VM-hours or dollars and enforce them at start

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G14 · **Depends on:** E25-T06, E23-T02, E17-T03

G14 asks for per-team quotas (running VM-hours or $) so a team cannot exceed its allowance silently.

**Files**

- `src/main/resources/db/migration/V<next>__add_team_quota.sql` (new) — team_quota(quota_id, team_id, metric VM_HOURS|COST, limit_value DECIMAL(14,2), enforcement WARN|REQUIRE_APPROVAL|BLOCK, warn_percent INT DEFAULT 80, is_active, timestamps) UNIQUE(team_id, metric)
- `src/main/java/com/tcgdigital/vmcontrol/model/TeamQuota.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/service/QuotaUsageService.java` (new) — monthToDate(teamId): VM-hours from vm_state_history running intervals (or the E17 ledger runtime hours when present) and cost from cost_daily_snapshot plus today's estimate, over the team's environments
- `src/main/java/com/tcgdigital/vmcontrol/service/QuotaService.java` (new) — CRUD; check(teamId, preview) -> OK | WARN | REQUIRE_APPROVAL | BLOCK with projected usage
- `src/main/java/com/tcgdigital/vmcontrol/service/ApprovalPolicyService.java` (modify) — evaluate() includes the quota check result (REQUIRE_APPROVAL adds team approvers; BLOCK returned as a separate decision)
- `src/main/java/com/tcgdigital/vmcontrol/controller/TeamController.java` (modify) — GET/PUT /teams/{id}/quotas (ADMIN), GET /teams/{id}/quota-usage (team members, admins)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/QuotaWarningScheduler.java` (new) — hourly: notify team members once per month when usage crosses warn_percent and 100%
- `src/main/resources/application.properties` (modify) — quotas.enabled=${QUOTAS_ENABLED:false}

**Steps**

1. Migration/entity; QuotaUsageService with one aggregate query per metric (no per-VM loops).
2. Projection at start: VM_HOURS -> usage + (number of VMs that will start x requested hours); COST -> usage + preview.estimatedTotal.
3. Enforcement: WARN -> allow and return a warning in the start response; REQUIRE_APPROVAL -> route through E25-T04 with approver type TEAM (team members with ENV ADMIN level) or ADMINs; BLOCK -> 409 'Team quota exceeded: X of Y used'. Global ADMIN override requires approvals.admin-bypass as in E25-T03.
4. QuotaWarningScheduler via runLocked; dedupe with Notification createIfAbsent semantics keyed by quota id + month.
5. All behaviour off unless quotas.enabled=true.

**Acceptance criteria**

- Given a team VM_HOURS quota of 1000 with 990 used and BLOCK, when a member starts 4 VMs for 8 h, then 409 with the usage message and nothing starts.
- Given enforcement REQUIRE_APPROVAL, then the start returns 202 and is routed to approvers.
- Given usage crosses 80%, then team members get one notification that month.
- Given quotas.enabled=false, then starts are never blocked by quotas.

**Tests**

- QuotaUsageServiceIntegrationTest: VM-hours from seeded state history across month boundary; cost from snapshots.
- QuotaServiceTest: projection and each enforcement mode.
- VmOperationsControllerIntegrationTest: 409 BLOCK path.

#### E25-T08 — Quota management and usage display

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G14 · **Depends on:** E25-T07

Admins must set quotas and team members must see remaining allowance before starting (G14).

**Files**

- `src/main/resources/static/js/features/teams.js` (modify) — Quotas section per team: metric, limit, enforcement, warn %, usage bar
- `src/main/resources/static/js/features/environments.js` (modify) — start dialog shows 'Team quota: X of Y h used; this start adds ~Z' and the enforcement outcome
- `src/main/resources/static/js/features/my-account.js` (modify) — My Account shows my teams' quota usage

**Steps**

1. Teams page quota editor calling PUT /teams/{id}/quotas; usage bar from GET /teams/{id}/quota-usage.
2. Start dialog: call quota-usage for the environment's team alongside the cost preview; show a warning style when projected > warn %, error style when BLOCK would trigger (button disabled with explanation).
3. My Account: small card per team with both metrics.

**Acceptance criteria**

- Given a team at 85% of its VM-hour quota, when a member opens Start, then the dialog shows the projected usage in warning style.
- Given BLOCK would trigger, then the Start button is disabled with the reason.
- Given an admin sets a COST quota, then the Teams page shows the usage bar in dollars.

**Tests**

- Manual QA checklist; Playwright spec if test/ runs in CI.

## E26

### EKS cost & idle coverage — Next

EKS node groups get utilisation metrics so idle detection and rightsizing cover them, their estimated cost includes node disks and the cluster control-plane fee, and pod/namespace cost from Split Cost Allocation Data is shown where the account exports it.

Sources: G15 · 5 tasks · 7.5 days

#### E26-T01 — Collect node-group metrics from CloudWatch by Auto Scaling group so EKS VMs get samples and idle summaries

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G15 · **Depends on:** E09-T07, E12-T03

G15: CloudMetricsProviderFactory has no provider for AWS_EKS, so VmMetricsService.syncRunningVmMetrics skips every node group and idle detection/rightsizing never see EKS.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/AwsEksMetricsProviderService.java` (new) — CloudMetricsProviderService for AWS_EKS using AWS/EC2 metrics with AutoScalingGroupName dimension; optional Container Insights memory
- `src/main/java/com/tcgdigital/vmcontrol/service/EksSyncService.java` (modify) — store nodegroup.resources().autoScalingGroups() names and diskSize in Vm metadata (merge, do not drop minSize/desiredSize)
- `src/main/resources/application.properties` (modify) — eks.metrics.enabled=false, eks.metrics.container-insights.enabled=false
- `src/test/java/com/tcgdigital/vmcontrol/service/AwsEksMetricsProviderServiceTest.java` (new) — query building and aggregation
- `src/test/java/com/tcgdigital/vmcontrol/service/EksSyncServiceTest.java` (modify) — asgNames/diskSize persisted

**Steps**

1. EksSyncService.upsertNodeGroup: from the Nodegroup already fetched (E09-T07) read resources().autoScalingGroups() names and diskSize; merge them into Vm metadata as asgNames (array) and diskSizeGib using the same merge approach as mergeInstanceTypeIntoMetadata.
2. AwsEksMetricsProviderService implements CloudMetricsProviderService: getProvider() = AWS_EKS; isAvailable() = credentials present && eks.metrics.enabled. fetchMetricSeries/fetchLatestMetrics (E12-T03 interface): input ids are 'cluster/nodegroup'; resolve asgNames via VmRepository.findByProviderAndProviderVmId(AWS_EKS, id) metadata; query AWS/EC2 CPUUtilization (Average), NetworkIn/NetworkOut/DiskReadBytes/DiskWriteBytes (Sum) with dimension AutoScalingGroupName per ASG; combine multiple ASGs per node group (avg CPU, summed bytes). When container-insights is enabled also query ContainerInsights node_memory_utilization (Average) with ClusterName dimension as a cluster-wide memory figure.
3. Results are keyed by the node group's providerVmId so VmMetricsService stores them unchanged. A node group scaled to 0 is not RUNNING and is skipped like a stopped EC2 VM.
4. Register as a Spring @Service so CloudMetricsProviderFactory picks it up from the provider list.

**Acceptance criteria**

- Given eks.metrics.enabled=true and a RUNNING node group with ASG 'eks-ng-abc', when the metrics sync runs, then vm_metric_sample rows are stored for that node group's Vm.
- Given 6 consecutive samples under the idle thresholds, then its VmIdleSummary is idle=true.
- Given eks.metrics.enabled=false, then no CloudWatch call is made for EKS VMs.
- Given metadata without asgNames (not yet re-synced), then the node group is skipped with a debug log, not an error.

**Tests**

- AwsEksMetricsProviderServiceTest (mock CloudWatchClient): dimension AutoScalingGroupName used, multi-ASG aggregation, flag off.
- EksSyncServiceTest: asgNames and diskSizeGib merged without losing minSize/desiredSize.

**Notes:** Read-only cloudwatch:GetMetricData. CPU is the ASG average per node, which is what node-group rightsizing needs.

#### E26-T02 — Include EKS node groups in idle detection, idle-waste and rightsizing views

- [ ] Done · **Type:** enable · **Estimate:** 1 d · **Risk:** low · **Sources:** G15 · **Depends on:** E26-T01, E16 (idle auto-stop), E17 (savings ledger)

G15: with metrics present, idle and rightsizing outputs must treat a node group as N nodes of one instance type.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — idle-waste and rightsizing candidates include AWS_EKS rows with node count; rightsizing for EKS keeps the local sizing map (lines ~356-364)
- `src/main/java/com/tcgdigital/vmcontrol/dto/IdleWasteRowDTO.java` (modify) — nodeCount field
- `src/main/java/com/tcgdigital/vmcontrol/dto/RightsizingCandidateDTO.java` (modify) — nodeCount field; savings x nodeCount
- `src/main/resources/static/js/features/cost-management.js` (modify) — show 'EKS - N nodes' in idle and rightsizing tables
- `src/test/java/com/tcgdigital/vmcontrol/service/CostEstimationServiceTest.java` (modify) — EKS rows

**Steps**

1. Read CostEstimationService's idle-waste and rightsizing builders; where they filter by provider or require a VmInventorySnapshot, add the AWS_EKS path using EstimatedCostProvider.resolveEksNodeInfo-equivalent data (make that helper package-visible or move it to a small EksNodeInfoResolver component).
2. Idle waste cost for EKS = hourly rate x nodeCount x idle hours. Rightsizing savings = (current - suggested hourly rate) x nodeCount x 730.
3. cost-management.js: label EKS rows with node count; apply-rightsizing for EKS keeps the existing behaviour.
4. Coordinate with E16 (idle auto-stop): if E16 excluded AWS_EKS for lack of metrics, add a property-gated opt-in (idle.auto-stop.include-eks=false) rather than enabling silently.

**Acceptance criteria**

- Given an idle 3-node m5.large node group, when the idle-waste view loads, then it shows one row with 3 nodes and cost = 3 x hourly rate x idle hours.
- Given a node group averaging 8% CPU for the rightsizing window, then a candidate is listed with savings multiplied by node count.

**Tests**

- CostEstimationServiceTest: EKS idle-waste row and rightsizing candidate math with nodeCount.

#### E26-T03 — Add the EKS control-plane fee and node disk cost to node-group and environment cost estimates

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G15 · **Depends on:** E08 (cost data correctness), E26-T02

G15: EstimatedCostProvider prices EKS as hourly rate x desiredSize only; it omits the per-cluster control-plane fee ($0.10/hour standard support) and node EBS disks.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/EstimatedCostProvider.java` (modify) — node disk storage = diskSizeGib x nodeCount; control-plane fee per EKS environment
- `src/main/java/com/tcgdigital/vmcontrol/service/PricingReferenceService.java` (modify) — getEksControlPlaneHourlyRate() from eks.pricing.control-plane-hourly (default 0.10; extended-support 0.60 via property)
- `src/main/java/com/tcgdigital/vmcontrol/service/CostSnapshotService.java` (modify) — daily snapshot adds a control-plane line per active EKS environment
- `src/main/java/com/tcgdigital/vmcontrol/dto/CostSummaryDTO.java` (modify) — eksControlPlaneCost
- `src/main/resources/static/js/features/cost-management.js` (modify) — show control-plane line in environment spend and VM detail
- `src/test/java/com/tcgdigital/vmcontrol/service/EstimatedCostProviderTest.java` (modify) — disk and control-plane cases

**Steps**

1. In EstimatedCostProvider's EKS branch, storageGib = diskSizeGib (from metadata, E26-T01) x nodeCount, priced with the existing storage rate and prorated for runtime like EC2 volumes (node volumes are deleted when nodes scale to 0).
2. Control plane: charged for every hour the EKS environment is active regardless of node state. Compute it per environment (not per node group) as rate x window hours and expose it as a separate environment-level line; do not spread it across node groups.
3. Read CostSnapshotService to find where per-environment daily estimates are written and add the control-plane amount to EKS environments' estimates with a clear field/label; keep EC2 environments unchanged.
4. Cost Management UI: 'EKS control plane' row under each EKS environment and in the summary.

**Acceptance criteria**

- Given an active EKS environment over a 30-day window, then its estimate includes 0.10 x 720 = 72.00 control-plane cost even when all node groups are scaled to 0.
- Given a 3-node group with 50 GiB disks running all window, then 150 GiB of storage is included.
- Given an EC2 environment, then its estimates are unchanged.

**Tests**

- EstimatedCostProviderTest: disk x nodes, control plane once per environment, EC2 unaffected.
- CostSnapshotService test (existing or new) for the EKS line.

**Notes:** Reconciliation with Cost Explorer actuals (CostReconciliationService) should treat the control-plane line as part of the environment total; check E08's reconciliation rules at implementation time.

#### E26-T04 — Ingest pod/namespace cost from Split Cost Allocation Data via Athena (opt-in)

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G15 · **Depends on:** E10-T02, E26-T03

G15: pod-level cost for EKS is only available through Split Cost Allocation Data in the CUR 2.0 / Data Exports, queried from S3 (Athena).

**Files**

- `build.gradle` (modify) — implementation 'software.amazon.awssdk:athena' (BOM-managed)
- `src/main/resources/db/migration/V<next>__add_eks_workload_cost_daily.sql` (new) — eks_workload_cost_daily(environment_id, usage_date, namespace, workload, split_cost, unused_cost, PK) (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/EksWorkloadCostDaily.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/EksWorkloadCostDailyRepository.java` (new) — upsert/find by environment and date range
- `src/main/java/com/tcgdigital/vmcontrol/service/EksSplitCostIngestionService.java` (new) — Athena query, poll, page results, upsert
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/EksSplitCostScheduler.java` (new) — daily cron, runLocked("eks_split_cost"), @ConditionalOnProperty eks.scad.enabled
- `src/main/java/com/tcgdigital/vmcontrol/service/SchedulerJobCatalog.java` (modify) — register eks_split_cost
- `src/main/resources/application.properties` (modify) — eks.scad.enabled=false, eks.scad.athena.database, .table, .workgroup, .output-location, eks.scad.lookback-days=3
- `src/test/java/com/tcgdigital/vmcontrol/service/EksSplitCostIngestionServiceTest.java` (new) — query text, paging, mapping to environments

**Steps**

1. Add the athena SDK module; create an AthenaClient (static credentials like the other services, region from eks.scad.athena.region or aws.region).
2. Query (parameterised dates, configured table): SELECT DATE(line_item_usage_start_date) d, split_line_item_parent_resource_id cluster_arn, resource_tags/labels for namespace and workload (use the CUR 2.0 split columns: split_line_item_split_cost, split_line_item_unused_cost, and the Kubernetes namespace/workload attributes present in the export; confirm exact column names against the account's export schema), SUM(...) GROUP BY d, cluster, namespace, workload for the last lookback-days.
3. StartQueryExecution -> poll GetQueryExecution until SUCCEEDED/FAILED (timeout 10 min) -> GetQueryResults paginator. Map cluster ARN name to Environment via eksClusterName (E10-T02); skip unknown clusters with a counter.
4. Upsert rows per (environment_id, usage_date, namespace, workload) in short transactions; audit SCHEDULED_JOB_EXECUTED with counts.
5. If the table or columns do not exist, log a clear warning ('Split Cost Allocation Data not enabled in Data Exports') and exit without failing other jobs.

**Acceptance criteria**

- Given eks.scad.enabled=false, then no Athena call is made and the scheduler bean is absent.
- Given an export with rows for cluster 'MyCluster' namespace 'payments', when ingestion runs, then eks_workload_cost_daily has the summed split and unused cost for that day under the matching environment.
- Given a query FAILED state, then the job logs the Athena error and no rows change.

**Tests**

- EksSplitCostIngestionServiceTest (mock AthenaClient): polling, pagination, cluster-to-environment mapping, missing-table handling.

**Notes:** Opt-in: needs Data Exports (CUR 2.0) with split cost allocation enabled, an Athena table, and athena:StartQueryExecution/GetQueryExecution/GetQueryResults, glue:GetTable, s3:GetObject/PutObject on the output location.

#### E26-T05 — Show namespace and workload cost for EKS environments

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G15 · **Depends on:** E18 (cost for owners), E26-T04

G15: owners need pod-level cost in the environment view, not just node-group totals.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /eks-workload-cost?environmentId&days (ADMIN)
- `src/main/java/com/tcgdigital/vmcontrol/dto/EksWorkloadCostDTO.java` (new) — namespace, workload, splitCost, unusedCost, share
- `src/main/resources/static/js/features/cost-management.js` (modify) — EKS environment drill-down table: namespace -> workloads, unused (idle) cost highlighted
- `src/test/java/com/tcgdigital/vmcontrol/controller/EksWorkloadCostControllerTest.java` (new) — role matrix and bounds

**Steps**

1. Endpoint: days bounded 1..90 (400 otherwise); aggregate eks_workload_cost_daily by namespace and workload; include total unused cost (capacity paid for but not requested).
2. cost-management.js: in the environment spend view, for EKS environments add a 'Workloads' tab with namespace rows expandable to workloads and an 'Unused capacity' row; empty state explains how to enable Split Cost Allocation Data. Use Utils.html and the router stale-render guard (E02).
3. Run .\build-frontend.ps1.
4. If E18 (cost for owners) exposes environment cost to ENV_ADMIN/USER, add the same tab there behind the same role rules.

**Acceptance criteria**

- Given ingested rows for 'payments' (40.00) and 'search' (10.00) and 5.00 unused, when an ADMIN opens the EKS environment's Workloads tab for 7 days, then namespaces show 40.00 and 10.00 and Unused 5.00.
- Given days=365, then 400.
- Given no data, then the empty state with enablement guidance shows.

**Tests**

- EksWorkloadCostControllerTest (SecuredWebTestBase): ADMIN 200, others 403, days bounds, aggregation.

## E27

### Commitment-aware savings — Next

FinOps can see how schedules interact with Savings Plan/RI coverage, what a schedule-safe commitment floor is, and AWS's own purchase recommendations. The view is display only; nothing is purchased.

Sources: G16 · 3 tasks · 4.5 days

#### E27-T01 — Ingest Savings Plans and Reserved Instance purchase recommendations weekly

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G16 · **Depends on:** E08-T02, E08-T10

G16 asks to pull AWS purchase recommendations (GetSavingsPlansPurchaseRecommendation, GetReservationPurchaseRecommendation) so FinOps sees them next to schedule data.

**Files**

- `src/main/resources/db/migration/V<next>__add_commitment_recommendation.sql` (new) — commitment_recommendation table (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/CommitmentRecommendation.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/CommitmentRecommendationRepository.java` (new) — latest by type
- `src/main/java/com/tcgdigital/vmcontrol/service/CommitmentRecommendationService.java` (new) — fetch + parse SP and RI recommendations (paginated)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ReservationCoverageScheduler.java` (modify) — weekly (Monday) call to capture recommendations after the coverage snapshot
- `src/main/resources/application.properties` (modify) — cost.commitments.recommendations.enabled=true, term, payment option, lookback
- `docs/aws-iam-permissions.md` (modify) — ce:GetSavingsPlansPurchaseRecommendation, ce:GetReservationPurchaseRecommendation
- `src/test/java/com/tcgdigital/vmcontrol/service/CommitmentRecommendationServiceTest.java` (new) — parsing

**Steps**

1. Migration: commitment_recommendation (rec_id VARCHAR(36) PK, generated_on DATE NOT NULL, rec_type VARCHAR(30) NOT NULL (COMPUTE_SP, EC2_INSTANCE_SP, EC2_RI), term VARCHAR(10), payment_option VARCHAR(20), lookback_days INT, hourly_commitment DECIMAL(12,4) NULL, upfront_cost DECIMAL(12,2) NULL, estimated_monthly_savings DECIMAL(12,2), estimated_savings_percent DECIMAL(6,2), estimated_on_demand_cost DECIMAL(12,2), details TEXT (JSON of the top lines: instance family, region, count), created_at, UNIQUE(generated_on, rec_type, term, payment_option)).
2. CommitmentRecommendationService.capture() uses CostExplorerClientProvider (E08-T02). (a) For COMPUTE_SP and EC2_INSTANCE_SP: GetSavingsPlansPurchaseRecommendation(SavingsPlansType, TermInYears from cost.commitments.term (default ONE_YEAR), PaymentOption from cost.commitments.payment-option (default NO_UPFRONT), LookbackPeriodInDays from cost.commitments.lookback (default THIRTY_DAYS)) with a nextPageToken loop; read savingsPlansPurchaseRecommendationSummary (hourlyCommitmentToPurchase, estimatedMonthlySavingsAmount, estimatedSavingsPercentage, currentOnDemandSpend). (b) For EC2_RI: GetReservationPurchaseRecommendation(Service 'Amazon Elastic Compute Cloud - Compute', same term/payment/lookback) with a nextPageToken loop; sum recommendationDetails into one row and keep the top 10 details in JSON.
3. Capture each call independently (try/catch per type, as ReservationCoverageService does) so a missing permission for one does not blank the others.
4. Schedule: in ReservationCoverageScheduler, after captureDailySnapshot, call capture() when today is Monday (boundary.today().getDayOfWeek()) and cost.commitments.recommendations.enabled is true. That is 3 billed calls a week. It is read-only, so on by default, consistent with E08-T10.
5. Document the IAM actions.

**Acceptance criteria**

- Given AWS returns a Compute SP recommendation of $1.20/h saving $300/month, when Monday's capture runs, then a COMPUTE_SP row stores those numbers.
- Given the RI recommendation call is denied, then the SP rows are still stored and a warning is logged.
- Given capture runs twice on the same Monday, then rows are upserted, not duplicated.

**Tests**

- CommitmentRecommendationServiceTest (mocked client): SP summary parsing, RI detail aggregation and top-10 JSON, pagination, a per-type failure isolated.

**Notes:** Display only: no purchase APIs are called or added.

#### E27-T02 — Compute how schedules interact with commitments and a schedule-safe commitment floor

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G16 · **Depends on:** E27-T01, E17-T03

Schedules and commitments interact. Hours already covered by a Savings Plan save nothing when stopped, and AWS recommendations based on pre-schedule usage can over-commit. FinOps needs both numbers (G16).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CommitmentInsightService.java` (new) — covered-but-scheduled hours, always-on floor, recommendation risk
- `src/main/java/com/tcgdigital/vmcontrol/dto/CommitmentInsightDTO.java` (new) — coverage, utilization, scheduledHoursCoveredValue, alwaysOnHourlyFloor, recommendations[], riskFlags[]
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /commitments/insights (ADMIN)
- `src/test/java/com/tcgdigital/vmcontrol/service/CommitmentInsightServiceTest.java` (new) — floor and flags

**Steps**

1. scheduledHoursCoveredValue (last 30 days): the sum over savings_ledger_entry (E17) of commitment_adjustment, i.e. the value of avoided hours that were already paid for by commitments. Also return the gross saving the schedules would have produced with no commitments.
2. alwaysOnHourlyFloor: from vm_state_history over the last 30 days, compute for each UTC hour the On-Demand $ of VMs RUNNING in that hour (rate from PricingReferenceService), then take the 10th-percentile hour. That is the spend that runs even in the quietest hours, which is safe to commit to. Implement it as a pure method over (vm, intervals, rate) so it is unit-testable; reuse VmCostCalculator-style interval walking. Bound it to AWS VMs with known prices and note EKS exclusion.
3. Risk flags: (a) for each SP recommendation, if hourly_commitment > alwaysOnHourlyFloor x 1.1, flag 'Recommendation exceeds the always-on floor; schedules may leave the commitment under-used'; (b) if the latest sp_utilization_percent or ri_utilization_percent < 90, flag 'Existing commitments under-utilised'; (c) if coverage > 80% and schedules cover many hours, flag 'Most scheduled hours are already covered by commitments; extra schedules save little compute'.
4. Controller: GET /api/v1/cost-management/commitments/insights, @PreAuthorize("hasRole('ADMIN')"), returning the DTO with the latest reservation_coverage_snapshot, the latest recommendations (E27-T01) and the computed values.

**Acceptance criteria**

- Given 20 always-on VMs at $0.10/h and 30 scheduled VMs, when insights are computed, then alwaysOnHourlyFloor is about $2.00/h.
- Given an SP recommendation of $3.50/h with a floor of $2.00/h, then the over-commit risk flag is present.
- Given ledger entries with commitment adjustments totalling $120, then scheduledHoursCoveredValue = $120.

**Tests**

- CommitmentInsightServiceTest: percentile floor on synthetic intervals, each risk flag, empty data returns nulls not zeros.

**Notes:** All numbers are estimates from the static pricing reference; label them so in the UI.

#### E27-T03 — Add a Commitments panel to Cost Management (display only)

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G16 · **Depends on:** E27-T02, E08-T09

FinOps needs coverage and utilisation trends, the schedule-safe floor and AWS recommendations side by side, with a clear note that purchasing stays in AWS (G16).

**Files**

- `src/main/resources/static/js/features/cost-management.js` (modify) — Commitments panel: KPIs, floor vs recommendation chart, recommendations table, risk flags; extends the existing reservations chart section
- `src/main/resources/static/css/features/cost-management.css` (modify) — panel styles
- `src/main/resources/static/js/config.js` (modify) — costManagement.commitmentInsights
- `src/main/resources/static/dist/app.js` (modify) — regenerated by build-frontend.ps1 (never hand-edit)

**Steps**

1. Next to buildReservationsChart(), add buildCommitmentsPanel(): KPI tiles (SP coverage, RI coverage, SP utilisation, the value of scheduled hours already covered), a small bar comparing 'Always-on floor $/h' with each recommendation's 'Recommended commitment $/h', and a recommendations table (type, term, payment, hourly commitment, est. monthly savings, savings %, generated on).
2. Risk flags appear as an alert list above the table. Footer note: 'Recommendations come from AWS Cost Explorer. Purchases are made by FinOps in the AWS console; this page never buys anything.'
3. Load it with the page's allSettled pattern and per-panel error (E08-T09). Include the new chart in the PNG export id list.
4. Empty state when recommendations are disabled or not yet captured: 'No recommendations yet; they are captured every Monday.' with a link to Cost Setup.
5. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given recommendations and coverage data, when an admin opens Cost Management, then the Commitments panel shows the floor, the recommendations and any risk flags.
- Given the insights endpoint fails, then only the Commitments panel shows an error.

**Tests**

- Manual checklist (data, empty, error, PNG export includes the new chart).

## E28

### Multi-account reach — Next

The platform controls, discovers and costs EC2/EKS resources across many AWS accounts via an account registry and STS AssumeRole, with IAM-role (default credential chain) support and existing data migrated to a default account.

Sources: G17 · 12 tasks · 15 days

#### E28-T01 — Introduce AwsClientFactory with default-credential-chain support and move AwsConfig and Ec2Service onto it

- [ ] Done · **Type:** refactor · **Estimate:** 1 d · **Risk:** medium · **Sources:** G17 · **Depends on:** E00-T01

G17: AWS credentials are a single static key re-read in 10 places (AwsConfig plus 9 services each building StaticCredentialsProvider). One factory is the prerequisite for per-account credentials and lets the app run on an IAM role without keys.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsClientFactory.java` (new) — Base credential resolution (static key or DefaultCredentialsProvider), cached clients per service+region, shared timeouts, @PreDestroy close
- `src/main/java/com/tcgdigital/vmcontrol/config/AwsConfig.java` (modify) — awsCredentialsProvider and stsClient beans delegate to the factory; drop the unused Ec2Client bean
- `src/main/java/com/tcgdigital/vmcontrol/service/Ec2Service.java` (modify) — createEc2Client(region) (L30) replaced by factory.ec2(region); remove try-with-resources that would close a cached client (L45, L154, L220, listInstances L357)
- `src/main/resources/application.properties` (modify) — aws.access-key/aws.secret-key get empty defaults; add aws.credentials.use-default-chain
- `.env.example` (modify) — Document AWS_USE_DEFAULT_CREDENTIAL_CHAIN and that keys may be blank on an IAM role
- `src/test/java/com/tcgdigital/vmcontrol/service/aws/AwsClientFactoryTest.java` (new) — Credential resolution and caching tests
- `src/test/java/com/tcgdigital/vmcontrol/AwsConfigurationTest.java` (modify) — Keep bean-existence asserts for AwsCredentialsProvider and StsClient

**Steps**

1. Create package com.tcgdigital.vmcontrol.service.aws and class AwsClientFactory (@Component). Inject @Value aws.access-key:, aws.secret-key:, aws.region:ap-south-1, aws.credentials.use-default-chain:false.
2. In @PostConstruct resolve base credentials once: both keys non-blank -> StaticCredentialsProvider(AwsBasicCredentials); else use-default-chain=true -> DefaultCredentialsProvider.create() (env vars, profile, ECS/EC2 instance role, IRSA); else none. Expose boolean isConfigured() and AwsCredentialsProvider baseCredentials() (IllegalStateException when not configured). Log the chosen mode, never key values.
3. Add a private static ClientOverrideConfiguration DEFAULT_OVERRIDES (apiCallTimeout 30s, apiCallAttemptTimeout 25s - the values used today in AwsCloudProviderService.getEc2Client L749).
4. Add cached accessors keyed by "<service>|<region>" in a ConcurrentHashMap<String, SdkClient>: Ec2Client ec2(String region), CloudWatchClient cloudWatch(String region), EksClient eks(String region), ComputeOptimizerClient computeOptimizer(String region), CostExplorerClient costExplorer() (always Region.US_EAST_1), StsClient sts(String region). Null/blank region falls back to aws.region. @PreDestroy closeAll() closes every cached client.
5. AwsConfig: awsCredentialsProvider() returns factory.baseCredentials() when configured, else a provider whose resolveCredentials() throws IllegalStateException("AWS credentials not configured") so the context still starts without AWS config, as today. stsClient() returns factory.sts(null). Delete the ec2Client bean after confirming with grep that nothing injects Ec2Client (nothing in src/main does at review time).
6. Ec2Service: inject AwsClientFactory instead of AwsCredentialsProvider; replace createEc2Client(region) with factory.ec2(region); turn every `try (Ec2Client ec2Client = createEc2Client(region))` into a plain local variable so cached clients are not closed. If an earlier epic removed Ec2Service/Ec2Controller start/stop endpoints, only update what remains.
7. application.properties: aws.access-key=${AWS_ACCESS_KEY:} and aws.secret-key=${AWS_SECRET_KEY:}; add aws.credentials.use-default-chain=${AWS_USE_DEFAULT_CREDENTIAL_CHAIN:false} with a comment. Update .env.example (placeholder only).
8. AwsClientFactoryTest (plain JUnit + ReflectionTestUtils for @Value fields, then call init): static keys -> isConfigured and StaticCredentialsProvider; blank keys + chain=false -> not configured; blank keys + chain=true -> DefaultCredentialsProvider; ec2("eu-west-1") twice returns same instance; ec2(null) uses aws.region; closeAll is idempotent.

**Acceptance criteria**

- Given AWS_ACCESS_KEY/AWS_SECRET_KEY are set, when the app starts, then behaviour is unchanged and Ec2Service reuses one cached client per region.
- Given no keys and AWS_USE_DEFAULT_CREDENTIAL_CHAIN=true on a host with an instance role, when AWS calls run, then they authenticate with the instance role.
- Given no keys and the flag false, when the app starts, then the context starts and AWS features report unavailable as today.

**Tests**

- AwsClientFactoryTest: credential mode selection, region fallback, per-region caching, closeAll
- AwsConfigurationTest still green

**Notes:** Verified construction-site inventory: AwsConfig L26-46 (StaticCredentialsProvider, StsClient, Ec2Client beans); Ec2Service.createEc2Client L30 (new client per call); AwsCloudProviderService.getEc2Client L749; AwsCloudInventoryProviderService.getEc2Client L158; AwsCloudMetricsProviderService.getCloudWatchClient L141; EksCloudProviderService.getEksClient L493; ComputeOptimizerService.getClient L119; CostExplorerBillingService.getClient L222; CostExplorerTagActivationService.getClient L78; ReservationCoverageService.getClient L175. The last eight move in E28-T02.

#### E28-T02 — Move the eight provider and cost services' private client caches onto AwsClientFactory

- [ ] Done · **Type:** refactor · **Estimate:** 1 d · **Risk:** medium · **Sources:** G17 · **Depends on:** E28-T01

G17: each service keeps its own client cache and copy of the static key; per-account credentials can only be added once all clients come from one place.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — Drop accessKey/secretKey/clientCache (L51-60); getEc2Client delegates to factory; isAvailable() (L558) = factory.isConfigured()
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudInventoryProviderService.java` (modify) — Same for L26-35, isAvailable L43, getEc2Client L158
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudMetricsProviderService.java` (modify) — Same for L37-46, isAvailable L54, getCloudWatchClient L141
- `src/main/java/com/tcgdigital/vmcontrol/service/EksCloudProviderService.java` (modify) — Same for L41-59, isAvailable L75, getEksClient L493; constructor (L64) gains AwsClientFactory
- `src/main/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerService.java` (modify) — isAvailable = enabled && factory.isConfigured(); getClient(region) -> factory.computeOptimizer(region)
- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerBillingService.java` (modify) — getClient() L222 -> factory.costExplorer(); isAvailable L80
- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerTagActivationService.java` (modify) — getClient() L78 -> factory.costExplorer(); isAvailable L45
- `src/main/java/com/tcgdigital/vmcontrol/service/ReservationCoverageService.java` (modify) — getClient() L175 -> factory.costExplorer(); isAvailable L60
- `src/test/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderServiceTest.java` (modify) — Construct with a mocked AwsClientFactory instead of reflecting accessKey
- `src/test/java/com/tcgdigital/vmcontrol/service/EksCloudProviderServiceTest.java` (modify) — Pass mocked AwsClientFactory; stub factory.eks(region)
- `src/test/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerServiceTest.java` (modify) — Construct with mocked factory
- `src/test/java/com/tcgdigital/vmcontrol/service/CostExplorerBillingServiceTest.java` (modify) — Construct with mocked factory

**Steps**

1. Add AwsClientFactory as a constructor parameter to each listed service (convert @Value-field-only classes such as AwsCloudProviderService and ComputeOptimizerService to constructor injection; keep non-credential @Value fields).
2. Delete the @Value aws.access-key / aws.secret-key fields, the StaticCredentialsProvider/AwsBasicCredentials imports and the private clientCache / volatile client fields.
3. Replace each private getXxxClient(region) body with the matching factory accessor; keep the method as a one-line delegate so call sites are untouched in this task.
4. Replace each isAvailable() key check with factory.isConfigured() (services with an enabled flag keep it: enabled && factory.isConfigured()).
5. grep -rn "AwsBasicCredentials\|StaticCredentialsProvider" src/main must now match only AwsClientFactory.
6. Update the four existing tests to build services with a Mockito mock of AwsClientFactory (when(factory.isConfigured()).thenReturn(true); when(factory.ec2(any())).thenReturn(mockEc2)); remove ReflectionTestUtils.setField("accessKey"/"secretKey") lines.

**Acceptance criteria**

- Given the refactor, when grepping src/main for StaticCredentialsProvider, then only AwsClientFactory uses it.
- Given valid static keys, when state sync, metrics, inventory, EKS sync, Compute Optimizer and Cost Explorer jobs run, then they behave as before (same regions, same timeouts).
- Given no AWS credentials, when any of these services is asked isAvailable(), then it returns false.

**Tests**

- AwsCloudProviderServiceTest, EksCloudProviderServiceTest, ComputeOptimizerServiceTest, CostExplorerBillingServiceTest updated and green
- AwsCloudProviderServiceTest: isAvailable() mirrors factory.isConfigured()

**Notes:** Pure refactor, no behaviour change. Rebase onto any changes earlier epics (E08, E09, E12, E26) made to these classes.

#### E28-T03 — Add the AWS account registry table and migrate existing environments and VMs to a default account

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G17 · **Depends on:** E00-T01, E28-T02

G17: there is no notion of an AWS account; every environment/VM implicitly lives in the one account behind the static key. An account dimension with a default row keeps existing data working.

**Files**

- `src/main/resources/db/migration/V<next>__add_aws_account_registry.sql` (new) — aws_account table, default row, aws_account_ref on environment and vm, backfill (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/AwsAccount.java` (new) — JPA entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/AwsAccountRepository.java` (new) — findByIsDefaultTrue, findByIsActiveTrueOrderByDisplayNameAsc, findByAccountNumber, findByIsBillingAccountTrue
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsAccountService.java` (new) — getDefault(), resolveRef(Vm), resolveRef(Environment), listActive(), resolveDefaultAccountNumber() on ApplicationReadyEvent
- `src/main/java/com/tcgdigital/vmcontrol/model/Environment.java` (modify) — String awsAccountRef (column aws_account_ref)
- `src/main/java/com/tcgdigital/vmcontrol/model/Vm.java` (modify) — String awsAccountRef (column aws_account_ref)
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — New environments default to the default account ref
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — Register/update (L124, L181) set vm.awsAccountRef from environment when null
- `src/main/java/com/tcgdigital/vmcontrol/service/EksSyncService.java` (modify) — upsertNodeGroup (L326) sets vm.awsAccountRef from environment
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — Discovered VMs get the environment's account ref
- `src/test/resources/db/reset-test-data.sql` (modify) — Re-insert the default aws_account row if the script truncates tables
- `src/test/java/com/tcgdigital/vmcontrol/service/aws/AwsAccountServiceTest.java` (new) — Resolution order and default-number resolution
- `src/test/java/com/tcgdigital/vmcontrol/repository/AwsAccountMigrationIT.java` (new) — Testcontainers check of default row and backfill

**Steps**

1. Migration: CREATE TABLE aws_account (aws_account_ref VARCHAR(36) PRIMARY KEY, account_number VARCHAR(12) NULL UNIQUE, display_name VARCHAR(255) NOT NULL, role_arn VARCHAR(2048) NULL, external_id VARCHAR(1224) NULL, regions TEXT NULL, is_default BOOLEAN NOT NULL DEFAULT FALSE, is_billing_account BOOLEAN NOT NULL DEFAULT FALSE, is_active BOOLEAN NOT NULL DEFAULT TRUE, source VARCHAR(20) NOT NULL DEFAULT 'MANUAL', last_verified_at TIMESTAMP NULL, last_error TEXT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP).
2. Same migration: INSERT default row aws_account_ref='00000000-0000-0000-0000-000000000001', display_name='Default account', role_arn NULL, is_default=TRUE, is_billing_account=TRUE. ALTER TABLE environment ADD COLUMN aws_account_ref VARCHAR(36) NULL; ALTER TABLE vm ADD COLUMN aws_account_ref VARCHAR(36) NULL; add FKs to aws_account and indexes idx_environment_aws_account and idx_vm_aws_account_region (aws_account_ref, region). UPDATE environment SET aws_account_ref=<default>; UPDATE vm SET aws_account_ref=<default> WHERE provider IN ('AWS','AWS_EKS').
3. AwsAccount entity mirrors the columns (@PrePersist sets UUID and timestamps, @PreUpdate updatedAt - follow Environment.java). regions is comma-separated; add List<String> getRegionList().
4. Add awsAccountRef (String) with getter/setter to Environment and Vm. No @ManyToOne, so existing fetch behaviour is unchanged.
5. AwsAccountService: getDefault() (throws IllegalStateException if missing); String resolveRef(Vm) -> vm.awsAccountRef, else vm.group.environment.awsAccountRef, else default ref; resolveRef(Environment). @EventListener(ApplicationReadyEvent) resolveDefaultAccountNumber(): if factory.isConfigured() and default.accountNumber is null, call factory.sts(null).getCallerIdentity() and store getAccount(); catch and log any exception (never block startup).
6. EnvironmentService create path sets awsAccountRef to the default when not supplied (DTO field arrives in E28-T10). VmService register/update copy the environment's ref when vm.awsAccountRef is null. EksSyncService.upsertNodeGroup and VmDiscoveryService registration set it from the environment.
7. Tests: AwsAccountServiceTest (Mockito) - VM ref wins, then environment ref, then default; resolveDefaultAccountNumber stores the STS account and swallows SdkException. AwsAccountMigrationIT (extends AbstractIntegrationTest) - default row exists and an environment created through EnvironmentService gets the default ref.

**Acceptance criteria**

- Given an existing database, when the migration runs, then exactly one aws_account row with is_default=true exists and every environment and AWS/AWS_EKS VM references it.
- Given a new environment created without an account, when saved, then it references the default account.
- Given AWS credentials are configured, when the app becomes ready, then the default account_number is filled from STS GetCallerIdentity; given STS fails, then startup completes and the error is logged.

**Tests**

- AwsAccountServiceTest
- AwsAccountMigrationIT (Testcontainers MySQL)

**Notes:** Never edit existing migrations.

#### E28-T04 — Resolve per-account credentials with STS AssumeRole and cache clients per account and region

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** medium · **Sources:** G17 · **Depends on:** E28-T03

G17: member accounts are reached by assuming a role from the platform's base identity; clients must be cached per (account, region) and credentials refreshed automatically.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsAccountCredentialsResolver.java` (new) — Builds and caches StsAssumeRoleCredentialsProvider per account
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsClientFactory.java` (modify) — Account-aware overloads ec2(accountRef, region) etc.; evict(accountRef)
- `src/main/java/com/tcgdigital/vmcontrol/exception/AwsAccountUnavailableException.java` (new) — Unknown or inactive account
- `src/main/resources/application.properties` (modify) — aws.multi-account.enabled=false, aws.assume-role.session-duration-seconds=3600, aws.assume-role.session-name-prefix=vmcontrol
- `src/test/java/com/tcgdigital/vmcontrol/service/aws/AwsAccountCredentialsResolverTest.java` (new) — AssumeRole request contents and caching

**Steps**

1. AwsAccountCredentialsResolver (@Component): AwsCredentialsProvider forAccount(String accountRef). If aws.multi-account.enabled=false, or the account is default, or its roleArn is null -> factory base credentials.
2. Otherwise build StsAssumeRoleCredentialsProvider.builder().stsClient(factory.sts(aws.region)).refreshRequest(AssumeRoleRequest.builder().roleArn(roleArn).roleSessionName(prefix + "-" + first 8 chars of ref).externalId(externalId when non-blank).durationSeconds(sessionDuration).build()).build(); cache per ref in a ConcurrentHashMap.
3. Throw AwsAccountUnavailableException(accountRef) when the account is unknown or inactive.
4. AwsClientFactory: add overloads ec2(String accountRef, String region), cloudWatch, eks, computeOptimizer, sts, costExplorer(String accountRef). Cache key becomes service|accountRef|region (null ref -> default ref). Existing single-argument methods delegate with the default ref. Avoid a constructor cycle: the factory looks up the resolver lazily via ObjectProvider<AwsAccountCredentialsResolver>.
5. Add evict(String accountRef): remove and close every cached client for that ref and drop/close its credentials provider. Used by the admin API on update/deactivate.
6. Tests: mocked StsClient and AwsAccountRepository; AssumeRoleRequest carries roleArn, externalId and session name; same provider returned twice; flag off -> base credentials even with roleArn set; evict closes the provider. Factory: ec2(refA, r) != ec2(refB, r); ec2(refA, r) cached.

**Acceptance criteria**

- Given aws.multi-account.enabled=false, when a client is requested for any account, then base credentials are used and no AssumeRole call is made.
- Given the flag is true and an account has roleArn and externalId, when an EC2 client is requested for it, then credentials come from sts:AssumeRole with that external ID and refresh before expiry.
- Given an account was evicted, when a client is next requested, then a new client is built.

**Tests**

- AwsAccountCredentialsResolverTest
- AwsClientFactoryTest extended for per-account caching and evict

**Notes:** aws.multi-account.enabled defaults to false (safe). The base identity needs sts:AssumeRole on member roles (documented in E28-T12).

#### E28-T05 — Thread the account through CloudProviderService start/stop/status, state sync, tagging and resize

- [ ] Done · **Type:** refactor · **Estimate:** 2 d · **Risk:** high · **Sources:** G17 · **Depends on:** E28-T04

G17: operation calls pass only a region (VmOperationsService L626-643/L766, StateSyncService L124/L142/L374/L443, TagReconciliationService L101-136, VmResizeService L65), so a VM in another account would be looked up in the wrong account.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CloudTarget.java` (new) — record CloudTarget(String accountRef, String region)
- `src/main/java/com/tcgdigital/vmcontrol/service/CloudProviderService.java` (modify) — CloudTarget replaces String region in startVm/stopVm/getVmStatus/getVmName/getVmStatusBatch
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — factory.ec2(target.accountRef(), target.region()); tagInstances/changeInstanceType/discover* take CloudTarget
- `src/main/java/com/tcgdigital/vmcontrol/service/EksCloudProviderService.java` (modify) — Same; listNodegroups/describeNodegroup/listClusters/tagNodeGroup take CloudTarget
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — CloudTarget per VM at L626-643 and L766
- `src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java` (modify) — Group key provider:accountRef:region (L124), batch with CloudTarget (L142), L374, L443
- `src/main/java/com/tcgdigital/vmcontrol/service/TagReconciliationService.java` (modify) — Group by accountRef|region|env (L101); CloudTarget at L109, L129, L136
- `src/main/java/com/tcgdigital/vmcontrol/service/VmResizeService.java` (modify) — changeInstanceType(CloudTarget.of(vm), ...) at L65
- `src/main/java/com/tcgdigital/vmcontrol/service/EksSyncService.java` (modify) — Pass CloudTarget(environment ref, region) to EKS calls
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — Pass CloudTarget to discover* calls (L102, L241)
- `src/test/java/com/tcgdigital/vmcontrol/service/StateSyncServiceTest.java` (modify) — CloudTarget stubs; two-accounts-same-region case
- `src/test/java/com/tcgdigital/vmcontrol/service/VmOperationsServiceTest.java` (modify) — any(CloudTarget.class) matchers
- `src/test/java/com/tcgdigital/vmcontrol/service/TagReconciliationServiceTest.java` (modify) — Signatures
- `src/test/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderServiceTest.java` (modify) — Signatures
- `src/test/java/com/tcgdigital/vmcontrol/service/EksCloudProviderServiceTest.java` (modify) — Signatures
- `src/test/java/com/tcgdigital/vmcontrol/service/EksSyncServiceTest.java` (modify) — Signatures

**Steps**

1. Create record CloudTarget(String accountRef, String region) with static of(String accountRef, String region) and of(Vm vm, AwsAccountService accounts) -> new CloudTarget(accounts.resolveRef(vm), vm.getRegion()); add String key() = accountRef + ":" + region.
2. CloudProviderService: change the region parameter of startVm (both overloads), stopVm (both), getVmStatus, getVmName and getVmStatusBatch to CloudTarget target. Check discoverInstanceIds(List<String>) with grep; delete it if it has no caller, else leave it.
3. AwsCloudProviderService: getEc2Client(CloudTarget t) -> factory.ec2(t.accountRef(), t.region()); public non-interface methods tagInstances, changeInstanceType, discoverTaggedInstances, discoverInstancesByNamePrefix take CloudTarget. EksCloudProviderService likewise (getEksClient, listNodegroups, describeNodegroup, listClusters, tagNodeGroup, saveScalingConfigToMetadata).
4. Update callers: VmOperationsService L626/L631/L637/L643/L766; StateSyncService group key "PROVIDER:accountRef:region" (split with limit 3) and L374/L443; TagReconciliationService; VmResizeService; EksSyncService and VmDiscoveryService (use the environment's ref; account iteration comes in E28-T06).
5. Fix test compile errors with any(CloudTarget.class) or eq(CloudTarget.of(ref, region)).
6. New StateSyncServiceTest case: two VMs in the same region but different accountRef produce two getVmStatusBatch calls with distinct CloudTargets.

**Acceptance criteria**

- Given two VMs in ap-south-1 in different accounts, when state sync runs, then each account gets its own batched DescribeInstances call through its own client.
- Given a VM in account B, when a user starts it, then StartInstances uses account B's assumed-role client.
- Given only the default account exists, when any operation runs, then behaviour is identical to before.

**Tests**

- StateSyncServiceTest multi-account grouping
- VmOperationsServiceTest, TagReconciliationServiceTest, AwsCloudProviderServiceTest, EksCloudProviderServiceTest, EksSyncServiceTest updated

**Notes:** Large mechanical signature change in one PR so the build stays green. Line numbers are from review time; E05/E09 may have moved them.

#### E28-T06 — Make metrics, inventory, Compute Optimizer, discovery and EKS sync iterate accounts

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G17 · **Depends on:** E28-T05

G17: metrics and inventory group VMs by provider:region (VmMetricsService L67, VmInventoryService L86), Compute Optimizer is called per region (CostEstimationService L455-462), and discovery/EKS sync scan regions of one account only (VmDiscoveryService L102/L241, EksSyncService L84-160).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CloudMetricsProviderService.java` (modify) — fetchLatestMetrics(ids, CloudTarget, start, end, period)
- `src/main/java/com/tcgdigital/vmcontrol/service/CloudInventoryProviderService.java` (modify) — fetchInventory(ids, CloudTarget)
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudMetricsProviderService.java` (modify) — factory.cloudWatch(accountRef, region)
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudInventoryProviderService.java` (modify) — factory.ec2(accountRef, region)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricsService.java` (modify) — Group by provider:accountRef:region (L67, L85)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmInventoryService.java` (modify) — Group by provider:accountRef:region (L86, L101)
- `src/main/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerService.java` (modify) — getEc2Recommendations(CloudTarget); follow nextToken
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — fetchComputeOptimizerRecommendations over distinct CloudTargets (L455-462)
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsAccountService.java` (modify) — scanTargets(List<String> fallbackRegions)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — Name-pattern strategy loops scanTargets; tag strategy uses environment account
- `src/main/java/com/tcgdigital/vmcontrol/service/EksSyncService.java` (modify) — getUnregisteredEksClusters/autoDiscoverClusters loop scanTargets; registered env sync uses env account
- `src/main/java/com/tcgdigital/vmcontrol/dto/EksClusterInfoDTO.java` (modify) — Add accountRef and accountName
- `src/test/java/com/tcgdigital/vmcontrol/service/EksSyncServiceTest.java` (modify) — Two-account discovery case
- `src/test/java/com/tcgdigital/vmcontrol/service/CostEstimationServiceTest.java` (modify) — Compute Optimizer called once per (account, region)
- `src/test/java/com/tcgdigital/vmcontrol/service/VmMetricsServiceTest.java` (new) — Grouping by account

**Steps**

1. Change the two provider interfaces and their AWS implementations to take CloudTarget; VmMetricsService and VmInventoryService group by provider:accountRef:region using AwsAccountService.resolveRef(vm).
2. ComputeOptimizerService.getEc2Recommendations(CloudTarget) uses factory.computeOptimizer(ref, region) and loops while response.nextToken() != null so large accounts are complete. CostEstimationService.fetchComputeOptimizerRecommendations maps AWS bundles to distinct CloudTarget.of(vm) values.
3. AwsAccountService.scanTargets(List<String> fallbackRegions): for each active account, its region list if set, else fallbackRegions -> List<CloudTarget>.
4. VmDiscoveryService: tag strategy (L241) uses CloudTarget(env.awsAccountRef, region); name-pattern strategy (L102) iterates scanTargets(List.of(defaultRegion)) and sets each discovered VM's awsAccountRef to the target account.
5. EksSyncService: resolveSyncRegions() stays as fallback; getUnregisteredEksClusters and autoDiscoverClusters iterate scanTargets(resolveSyncRegions()); new EKS environments store awsAccountRef (region stays in metadata); syncEksEnvironment uses CloudTarget(env.awsAccountRef, metadata region). EksClusterInfoDTO gains accountRef/accountName. One account's failure is logged and does not stop the others.
6. Tests: EksSyncServiceTest two accounts each returning one cluster -> two DTOs with correct accountRef; CostEstimationServiceTest two VMs same region different accounts -> two Compute Optimizer calls; VmMetricsServiceTest grouping.

**Acceptance criteria**

- Given account B with regions [eu-west-1], when the EKS picker lists unregistered clusters, then clusters from B/eu-west-1 appear labelled with B.
- Given VMs in two accounts, when metrics and inventory sync run, then CloudWatch and DescribeInstances calls go to each VM's own account.
- Given account B's role is broken, when discovery runs, then account A is still scanned and B's error is logged.

**Tests**

- EksSyncServiceTest two-account case
- CostEstimationServiceTest per-account Compute Optimizer
- VmMetricsServiceTest (new)

#### E28-T07 — Add the admin API for AWS accounts with connection verification

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G17 · **Depends on:** E28-T06, E00-T02, E00-T05

G17: admins need to register member accounts (role ARN, external ID, regions), test the connection and deactivate accounts.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/AwsAccountController.java` (new) — /api/v1/aws-accounts CRUD + verify, ADMIN only
- `src/main/java/com/tcgdigital/vmcontrol/dto/AwsAccountDTO.java` (new) — Response with masked externalId
- `src/main/java/com/tcgdigital/vmcontrol/dto/UpsertAwsAccountDTO.java` (new) — Validated request
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsAccountService.java` (modify) — create/update/deactivate/verify
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — AWS_ACCOUNT_CREATED, AWS_ACCOUNT_UPDATED, AWS_ACCOUNT_DEACTIVATED, AWS_ACCOUNT_VERIFIED
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentRepository.java` (modify) — countByAwsAccountRefAndIsActiveTrue
- `src/test/java/com/tcgdigital/vmcontrol/controller/AwsAccountControllerTest.java` (new) — Security, validation, verify

**Steps**

1. UpsertAwsAccountDTO (record): @NotBlank displayName; roleArn @Pattern("^arn:aws(-[a-z]+)?:iam::\\d{12}:role/[\\w+=,.@/-]+$") (null only allowed for the default account); externalId optional @Size(min=2,max=1224); regions List<String> each @Pattern("^[a-z]{2}(-[a-z]+)+-\\d$"); isBillingAccount Boolean.
2. AwsAccountService.create: accountNumber = 12 digits after 'iam::'; reject duplicates (ValidationException); source MANUAL. update: same validation then factory.evict(ref). Setting isBillingAccount=true clears it on other rows in the same transaction. deactivate: refuse for the default account or when EnvironmentRepository.countByAwsAccountRefAndIsActiveTrue(ref) > 0; else isActive=false and evict.
3. verify(ref): factory.sts(ref, region).getCallerIdentity(); success -> lastVerifiedAt=now, lastError=null, and if the returned Account differs from accountNumber set lastError='Role resolved to account <n>'; failure -> lastError = message truncated to 1000 chars. For non-default accounts while aws.multi-account.enabled=false, throw ValidationException('Multi-account is disabled').
4. AwsAccountController @RequestMapping("/api/v1/aws-accounts") @PreAuthorize("hasRole('ADMIN')"): GET list, POST (201), PUT /{ref}, DELETE /{ref} (deactivate, 204), POST /{ref}/verify. Every mutation writes an audit row through AuditService (follow the AutomationRuleService audit calls).
5. AwsAccountDTO masks externalId to its last 4 characters ('****abcd') and includes environmentCount, lastVerifiedAt, lastError, isDefault, isBillingAccount, regions, source.
6. Tests (SecuredWebTestBase): USER/ENV_ADMIN/VIEWER -> 403; bad ARN -> 400; duplicate number -> 400/409 per E00-T05 body; deactivate with active env -> rejected; verify with mocked factory -> lastVerifiedAt set; GET never contains the full externalId.

**Acceptance criteria**

- Given an ADMIN, when they POST a valid role ARN, then the account is created with the number parsed from the ARN and an audit row is written.
- Given a non-admin, when they call any /api/v1/aws-accounts endpoint, then they get 403.
- Given an account with active environments, when an admin deactivates it, then the request is rejected with a clear message.
- Given a reachable role, when verify is called, then lastVerifiedAt is updated and lastError cleared.

**Tests**

- AwsAccountControllerTest (SecuredWebTestBase)
- AwsAccountServiceTest extended for create/update/deactivate/verify

#### E28-T08 — Discover member accounts from AWS Organizations and import them

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G17 · **Depends on:** E28-T07

G17: compared products are Organizations-aware; admins should import accounts from the organization instead of typing ARNs.

**Files**

- `build.gradle` (modify) — implementation 'software.amazon.awssdk:organizations' (BOM-managed)
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsClientFactory.java` (modify) — organizations(accountRef) client (us-east-1)
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsAccountService.java` (modify) — listOrganizationCandidates(), importAccounts(...)
- `src/main/java/com/tcgdigital/vmcontrol/controller/AwsAccountController.java` (modify) — GET /organization-candidates, POST /import
- `src/main/java/com/tcgdigital/vmcontrol/dto/OrganizationAccountCandidateDTO.java` (new) — accountNumber, name, email, proposedRoleArn, alreadyRegistered
- `src/main/resources/application.properties` (modify) — aws.organizations.enabled=false, aws.organizations.member-role-name=VmControlAccess
- `src/test/java/com/tcgdigital/vmcontrol/service/aws/AwsOrganizationsDiscoveryTest.java` (new) — Paginated listAccounts mapping and errors

**Steps**

1. Add the organizations SDK module to build.gradle next to the other awssdk modules.
2. Factory: OrganizationsClient organizations(String accountRef), cached, region us-east-1; called with the billing account's credentials (management or delegated-admin account).
3. AwsAccountService.listOrganizationCandidates(): if aws.organizations.enabled=false throw ValidationException('Organizations discovery is disabled'). Use listAccountsPaginator(), keep status ACTIVE, map to OrganizationAccountCandidateDTO with proposedRoleArn arn:aws:iam::<id>:role/<member-role-name> and alreadyRegistered from findByAccountNumber. Map AwsOrganizationsNotInUseException and AccessDeniedException to ValidationException with an actionable message.
4. importAccounts(List<ImportAccountRequest(accountNumber, displayName, roleArn, externalId, regions)>) creates rows with source ORGANIZATIONS through the same validation as create; already-registered numbers are skipped and reported.
5. Controller (ADMIN): GET /api/v1/aws-accounts/organization-candidates; POST /api/v1/aws-accounts/import -> {created:[...], skipped:[...]}; audit each created account.
6. Tests: mocked paginator with two pages; already-registered flag; AccessDenied -> 400 with message; flag off -> 400.

**Acceptance criteria**

- Given discovery is enabled and the billing account is the management account, when an admin lists candidates, then every ACTIVE member account appears with a proposed role ARN and registered ones are flagged.
- Given selected candidates, when imported, then accounts are created with source ORGANIZATIONS and audited.
- Given the account is not in an organization, when candidates are requested, then a clear message is returned.

**Tests**

- AwsOrganizationsDiscoveryTest
- AwsAccountControllerTest extended for /organization-candidates and /import

**Notes:** aws.organizations.enabled defaults false. Needs organizations:ListAccounts on the billing identity.

#### E28-T09 — Build the AWS Accounts admin page

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G17 · **Depends on:** E28-T08

G17: admins need UI for the account registry, verification and Organizations import.

**Files**

- `src/main/resources/static/js/features/aws-accounts.js` (new) — IIFE singleton AwsAccounts: list, add/edit modal, verify, deactivate, import
- `src/main/resources/static/js/core/router.js` (modify) — Route 'aws-accounts': '#/aws-accounts' plus loader entry
- `src/main/resources/static/js/ui/sidebar.js` (modify) — Admin-only nav entry
- `src/main/resources/static/index.html` (modify) — Content container/script include as other features
- `build-frontend.ps1` (modify) — Add the file to the bundle order if files are listed explicitly

**Steps**

1. Follow automation-rules.js structure (load(), render, ApiClient calls); use Utils.html / Utils.escapeHtml for every interpolated value (E01) and ContentRouter.onLeave/token for stale renders (E02).
2. Table: Name, Account number, Role ARN (truncated with tooltip), Regions, Environments, Billing, Status, Last verified (relative time; red badge with lastError tooltip), actions Verify / Edit / Deactivate (default account: Verify and Edit only).
3. Add/Edit modal: display name, role ARN, external ID (password-style input; blank on edit keeps current), regions (comma-separated, same regex client-side), billing checkbox; show server 400 messages inline.
4. 'Import from AWS Organizations' button (hidden when the candidates call returns the disabled message): modal with checkboxes, editable role ARN per row, shared external ID and regions; POST /import and show created/skipped summary.
5. Register the route and the ADMIN-only sidebar item (mirror how cost-management is gated). Run .\build-frontend.ps1.

**Acceptance criteria**

- Given an ADMIN, when they open #/aws-accounts, then the registry is listed with verification status.
- Given a non-admin, when they navigate to #/aws-accounts, then the nav item is absent and the standard not-authorised state shows.
- Given an account named <script>x</script>, when listed, then it renders as text.

**Tests**

- Manual checklist in the PR (desktop and 375px width); API contract covered by AwsAccountControllerTest

**Notes:** No JS test framework in the repo; manual verification.

#### E28-T10 — Add the account dimension to environment and VM APIs, forms and lists

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G17 · **Depends on:** E28-T09

G17: users must choose and see the account an environment/VM belongs to, and instance browsing must target the chosen account.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/CreateEnvironmentDTO.java` (modify) — awsAccountRef (optional)
- `src/main/java/com/tcgdigital/vmcontrol/dto/UpdateEnvironmentDTO.java` (modify) — awsAccountRef (optional)
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — awsAccountRef, awsAccountName
- `src/main/java/com/tcgdigital/vmcontrol/dto/RegisterVmDTO.java` (modify) — awsAccountRef (optional, defaults to environment's)
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmDTO.java` (modify) — awsAccountRef, awsAccountName
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — Validate ref is an active account
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — Validate and persist awsAccountRef
- `src/main/java/com/tcgdigital/vmcontrol/controller/Ec2Controller.java` (modify) — GET /api/ec2/instances accepts optional accountRef
- `src/main/java/com/tcgdigital/vmcontrol/service/Ec2Service.java` (modify) — listInstances(accountRef, region)
- `src/main/java/com/tcgdigital/vmcontrol/controller/AwsAccountController.java` (modify) — GET /api/v1/aws-accounts/options (ADMIN, ENV_ADMIN)
- `src/main/resources/static/js/features/environments.js` (modify) — Account select in create/edit form; Account column next to Region (L342/L425)
- `src/main/resources/static/js/features/vm-registry.js` (modify) — Account select before region in Fetch Instances (L731-800) and register form; account column (L467)
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentServiceTest.java` (modify) — Account validation
- `src/test/java/com/tcgdigital/vmcontrol/controller/Ec2ControllerTest.java` (modify) — accountRef passthrough

**Steps**

1. Add the DTO fields; map names in EnvironmentDTO/VmDTO using AwsAccountService.namesByRef() (one query) to avoid N+1.
2. EnvironmentService create/update: a supplied awsAccountRef must be an active AwsAccount (ValidationException otherwise). VmService register: ref defaults to the environment's; an explicit different active ref is allowed (shared-services VMs).
3. Ec2Controller listInstances: @RequestParam(required=false) String accountRef -> Ec2Service.listInstances(accountRef, region) using factory.ec2(accountRef, region).
4. GET /api/v1/aws-accounts/options with @PreAuthorize("hasAnyRole('ADMIN','ENV_ADMIN')") returns [{ref, name, regions}] of active accounts.
5. Frontend: when only one active account exists hide the account select and column (single-account UI unchanged). Otherwise show the select in the environment form and vm-registry Fetch Instances; an account with a region list restricts the region dropdown.
6. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given two active accounts, when an ENV_ADMIN browses instances, then they pick account and region and see that account's instances.
- Given an inactive or unknown accountRef, when creating an environment, then the API returns 400.
- Given only the default account, when users view environments and the VM registry, then no account UI is shown.

**Tests**

- EnvironmentServiceTest account validation
- Ec2ControllerTest accountRef passthrough
- VmDTO mapping includes awsAccountName

#### E28-T11 — Add the account dimension to cost: billing-account Cost Explorer client and spend by account

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G17 · **Depends on:** E28-T10

G17: cost must be attributable per account; Cost Explorer for linked accounts must be read from the payer/management (billing) account.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerBillingService.java` (modify) — Billing-account client; group by tag + LINKED_ACCOUNT; warn on env/account mismatch
- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerTagActivationService.java` (modify) — Billing-account client
- `src/main/java/com/tcgdigital/vmcontrol/service/ReservationCoverageService.java` (modify) — Billing-account client
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — getSpendByAccount()
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /api/v1/cost-management/spend-by-account (ADMIN)
- `src/main/resources/static/js/features/cost-management.js` (modify) — 'By account' card shown only with more than one account
- `src/test/java/com/tcgdigital/vmcontrol/service/CostExplorerBillingServiceTest.java` (modify) — Two-key group parsing
- `src/test/java/com/tcgdigital/vmcontrol/service/CostEstimationServiceTest.java` (modify) — Spend by account

**Steps**

1. AwsAccountService.getBillingAccountRef(): the is_billing_account row, else default.
2. The three Cost Explorer services call factory.costExplorer(billingRef).
3. CostExplorerBillingService.fetchDailyCostByEnvironment (L207): add GroupDefinition(type DIMENSION, key LINKED_ACCOUNT) as second group-by. parseCostByEnvironmentTag sums across accounts per environment (totals unchanged) and collects env -> account numbers; WARN when an environment's cost arrives from an account other than its registered account. Idempotency guard unchanged.
4. CostEstimationService.getSpendByAccount(): groupSpend(buildBundles(), b -> accounts.resolveRef(b.vm()), b -> account display name), same helper as getSpendByEnvironment (L146).
5. Controller endpoint following /spend-by-team (L108).
6. Frontend card next to 'By team' using the existing chart helper; hidden when /aws-accounts/options returns one account. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given the billing account is the management account, when daily actuals are ingested, then each environment total equals the sum across linked accounts.
- Given VMs in two accounts, when an admin opens Cost Management, then 'By account' shows two rows summing to the overall estimate.
- Given a resource tagged with environment X running in another account than X's, when ingestion runs, then a warning naming both accounts is logged.

**Tests**

- CostExplorerBillingServiceTest: TAG+LINKED_ACCOUNT keys parsed and summed
- CostEstimationServiceTest: getSpendByAccount

**Notes:** Cost Explorer allows two GroupBy definitions, so this stays one billed call per day.

#### E28-T12 — Document multi-account setup: IAM role, trust policy, external ID and rollout

- [ ] Done · **Type:** docs · **Estimate:** 0.5 d · **Risk:** low · **Sources:** G17 · **Depends on:** E28-T11

G17: operators need a repeatable recipe for member-account roles and for running on an IAM role instead of keys.

**Files**

- `docs/aws-multi-account.md` (new) — Setup and rollout guide
- `docs/aws-iam-permissions.md` (modify) — sts:AssumeRole, sts:GetCallerIdentity, organizations:ListAccounts; member-role permission set
- `.env.example` (modify) — AWS_MULTI_ACCOUNT_ENABLED, AWS_ORGANIZATIONS_ENABLED, AWS_ORGANIZATIONS_MEMBER_ROLE_NAME placeholders
- `src/main/resources/application.properties` (modify) — Bind the new flags to env vars with safe defaults

**Steps**

1. docs/aws-multi-account.md: architecture (base identity -> AssumeRole into a member role per account; billing account for Cost Explorer/Organizations), member trust policy JSON with sts:ExternalId condition, member permission set (EC2/EKS/CloudWatch/Compute Optimizer actions from aws-iam-permissions.md), CloudFormation StackSet snippet to deploy the role org-wide, running on an instance role with AWS_USE_DEFAULT_CREDENTIAL_CHAIN=true.
2. Rollout checklist: deploy with flags off -> confirm default account number resolved -> create member roles -> enable aws.multi-account.enabled -> add/import accounts -> Verify each -> enable discovery.
3. application.properties: aws.multi-account.enabled=${AWS_MULTI_ACCOUNT_ENABLED:false}, aws.organizations.enabled=${AWS_ORGANIZATIONS_ENABLED:false}, aws.organizations.member-role-name=${AWS_ORGANIZATIONS_MEMBER_ROLE_NAME:VmControlAccess}; .env.example comments only.

**Acceptance criteria**

- Given a new operator, when following docs/aws-multi-account.md, then they can add a member account and get a successful Verify without reading code.

**Tests**

- None (docs and property binding only); app context test still green

## E29

### Help, settings & notification preferences — Next

Admins change safe runtime settings from a Settings page without a restart, every user chooses which notifications reach them in-app or by email, and the in-app Help explains each feature where it is used.

Sources: G26 · 8 tasks · 10.5 days

#### E29-T01 — Add the app_setting store, a typed SettingsService with cache, and the admin settings API

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G26 · **Depends on:** E14-T08

docs/settings-page-requirement.md proposes an app_setting key-value table and a SettingsService seeded from application.properties so Tier 1 flags can change live; #/settings is a placeholder in router.js (showPlaceholder('settings')) (G26).

**Files**

- `src/main/resources/db/migration/V<next>__add_app_setting.sql` (new) — app_setting(setting_key VARCHAR(150) PK, setting_value TEXT NOT NULL, updated_at, updated_by_user_id NULL)
- `src/main/java/com/tcgdigital/vmcontrol/model/AppSetting.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/AppSettingRepository.java` (new) — JpaRepository<AppSetting, String>
- `src/main/java/com/tcgdigital/vmcontrol/service/settings/SettingDefinition.java` (new) — record(key, type BOOLEAN|INT|DECIMAL|STRING, tier 1|2, group, label, description, min, max)
- `src/main/java/com/tcgdigital/vmcontrol/service/settings/SettingsRegistry.java` (new) — static list of definitions: Tier 1 editable keys and Tier 2 read-only keys from the requirement doc; nothing matching a secret pattern
- `src/main/java/com/tcgdigital/vmcontrol/service/settings/SettingsService.java` (new) — getBoolean/getInt/getDecimal/getString(key): DB override else Spring Environment property; cache with 30s TTL and invalidation on write; set(key, value, actor) validates and audits
- `src/main/java/com/tcgdigital/vmcontrol/controller/SettingsController.java` (new) — GET /api/v1/settings (ADMIN): definitions + effective value + source (DEFAULT|OVERRIDE); PUT /api/v1/settings/{key}; DELETE /api/v1/settings/{key} (reset to default)

**Steps**

1. Migration, entity, repository.
2. SettingsRegistry: Tier 1 = notification.email.* toggles, notification.weekly-reports.enabled, cost.tagging/actuals/optimizer/reservations/snapshot.enabled, rightsizing.* thresholds, vm.metrics.idle.* thresholds, automation.rules.enabled, vm.state.sync.enabled, vm.inventory.sync.enabled, vm.metrics.archive.enabled, eks.sync.auto-register.enabled, eks.sync.status-refresh.enabled, cloudwatch.metric.enable, plus the flags added in E11-E25 (retention.*, chat.enabled, approvals.enabled, quotas.enabled, allocation.*). Tier 2 (read-only) = entraid.enabled, every *.cron, spring.mail.host/port, aws.region. Secrets are never defined (denylist: password, secret, token, key-prefix excluded only by not being registered; add a unit test asserting no registered key matches /(password|secret|token|api-key|signing-key)/).
3. SettingsService: resolve DB value -> Environment.getProperty(key) -> definition default; typed parsing; cache Map<key, CachedValue> with 30s expiry so other instances pick up changes within 30s; write path saves, evicts, and logs SYSTEM_CONFIG_UPDATED (existing AuditAction) with old and new value.
4. Validation on PUT: key must be Tier 1; value must parse to the type and respect min/max; else 400.
5. Controller ADMIN-only (@PreAuthorize("hasRole('ADMIN')")).

**Acceptance criteria**

- Given no override, when GET /api/v1/settings, then each Tier 1 key shows its application.properties value with source DEFAULT.
- Given an admin PUTs notification.email.lock-broken.enabled=true, then the next getBoolean returns true within 30 s on every instance and an audit row records old/new value.
- Given PUT on a Tier 2 key (e.g. entraid.enabled), then 400 'read-only setting'.
- Given an ENV_ADMIN, then every settings endpoint returns 403.
- Given DELETE on an overridden key, then the default is effective again.

**Tests**

- SettingsServiceTest: precedence, typing, TTL cache, validation, secret-pattern guard on the registry.
- SettingsControllerIntegrationTest (SecuredWebTestBase): role matrix, 400 cases, audit row.

#### E29-T02 — Read notification and weekly-report toggles through SettingsService at call time

- [ ] Done · **Type:** refactor · **Estimate:** 1 d · **Risk:** low · **Sources:** G26 · **Depends on:** E29-T01

NotificationService (@Value fields lines ~38-55) and WeeklyReportService read email toggles once at startup, so the Settings page could not change them live (G26).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — replace the nine emailXxxEnabled @Value fields with settingsService.getBoolean(...) at each check
- `src/main/java/com/tcgdigital/vmcontrol/service/EmailService.java` (modify) — master switch notification.email.enabled via SettingsService
- `src/main/java/com/tcgdigital/vmcontrol/service/WeeklyReportService.java` (modify) — weekly report email toggles via SettingsService
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/WeeklyReportScheduler.java` (modify) — runtime check of notification.weekly-reports.enabled via SettingsService in addition to the bean condition
- `src/test/java/com/tcgdigital/vmcontrol/service/NotificationServiceTest.java` (modify) — stub SettingsService instead of ReflectionTestUtils on fields

**Steps**

1. Inject SettingsService; replace each boolean field read with a call at the decision point using the same key and the same default (false).
2. Where a scheduler bean is created by @ConditionalOnProperty, keep the property as the deploy-time master switch and document in SettingsRegistry that the live toggle only works when the bean exists (description text); also add a runtime check at the top of the scheduled method.
3. Update tests to stub SettingsService.

**Acceptance criteria**

- Given notification.email.operation-failed.enabled is toggled on in Settings, when an operation fails, then the failure email is sent without restart.
- Given it is toggled off again, then the next failure sends no email.
- Given no overrides, then behaviour equals today's.

**Tests**

- NotificationServiceTest and WeeklyReportServiceTest: each toggle read via SettingsService at call time (toggle between two calls).

#### E29-T03 — Read cost, sync, discovery, automation and threshold flags through SettingsService

- [ ] Done · **Type:** refactor · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G26 · **Depends on:** E29-T02

The remaining Tier 1 flags and thresholds (cost.*.enabled, rightsizing.*, vm.metrics.idle.*, automation.rules.enabled, vm.state.sync.enabled, vm.inventory.sync.enabled, vm.metrics.archive.enabled, eks.sync.*, cloudwatch.metric.enable) are @Value fields read once (G26).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/TagReconciliationService.java` (modify) — cost.tagging.enabled via SettingsService
- `src/main/java/com/tcgdigital/vmcontrol/service/CostExplorerBillingService.java` (modify) — cost.actuals.enabled
- `src/main/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerService.java` (modify) — cost.optimizer.enabled
- `src/main/java/com/tcgdigital/vmcontrol/service/ReservationCoverageService.java` (modify) — cost.reservations.enabled
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — rightsizing.* thresholds
- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricsService.java` (modify) — vm.metrics.idle.* thresholds
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/*.java` (modify) — runtime enabled checks via SettingsService (StateSync, VmInventory, VmMetricsArchive, AutomationRule, EksSync)

**Steps**

1. Before editing, grep each key to find its current owner (the @Value list was taken from the code at planning time; E08, E16 and E22 may have moved some). Replace each with SettingsService at call time, keeping key names and defaults.
2. Schedulers keep @ConditionalOnProperty as deploy-time master switch; the 'enabled' field checks become settingsService.getBoolean(key, default).
3. Thresholds: read once per job run (not per VM) and pass down.
4. Remove @Value fields that become unused.

**Acceptance criteria**

- Given vm.metrics.idle.cpu-threshold-percent is changed in Settings, when the next idle evaluation runs, then it uses the new threshold.
- Given vm.state.sync.enabled is turned off in Settings, then the next scheduled sync is skipped and logged as disabled.
- Given no overrides, then all existing tests pass unchanged.

**Tests**

- Per-service unit tests stub SettingsService; one integration test toggles vm.state.sync.enabled and asserts StateSyncScheduler skips.

#### E29-T04 — Build the admin Settings page

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G26 · **Depends on:** E29-T03

Replace the #/settings placeholder with the grouped settings UI described in docs/settings-page-requirement.md, showing Tier 2 values read-only (resolving the doc's open decision in favour of showing the full picture) (G26).

**Files**

- `src/main/resources/static/js/features/settings.js` (new) — groups (Notifications, Cost Management, EKS/Discovery, Automation, Idle/Rightsizing thresholds, Retention, Integrations, Approvals & quotas); toggles/number inputs with min/max; Save per row; Reset to default; Tier 2 read-only with 'edit .env and restart' note
- `src/main/resources/static/css/features/settings.css` (new) — styles
- `src/main/resources/static/js/core/router.js` (modify) — 'settings' loader -> Settings.load() for ADMIN; non-admins see the access-denied state
- `src/main/resources/static/index.html` (modify) — script/css tags; sidebar Settings link visible to ADMIN
- `src/main/resources/static/js/config.js` (modify) — Config.API.settings.*

**Steps**

1. Load GET /api/v1/settings; render groups with label, description, effective value, source badge (Default/Changed) and last changed by/at.
2. Edits: boolean toggles save immediately with an undo toast; numbers validate min/max client-side and on Save; Reset calls DELETE.
3. Risky toggles that change AWS state (cost.tagging.enabled, automation.rules.enabled, idle auto-stop, approvals/quotas) require a confirm dialog naming the effect.
4. Follow the router lifecycle, Utils.html escaping and the E13 page-header pattern; phone layout stacks rows.

**Acceptance criteria**

- Given an ADMIN opens #/settings, then all Tier 1 settings are editable and Tier 2 are visible read-only with the restart note.
- Given they toggle 'Email: lock broken' on, then the row shows Changed with their name and time.
- Given an invalid number, then Save is blocked with the allowed range.
- Given a non-admin opens #/settings, then the access-denied state shows and no API call is made.

**Tests**

- Manual QA checklist; Playwright spec if test/ runs in CI.

#### E29-T05 — Per-user notification preferences by event type and channel

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G26 · **Depends on:** E29-T02, E14-T05, E11-T08

Users cannot choose which events reach them in-app or by email (G26); V1 already created user_notification_preference(user_id, notification_type, in_app_enabled, email_enabled) with no entity (kept by E14-T05 for this purpose).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/model/UserNotificationPreference.java` (new) — entity mapped to user_notification_preference (preference_id, userId, notificationType, inAppEnabled, emailEnabled, updatedAt)
- `src/main/java/com/tcgdigital/vmcontrol/repository/UserNotificationPreferenceRepository.java` (new) — findByUserId, findByUserIdInAndNotificationType
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationPreferenceService.java` (new) — effective prefs per user (default: both on); MANDATORY set cannot disable in-app; bulk filter helpers for broadcasts
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — create/saveAll skip users with in-app off; sendEventEmail/emailBroadcastSplitByActor drop users with email off (one query per broadcast)
- `src/main/java/com/tcgdigital/vmcontrol/service/WeeklyReportService.java` (modify) — respect email preference for WEEKLY_* types
- `src/main/java/com/tcgdigital/vmcontrol/controller/UserController.java` (modify) — GET /api/v1/users/me/notification-preferences, PUT same (list of {type, inApp, email})
- `src/main/java/com/tcgdigital/vmcontrol/dto/NotificationPreferenceDTO.java` (new) — type, label, category, inApp, email, inAppLocked, emailAvailable (platform toggle on)

**Steps**

1. Entity/repository on the existing table (validate passes with E14-T07's ddl validate; check column types match V1).
2. MANDATORY in-app types (cannot be turned off): ACCESS_REVOKED, ACCESS_EXPIRED, LOCK_BROKEN, ENVIRONMENT_STOP_NOTICE, OPERATION_FAILED for the actor, APPROVAL_REQUESTED for approvers, plus lease-ending warnings from E15. Email can be turned off for all types.
3. NotificationService: before saving a broadcast batch, load preferences for all recipient ids and the type in one query and filter; same for email recipients. The platform-level email toggle (SettingsService) still applies first.
4. API returns every NotificationType with label/category and the effective flags; emailAvailable=false when the platform toggle for that type is off (UI greys it out).
5. PUT ignores attempts to disable mandatory in-app types (returns them locked) and audits nothing (user preference, not admin config).

**Acceptance criteria**

- Given a user turns off in-app OPERATION_COMPLETED, when another member's operation completes, then that user gets no bell item and others still do.
- Given a user turns off email ACCESS_EXPIRING, then the expiry warning is in-app only for them.
- Given a user tries to disable in-app LOCK_BROKEN, then the response shows it still enabled and locked.
- Given a broadcast to 200 users, then preferences are loaded with one query.

**Tests**

- NotificationPreferenceServiceTest: defaults, mandatory lock, bulk filtering.
- NotificationServiceTest: in-app and email filtering per user.
- UserController integration test for GET/PUT preferences.

#### E29-T06 — Notifications tab in My Account for preferences

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G26 · **Depends on:** E29-T05

Users need a place to set their notification preferences (G26); My Account has tabs overview, access, activity (my-account.js TABS line 17).

**Files**

- `src/main/resources/static/js/features/my-account.js` (modify) — add ['notifications', 'Notifications'] tab: matrix of event types (grouped by category) x In-app / Email switches, locked rows with tooltip, Save
- `src/main/resources/static/css/features/my-account.css` (modify) — matrix styles; stacked layout under 768px
- `src/main/resources/static/js/config.js` (modify) — Config.API.users.notificationPreferences
- `src/main/resources/static/js/ui/notification-bell.js` (modify) — footer link 'Notification settings' to the new tab

**Steps**

1. Add the tab following the existing tab pattern (keyboard tab handling already in my-account.js).
2. Render switches with accessible labels ('Email for Access expiring'); locked in-app switches disabled with 'Required' text; email column disabled with 'Email is off for this event platform-wide' when emailAvailable=false.
3. Save sends only changed rows; show success/failure inline; unsaved-changes guard on leaving the tab.
4. Bell dropdown footer link opens My Account on this tab.

**Acceptance criteria**

- Given a user opens My Account > Notifications, then every event type is listed with its two switches and locked rows are marked Required.
- Given they turn off Email for 'Operation completed' and Save, then reloading shows it off.
- Given a 390px screen, then the matrix is usable without horizontal scroll.

**Tests**

- Manual QA checklist including keyboard-only use; Playwright spec if test/ runs in CI.

#### E29-T07 — Build the in-app Help page with search and contextual help links

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G26 · **Depends on:** E29-T06, E02-T03

#/help is a placeholder (router.js showPlaceholder('help')); users need in-app help pages reachable from the screen they are on (G26).

**Files**

- `src/main/resources/static/js/features/help.js` (new) — Help page: topic list, search over titles/body, topic view at #/help/<topic> (E02-T03 route params)
- `src/main/resources/static/js/features/help-content.js` (new) — HelpContent: array of {id, title, roles, keywords, sections:[{heading, paragraphs, steps}]} as plain text rendered with Utils.html
- `src/main/resources/static/css/features/help.css` (new) — styles
- `src/main/resources/static/js/core/router.js` (modify) — 'help' loader -> Help.load(params)
- `src/main/resources/static/index.html` (modify) — script/css tags
- `src/main/resources/static/js/ui/user-menu.js` (modify) — Help item in the user menu
- `src/main/resources/static/js/features/*.js` (modify) — a '?' help icon in each page header linking to its topic (environments, activity logs, all logs, cost, access requests, my account, vm registry, automation rules)

**Steps**

1. help-content.js: initial topics written for the current product: Getting started & roles; Requesting and managing access; Environments, groups and start order; Starting, stopping and locks; Activity and audit logs; Costs and rightsizing; Notifications and preferences; Automation rules; FAQ/troubleshooting (403s, stuck operations, missing VMs). Text only, no HTML in content.
2. help.js renders a two-pane layout (list + topic) collapsing to one column on phones; search filters by title/keywords/body (case-insensitive); topics with roles are hidden from users without them.
3. Add the header '?' icon (labelled button, aria-label 'Help: <page>') to each listed page; it navigates to #/help/<topic>.
4. Help is static client content; no backend changes.

**Acceptance criteria**

- Given any user opens #/help, then they see the topic list and can open a topic; the URL #/help/locks deep-links to it.
- Given they search 'lock', then only matching topics show.
- Given a USER, then admin-only topics (Automation rules administration) are not listed.
- Given the Environments page, when the '?' icon is clicked, then the Starting, stopping and locks topic opens.

**Tests**

- Manual QA checklist; a small unit check in the Playwright suite (if present) that every page help link resolves to an existing topic id.

#### E29-T08 — Write help topics for leases, idle auto-stop, savings, budgets, chat actions, approvals and quotas

- [ ] Done · **Type:** docs · **Estimate:** 1 d · **Risk:** low · **Sources:** G26 · **Depends on:** E29-T07, E25-T08, E24-T07

Features added in E15-E25 change user-visible behaviour (automatic stops, approvals, quotas) and need in-app explanations next to them (G26).

**Files**

- `src/main/resources/static/js/features/help-content.js` (modify) — topics: Leases and extensions; Idle auto-stop and snooze; Savings and efficiency score; Budgets and alerts; Teams and chargeback; Slack/Teams actions and Jira tickets; Approvals and quotas
- `src/main/resources/static/js/features/*.js` (modify) — '?' links on the lease banner, idle warning, budgets, chargeback, approvals, settings pages

**Steps**

1. For each feature, describe what triggers it, what the user sees, how to act (extend, snooze, request approval), and who to contact; state defaults read from the server where relevant (e.g. lease default from E15's settings) rather than hard-coding numbers.
2. Hide topics whose feature flag is off (read flags from GET /api/v1/settings for admins, or a small public GET /api/v1/features endpoint if E29-T01 exposes one; otherwise always show with 'may be disabled in your organisation').
3. Add the '?' links.

**Acceptance criteria**

- Given leases are enabled, when a user clicks '?' on the lease banner, then the Leases topic opens and explains extend and the maximum.
- Given every '?' link in the app, then each resolves to an existing topic.

**Tests**

- Manual review by the product owner; link-resolution check from E29-T07.

## E30

### Rightsizing 2.0 — Later

Rightsizing recommendations use memory as well as CPU, cover newer-generation and Graviton types with complete pricing, agree with Compute Optimizer, and can be applied as a safe stop-resize-start with automatic rollback.

Sources: G18 · 7 tasks · 10 days

#### E30-T01 — Roll memory utilisation into the daily metrics and expose it in utilisation stats

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G18 · **Depends on:** E00-T01

G18: memoryUtilization is collected per sample (VmMetricSample L38, V16) but never rolled up: VmMetricDaily has only CPU/network/disk and VmMetricDailyRepository.CpuStats (L44) has no memory, so rightsizing cannot use it.

**Files**

- `src/main/resources/db/migration/V<next>__add_vm_metric_daily_memory.sql` (new) — avg_memory_utilization, max_memory_utilization DECIMAL(7,3) NULL, memory_sample_count INT NOT NULL DEFAULT 0 on vm_metric_daily
- `src/main/java/com/tcgdigital/vmcontrol/model/VmMetricDaily.java` (modify) — Three new fields
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmMetricSampleRepository.java` (modify) — aggregateByVmForPeriod (L30) adds AVG/MAX(memoryUtilization), COUNT(memoryUtilization); DailyAggregate gets getAvgMemory/getMaxMemory/getMemorySampleCount
- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricRollupService.java` (modify) — rollupForDate (L83) sets memory fields; gap-fill rows set them null/0
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmMetricDailyRepository.java` (modify) — findCpuStatsSince (L31) also returns avgMemory, maxMemory; rename projection CpuStats -> UtilizationStats
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — Use renamed projection; carry avgMemory/peakMemory on VmCostBundle
- `src/test/java/com/tcgdigital/vmcontrol/service/VmMetricRollupServiceTest.java` (new) — Memory averages and null when no agent

**Steps**

1. Migration (next free V number): ALTER TABLE vm_metric_daily ADD COLUMN avg_memory_utilization DECIMAL(7,3) NULL, ADD COLUMN max_memory_utilization DECIMAL(7,3) NULL, ADD COLUMN memory_sample_count INT NOT NULL DEFAULT 0.
2. VmMetricDaily: add avgMemoryUtilization, maxMemoryUtilization, memorySampleCount with getters/setters.
3. VmMetricSampleRepository.aggregateByVmForPeriod: add `AVG(s.memoryUtilization) AS avgMemory, MAX(s.memoryUtilization) AS maxMemory, COUNT(s.memoryUtilization) AS memorySampleCount` (COUNT of a column ignores nulls, so VMs without the CloudWatch agent get 0).
4. VmMetricRollupService.rollupForDate: set the three fields from the aggregate; the gap-fill branch sets them to null/null/0.
5. VmMetricDailyRepository: rename CpuStats to UtilizationStats, add getAvgMemory()/getMaxMemory() (AVG(m.avgMemoryUtilization), MAX(m.maxMemoryUtilization)); update the single caller in CostEstimationService.buildBundles and add avgMemory/peakMemory to the VmCostBundle record.
6. Test: VmMetricRollupServiceTest (Mockito repositories) - samples with memory produce avg/max/count; samples without memory produce null and count 0.

**Acceptance criteria**

- Given a VM with the CloudWatch agent reporting memory, when the daily rollup runs, then vm_metric_daily has avg/max memory and memory_sample_count > 0.
- Given a VM without the agent, when the rollup runs, then memory columns are null and memory_sample_count is 0.

**Tests**

- VmMetricRollupServiceTest (new)
- CostEstimationServiceTest compiles against the renamed projection

**Notes:** The rollup re-aggregates its lookback window each run, so history inside vm.metrics.archive.raw-to-archive-days backfills automatically.

#### E30-T02 — Extend the pricing reference with instance specs, Graviton and newer generations, more regions and a regeneration script

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G18 · **Depends on:** E08-T07

G18: instance-pricing.json covers 26 types in 4 regions (ap-south-1, us-east-1, us-west-2, eu-west-1) with no vCPU/memory/architecture data, so newer-generation and Graviton suggestions cannot be priced or checked for memory fit.

**Files**

- `src/main/resources/pricing/instance-pricing.json` (modify) — Add instanceTypes spec section, generationUpgradeMap, more types and regions
- `scripts/generate-instance-pricing.ps1` (new) — Developer-run script using the AWS Price List API (aws pricing get-products) and describe-instance-types to regenerate the JSON
- `src/main/java/com/tcgdigital/vmcontrol/service/PricingReferenceService.java` (modify) — Load specs and generation map; getSpec(type), suggestNewerGeneration(type, sameArchitecture)
- `src/test/java/com/tcgdigital/vmcontrol/service/PricingReferenceServiceTest.java` (modify) — Spec and generation-map lookups (file created by E08-T07)

**Steps**

1. Write scripts/generate-instance-pricing.ps1: params -Regions (default ap-south-1,us-east-1,us-west-2,eu-west-1,eu-central-1,ap-southeast-1) -Families (default t3,t3a,t4g,m5,m6i,m6a,m6g,m7i,m7g,c5,c6i,c6g,c7i,c7g,r5,r6i,r6g,r7i,r7g). For each, call `aws pricing get-products --service-code AmazonEC2 --region us-east-1` with filters operatingSystem=Linux, tenancy=Shared, preInstalledSw=NA, capacitystatus=Used, regionCode=<r>, instanceType=<t>, and `aws ec2 describe-instance-types` for vCPU, memory and supported architectures. Emit the JSON in the existing shape, preserving storageGbMonthRate and downsizeMap.
2. Add to the JSON a top-level "instanceTypes" array: {instanceType, vcpu, memoryMib, architecture (x86_64|arm64), generation (int), family}. Add "generationUpgradeMap": {"AWS:m5.large": {"x86_64": "m7i.large", "arm64": "m7g.large"}, ...} for every size of t3/t3a->t4g, m5/m6i->m7i|m7g, c5/c6i->c7i|c7g, r5/r6i->r7i|r7g. Extend downsizeMap to the new families.
3. Run the script once (a developer with read-only pricing access) and commit the regenerated JSON; keep the _comment explaining it is a static reference and how to regenerate.
4. PricingReferenceService.loadPricing: parse instanceTypes into Map<String, InstanceSpec> (record InstanceSpec(String instanceType, int vcpu, int memoryMib, String architecture, int generation)) and generationUpgradeMap. Add Optional<InstanceSpec> getSpec(String type) and Optional<String> suggestNewerGeneration(String provider, String type, String architecture) (returns the same-architecture target) plus Optional<String> suggestGraviton(String provider, String type).
5. Tests: spec for m7g.large is arm64 with expected memory; suggestNewerGeneration("AWS","m5.large","x86_64") = m7i.large; suggestGraviton("AWS","m5.large") = m7g.large; unknown type -> empty; existing lookups unchanged.

**Acceptance criteria**

- Given the regenerated reference, when the app starts, then it logs well over 26 instance rates across at least 6 regions and loads specs for every priced type.
- Given m5.large in eu-central-1, when looked up, then an exact-region price is returned (not the any-region fallback).

**Tests**

- PricingReferenceServiceTest extended

**Notes:** Assumption: a static, regenerable reference is kept (no live Price List calls at runtime) to stay consistent with the existing estimated cost provider.

#### E30-T03 — Make rightsizing candidacy memory-aware and check that the suggested type fits observed memory

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G18 · **Depends on:** E30-T01, E30-T02, E08-T05

G18: candidacy is CPU-only (RightsizingCandidateDTO javadoc; computeScaleDownCandidates L310-330), so a memory-bound VM can be told to halve its memory.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — computeScaleDownCandidates/computeScaleUpCandidates consider memory; memory-fit check on suggested type; reasons
- `src/main/java/com/tcgdigital/vmcontrol/dto/RightsizingCandidateDTO.java` (modify) — avgMemoryUtilization, peakMemoryUtilization, memoryDataAvailable, reasons (List<String>)
- `src/main/resources/application.properties` (modify) — rightsizing.memory.enabled=true, rightsizing.scale-down.memory-peak-threshold=60, rightsizing.scale-up.memory-threshold=85, rightsizing.memory.headroom-percent=20
- `src/main/resources/static/js/features/cost-management.js` (modify) — Memory avg/peak columns and reason chips in the rightsizing table (buildRightsizingRows)
- `src/test/java/com/tcgdigital/vmcontrol/service/CostEstimationServiceTest.java` (modify) — Memory rule cases

**Steps**

1. Scale-down: keep the CPU rule; when memory data exists for the window (memorySampleCount > 0 on the daily rows), additionally require every one of the last scaleDownConsecutiveDays rows to have maxMemoryUtilization < memory-peak-threshold. Reason codes: CPU_LOW, MEMORY_LOW (or MEMORY_UNKNOWN when the agent is absent).
2. Scale-up: a VM is also a candidate when every raw sample in the scale-up window has memoryUtilization > scale-up.memory-threshold (same >=2-sample rule as CPU, L333-350). Reason MEMORY_HIGH.
3. Memory fit: for scale-down suggestions (local rule or Compute Optimizer), look up current memoryMib (VmInventorySnapshot) and target memoryMib (PricingReferenceService.getSpec). If peak memory exists, required = currentMemoryMib x peakMemory% x (1 + headroom%). If target memory < required, drop the suggestion and record reason MEMORY_WOULD_NOT_FIT (row still listed with suggestedInstanceType null and costKnown false so admins see why).
4. Add the DTO fields; javadoc updated to describe memory rules.
5. UI: two columns (Mem avg / Mem peak, '—' with tooltip 'CloudWatch agent not reporting memory' when unavailable) and reason chips rendered with Utils.escapeHtml. Run .\build-frontend.ps1.
6. Tests: low CPU + high memory peak -> not a scale-down candidate; low CPU + no memory data -> candidate with MEMORY_UNKNOWN; high memory samples -> SCALE_UP with MEMORY_HIGH; suggestion that would not fit -> MEMORY_WOULD_NOT_FIT; rightsizing.memory.enabled=false -> exactly the old CPU-only results.

**Acceptance criteria**

- Given a VM with CPU 5% but memory peaks of 80%, when rightsizing runs, then it is not suggested for scale-down.
- Given a VM with memory above 85% in every sample of the window, when rightsizing runs, then it appears as SCALE_UP with reason MEMORY_HIGH.
- Given a VM without the CloudWatch agent, when it is a CPU scale-down candidate, then the row shows MEMORY_UNKNOWN and memory columns show a dash.

**Tests**

- CostEstimationServiceTest memory cases

**Notes:** rightsizing.memory.enabled is a kill switch returning CPU-only behaviour.

#### E30-T04 — Store Compute Optimizer recommendations daily with all options, risk and architecture differences

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G18 · **Depends on:** E30-T03, E06-T01, E08-T04, E28-T06

G18 (align with Compute Optimizer): ComputeOptimizerService.parseRecommendations (L84-L103) keeps only the top-ranked type and finding, ignores pagination, under-provisioned/idle findings, performance risk, migration effort and architecture differences.

**Files**

- `src/main/resources/db/migration/V<next>__add_compute_optimizer_recommendation.sql` (new) — compute_optimizer_recommendation and compute_optimizer_option tables
- `src/main/java/com/tcgdigital/vmcontrol/model/ComputeOptimizerRecommendation.java` (new) — Entity per instance (vm_id, finding, finding_reasons, current_type, lookback_days, memory_metric_present, captured_at)
- `src/main/java/com/tcgdigital/vmcontrol/model/ComputeOptimizerOption.java` (new) — rank, instance_type, performance_risk, migration_effort, platform_differences, estimated_monthly_savings
- `src/main/java/com/tcgdigital/vmcontrol/repository/ComputeOptimizerRecommendationRepository.java` (new) — findByVmVmIdIn, deleteByCapturedAtBefore
- `src/main/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerService.java` (modify) — captureRecommendations(): paginate, map all options, persist; getEc2Recommendations reads stored rows
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ComputeOptimizerScheduler.java` (new) — Daily capture via ScheduledJobLockService.runLocked
- `src/main/resources/application.properties` (modify) — cost.optimizer.cron=0 45 5 * * *
- `src/test/java/com/tcgdigital/vmcontrol/service/ComputeOptimizerServiceTest.java` (modify) — Multi-page parsing, option mapping, persistence

**Steps**

1. Migration: compute_optimizer_recommendation (recommendation_id PK, vm_id FK NULL, provider_vm_id, aws_account_ref NULL, region, finding VARCHAR(40), finding_reasons TEXT, current_instance_type, memory_metric_present BOOLEAN, captured_at TIMESTAMP, UNIQUE(provider_vm_id)); compute_optimizer_option (option_id PK, recommendation_id FK ON DELETE CASCADE, option_rank INT, instance_type, performance_risk DECIMAL(4,2), migration_effort VARCHAR(20), platform_differences TEXT, estimated_monthly_savings DECIMAL(12,2) NULL).
2. ComputeOptimizerService.captureRecommendations(): for each CloudTarget among active AWS VMs (E28 grouping), call getEC2InstanceRecommendations following nextToken; map finding (OVER_PROVISIONED, UNDER_PROVISIONED, OPTIMIZED, NOT_OPTIMIZED), findingReasonCodes, utilizationMetrics contains MEMORY, and every option's rank/instanceType/performanceRisk/migrationEffort/platformDifferences/savingsOpportunity.estimatedMonthlySavings. Replace stored rows per provider_vm_id in one transaction; link vm_id via VmRepository provider id lookup.
3. getEc2Recommendations becomes a DB read (no AWS call on page load), keeping the existing Recommendation record but adding options. Keep E08-T05's rule: for scale-down only trust cheaper OVER_PROVISIONED options.
4. ComputeOptimizerScheduler: @Scheduled(cron = "${cost.optimizer.cron:0 45 5 * * *}") wrapped in ScheduledJobLockService.runLocked("compute-optimizer-capture", ...); no-op when cost.optimizer.enabled=false.
5. Tests: two-page response -> all instances stored; option with platformDifferences [ARCHITECTURE] stored; UNDER_PROVISIONED stored; disabled flag -> no calls.

**Acceptance criteria**

- Given Compute Optimizer is enabled, when the daily capture runs, then every recommendation across all pages is stored with all options.
- Given the rightsizing page is opened, when candidates are built, then no Compute Optimizer API call is made.

**Tests**

- ComputeOptimizerServiceTest extended
- ComputeOptimizerScheduler test that it calls runLocked

**Notes:** Read-only AWS calls; same flag cost.optimizer.enabled.

#### E30-T05 — Merge Compute Optimizer and local rules into one recommendation with newer-generation and Graviton options

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G18 · **Depends on:** E30-T04

G18: Datadog reports 83% still run previous generations; recommendations should offer a same-architecture newer-generation move, a Graviton option flagged as needing an ARM64 image, and show where AWS and the local rule agree.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — buildRightsizingCandidates: MODERNIZE direction, alternatives list, agreement flag
- `src/main/java/com/tcgdigital/vmcontrol/dto/RightsizingCandidateDTO.java` (modify) — alternatives (List<RightsizingOptionDTO>), agreement (AGREE|ONLY_AWS|ONLY_RULE|DISAGREE), applicable (boolean), applicableReason
- `src/main/java/com/tcgdigital/vmcontrol/dto/RightsizingOptionDTO.java` (new) — instanceType, kind (RIGHTSIZE|NEWER_GENERATION|GRAVITON), architecture, monthlyCostAfter, monthlySavings, performanceRisk, requiresArchitectureChange
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /rightsizing accepts direction filter (SCALE_DOWN|SCALE_UP|MODERNIZE)
- `src/main/resources/static/js/features/cost-management.js` (modify) — Direction filter, expandable alternatives row, agreement badge, ARM64 warning
- `src/test/java/com/tcgdigital/vmcontrol/service/CostEstimationServiceTest.java` (modify) — Modernize and agreement cases

**Steps**

1. For every plain AWS VM with a known instance type whose PricingReferenceService.getSpec(type).generation is below the family's newest and which is not already a SCALE_DOWN/SCALE_UP candidate, emit direction MODERNIZE with suggestedInstanceType = suggestNewerGeneration(type, inventory architecture), only when it is cheaper or equal per hour.
2. For every candidate, build alternatives: (a) the chosen same-architecture option, (b) newer-generation same-architecture, (c) Graviton (suggestGraviton) with requiresArchitectureChange=true when inventory architecture is x86_64, (d) every stored Compute Optimizer option (E30-T04) with its performanceRisk; price each with lookupHourlyRate and the existing cost window maths.
3. agreement: AGREE when the Compute Optimizer top option equals the local suggestion or both point the same direction; ONLY_AWS / ONLY_RULE when one side is missing; DISAGREE when CO says OPTIMIZED or UNDER_PROVISIONED but the rule says SCALE_DOWN (or vice versa).
4. applicable=false with applicableReason 'Requires an ARM64 image' for options that change architecture; Apply is offered only for applicable options of the same architecture.
5. Controller: optional @RequestParam direction filters before paging. UI: filter pills, expandable alternatives sub-table, agreement badge, ARM64 warning icon. All values escaped. Run .\build-frontend.ps1.
6. Tests: m5.large x86_64 idle-ish -> MODERNIZE to m7i.large with Graviton alternative flagged; CO OPTIMIZED + rule SCALE_DOWN -> DISAGREE; CO option with performanceRisk carried to the DTO.

**Acceptance criteria**

- Given an m5.large x86_64 VM, when rightsizing is listed with direction MODERNIZE, then it suggests m7i.large and lists m7g.large as a Graviton alternative marked 'Requires an ARM64 image'.
- Given Compute Optimizer and the local rule suggest the same type, when listed, then the row shows AGREE.
- Given a Graviton alternative, when an admin views it, then no Apply button is offered for it.

**Tests**

- CostEstimationServiceTest modernize, alternatives and agreement cases

**Notes:** Weekly rightsizing report (WeeklyReportService) keeps using SCALE_DOWN/SCALE_UP only unless product asks otherwise.

#### E30-T06 — Add a safe stop-resize-start with pre-checks and automatic rollback

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G18 · **Depends on:** E30-T05, E05-T09, E17-T04, E00-T03

G18: Apply today only works on an already-stopped VM and has no rollback (VmResizeService.applyInstanceTypeChange); a safe flow should stop, resize, start, verify status checks and revert to the old type on failure.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/VmResizeService.java` (modify) — resizeWithRestart(vmId, targetType, userId) async workflow
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — isInstanceTypeOffered(target, az), describeInstanceArchitecture/enaSupport helpers
- `src/main/resources/db/migration/V<next>__add_vm_resize_event_status.sql` (new) — status, stage, error_message, restart_requested, original_status columns on vm_resize_event (table from E17-T04)
- `src/main/java/com/tcgdigital/vmcontrol/model/VmResizeEvent.java` (modify) — New fields; status enum ResizeStatus
- `src/main/java/com/tcgdigital/vmcontrol/model/ResizeStatus.java` (new) — REQUESTED, STOPPING, RESIZING, STARTING, COMPLETED, ROLLING_BACK, ROLLED_BACK, FAILED
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — VM_RESIZE_ROLLED_BACK
- `src/main/java/com/tcgdigital/vmcontrol/dto/ApplyRightsizingRequestDTO.java` (modify) — boolean restartIfRunning
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — POST /rightsizing/apply returns 202 + resize id when restartIfRunning; GET /rightsizing/resizes/{id}
- `src/main/resources/application.properties` (modify) — rightsizing.safe-resize.enabled=false
- `src/test/java/com/tcgdigital/vmcontrol/service/VmResizeServiceTest.java` (modify) — Happy path, start failure rollback, pre-check failures

**Steps**

1. Migration: add status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED', stage VARCHAR(100) NULL, error_message TEXT NULL, restart_requested BOOLEAN NOT NULL DEFAULT FALSE, original_status VARCHAR(20) NULL to vm_resize_event.
2. Pre-checks (synchronous, 400 on failure): rightsizing.safe-resize.enabled=true; caller holds the environment lock (lockService.verifyLockPermission); no active operation (E05-T09 check); target type offered in the VM's AZ (DescribeInstanceTypeOfferings with location-type availability-zone); target supports the instance's architecture (DescribeInstanceTypes processorInfo.supportedArchitectures vs inventory architecture); if target requires ENA (networkInfo.enaSupport=required) the instance has enaSupport=true (DescribeInstanceAttribute or Instance.enaSupport()).
3. Create VmResizeEvent(status REQUESTED, original type, target, original live status, restartRequested) and return its id; run the workflow with @Async on the existing operation executor (AsyncConfig) after commit (AfterCommit.run).
4. Workflow: if original status RUNNING -> STOPPING: providerService.stopVm(target, false, listener).join(); require STOPPED. RESIZING: changeInstanceType. If original was RUNNING -> STARTING: startVm(...).join(); success requires RUNNING with status checks passed (the existing AwsCloudProviderService waitForStartReadiness). COMPLETED: update VmInventorySnapshot, VM status via VmRepository.updateStatusIfCurrent, audit VM_RESIZE_COMPLETED, notify the requester.
5. Rollback: if start fails (InsufficientInstanceCapacity, Unsupported, status checks failed or timeout) -> ROLLING_BACK: stop if not stopped, changeInstanceType back to the original, start again, then ROLLED_BACK with error_message and audit VM_RESIZE_ROLLED_BACK + notification. If rollback itself fails -> FAILED, notification to environment admins with manual-fix instructions. If changeInstanceType fails -> start the VM again with the original type (when it was running) and mark FAILED.
6. Controller: restartIfRunning=false keeps today's behaviour (VM must be stopped). GET /api/v1/cost-management/rightsizing/resizes/{id} (ADMIN) returns status/stage/error.
7. Tests (Mockito AwsCloudProviderService): running VM happy path calls stop, change, start in order; start failure triggers change back and second start and ends ROLLED_BACK; AZ not offered -> 400 and no AWS state change; flag off -> 400.

**Acceptance criteria**

- Given safe resize is enabled and an admin holds the lock, when they apply a recommendation to a running VM with restart, then the VM is stopped, resized, started and passes status checks, and the event ends COMPLETED.
- Given the new type cannot start (capacity), when the workflow runs, then the VM is reverted to its original type, started again, the event ends ROLLED_BACK and the requester is notified.
- Given the target type is not offered in the VM's AZ, when apply is requested, then it is rejected before any AWS change.

**Tests**

- VmResizeServiceTest: ordering, rollback, pre-checks, flag

**Notes:** rightsizing.safe-resize.enabled defaults false. Changes AWS state; pilot on one non-production environment first.

#### E30-T07 — Apply dialog with pre-check results, restart option and live resize progress

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G18 · **Depends on:** E30-T06, E02-T01

G18: admins need to see what the safe resize will do (downtime, pre-checks) and follow its progress and rollback.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /rightsizing/precheck?vmId&targetInstanceType returns check list
- `src/main/java/com/tcgdigital/vmcontrol/service/VmResizeService.java` (modify) — precheck(vmId, target) returning List<ResizeCheckDTO> without side effects
- `src/main/java/com/tcgdigital/vmcontrol/dto/ResizeCheckDTO.java` (new) — name, passed, message
- `src/main/resources/static/js/features/cost-management.js` (modify) — Apply dialog: checks, 'Stop, resize and start again' option for running VMs, progress polling, result toast
- `src/test/java/com/tcgdigital/vmcontrol/service/VmResizeServiceTest.java` (modify) — precheck results

**Steps**

1. Extract the step-2 checks of E30-T06 into precheck() returning every check result (lock held, no active operation, offered in AZ, architecture, ENA, flag); the apply path calls it and rejects if any failed.
2. UI: replace the Apply confirm with a modal that loads /rightsizing/precheck, lists checks with pass/fail icons, shows estimated downtime text ('VM will be stopped and started; typically 2-5 minutes') when running, and enables Confirm only when all pass. For running VMs the 'Stop, resize and start again' checkbox is required to proceed.
3. After 202, poll GET /rightsizing/resizes/{id} with RealTime.startPolling(key, fn, 5000, { pageScoped: true }) until a terminal status; show stage text; on ROLLED_BACK/FAILED show the error_message as text. Refresh the rightsizing table at the end.
4. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given a running VM and a valid target, when the admin opens Apply, then all checks are listed and Confirm requires the restart option.
- Given a resize in progress, when the admin watches the dialog, then the stage updates until COMPLETED or ROLLED_BACK.
- Given the admin navigates away, when the resize is still running, then polling stops.

**Tests**

- VmResizeServiceTest precheck cases
- Manual UI checklist

## E31

### Spot for non-prod and EKS — Later

Owners of interruptible environments can opt in to Spot: EKS node groups move to a Spot node group with automatic on-demand fallback, Spot VMs are priced and reported correctly, and interruptions are detected and surfaced.

Sources: G19 · 8 tasks · 10 days

#### E31-T01 — Record the purchase option (on-demand or Spot) of every EC2 instance and EKS node group

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G19 · **Depends on:** E28-T06, E05-T04

G19: the platform cannot tell Spot from on-demand today: AwsCloudInventoryProviderService.fetchInventory (L49-105) ignores Instance.instanceLifecycle and EksSyncService.upsertNodeGroup (L326-L440) ignores Nodegroup.capacityType, so Spot cost and interruptions cannot be handled.

**Files**

- `src/main/resources/db/migration/V<next>__add_purchase_option.sql` (new) — purchase_option VARCHAR(20) NOT NULL DEFAULT 'ON_DEMAND' on vm_inventory_snapshot and vm
- `src/main/java/com/tcgdigital/vmcontrol/model/PurchaseOption.java` (new) — enum ON_DEMAND, SPOT
- `src/main/java/com/tcgdigital/vmcontrol/service/CloudInventoryProviderService.java` (modify) — VmInventoryData.purchaseOption
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudInventoryProviderService.java` (modify) — SPOT when instance.instanceLifecycle() == InstanceLifecycleType.SPOT
- `src/main/java/com/tcgdigital/vmcontrol/service/VmInventoryService.java` (modify) — Persist purchaseOption to snapshot and vm
- `src/main/java/com/tcgdigital/vmcontrol/model/VmInventorySnapshot.java` (modify) — purchaseOption field
- `src/main/java/com/tcgdigital/vmcontrol/model/Vm.java` (modify) — purchaseOption field (@Enumerated STRING)
- `src/main/java/com/tcgdigital/vmcontrol/service/EksSyncService.java` (modify) — Set vm.purchaseOption from nodegroup.capacityType()
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmDTO.java` (modify) — purchaseOption
- `src/main/resources/static/js/features/environments.js` (modify) — 'Spot' badge next to the VM region (around L702)
- `src/test/java/com/tcgdigital/vmcontrol/service/EksSyncServiceTest.java` (modify) — SPOT node group sets purchaseOption
- `src/test/java/com/tcgdigital/vmcontrol/service/VmInventoryServiceTest.java` (modify) — Spot lifecycle persisted (test created by E08-T06)

**Steps**

1. Migration adds purchase_option to vm_inventory_snapshot and vm (default ON_DEMAND).
2. Add PurchaseOption enum; VmInventoryData gets purchaseOption; AwsCloudInventoryProviderService maps instance.instanceLifecycle() SPOT -> SPOT else ON_DEMAND.
3. VmInventoryService writes it to the snapshot and to vm.purchaseOption (use a targeted update or the E05-T04 pattern, not a full save that could overwrite status).
4. EksSyncService.upsertNodeGroup: vm.purchaseOption = SPOT when nodegroup.capacityType() == CapacityTypes.SPOT.
5. VmDTO exposes it; environments.js shows a small 'Spot' badge (escaped, Bootstrap badge) for SPOT VMs. Run .\build-frontend.ps1.
6. Tests for both sync paths.

**Acceptance criteria**

- Given an EC2 Spot instance registered as a VM, when inventory sync runs, then the VM shows purchaseOption SPOT and a Spot badge.
- Given a SPOT EKS managed node group, when EKS sync runs, then its VM is SPOT.

**Tests**

- EksSyncServiceTest
- VmInventoryServiceTest

**Notes:** Read-only. Assumption: EC2 instances cannot be converted between purchase options in place, so EC2 Spot support covers instances already launched as Spot (persistent requests with stop behaviour); conversion is offered for EKS node groups only (E31-T05).

#### E31-T02 — Ingest Spot prices daily and use them in cost estimates with a Spot-savings figure

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G19 · **Depends on:** E31-T01, E06-T01

G19 (cost reporting): EstimatedCostProvider prices every VM and node group at the on-demand reference rate (L99-101), overstating Spot cost and hiding Spot savings.

**Files**

- `src/main/resources/db/migration/V<next>__add_spot_price_snapshot.sql` (new) — spot_price_snapshot (aws_account_ref, region, availability_zone, instance_type, price_per_hour, captured_on DATE, UNIQUE(region, availability_zone, instance_type, captured_on))
- `src/main/java/com/tcgdigital/vmcontrol/model/SpotPriceSnapshot.java` (new) — Entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/SpotPriceSnapshotRepository.java` (new) — Latest price per (region, az, type)
- `src/main/java/com/tcgdigital/vmcontrol/service/SpotPriceService.java` (new) — captureDaily(), lookupSpotRate(type, region, az)
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/SpotPriceScheduler.java` (new) — Daily capture via runLocked
- `src/main/java/com/tcgdigital/vmcontrol/service/EstimatedCostProvider.java` (modify) — Use Spot rate for SPOT VMs; expose onDemandEquivalentCost
- `src/main/java/com/tcgdigital/vmcontrol/service/CostDataProvider.java` (modify) — VmCostEstimate gains onDemandEquivalentCost (nullable)
- `src/main/java/com/tcgdigital/vmcontrol/dto/CostSummaryDTO.java` (modify) — spotSavings, spotVmCount
- `src/main/java/com/tcgdigital/vmcontrol/service/CostEstimationService.java` (modify) — getSummary computes spotSavings
- `src/main/resources/application.properties` (modify) — spot.pricing.enabled=false, spot.pricing.cron=0 20 4 * * *
- `src/test/java/com/tcgdigital/vmcontrol/service/SpotPriceServiceTest.java` (new) — Capture and lookup
- `src/test/java/com/tcgdigital/vmcontrol/service/EstimatedCostProviderTest.java` (modify) — SPOT VM priced at Spot rate

**Steps**

1. SpotPriceService.captureDaily(): for each CloudTarget that has SPOT VMs or spot-eligible environments (E31-T03), collect distinct instance types (inventory + EKS metadata instanceType) and call DescribeSpotPriceHistory(productDescriptions 'Linux/UNIX', instanceTypes, startTime now-1h) with pagination; keep the newest price per (az, type); upsert rows for today. Read-only, gated by spot.pricing.enabled.
2. lookupSpotRate(type, region, az): exact AZ price of the latest day, else the average across AZs in the region, else empty.
3. SpotPriceScheduler with ScheduledJobLockService.runLocked("spot-price-capture", ...).
4. EstimatedCostProvider: when vm.purchaseOption == SPOT and a Spot rate exists, use it (x nodeCount for EKS) and record onDemandEquivalentCost using the on-demand rate; otherwise fall back to on-demand (and onDemandEquivalentCost null).
5. CostEstimationService.getSummary adds spotSavings = sum(onDemandEquivalentCost - cost) and spotVmCount.
6. Tests: price capture picks newest per AZ; lookup fallback to region average; SPOT VM cost uses Spot rate and spotSavings is positive.

**Acceptance criteria**

- Given spot pricing is enabled and a SPOT node group of 3 m6i.large nodes, when costs are estimated, then the hourly rate is 3 x the current Spot price and the summary shows the saving versus on-demand.
- Given spot pricing is disabled, when costs are estimated, then results are unchanged.

**Tests**

- SpotPriceServiceTest
- EstimatedCostProviderTest SPOT case

**Notes:** Needs ec2:DescribeSpotPriceHistory (read-only).

#### E31-T03 — Add an opt-in Spot policy per environment with per-VM exclusions

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G19 · **Depends on:** E31-T02, E03-T01, E00-T02

G19: Spot must be optional and limited to interruptible (non-production) environments chosen by their owners.

**Files**

- `src/main/resources/db/migration/V<next>__add_spot_policy.sql` (new) — environment.spot_policy VARCHAR(20) NOT NULL DEFAULT 'NONE'; vm.spot_excluded BOOLEAN NOT NULL DEFAULT FALSE
- `src/main/java/com/tcgdigital/vmcontrol/model/SpotPolicy.java` (new) — enum NONE, ALLOWED
- `src/main/java/com/tcgdigital/vmcontrol/model/Environment.java` (modify) — spotPolicy
- `src/main/java/com/tcgdigital/vmcontrol/model/Vm.java` (modify) — spotExcluded
- `src/main/java/com/tcgdigital/vmcontrol/controller/SpotController.java` (new) — PUT /api/v1/environments/{envId}/spot-policy, PUT /api/v1/environments/{envId}/vms/{vmId}/spot-excluded
- `src/main/java/com/tcgdigital/vmcontrol/service/SpotPolicyService.java` (new) — Validation, audit
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — SPOT_POLICY_CHANGED
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — spotPolicy
- `src/main/resources/application.properties` (modify) — spot.enabled=false
- `src/test/java/com/tcgdigital/vmcontrol/controller/SpotControllerTest.java` (new) — Security and validation

**Steps**

1. Migration and entity fields.
2. SpotPolicyService.setPolicy(envId, policy, userId): requires spot.enabled=true (else ValidationException 'Spot is disabled'); caller must be able to administer the environment (SecurityService helper from E03-T01); refuse ALLOWED when the environment is marked production (if E15/E23 introduced a production/criticality marker use it; otherwise require a confirmation flag acknowledgeInterruptible=true in the request). Audit SPOT_POLICY_CHANGED.
3. setVmExcluded(envId, vmId, excluded) with SecurityService.assertSameEnvironment(vm env, envId).
4. Controller endpoints; EnvironmentDTO exposes spotPolicy.
5. Tests: VIEWER/USER -> 403; ENV_ADMIN of another environment -> 403/404; flag off -> 400; happy path writes audit.

**Acceptance criteria**

- Given spot is enabled, when an environment admin sets ALLOWED with acknowledgeInterruptible, then the environment is Spot-eligible and the change is audited.
- Given spot.enabled=false, when anyone sets a policy, then the request is rejected.

**Tests**

- SpotControllerTest (SecuredWebTestBase)
- SpotPolicyServiceTest

**Notes:** Assumption: no 'production' flag exists on Environment today (E15-T01 note), so an explicit acknowledgement is required.

#### E31-T04 — List Spot opportunities with estimated savings and placement scores

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G19 · **Depends on:** E31-T03, E00-T04

G19: owners need to see which on-demand node groups and VMs in Spot-eligible environments would save money and how likely Spot capacity is.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/SpotOpportunityService.java` (new) — Build opportunity rows
- `src/main/java/com/tcgdigital/vmcontrol/dto/SpotOpportunityDTO.java` (new) — vmId, name, environment, kind (EKS_NODE_GROUP|EC2), instanceType, nodeCount, onDemandMonthly, spotMonthly, savings, placementScore, eligible, reason
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /api/v1/cost-management/spot-opportunities (ADMIN), paged with Paging.of
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — getSpotPlacementScores(target, instanceTypes, targetCapacity)
- `src/main/resources/static/js/features/cost-management.js` (modify) — Spot opportunities table
- `src/test/java/com/tcgdigital/vmcontrol/service/SpotOpportunityServiceTest.java` (new) — Eligibility and savings maths

**Steps**

1. For environments with spotPolicy ALLOWED, take ON_DEMAND VMs not spotExcluded. EKS node groups are eligible (convertible in E31-T05); EC2 VMs are listed as 'requires relaunch' with eligible=false (informational).
2. savings = (on-demand rate - Spot rate) x runtime hours of the cost window x node count, using EstimatedCostProvider runtime and SpotPriceService.
3. Placement score: call EC2 GetSpotPlacementScores (singleAvailabilityZone=false, regionNames=[region], instanceTypes = diversified list of same-size types from PricingReferenceService specs, targetCapacity = node count); cache per (account, region, type set) for 24h in memory; on AccessDenied leave null.
4. Endpoint and a table in Cost Management (hidden when spot.enabled=false), sorted by savings.
5. Tests: excluded VMs omitted; EC2 rows eligible=false; savings computed.

**Acceptance criteria**

- Given a Spot-eligible environment with an on-demand node group, when an admin opens Spot opportunities, then the node group is listed with monthly savings and a placement score.
- Given a VM is excluded, when the list is built, then it is not shown.

**Tests**

- SpotOpportunityServiceTest

**Notes:** Needs ec2:GetSpotPlacementScores (read-only).

#### E31-T05 — Convert an EKS node group to Spot by creating a Spot twin and keeping the on-demand group as standby

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G19 · **Depends on:** E31-T04, E05-T07, E00-T03

G19: EKS managed node group capacity type is fixed at creation, so Spot needs a twin node group; the on-demand group stays as the fallback.

**Files**

- `src/main/resources/db/migration/V<next>__add_vm_standby.sql` (new) — vm.is_standby BOOLEAN NOT NULL DEFAULT FALSE, vm.spot_partner_vm_id VARCHAR(36) NULL
- `src/main/java/com/tcgdigital/vmcontrol/model/Vm.java` (modify) — isStandby, spotPartnerVmId
- `src/main/java/com/tcgdigital/vmcontrol/service/EksCloudProviderService.java` (modify) — createSpotTwin(target, cluster, sourceNodegroup, instanceTypes), deleteNodegroup(...)
- `src/main/java/com/tcgdigital/vmcontrol/service/SpotConversionService.java` (new) — convertToSpot(vmId, userId), revertToOnDemand(vmId, userId)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — Skip is_standby VMs when building START plans
- `src/main/java/com/tcgdigital/vmcontrol/controller/SpotController.java` (modify) — POST /api/v1/environments/{envId}/vms/{vmId}/convert-to-spot, POST .../revert-to-on-demand
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — SPOT_CONVERSION_STARTED, SPOT_CONVERSION_COMPLETED, SPOT_CONVERSION_FAILED, SPOT_REVERTED
- `src/main/resources/application.properties` (modify) — spot.eks.conversion.enabled=false, spot.eks.min-instance-types=3
- `src/test/java/com/tcgdigital/vmcontrol/service/SpotConversionServiceTest.java` (new) — Happy path, failure leaves on-demand running, revert

**Steps**

1. Migration and Vm fields.
2. EksCloudProviderService.createSpotTwin: describeNodegroup(source); CreateNodegroup with name <source>-spot, capacityType SPOT, instanceTypes = source type plus same vCPU/memory alternatives from PricingReferenceService specs (at least spot.eks.min-instance-types, same architecture), same subnets, nodeRole, labels, taints, launchTemplate (when set; instanceTypes must then be omitted from the launch template), scalingConfig copied from source, tags plus tcg:spot-twin-of=<source>. Wait until ACTIVE with the existing waitForNodegroupActive helper.
3. SpotConversionService.convertToSpot (async after commit, requires spot.enabled and spot.eks.conversion.enabled, environment spotPolicy ALLOWED, VM not excluded, caller holds the environment lock, no active operation): create twin -> register it as a Vm (AWS_EKS, purchaseOption SPOT, same group, sequence/dependencies copied) via EksSyncService.upsertNodeGroup -> once the twin's desired nodes are ready, scale the source to 0 (reuse stopVm which saves scaling config) and mark the source is_standby=true with spotPartnerVmId pointing to the twin (and vice versa). Audit each stage; notify the requester.
4. If the twin fails to become ACTIVE or reach desired size within eks.nodegroup.operation-timeout-ms, delete the twin, leave the source untouched and running, audit SPOT_CONVERSION_FAILED.
5. revertToOnDemand: start the standby source (startVm restores saved size), wait ready, stop and delete the twin, clear standby flags, deactivate the twin Vm.
6. VmOperationsService plan building: exclude is_standby VMs from START targets (STOP still includes them so nothing is left running).
7. Tests (Mockito EksClient): CreateNodegroupRequest has capacityType SPOT and >=3 instance types; source scaled to 0 only after twin ready; timeout path deletes twin; revert order; START plan skips standby VMs.

**Acceptance criteria**

- Given a Spot-eligible environment and conversion enabled, when an admin converts a node group, then a SPOT twin with diversified instance types runs the workload and the on-demand group is scaled to 0 as standby.
- Given the twin cannot get capacity, when the timeout passes, then the twin is deleted and the on-demand group keeps running.
- Given a converted environment, when it is started, then the Spot twin starts and the standby group does not.

**Tests**

- SpotConversionServiceTest
- VmOperationsServiceTest standby exclusion

**Notes:** Changes AWS state behind spot.eks.conversion.enabled=false. Needs eks:CreateNodegroup, eks:DeleteNodegroup and iam:PassRole on the node role.

#### E31-T06 — Fall back to on-demand automatically when a Spot node group loses capacity

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** high · **Sources:** G19 · **Depends on:** E31-T05

G19: Spot needs on-demand fallback; when a Spot twin cannot keep its desired nodes, the standby on-demand group must take over.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/SpotFallbackService.java` (new) — Evaluate Spot twins; trigger and clear fallback
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/SpotFallbackScheduler.java` (new) — Every 5 min via runLocked
- `src/main/java/com/tcgdigital/vmcontrol/service/EksCloudProviderService.java` (modify) — getNodegroupHealth(target, cluster, ng): health issue codes and actual vs desired
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — SPOT_FALLBACK_ACTIVATED, SPOT_FALLBACK_CLEARED
- `src/main/resources/application.properties` (modify) — spot.fallback.enabled=false, spot.fallback.grace-minutes=10
- `src/test/java/com/tcgdigital/vmcontrol/service/SpotFallbackServiceTest.java` (new) — Trigger, grace, clear

**Steps**

1. For each active SPOT twin VM whose environment is running (twin desired > 0), read nodegroup health: issues containing AsgInstanceLaunchFailures / InsufficientFreeAddresses / Ec2...Capacity codes, or the twin's Auto Scaling group in-service count below desired (describeNodegroup resources.autoScalingGroups + autoscaling DescribeAutoScalingGroups; add the autoscaling SDK module) for longer than spot.fallback.grace-minutes.
2. Trigger fallback: startVm on the standby partner (restores saved size), set Vm metadata spotFallbackActive=true on the twin, audit SPOT_FALLBACK_ACTIVATED, notify environment admins.
3. Clear fallback: when the twin has been healthy at desired size for 2 consecutive checks, stop the standby partner again and audit SPOT_FALLBACK_CLEARED.
4. Never act while an operation is running in the environment; skip environments that are stopped.
5. Tests: degraded for less than grace -> no action; beyond grace -> partner started once (idempotent across runs); healthy again -> partner stopped.

**Acceptance criteria**

- Given a Spot twin below desired capacity for longer than the grace period, when the fallback job runs, then the on-demand standby group is scaled back up and admins are notified.
- Given Spot capacity returns, when two checks pass, then the standby group is scaled back to 0.

**Tests**

- SpotFallbackServiceTest

**Notes:** spot.fallback.enabled defaults false. Assumption: Karpenter/cluster-autoscaler integration is out of scope; fallback works at managed-node-group level.

#### E31-T07 — Detect and surface Spot interruptions for EC2 Spot VMs and Spot node groups

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** medium · **Sources:** G19 · **Depends on:** E31-T06, E11-T01

G19 (interruption handling): when AWS reclaims a Spot instance, state sync records it as plain drift; owners should see it was an interruption and Spot start failures need a clear message.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — getStateReasons(target, instanceIds) -> Map<id, stateReasonCode>; map InsufficientInstanceCapacity on start to a Spot-specific failure message
- `src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java` (modify) — For SPOT VMs that became STOPPED/TERMINATED without an operation, fetch state reasons; record change_source 'spot_interruption'
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — SPOT_INTERRUPTION
- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — SPOT_INTERRUPTED
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifySpotInterrupted(vm)
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmStateHistoryRepository.java` (modify) — countByChangeSourceAndChangedAtBetween for reporting
- `src/test/java/com/tcgdigital/vmcontrol/service/StateSyncServiceTest.java` (modify) — Interruption recorded and notified

**Steps**

1. getStateReasons: DescribeInstances for the ids (batched, same client) and return instance.stateReason().code().
2. StateSyncService: after batch status fetch, for VMs with purchaseOption SPOT whose DB status was RUNNING and live status is STOPPED/TERMINATED and no active operation, call getStateReasons; codes Server.SpotInstanceShutdown or Server.SpotInstanceTermination -> save VmStateHistory with changeSource 'spot_interruption' and details containing the code, audit SPOT_INTERRUPTION, notify environment admins and current lock holder (AfterCommit).
3. On start of a SPOT VM, map InsufficientInstanceCapacity / SpotMaxPriceTooLow errors to 'Spot capacity is not available right now - try again later or another instance type' in the VmOperationResult message.
4. EKS: when SpotFallbackService (E31-T06) activates fallback, it also records a 'spot_interruption' state-history row on the twin so reporting counts both.
5. Tests: interruption code -> history row + notification; normal user stop -> no interruption; start failure message mapping.

**Acceptance criteria**

- Given a Spot VM reclaimed by AWS, when state sync runs, then a 'spot_interruption' history row is written and environment admins get a notification.
- Given a Spot VM start fails for capacity, when the operation finishes, then the failure message explains Spot capacity is unavailable.

**Tests**

- StateSyncServiceTest interruption case
- AwsCloudProviderServiceTest start error mapping

**Notes:** Assumption: polling-based detection (5-minute state sync) is enough for non-prod; an EventBridge -> SQS listener for the 2-minute warning is a possible follow-up. Depends on E11-T01 because audit writes go through its AuditWriter.

#### E31-T08 — Spot panel on the environment page and Spot reporting in Cost Management

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G19 · **Depends on:** E31-T07, E02-T01

G19 (cost reporting): owners and admins need to see Spot policy, conversions, fallbacks, interruptions and realised Spot savings.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/SpotController.java` (modify) — GET /api/v1/environments/{envId}/spot-summary
- `src/main/java/com/tcgdigital/vmcontrol/dto/SpotSummaryDTO.java` (new) — policy, spotVmCount, standbyCount, fallbackActive, interruptions30d, spotSavingsMonth
- `src/main/java/com/tcgdigital/vmcontrol/service/SpotPolicyService.java` (modify) — summary(envId)
- `src/main/resources/static/js/features/environments.js` (modify) — Spot panel: policy toggle with acknowledgement, per-VM exclude, convert/revert buttons, fallback badge, interruptions
- `src/main/resources/static/js/features/cost-management.js` (modify) — 'Spot savings' KPI and interruptions count
- `docs/spot.md` (new) — How Spot works here, IAM actions, flags and rollout

**Steps**

1. summary(envId) requires view rights (SecurityService.assertCanView) and aggregates the data from E31-T02..T07.
2. Environment page panel shown only when spot.enabled (expose via existing config/feature endpoint or the summary returning 404 when disabled). Buttons only for users who can administer the environment. All values escaped; polling page-scoped.
3. Cost Management KPI tile 'Spot savings (30d)' from CostSummaryDTO.spotSavings and interruptions count.
4. docs/spot.md: flags (spot.enabled, spot.pricing.enabled, spot.eks.conversion.enabled, spot.fallback.enabled), IAM actions, pilot plan. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given a Spot-eligible environment, when an owner opens it, then they see policy, Spot node groups, standby/fallback state and interruptions in the last 30 days.
- Given a VIEWER, when they open the panel, then no action buttons are shown and actions return 403 if called.

**Tests**

- SpotControllerTest summary security
- Manual UI checklist

## E32

### Templates & provisioning — Later

Users can request a new environment from an approved template (AWS Service Catalog or Terraform), it is provisioned after approval, registered automatically, and destroyed at the end of its time-to-live through the lease mechanism.

Sources: G20 · 8 tasks · 12.5 days

#### E32-T01 — Add the environment template catalogue model and admin API

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G20 · **Depends on:** E00-T02, E00-T05, E28-T03

G20: the platform can only control existing environments; a catalogue of approved templates is the base for self-service provisioning.

**Files**

- `src/main/resources/db/migration/V<next>__add_environment_templates.sql` (new) — environment_template and provisioning_request tables
- `src/main/java/com/tcgdigital/vmcontrol/model/EnvironmentTemplate.java` (new) — Template entity
- `src/main/java/com/tcgdigital/vmcontrol/model/ProvisioningEngineType.java` (new) — enum SERVICE_CATALOG, TERRAFORM
- `src/main/java/com/tcgdigital/vmcontrol/model/ProvisioningRequest.java` (new) — Request entity
- `src/main/java/com/tcgdigital/vmcontrol/model/ProvisioningStatus.java` (new) — PENDING_APPROVAL, APPROVED, REJECTED, PROVISIONING, READY, FAILED, DESTROYING, DESTROYED, CANCELLED
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentTemplateRepository.java` (new) — findByIsActiveTrueOrderByName
- `src/main/java/com/tcgdigital/vmcontrol/repository/ProvisioningRequestRepository.java` (new) — findByStatus, findByRequestedByUserId, findByDestroyAtBeforeAndStatus
- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/TemplateService.java` (new) — CRUD, parameter-schema validation
- `src/main/java/com/tcgdigital/vmcontrol/controller/TemplateController.java` (new) — /api/v1/templates: GET list (authenticated), admin POST/PUT/DELETE
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentTemplateDTO.java` (new) — Template view
- `src/main/java/com/tcgdigital/vmcontrol/dto/UpsertEnvironmentTemplateDTO.java` (new) — Validated request
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — TEMPLATE_CREATED, TEMPLATE_UPDATED, TEMPLATE_RETIRED
- `src/main/resources/application.properties` (modify) — provisioning.enabled=false
- `src/test/java/com/tcgdigital/vmcontrol/controller/TemplateControllerTest.java` (new) — Security, validation

**Steps**

1. Migration: environment_template (template_id PK, name UNIQUE, description TEXT, engine VARCHAR(20), engine_ref TEXT (JSON: Service Catalog productId + provisioningArtifactId + launchPathId, or Terraform organization/workspace-prefix/module), parameter_schema TEXT (JSON array of {key,label,type:string|number|enum,required,default,options,pattern}), aws_account_ref NULL, region, default_ttl_hours INT, max_ttl_hours INT, requires_approval BOOLEAN DEFAULT TRUE, approver_role VARCHAR(20) DEFAULT 'ADMIN', allowed_roles VARCHAR(100) DEFAULT 'ENV_ADMIN,USER', estimated_monthly_cost DECIMAL(12,2) NULL, is_active, created_by, created_at, updated_at). provisioning_request (request_id PK, template_id FK, requested_by_user_id, environment_name, parameters TEXT, justification TEXT, ttl_hours INT, status VARCHAR(20), reviewed_by_user_id, reviewed_at, review_notes, engine_record_id, engine_resource_id, environment_id FK NULL, destroy_at TIMESTAMP NULL, error_message TEXT, created_at, updated_at).
2. Entities, enums and repositories.
3. TemplateService: create/update validates engine_ref shape per engine and parameter_schema (unique keys, valid types, enum options non-empty, default matches pattern); retire = isActive false. Audit each change.
4. TemplateController: GET /api/v1/templates returns active templates the caller's role may request (allowed_roles); ADMIN-only POST, PUT /{id}, DELETE /{id} (retire). Entire controller returns 404 when provisioning.enabled=false.
5. Tests: non-admin write -> 403; invalid schema -> 400; USER only sees templates allowing USER; flag off -> 404.

**Acceptance criteria**

- Given an ADMIN, when they create a template with a valid parameter schema, then it is listed for the allowed roles and the change is audited.
- Given provisioning.enabled=false, when anyone calls /api/v1/templates, then 404 is returned.

**Tests**

- TemplateControllerTest (SecuredWebTestBase)
- TemplateServiceTest schema validation

**Notes:** Assumption: one template produces one environment; multi-environment stacks are out of scope.

#### E32-T02 — Add the ProvisioningEngine abstraction and the AWS Service Catalog engine

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G20 · **Depends on:** E32-T01

G20: provisioning needs a pluggable engine; AWS Service Catalog lets platform teams publish approved CloudFormation/Terraform products that this app launches.

**Files**

- `build.gradle` (modify) — implementation 'software.amazon.awssdk:servicecatalog'
- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/ProvisioningEngine.java` (new) — Interface: type(), provision(request, template) -> engineRecordId, poll(recordId) -> ProvisioningProgress, outputs(resourceId), destroy(resourceId) -> recordId
- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/ProvisioningProgress.java` (new) — record(status IN_PROGRESS|SUCCEEDED|FAILED, resourceId, message)
- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/ServiceCatalogEngine.java` (new) — ProvisionProduct, DescribeRecord, GetProvisionedProductOutputs, TerminateProvisionedProduct
- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/ProvisioningEngineRegistry.java` (new) — Map engine type -> bean (pattern of CloudProviderFactory)
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsClientFactory.java` (modify) — serviceCatalog(accountRef, region)
- `src/test/java/com/tcgdigital/vmcontrol/service/provisioning/ServiceCatalogEngineTest.java` (new) — Request mapping and status mapping

**Steps**

1. Add the servicecatalog SDK module.
2. ServiceCatalogEngine.provision: ProvisionProductRequest(productId, provisioningArtifactId, pathId from engine_ref, provisionedProductName = environment name, provisioningParameters from the request parameters, provisionToken = request id for idempotency, tags tcg:environment=<name>, tcg:managed-by=vmcontrol, tcg:provisioning-request=<id>). Return recordDetail.recordId.
3. poll: DescribeRecord(recordId) -> CREATED/IN_PROGRESS/IN_PROGRESS_IN_ERROR = IN_PROGRESS, SUCCEEDED, FAILED (message from recordErrors); resourceId = provisionedProductId.
4. outputs: GetProvisionedProductOutputs -> Map<String,String>. destroy: TerminateProvisionedProduct(provisionedProductId, terminateToken = request id + '-destroy').
5. ProvisioningEngineRegistry collects all ProvisioningEngine beans.
6. Tests with a mocked ServiceCatalogClient: request has tags and idempotency token; status mapping; FAILED message propagated.

**Acceptance criteria**

- Given a Service Catalog template, when provision is called, then ProvisionProduct is sent with the environment tags and an idempotency token.
- Given a record that failed, when polled, then FAILED with AWS's error message is returned.

**Tests**

- ServiceCatalogEngineTest

**Notes:** IAM: servicecatalog:ProvisionProduct, DescribeRecord, GetProvisionedProductOutputs, TerminateProvisionedProduct; the launch constraint role in Service Catalog does the actual resource creation.

#### E32-T03 — Let users request an environment from a template and route the request for approval

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G20 · **Depends on:** E32-T02, E01-T08, E03-T06, E00-T03, E00-T04

G20: templates must be approved before provisioning; requests need validation of parameters, names and TTL.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/ProvisioningRequestService.java` (new) — create, approve, reject, cancel, list
- `src/main/java/com/tcgdigital/vmcontrol/controller/ProvisioningRequestController.java` (new) — /api/v1/provisioning-requests endpoints
- `src/main/java/com/tcgdigital/vmcontrol/dto/CreateProvisioningRequestDTO.java` (new) — templateId, environmentName, parameters, ttlHours, justification
- `src/main/java/com/tcgdigital/vmcontrol/dto/ProvisioningRequestDTO.java` (new) — View incl. status, destroyAt, environmentId
- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — PROVISIONING_REQUESTED, PROVISIONING_APPROVED, PROVISIONING_REJECTED
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — Notify approvers and requester
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — PROVISIONING_REQUESTED, PROVISIONING_APPROVED, PROVISIONING_REJECTED, PROVISIONING_CANCELLED
- `src/test/java/com/tcgdigital/vmcontrol/service/provisioning/ProvisioningRequestServiceTest.java` (new) — Validation and state transitions
- `src/test/java/com/tcgdigital/vmcontrol/controller/ProvisioningRequestControllerTest.java` (new) — Security

**Steps**

1. create: template active and caller role allowed; environmentName matches the server-side name rule from E01-T08 and is unique across environment.name and open requests; parameters validated against parameter_schema (required, type, enum, pattern; unknown keys rejected); ttlHours defaults to default_ttl_hours and must be <= max_ttl_hours. Status PENDING_APPROVAL, or APPROVED immediately when requires_approval=false. Audit and notify approvers (users with approver_role) after commit.
2. approve/reject (approver_role or ADMIN; requester cannot approve own request, mirroring E03-T06): conditional status update from PENDING_APPROVAL (optimistic: UPDATE ... WHERE status='PENDING_APPROVAL', 409 if 0 rows). Reject requires notes. Notify requester.
3. cancel by requester while PENDING_APPROVAL.
4. Endpoints: POST /api/v1/provisioning-requests, GET /mine, GET /pending (approvers), POST /{id}/approve, POST /{id}/reject, POST /{id}/cancel, GET /{id} (requester or approver). Paged lists use Paging.of.
5. Tests: parameter validation errors; duplicate name; self-approval refused; double approve -> 409; USER cannot see others' requests.

**Acceptance criteria**

- Given a template requiring approval, when a user submits a valid request, then it is PENDING_APPROVAL and approvers are notified.
- Given an approver, when they approve, then the request becomes APPROVED and the requester is notified; given the requester tries to approve their own request, then it is refused.
- Given an invalid parameter, when submitted, then 400 names the parameter.

**Tests**

- ProvisioningRequestServiceTest
- ProvisioningRequestControllerTest

**Notes:** Assumption: a dedicated approval flow is used rather than E25 approval policies, which govern starts and extensions; it reuses the same notification channels (E24 chat fan-out picks up the new notification types if configured).

#### E32-T04 — Provision approved requests, create and register the environment, and grant the requester access

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** high · **Sources:** G20 · **Depends on:** E32-T03, E06-T01, E15-T01, E02-T03

G20: after approval the environment must be created in AWS and appear in the platform ready to use, owned by the requester.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/ProvisioningExecutor.java` (new) — Start provisioning, poll, finalise
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ProvisioningScheduler.java` (new) — Every minute via runLocked: start APPROVED, poll PROVISIONING
- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — Make discoverEnvironmentVms(Environment) (L234) public for a single environment
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — createEnvironment used with metadata region/account and provisioning source
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — Use grantAccess (L303) to give the requester ADMIN-level access on the new environment
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — PROVISIONING_STARTED, PROVISIONING_SUCCEEDED, PROVISIONING_FAILED
- `src/test/java/com/tcgdigital/vmcontrol/service/provisioning/ProvisioningExecutorTest.java` (new) — Success, failure, timeout

**Steps**

1. ProvisioningScheduler (provisioning.enabled gate, ScheduledJobLockService.runLocked("provisioning", ...)): for APPROVED requests call engine.provision, store engine_record_id, status PROVISIONING (conditional update so two nodes never provision twice; the idempotency token protects AWS too).
2. For PROVISIONING requests poll; on FAILED set FAILED with message, audit, notify requester; on timeout (provisioning.timeout-minutes, default 60) set FAILED with 'timed out' and leave the AWS product for an admin to inspect.
3. On SUCCEEDED in one transaction: create the Environment through EnvironmentService.createEnvironment (name, displayName, description 'Provisioned from <template>', metadata {"region":..., "provisioningRequestId":...}, awsAccountRef from the template); set environment_id and engine_resource_id; destroy_at = now + ttlHours; status READY. After commit: run VmDiscoveryService.discoverEnvironmentVms(env) to register instances tagged tcg:environment=<name> (make it public; it already uses the tag key from vm.discovery.tag.environment-key); grant the requester environment ADMIN access via EnvironmentAccessService.grantAccess with expiry = destroy_at; open a lease if E15 leases are enabled for the template's environments (LeaseService from E15-T01).
4. Audit each stage and notify the requester with a link to #/environments/<id> (E02-T03 route).
5. Tests (mock engine): success creates environment, access grant and destroy_at; failure path; second scheduler pass does not re-provision; discovery invoked after commit.

**Acceptance criteria**

- Given an approved request, when the scheduler runs and Service Catalog succeeds, then an environment named as requested exists with its tagged VMs registered, the requester has admin access until destroy_at, and they are notified.
- Given provisioning fails, when polled, then the request is FAILED with AWS's message and no environment is created.

**Tests**

- ProvisioningExecutorTest
- Integration test (AbstractIntegrationTest) for the success transaction

**Notes:** Creates AWS resources; provisioning.enabled defaults false. Requires vm.discovery tag key to match the Service Catalog product's tagging.

#### E32-T05 — Destroy provisioned environments at their TTL with warnings, extension through leases and manual destroy

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** high · **Sources:** G20 · **Depends on:** E32-T04, E15-T04, E15-T05

G20: provisioned environments need destroy/TTL integration with E15 leases so temporary environments do not live forever.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/ProvisioningDestroyService.java` (new) — Warn, extend, destroy, finalise
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/ProvisioningScheduler.java` (modify) — Destroy pass and warning pass
- `src/main/java/com/tcgdigital/vmcontrol/controller/ProvisioningRequestController.java` (modify) — POST /{id}/extend-ttl, POST /{id}/destroy
- `src/main/java/com/tcgdigital/vmcontrol/service/LeaseService.java` (modify) — Lease extension of a provisioned environment also extends destroy_at (capped by template max_ttl_hours)
- `src/main/java/com/tcgdigital/vmcontrol/model/NotificationType.java` (modify) — ENVIRONMENT_DESTROY_WARNING, ENVIRONMENT_DESTROYED
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — PROVISIONING_DESTROY_REQUESTED, PROVISIONING_DESTROYED, PROVISIONING_TTL_EXTENDED
- `src/test/java/com/tcgdigital/vmcontrol/service/provisioning/ProvisioningDestroyServiceTest.java` (new) — Warnings, extension cap, destroy flow

**Steps**

1. Warning pass: READY requests with destroy_at within 24h and within 1h get one notification each (store warned stages in a small JSON column or two booleans added by a migration, next free V number).
2. extendTtl(id, hours, userId): requester or environment admin; new destroy_at <= created_at + max_ttl_hours, else 400 (ADMIN may exceed). LeaseService extension hook calls the same method so extending the lease in the UI also extends the TTL.
3. Destroy pass (provisioning.destroy.enabled=false by default, separate from provisioning.enabled): for READY requests past destroy_at with no active operation: stop the environment through the normal operation pipeline first if anything is running (reuse E15-T05's stop path), then engine.destroy, status DESTROYING; poll until SUCCEEDED -> deactivate the environment (EnvironmentService.deactivateEnvironment), revoke grants, status DESTROYED, notify; FAILED -> keep DESTROYING with error and notify admins.
4. Manual destroy endpoint: requester or ADMIN with confirm=true body field; same flow immediately.
5. Tests: warnings sent once; extension capped; destroy deactivates environment only after the engine reports success; destroy flag off -> only warnings.

**Acceptance criteria**

- Given a provisioned environment 1 hour before its TTL, when the job runs, then the owner gets a destroy warning with an extend link.
- Given the owner extends the lease, when within the template maximum, then destroy_at moves accordingly.
- Given the TTL passes and destroy is enabled, when the job runs, then the product is terminated, the environment deactivated and owners notified.

**Tests**

- ProvisioningDestroyServiceTest

**Notes:** Deletes AWS resources; provisioning.destroy.enabled defaults false. Pilot with a disposable template first.

#### E32-T06 — Add a Terraform engine using the HCP Terraform / Terraform Enterprise runs API

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G20 · **Depends on:** E32-T05

G20: teams that standardise on Terraform should be able to publish templates as Terraform workspaces instead of Service Catalog products.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/TerraformCloudEngine.java` (new) — Create workspace from template, set variables, queue apply/destroy runs, poll, read outputs
- `src/main/java/com/tcgdigital/vmcontrol/config/TerraformCloudConfig.java` (new) — RestClient with base URL and bearer token from env
- `src/main/resources/application.properties` (modify) — provisioning.terraform.enabled=false, provisioning.terraform.base-url=${TFC_BASE_URL:https://app.terraform.io}, provisioning.terraform.token=${TFC_TOKEN:}, provisioning.terraform.organization=${TFC_ORGANIZATION:}
- `.env.example` (modify) — TFC_* placeholders
- `src/test/java/com/tcgdigital/vmcontrol/service/provisioning/TerraformCloudEngineTest.java` (new) — MockRestServiceServer request/response mapping

**Steps**

1. engine_ref for TERRAFORM: {workspacePrefix, vcsRepoIdentifier or noCodeModuleId, terraformVersion, agentPoolId?}. provision: create workspace '<prefix>-<environmentName>' (idempotent: reuse when it exists with tag provisioning-request=<id>), set workspace variables from parameters plus environment_name and tags variables, queue a run (POST /api/v2/runs with auto-apply true) and return the run id.
2. poll: GET /api/v2/runs/{id} -> applied = SUCCEEDED; errored/canceled/discarded = FAILED; others IN_PROGRESS. resourceId = workspace id. outputs: current state version outputs.
3. destroy: POST /api/v2/runs with is-destroy=true; after success delete the workspace (DELETE /api/v2/organizations/{org}/workspaces/{name}) only when provisioning.terraform.delete-workspace=true.
4. Bean is only active when provisioning.terraform.enabled=true (@ConditionalOnProperty); token never logged.
5. Tests with MockRestServiceServer for each call and status mapping.

**Acceptance criteria**

- Given a Terraform template and the engine enabled, when a request is provisioned, then a workspace with the request variables is created and an apply run queued.
- Given the run errors, when polled, then the request becomes FAILED with the run status.

**Tests**

- TerraformCloudEngineTest

**Notes:** Assumption: Terraform is executed by HCP Terraform/TFE, not inside this app (no terraform binary on the app host).

#### E32-T07 — Build the template catalogue, request form and 'My requests' UI

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G20 · **Depends on:** E32-T06, E02-T01

G20: users need a catalogue UI to browse templates, request an environment and follow its status.

**Files**

- `src/main/resources/static/js/features/templates.js` (new) — Catalogue cards, request form generated from parameter_schema, my requests list with status and TTL
- `src/main/resources/static/js/core/router.js` (modify) — Route 'templates': '#/templates'
- `src/main/resources/static/js/ui/sidebar.js` (modify) — 'New environment' nav item when provisioning is enabled
- `src/main/resources/static/index.html` (modify) — Container/script include
- `build-frontend.ps1` (modify) — Bundle order

**Steps**

1. Catalogue: cards with name, description, region, estimated monthly cost, default TTL, approval badge. All text escaped (Utils.html).
2. Request form generated from parameter_schema: string -> input with pattern, number -> number input, enum -> select; required markers; environment name and TTL fields; client-side validation mirrors server; server 400 messages shown per field.
3. My requests table: status chip, created, approver, destroy_at countdown, link to the environment when READY, Cancel (pending), Extend TTL and Destroy (with typed-name confirmation) when READY. Poll page-scoped every 15s while any request is PROVISIONING/DESTROYING.
4. Hide the nav item when GET /api/v1/templates returns 404. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given provisioning is enabled, when a USER opens #/templates, then they see allowed templates and can submit a request.
- Given their request is provisioning, when they watch My requests, then the status updates without reload and links to the environment when ready.
- Given Destroy is clicked, when the environment name is not typed exactly, then the action is not sent.

**Tests**

- Manual UI checklist incl. 375px width

#### E32-T08 — Build the admin template editor and the provisioning approvals queue

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G20 · **Depends on:** E32-T07

G20: admins curate the catalogue and approvers review requests.

**Files**

- `src/main/resources/static/js/features/templates.js` (modify) — Admin tab: template list/editor with parameter-schema builder; Approvals tab
- `src/main/java/com/tcgdigital/vmcontrol/controller/TemplateController.java` (modify) — GET /api/v1/templates/all (ADMIN, includes retired)
- `docs/provisioning.md` (new) — Publishing a Service Catalog product or Terraform workspace template, tagging convention, flags, IAM

**Steps**

1. Template editor modal: engine select with engine-specific fields, parameter rows (key, label, type, required, default, options, pattern) with add/remove, TTL defaults, approval settings, allowed roles; save via POST/PUT; retire button.
2. Approvals tab for approvers: pending requests with template, requester, parameters (rendered as text), justification, estimated cost; Approve / Reject (notes required) with confirmation.
3. docs/provisioning.md: product must tag resources with tcg:environment=<environment_name> (parameter EnvironmentName) for auto-registration; launch constraint role; flags provisioning.enabled, provisioning.destroy.enabled, provisioning.terraform.enabled.
4. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given an ADMIN, when they build a template with an enum parameter, then the request form shows a select with those options.
- Given an approver, when they reject without notes, then the UI requires notes before sending.

**Tests**

- TemplateControllerTest /all security
- Manual UI checklist

## E33

### RDS & other services — Later

RDS DB instances and Aurora clusters are registered, scheduled, started and stopped like VMs through a new AWS_RDS provider, stay stopped despite AWS's 7-day auto-start, and are included in cost, tagging and idle detection.

Sources: G21 · 6 tasks · 9 days

#### E33-T01 — Add the AWS_RDS cloud provider with start, stop and status for DB instances and Aurora clusters

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G21 · **Depends on:** E28-T05, E05-T05

G21: non-prod databases often cost as much as their app servers but cannot be stopped from the platform; CloudProviderFactory only knows AWS and AWS_EKS (CloudProvider.java, CloudProviderFactory L36-38).

**Files**

- `build.gradle` (modify) — implementation 'software.amazon.awssdk:rds'
- `src/main/java/com/tcgdigital/vmcontrol/model/CloudProvider.java` (modify) — Add AWS_RDS
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsClientFactory.java` (modify) — rds(accountRef, region)
- `src/main/java/com/tcgdigital/vmcontrol/service/RdsCloudProviderService.java` (new) — CloudProviderService implementation
- `src/main/java/com/tcgdigital/vmcontrol/service/CloudProviderFactory.java` (modify) — Error message lists supported providers dynamically
- `src/main/resources/application.properties` (modify) — rds.enabled=false, rds.poll-interval-ms=20000, rds.operation-timeout-ms=1800000
- `src/test/java/com/tcgdigital/vmcontrol/service/RdsCloudProviderServiceTest.java` (new) — Status mapping, start/stop calls, cluster vs instance

**Steps**

1. Add the rds SDK module and AwsClientFactory.rds(accountRef, region) (same caching as other clients).
2. Add AWS_RDS to CloudProvider. vm.provider is a plain VARCHAR (V7 comment), so no migration is needed; grep for switch statements over CloudProvider and add a branch where required.
3. RdsCloudProviderService implements CloudProviderService (getProvider AWS_RDS; isAvailable = rds.enabled && factory.isConfigured()). providerVmId convention: 'db:<DBInstanceIdentifier>' or 'cluster:<DBClusterIdentifier>'.
4. startVm: StartDBInstance / StartDBCluster; poll DescribeDBInstances/DescribeDBClusters every rds.poll-interval-ms until 'available' (RUNNING) or timeout (failure). Report progress through OperationProgressListener with stage labels. stopVm: StopDBInstance / StopDBCluster, poll until 'stopped'. Treat InvalidDBInstanceState when already in the target state as success; a DB that is a read replica, Multi-AZ SQL Server, or member of an Aurora cluster (instance-level stop not allowed) returns a failure with a clear message.
5. getVmStatus mapping: available -> RUNNING; stopped -> STOPPED; starting -> STARTING; stopping -> STOPPING; deleting/deleted -> TERMINATED; backing-up, modifying, upgrading, maintenance, configuring-* -> RUNNING with drift-safe handling; anything else -> UNKNOWN. getVmName returns the identifier. getVmStatusBatch: one DescribeDBInstances (filter db-instance-id, up to 100 ids) and one DescribeDBClusters call per target.
6. Tests with a mocked RdsClient: status mapping table; instance vs cluster API chosen from the prefix; already-stopped is success; timeout -> failure; isAvailable false when flag off.

**Acceptance criteria**

- Given rds.enabled=true and a registered RDS instance, when a user stops its environment, then StopDBInstance is called and the step completes when the DB reports stopped.
- Given an Aurora cluster VM, when started, then StartDBCluster is used.
- Given rds.enabled=false, when an AWS_RDS VM is operated, then the provider reports unavailable and nothing is called.

**Tests**

- RdsCloudProviderServiceTest

**Notes:** Assumption: RDS resources are modelled as Vm rows with provider AWS_RDS inside existing environments (a DB can sit in the same environment as its app servers and be ordered with depends_on). Environment.serviceType is unchanged. IAM: rds:StartDBInstance, StopDBInstance, StartDBCluster, StopDBCluster, Describe*.

#### E33-T02 — Browse and register RDS databases in the VM registry

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G21 · **Depends on:** E33-T01, E28-T10

G21: admins need to pick RDS instances/clusters from an account and region and register them into a group, like EC2 instances.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/RdsController.java` (new) — GET /api/v1/rds/databases?accountRef&region (ADMIN, ENV_ADMIN)
- `src/main/java/com/tcgdigital/vmcontrol/dto/RdsDatabaseInfoDTO.java` (new) — providerVmId, identifier, kind (INSTANCE|CLUSTER), engine, instanceClass, status, multiAz, stoppable, registered
- `src/main/java/com/tcgdigital/vmcontrol/service/RdsCloudProviderService.java` (modify) — listDatabases(target)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — Validate AWS_RDS providerVmId prefix on register
- `src/main/resources/static/js/features/vm-registry.js` (modify) — 'EC2 / RDS' source toggle in the fetch panel (L731-800); RDS icon in the VM table
- `src/main/resources/static/js/features/environments.js` (modify) — Database icon and engine label for AWS_RDS VMs
- `src/test/java/com/tcgdigital/vmcontrol/controller/RdsControllerTest.java` (new) — Security and mapping

**Steps**

1. listDatabases: DescribeDBInstances (paginated) excluding Aurora cluster members (dbClusterIdentifier != null) plus DescribeDBClusters; mark stoppable=false for read replicas and other unsupported cases with a reason; registered = vmRepository.existsByProviderAndProviderVmId(AWS_RDS, id).
2. RdsController returns 404 when rds.enabled=false.
3. VmService.registerVm: for AWS_RDS require providerVmId to start with 'db:' or 'cluster:' (400 otherwise).
4. vm-registry.js: a toggle switches the fetch list between EC2 and RDS; selecting an RDS row pre-fills the register form with provider AWS_RDS, region and providerVmId. Escape all values. Run .\build-frontend.ps1.
5. Tests: VIEWER/USER -> 403; cluster members omitted; flag off -> 404.

**Acceptance criteria**

- Given rds.enabled, when an ENV_ADMIN fetches RDS databases for a region, then instances and clusters are listed with stoppable flags and they can register one into a group.
- Given an Aurora cluster member instance, when listed, then it is not offered separately.

**Tests**

- RdsControllerTest
- VmService register validation test

#### E33-T03 — Collect RDS inventory and CloudWatch metrics including database connections

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G21 · **Depends on:** E33-T02, E28-T06

G21: cost and idle need the DB instance class, storage and utilisation; the metrics provider only queries AWS/EC2 (AwsCloudMetricsProviderService NAMESPACE L27).

**Files**

- `src/main/resources/db/migration/V<next>__add_db_connections_metric.sql` (new) — db_connections DECIMAL(12,3) NULL on vm_metric_sample and vm_metric_sample_archive; engine VARCHAR(40), multi_az BOOLEAN on vm_inventory_snapshot
- `src/main/java/com/tcgdigital/vmcontrol/service/RdsCloudInventoryProviderService.java` (new) — CloudInventoryProviderService for AWS_RDS: instance class, vCPU/memory from reference, allocated storage, engine, multiAZ
- `src/main/java/com/tcgdigital/vmcontrol/service/RdsCloudMetricsProviderService.java` (new) — CloudMetricsProviderService for AWS_RDS: CPUUtilization, DatabaseConnections, FreeableMemory, Read/WriteIOPS (AWS/RDS)
- `src/main/java/com/tcgdigital/vmcontrol/service/CloudMetricsProviderService.java` (modify) — VmMetricData.dbConnections
- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricsService.java` (modify) — Persist dbConnections
- `src/main/java/com/tcgdigital/vmcontrol/model/VmMetricSample.java` (modify) — dbConnections
- `src/main/java/com/tcgdigital/vmcontrol/model/VmMetricSampleArchive.java` (modify) — dbConnections
- `src/main/java/com/tcgdigital/vmcontrol/model/VmInventorySnapshot.java` (modify) — engine, multiAz
- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricsArchiveService.java` (modify) — Copy dbConnections into the archive
- `src/test/java/com/tcgdigital/vmcontrol/service/RdsCloudMetricsProviderServiceTest.java` (new) — Query building and mapping

**Steps**

1. Migration as listed.
2. RdsCloudInventoryProviderService (registered automatically in CloudInventoryProviderFactory by getProvider AWS_RDS): DescribeDBInstances/Clusters for the ids; instanceType = DB instance class (e.g. db.t4g.medium; for clusters, the writer's class), totalStorageGib = allocatedStorage (Aurora: null), engine, multiAz.
3. RdsCloudMetricsProviderService: GetMetricData in namespace AWS/RDS with dimension DBInstanceIdentifier (or DBClusterIdentifier for clusters), metrics CPUUtilization (Average), DatabaseConnections (Maximum), ReadIOPS/WriteIOPS mapped to diskRead/diskWrite fields; same period and batching as the EC2 provider.
4. VmMetricData.dbConnections persisted to vm_metric_sample.db_connections; archive copies it.
5. Tests: metric query ids and dimensions; mapping of DatabaseConnections.

**Acceptance criteria**

- Given a registered RDS instance, when inventory and metrics sync run, then its instance class, storage, engine and CPU/connections samples are stored.
- Given rds.enabled=false, when the sync jobs run, then no RDS calls are made.

**Tests**

- RdsCloudMetricsProviderServiceTest
- RdsCloudInventoryProviderService test

#### E33-T04 — Keep stopped RDS databases stopped after AWS's 7-day automatic restart

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G21 · **Depends on:** E33-T03, E06-T01, E17-T01

G21: AWS automatically starts a stopped RDS instance or Aurora cluster after 7 days; without handling, scheduled-off databases silently run and cost money.

**Files**

- `src/main/resources/db/migration/V<next>__add_vm_intended_state.sql` (new) — vm.intended_stopped_since TIMESTAMP NULL
- `src/main/java/com/tcgdigital/vmcontrol/model/Vm.java` (modify) — intendedStoppedSince
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — Set intendedStoppedSince on successful stop of AWS_RDS VMs; clear on start
- `src/main/java/com/tcgdigital/vmcontrol/service/RdsKeepStoppedService.java` (new) — Detect auto-start and re-stop; upcoming auto-start warnings
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/RdsKeepStoppedScheduler.java` (new) — Every 15 minutes via runLocked
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — RDS_AUTO_START_DETECTED, RDS_RESTOPPED
- `src/main/resources/application.properties` (modify) — rds.keep-stopped.enabled=false
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmDTO.java` (modify) — awsAutoStartAt (intendedStoppedSince + 7 days) for AWS_RDS
- `src/test/java/com/tcgdigital/vmcontrol/service/RdsKeepStoppedServiceTest.java` (new) — Re-stop logic

**Steps**

1. When an operation (user, schedule, lease, idle) stops an AWS_RDS VM successfully, set intendedStoppedSince = now (if null); any start through the platform clears it.
2. RdsKeepStoppedService.run(): for AWS_RDS VMs with intendedStoppedSince != null whose live status (provider getVmStatusBatch) is STARTING or RUNNING and with no platform operation running for the environment: record VmStateHistory changeSource 'rds_auto_start', audit RDS_AUTO_START_DETECTED, then once RUNNING call stopVm (AWS rejects stop while 'starting'; retry on the next pass), audit RDS_RESTOPPED. intendedStoppedSince stays set; AWS's 7-day clock restarts with the new stop.
3. Skip when an automation rule or lease is about to start the environment within the next 60 minutes (ask AutomationRuleService for next run if E19-T04 exposes it; otherwise skip this optimisation).
4. VmDTO.awsAutoStartAt shown on the VM row as 'AWS will restart on <date>; the platform will stop it again' when keep-stopped is enabled, else a warning.
5. Tests: auto-started DB with intent -> restopped once RUNNING; DB started by a user through the platform -> untouched; STARTING -> waits; flag off -> only detection/audit.

**Acceptance criteria**

- Given an RDS instance stopped by a schedule 7 days ago, when AWS restarts it, then within 15 minutes of reaching 'available' the platform stops it again and audits both events.
- Given a user started the DB through the platform, when it is running, then it is not stopped.
- Given keep-stopped is disabled, when an auto-start happens, then it is recorded and owners are warned but nothing is stopped.

**Tests**

- RdsKeepStoppedServiceTest

**Notes:** rds.keep-stopped.enabled defaults false (it stops AWS resources). Uses E17-T01's operation-to-history link to tell platform starts from AWS auto-starts.

#### E33-T05 — Price RDS in cost estimates and tag RDS resources for Cost Explorer actuals

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G21 · **Depends on:** E33-T04, E30-T02

G21: RDS must be included in cost; EstimatedCostProvider only prices EC2 instance types and EKS nodes (L93-101) and TagReconciliationService only tags EC2 and EKS (L101-136).

**Files**

- `src/main/resources/pricing/instance-pricing.json` (modify) — rdsPricing entries (engine, instanceClass, region, deployment SINGLE_AZ|MULTI_AZ, hourlyRate) and rdsStorageGbMonthRate
- `scripts/generate-instance-pricing.ps1` (modify) — Also pull AmazonRDS prices for common classes/engines (script from E30-T02)
- `src/main/java/com/tcgdigital/vmcontrol/service/PricingReferenceService.java` (modify) — lookupRdsHourlyRate(engine, instanceClass, region, multiAz); getRdsStorageGbMonthRate()
- `src/main/java/com/tcgdigital/vmcontrol/service/EstimatedCostProvider.java` (modify) — AWS_RDS branch: compute hours x RDS rate, storage always billed
- `src/main/java/com/tcgdigital/vmcontrol/service/RdsCloudProviderService.java` (modify) — tagResource(target, providerVmId, tags) via AddTagsToResource (ARN from describe)
- `src/main/java/com/tcgdigital/vmcontrol/service/TagReconciliationService.java` (modify) — Tag AWS_RDS VMs with the cost-allocation tags
- `src/test/java/com/tcgdigital/vmcontrol/service/EstimatedCostProviderTest.java` (modify) — RDS cost cases
- `src/test/java/com/tcgdigital/vmcontrol/service/TagReconciliationServiceTest.java` (modify) — RDS tagging

**Steps**

1. Extend the pricing JSON with rdsPricing for db.t3/t4g, db.m5/m6g/m6i, db.r5/r6g/r6i classes, engines mysql, postgres, aurora-mysql, aurora-postgresql, in the reference regions; Single-AZ and Multi-AZ; rdsStorageGbMonthRate (gp3).
2. PricingReferenceService loads and looks them up with the same exact-region then any-region fallback (flagged approximate per E08-T07).
3. EstimatedCostProvider: for AWS_RDS use inventory engine/instance class/multiAz; cost = hourly x running hours + storage at the RDS rate for the whole window (storage is billed while stopped, unlike compute).
4. TagReconciliationService: include AWS_RDS VMs (behind cost.tagging.enabled) using RdsCloudProviderService.tagResource; Cost Explorer actuals then include RDS automatically via the environment tag.
5. Tests: running vs stopped cost; Multi-AZ uses the Multi-AZ rate; tagging call made with ARN.

**Acceptance criteria**

- Given a db.t4g.medium PostgreSQL instance stopped half the window, when costs are estimated, then compute is charged for half the hours and storage for the full window.
- Given cost tagging is enabled, when reconciliation runs, then RDS resources carry the environment and team tags.

**Tests**

- EstimatedCostProviderTest
- TagReconciliationServiceTest
- PricingReferenceServiceTest RDS lookups

**Notes:** Savings ledger (E17) picks RDS up automatically if it reads hourly rates through CostDataProvider; verify and add an RDS case to SavingsLedgerServiceTest.

#### E33-T06 — Include RDS in idle detection and the idle-waste report

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** G21 · **Depends on:** E33-T05, E16-T02, E16-T03

G21: an RDS database with no connections is idle; idle auto-stop (E16) and the idle-waste list should cover it.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/idle/IdleEvaluator.java` (modify) — AWS_RDS rule: max DatabaseConnections == 0 and CPU below threshold over the window
- `src/main/java/com/tcgdigital/vmcontrol/service/VmMetricsService.java` (modify) — Idle summary for AWS_RDS uses connections instead of network bytes
- `src/main/resources/application.properties` (modify) — rds.idle.max-connections=0, rds.idle.cpu-threshold-percent=5
- `src/test/java/com/tcgdigital/vmcontrol/service/idle/IdleEvaluatorTest.java` (modify) — RDS idle and non-idle cases

**Steps**

1. IdleEvaluator (E16-T02): branch on provider AWS_RDS - idle when every sample in the rule window has dbConnections <= rds.idle.max-connections and CPU below rds.idle.cpu-threshold-percent; missing dbConnections -> not idle (unknown).
2. VmMetricsService idle summary (vm_idle_summary) uses the same RDS rule so CostEstimationService.getIdleWaste lists idle databases.
3. Idle auto-stop (E16-T03) then stops idle databases in dependency order with the rest of the environment; nothing else changes.
4. Tests: 0 connections + low CPU -> idle; 1 connection -> not idle; missing data -> unknown.

**Acceptance criteria**

- Given an RDS database with zero connections for the whole idle window, when idle evaluation runs, then it is idle and appears in idle waste with its monthly cost.
- Given an application holds one connection, when evaluated, then the database is not idle.

**Tests**

- IdleEvaluatorTest RDS cases

**Notes:** Assumption: 'other services' beyond RDS (ECS services, ASGs, OpenSearch, Redshift pause) follow the same provider pattern and are left for a follow-up epic once RDS proves the model.

## E34

### API tokens, Terraform provider & webhooks — Later

Teams can automate the platform with scoped personal and service API tokens, browse the OpenAPI spec when authenticated, receive signed outbound webhooks for key events, and have a reviewed plan and API surface for a Terraform provider.

Sources: G22 · 8 tasks · 12 days

#### E34-T01 — Add hashed, scoped personal API tokens with a self-service API

- [ ] Done · **Type:** security · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G22 · **Depends on:** E03-T09, E00-T02, E00-T05

G22: the API is session-only (EntraidSecurityConfig uses oauth2Login with session cookies), so scripts and CI cannot call it; tokens must be scoped, expiring and stored only as hashes.

**Files**

- `src/main/resources/db/migration/V<next>__add_api_token.sql` (new) — api_token table
- `src/main/java/com/tcgdigital/vmcontrol/model/ApiToken.java` (new) — Entity
- `src/main/java/com/tcgdigital/vmcontrol/model/ApiScope.java` (new) — enum with string values: environments:read, operations:write, schedules:read, schedules:write, cost:read, audit:read, access:write, admin
- `src/main/java/com/tcgdigital/vmcontrol/repository/ApiTokenRepository.java` (new) — findByTokenPrefix, findByUserUserIdAndRevokedAtIsNull
- `src/main/java/com/tcgdigital/vmcontrol/service/ApiTokenService.java` (new) — create (returns plaintext once), list, revoke, verify(raw)
- `src/main/java/com/tcgdigital/vmcontrol/controller/ApiTokenController.java` (new) — /api/v1/me/api-tokens GET/POST/DELETE
- `src/main/java/com/tcgdigital/vmcontrol/dto/CreateApiTokenDTO.java` (new) — name, scopes, expiresInDays
- `src/main/java/com/tcgdigital/vmcontrol/dto/ApiTokenDTO.java` (new) — id, name, prefix, scopes, expiresAt, lastUsedAt, createdAt (never the secret or hash)
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — API_TOKEN_CREATED, API_TOKEN_REVOKED
- `src/main/resources/application.properties` (modify) — api-tokens.enabled=false, api-tokens.max-lifetime-days=365, api-tokens.pepper=${API_TOKEN_PEPPER:}
- `.env.example` (modify) — API_TOKEN_PEPPER placeholder
- `src/test/java/com/tcgdigital/vmcontrol/service/ApiTokenServiceTest.java` (new) — Format, hashing, verify, expiry, revoke
- `src/test/java/com/tcgdigital/vmcontrol/controller/ApiTokenControllerTest.java` (new) — Only own tokens; plaintext returned once

**Steps**

1. Migration: api_token (token_id VARCHAR(36) PK, user_id FK app_user, name VARCHAR(100), token_prefix CHAR(8) UNIQUE, token_hash CHAR(64), scopes TEXT, expires_at TIMESTAMP NOT NULL, last_used_at TIMESTAMP NULL, last_used_ip VARCHAR(64) NULL, revoked_at TIMESTAMP NULL, revoked_by VARCHAR(36) NULL, created_at TIMESTAMP).
2. Token format 'vmc_<prefix8>_<secret43>': prefix and secret from SecureRandom (base62/URL-safe base64). Store HMAC-SHA256(pepper, full token) hex; fail startup when api-tokens.enabled=true and the pepper is blank or shorter than 32 bytes.
3. ApiTokenService.create(userId, dto): name 1-100 chars; scopes non-empty and a subset of what the user's role allows (admin scope only for ADMIN; access:write only for ENV_ADMIN/ADMIN); expiresInDays 1..max-lifetime-days (required, no non-expiring tokens); max 20 active tokens per user. Returns the plaintext once. Audit (never log the token).
4. verify(raw): parse prefix, findByTokenPrefix, MessageDigest.isEqual on the HMAC, reject revoked/expired/user inactive; return the token entity.
5. Controller (any authenticated session user; tokens cannot create tokens - reject when the current authentication is a token authentication): GET list own, POST create -> 201 {token: plaintext, ...dto}, DELETE /{id} revoke own (404 for others). 404 for all when api-tokens.enabled=false.
6. Tests: plaintext never stored; verify succeeds then fails after revoke/expiry; USER cannot request admin scope; cannot revoke another user's token.

**Acceptance criteria**

- Given tokens are enabled, when a user creates a token with environments:read for 30 days, then the response shows the token once and later listings show only its prefix.
- Given a token is revoked or expired, when it is verified, then verification fails.
- Given a USER, when they request the admin scope, then the request is rejected with 400.

**Tests**

- ApiTokenServiceTest
- ApiTokenControllerTest (SecuredWebTestBase)

**Notes:** api-tokens.enabled defaults false.

#### E34-T02 — Authenticate API requests with bearer tokens and enforce scopes, statelessly and rate-limited

- [ ] Done · **Type:** security · **Estimate:** 2 d · **Risk:** high · **Sources:** G22 · **Depends on:** E34-T01, E03-T10

G22: tokens must work alongside the Entra session login without sessions or CSRF cookies, and each endpoint must require the right scope.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/security/ApiTokenAuthenticationFilter.java` (new) — Bearer vmc_ token -> ApiTokenAuthentication
- `src/main/java/com/tcgdigital/vmcontrol/security/ApiTokenAuthentication.java` (new) — AbstractAuthenticationToken with principal = userId String, role authorities + SCOPE_ authorities, tokenId
- `src/main/java/com/tcgdigital/vmcontrol/security/ApiScopeRegistry.java` (new) — Maps HTTP method + path pattern to required ApiScope
- `src/main/java/com/tcgdigital/vmcontrol/security/ApiScopeAuthorizationFilter.java` (new) — 403 when a token lacks the scope or the endpoint is unmapped
- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — Register filters before CurrentUserRefreshFilter; ignore CSRF for bearer requests; no session for token requests
- `src/main/java/com/tcgdigital/vmcontrol/security/CurrentUserRefreshFilter.java` (modify) — Skip ApiTokenAuthentication (already freshly loaded)
- `src/main/java/com/tcgdigital/vmcontrol/service/ApiTokenService.java` (modify) — touch(tokenId, ip) at most once per minute; per-token rate limiter
- `src/test/java/com/tcgdigital/vmcontrol/security/ApiTokenAuthenticationIntegrationTest.java` (new) — End-to-end bearer calls

**Steps**

1. ApiTokenAuthenticationFilter (OncePerRequestFilter, only /api/**, only when api-tokens.enabled): if Authorization starts with 'Bearer vmc_', verify via ApiTokenService; on failure write 401 JSON (same body shape as E00-T05) and stop; on success set SecurityContextHolder with ApiTokenAuthentication (authorities = RoleAuthorities.forUser(user) from E03-T09 plus 'SCOPE_<scope>'), principal = user id String (UserService.getCurrentUser already resolves a String principal, L372). Do not create or touch the HTTP session (use RequestAttributeSecurityContextRepository for these requests).
2. CSRF: csrf.ignoringRequestMatchers(request -> header starts with 'Bearer vmc_'), so cookie sessions stay protected (E03-T10).
3. ApiScopeRegistry: explicit table, e.g. GET /api/v1/environments/** -> environments:read; POST /api/v1/environments/*/operations/** -> operations:write; GET/POST/PUT/PATCH/DELETE /api/v1/automation-rules/** -> schedules:read / schedules:write; GET /api/v1/cost-management/** -> cost:read; GET /api/v1/audit/** -> audit:read; access grants/requests writes -> access:write; everything under admin-only controllers -> admin. Unmapped endpoints are denied to tokens (default deny). Session users are unaffected.
4. ApiScopeAuthorizationFilter runs after authentication; method security (@PreAuthorize roles) and SecurityService environment checks still apply, so a token never exceeds its owner's rights.
5. Rate limit: in-memory token bucket per token id (api-tokens.rate-limit-per-minute=600) -> 429 with Retry-After. touch() updates last_used_at/ip asynchronously at most once per minute per token.
6. Integration tests: GET environments with a read token -> 200 and no Set-Cookie; POST operation with a read-only token -> 403; with operations:write but no environment access -> 403 from SecurityService; revoked token -> 401; POST with bearer and no CSRF token -> not 403 CSRF; unmapped endpoint -> 403; rate limit -> 429.

**Acceptance criteria**

- Given a valid token with environments:read, when a script calls GET /api/v1/environments with it, then it gets the same list the owner would see and no session cookie is created.
- Given a token without operations:write, when it starts an environment, then 403 is returned.
- Given a token whose owner has been deactivated, when it is used, then 401 is returned.

**Tests**

- ApiTokenAuthenticationIntegrationTest
- ApiScopeRegistryTest (pattern table)

**Notes:** Security-critical; request a security review before enabling in production.

#### E34-T03 — Add service accounts with admin-issued service tokens

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G22 · **Depends on:** E34-T02

G22: CI pipelines and the Terraform provider need tokens not tied to a person who may leave.

**Files**

- `src/main/resources/db/migration/V<next>__add_service_accounts.sql` (new) — app_user.is_service_account BOOLEAN NOT NULL DEFAULT FALSE, service_owner_user_id NULL; api_token.kind VARCHAR(20) DEFAULT 'PERSONAL'
- `src/main/java/com/tcgdigital/vmcontrol/model/User.java` (modify) — isServiceAccount, serviceOwnerUserId
- `src/main/java/com/tcgdigital/vmcontrol/service/ServiceAccountService.java` (new) — Create/deactivate service accounts, issue/revoke their tokens
- `src/main/java/com/tcgdigital/vmcontrol/controller/ServiceAccountController.java` (new) — /api/v1/service-accounts (ADMIN)
- `src/main/java/com/tcgdigital/vmcontrol/security/CustomOAuth2UserService.java` (modify) — Refuse interactive login for service accounts
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — SERVICE_ACCOUNT_CREATED, SERVICE_ACCOUNT_DEACTIVATED
- `src/test/java/com/tcgdigital/vmcontrol/controller/ServiceAccountControllerTest.java` (new) — Security and lifecycle

**Steps**

1. Migration and User fields. Service accounts get a synthetic email '<name>@service.local' and no Entra object id.
2. ServiceAccountService.create(name, role USER|ENV_ADMIN, ownerUserId): ADMIN only; role ADMIN not allowed for service accounts. Access to environments is granted through the existing EnvironmentAccessService.grantAccess like any user.
3. Issue tokens via ApiTokenService with kind SERVICE (same scopes rules for the service account's role); list/revoke per service account.
4. Deactivating a service account revokes all its tokens.
5. CustomOAuth2UserService: if an upsert would match a service account, throw OAuth2AuthenticationException.
6. User Management lists service accounts with a badge (filter out of directory onboarding).
7. Tests: non-admin -> 403; deactivate revokes tokens; service token works with granted environment only.

**Acceptance criteria**

- Given an ADMIN, when they create a service account and grant it access to one environment, then its token can operate that environment and no other.
- Given the service account is deactivated, when its token is used, then 401 is returned.

**Tests**

- ServiceAccountControllerTest
- ServiceAccountServiceTest

#### E34-T04 — Serve the OpenAPI spec and Swagger UI to authenticated users with the bearer scheme and scope annotations

- [ ] Done · **Type:** enable · **Estimate:** 1 d · **Risk:** low · **Sources:** G22 · **Depends on:** E34-T03

G22: OpenAPI is disabled in production (application-prod.properties L74-75: springdoc.swagger-ui.enabled=false, springdoc.api-docs.enabled=false), so token users have no reference.

**Files**

- `src/main/resources/application-prod.properties` (modify) — Enable api-docs and swagger-ui when api-tokens.enabled (property placeholders)
- `src/main/java/com/tcgdigital/vmcontrol/config/OpenApiConfig.java` (new) — OpenAPI bean with bearer security scheme; OperationCustomizer adding x-required-scope from ApiScopeRegistry
- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — /v3/api-docs/** and /swagger-ui/** require authentication (session or token)
- `src/test/java/com/tcgdigital/vmcontrol/config/OpenApiConfigTest.java` (new) — Spec requires auth; contains bearer scheme and scopes

**Steps**

1. application-prod.properties: springdoc.api-docs.enabled=${API_DOCS_ENABLED:false}, springdoc.swagger-ui.enabled=${API_DOCS_ENABLED:false}; document turning it on together with tokens.
2. OpenApiConfig: components.securitySchemes 'apiToken' (http bearer, bearerFormat 'vmc token'); global security requirement; OperationCustomizer that looks up the operation's method+path in ApiScopeRegistry and adds extension x-required-scope (or 'session-only' when unmapped).
3. Security: permit nothing anonymously under /v3/api-docs and /swagger-ui; authenticated sessions and tokens with any scope may read them.
4. Tests: anonymous GET /v3/api-docs -> 401; authenticated -> 200 and JSON contains 'apiToken' and 'x-required-scope'.

**Acceptance criteria**

- Given API docs are enabled in production, when an anonymous client requests /v3/api-docs, then 401 is returned; when a token user requests it, then the spec lists each operation's required scope.

**Tests**

- OpenApiConfigTest

#### E34-T05 — API tokens tab in My Account and an admin tokens and service accounts page

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G22 · **Depends on:** E34-T04, E04-T08, E02-T09

G22: users need to create and revoke tokens; admins need oversight of every active token and service accounts.

**Files**

- `src/main/resources/static/js/features/my-account.js` (modify) — 'API tokens' tab: list, create modal (name, scopes checkboxes, expiry), one-time token reveal with copy, revoke
- `src/main/resources/static/js/features/api-access-admin.js` (new) — Admin page: all active tokens (owner, prefix, scopes, last used), revoke; service accounts list/create/tokens
- `src/main/java/com/tcgdigital/vmcontrol/controller/ApiTokenController.java` (modify) — GET /api/v1/api-tokens (ADMIN, paged), DELETE /api/v1/api-tokens/{id} (ADMIN)
- `src/main/resources/static/js/core/router.js` (modify) — Route 'api-access'
- `src/main/resources/static/js/ui/sidebar.js` (modify) — Admin nav item
- `src/main/resources/static/index.html` (modify) — Container/script
- `build-frontend.ps1` (modify) — Bundle order

**Steps**

1. My Account tab hidden when GET /api/v1/me/api-tokens returns 404. Create modal shows only scopes the role allows; the reveal dialog shows the token in a read-only input with a Copy button and the warning 'You will not see this again'; closing clears it from the DOM.
2. Admin page: paged token list with revoke (confirm), and service accounts section using E34-T03 endpoints.
3. Admin endpoints: list all tokens with owner names (Paging.of), revoke any token with audit.
4. Escape everything; page-scoped lifecycle. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given a user creates a token, when the reveal dialog closes, then the plaintext is no longer present in the page.
- Given an ADMIN, when they revoke another user's token, then it stops working and the action is audited.

**Tests**

- ApiTokenControllerTest admin endpoints
- Manual UI checklist

#### E34-T06 — Add webhook subscriptions with signed, retried and SSRF-safe delivery

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G22 · **Depends on:** E34-T05, E24-T02, E06-T01

G22: teams want outbound webhooks for key events to drive their own automation.

**Files**

- `src/main/resources/db/migration/V<next>__add_webhooks.sql` (new) — webhook_subscription and webhook_delivery tables
- `src/main/java/com/tcgdigital/vmcontrol/model/WebhookSubscription.java` (new) — url, secret_enc, event_types, environment_id NULL, is_active, created_by
- `src/main/java/com/tcgdigital/vmcontrol/model/WebhookDelivery.java` (new) — event_id, event_type, payload, attempt, next_attempt_at, status, response_code, error
- `src/main/java/com/tcgdigital/vmcontrol/repository/WebhookSubscriptionRepository.java` (new) — findActiveFor(eventType, environmentId)
- `src/main/java/com/tcgdigital/vmcontrol/repository/WebhookDeliveryRepository.java` (new) — findDue(now, limit)
- `src/main/java/com/tcgdigital/vmcontrol/service/webhook/WebhookService.java` (new) — CRUD, URL validation, test delivery
- `src/main/java/com/tcgdigital/vmcontrol/service/webhook/WebhookDeliveryService.java` (new) — Enqueue, sign, send, retry with backoff
- `src/main/java/com/tcgdigital/vmcontrol/service/webhook/SafeUrlValidator.java` (new) — https only, DNS resolve and block private/loopback/link-local/metadata ranges
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/WebhookDeliveryScheduler.java` (new) — Every 30 s via runLocked
- `src/main/java/com/tcgdigital/vmcontrol/controller/WebhookController.java` (new) — /api/v1/webhooks CRUD + POST /{id}/test + GET /{id}/deliveries
- `src/main/resources/application.properties` (modify) — webhooks.enabled=false, webhooks.timeout-ms=5000, webhooks.max-attempts=6
- `src/test/java/com/tcgdigital/vmcontrol/service/webhook/WebhookDeliveryServiceTest.java` (new) — Signature, retry schedule, give-up
- `src/test/java/com/tcgdigital/vmcontrol/service/webhook/SafeUrlValidatorTest.java` (new) — Blocked addresses

**Steps**

1. Migration: webhook_subscription (webhook_id PK, label, url TEXT, secret_enc TEXT, event_types TEXT, environment_id NULL FK, is_active, created_by, created_at, updated_at); webhook_delivery (delivery_id PK, webhook_id FK, event_id VARCHAR(36), event_type, payload MEDIUMTEXT, attempt INT, status PENDING|DELIVERED|FAILED|GAVE_UP, next_attempt_at, response_code, error TEXT, created_at, delivered_at; index (status, next_attempt_at)).
2. Secrets: generated server-side (32 random bytes, shown once), encrypted with SecretCipher from E24-T02.
3. SafeUrlValidator: https scheme only; resolve all A/AAAA records and reject loopback, site-local/private (10/8, 172.16/12, 192.168/16, fc00::/7), link-local incl. 169.254.169.254, multicast and unspecified; validate at save time and again before each send (DNS rebinding); no redirects followed.
4. Delivery: POST JSON {id, type, occurredAt, environment:{id,name}, data}; headers X-VmControl-Event, X-VmControl-Delivery, X-VmControl-Signature 't=<unix>,v1=<hex HMAC-SHA256(secret, t + "." + body)>'; 2xx = DELIVERED; otherwise retry at 1m, 5m, 30m, 2h, 12h then GAVE_UP; timeout webhooks.timeout-ms. Auto-disable a subscription after 50 consecutive GAVE_UP deliveries and notify its creator.
5. Controller: ADMIN for global (environment_id null) subscriptions; environment admins for their own environments (SecurityService). Test endpoint sends a 'ping' event synchronously. Deliveries listing paged.
6. Tests: signature verifies with the documented algorithm; retry schedule; 169.254.169.254 and 10.0.0.5 rejected; redirects not followed (MockWebServer).

**Acceptance criteria**

- Given a subscription, when a matching event is enqueued, then a signed POST is delivered and recorded as DELIVERED.
- Given the receiver returns 500, when retries run, then attempts follow the backoff schedule and stop as GAVE_UP after the last attempt.
- Given a URL resolving to a private or metadata address, when saved or delivered, then it is rejected.

**Tests**

- WebhookDeliveryServiceTest
- SafeUrlValidatorTest
- WebhookControllerTest security

**Notes:** webhooks.enabled defaults false.

#### E34-T07 — Emit platform events for key actions and add the webhooks UI

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G22 · **Depends on:** E34-T06, E15-T05, E16-T03

G22: webhooks need events from operations, leases, access, idle stops, budgets and provisioning, and a UI to manage subscriptions.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/events/PlatformEvent.java` (new) — record(id, type, occurredAt, environmentId, data Map)
- `src/main/java/com/tcgdigital/vmcontrol/service/events/PlatformEventType.java` (new) — operation.completed, operation.failed, environment.started, environment.stopped, lease.expiring, lease.expired, access.granted, access.revoked, idle.stopped, budget.threshold, provisioning.ready, provisioning.destroyed
- `src/main/java/com/tcgdigital/vmcontrol/service/webhook/WebhookEventListener.java` (new) — @TransactionalEventListener(AFTER_COMMIT) -> enqueue deliveries
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — Publish operation.completed/failed and environment.started/stopped when an execution finishes
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — Publish access.granted/revoked
- `src/main/java/com/tcgdigital/vmcontrol/service/LeaseService.java` (modify) — Publish lease.expiring/expired
- `src/main/resources/static/js/features/environments.js` (modify) — Webhooks list in the environment Integrations section (next to chat channels from E24-T02)
- `src/test/java/com/tcgdigital/vmcontrol/service/webhook/WebhookEventListenerTest.java` (new) — Events enqueue only after commit and only for matching subscriptions

**Steps**

1. Define PlatformEvent/PlatformEventType; publish with ApplicationEventPublisher from the listed services at the point where the corresponding notification is created today (and from IdleStopService, BudgetEvaluationService and ProvisioningExecutor/DestroyService if those epics are done: E16-T03, E20-T02, E32-T04/T05).
2. WebhookEventListener: on AFTER_COMMIT, find active subscriptions for the event type and environment (or global) and insert PENDING deliveries; never call HTTP in the business transaction.
3. UI: Integrations section lists webhooks with event checkboxes, masked URL host, last delivery status, Test, Disable, Delete, and a deliveries drawer. Secret shown once on creation. Run .\build-frontend.ps1.
4. docs/webhooks.md: payload schema per event, signature verification sample (Node and Python), retry policy.

**Acceptance criteria**

- Given an environment webhook for environment.stopped, when the environment stop operation completes, then exactly one delivery is enqueued after commit with the environment id and operation id.
- Given the business transaction rolls back, when events were published, then no delivery is created.

**Tests**

- WebhookEventListenerTest
- VmOperationsServiceTest asserts event publication

**Notes:** docs/webhooks.md is part of this task.

#### E34-T08 — Write the Terraform provider plan and close the API gaps it needs

- [ ] Done · **Type:** docs · **Estimate:** 1 d · **Risk:** low · **Sources:** G22 · **Depends on:** E34-T07

G22: a minimal Terraform provider (separate repository) for environments and schedules needs stable, idempotent API operations and an agreed design.

**Files**

- `docs/terraform-provider-plan.md` (new) — Provider design: resources, data sources, auth, import, API mapping, test plan, release
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — GET /api/v1/environments/by-name/{name} for data source and import
- `src/main/java/com/tcgdigital/vmcontrol/controller/AutomationRuleController.java` (modify) — Ensure GET /{ruleId} (L43) returns all fields needed for read-after-write; 404 for missing
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentControllerByNameTest.java` (new) — Lookup and access check

**Steps**

1. docs/terraform-provider-plan.md: repository 'terraform-provider-vmcontrol' (Go, terraform-plugin-framework); provider config {endpoint, token (env VMCONTROL_TOKEN)}; resources vmcontrol_environment (name, display_name, description, aws_account, region), vmcontrol_vm_group, vmcontrol_vm (registration of existing instances), vmcontrol_schedule (maps to /api/v1/automation-rules), vmcontrol_access_grant; data sources vmcontrol_environment, vmcontrol_aws_account; CRUD-to-endpoint table; import by id and by name; required scopes (environments:read, schedules:write, admin for environment creation); acceptance test plan against a dev instance; versioning and Terraform Registry publishing.
2. Identify API gaps while writing the mapping; implement only small ones in this task: GET /api/v1/environments/by-name/{name} (view rights via SecurityService.assertCanView, 404 otherwise). List any larger gaps in the doc as follow-ups.
3. Register the new endpoint in ApiScopeRegistry (environments:read).
4. Test: by-name returns the environment for a user with access, 404 for one without.

**Acceptance criteria**

- Given the plan, when reviewed, then every provider resource maps to an existing (or listed) API endpoint with its required scope.
- Given a token with environments:read, when it calls GET /api/v1/environments/by-name/{name} for an accessible environment, then the environment is returned.

**Tests**

- EnvironmentControllerByNameTest

**Notes:** The provider code itself lives in a separate repository and is out of this backlog.

## E35

### AWS forecast & FOCUS export — Later

The cost page shows AWS Cost Explorer's forecast (up to 18 months, with its prediction interval) next to the platform's own forecast, and finance can download cost data as a FOCUS 1.2-compatible CSV.

Sources: G23 · 3 tasks · 5 days

#### E35-T01 — Capture the Cost Explorer forecast (daily 3 months, monthly 18 months) for platform-managed resources

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G23 · **Depends on:** E08-T02, E08-T11, E06-T01

The platform forecast is a simple linear fit over snapshots. G23 asks to use AWS's GetCostForecast (up to 18 months) alongside it.

**Files**

- `src/main/resources/db/migration/V<next>__add_aws_cost_forecast.sql` (new) — aws_cost_forecast table (next free V number at implementation time)
- `src/main/java/com/tcgdigital/vmcontrol/model/AwsCostForecast.java` (new) — entity
- `src/main/java/com/tcgdigital/vmcontrol/repository/AwsCostForecastRepository.java` (new) — latest generation by granularity
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCostForecastService.java` (new) — GetCostForecast DAILY 90d + MONTHLY 18m, filtered to tcg:managed-by
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/AwsCostForecastScheduler.java` (new) — weekly via runLocked
- `src/main/resources/application.properties` (modify) — cost.aws-forecast.enabled=true, cron, prediction-interval=80
- `docs/aws-iam-permissions.md` (modify) — ce:GetCostForecast
- `src/test/java/com/tcgdigital/vmcontrol/service/AwsCostForecastServiceTest.java` (new) — request + parsing

**Steps**

1. Migration: aws_cost_forecast (forecast_id PK, generated_on DATE NOT NULL, granularity VARCHAR(10) NOT NULL (DAILY|MONTHLY), period_start DATE NOT NULL, period_end DATE NOT NULL, mean DECIMAL(14,2), lower_bound DECIMAL(14,2), upper_bound DECIMAL(14,2), scope VARCHAR(20) NOT NULL DEFAULT 'MANAGED', UNIQUE(generated_on, granularity, period_start, scope)).
2. AwsCostForecastService.capture() uses CostExplorerClientProvider (E08-T02). Make two calls. (a) DAILY: TimePeriod from tomorrow to +90 days, Metric UNBLENDED_COST, PredictionIntervalLevel 80. (b) MONTHLY: from the first day of next month to +18 months. Both use Filter Expression Tags{Key '<prefix>managed-by', Values ['<the value TagReconciliationService writes>'] (read it from the same constant/property)} so the forecast covers platform-managed resources. Store each forecastResultsByTime entry (meanValue, predictionIntervalLowerBound, predictionIntervalUpperBound) and the total.
3. Handle DataUnavailableException (too little history) by logging and storing nothing.
4. AwsCostForecastScheduler: @ConditionalOnProperty("cost.aws-forecast.enabled", matchIfMissing=true), weekly cron (Monday 06:00 UTC), runLocked('aws_cost_forecast', 10 min). That is 2 billed calls a week, read-only, consistent with E08-T10's defaults. Add it to the Cost Setup feature list (E08-T11) with its IAM action.

**Acceptance criteria**

- Given AWS returns 18 monthly forecast points, when capture runs, then 18 MONTHLY rows with mean and 80% bounds are stored for that generation date.
- Given DataUnavailableException, then no rows are written and a warning is logged.
- Given AWS credentials are missing, then no call is made.

**Tests**

- AwsCostForecastServiceTest (mocked client): both requests carry the tag filter, the right period and the prediction interval; results are parsed; DataUnavailableException is handled.

#### E35-T02 — Show the AWS forecast alongside the platform forecast, with an 18-month view

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G23 · **Depends on:** E35-T01, E08-T09

Admins should compare the platform's linear forecast with AWS's ML forecast and its interval (G23).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/CostForecastDTO.java` (modify) — append awsDaily[] and awsGeneratedOn
- `src/main/java/com/tcgdigital/vmcontrol/dto/AwsForecastPointDTO.java` (new) — date/periodStart, mean, lower, upper
- `src/main/java/com/tcgdigital/vmcontrol/service/CostForecastService.java` (modify) — attach the latest AWS daily forecast within the requested horizon
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /forecast/long-range (monthly 18m, ADMIN)
- `src/main/resources/static/js/features/cost-management.js` (modify) — trend chart adds the AWS mean line + interval band; '18 months' toggle
- `src/main/resources/static/js/config.js` (modify) — costManagement.forecastLongRange
- `src/main/resources/static/dist/app.js` (modify) — regenerated by build-frontend.ps1 (never hand-edit)
- `src/test/java/com/tcgdigital/vmcontrol/service/CostForecastServiceTest.java` (modify) — AWS points attached

**Steps**

1. CostForecastDTO: append List<AwsForecastPointDTO> awsDaily and Date awsGeneratedOn. Update the constructor calls in CostForecastService (two places).
2. CostForecastService.getForecast: after building the platform points, load the latest DAILY generation from AwsCostForecastRepository and include the points within [lastHistoryDate+1, +forecastDays]; return an empty list when there are none.
3. New endpoint GET /api/v1/cost-management/forecast/long-range returning the latest MONTHLY generation (18 points) plus, for comparison, the platform's monthly run-rate (the average of the last 30 days x days in the month). ADMIN only.
4. cost-management.js buildTrendOption: add an 'AWS forecast' dashed line and a translucent band between lower and upper, using a stacked-area pair (ECharts confidence-band pattern). Legend entries 'Platform forecast' and 'AWS forecast (80% interval)'. Add a segmented toggle '14 days | 18 months'; '18 months' fetches /forecast/long-range and renders a monthly bar/line chart. Dispose and re-init through chart() (E08-T09).
5. Subtitle: 'AWS forecast generated <date>; covers resources tagged tcg:managed-by'.
6. Run .\build-frontend.ps1.

**Acceptance criteria**

- Given AWS daily forecast rows exist, when the cost page loads, then the trend chart shows the platform forecast and the AWS mean with its interval band for the same days.
- Given the admin selects '18 months', then 18 monthly AWS points render, with the platform run-rate as a comparison line.
- Given no AWS forecast has been captured, then the chart shows only the platform forecast with a note.

**Tests**

- CostForecastServiceTest: AWS points are attached within the horizon and empty when absent.
- Manual chart checklist.

#### E35-T03 — Export cost data as a FOCUS 1.2-compatible CSV

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G23 · **Depends on:** E08-T07, E08-T03, E08-T08

Finance tools ingest FOCUS. G23 asks for a FOCUS export of the platform's cost data.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/FocusExportService.java` (new) — streams FOCUS rows for a month: per-VM daily estimates or per-environment daily actuals
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /export/focus?month=yyyy-MM&basis=estimated|actual (ADMIN), text/csv StreamingResponseBody
- `src/main/resources/static/js/features/cost-management.js` (modify) — 'Export FOCUS CSV' toolbar menu (month + basis)
- `src/main/resources/static/js/config.js` (modify) — costManagement.focusExport(month, basis)
- `docs/cost-management-setup.md` (modify) — FOCUS column mapping table and limitations
- `src/test/java/com/tcgdigital/vmcontrol/service/FocusExportServiceTest.java` (new) — header + row mapping + CSV escaping

**Steps**

1. Before coding, take the FOCUS 1.2 column list from the FOCUS specification (focus.finops.org) and confirm which columns are Mandatory and their allowed values. Encode the header list as a constant in FocusExportService, in spec order.
2. basis=estimated: for each day of the month (UTC, CostDayBoundary) call costDataProvider.estimateCosts(vms, dayStart, dayEnd) for the active AWS VMs, in batches of days so memory stays bounded. Emit one row per VM per day with ChargeCategory 'Usage', ChargeFrequency 'Usage-Based', BilledCost = EffectiveCost = ListCost = ContractedCost = the estimate, BillingCurrency 'USD', BillingPeriodStart/End = the month, ChargePeriodStart/End = the day (ISO-8601 UTC), ServiceName 'Amazon Elastic Compute Cloud', ServiceCategory 'Compute', ProviderName/PublisherName/InvoiceIssuerName 'AWS', RegionId = vm.getRegion(), ResourceId = providerVmId, ResourceName = displayName, ResourceType 'Instance', SkuId/SkuPriceId = the instance type where applicable, PricingQuantity/ConsumedQuantity = runtime hours, PricingUnit/ConsumedUnit 'Hours', Tags = JSON {"tcg:environment":..., "tcg:team":...}, and custom columns x_CostBasis 'Estimated' and x_PriceApproximate (E08-T07). Leave columns with no source empty where the spec allows nulls; document each such column.
3. basis=actual: one row per environment per day from cost_daily_snapshot.actual_cost (E08-T02/T03): ResourceId empty, Tags {"tcg:environment": name}, x_CostBasis 'Actual (Cost Explorer UnblendedCost by tag)'.
4. Controller: @GetMapping("/export/focus") with month validated by YearMonth.parse (400 on a bad value) and not in the future, basis enum. Return ResponseEntity<StreamingResponseBody> with Content-Type text/csv; charset=UTF-8 and Content-Disposition attachment; filename=focus-<basis>-<yyyy-MM>.csv. Write RFC 4180 CSV (quote fields containing comma, quote or newline; double inner quotes). ADMIN only.
5. UI: a toolbar dropdown 'Export FOCUS CSV' with a month picker (last 13 months) and basis radio; it downloads via the existing Utils.downloadBlob pattern or a plain link.
6. Document the column mapping and the limitations: estimates come from the static pricing reference, and actuals are per environment, not per resource.

**Acceptance criteria**

- Given September 2026 with 10 VMs, when an admin downloads basis=estimated, then the CSV has the FOCUS 1.2 header in spec order and 300 data rows whose BilledCost sum equals the sum of the daily estimates.
- Given a VM name containing a comma, then the field is quoted correctly.
- Given month=2026-13 or a future month, then 400; given a USER, then 403.

**Tests**

- FocusExportServiceTest: header equals the constant; the row mapping for one VM-day; the actual basis rows; CSV escaping; the totals match the estimates.
- Controller security test: ADMIN-only; bad month -> 400.

**Notes:** The brief says FOCUS 1.2 and the gap review cites FOCUS 1.4. Target 1.2 as assigned; extra columns use the x_ prefix the spec reserves for custom columns.

## E36

### AI assistant / MCP — Later

Users can ask natural-language questions about their environments, costs, savings and idle resources from an MCP-capable AI client (optionally next to the AWS Billing and Cost Management MCP server), through a read-only MCP server that only returns data the caller may see.

Sources: G24 · 5 tasks · 7 days

#### E36-T01 — Stand up a read-only MCP server endpoint authenticated by API tokens

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G24 · **Depends on:** E34-T05

G24: an MCP server is the standard way to give AI assistants (Claude Desktop/Code, IDE agents) tool access to platform data; it must reuse token authentication and per-user scoping.

**Files**

- `build.gradle` (modify) — MCP server dependency (Spring AI MCP server WebMVC starter or the MCP Java SDK servlet transport) - version compatible with Spring Boot 3.5
- `src/main/java/com/tcgdigital/vmcontrol/config/McpServerConfig.java` (new) — Register tool providers, server name/version, Streamable HTTP transport at /mcp; @ConditionalOnProperty assistant.mcp.enabled
- `src/main/java/com/tcgdigital/vmcontrol/model/ApiScope.java` (modify) — Add assistant:read
- `src/main/java/com/tcgdigital/vmcontrol/security/ApiScopeRegistry.java` (modify) — /mcp/** requires assistant:read
- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — /mcp/** token-only (session cookies rejected), stateless
- `src/main/java/com/tcgdigital/vmcontrol/service/assistant/AssistantPingTool.java` (new) — whoami tool returning the caller's display name and role (smoke test)
- `src/main/resources/application.properties` (modify) — assistant.mcp.enabled=false
- `src/test/java/com/tcgdigital/vmcontrol/assistant/McpEndpointSecurityTest.java` (new) — Auth and scope enforcement on /mcp

**Steps**

1. Pick the MCP server library at implementation time: Spring AI's MCP server WebMVC starter if its release line supports Boot 3.5 and Streamable HTTP; otherwise the MCP Java SDK with its servlet Streamable HTTP transport registered as a servlet at /mcp. Record the choice and version in the PR.
2. McpServerConfig exposes server info (name 'vmcontrol', version from build), only tools (no resources/prompts writing anything), and is created only when assistant.mcp.enabled=true and api-tokens.enabled=true.
3. Security: /mcp/** requires an ApiTokenAuthentication with scope assistant:read; session cookies get 401 (prevents browser-driven cross-site use); tokens are created with the assistant:read scope by any role in My Account (E34-T05 shows the new scope).
4. AssistantPingTool 'whoami' returns {name, role} via UserService.getCurrentUser() so clients can verify the connection.
5. Tests: no token -> 401; token without assistant:read -> 403; valid token -> MCP initialize and tools/list succeed and include whoami; flag off -> 404.

**Acceptance criteria**

- Given MCP is enabled and a user has a token with assistant:read, when an MCP client connects to /mcp, then it initialises and lists the tools.
- Given a session cookie but no token, when /mcp is called, then 401 is returned.

**Tests**

- McpEndpointSecurityTest

**Notes:** assistant.mcp.enabled defaults false. All tools are read-only by design; no tool may start, stop or change anything.

#### E36-T02 — Add read-only MCP tools for environments, costs, savings ledger and idle resources with per-user scoping

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G24 · **Depends on:** E36-T01, E18-T01, E17-T06, E23-T01

G24: questions like 'what did my team spend on idle environments' need tools over environments, costs, the savings ledger and idle data that only return what the caller can view (Cost Management endpoints are ADMIN-only today, CostManagementController L90-L160).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/assistant/AssistantScope.java` (new) — Resolves the caller's viewable environment ids (all for ADMIN; SecurityService/EnvironmentAccessService for others)
- `src/main/java/com/tcgdigital/vmcontrol/service/assistant/EnvironmentTools.java` (new) — list_environments(search?), get_environment(envId)
- `src/main/java/com/tcgdigital/vmcontrol/service/assistant/CostTools.java` (new) — get_environment_cost(envId), spend_by(dimension: environment|team|account, days), cost_trend(envId?, days)
- `src/main/java/com/tcgdigital/vmcontrol/service/assistant/SavingsTools.java` (new) — savings_summary(from, to, groupBy: environment|team|source)
- `src/main/java/com/tcgdigital/vmcontrol/service/assistant/IdleTools.java` (new) — idle_resources(envId?, limit), rightsizing_candidates(envId?, limit)
- `src/main/java/com/tcgdigital/vmcontrol/config/McpServerConfig.java` (modify) — Register the tool beans
- `src/test/java/com/tcgdigital/vmcontrol/service/assistant/AssistantToolsScopingTest.java` (new) — Non-admin sees only own environments in every tool

**Steps**

1. AssistantScope.viewableEnvironmentIds(): ADMIN -> all active; others -> environments where SecurityService grants view (reuse EnvironmentService.getEnvironmentsForCurrentUser()).
2. EnvironmentTools: return id, name, displayName, status counts, running VM count, region(s), account name, lease end (if E15), schedule summary; filtered by scope. get_environment checks scope and returns 'not found' otherwise (never reveal existence).
3. CostTools: get_environment_cost delegates to EnvironmentCostService (E18-T01, already scoped). spend_by and cost_trend reuse CostEstimationService grouping but filter bundles to viewable environments (add a filtered overload getSpendByEnvironment(Set<String> envIds) etc. rather than calling the ADMIN endpoints).
4. SavingsTools: SavingsReportService (E17-T06) with an environment-id filter; IdleTools: CostEstimationService.getIdleWaste/getRightsizingCandidates filtered by environment ids.
5. Every tool: typed input schema with descriptions, max 50 rows, amounts rounded to 2 decimals with currency 'USD', dates ISO-8601; include 'estimated' vs 'actual' labels so the assistant does not overstate precision; no user emails or IPs in outputs.
6. Tests: USER with access to env A only gets A in every tool; ADMIN gets all; get_environment for B returns not found for USER.

**Acceptance criteria**

- Given a USER with access to two environments, when their assistant asks for idle resources, then only VMs in those two environments are returned.
- Given an ADMIN, when asked 'spend by team over 30 days', then spend_by returns team totals matching the Cost Management page.
- Given any tool, when called, then it returns at most 50 rows and never modifies data.

**Tests**

- AssistantToolsScopingTest
- One unit test per tool class for schema and mapping

**Notes:** Team grouping uses the Team entity from E23-T01.

#### E36-T03 — Audit, rate-limit and cap MCP tool calls

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** low · **Sources:** G24 · **Depends on:** E36-T02, E11-T01

G24 (security scoping per user): assistant access must be traceable and bounded so a runaway agent cannot scrape or overload the platform.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/assistant/AssistantToolGuard.java` (new) — Wraps every tool: audit, per-user rate limit, timeout, output size cap
- `src/main/java/com/tcgdigital/vmcontrol/model/AuditAction.java` (modify) — ASSISTANT_TOOL_CALLED
- `src/main/resources/application.properties` (modify) — assistant.mcp.rate-limit-per-minute=60, assistant.mcp.max-output-chars=50000, assistant.mcp.tool-timeout-ms=10000
- `src/test/java/com/tcgdigital/vmcontrol/service/assistant/AssistantToolGuardTest.java` (new) — Audit, limit, truncation

**Steps**

1. AssistantToolGuard.invoke(toolName, args, supplier): check per-user token bucket (429-style MCP error 'rate limited'), run with timeout, serialise result and truncate above max-output-chars with a 'truncated' flag, write an audit row (actor, token prefix, tool name, argument summary without free text longer than 200 chars, result row count, duration). Audit writes go through AuditWriter (E11-T01) and must not fail the call.
2. Route every tool method in E36-T02 through the guard.
3. Add a filter on the Audit Logs page for ASSISTANT_TOOL_CALLED (existing action filter covers it once the enum value exists).
4. Tests: 61st call in a minute rejected; long output truncated; audit row written with tool name.

**Acceptance criteria**

- Given a user's assistant calls tools, when an admin opens the audit log, then each call appears with the tool name and the user.
- Given more than 60 calls in a minute, when the next call arrives, then it is rejected with a rate-limit error.

**Tests**

- AssistantToolGuardTest

#### E36-T04 — Document client setup with the AWS Billing and Cost Management MCP server alongside

- [ ] Done · **Type:** docs · **Estimate:** 0.5 d · **Risk:** low · **Sources:** G24 · **Depends on:** E36-T03

G24: start from the AWS Billing MCP server; users need a tested configuration combining it with the vmcontrol server and guidance on which questions each answers.

**Files**

- `docs/ai-assistant.md` (new) — Setup, security model, sample questions, limitations
- `docs/examples/mcp-config.json` (new) — Example client config with both servers (placeholders only)

**Steps**

1. Explain the model: vmcontrol MCP = environment-aware, per-user scoped platform data; AWS Billing and Cost Management MCP server (awslabs) = account-level AWS billing data, run locally by FinOps/admin users with a read-only billing role (never the platform's credentials).
2. Client configuration for Claude Desktop and Claude Code: remote HTTP server URL https://<host>/mcp with 'Authorization: Bearer <token with assistant:read>'; the awslabs server via uvx with AWS_PROFILE for a read-only billing profile. Placeholders only, no secrets.
3. Sample questions mapped to tools ('What did my team spend on idle environments last month?' -> idle_resources + spend_by team; 'Which environments saved most from schedules?' -> savings_summary), and which need the AWS server (Savings Plans coverage across all accounts).
4. Security notes: token scope, rotation, audit, data leaves the platform into the AI client's context; recommend org-approved AI clients only.

**Acceptance criteria**

- Given a user follows docs/ai-assistant.md, when they connect a client, then 'whoami' and a sample question work end to end.

**Tests**

- Manual end-to-end check recorded in the PR

#### E36-T05 — Add an optional in-app 'Ask' panel backed by an LLM using the same read-only tools

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** G24 · **Depends on:** E36-T04, E02-T01

G24: users without an MCP-capable client should be able to ask questions inside the platform.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/assistant/AssistantChatService.java` (new) — Server-side tool-use loop over the E36-T02 tools for the current session user
- `src/main/java/com/tcgdigital/vmcontrol/controller/AssistantController.java` (new) — POST /api/v1/assistant/ask {question, history[]} -> {answer, toolCalls[]}
- `src/main/resources/application.properties` (modify) — assistant.chat.enabled=false, assistant.chat.provider-url, assistant.chat.model, assistant.chat.api-key=${ASSISTANT_API_KEY:}, assistant.chat.daily-questions-per-user=50
- `.env.example` (modify) — ASSISTANT_API_KEY placeholder
- `src/main/resources/static/js/features/assistant.js` (new) — Slide-out Ask panel: question box, answer rendered as escaped text with simple lists, 'tools used' disclosure
- `src/main/resources/static/js/ui/user-menu.js` (modify) — 'Ask' entry when enabled
- `src/main/resources/static/index.html` (modify) — Script include
- `src/test/java/com/tcgdigital/vmcontrol/service/assistant/AssistantChatServiceTest.java` (new) — Loop with a stubbed LLM client

**Steps**

1. AssistantChatService: build a system prompt describing the platform and the rule 'answer only from tool results; say when data is estimated'; send the user question plus up to 10 prior turns to the configured LLM provider with the tool definitions generated from the same tool classes; execute requested tools through AssistantToolGuard as the current user; loop at most 6 tool rounds; return the final text and the list of tools called. Model id, endpoint and key come from properties; key never sent to the browser.
2. Controller: authenticated session users; CSRF protected; per-user daily quota (429 when exceeded); 404 when assistant.chat.enabled=false. No conversation is persisted server-side beyond the audit rows from the guard.
3. UI: slide-out panel (existing slideout.js), answers rendered as text (Utils.escapeHtml, newline to <br>, '- ' to list items) - never raw HTML from the model. Run .\build-frontend.ps1.
4. Tests with a stubbed LLM client: tool call executed with the caller's scope; loop cap; quota.

**Acceptance criteria**

- Given the assistant is enabled, when a USER asks 'which of my environments were idle this week', then the answer cites only their environments and lists the tools used.
- Given the model output contains HTML, when shown, then it is rendered as text.

**Tests**

- AssistantChatServiceTest
- AssistantController security test

**Notes:** Assumption: provider-neutral HTTP client with configurable endpoint/model so the organisation can choose its approved LLM (for example via Amazon Bedrock). Defaults off; data-processing approval needed before enabling.

## E37

### Carbon per environment — Later

Monthly AWS Customer Carbon Footprint data from Data Exports is ingested and attributed to environments by cost share, and shown next to cost on the environment page and in Cost Management.

Sources: G25 · 3 tasks · 4.5 days

#### E37-T01 — Ingest the AWS carbon emissions Data Export from S3 into a monthly table

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G25 · **Depends on:** E28-T11, E06-T01

G25: AWS publishes Customer Carbon Footprint Tool data as a Data Export to S3; the platform has no S3 client or carbon data today.

**Files**

- `build.gradle` (modify) — implementation 'software.amazon.awssdk:s3'
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsClientFactory.java` (modify) — s3(accountRef, region)
- `src/main/resources/db/migration/V<next>__add_carbon_tables.sql` (new) — carbon_monthly and carbon_ingest_file tables
- `src/main/java/com/tcgdigital/vmcontrol/model/CarbonMonthly.java` (new) — month, account_number, region, service_code, emissions_mbm_kg, emissions_lbm_kg, source_file
- `src/main/java/com/tcgdigital/vmcontrol/repository/CarbonMonthlyRepository.java` (new) — findByMonth, deleteByMonth
- `src/main/java/com/tcgdigital/vmcontrol/service/carbon/CarbonExportReader.java` (new) — List export files under bucket/prefix, stream gzip CSV, map columns by header name
- `src/main/java/com/tcgdigital/vmcontrol/service/carbon/CarbonIngestionService.java` (new) — Ingest new/changed files idempotently per month
- `src/main/java/com/tcgdigital/vmcontrol/scheduler/CarbonIngestionScheduler.java` (new) — Daily via runLocked
- `src/main/resources/application.properties` (modify) — carbon.enabled=false, carbon.export.bucket, carbon.export.prefix, carbon.export.region, carbon.cron=0 0 6 * * *
- `src/test/java/com/tcgdigital/vmcontrol/service/carbon/CarbonExportReaderTest.java` (new) — Parse a sample CSV fixture
- `src/test/resources/carbon/sample-carbon-export.csv.gz` (new) — Small fixture with header and rows

**Steps**

1. Add the s3 SDK module and AwsClientFactory.s3(accountRef, region) using the billing account (AwsAccountService.getBillingAccountRef from E28-T11), since the export lives in the management/billing account.
2. Migration: carbon_monthly (carbon_id PK, month DATE (first of month), account_number VARCHAR(12), region VARCHAR(30), service_code VARCHAR(100), emissions_mbm_kg DECIMAL(14,4) NULL, emissions_lbm_kg DECIMAL(14,4) NULL, source_file VARCHAR(1024), ingested_at; UNIQUE(month, account_number, region, service_code)); carbon_ingest_file (s3_key PK, etag, ingested_at, row_count).
3. CarbonExportReader: ListObjectsV2 under carbon.export.prefix, read .csv.gz files (configure the export in CSV/gzip format; Parquet out of scope), map columns by header name through a configurable mapping (carbon.columns.month, .account, .region, .service, .mbm, .lbm) because the export schema must be verified against the live export at implementation time; convert units to kg CO2e (MTCO2e x 1000) based on a unit column or carbon.columns.unit-multiplier.
4. CarbonIngestionService: skip files whose s3_key+etag were already ingested; otherwise replace all rows for the months contained in the file in one transaction. Gate with carbon.enabled; log and continue on per-file errors.
5. Scheduler with ScheduledJobLockService.runLocked("carbon-ingestion", ...) and an ADMIN endpoint POST /api/v1/cost-management/carbon/ingest to trigger it manually.
6. Tests: fixture parsed into the expected rows and units; re-ingest of same etag is a no-op; changed etag replaces the month.

**Acceptance criteria**

- Given a carbon Data Export in the configured bucket, when ingestion runs, then monthly emissions per account, region and service are stored in kg CO2e.
- Given the same file again, when ingestion runs, then nothing changes.

**Tests**

- CarbonExportReaderTest
- CarbonIngestionServiceTest

**Notes:** Read-only AWS access (s3:ListBucket, s3:GetObject on the export prefix). Assumption: column names in the carbon export are configured, not hard-coded, until verified against a real export. Carbon data is monthly and lags by weeks; the UI says so.

#### E37-T02 — Attribute monthly emissions to environments by cost share

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** G25 · **Depends on:** E37-T01, E08-T04

G25: CCFT data is per account/region/service, not per resource; environments get a share proportional to their cost in the same account, region and service.

**Files**

- `src/main/resources/db/migration/V<next>__add_environment_carbon_monthly.sql` (new) — environment_carbon_monthly table
- `src/main/java/com/tcgdigital/vmcontrol/model/EnvironmentCarbonMonthly.java` (new) — environment_id NULL (NULL = unallocated), month, emissions_mbm_kg, emissions_lbm_kg, cost_basis, coverage_pct
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentCarbonMonthlyRepository.java` (new) — findByMonthBetween, findByEnvironmentEnvironmentIdAndMonthBetween
- `src/main/java/com/tcgdigital/vmcontrol/service/carbon/CarbonAttributionService.java` (new) — attributeMonth(YearMonth)
- `src/main/java/com/tcgdigital/vmcontrol/service/carbon/CarbonIngestionService.java` (modify) — Re-run attribution for months that changed
- `src/test/java/com/tcgdigital/vmcontrol/service/carbon/CarbonAttributionServiceTest.java` (new) — Shares, unallocated remainder, missing cost

**Steps**

1. For the month, compute each environment's cost per (account number, region, service) bucket: VM-level costs for the month from CostDataProvider estimates over the month window (EC2 VMs -> AmazonEC2, EKS node groups -> AmazonEC2 plus AmazonEKS, AWS_RDS -> AmazonRDS if E33 is done), using actual costs to scale estimates when cost_daily_snapshot.actual_cost exists for the environment (environment-level ratio actual/estimated).
2. For each carbon_monthly row, find the account's total cost for that service/region in the month from Cost Explorer actuals if available (one extra grouped GetCostAndUsage per month with GroupBy LINKED_ACCOUNT and SERVICE, filtered by region, cached in a small table or memory), else the sum of platform environments' costs in that bucket (then coverage_pct = 100% of tracked spend). share_env = env_cost / bucket_total_cost; emissions_env = emissions x share_env.
3. Write one row per environment per month (summing buckets) plus a NULL-environment 'unallocated' row with the remainder; coverage_pct = attributed / total emissions for the accounts the platform knows.
4. Idempotent: delete and rewrite the month's rows in one transaction.
5. Tests: two environments with 75/25 cost share in one bucket get 75/25 of emissions; bucket with no platform cost goes to unallocated; zero total cost does not divide by zero.

**Acceptance criteria**

- Given an account/region/service with 100 kg CO2e and two environments holding 60% and 20% of its cost, when attribution runs, then they get 60 kg and 20 kg and 20 kg is unallocated.
- Given carbon data for a month is re-ingested, when attribution runs again, then the month's rows are replaced, not duplicated.

**Tests**

- CarbonAttributionServiceTest

**Notes:** Assumption: cost share is the attribution method required by G25; both market-based (MBM) and location-based (LBM) values are carried when present.

#### E37-T03 — Show carbon next to cost on the environment page and in Cost Management, with setup docs

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** G25 · **Depends on:** E37-T02, E18-T03, E03-T01

G25: carbon must be visible alongside cost to owners and admins.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/carbon/CarbonReportService.java` (new) — Environment trend, fleet by environment, kgCO2e per $
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentCarbonDTO.java` (new) — months[{month, mbmKg, lbmKg, cost, kgPerDollar}], coverageNote
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — GET /api/v1/environments/{envId}/carbon (view rights)
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — GET /api/v1/cost-management/carbon (ADMIN), GET /carbon/export (xlsx via ExcelExportService)
- `src/main/java/com/tcgdigital/vmcontrol/service/ExcelExportService.java` (modify) — Carbon sheet
- `src/main/resources/static/js/features/environments.js` (modify) — Carbon line/sparkline inside the E18-T03 cost card
- `src/main/resources/static/js/features/cost-management.js` (modify) — 'Carbon by environment' table and monthly trend; caveat text
- `docs/carbon.md` (new) — Creating the carbon Data Export, bucket policy, IAM, attribution method and caveats
- `src/test/java/com/tcgdigital/vmcontrol/controller/CarbonEndpointsSecurityTest.java` (new) — View rights and admin-only fleet view

**Steps**

1. CarbonReportService: environment monthly series (last 12 months) joined with monthly cost; fleet table for a month sorted by emissions incl. 'Unallocated' row and coverage.
2. Endpoints: environment carbon requires SecurityService.assertCanView(envId); fleet and export ADMIN only; 404 when carbon.enabled=false.
3. UI: in the environment cost card add 'Carbon (last full month): X kg CO2e (market-based)' with a 12-month sparkline and an info tooltip 'Estimated by cost share from AWS Customer Carbon Footprint data; published monthly with a delay'. Cost Management: table + trend chart using existing chart helpers; Excel export button. Escape all values; run .\build-frontend.ps1.
4. docs/carbon.md: create a Data Export of the carbon emissions table (CSV, gzip) in the management account to the configured bucket, required IAM for the billing role, properties, how attribution works, limits.
5. Tests: VIEWER of env A can read A's carbon and gets 404 for B; fleet endpoint ADMIN-only; flag off -> 404.

**Acceptance criteria**

- Given carbon data is ingested and attributed, when an environment owner opens their environment, then the cost card shows last month's emissions and a 12-month trend.
- Given an ADMIN, when they open Cost Management, then carbon by environment, the unallocated share and coverage are shown and exportable to Excel.

**Tests**

- CarbonEndpointsSecurityTest
- CarbonReportServiceTest

## PL

### Parking lot — Parked

**Goal:** Decisions and ops switches deferred by the product owner (2026-10-08). Not scheduled: each item is picked up only after it is discussed.

#### PL-01 — Register the Entra post-logout redirect URI before deploying E03

- [ ] Done · **Type:** ops · **Estimate:** 0.25 d · **Risk:** low · **Sources:** E03-T12

E03-T12 sends Entra users to the Entra end-session endpoint on logout. Unless https://selfservice-new.tcgdigital.com/login?logout=true is a registered redirect URI on the app registration, Entra shows an error page after sign-out instead of returning to the app.

**Steps**

1. Entra admin centre > App registrations > (this app) > Authentication > Web > Redirect URIs: add https://selfservice-new.tcgdigital.com/login?logout=true.
2. Deploy; sign in with Entra, click Logout, confirm Microsoft signs you out and returns to the login page with the logout message.
3. Click Sign in with Entra ID again and confirm Entra asks for an account instead of signing in silently.

**Acceptance criteria**

- Logout in production returns to /login?logout=true without an Entra error page.

**Notes:** Blocks the production deploy of E03 (already on main). Also tell owners of API scripts that POST/PUT/PATCH/DELETE now need the X-XSRF-TOKEN header (E03-T10).

#### PL-02 — Switch ENV_ADMIN scope from 'global' to 'assigned'

- [ ] Done · **Type:** ops · **Estimate:** 0.5 d · **Risk:** medium · **Sources:** E03-T05

With SECURITY_ENV_ADMIN_SCOPE=global (current default) every ENV_ADMIN can manage every environment. 'assigned' limits them to environments where they hold an active ENVIRONMENT ADMIN grant. The code for both modes shipped in E03-T05.

**Steps**

1. Run the ENV_ADMIN / grant query from the E03-T05 commit message (925f51b) against production; rows with NULL environment_id have no ADMIN grant anywhere.
2. Give each ENV_ADMIN an ENVIRONMENT ADMIN grant on the environments they should manage.
3. Set SECURITY_ENV_ADMIN_SCOPE=assigned and restart.
4. Rollback: set it back to global and restart (no data change).

**Acceptance criteria**

- An ENV_ADMIN without a grant on environment X gets 403 when editing X or approving its requests; global admins are unaffected.

**Notes:** Parked by the product owner on 2026-10-08: keep global for now, discuss later.

#### PL-03 — Enforce the Content-Security-Policy (leave report-only mode)

- [ ] Done · **Type:** ops · **Estimate:** 0.5 d · **Risk:** medium · **Sources:** E01

The CSP ships in report-only mode (SECURITY_CSP_REPORT_ONLY=true): violations are logged but nothing is blocked, so it gives no XSS protection yet. Enforcing it blocks injected scripts.

**Steps**

1. After a normal day of production use, search the logs for "CSP violation report".
2. Ignore browser-extension noise (chrome-extension://, moz-extension://); fix any real violation first.
3. Set SECURITY_CSP_REPORT_ONLY=false and restart.
4. Rollback: set it back to true and restart.

**Acceptance criteria**

- Responses carry Content-Security-Policy (not -Report-Only) and every page works.

**Notes:** Parked by the product owner on 2026-10-08: keep report-only for now, discuss later.

#### PL-04 — Decide on the uncommitted '**/.github' line in .gitignore

- [ ] Done · **Type:** chore · **Estimate:** 0 d · **Risk:** low

A local, uncommitted .gitignore change ignores .github, so every CI workflow edit needs git add -f (ci.yml was committed that way in c62b1dd).

**Files**

- `.gitignore` (modify) — keep or drop the '**/.github' line

**Steps**

1. Drop the line (recommended) or commit it knowingly.

**Acceptance criteria**

- Editing .github/workflows/ci.yml no longer needs -f, or the team agrees it should.

#### PL-05 — Lock history order ties: performed_at stored at second precision

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** finding 2026-10-08

lock_history.performed_at is stored at second precision in MySQL, so two lock events in the same second (acquire then release) can come back in the wrong order in the history view. Found while testing E03-T02.

**Steps**

1. Either a new migration to DATETIME(3)/TIMESTAMP(3) for lock_history.performed_at, or order by performed_at then an insertion sequence.
2. Consider folding into E07 (Locks) when it is planned.

**Acceptance criteria**

- Acquire and release in the same second are listed in the order they happened.

#### PL-06 — Two dev-mode e2e specs fail: unauthenticated /home and the login error wording

- [ ] Done · **Type:** chore · **Estimate:** 0.25 d · **Risk:** low · **Sources:** finding 2026-10-08

Found running the full Playwright suite against the test-schema app (E04, 2026-10-08). auth/session.spec expects an unauthenticated /home to be blocked, but dev security (ENTRAID_ENABLED=false) permits every request by design. smoke/login.spec rejects the text "Invalid credentials or user not found." The text is identical whether or not the user exists, so it does not enumerate users, but it reads as if it might. Neither was changed by E04.

**Files**

- `test/tests/auth/session.spec.ts` (modify) — skip the unauthenticated check when the app runs in dev mode
- `src/main/java/com/tcgdigital/vmcontrol/controller/AuthController.java` (modify) — INVALID_CREDENTIALS wording (line 47), if reworded
- `src/main/java/com/tcgdigital/vmcontrol/service/AuthenticationService.java` (modify) — same message (lines 52, 56)
- `src/main/resources/static/js/login.js` (modify) — DEFAULT_ERROR

**Steps**

1. Decide the login failure text, e.g. "Invalid username or password." (same for every failure).
2. Make session.spec skip, or run against an Entra-mode build, when the server uses dev security.
3. Rerun the full e2e suite: expect 0 failures.

**Acceptance criteria**

- The full Playwright suite passes against the test-schema app.

**Notes:** Pre-existing; not caused by E04 (login.js, AuthController, AuthenticationService and DefaultSecurityConfig are unchanged on the E04 branch).

#### PL-07 — Automation rules list returns 500 when a rule points to a deleted environment

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** finding 2026-10-08

Found in the full E2E run on the E05 build (2026-10-08): AutomationRuleService.resolveScopeName (toDto, line ~423) loads the rule's environment proxy; when that environment was deleted, Hibernate throws EntityNotFoundException and the rules list fails with a 500. Pre-existing: AutomationRuleService is unchanged since the rules engine was added.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — resolveScopeName tolerates a missing environment/group
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — deleting an environment disables or deletes its rules

**Steps**

1. Decide: delete or disable a rule when its environment is deleted (and clean up existing orphans with a migration).
2. Make resolveScopeName show "(deleted environment)" instead of throwing.
3. Test: rule list with an orphaned rule returns 200.

**Acceptance criteria**

- The automation rules list loads even if a rule references a deleted environment.

**Notes:** Fits E06 (automation reliability) or E10 (environment deletion).

#### PL-08 — Check Flyway history on every deployed database before releasing E14-T01

- [ ] Done · **Type:** ops · **Estimate:** 0.5 d · **Risk:** medium · **Sources:** E14-T01 step 1 · **Depends on:** E14-T01

E14-T01 (2026-10-12) removed the repair-then-migrate startup strategy; startup now validates migrations. Any checksum drift the old automatic repair was hiding will stop the app from starting on that environment. Comparing deployed databases needs access the delivery session does not have.

**Steps**

1. On each deployed environment (and any shared dev DB) run `SELECT version, checksum, success FROM flyway_schema_history ORDER BY installed_rank`.
2. Compare with `flyway info` of the release build (or a fresh test schema).
3. Resolve any mismatch deliberately per docs/runbook-flyway.md and record it before deploying the E14 build.

**Acceptance criteria**

- Every deployed database validates against the E14 build before it is deployed there.

**Notes:** Deploy prerequisite, like PL-01.

#### PL-09 — Delete home.html, home-original.html and static/old_backup/ (E14-T03 leftovers)

- [ ] Done · **Type:** cleanup · **Estimate:** 0.25 d · **Risk:** low · **Sources:** E14-T03, LOW-hygiene-dead-frontend · **Depends on:** E14-T03

E14-T03 asks to delete these mockups and backups, but the product owner decided earlier (E02) not to delete the legacy static files. E14-T03 removed everything else (TemplateLoader, templates/, the mock workspace section and panels) and stopped packaging old_backup/; home.html and home-original.html still ship and load CDN scripts without integrity hashes.

**Steps**

1. Confirm with the product owner that the legacy files can go (or move them out of src/main/resources).
2. Delete them and extend the CI JAR check with their paths.

**Acceptance criteria**

- The built JAR contains no home.html, home-original.html or old_backup entries.

**Notes:** Blocked on the 'keep legacy static files' decision.

#### PL-10 — Drop the six unused V1 tables and the two V4 temp tables (E14-T05 second half)

- [ ] Done · **Type:** cleanup · **Estimate:** 0.5 d · **Risk:** medium · **Sources:** E14-T05, LOW-hygiene-schema · **Depends on:** E14-T05

E14-T05 shipped the reversible half (V41 drops nine redundant indexes). Dropping vm_provider_details, login_session, system_config, cloud_provider_credential, scheduled_job, job_execution_log, temp_instance_parsed and temp_vm_master is irreversible; the task requires confirming on a production copy that they are unused (row counts, table_io_waits) and keeping a mysqldump first, which needs production access.

**Steps**

1. On a production copy: SELECT COUNT(*) per table and check performance_schema.table_io_waits_summary_by_table for reads; take a mysqldump of the 8 tables.
2. Add the DROP TABLE IF EXISTS migration (keep user_notification_preference for E29-T05).
3. Update reset-test-data.sql and FlywayMigrationTest; run the full suite.

**Acceptance criteria**

- information_schema has none of the 8 tables; user_notification_preference still exists; the dump is stored with the release.

**Notes:** Irreversible: do not merge without the production-copy check and dump.

#### PL-11 — Repo hygiene that depends on the owner's .gitignore and team decisions (E14-T06 remainder)

- [ ] Done · **Type:** cleanup · **Estimate:** 0.25 d · **Risk:** low · **Sources:** E14-T06, LOW-hygiene-repo · **Depends on:** E14-T06, PL-04

E14-T06 removed the duplicate deployment/app/application-prod.properties (its 9 extra keys were merged into config/). The rest touches decisions that belong to the owner: the uncommitted local .gitignore ignores graphify-out/ entirely (the task wants graph.json tracked and only cache/ ignored) and the delivery rules say not to commit graphify-out/, the .gitignore change or Z'AS'px; .ai/ is gitignored in the committed .gitignore; deleting generate_v4_migration.py and data/parse-vm-data.py needs team confirmation.

**Steps**

1. Decide: track graphify-out (minus cache/) or ignore it entirely; settle the local .gitignore lines (PL-04).
2. Decide whether .ai/ docs are versioned; if so remove `**/.ai` and `git add .ai/` after grepping for secrets.
3. Confirm and `git rm` generate_v4_migration.py and data/parse-vm-data.py; delete local junk (Z'AS'px, puttygen-*.tmp).

**Acceptance criteria**

- git ls-files graphify-out lists nothing under cache/ (or nothing at all, per the decision); junk files are gone; .ai/ matches the decision.

**Notes:** Owner decisions; nothing blocks the build.

#### PL-12 — graphify semantic extraction fails: its Anthropic API key is rejected

- [ ] Done · **Type:** ops · **Estimate:** 0.1 d · **Risk:** low · **Sources:** autonomous epic loop · **Depends on:** —

Since E23, `graphify .` (run after each epic) fails its semantic pass: every chunk gets `401 authentication_error: invalid x-api-key` from the Anthropic API, so docs/backlog.md, docs/aws-iam-permissions.md, docs/cost-management-setup.md and static/index.html are left out of the graph. The code graph is unaffected: `graphify update .` (AST only, no API) still runs after every commit.

**Steps**

1. Give graphify a valid key (the environment variable its 'claude' backend reads, e.g. ANTHROPIC_API_KEY), or switch it to another backend.
2. Run `graphify .` and confirm the four files appear in graphify-out/GRAPH_REPORT.md.

**Acceptance criteria**

- `graphify .` exits 0 with no failed semantic chunks.

**Notes:** Owner action (credentials). Nothing in the app depends on it; the loop keeps running `graphify update .` and retries `graphify .` at each epic end.

#### PL-13 — Slack as a second chat provider (E24 ships Microsoft Teams only)

- [ ] Done · **Type:** feature · **Estimate:** 0.5 d · **Risk:** low · **Sources:** G13, E24-T01 · **Depends on:** E24-T01

The product owner chose Microsoft Teams only for now (2026-10-13). E24-T01 first shipped a Slack Block Kit sender (commit eaba180); it was removed in the next commit, and ChatProvider has only TEAMS. The chat interface stays provider-neutral.

**Steps**

1. Restore SlackWebhookSender and SlackWebhookSenderTest from eaba180 (`git show eaba180:<path>`) and add SLACK back to ChatProvider.
2. Offer Slack in the channel editor (E24-T02 screens) and check the escaping of Slack mrkdwn.

**Acceptance criteria**

- A Slack channel mapping receives the same events and buttons as a Teams one.

**Notes:** Owner decision; pick up only after it is discussed.

#### PL-14 — Regenerate the pricing reference for every region with the AWS Price List API

- [ ] Done · **Type:** ops · **Estimate:** 0.25 d · **Risk:** low · **Sources:** G18, E30-T02 · **Depends on:** E30-T02

E30-T02 added scripts/generate-instance-pricing.ps1 and the instance specs, generation map and downsize map, but the development machine has no AWS CLI, so the script was not run. The newer families (t4g, m6g/m7i/m7g, c6i/c6g/c7i/c7g, r6i/r6g/r7i/r7g) are priced for us-east-1 and us-west-2 only; in ap-south-1, eu-west-1, eu-central-1 and ap-southeast-1 they fall back to the US rate, flagged as approximate. Two E30-T02 acceptance criteria wait for this run: at least 6 regions, and an exact eu-central-1 rate for m5.large.

E33-T05 added RDS pricing (rdsPricing, rdsStorageGbMonthRate) the same way: the us-east-1 rates for PostgreSQL, MySQL and Aurora were entered by hand from the list prices (Multi-AZ as twice Single-AZ); every other region falls back to them, flagged approximate. The script's -Rds switch reads them from the AmazonRDS price list.

**Steps**

1. On a machine with the AWS CLI v2 and credentials allowed pricing:GetProducts and ec2:DescribeInstanceTypes (read-only), run `./scripts/generate-instance-pricing.ps1 -Rds`.
2. Review the diff of src/main/resources/pricing/instance-pricing.json (rates that disappeared mean the API had no match) and commit it.

**Acceptance criteria**

- The app logs well over 26 instance rates in at least 6 regions at startup.
- m5.large in eu-central-1 resolves to an exact (not approximate) rate.
- db.t4g.medium PostgreSQL in eu-west-1 resolves to an exact RDS rate, and the hand-entered us-east-1 RDS rates match the price list.

**Notes:** Needs someone with AWS access; no code change.

#### PL-15 — Security review of API tokens, service accounts and webhooks before enabling them in production

- [ ] Done · **Type:** security · **Estimate:** 1 d · **Risk:** medium · **Sources:** G22, E34-T02, E34-T06 · **Depends on:** E34-T08

E34 adds bearer API tokens (api-tokens.enabled), service accounts and outbound webhooks (webhooks.enabled), all off by default. E34-T02 asks for a security review before tokens are enabled in production; webhooks add an SSRF surface.

**Steps**

1. Review the token format and hashing (HMAC-SHA256 with API_TOKEN_PEPPER), ApiScopeRegistry (default deny), the rate limit and the CSRF exemption for bearer requests (ApiTokenAuthenticationFilter).
2. Review service-account sign-in refusal and token revocation on deactivation.
3. Review webhook URL validation (SafeUrlValidator; the DNS window between the check and the connection), redirect handling and secret storage.
4. Then set API_TOKEN_PEPPER, API_TOKENS_ENABLED, API_DOCS_ENABLED and, with CHAT_SECRET_KEY, WEBHOOKS_ENABLED in production.

**Acceptance criteria**

- The reviewer signs off or files the changes they need.
- Tokens and webhooks are enabled in production only after that.

**Notes:** Needs a security reviewer; the features ship off.

#### PL-16 — End-to-end check of the MCP assistant with a real AI client on an Entra-enabled deployment

- [ ] Done · **Type:** ops · **Estimate:** 0.5 d · **Risk:** low · **Sources:** G24, E36-T04 · **Depends on:** E36-T04, PL-15

E36-T04 asks for a manual end-to-end check recorded in the PR. API tokens only authenticate with Entra sign-in on, so the local smoke app (Entra off) cannot do it; it needs a deployment and a person with an AI client. The protocol flow is covered by McpEndpointSecurityTest.

**Steps**

1. On a test deployment with Entra on, set API_TOKEN_PEPPER, API_TOKENS_ENABLED=true and ASSISTANT_MCP_ENABLED=true (after PL-15).
2. Create a token with only assistant:read in My Account.
3. Connect Claude Code or Claude Desktop as docs/ai-assistant.md says.
4. Ask 'who am I on vmcontrol?' and one sample question; check the ASSISTANT_TOOL_CALLED audit rows.

**Acceptance criteria**

- whoami and a sample question work end to end; the result is recorded on the E36 PR.
