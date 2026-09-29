# Graph Report - tcg-self-service-backup  (2026-09-29)

## Corpus Check
- 382 files · ~263,345 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 5826 nodes · 18553 edges · 211 communities (147 shown, 64 thin omitted)
- Extraction: 75% EXTRACTED · 25% INFERRED · 0% AMBIGUOUS · INFERRED: 4547 edges (avg confidence: 0.81)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `85eafed6`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- echarts.min.js
- org.junit.jupiter.api.Test
- VmInventorySnapshot
- org.junit.jupiter.api.DisplayName
- a
- User
- EnvironmentAccessRequest
- i
- U
- VmStatus
- html2canvas.min.js
- org.springframework.data.domain.Page
- LockHistory
- org.springframework.context.annotation.Bean
- org.springframework.security.access.prepost.PreAuthorize
- $
- java.sql.Timestamp
- AccessLevel
- E
- io.swagger.v3.oas.annotations.responses.ApiResponses
- VmRepository
- VmMetricSample
- SecurityService
- org.slf4j.Logger
- AuditAction
- .fetchInventory
- EnvironmentAccessDTO
- Vm
- AuditLog
- VmDTO
- VmIdleSummary
- org.springframework.data.jpa.repository.JpaRepository
- NotificationService
- jquery-3.6.0.min.js
- VmMetricSampleArchive
- org.springframework.http.ResponseEntity
- DashboardSummaryService
- OperationExecutionDTO
- OperationDetail
- VmGroupDTO
- OperationDetailDTO
- EksCloudProviderService
- org.springframework.data.jpa.repository.Query
- ReservationCoverageSnapshot
- .registerVm
- VmGroup
- AuditLogDTO
- WeeklyReportService
- remove
- AuditReportDTO
- VmMetricsDTO
- Environment
- Ec2ControllerTest
- AuditService
- WeeklyOptimizationSnapshot
- ln
- AwsCloudProviderServiceTest
- EnvironmentService
- OperationExecution
- auth.ts
- AccessGrantRequestDTO
- AutomationRuleRequestDTO
- UserService
- EmailLog
- .getCurrentUserId
- EnvironmentLock
- org.springframework.stereotype.Service
- VmMetricData
- .onboard
- CostDailySnapshotRepository
- .hide
- xn
- .getOperationEstimate
- GraphDirectoryService
- OAuth2IntegrationTest
- io.swagger.v3.oas.annotations.Operation
- org.springframework.transaction.annotation.Transactional
- VmMgmtController
- EnvironmentDTO
- NotificationType
- EksSyncServiceTest
- Notification
- AutomationRule
- .startOperation
- VmMetricDaily
- AwsCloudProviderService
- AutomationRuleDTO
- rt
- EnvironmentController
- CostForecastService
- AutomationRuleService
- .buildBundles
- _l
- ci
- gr
- .acquireLock
- CostEstimationServiceTest
- VmMetricsService
- .computeRuntimeHours
- .getVmId
- Iw
- scripts
- CostExplorerBillingService
- StateSyncStatusDTO
- .acquireLock
- LockStatusDTO
- Ni
- org.junit.jupiter.api.BeforeEach
- N
- wA
- LockServiceTest
- Access Grants — Requirement (admin-initiated + group-level access)
- Builder
- .getGroupId
- EksSyncService
- VmInventorySnapshotRepository
- CloudProviderService
- .estimateCosts
- AGENTS.md
- com.fasterxml.jackson.databind.ObjectMapper
- Review Checklist
- Design Principles for This Project
- .updateEnvironment
- TagReconciliationServiceTest
- Onboard a directory user from the admin panel (Microsoft Graph)
- Review Dimensions
- PricingReferenceService
- Y
- qn
- CLAUDE.md
- .login
- VmOperationsController
- AccessScopeType
- Ti
- Low Priority (design-system hygiene)
- AuthenticationService
- Wt
- generate_v4_migration.py
- database-engineer.agent.md
- frontend-engineer.agent.md
- AutomationTriggerType
- DailyAggregate
- vi
- High Priority
- UI Consistency Governance — Task List
- Settings Page — Requirement (brainstorm, not yet built)
- AutomationScopeType
- VmOperationProgress
- q
- E2E tests (Playwright) — TCG VM Self-Service Platform
- Medium Priority
- backend-engineer.agent.md
- cloud-architect.agent.md
- te
- Part C — Per-Page Notes
- VM Self-Service Platform — UX & Visual Design Review
- CostExplorerTagActivationService
- EmailTemplates
- Part B — Cross-Cutting Design System Findings
- AWS IAM Permissions
- Part A — Application Map
- 11. UI / UX
- EnvironmentVmCounts
- 6. Data model
- 8. API changes
- 3. Architecture
- 5. API
- gradlew
- playwright
- 2. Chosen approach
- modals.js
- production-readiness-audit-2026-09-14.md
- copilot-instructions.md
- config.js
- api-client.js
- keyboard.js
- realtime.js
- router.js
- template-loader.js
- utils.js
- access-management.js
- access-requests.js
- activity-logs.js
- all-logs.js
- audit-logs.js
- automation-rules.js
- cost-management.js
- environments.js
- features.js
- locks.js
- system-health.js
- user-management.js
- vm-operations.js
- vm-registry.js
- charts.js
- forms.js
- loading.js
- notification-bell.js
- notifications.js
- pagination.js
- sidebar.js
- user-menu.js

## God Nodes (most connected - your core abstractions)
1. `Vm` - 187 edges
2. `User` - 160 edges
3. `Environment` - 149 edges
4. `E()` - 135 edges
5. `VmRepository` - 108 edges
6. `AuditService` - 97 edges
7. `EnvironmentAccess` - 88 edges
8. `VmGroup` - 88 edges
9. `$` - 84 edges
10. `AuditAction` - 83 edges

## Surprising Connections (you probably didn't know these)
- `Auth` --indirect_call--> `logout()`  [INFERRED]
  src/main/resources/static/js/core/auth.js → test/fixtures/auth.ts
- `gr()` --indirect_call--> `_e()`  [INFERRED]
  src/main/resources/static/vendor/html2canvas/html2canvas.min.js → src/main/resources/static/vendor/bootstrap/js/bootstrap.bundle.min.js
- `vt()` --indirect_call--> `P()`  [INFERRED]
  src/main/resources/static/vendor/jquery/jquery-3.6.0.min.js → src/main/resources/static/vendor/echarts/echarts.min.js
- `Ss()` --indirect_call--> `ws()`  [INFERRED]
  src/main/resources/static/vendor/echarts/echarts.min.js → src/main/resources/static/vendor/html2canvas/html2canvas.min.js
- `rf()` --indirect_call--> `KB()`  [INFERRED]
  src/main/resources/static/vendor/echarts/echarts.min.js → src/main/resources/static/vendor/html2canvas/html2canvas.min.js

## Import Cycles
- None detected.

## Communities (211 total, 64 thin omitted)

### Community 0 - "echarts.min.js"
Cohesion: 0.02
Nodes (154): Ab(), aR(), at(), au(), aw(), Ax(), bB(), bo() (+146 more)

### Community 1 - "org.junit.jupiter.api.Test"
Cohesion: 0.04
Nodes (22): org.junit.jupiter.api.AfterEach, org.junit.jupiter.api.Test, org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc, org.springframework.boot.test.context.SpringBootTest, org.springframework.jdbc.core.JdbcTemplate, org.springframework.security.test.context.support.WithMockUser, org.springframework.test.context.jdbc.Sql, org.springframework.test.web.servlet.MockMvc (+14 more)

### Community 2 - "VmInventorySnapshot"
Cohesion: 0.03
Nodes (10): VmInventoryDTO, VolumeDTO, Entity, PrePersist, Table, VmInventorySnapshot, Entity, PrePersist (+2 more)

### Community 3 - "org.junit.jupiter.api.DisplayName"
Cohesion: 0.06
Nodes (7): org.junit.jupiter.api.DisplayName, Override, Override, Timestamp, EnvironmentAccessService, EnvironmentAccessControllerTest, EnvironmentAccessServiceTest

### Community 4 - "a"
Cohesion: 0.08
Nodes (88): aA(), c(), p(), An(), ba(), bf(), u(), bl() (+80 more)

### Community 5 - "User"
Cohesion: 0.05
Nodes (10): org.springframework.test.context.ActiveProfiles, Entity, Override, Table, Timestamp, User, UserRepository, VmStateHistoryDTOTest (+2 more)

### Community 6 - "EnvironmentAccessRequest"
Cohesion: 0.04
Nodes (11): EnvironmentAccessRequestDTO, AccessRequestStatus, APPROVED, CANCELLED, DENIED, PENDING, fromValue(), EnvironmentAccessRequest (+3 more)

### Community 7 - "i"
Cohesion: 0.06
Nodes (79): ac(), Al(), n(), B(), bG(), r(), br(), c() (+71 more)

### Community 8 - "U"
Cohesion: 0.04
Nodes (82): Ad(), ag(), Am(), ao(), ay(), bk(), bv(), Cr() (+74 more)

### Community 9 - "VmStatus"
Cohesion: 0.04
Nodes (14): VmStateHistoryDTO, Builder, Entity, Table, VmStateHistory, VmStatus, ERROR, NOT_FOUND (+6 more)

### Community 10 - "html2canvas.min.js"
Cohesion: 0.03
Nodes (28): an(), Be(), Cs(), dA(), ee(), FA(), fe(), gs() (+20 more)

### Community 11 - "org.springframework.data.domain.Page"
Cohesion: 0.07
Nodes (10): org.springframework.data.domain.Page, org.springframework.data.domain.Pageable, AuditController, GetMapping, RequestMapping, RestController, VmCostDetailDTO, AuditLogRepository (+2 more)

### Community 12 - "LockHistory"
Cohesion: 0.05
Nodes (10): com.fasterxml.jackson.annotation.JsonProperty, LockHistoryDTO, LockAction, ACQUIRED, BROKEN, EXPIRED, RELEASED, Entity (+2 more)

### Community 13 - "org.springframework.context.annotation.Bean"
Cohesion: 0.05
Nodes (37): AuthorizedClientServiceOAuth2AuthorizedClientManager, jakarta.servlet.FilterChain, jakarta.servlet.http.HttpServletRequest, jakarta.servlet.http.HttpServletResponse, org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy, org.springframework.boot.autoconfigure.SpringBootApplication, org.springframework.boot.env.EnvironmentPostProcessor, org.springframework.boot.SpringApplication (+29 more)

### Community 14 - "org.springframework.security.access.prepost.PreAuthorize"
Cohesion: 0.07
Nodes (18): org.apache.poi.ss.usermodel.CellStyle, org.springframework.security.access.prepost.PreAuthorize, org.springframework.web.bind.annotation.GetMapping, org.springframework.web.bind.annotation.PostMapping, org.springframework.web.bind.annotation.RequestMapping, org.springframework.web.bind.annotation.RestController, CostManagementController, Timestamp (+10 more)

### Community 15 - "$"
Cohesion: 0.08
Nodes (50): $, ae(), Be(), Bt(), Ce(), D(), _e(), ee() (+42 more)

### Community 16 - "java.sql.Timestamp"
Cohesion: 0.04
Nodes (7): java.sql.Timestamp, EmailLogDTO, IdleWasteRowDTO, Entity, PrePersist, Table, VmMetricHourly

### Community 17 - "AccessLevel"
Cohesion: 0.05
Nodes (11): CreateAccessRequestDTO, GrantAccessDTO, UpdateAccessGrantDTO, AccessLevel, ADMIN, USER, VIEWER, fromValue() (+3 more)

### Community 18 - "E"
Cohesion: 0.04
Nodes (59): az(), Cl(), Cm(), ct(), di(), Dk(), E(), el() (+51 more)

### Community 19 - "io.swagger.v3.oas.annotations.responses.ApiResponses"
Cohesion: 0.06
Nodes (13): io.swagger.v3.oas.annotations.responses.ApiResponses, DeleteMapping, GetMapping, PatchMapping, PostMapping, RequestMapping, RestController, UserController (+5 more)

### Community 20 - "VmRepository"
Cohesion: 0.08
Nodes (12): CircularDependencyException, ResourceNotFoundException, UnauthorizedException, ValidationException, EnvironmentRepository, EnvironmentGroupCounts, VmGroupRepository, VmRepository (+4 more)

### Community 21 - "VmMetricSample"
Cohesion: 0.06
Nodes (20): RecommendationDTO, EnvironmentInsightsDTO, GroupInsightDTO, RecommendationDTO, VmInsightRowDTO, VmMetricSeriesDTO, Entity, PrePersist (+12 more)

### Community 23 - "org.slf4j.Logger"
Cohesion: 0.12
Nodes (21): org.slf4j.Logger, org.springframework.boot.autoconfigure.condition.ConditionalOnProperty, org.springframework.scheduling.annotation.Scheduled, org.springframework.stereotype.Component, AccessExpirationScheduler, ActualCostIngestionScheduler, AutomationRuleScheduler, CostSnapshotScheduler (+13 more)

### Community 24 - "AuditAction"
Cohesion: 0.04
Nodes (54): AuditAction, ACCESS_DENIED, ACCESS_GRANTED, ACCESS_LEVEL_CHANGED, ACCESS_REQUESTED, ACCESS_REVOKED, AUTOMATION_RULE_CREATED, AUTOMATION_RULE_DELETED (+46 more)

### Community 25 - ".fetchInventory"
Cohesion: 0.06
Nodes (8): InstanceTypeInfo, software.amazon.awssdk.services.ec2.Ec2Client, AwsCloudInventoryProviderService, Instance, Override, VmInventoryData, VmVolumeData, Volume

### Community 26 - "EnvironmentAccessDTO"
Cohesion: 0.06
Nodes (10): EnvironmentAccessDTO, AccessInitiation, DIRECT, REQUEST, AccessStatus, ACTIVE, EXPIRED, PENDING (+2 more)

### Community 27 - "Vm"
Cohesion: 0.08
Nodes (4): Entity, Table, Vm, VmService

### Community 28 - "AuditLog"
Cohesion: 0.06
Nodes (5): AuditLog, Entity, Table, Builder, AuditServiceTest

### Community 30 - "VmIdleSummary"
Cohesion: 0.06
Nodes (7): PreUpdate, VmUtilizationSummaryDTO, Entity, PrePersist, Table, Timestamp, VmIdleSummary

### Community 31 - "org.springframework.data.jpa.repository.JpaRepository"
Cohesion: 0.09
Nodes (14): org.springframework.data.jpa.repository.JpaRepository, org.springframework.data.jpa.repository.Lock, org.springframework.stereotype.Repository, AutomationRuleRepository, EmailLogRepository, EnvironmentLockRepository, LockHistoryRepository, ScheduledJobLockRepository (+6 more)

### Community 32 - "NotificationService"
Cohesion: 0.10
Nodes (3): Timestamp, NotificationService, NotificationServiceTest

### Community 33 - "jquery-3.6.0.min.js"
Cohesion: 0.08
Nodes (35): De(), A(), at(), b(), be(), ce(), e(), Ee() (+27 more)

### Community 34 - "VmMetricSampleArchive"
Cohesion: 0.06
Nodes (5): Entity, PrePersist, Table, Timestamp, VmMetricSampleArchive

### Community 35 - "org.springframework.http.ResponseEntity"
Cohesion: 0.08
Nodes (18): org.springframework.http.converter.HttpMessageNotReadableException, org.springframework.http.ResponseEntity, org.springframework.security.access.AccessDeniedException, org.springframework.web.bind.annotation.ExceptionHandler, org.springframework.web.bind.annotation.RestControllerAdvice, org.springframework.web.bind.MethodArgumentNotValidException, org.springframework.web.bind.MissingServletRequestParameterException, AutomationRuleController (+10 more)

### Community 36 - "DashboardSummaryService"
Cohesion: 0.09
Nodes (13): ChartPointDTO, CoverageDTO, DashboardSummaryDTO, EnvironmentCardDTO, RiskComplianceDTO, SchedulerHealthDTO, StorageDTO, SummaryDTO (+5 more)

### Community 37 - "OperationExecutionDTO"
Cohesion: 0.06
Nodes (5): OperationExecutionDTO, OperationType, RESTART, START, STOP

### Community 38 - "OperationDetail"
Cohesion: 0.07
Nodes (4): Entity, Table, OperationDetail, OperationDetailRepository

### Community 39 - "VmGroupDTO"
Cohesion: 0.07
Nodes (8): DeleteMapping, GetMapping, PostMapping, PutMapping, RequestMapping, RestController, VmGroupController, VmGroupDTO

### Community 41 - "EksCloudProviderService"
Cohesion: 0.12
Nodes (9): DescribeNodegroupResponse, NodegroupStatus, software.amazon.awssdk.services.eks.EksClient, EksCloudProviderService, Nodegroup, Override, EksCloudProviderServiceTest, Nodegroup (+1 more)

### Community 42 - "org.springframework.data.jpa.repository.Query"
Cohesion: 0.09
Nodes (8): org.springframework.data.jpa.repository.Modifying, org.springframework.data.jpa.repository.Query, EnvironmentAccess, Entity, Table, Timestamp, EnvironmentAccessRepository, NotificationRepository

### Community 43 - "ReservationCoverageSnapshot"
Cohesion: 0.09
Nodes (7): software.amazon.awssdk.services.costexplorer.model.DateInterval, Entity, PrePersist, Table, ReservationCoverageSnapshot, ReservationCoverageSnapshotRepository, ReservationCoverageService

### Community 45 - "VmGroup"
Cohesion: 0.08
Nodes (6): CreateVmGroupDTO, Entity, Table, Transient, VmGroup, VmGroupService

### Community 47 - "WeeklyReportService"
Cohesion: 0.13
Nodes (4): WeeklyCostReportRowDTO, EnvironmentCostTotal, WeeklyReportService, WeeklyReportServiceTest

### Community 48 - "remove"
Cohesion: 0.07
Nodes (4): pi, remove(), Ui, W

### Community 49 - "AuditReportDTO"
Cohesion: 0.06
Nodes (3): AuditReportDTO, EnvironmentActivityDTO, EnvironmentActivitySummary

### Community 50 - "VmMetricsDTO"
Cohesion: 0.07
Nodes (3): IdleDTO, SampleDTO, VmMetricsDTO

### Community 51 - "Environment"
Cohesion: 0.13
Nodes (3): Environment, Entity, Table

### Community 52 - "Ec2ControllerTest"
Cohesion: 0.11
Nodes (7): io.swagger.v3.oas.annotations.media.Schema, Ec2InstanceActionResponse, Ec2InstanceInfo, Ec2InstanceStatus, Ec2Service, Instance, Ec2ControllerTest

### Community 54 - "WeeklyOptimizationSnapshot"
Cohesion: 0.10
Nodes (11): RightsizingCandidateDTO, WeeklyOptimizationReportType, IDLE_WASTE, RIGHTSIZING, Entity, PrePersist, Table, WeeklyOptimizationSnapshot (+3 more)

### Community 56 - "AwsCloudProviderServiceTest"
Cohesion: 0.15
Nodes (7): DescribeInstancesResponse, DescribeInstanceStatusResponse, Ec2Exception, AwsCloudProviderServiceTest, InstanceStateName, StartInstancesResponse, StopInstancesResponse

### Community 57 - "EnvironmentService"
Cohesion: 0.12
Nodes (4): CreateEnvironmentDTO, EnvironmentCounts, EnvironmentService, EnvironmentServiceTest

### Community 58 - "OperationExecution"
Cohesion: 0.07
Nodes (11): ExecutionStatus, CANCELLED, COMPLETED, FAILED, IN_PROGRESS, PARTIAL_SUCCESS, PENDING, Entity (+3 more)

### Community 59 - "auth.ts"
Cohesion: 0.08
Nodes (29): DOM, ES2022, node, node_modules, playwright-report, @playwright/test, test-results, **/*.ts (+21 more)

### Community 60 - "AccessGrantRequestDTO"
Cohesion: 0.13
Nodes (3): PostMapping, AccessGrantRequestDTO, InitialGrant

### Community 61 - "AutomationRuleRequestDTO"
Cohesion: 0.07
Nodes (4): AutomationRuleRequestDTO, AccessGrantMode, ACCESS_APPROVED, LOCK_ACQUIRE

### Community 62 - "UserService"
Cohesion: 0.09
Nodes (8): io.swagger.v3.oas.annotations.tags.Tag, org.springframework.http.HttpStatus, AccessGrantController, DeleteMapping, PatchMapping, RequestMapping, RestController, UserService

### Community 63 - "EmailLog"
Cohesion: 0.11
Nodes (6): org.springframework.mail.javamail.JavaMailSender, EmailLog, Entity, Table, EmailService, EmailServiceTest

### Community 64 - ".getCurrentUserId"
Cohesion: 0.10
Nodes (12): EnvironmentAccessController, DeleteMapping, GetMapping, PostMapping, RequestMapping, RestController, GetMapping, PatchMapping (+4 more)

### Community 65 - "EnvironmentLock"
Cohesion: 0.07
Nodes (3): EnvironmentLock, Entity, Table

### Community 66 - "org.springframework.stereotype.Service"
Cohesion: 0.09
Nodes (12): org.springframework.stereotype.Service, CloudProvider, AWS, AWS_EKS, AZURE, GCP, OCI, StateSyncScheduler (+4 more)

### Community 67 - "VmMetricData"
Cohesion: 0.09
Nodes (12): software.amazon.awssdk.services.cloudwatch.CloudWatchClient, AwsCloudMetricsProviderService, Override, MetricSpec, CPU, DISK_READ, DISK_WRITE, MEMORY (+4 more)

### Community 68 - ".onboard"
Cohesion: 0.13
Nodes (6): DirectoryUserDTO, OnboardUserDTO, UserAlreadyExistsException, OnboardResult, UserOnboardingService, UserOnboardingServiceTest

### Community 69 - "CostDailySnapshotRepository"
Cohesion: 0.10
Nodes (8): CostDailySnapshot, Entity, PrePersist, Table, CostDailySnapshotRepository, Accumulator, CostReconciliationService, CostReconciliationServiceTest

### Community 70 - ".hide"
Cohesion: 0.09
Nodes (3): Nn, zi, ji()

### Community 71 - "xn"
Cohesion: 0.12
Nodes (6): ft, getElementFromSelector(), getSelectorFromElement(), xn, Tn(), ht()

### Community 73 - "GraphDirectoryService"
Cohesion: 0.12
Nodes (9): com.fasterxml.jackson.annotation.JsonIgnoreProperties, org.springframework.beans.factory.ObjectProvider, org.springframework.test.web.client.MockRestServiceServer, org.springframework.web.client.RestClient, DirectoryLookupException, GraphDirectoryService, GraphUser, GraphUsersResponse (+1 more)

### Community 74 - "OAuth2IntegrationTest"
Cohesion: 0.12
Nodes (10): org.springframework.security.authentication.AbstractAuthenticationToken, org.springframework.security.core.GrantedAuthority, org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest, org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService, org.springframework.security.oauth2.core.oidc.user.OidcUser, CustomOAuth2UserService, Override, Override (+2 more)

### Community 75 - "io.swagger.v3.oas.annotations.Operation"
Cohesion: 0.13
Nodes (11): io.swagger.v3.oas.annotations.Operation, Ec2Controller, GetMapping, PostMapping, RequestMapping, RestController, GetMapping, PostMapping (+3 more)

### Community 76 - "org.springframework.transaction.annotation.Transactional"
Cohesion: 0.10
Nodes (4): jakarta.persistence.Entity, jakarta.persistence.Table, org.springframework.transaction.annotation.Transactional, ScheduledJobLock

### Community 77 - "VmMgmtController"
Cohesion: 0.12
Nodes (9): DeleteMapping, GetMapping, PostMapping, PutMapping, RequestMapping, RestController, VmGroupWithVmsDTO, VmMgmtController (+1 more)

### Community 79 - "NotificationType"
Cohesion: 0.07
Nodes (23): NotificationDTO, NotificationType, ACCESS_EXPIRED, ACCESS_EXPIRING, ACCESS_GRANTED, ACCESS_LEVEL_CHANGED, ACCESS_REQUEST_APPROVED, ACCESS_REQUEST_DENIED (+15 more)

### Community 80 - "EksSyncServiceTest"
Cohesion: 0.24
Nodes (3): software.amazon.awssdk.services.eks.model.Nodegroup, EksSyncChanges, EksSyncServiceTest

### Community 81 - "Notification"
Cohesion: 0.10
Nodes (3): Entity, Table, Notification

### Community 82 - "AutomationRule"
Cohesion: 0.12
Nodes (3): AutomationRule, Entity, Table

### Community 84 - "VmMetricDaily"
Cohesion: 0.10
Nodes (4): Entity, PrePersist, Table, VmMetricDaily

### Community 85 - "AwsCloudProviderService"
Cohesion: 0.15
Nodes (8): InstanceStatus, AwsCloudProviderService, Instance, InstanceStateName, Override, StartReadiness, StatusCheckCount, SuppressWarnings

### Community 86 - "AutomationRuleDTO"
Cohesion: 0.08
Nodes (5): AutomationRuleDTO, AutomationRunStatus, FAILED, SKIPPED, SUCCESS

### Community 88 - "EnvironmentController"
Cohesion: 0.11
Nodes (8): EnvironmentController, DeleteMapping, GetMapping, PostMapping, PutMapping, RequestMapping, RestController, EksClusterInfoDTO

### Community 89 - "CostForecastService"
Cohesion: 0.15
Nodes (6): CostForecastDTO, CostForecastPointDTO, DailyCostTotal, CostForecastService, Regression, CostForecastServiceTest

### Community 92 - ".buildBundles"
Cohesion: 0.13
Nodes (5): InstanceTypeProjection, CpuStats, CostDataProvider, VmCostEstimate, VmCostBundle

### Community 93 - "_l"
Cohesion: 0.16
Nodes (23): ap(), cp(), dp(), Dy(), ep(), fp(), hp(), I() (+15 more)

### Community 94 - "ci"
Cohesion: 0.11
Nodes (3): ci, Ze(), Qe()

### Community 95 - "gr"
Cohesion: 0.09
Nodes (24): bt(), cc(), et(), gp(), Ik(), ir(), it(), kl() (+16 more)

### Community 97 - "CostEstimationServiceTest"
Cohesion: 0.18
Nodes (6): software.amazon.awssdk.services.computeoptimizer.ComputeOptimizerClient, software.amazon.awssdk.services.computeoptimizer.model.GetEc2InstanceRecommendationsResponse, ComputeOptimizerService, Recommendation, ComputeOptimizerServiceTest, CostEstimationServiceTest

### Community 98 - "VmMetricsService"
Cohesion: 0.13
Nodes (3): CloudMetricsProviderFactory, CloudMetricsProviderService, VmMetricsService

### Community 101 - "Iw"
Cohesion: 0.14
Nodes (22): A(), bi(), Cw(), Dw(), Ew(), fi(), hi(), hw() (+14 more)

### Community 102 - "scripts"
Cohesion: 0.09
Nodes (21): dotenv, @playwright/test, description, devDependencies, dotenv, @playwright/test, @types/node, typescript (+13 more)

### Community 103 - "CostExplorerBillingService"
Cohesion: 0.18
Nodes (5): software.amazon.awssdk.services.costexplorer.model.GetCostAndUsageResponse, software.amazon.awssdk.services.costexplorer.model.Group, CostExplorerBillingService, IngestResult, CostExplorerBillingServiceTest

### Community 106 - ".acquireLock"
Cohesion: 0.15
Nodes (6): GetMapping, PostMapping, RequestMapping, RestController, LockController, AcquireLockDTO

### Community 108 - "Ni"
Cohesion: 0.15
Nodes (4): d(), Ni, ki(), Li()

### Community 109 - "org.junit.jupiter.api.BeforeEach"
Cohesion: 0.24
Nodes (4): org.junit.jupiter.api.BeforeEach, org.junit.jupiter.api.extension.ExtendWith, org.mockito.junit.jupiter.MockitoExtension, software.amazon.awssdk.services.eks.model.NodegroupStatus

### Community 110 - "N"
Cohesion: 0.17
Nodes (5): App, Dashboard, N(), parents(), fn()

### Community 111 - "wA"
Cohesion: 0.18
Nodes (18): A(), B(), n(), r(), B(), cn(), E(), H() (+10 more)

### Community 113 - "Access Grants — Requirement (admin-initiated + group-level access)"
Cohesion: 0.12
Nodes (17): 10. Visibility rules, 12. The lock (explicit limitation for v1), 13. Notifications & audit, 14. Convergence of grant + approve (F3), 15. Migration & rollout, 16. Test plan (outline), 17. Out of scope for v1 (v2 backlog), 18. Decisions (locked 2026-09-09) (+9 more)

### Community 117 - "VmInventorySnapshotRepository"
Cohesion: 0.27
Nodes (4): VmInventorySnapshotRepository, VmVolumeSnapshotRepository, EstimatedCostProvider, VmInventoryService

### Community 118 - "CloudProviderService"
Cohesion: 0.19
Nodes (4): FunctionalInterface, CloudProviderService, OperationProgressListener, VmOperationResult

### Community 119 - ".estimateCosts"
Cohesion: 0.20
Nodes (3): VolumeSizeTotal, Override, EstimatedCostProviderTest

### Community 120 - "AGENTS.md"
Cohesion: 0.14
Nodes (12): Architecture, Build And Run Commands, Cloud Provider Pattern, Codex Working Notes, Database, Frontend, graphify, Internal Docs (+4 more)

### Community 121 - "com.fasterxml.jackson.databind.ObjectMapper"
Cohesion: 0.22
Nodes (4): com.fasterxml.jackson.databind.ObjectMapper, java.util.regex.Pattern, TeamResolver, VmDiscoveryService

### Community 122 - "Review Checklist"
Cohesion: 0.14
Nodes (13): Authentication, Authorization, Constraints, Credentials, Infrastructure, Input Validation, Key Security Files, Known Security Gaps (+5 more)

### Community 123 - "Design Principles for This Project"
Cohesion: 0.14
Nodes (13): 1. Status Visibility, 2. Destructive Action Safety, 3. Real-Time Feedback, 4. Empty & Error States, 5. Responsive Priorities, Constraints, Design Principles for This Project, Design System (+5 more)

### Community 126 - "Onboard a directory user from the admin panel (Microsoft Graph)"
Cohesion: 0.15
Nodes (11): 10. v2 backlog, 1. Problem, 4. Data model, 6. Frontend — User Management admin panel, 7. Config keys, 8. Security / resilience notes, 9. Build plan — one commit per step, As shipped (+3 more)

### Community 127 - "Review Dimensions"
Cohesion: 0.15
Nodes (12): 1. Architecture Compliance, 2. API Quality, 3. Business Logic, 4. Code Quality, 5. Testing, 6. Database, 7. Frontend (if applicable), Constraints (+4 more)

### Community 128 - "PricingReferenceService"
Cohesion: 0.31
Nodes (3): jakarta.annotation.PostConstruct, PriceLookupResult, PricingReferenceService

### Community 130 - "qn"
Cohesion: 0.18
Nodes (13): BD(), Bs(), a(), Fn(), gs(), Hn(), ii(), jn() (+5 more)

### Community 131 - "CLAUDE.md"
Cohesion: 0.17
Nodes (10): Architecture Overview, Authentication & Authorization, Build & Run Commands, Database & Migrations, Frontend, graphify, Internal Docs, Key Domain Concepts (+2 more)

### Community 132 - ".login"
Cohesion: 0.20
Nodes (3): HttpServletResponse, Override, LoginRequest

### Community 133 - "VmOperationsController"
Cohesion: 0.24
Nodes (5): GetMapping, PostMapping, RequestMapping, RestController, VmOperationsController

### Community 134 - "AccessScopeType"
Cohesion: 0.20
Nodes (3): AccessScopeType, ENVIRONMENT, GROUP

### Community 136 - "Low Priority (design-system hygiene)"
Cohesion: 0.17
Nodes (12): Low Priority (design-system hygiene), `- [x]` TASK-046 — Sweep hardcoded hex colors that duplicate existing CSS variables, `- [x]` TASK-047 — Unify the two muted-danger red hex values, `- [x]` TASK-048 — Remove the dead duplicate `.metric-card:hover` rule, `- [x]` TASK-049 — Decide the adoption path for `.table-baseline`, `- [x]` TASK-050 — Update or remove the stale `css/index.css` documentation file, `- [x]` TASK-051 — Extract shared scrollbar-thumb styling into one rule, `- [x]` TASK-052 — Document a formal z-index scale (+4 more)

### Community 137 - "AuthenticationService"
Cohesion: 0.24
Nodes (4): org.springframework.security.crypto.password.PasswordEncoder, org.springframework.security.web.context.SecurityContextRepository, AuthController, AuthenticationService

### Community 138 - "Wt"
Cohesion: 0.25
Nodes (11): be(), bh(), Fh(), Ie(), Se(), vi(), vn(), we() (+3 more)

### Community 139 - "generate_v4_migration.py"
Cohesion: 0.27
Nodes (9): escape_sql_string(), generate_v4_migration(), main(), normalize_name(), parse_vm_master(), Parse vm-master.sql and extract VM records, Escape single quotes for SQL, Convert name to lowercase-hyphenated format (+1 more)

### Community 140 - "database-engineer.agent.md"
Cohesion: 0.20
Nodes (9): Constraints, Current Schema (20+ tables, 4 migrations), Database Stack, JPA Entity Template, Migration Template, Naming Conventions, Output Format, Project Context (+1 more)

### Community 141 - "frontend-engineer.agent.md"
Cohesion: 0.20
Nodes (9): API Access, Constraints, File Structure, Frontend Stack, Module Pattern (Mandatory), Naming Rules, Output Format, Project Context (+1 more)

### Community 142 - "AutomationTriggerType"
Cohesion: 0.20
Nodes (3): AutomationTriggerType, ACCESS_GRANT, SCHEDULE

### Community 145 - "High Priority"
Cohesion: 0.20
Nodes (7): Decisions Needed (not pure engineering tasks), High Priority, UX Remediation Task List, `- [x]` TASK-034 — Wire up `DestructiveConfirm` to real destructive actions (or remove it), `- [x]` TASK-035 — Reconcile Dashboard's status colors with the app's design tokens, `- [x]` TASK-036 — Give "Stopped" its own color in `config.js` instead of reusing danger/error red, `- [x]` TASK-037 — Repalette the notification bell dropdown and fix its broken `var()` references

### Community 146 - "UI Consistency Governance — Task List"
Cohesion: 0.20
Nodes (10): Suggested order, `- [ ]` TASK-057 — Build the living component reference page, `- [ ]` TASK-058 — Add a `Utils.cssVar(name)` helper bridging CSS custom properties into JS, `- [ ]` TASK-059 — Migrate `config.js`'s `VM_STATUS_CONFIG` colors onto the bridge, `- [ ]` TASK-060 — Migrate `dashboard.js`'s chart-color arrays onto the bridge, `- [ ]` TASK-061 — Promote frequently-reused unnamed colors to real CSS variables, `- [ ]` TASK-062 — Add the consistency checklist to the PR/review workflow, `- [ ]` TASK-063 (optional) — Evaluate a lightweight lint check for hardcoded colors/font-sizes (+2 more)

### Community 147 - "Settings Page — Requirement (brainstorm, not yet built)"
Cohesion: 0.22
Nodes (8): Context, Proposed architecture (not yet built), Settings Page — Requirement (brainstorm, not yet built), Still to decide before building, The core design fork: not everything is equally safe to make live, Tier 1 — Truly runtime-editable, no restart needed, Tier 2 — Not secrets, but need a restart or don't make sense live, Tier 3 — Secrets (excluded per explicit request)

### Community 148 - "AutomationScopeType"
Cohesion: 0.22
Nodes (4): AutomationScopeType, ENVIRONMENT, GROUP, VM

### Community 152 - "E2E tests (Playwright) — TCG VM Self-Service Platform"
Cohesion: 0.22
Nodes (8): 1. Prerequisites, 2. Install, 3. Configure, 4. Run, 5. Layout, 6. What this does and doesn't cover, 7. Extending, E2E tests (Playwright) — TCG VM Self-Service Platform

### Community 153 - "Medium Priority"
Cohesion: 0.22
Nodes (9): Medium Priority, `- [x]` TASK-038 — Fix the `.content-header h1` responsive cascade bug (mobile renders larger than desktop), `- [x]` TASK-039 — Delete or finish the dead `Modals.showRegisterVm` / `Modals.showCreateGroup` builders, `- [x]` TASK-040 — Reconcile or remove the dead Audit Log Details template, `- [x]` TASK-041 — Unify status/badge shape (pill vs. Bootstrap-default rectangle), `- [x]` TASK-042 — Wire up or remove the dead top-nav search box, `- [x]` TASK-043 — Decide and fix Login page asset sourcing (CDN vs. local vendor), `- [x]` TASK-044 — Load the "Inter" font family used by charts, or drop it from the stack (+1 more)

### Community 154 - "backend-engineer.agent.md"
Cohesion: 0.25
Nodes (7): Architecture Rules (Non-Negotiable), Constraints, Output Format, Package Layout, Project Context, When Implementing a Feature, Your Expertise

### Community 155 - "cloud-architect.agent.md"
Cohesion: 0.25
Nodes (7): Constraints, Current State, Key Files, Output Format, Project Context, When Implementing a New Cloud Provider, Your Expertise

### Community 157 - "te"
Cohesion: 0.32
Nodes (8): ce(), ee(), er(), n(), he(), le(), te(), ue()

### Community 158 - "Part C — Per-Page Notes"
Cohesion: 0.25
Nodes (8): Access Management / User Management (admin), Audit Logs (All) vs. My Activity Logs, Cost Management / Automation Rules, Dashboard (default landing page), Login, My Environments / Environment Detail, Part C — Per-Page Notes, VM Registry (admin)

### Community 159 - "VM Self-Service Platform — UX & Visual Design Review"
Cohesion: 0.25
Nodes (8): Appendix — Raw Value Inventories, Colors observed in `--*-color`/status usage across the app, by "family", Font-size fallback values referencing `--table-body-font` (should all be `0.81rem`), How to read this, `.metric-card .metric-value` size by page (base class = `2rem`, `cards.css:29`), Part D — What's Working Well, Part E — Prioritized Recommendations, VM Self-Service Platform — UX & Visual Design Review

### Community 162 - "Part B — Cross-Cutting Design System Findings"
Cohesion: 0.29
Nodes (7): B.1 Color, B.2 Typography, B.3 Buttons, Badges & Modals, B.4 Structural / Dead Code, B.5 Responsive & Accessibility (brief — not the primary ask, noted for completeness), B.6 Pagination, Alerts & Toasts, Part B — Cross-Cutting Design System Findings

### Community 163 - "AWS IAM Permissions"
Cohesion: 0.33
Nodes (5): AWS IAM Permissions, Full permission table, How to verify this yourself (authoritative — better than anything below), Practical takeaway, Status legend

### Community 164 - "Part A — Application Map"
Cohesion: 0.33
Nodes (6): A.1 Top Navigation (`index.html` lines 51–110), A.2 Sidebar Menus (`index.html` lines 112–284), A.3 Mobile Bottom Nav (`index.html` lines 653–670), A.4 Pages (routed into `#content-area`), A.5 Modals & Dialogs (catalogued by where they live), Part A — Application Map

### Community 165 - "11. UI / UX"
Cohesion: 0.40
Nodes (5): 11.1 Grant / Manage Access modal (redesign of the existing one), 11.2 Entry points, 11.3 Existing-grant management, 11.4 "My Access", 11. UI / UX

### Community 167 - "6. Data model"
Cohesion: 0.50
Nodes (4): 6.1 Add scope columns to `environment_access` (V20), 6.2 Extend the request entity, 6.3 Why keep `environment_id` on every row, 6. Data model

### Community 168 - "8. API changes"
Cohesion: 0.50
Nodes (4): 8.1 New / changed grant endpoints, 8.2 Operation authorization (the enforcement that gives group access teeth), 8.3 Read endpoints that must filter to visible groups, 8. API changes

### Community 169 - "3. Architecture"
Cohesion: 0.50
Nodes (4): 3.1 Graph authentication — no new dependency, 3.2 Ops prerequisite (not code), 3.3 Feature flag, 3. Architecture

### Community 170 - "5. API"
Cohesion: 0.50
Nodes (4): 5.1 `GET /api/v1/directory/search`, 5.2 `POST /api/v1/users`, 5.3 First-login adoption (`UserService.findOrCreateUser`), 5. API

### Community 171 - "gradlew"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 172 - "playwright"
Cohesion: 0.50
Nodes (3): npx, playwright, @playwright/mcp

### Community 173 - "2. Chosen approach"
Cohesion: 0.67
Nodes (3): 2.1 Locked decisions, 2.2 Non-goals (v1), 2. Chosen approach

## Knowledge Gaps
- **403 isolated node(s):** `npx`, `@playwright/mcp`, `RecommendationDTO`, `LOCK_ACQUIRE`, `ACCESS_APPROVED` (+398 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **64 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vm` connect `Vm` to `org.junit.jupiter.api.Test`, `VmInventorySnapshot`, `VmStatus`, `java.sql.Timestamp`, `VmRepository`, `VmMetricSample`, `org.slf4j.Logger`, `VmDTO`, `VmIdleSummary`, `org.springframework.data.jpa.repository.JpaRepository`, `VmMetricSampleArchive`, `DashboardSummaryService`, `OperationDetail`, `EksCloudProviderService`, `.registerVm`, `VmGroup`, `UserService`, `org.springframework.stereotype.Service`, `CostDailySnapshotRepository`, `org.springframework.transaction.annotation.Transactional`, `EksSyncServiceTest`, `.startOperation`, `VmMetricDaily`, `.upsertNodeGroup`, `.buildBundles`, `.acquireLock`, `CostEstimationServiceTest`, `VmMetricsService`, `.computeRuntimeHours`, `.getVmId`, `org.junit.jupiter.api.BeforeEach`, `.getGroupId`, `VmInventorySnapshotRepository`, `.estimateCosts`, `com.fasterxml.jackson.databind.ObjectMapper`, `TagReconciliationServiceTest`?**
  _High betweenness centrality (0.037) - this node is a cross-community bridge._
- **Why does `VmStatus` connect `VmStatus` to `org.junit.jupiter.api.Test`, `VmRepository`, `VmOperationProgress`, `Vm`, `VmDTO`, `org.springframework.data.jpa.repository.JpaRepository`, `DashboardSummaryService`, `OperationExecutionDTO`, `EnvironmentVmCounts`, `EksCloudProviderService`, `org.springframework.stereotype.Service`, `VmMgmtController`, `EksSyncServiceTest`, `AwsCloudProviderService`, `VmMetricsService`, `.computeRuntimeHours`, `.getVmId`, `org.junit.jupiter.api.BeforeEach`, `VmInventorySnapshotRepository`, `CloudProviderService`?**
  _High betweenness centrality (0.031) - this node is a cross-community bridge._
- **Why does `OperationDetailDTO` connect `OperationDetailDTO` to `java.sql.Timestamp`, `OperationExecutionDTO`?**
  _High betweenness centrality (0.026) - this node is a cross-community bridge._
- **What connects `npx`, `@playwright/mcp`, `RecommendationDTO` to the rest of the system?**
  _403 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `echarts.min.js` be split into smaller, more focused modules?**
  _Cohesion score 0.015174299384825701 - nodes in this community are weakly interconnected._
- **Should `org.junit.jupiter.api.Test` be split into smaller, more focused modules?**
  _Cohesion score 0.03980716253443526 - nodes in this community are weakly interconnected._
- **Should `VmInventorySnapshot` be split into smaller, more focused modules?**
  _Cohesion score 0.02665507750253513 - nodes in this community are weakly interconnected._