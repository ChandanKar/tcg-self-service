# VM Self-Service Platform — UI & UX Backlog

Turns the approved redesign mockup (74 screens, 19 October 2026) into the product. One developer works through these tasks **in order, top to bottom**. Each task builds only on tasks above it, is mergeable on its own with tests, and takes 0.5–2 days.

**17 epics · 124 tasks · 176 estimated developer-days (about 35 weeks at 5 days a week).**

| Phase | Days | Weeks |
| --- | --- | --- |
| Foundation | 32 | 6 |
| Everyday | 46 | 9 |
| Manage | 38.5 | 8 |
| Admin | 38.5 | 8 |
| Finish | 21 | 4 |

Rules: AWS instance IDs are visible to administrators only, enforced by the backend; IP addresses stay visible to everyone who can view the environment. No horizontal or vertical page scrollbars: every desktop screen fits 1440×900. The side menu is not an accordion. Never edit an existing Flyway migration (use the next free `V` number at implementation time). Mockup screens are named by their board file (for example `EnvDetail.dc.html`).

## Epics

| Epic | Phase | Tasks | Days | Goal |
| --- | --- | --- | --- | --- |
| [U01 Instance IDs for administrators only](#u01) | Foundation | 6 | 8.5 | No AWS instance ID (or other AWS resource ID) reaches anyone but an ADMIN through any API response, message, payload or export, while environment admins can still add VMs from AWS by picking them by name. |
| [U02 Pages people can open](#u02) | Foundation | 5 | 6 | Every page a person can reach by menu or URL either works for their role and the server's switches or says plainly that it is not for them, with no failing background requests, correct help links and named controls. |
| [U03 Visual system](#u03) | Foundation | 4 | 6 | Every page uses the approved TCG identity (navy, gear green, gold, IBM Plex, power strip) from one set of tokens and one theme layer, with no Google font CDN and no competing old palette. |
| [U04 App shell and layout](#u04) | Foundation | 4 | 5.5 | A fixed 56 px top bar with a Help '?' and a fixed 240 px side menu whose sections are always open, plus shared header, pager, tabs and master-detail pieces so every screen fits 1440x900 without page scrollbars. |
| [U05 Data the new screens need](#u05) | Foundation | 6 | 6 | The API returns what Home, the environment list, the environment page, VM details, admin Home and Users need: when each VM entered its state, its instance type, per-group state counts, a 'needs attention' summary and how each person signs in. |
| [U06 Home & environment list](#u06) | Everyday | 6 | 7.5 | People who run environments land on a Home that says what needs them and shows each environment as a card with its power strip, and the Environments list fits up to 15 environments on one screen with search, Running/Stopped filters, access and cost. |
| [U07 Environment page](#u07) | Everyday | 9 | 12.5 | The environment page leads with the start order and the VMs, keeps lock, lease, schedule, idle auto-stop and cost in a side column, shows every alert in one ordered stack, scales to 50+ VMs with a group list, and renders EKS node groups properly, all within 1440x900. |
| [U08 VM details & operations](#u08) | Everyday | 9 | 14 | Starting, stopping and following operations reads as plain steps by group, history explains each run and offers a retry, and VM details, environment insights and idle auto-stop open as tabbed side panels that answer what is running, what it costs and what to do. |
| [U09 Requests, approvals, activity, account](#u09) | Everyday | 9 | 12 | Every everyday self-service screen (sign in, request access, approvals, my activity, my account, notifications, help, Ask, Teams confirm, new environment) matches the approved mockup and fits 1440x900 without page scrollbars. |
| [U10 Access for environment admins](#u10) | Manage | 8 | 11 | Environment admins answer access requests, see and change every grant on their environments from one paged list, and grant many people access to many environments or groups in one step with a preview of what will be new, extended or skipped. |
| [U11 VM registry & environment settings](#u11) | Manage | 9 | 14.5 | Environment admins set up environments in a master-detail registry, add many AWS VMs to a group without seeing instance IDs, manage each environment's settings on one page, and run EKS node groups on Spot from a dedicated page. |
| [U12 Automation, approval policies, budgets, audit log](#u12) | Manage | 8 | 13 | Environment admins see and edit weekly schedules on a week view, set access-triggered rules, read holiday calendars and recent runs, manage approval policies and budgets in plain sentences, and search an audit log that reads like what happened. |
| [U13 People: users, bulk onboarding, teams](#u13) | Admin | 9 | 12 | Administrators add many people at once with role, team, department, project code and access in one step, manage each person through three plain dialogs, and run teams (members, Entra group, environments, monthly limits) from a list and a detail page. |
| [U14 Admin home & platform](#u14) | Admin | 9 | 13 | Administrators land on a Home that lists what needs attention, see platform health at a glance, and manage AWS accounts, API access, settings and environment templates on screens that fit without scrolling. |
| [U15 Cost pages](#u15) | Admin | 9 | 13.5 | Cost management becomes a tabbed page (Overview, Rightsizing, Commitments, Carbon, Unusual spend, plus Setup) with two menus instead of seven buttons, and Savings, Waste with its fixes flow, and Chargeback with shared cost rules fit one screen each. |
| [U16 Phone](#u16) | Finish | 5 | 7.5 | On a 390 px phone people can see their environments, start/stop/extend, approve and check activity through a phone top bar, a four-item bottom bar and a full-screen menu, with no horizontal scrolling anywhere. |
| [U17 States, copy, accessibility & clean-up](#u17) | Finish | 9 | 13.5 | Every page shows the same empty, loading, error, toast, reconnecting and signed-out patterns with plain copy, passes an axe scan on every screen, matches the mockup at 1440x900 without page scrollbars, and carries no leftover code from the old look. |
| [UPL Parking lot](#upl) | Parked | 1 | 0 | Ideas and decisions the product owner deferred. Not scheduled: each item is picked up only after it is discussed. |

## U01

### Instance IDs for administrators only — Foundation

No AWS instance ID (or other AWS resource ID) reaches anyone but an ADMIN through any API response, message, payload or export, while environment admins can still add VMs from AWS by picking them by name.

Sources: VmDetails.dc.html, RegisterVms.dc.html, VmRegistry.dc.html, States.dc.html, BRIEF: Data visibility rule (AWS instance IDs for administrators only) · 6 tasks · 8.5 days

#### U01-T01 — Central rule + VmDTO/VmInventoryDTO masking

- [ ] Done · **Type:** backend · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** VmDetails.dc.html, BRIEF: Data visibility rule

VmDTO.fromEntity (dto/VmDTO.java:59) and VmInventoryDTO.from (dto/VmInventoryDTO.java:30) copy providerVmId for every caller; VmMgmtController GET /vms, /{groupId}/page, /{vmId} and /{vmId}/inventory are isAuthenticated(), so users and viewers receive i-... IDs and EBS volume IDs (VolumeDTO.volumeId). The mockup shows 'Instance ID · Administrators only' on VM details for admins and nothing for others (VmDetails.dc.html). One rule, applied at serialization, keeps every current and future DTO consistent and fails closed when there is no signed-in admin.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/security/ProviderIdVisibility.java` (new) — @Component with static accessor: boolean viewerMaySeeProviderIds() = the current user is ADMIN (UserService.getCurrentUser().isAdmin(), so it also works in the dev X-User-Id mode where the Authentication has no authorities, and for API tokens). Memoised per request in a RequestContextHolder attribute; false when there is no request/authentication (scheduler, async, webhook threads).
- `src/main/java/com/tcgdigital/vmcontrol/dto/json/AdminOnly.java` (new) — @JacksonAnnotationsInside @JsonSerialize(using = AdminOnlySerializer.class) @JsonInclude(NON_EMPTY) annotation for fields/record components.
- `src/main/java/com/tcgdigital/vmcontrol/dto/json/AdminOnlySerializer.java` (new) — No-arg JsonSerializer<Object>: writes the value for admins; isEmpty() returns true for everyone else so the property is omitted (not null). Resolves ProviderIdVisibility through its static holder so it also works with ObjectMappers built outside Spring.
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmDTO.java` (modify) — @AdminOnly on providerVmId (line 27). Add rdsKind ('CLUSTER'|'INSTANCE', null for non-RDS) derived from the 'cluster:' prefix in fromEntity, so the UI no longer parses providerVmId (environments.js:809).
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmInventoryDTO.java` (modify) — @AdminOnly on providerVmId (line 11) and on VolumeDTO.volumeId (line 50); volumes keep deviceName/type/size so the Storage tab can label them 'Root disk' / 'Data disk'.
- `src/main/java/com/tcgdigital/vmcontrol/dto/OperationDetailDTO.java` (modify) — @AdminOnly on cloudOperationId (line 26) - AWS request/update ids are admin diagnostics.
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — No logic change; add a class Javadoc line pointing at the rule so new endpoints reuse VmDTO instead of building maps with providerVmId.

**Steps**

1. Add ProviderIdVisibility (security package) with a static instance set in @PostConstruct and a request-scoped memo of UserService.getCurrentUser().isAdmin(); return false when RequestContextHolder/SecurityContext is empty.
2. Add @AdminOnly + AdminOnlySerializer (dto/json). Override isEmpty(SerializerProvider, value) so non-admin output omits the property; serialize() writes the raw value otherwise.
3. Annotate VmDTO.providerVmId, VmInventoryDTO.providerVmId, VmInventoryDTO.VolumeDTO.volumeId and OperationDetailDTO.cloudOperationId.
4. Add VmDTO.rdsKind (getter/setter, set in fromEntity when provider == AWS_RDS).
5. Write ProviderIdVisibilityTest (unit) and VmProviderIdMaskingTest (SecuredWebTestBase) seeding a VM with providerVmId 'i-0123456789abcdef0' and an inventory snapshot with a volume.
6. Run ./gradlew test; fix any existing test that reads providerVmId from a non-admin response.

**Acceptance criteria**

- Given an ADMIN, when GET /api/v1/environments/{env}/vms/{vmId}, then the body contains providerVmId.
- Given an ENV_ADMIN, USER or VIEWER with access, when GET /vms, /vms/{group}/page, /vms/{vmId} or /vms/{vmId}/inventory, then the body has no providerVmId and no volumeId property and still has privateIp and rdsKind.
- Given no request context (e.g. a scheduler serializing a VmDTO), when it is serialized, then providerVmId is omitted.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/security/ProviderIdVisibilityTest.java (NEW): no auth -> false; admin -> true; memo reused within one request.
- src/test/java/com/tcgdigital/vmcontrol/controller/VmProviderIdMaskingTest.java (NEW, SecuredWebTestBase): asAdmin sees providerVmId/volumeId; asEnvAdmin/asUser/asViewer do not; body never matches /i-[0-9a-f]{8,17}/ for non-admins.
- src/test/java/com/tcgdigital/vmcontrol/dto/VmDtoRdsKindTest.java (NEW): db: -> INSTANCE, cluster: -> CLUSTER, AWS -> null.

**Notes:** Decision: serialization-time masking (annotation + serializer) rather than a mapper helper in each controller, because VmDTO is built in 9 places in VmMgmtController alone and a missed call would leak; the serializer fails closed. 'Administrators' means role ADMIN only (User.isAdmin), not ENV_ADMIN. IP addresses stay visible (brief). RDS identifiers (db:<name>) and EKS ids (<cluster>/<nodegroup>) are names, but providerVmId is masked for every provider to keep one rule; rdsKind replaces the only UI use.

#### U01-T02 — Opaque reference for env-admin VM registration (Ec2InstanceInfo)

- [ ] Done · **Type:** backend · **Estimate:** 2 d · **Risk:** high · **Sources:** RegisterVms.dc.html, VmRegistry.dc.html · **Depends on:** U01-T01

Ec2Controller (/api/ec2, hasAnyRole ADMIN, ENV_ADMIN) returns the full Ec2InstanceInfo record (instanceId, vpcId, subnetId, imageId, iamInstanceProfileArn, securityGroupIds, tags) and /api/ec2/registered-ids returns every registered instance ID; vm-registry.js:1191-1200 matches the two lists by instanceId and submits providerVmId in RegisterVmDTO (@NotBlank, RegisterVmDTO.java:46). VmService.updateVm (VmService.java:268) overwrites providerVmId from the form, so once T01 masks it an env-admin edit would blank it. RegisterVms.dc.html lists instances by name, size, zone, IP, state and a note ('Found by discovery, not in a group', 'Already in Analytics QA') with no ID.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/support/InstanceRefCodec.java` (new) — AES-256-GCM (same scheme as service/chat/SecretCipher) over a compact JSON {provider, accountRef, region, instanceId, userId, expiresAt}; base64url output. encode(...) / decode(ref, currentUserId) -> InstanceRef or ValidationException('This list is out of date. Fetch the instances again.'). Key: vm.instance-ref.key (INSTANCE_REF_KEY, base64 32 bytes); when blank, a random per-process key with a startup WARN (refs then only valid on that node). TTL vm.instance-ref.ttl-minutes (default 30).
- `src/main/java/com/tcgdigital/vmcontrol/dto/Ec2InstanceChoiceDTO.java` (new) — record(ref, name, instanceType, state, privateIpAddress, availabilityZone, launchTime, platform, registeredVmId, registeredEnvironmentName, registeredGroupName, discoveryPending) - name from the Name tag (null when untagged). registeredEnvironmentName only when the caller can view that environment, otherwise 'another environment'.
- `src/main/java/com/tcgdigital/vmcontrol/controller/Ec2Controller.java` (modify) — GET /instances returns List<Ec2InstanceChoiceDTO> for everyone (admins additionally get instanceId via an @AdminOnly field on the DTO); join registration state server-side with VmRepository.findByProviderVmIdFetchGroupAndEnvironment instead of the client joining registered-ids. GET /registered-ids becomes @PreAuthorize(hasRole('ADMIN')) and is marked deprecated.
- `src/main/java/com/tcgdigital/vmcontrol/dto/RegisterVmDTO.java` (modify) — Add instanceRef; drop @NotBlank from providerVmId (line 46) and validate 'exactly one of providerVmId / instanceRef' in the service; providerVmId is accepted from ADMIN only for provider AWS.
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — registerVm (line ~144): resolve instanceRef via InstanceRefCodec (checks user, expiry, region/account) to providerVmId/region/awsAccountRef before the uniqueness check; reword the duplicate errors (lines 147-150) to name the environment, not the instance. updateVm (line 268): keep the stored providerVmId when the caller is not ADMIN or the field is blank; reject a change by a non-admin; AuditChanges .add('providerVmId', ...) (line 234) only when it actually changed.
- `src/main/resources/application.properties` (modify) — vm.instance-ref.key=${INSTANCE_REF_KEY:} and vm.instance-ref.ttl-minutes=${INSTANCE_REF_TTL_MINUTES:30}.
- `.env.example` (modify) — Document INSTANCE_REF_KEY (base64 of 32 random bytes; same value on every node).

**Steps**

1. Implement InstanceRefCodec with encode/decode, user binding and expiry; unit-test tampering, expiry, wrong user, missing key fallback.
2. Add Ec2InstanceChoiceDTO; in Ec2Controller.listInstances map Ec2Service.listInstances(accountRef, region) to choices: ref = codec.encode(AWS, accountRef, region, instanceId, currentUserId); fill registration fields from one VmRepository query over the returned ids; mark terminated/shutting-down as state so the UI can show 'stale'.
3. Restrict /api/ec2/registered-ids to ADMIN.
4. RegisterVmDTO: add instanceRef, relax providerVmId validation; in VmService.registerVm resolve the ref first, then run the existing checks; a non-admin sending providerVmId for provider AWS gets 400 'Pick the instance from the AWS list.'
5. VmService.updateVm: preserve providerVmId for non-admins and blank input; 403-style ValidationException if a non-admin tries to change it.
6. Update Ec2ControllerTest to the new response shape and add Ec2RegistrationRefTest (SecuredWebTestBase) covering env-admin register-by-ref and admin register-by-id; update RegistryPathScopeSecurityTest/NameValidationTest payloads only if they run as non-admin (they use asAdmin today).

**Acceptance criteria**

- Given an ENV_ADMIN, when GET /api/ec2/instances?region=..., then no element contains instanceId, vpcId, subnetId, imageId, ARNs or security group ids, and each has a ref, name, size, zone, IP, state and registration note.
- Given an ENV_ADMIN with a ref from that list, when POST /environments/{env}/vms with instanceRef, then the VM is created with the right providerVmId (visible to an admin) and 201.
- Given a ref older than 30 minutes or issued to another user, when it is used, then 400 'This list is out of date. Fetch the instances again.'
- Given an ENV_ADMIN edits a VM's name, when PUT /vms/{vmId} without providerVmId, then the stored instance ID is unchanged.
- Given an ENV_ADMIN, when GET /api/ec2/registered-ids, then 403.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/service/support/InstanceRefCodecTest.java (NEW): round trip, tamper, expiry, wrong user.
- src/test/java/com/tcgdigital/vmcontrol/controller/Ec2RegistrationRefTest.java (NEW, SecuredWebTestBase, Ec2Service mocked): choice shape per role, register by ref, stale ref, registered-ids 403 for env admin.
- src/test/java/com/tcgdigital/vmcontrol/controller/Ec2ControllerTest.java (UPDATE): new DTO shape.
- src/test/java/com/tcgdigital/vmcontrol/service/VmServiceUpdateProviderIdTest.java (NEW): non-admin update keeps providerVmId.

**Notes:** Stateless encrypted ref (not a server-side map) because several app nodes may serve one user (the job-lock design assumes multiple instances). RDS registration (RdsController /databases) keeps the identifier because it is a name people choose; EKS node groups are registered by EksSyncService, not by hand. A discovered-but-unassigned VM (discoveryPending) is offered with registeredVmId so the UI moves/acknowledges it (existing /vms/{vmId}/move and /acknowledge) instead of registering it twice. Front-end switch to refs is U01-T05.

#### U01-T03 — Audit/activity text and operation error messages

- [ ] Done · **Type:** backend · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** States.dc.html, OperationProgress.dc.html, AuditLog.dc.html, BRIEF: audit/activity text, operation error messages · **Depends on:** U01-T01

Free text carries instance IDs: AwsCloudProviderService.startVm/stopVm return 'AWS Error: ' + awsErrorDetails().errorMessage() (lines 147, 247) which quotes the instance, stored in OperationDetail/OperationExecution.errorMessage and shown to operators (OperationDetailDTO, OperationExecutionDTO, MyAccountService.java:130 -> MyActivityItemDTO.note); VmService writes 'discovery will ignore instance ' + providerVmId (line 313) and audits providerVmId old/new (line 234) into audit rows env admins read through AuditController; VmResizeService.java:260 'AWS does not know instance ...'; StateSyncService.java:398 Spot details; VmService.java:147-150 validation errors. States.dc.html wants plain errors ('pay-api-02 didn't start: AWS has no capacity for that size in ap-south-1a right now.').

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/support/ProviderIdRedactor.java` (new) — Pure static redact(String): replaces AWS resource ids \b(i|vol|snap|eni|sg|subnet|vpc|ami|eipalloc|sir|lt|r)-[0-9a-f]{8}([0-9a-f]{9})?\b with '<prefix>-…' and ARNs arn:aws[\w-]*:[^\s"',)]+ with 'arn:…'; null-safe; also redactDeep(Object) for Map/List/String trees.
- `src/main/java/com/tcgdigital/vmcontrol/dto/json/RedactProviderIds.java` (new) — Annotation bundling @JsonSerialize(using = RedactingStringSerializer.class).
- `src/main/java/com/tcgdigital/vmcontrol/dto/json/RedactingStringSerializer.java` (new) — Writes the raw string for admins (ProviderIdVisibility), ProviderIdRedactor.redact(value) for everyone else.
- `src/main/java/com/tcgdigital/vmcontrol/dto/OperationDetailDTO.java` (modify) — @RedactProviderIds on errorMessage (line 25) and stageLabel.
- `src/main/java/com/tcgdigital/vmcontrol/dto/OperationExecutionDTO.java` (modify) — @RedactProviderIds on errorMessage.
- `src/main/java/com/tcgdigital/vmcontrol/dto/AuditLogDTO.java` (modify) — @RedactProviderIds on details, oldValue, newValue, errorMessage, targetName (lines 21-28).
- `src/main/java/com/tcgdigital/vmcontrol/dto/MyActivityItemDTO.java` (modify) — @RedactProviderIds on note (record component).
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmStateHistoryDTO.java` (modify) — @RedactProviderIds on details (MonitoringController /vms/{vmId}/history is isAuthenticated).
- `src/main/java/com/tcgdigital/vmcontrol/dto/NotificationDTO.java` (modify) — @RedactProviderIds on title and message (covers rows written before T04).
- `src/main/java/com/tcgdigital/vmcontrol/dto/EmailLogDTO.java` (modify) — @RedactProviderIds on subject and errorMessage (NotificationController:74 is open to ENV_ADMIN).
- `src/main/java/com/tcgdigital/vmcontrol/dto/ProvisioningRequestDTO.java` (modify) — @RedactProviderIds on errorMessage (isAuthenticated endpoints).
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — Line 313 audit text -> 'VM removed from the platform; discovery will ignore its instance'; lines 147-150 messages name the environment/VM, not the instance.
- `src/main/java/com/tcgdigital/vmcontrol/service/VmResizeService.java` (modify) — Line 260 -> ResizeCheck.fail('Instance found', 'AWS no longer has this VM').
- `src/main/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderService.java` (modify) — startVm/stopVm failure messages (lines 147-164, 246-263): map common error codes (InsufficientInstanceCapacity, InstanceLimitExceeded, IncorrectInstanceState, UnauthorizedOperation, InvalidInstanceID.NotFound) to plain sentences without IDs; keep the raw AWS text after ' - AWS said: ' so admins still see it unredacted.

**Steps**

1. Implement ProviderIdRedactor with table-driven unit tests (instance, volume, snapshot, spot request, ARN, text without ids unchanged, null).
2. Add @RedactProviderIds + RedactingStringSerializer using ProviderIdVisibility from U01-T01.
3. Annotate the DTO fields listed above; for records annotate the component.
4. Reword VmService/VmResizeService messages so they never embed the instance ID.
5. Add AwsCloudProviderService.plainFailure(code, raw) used by startVm/stopVm, keeping spotStartMessage behaviour.
6. Integration test: seed an OperationDetail error 'The instance i-0123456789abcdef0 is not in a state...', an audit row with the same text and a VmStateHistory detail; read them as each role.

**Acceptance criteria**

- Given a failed step whose AWS error names i-0123456789abcdef0, when a USER reads the operation, then errorMessage reads 'The instance i-… is not in a state...' and an ADMIN sees the full ID.
- Given an ENV_ADMIN, when GET /api/v1/audit/logs?environmentId=..., then no details/oldValue/newValue contain an instance ID or ARN.
- Given an InsufficientInstanceCapacity failure, when the step fails, then the message starts with a plain sentence saying AWS has no capacity for that size in the zone right now.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/service/support/ProviderIdRedactorTest.java (NEW): patterns above.
- src/test/java/com/tcgdigital/vmcontrol/controller/ProviderIdTextRedactionTest.java (NEW, SecuredWebTestBase): operation detail, execution, audit logs, /users/me/activity, /monitoring/vms/{id}/history per role.
- src/test/java/com/tcgdigital/vmcontrol/service/AwsCloudProviderServiceTest.java (UPDATE): plain messages per error code.

**Notes:** Stored text stays raw so admins keep full diagnostics; redaction happens on the way out. The '…' replacement keeps sentences readable and shows what kind of resource it was. AWS account ids (12 digits) are not instance IDs and are not redacted (false positives on amounts); revisit if the user wants them hidden. Log lines (log.warn with ids) are server-side only and unchanged.

#### U01-T04 — Assistant/MCP and webhook payloads

- [ ] Done · **Type:** backend · **Estimate:** 1 d · **Risk:** medium · **Sources:** AskPanel.dc.html, ChatAction.dc.html, BRIEF: assistant/MCP and webhook payloads · **Depends on:** U01-T03

Text produced for non-browser readers bypasses the DTO serializers: VmOperationsService.reportFailure (line ~1330) puts errorMessage into the OPERATION_FAILED webhook data and NotificationService.notifyOperationFailedForEnvironment (line 775) broadcasts 'Reason: ' + reason to bell, Teams (toChat) and email for every member; AssistantToolGuard.invoke (line 133) serializes tool output to text for both AssistantController and McpController; ChatDispatchService builds ChatMessage text from those strings. Recipients are mixed or external, so they must be redacted at the source.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/events/PlatformEvents.java` (modify) — publish(...) passes data through ProviderIdRedactor.redactDeep before PlatformEvent.of - every webhook payload is redacted (receivers are external).
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — Redact title/message in the private create(...) and broadcastToEnvironment(...) paths (covers bell, toChat and email splits, e.g. notifyOperationFailed line 732 and notifyOperationFailedForEnvironment line 775).
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatDispatchService.java` (modify) — dispatch(...) redacts ChatMessage title, text and field values before sending (defence in depth for callers other than NotificationService).
- `src/main/java/com/tcgdigital/vmcontrol/service/assistant/AssistantToolGuard.java` (modify) — After serializing the tool result (line 133), redact text and structured value unless the user behind userId is ADMIN (look up via UserRepository; the tool may run on the guard's timeout thread without a SecurityContext).
- `src/main/java/com/tcgdigital/vmcontrol/service/EmailService.java` (modify) — Redact subject/body for operation emails if they bypass NotificationService (verify call sites; otherwise no change).

**Steps**

1. Add ProviderIdRedactor.redactDeep(Object) for nested Map/List/String values (copying, not mutating, the input).
2. Apply it in PlatformEvents.publish and in NotificationService's shared create/broadcast helpers.
3. Apply it in ChatDispatchService.dispatch for title, text and fields.
4. In AssistantToolGuard.invoke, compute 'admin' from userId once per call and redact the output text/structured value for non-admins.
5. Tests: WebhookDeliveryService payload, notification row, chat message capture, MCP tools/call output for an env-admin token vs an admin token.

**Acceptance criteria**

- Given an operation fails with an AWS message naming an instance, when the OPERATION_FAILED webhook is queued, then its JSON body contains no instance ID.
- Given the same failure, when environment members get the bell notification and Teams message, then neither contains the ID.
- Given an API token of a non-admin, when an MCP tools/call returns text that contains an instance ID or ARN, then the result shows 'i-…'/'arn:…'; given an admin token, then the raw text is returned.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/service/webhook/WebhookPayloadRedactionTest.java (NEW).
- src/test/java/com/tcgdigital/vmcontrol/service/NotificationServiceRedactionTest.java (NEW): operation failed broadcast + chat capture.
- src/test/java/com/tcgdigital/vmcontrol/assistant/AssistantToolGuardRedactionTest.java (NEW): admin vs non-admin user id.

**Notes:** Today's assistant tools (EnvironmentTools, OptimizationTools, CostTools) put no providerVmId in their maps; the guard-level redaction protects future tools and error texts. Jira ticket bodies (service/jira) do not include provider ids today; keep it that way (checked by the role test in T05 grep step).

#### U01-T05 — Front end shows the ID only when present + role tests across endpoints

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** VmDetails.dc.html, RegisterVms.dc.html, VmRegistry.dc.html · **Depends on:** U01-T01, U01-T02, U01-T03, U01-T04

The UI assumes the ID is always there: environments.js:1844 prints vm.providerVmId || 'pending-provider-id', environments.js:809 reads the 'cluster:' prefix, vm-registry.js:548/721/826 have an 'Instance ID' column, vm-registry.js:1040 fills the required #vmProviderVmId field (index.html:543) and the EC2 picker (vm-registry.js:1191-1356) joins registered-ids by instanceId and searches on it. After T01/T02 non-admins get no ID, so these must render nothing and the picker must use refs. A cross-endpoint test then locks the rule in.

**Files**

- `src/main/resources/static/js/features/environments.js` (modify) — Line 809: RDS badge from vm.rdsKind. Line 1842-1845: render the provider line's ID only when vm.providerVmId is set, labelled 'Instance ID · Administrators only' (VmDetails.dc.html); no 'pending-provider-id' placeholder.
- `src/main/resources/static/js/features/vm-registry.js` (modify) — Lines 548, 721, 826: Instance ID column only when Auth.isAdmin(). Line 1040/1051: hide the Instance ID form group for non-admins and omit providerVmId from the PUT body (line 1389). Lines 1191-1356: drop the registered-ids call, render Ec2InstanceChoiceDTO rows (name or '(no name tag)', size, zone, IP, state, note from registeredEnvironmentName/discoveryPending), select by ref (data-ref), submit instanceRef; search on name/size/IP; toast 'Selected: <name>'. 'Name / Instance ID' header (line 1305) -> 'Name'.
- `src/main/resources/static/index.html` (modify) — Line 543-544: #vmProviderVmId group gets id 'vmProviderVmIdGroup' and loses 'required' (validated in JS for admins only); bump ?v= asset stamp.
- `src/main/resources/static/js/config.js` (modify) — Mark Config.API.ec2.registeredIds as admin-only (comment) - no longer used by the picker.
- `src/test/java/com/tcgdigital/vmcontrol/controller/ProviderIdExposureSecurityTest.java` (new) — SecuredWebTestBase sweep: seed a VM (i-0123456789abcdef0), an inventory volume (vol-0123456789abcdef0), a failed operation detail, audit row, notification, state history; call every GET reachable by ENV_ADMIN/USER/VIEWER for that environment (environments list/page/detail/insights/cost, vms list/page/detail/inventory/metrics/utilization, operations list/detail, locks, leases, audit logs, my activity, notifications, monitoring history, spot summary, budgets, ec2 instances with Ec2Service mocked) and assert no body matches /\b(i|vol)-[0-9a-f]{8,17}\b|arn:aws/; the admin run of the same list finds the ID in vms detail.
- `test/tests/inventory/vm-registry-register-by-ref.spec.ts` (new) — Mock /api/ec2/instances with choice rows; as env admin pick two rows, Add, assert POST bodies carry instanceRef and no providerVmId; no 'Instance ID' column.
- `test/tests/environments/vm-details-instance-id.spec.ts` (new) — As admin-001 the VM details Overview shows 'Instance ID · Administrators only'; as user-001 and user-002 it is absent and no text matches /i-[0-9a-f]{8,}/.

**Steps**

1. environments.js: switch the RDS badge to rdsKind and make the provider line conditional.
2. vm-registry.js: role-conditional Instance ID column/header in both tables; hide/omit the field in the add/edit form for non-admins.
3. Rewrite the EC2 picker to the choice DTO and instanceRef; keep the RDS picker unchanged.
4. Update existing Playwright specs that rely on instance IDs: test/tests/inventory/vm-registry-review.spec.ts (seed still uses providerVmId as admin; assertions on the ID column only as admin), test/tests/inventory/vm-registry-rds.spec.ts (badge text from rdsKind), test/tests/environments/rds-auto-start.spec.ts.
5. Add ProviderIdExposureSecurityTest and the two new Playwright specs; bump the ?v= stamp.
6. Add a 'Data visibility' paragraph to docs/aws-iam-permissions.md (or a new docs section) stating the admin-only rule and INSTANCE_REF_KEY.

**Acceptance criteria**

- Given user-001 opens a VM's details, when the Overview tab renders, then no instance ID or 'pending-provider-id' text appears and the IP address does.
- Given admin-001 opens the same VM, then 'Instance ID · Administrators only' shows the ID.
- Given dev-user-001 (env admin) adds VMs from AWS, when picking instances, then rows show names and the request carries instanceRef only.
- Given the role sweep test, when run against all listed endpoints as ENV_ADMIN, USER and VIEWER, then no response contains an instance ID, volume ID or ARN.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/controller/ProviderIdExposureSecurityTest.java (NEW): cross-endpoint sweep per role.
- test/tests/inventory/vm-registry-register-by-ref.spec.ts (NEW).
- test/tests/environments/vm-details-instance-id.spec.ts (NEW).
- test/tests/inventory/vm-registry-review.spec.ts, vm-registry-rds.spec.ts, environments/rds-auto-start.spec.ts (UPDATE): ID column/badge assertions.

**Notes:** U07/U08/U11 restyle these screens later; this task only removes the dependency on the ID so their rebuilds start clean. Exports: FOCUS CSV (CostManagementController /export/focus) and Excel exports are hasRole('ADMIN') today - the sweep asserts 403 for non-admins rather than redacting.

#### U01-T06 — Stop naming unnamed discovered VMs after their instance ID

- [ ] Done · **Type:** backend · **Estimate:** 1 d · **Risk:** medium · **Sources:** RegisterVms.dc.html, BRIEF: Data visibility rule · **Depends on:** U01-T01

VmDiscoveryService uses the instance ID as the display name when an instance has no Name tag (VmDiscoveryService.java:241 and :378) and appends the last 4 ID characters to the slug (lines 245, 382), so the ID reaches everyone through VmDTO.name/displayName, audit target names and notifications even after masking. StateSyncService.syncVmName (lines 412-440) relies on 'name equals providerVmId' to adopt a Name tag later.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/VmDiscoveryService.java` (modify) — Lines 241-245 and 378-382: placeholder name 'unnamed-' + first 6 hex of SHA-256(instanceId) (stable, not reversible) for name and display name; duplicate-slug suffix uses the same hash instead of the ID tail.
- `src/main/java/com/tcgdigital/vmcontrol/service/StateSyncService.java` (modify) — syncVmName (lines 412-440): treat a name equal to providerVmId OR to the VM's placeholder as 'not named yet', so a Name tag added later still replaces it.
- `src/main/java/com/tcgdigital/vmcontrol/service/support/UnnamedVmNames.java` (new) — placeholderFor(providerVmId) and isPlaceholder(name, providerVmId) shared by discovery and sync.
- `src/main/resources/db/migration/V90__rename_vms_named_after_instance_id.sql` (new) — Next free V number (V90): UPDATE vm SET name/display_name = CONCAT('unnamed-', LEFT(SHA2(provider_vm_id,256),6)) WHERE provider='AWS' AND (name = provider_vm_id OR display_name = provider_vm_id); check column names against the vm table before writing.

**Steps**

1. Add UnnamedVmNames with unit tests.
2. Use it in both discovery paths (register and per-environment discovery).
3. Teach StateSyncService.syncVmName to recognise the placeholder.
4. Add the data migration (next free V number) and verify with FlywayMigrationTest.
5. Update VmDiscoveryServiceTest / VmDiscoveryIntegrationTest expectations for untagged instances.

**Acceptance criteria**

- Given discovery finds an instance without a Name tag, when it registers the VM, then the name is 'unnamed-xxxxxx' and contains no part of the instance ID.
- Given that VM later gets a Name tag in AWS, when state sync runs, then the VM takes the tag's name.
- Given an existing VM whose name equals its instance ID, when the migration runs, then it is renamed to the placeholder.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/service/support/UnnamedVmNamesTest.java (NEW).
- src/test/java/com/tcgdigital/vmcontrol/service/VmDiscoveryServiceTest.java (UPDATE): untagged naming.
- src/test/java/com/tcgdigital/vmcontrol/service/StateSyncServiceTest.java (UPDATE): placeholder replaced by Name tag.
- src/test/java/com/tcgdigital/vmcontrol/FlywayMigrationTest.java (runs the new migration).

**Notes:** Name uniqueness is per group; a 6-hex hash collision inside one group is negligible, but the migration should append '-2' style suffixes if the unique index rejects a row (write it as a guarded UPDATE or a Java migration if needed). Migration numbers V90-V95 follow task order (U01-T06 V90, U08-T01 V91, U09-T09 V92, U13-T01 V93, U13-T06 V94, U15-T08 V95); take the next free number if another migration lands first.

## U02

### Pages people can open — Foundation

Every page a person can reach by menu or URL either works for their role and the server's switches or says plainly that it is not for them, with no failing background requests, correct help links and named controls.

Sources: NoAccess.dc.html, EnvDetailViewer.dc.html, VmRegistry.dc.html, AccessRequests.dc.html, SideNav.dc.html, States.dc.html, Crawl audit (scratchpad/audit/audit.json): admin pages open by URL, blank pages, wrong help link, unnamed switches, Approve/Deny overlap, failing background requests · 5 tasks · 6 days

#### U02-T01 — Route guard + "no access" page

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** NoAccess.dc.html, SideNav.dc.html, Crawl audit: admin pages open by URL, blank pages

ContentRouter.loadContent (js/core/router.js:218) runs any loader for any role; the only role check is the sidebar toggling .admin-only/.env-admin-only (app.js updateSidebarForRole). The crawl shows users and viewers opening vm-registry (with Create/Edit/Deactivate), budgets, system-health and templates by URL, and blank pages (empty h1) for user-management, cost pages, teams, aws-accounts, settings etc.; user-management.js:40 shows a bare 'Access denied. Admin only.' alert. Unknown hashes silently fall back to the dashboard (matchRoute line 203). NoAccess.dc.html defines the target: '<Page> is for environment admins', 'You followed a link to a page your role can't use. Nothing went wrong, and nothing was changed.', Go to Home / Your environments.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/CapabilitiesDTO.java` (new) — record(boolean admin, boolean envAdmin, Map<String, Boolean> features) - feature keys: templates, approvals, webhooks, chat, spot, carbon, rds, rdsKeepStopped, leases, idleStop, apiTokens, assistant, jira, budgetActions.
- `src/main/java/com/tcgdigital/vmcontrol/service/CapabilitiesService.java` (new) — Builds CapabilitiesDTO from the existing switches/services (provisioning.enabled, approvals.enabled, webhooks.enabled, chat.enabled, spot.enabled, carbon ingestion isEnabled, RdsCloudProviderService.isEnabled, RdsKeepStoppedService.isKeepStoppedEnabled, lease.enabled, automation.idle-stop.enabled, api-tokens.enabled, assistant.chat.enabled, jira.enabled), reading runtime overrides through SettingsService where a setting exists.
- `src/main/java/com/tcgdigital/vmcontrol/controller/UserController.java` (modify) — GET /api/v1/users/me/capabilities (isAuthenticated) next to /me (line 115).
- `src/main/resources/static/js/core/capabilities.js` (new) — Capabilities singleton: load() once after Auth.init, has(feature), isAdmin()/isEnvAdmin() passthrough; defaults to all-off on failure.
- `src/main/resources/static/js/core/router.js` (modify) — Add ROUTE_ACCESS {contentType: {role: null|'ENV_ADMIN'|'ADMIN', feature?, label}} per SideNav.dc.html: Workspace (dashboard, my-environments, environment-detail, request-access, approvals[feature approvals], activity-logs, help, chat-action, templates[feature templates]) open to all; Manage (pending-requests, access-management, vm-registry, automation-rules, approval-policies, budgets, audit-logs-all) ENV_ADMIN; Cost + Administration (cost-management, savings, waste, chargeback, cost-setup, user-management, teams, aws-accounts, api-access[feature apiTokens], settings, system-health) ADMIN. loadContent checks it before the loader and calls NoAccessPage.render(...). matchRoute's unknown-path fallback renders a 'Page not found' variant instead of the dashboard. Export showNoAccess(kind, opts) for loaders.
- `src/main/resources/static/js/ui/no-access.js` (new) — NoAccessPage.render({title, body, hint, actions}) with the NoAccess.dc.html copy; variants: role ('VM registry is for environment admins' / '... is for administrators'), feature off ('<Page> is switched off. An administrator can turn it on in Settings.'), environment ('You can't open this environment' + 'Request access'), not found. Focuses the h1; sets document.title.
- `src/main/resources/static/css/components/no-access.css` (new) — Centered panel layout from the mockup using existing tokens (U03 restyles).
- `src/main/resources/static/js/core/auth.js` (modify) — init(): await Capabilities.load() after loadCurrentUser (line 21).
- `src/main/resources/static/js/features/environments.js` (modify) — loadDetail: a 403/404 from fetchEnvironmentDetails (line 272) shows ContentRouter.showNoAccess('environment', {environmentId}) instead of an error toast + empty page.
- `src/main/resources/static/js/features/user-management.js` (modify) — Remove the inline 'Access denied. Admin only.' branch (line 40) - the guard handles it.
- `src/main/resources/static/index.html` (modify) — Add js/core/capabilities.js after auth.js, js/ui/no-access.js after ui/forms.js, the new css; bump ?v= stamp.

**Steps**

1. Backend: CapabilitiesDTO, CapabilitiesService, GET /api/v1/users/me/capabilities with a SecuredWebTestBase test per role and with switches on/off.
2. Front end: Capabilities module loaded in Auth.init; expose via window.Capabilities.
3. router.js: ROUTE_ACCESS table + guard in loadContent; unknown routes -> not-found variant; Sidebar.setActiveItem(null) for denied routes.
4. no-access.js + css with the mockup copy (sentence case, no apology).
5. environments.js: environment 403/404 -> environment variant with a 'Request access' button to #/request-access.
6. Remove page-level role checks that the guard now covers (user-management.js:40) and update navigation specs.

**Acceptance criteria**

- Given user-001 types #/vm-registry, when the page loads, then 'VM registry is for environment admins' shows with Go to Home and Your environments, and no /api/v1 registry request is sent.
- Given dev-user-001 (env admin) opens #/user-management, then the page says it is for administrators (not blank).
- Given templates are switched off, when anyone opens #/templates, then the switched-off variant shows and GET /api/v1/templates is not called.
- Given a viewer opens #/environments/<id they cannot see>, then 'You can't open this environment' with Request access shows.
- Given #/no-such-page, then a not-found message shows instead of the dashboard.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/controller/CapabilitiesControllerTest.java (NEW): flags per role and switch.
- test/tests/navigation/route-guard.spec.ts (NEW): every ROUTE_ACCESS entry x admin-001, dev-user-001, user-001, user-002 -> page or no-access, never an empty h1.
- src/test/js/router-access.test.mjs (NEW, node --test): ROUTE_ACCESS covers every key of ROUTES and PARAM_ROUTES.
- test/tests/navigation/navigation.spec.ts, navigation.admin.spec.ts, security/system-health.spec.ts (UPDATE): system-health is admin-only now; adjust expectations.

**Notes:** The server still enforces every API call; the guard is for clarity, not security. System health and API access move to admin-only per SideNav.dc.html even though MonitoringController lets ENV_ADMIN trigger syncs (U14 owns the admin pages). U04-T01 rebuilds the side menu from the same ROUTE_ACCESS table, so keep it exported (ContentRouter.access(contentType)).

#### U02-T02 — Controls follow server capability flags on every page

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** EnvDetailViewer.dc.html, VmRegistry.dc.html, Crawl audit: VM registry shows Create/Edit/Deactivate to users · **Depends on:** U02-T01

EnvironmentDTO has viewerCanOperate/viewerCanAdminister but they are set only on GET /environments/{id} (EnvironmentController.java:296-297), not on the list/page endpoints, and there is no group-level flag, so environments.js:641 assumes 'can operate' when the flag is missing and group-scoped operators get environment-level answers. vm-registry.js renders Create Environment, Edit, Deactivate and Add VMs for every environment it lists (crawl: users/viewers see them), budgets shows 'New budget' to env admins for any environment, system-health shows Trigger Sync. EnvDetailViewer.dc.html shows a viewer no Start/Stop and an 'Ask for operator access' call to action.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — Add viewerCanManageAccess (SecurityService.canManageEnvironmentAccess) next to viewerCanOperate/viewerCanAdminister (lines 50-51).
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — Extract the flag setting at lines 296-297 into a helper used by GET (line 91), /page (line 128), /{id} and /by-name, so every EnvironmentDTO the UI receives carries the three flags.
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — VmGroupWithVmsDTO (line 454): add viewerCanOperate = securityService.canOperateInEnvironment(env) || securityService.hasGroupAccessLevel(groupId, AccessLevel.USER).
- `src/main/resources/static/js/features/environments.js` (modify) — Line 641: canOperate = env.viewerCanOperate === true; buildGroupCard (line 767) uses group.viewerCanOperate for its buttons; the view-only note (line 685) gets an 'Ask for operator access' link to #/request-access with the environment preselected.
- `src/main/resources/static/js/features/vm-registry.js` (modify) — Create Environment only for Auth.isAdmin() or env admins with global scope (follow EnvironmentController POST rules); per-environment Edit/Deactivate/Groups/Add VMs only when env.viewerCanAdminister.
- `src/main/resources/static/js/features/access-management.js` (modify) — Grant/edit/revoke controls only when env.viewerCanManageAccess.
- `src/main/resources/static/js/features/budgets.js` (modify) — New budget/edit/delete per BudgetController rules: admin, or env.viewerCanAdminister for environment budgets; read-only rows otherwise.
- `src/main/resources/static/js/features/locks.js` (modify) — buildLockBanner: acquire/extend/break buttons only when the environment's viewerCanOperate (break: viewerCanAdminister).
- `src/main/resources/static/index.html` (modify) — Bump ?v= stamp.

**Steps**

1. Backend flags: viewerCanManageAccess on EnvironmentDTO; set all flags on list/page/detail/by-name; group-level viewerCanOperate.
2. Extend EnvironmentViewerCapabilitiesTest for list/page endpoints, group-scoped grant, and assigned env-admin scope.
3. environments.js: strict flag reading, group-level buttons, view-only note with request link.
4. vm-registry.js, access-management.js, budgets.js, locks.js: hide controls the flags deny (hide, not disable, per 'no controls people cannot use').
5. Update Playwright specs that click controls now hidden for some roles; add a registry capability spec.

**Acceptance criteria**

- Given a viewer on an environment page, then no Start/Stop/lock buttons render and 'Ask for operator access' links to the request page.
- Given a user with operator access on one group only, then only that group's card shows Start/Stop.
- Given an env admin with security.env-admin.scope=assigned, when the registry lists an environment they hold no admin grant on, then it shows no Edit/Deactivate/Add VMs.
- Given GET /api/v1/environments/page as any role, then each item has viewerCanOperate, viewerCanAdminister and viewerCanManageAccess.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentViewerCapabilitiesTest.java (UPDATE): flags on list/page, viewerCanManageAccess, group flag.
- test/tests/environments/view-only.spec.ts (UPDATE): request-access link, group-scoped operator.
- test/tests/inventory/vm-registry-capabilities.spec.ts (NEW): env admin with assigned scope sees read-only rows for other environments.
- test/tests/environments/lock-banner.spec.ts (UPDATE): viewer sees the banner without buttons.

**Notes:** Capability flags come from SecurityService so they always match what the API will allow. U07/U11 rebuild these pages and must keep reading the same flags.

#### U02-T03 — No background requests for switched-off features

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** EnvDetailViewer.dc.html, Crawl audit: 3-4 failing background requests per environment page · **Depends on:** U02-T01, U02-T02

Panels probe their APIs and swallow the failure: the crawl records on every environment page 404 /environments/{id}/spot-summary (spot.js:29), 404 /webhooks?environmentId (webhooks.js:36), 404 /environments/{id}/carbon (env-cost.js:64) and, for users/viewers, 403 /chat-channels (chat-channels.js:35); 404 /templates from Templates.probeNav (templates.js:42) on every dashboard; 404 /cost-management/carbon (cost-management.js:172); 404 /api-tokens and /service-accounts on API access; 400 /aws-accounts/organization-candidates on load (aws-accounts.js:49). Each shows as a console error and some as toasts.

**Files**

- `src/main/resources/static/js/features/environments.js` (modify) — bindDetailEvents (lines 976-1024): call ChatChannels.renderEnvPanel and WebhooksPanel.renderEnvPanel only when Capabilities.has('chat'|'webhooks') && env.viewerCanAdminister; SpotPanel.render only when has('spot'); rdsAutoStartNote (line 90) reads Capabilities.has('rdsKeepStopped') instead of GET /rds.
- `src/main/resources/static/js/features/env-cost.js` (modify) — addCarbon (line 63) only when Capabilities.has('carbon').
- `src/main/resources/static/js/features/cost-management.js` (modify) — carbon dataset (line 172) only when has('carbon').
- `src/main/resources/static/js/features/templates.js` (modify) — probeNav (line 41): show #templates-link from has('templates') without calling the API.
- `src/main/resources/static/js/features/approvals.js` (modify) — probeNav (line 43): call /approval-requests/status only when has('approvals').
- `src/main/resources/static/js/features/api-tokens.js` (modify) — Admin tables (line 243) and service accounts load only when has('apiTokens'); otherwise the existing 'API tokens are off' note (line 237).
- `src/main/resources/static/js/features/aws-accounts.js` (modify) — Do not probe organization-candidates on page load (line 49); fetch on the 'Import from organization' action and show the server message in the dialog when it is 400.
- `src/main/resources/static/js/features/spot.js` (modify) — Guard render() with has('spot') as a second line of defence.
- `src/main/resources/static/js/features/webhooks.js` (modify) — Guard renderEnvPanel with has('webhooks').
- `src/main/resources/static/js/features/chat-channels.js` (modify) — Guard renderEnvPanel with has('chat') and env.viewerCanAdminister (its own comment at line 33 says admins only).
- `src/main/resources/static/index.html` (modify) — Bump ?v= stamp.

**Steps**

1. Replace each probe listed above with a Capabilities.has(...) check (plus env flags where the panel is admin-only).
2. Keep the panels' own catch() handlers for real outages, but quiet (suppressGlobalError).
3. Move the organization-candidates call behind the import action.
4. Add a Playwright spec that records every /api/ response >= 400 while visiting the main pages per role.
5. Update specs that mock these endpoints (spot-panel, webhooks, chat-channels, templates, environment-cost-card) to also mock /users/me/capabilities with the feature on.

**Acceptance criteria**

- Given spot, webhooks, chat and carbon are off, when any role opens an environment page, then no request to spot-summary, webhooks, chat-channels or carbon is sent.
- Given a user (not env admin) with chat on, when opening an environment page, then /chat-channels is not requested.
- Given templates are off, when the dashboard loads, then /api/v1/templates is not requested.
- Given the crawl pages for admin, env admin, user and viewer, then no /api/ response is 4xx (except intentional user actions).

**Tests**

- test/tests/navigation/no-failing-requests.spec.ts (NEW): dashboard, my-environments, environment detail, cost-management, api-access, aws-accounts, templates per role; assert zero 4xx/5xx /api/ responses.
- test/tests/environments/spot-panel.spec.ts, webhooks.spec.ts, chat-channels.spec.ts, templates.spec.ts, environment-cost-card.spec.ts (UPDATE): mock capabilities.
- test/tests/navigation/background-polls.spec.ts (UPDATE if it counts requests).

**Notes:** Leases, idle-stop and budgets panels already read a status endpoint that answers 200 when off; leave them unless the new spec shows otherwise.

#### U02-T04 — Help links and named switches

- [ ] Done · **Type:** fix · **Estimate:** 1 d · **Risk:** low · **Sources:** Help.dc.html, TopBar.dc.html, Crawl audit: wrong help link (My Environments -> Locks), ~45 unnamed switches · **Depends on:** U02-T01

HelpContent.PAGE_TOPICS (js/features/help-content.js:411) maps environments and environment-detail to 'locks' and teams to 'chargeback', so the '?' on My Environments opens the Locks topic (crawl); keys are page labels ('environments', 'all-logs') rather than route names, which U04-T04's top-bar '?' needs. Switches and icon buttons without names: the rule on/off switch (automation-rules.js:344, role=switch, no label - read as 'on'), the rule Edit/Delete icon buttons (title only), the requester row chevron (access-requests.js:214); axe also needs a sweep of dialogs the crawl did not open.

**Files**

- `src/main/resources/static/js/features/help-content.js` (modify) — PAGE_TOPICS keyed by router contentType for every route: my-environments and environment-detail -> 'environments', teams -> new 'teams' topic, vm-registry -> new 'vm-registry' topic, audit-logs-all -> 'activity-logs', etc.; routes with no topic map to 'getting-started'. Add the 'teams' and 'vm-registry' topics (plain copy, roles ['ADMIN'] / ['ENV_ADMIN','ADMIN']).
- `src/main/resources/static/js/features/help.js` (modify) — HelpLink.topicFor(contentType) used with route names; button(page) callers pass the contentType.
- `src/main/resources/static/js/features/environments.js` (modify) — Lines 375 and 393: HelpLink.button('my-environments', ...).
- `src/main/resources/static/js/features/all-logs.js` (modify) — Line 257: HelpLink.button('audit-logs-all', ...).
- `src/main/resources/static/js/features/automation-rules.js` (modify) — Line 344 switch: aria-label 'Rule <name> on' (aria-checked follows checked); edit/delete buttons get aria-label 'Edit <name>' / 'Delete <name>'.
- `src/main/resources/static/js/features/access-requests.js` (modify) — Line 214 chevron button: aria-label 'Show details' + aria-expanded toggled with the icon (line 525).
- `src/test/js/help-content.test.mjs` (new) — node --test: every PAGE_TOPICS value exists in TOPICS; every router ROUTES key has a PAGE_TOPICS entry; my-environments maps to 'environments'.
- `src/main/resources/static/index.html` (modify) — Bump ?v= stamp.

**Steps**

1. Re-key PAGE_TOPICS by contentType and correct the wrong mappings; add the two missing topics.
2. Update every HelpLink.button caller to pass the contentType.
3. Name the automation rule switch, its icon buttons and the access-request chevron.
4. Extend test/tests/a11y/names-and-targets.spec.ts: run axe rules button-name, link-name, label, aria-toggle-field-name on every route for admin-001 and dev-user-001, plus the open rule editor, budget editor, lease settings and onboard-user dialogs; fix anything it finds in the same task.
5. Add help-content.test.mjs and update test/tests/navigation/help-links.spec.ts.

**Acceptance criteria**

- Given My Environments, when the help '?' is clicked, then the Environments topic opens.
- Given the automation rules list, when a screen reader reaches a rule's switch, then it announces 'Rule <name> on, switch, checked' (or not checked).
- Given the axe sweep on every route and the listed dialogs, then there are no button-name, label or aria-toggle-field-name violations.

**Tests**

- src/test/js/help-content.test.mjs (NEW).
- test/tests/navigation/help-links.spec.ts (UPDATE): topic per page, My Environments -> environments.
- test/tests/a11y/names-and-targets.spec.ts (UPDATE): all routes + dialogs, label and toggle rules.

**Notes:** The brief's ~45 unnamed switches counts one per rule row across pages; the current crawl found 8 rule switches + 16 icon buttons on automation-rules and the request-access chevron. The axe sweep is the guard against the rest. U04-T04 moves the '?' into the top bar and will call HelpLink.topicFor(currentContentType).

#### U02-T05 — Stop Approve and Deny overlapping on Access Requests

- [ ] Done · **Type:** fix · **Estimate:** 0.5 d · **Risk:** low · **Sources:** AccessRequests.dc.html, Crawl audit: Access Requests Approve/Deny overlap

css/features/access-requests.css:113-129 sets fixed widths for a 6-column #pending-requests-table, but access-requests.js:418-450 renders 9 cells (requester, environment, level, scope, duration, current access, requested, reason, actions); the 6th width lands on 'current access', the actions column gets the leftover space and the two buttons in .ra-action-buttons overlap at 1440 px. AccessRequests.dc.html shows Approve and Deny side by side with an 'Approve for' choice.

**Files**

- `src/main/resources/static/css/features/access-requests.css` (modify) — Lines 113-129: widths for 9 columns; actions column width 11rem with white-space:nowrap; .ra-action-buttons (line 169) no wrap; reason column ellipsis.
- `src/main/resources/static/js/features/access-requests.js` (modify) — Lines 418-450: add data-label to every cell and the table-cards class so under 1280 px rows stack (components/tables.css:158 pattern); button text 'Approve' / 'Deny…'.
- `src/main/resources/static/index.html` (modify) — Bump ?v= stamp.

**Steps**

1. Recount the columns and rewrite the nth-child width rules for 9 columns.
2. Give the actions column a fixed width and keep both buttons on one line.
3. Use the table-cards stacking below 1280 px.
4. Add a Playwright layout check at 1440x900 and 1280x800.

**Acceptance criteria**

- Given a pending request at 1440x900 and 1280x800, when the page renders, then the Approve and Deny buttons do not intersect and both are fully visible inside their row.
- Given a long justification, then it is truncated with an ellipsis and the full text is in the title.

**Tests**

- test/tests/access/pending-requests-layout.spec.ts (NEW): bounding boxes of Approve/Deny do not overlap at two viewport sizes.
- test/tests/access/review-request.spec.ts (UPDATE): 'Deny…' label if it clicks by text.

**Notes:** Tactical fix so the page is usable now; U09 rebuilds Access Requests as the card list in AccessRequests.dc.html.

## U03

### Visual system — Foundation

Every page uses the approved TCG identity (navy, gear green, gold, IBM Plex, power strip) from one set of tokens and one theme layer, with no Google font CDN and no competing old palette.

Sources: StyleGuide.dc.html, PowerStrip.dc.html, Main.dc.html, Dashboard.dc.html, Environments15.dc.html, EnvDetailLarge.dc.html, git stash@{0} visual-refresh-draft · 4 tasks · 6 days

#### U03-T01 — Add the colour tokens, self-hosted IBM Plex Sans and Mono, and the type scale

- [ ] Done · **Type:** design · **Estimate:** 1 d · **Risk:** low · **Sources:** StyleGuide.dc.html, git stash@{0}^3 (webfonts, main.css diff)

css/main.css :root (L6-82) still holds the old blue palette (--primary-color #2563eb, --success-color #10b981) and body uses the system font stack (L112); no web font is loaded anywhere. The mockup needs navy #14245C, gear green #12915E, gold #D9A21B, red #C2412D, ink/slate/hairline/canvas and IBM Plex Sans + Mono, self-hosted. The parked stash already holds the five latin woff2 files, the OFL licence and a token draft. Also, /webfonts/** is not in the permitAll list of EntraidSecurityConfig (L57), so fonts would be redirected to sign-in on login.html.

**Files**

- `src/main/resources/static/webfonts/ibm-plex-sans-latin-400-normal.woff2` (new) — From stash@{0}^3 (also -500, -600 and ibm-plex-mono-latin-400/-500)
- `src/main/resources/static/webfonts/IBM-Plex-LICENSE.txt` (new) — SIL OFL licence that must ship with the fonts (from stash@{0}^3)
- `src/main/resources/static/css/main.css` (modify) — @font-face block (400/500/600 Sans, 700 mapped to the 600 file for Bootstrap bold; 400/500 Mono; font-display: swap); :root brand tokens --brand-navy/-hover/-tint, --brand-green/-tint, --brand-gold/-tint, --brand-red/-tint, --ink, --slate, --line #E1E6EF, --line-soft #EEF1F5, --canvas #F3F5F9, --font-sans, --font-mono; re-point the existing names (--primary-color, --success-color, --text-primary, --border-color, --background-light, --status-*) at them so feature sheets follow; type scale --fs-page-title 28px, --fs-section 18px, --fs-card-title 16px, --fs-body 14px, --fs-lead 15px, --fs-caption 13px, --fs-small 12px; body font-family var(--font-sans), color var(--ink)
- `src/main/resources/static/css/theme.css` (new) — First section only: Bootstrap 5.3 variables (--bs-body-font-family, --bs-primary(-rgb), --bs-success, --bs-warning, --bs-danger, --bs-link-color #2F4A9E, --bs-border-color, radii, --bs-focus-ring-color) as in the stash's theme.css L9-41; later tasks add component sections
- `src/main/resources/static/index.html` (modify) — Add <link rel=preload as=font type=font/woff2 crossorigin> for the Sans 400 file; add css/theme.css after the feature sheets and before layout/mobile.css (L54); bump every ?v= stamp
- `src/main/resources/static/login.html` (modify) — Add the same preload; it already links css/main.css (L15) so the fonts and tokens apply
- `src/main/java/com/tcgdigital/vmcontrol/security/EntraidSecurityConfig.java` (modify) — Add "/webfonts/**" to the static permitAll matchers (L57) so the sign-in page can load the fonts

**Steps**

1. Extract the font files without touching the stash: git show 'stash@{0}^3:src/main/resources/static/webfonts/<file>' > src/main/resources/static/webfonts/<file> for the five woff2 files and IBM-Plex-LICENSE.txt (do not apply or drop the stash).
2. Copy the @font-face block and brand tokens from git stash show -p stash@{0} (main.css hunk) into css/main.css; add --brand-red #C2412D / --brand-red-tint #FBEEEB, --ink #141B2D, --slate #5B6478, --line-soft #EEF1F5 and the --fs-* scale; keep every old variable name as an alias.
3. Create css/theme.css with only the Bootstrap variable block; link it in index.html after automation-rules/assistant.css and before layout/mobile.css.
4. Add the font preload to index.html and login.html and bump the ?v= stamp in index.html (the bundle reuses it).
5. Add /webfonts/** to the EntraidSecurityConfig permitAll list.
6. Run ./gradlew bootRun (bundled) and confirm dist/app.css resolves url('../webfonts/...') to /webfonts/ (dist sits next to css/, so the same relative path works in both modes).

**Acceptance criteria**

- Given the app is served from the bundle, when any page loads, then body text renders in IBM Plex Sans and ids/times in IBM Plex Mono, and the network panel shows no request to fonts.googleapis.com or fonts.gstatic.com.
- Given a signed-out browser with Entra on, when /login.html loads, then GET /webfonts/ibm-plex-sans-latin-400-normal.woff2 returns 200 (not a redirect).
- Given CSP font-src 'self' data: (SecurityHeaders L32), when pages load, then no CSP font violation is reported.
- Given existing feature sheets that use var(--primary-color), when the page renders, then they show brand navy without further edits.

**Tests**

- test/tests/a11y/visual-tokens.spec.ts (NEW): document.fonts.check('14px "IBM Plex Sans"') is true after load; getComputedStyle(body).fontFamily starts with IBM Plex Sans; no request matched fonts.googleapis.com
- src/test/java/com/tcgdigital/vmcontrol/security/StaticFontsPublicTest.java (NEW, SecuredWebTestBase): unauthenticated GET /webfonts/ibm-plex-sans-latin-400-normal.woff2 is 200 with a font content type
- test/tests/a11y/contrast.spec.ts (RUN, unchanged): still no colour-contrast violations

**Notes:** Page title size: the style guide says 30/600 but every app screen uses 28/600; use 28. Gold is a fill/marker colour only (it fails 4.5:1 as text); gold text uses #7A5000.

#### U03-T02 — Restyle the core components: buttons, inputs, status chips, tables, cards, tabs, dialogs and toasts

- [ ] Done · **Type:** design · **Estimate:** 2 d · **Risk:** medium · **Sources:** StyleGuide.dc.html, Environments15.dc.html, EnvDetailLarge.dc.html, Shortcuts.dc.html, git stash@{0}^3:css/theme.css · **Depends on:** U03-T01

Buttons, badges, tables, modals and toasts are styled per file today (css/components/buttons.css, badges.css, tables.css, modals.css, toasts.css) in the old blue palette, and Bootstrap defaults show through (boxed nav-tabs, grey .btn-secondary). The style guide fixes one look: navy primary, green only for Start, red outline only for Stop/remove, outlined secondary, pill status chips, hairline tables with 38 px rows, 12 px cards, underline tabs, 14 px dialogs. The stash's theme.css (sections L94-405) is a tested starting point.

**Files**

- `src/main/resources/static/css/theme.css` (modify) — Add sections: links; buttons (.btn 38px/radius 8/14px 500; .btn-sm 30px, .btn-xs 28px; .btn-primary navy; .btn-success = Start green; .btn-outline-secondary/.btn-secondary = white with #B9C2DA border, navy text; .btn-outline-danger/.btn-stop = white, #E8B8AE border, #A8331F text; .btn-ghost); forms (36px inputs, #D4DAE5 border, radius 8, navy focus ring; .form-control-lg 44px); status chips (.chip-running #E3F4EC/#0B6B44, .chip-waiting #FBF3DC/#7A5000, .chip-failed #FBEEEB/#A8331F, .chip-stopped #ECEFF5/#4A5368, .chip-admin #ECEFF8/#14245C, 12px 500, 3px 10px, pill) and map the existing .status-badge.running/.stopped/.partial classes onto them; tables (thead #F7F8FB 12.5px 500 slate, rows min 38px, borders #EEF1F5, no zebra); cards/panels (white, 1px #E1E6EF, radius 12, shadow 0 1px 2px rgba(20,36,92,.05)); tabs (.nav-tabs as underline: navy 2px active, no boxes); dialogs (.modal-content radius 14, shadow 0 24px 48px rgba(20,36,92,.25), backdrop rgba(10,20,48,.42), header 18/600, 32px close); toasts (white, 4px coloured left edge, 14px); alerts (quiet tints with coloured edge); kbd (as in Shortcuts.dc.html); .mono and tabular-nums
- `src/main/resources/static/css/components/buttons.css` (modify) — Remove rules the theme now owns (primary hover L12-13, the #6c757d darkening at L37, btn-tonal colours L177-191) so there is one definition
- `src/main/resources/static/css/components/badges.css` (modify) — Drop the per-status hex colours that the chip classes replace; keep layout-only rules
- `src/main/resources/static/css/components/modals.css` (modify) — Keep sizes/scroll rules only; colours, radius and shadow come from theme.css
- `src/main/resources/static/css/components/toasts.css` (modify) — Keep position/stacking (z-index 9999) only
- `src/main/resources/static/index.html` (modify) — The #destructiveConfirmModal header (bg-danger text-white) becomes a plain header with a red Stop-style confirm button; bump ?v=

**Steps**

1. Port the stash theme.css sections (cards L94-145, tables L146-171, tabs L172-200, forms L201-225, buttons L226-322, badges L323-364, dialogs L365-392, alerts L393-404) into css/theme.css, then correct values to the mockup numbers listed above.
2. Add the .chip-* classes and alias the existing .status-badge state classes and Bootstrap .badge.bg-success/.bg-warning/.bg-danger to them.
3. Delete the duplicated colour rules from buttons.css, badges.css, modals.css and toasts.css so theme.css is the single source (keep layout).
4. Restyle the destructive confirm modal header in index.html and give its confirm button the Stop style.
5. Walk Dashboard, My Environments, an environment page, VM registry, Users and a modal at 1440x900 and fix any feature-sheet rule that overrides the theme with a higher specificity.
6. Bump the ?v= asset stamp.

**Acceptance criteria**

- Given any page, when a primary button renders, then its background is #14245C, height 38 px and radius 8 px; a Start button is #12915E; a Stop button is white with #A8331F text.
- Given a VM state badge, when the VM is running, stopped, starting or failed, then it renders as the matching pill chip from the style guide.
- Given a Bootstrap modal, when it opens, then it has a 14 px radius, the dark-navy backdrop and an 18/600 title.
- Given the axe colour-contrast rule, when it runs on the pages in contrast.spec.ts, then there are no violations.

**Tests**

- test/tests/a11y/visual-tokens.spec.ts (UPDATE): inject a button set, chips, a table and open a Modals.show dialog; assert computed background/border colours, heights and radii
- test/tests/a11y/contrast.spec.ts (RUN): no violations on the existing page list
- test/tests/navigation/toasts-modals.spec.ts (RUN, update selectors only if it asserts bg-danger on the destructive modal)

**Notes:** Restyle only: no markup changes in feature JS except where a class must be swapped to get the right variant. Page header and side menu styling belong to U04.

#### U03-T03 — Build the power strip component (one light per VM, grouped by start order)

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** PowerStrip.dc.html, StyleGuide.dc.html, Dashboard.dc.html, AdminDashboard.dc.html, Environments15.dc.html, EnvDetailLarge.dc.html, Help.dc.html, git stash@{0}^3:js/ui/power-strip.js · **Depends on:** U03-T01

VM state is shown as '3/6' pills today (environments.js buildListHtml status-badge at ~L503, dashboard.js running/total cell at ~L313) plus count tiles and a donut. The mockup's signature component replaces them: one light per VM, groups separated in start order, compact strips turn into a bar over 24 VMs, the large strip keeps every light. The stash has a draft js/ui/power-strip.js and css/components/power-strip.css, but its groups() falls back to a bar over 24 even when large, and it has no busy/failed/not-read inputs from counts.

**Files**

- `src/main/resources/static/js/ui/power-strip.js` (new) — PowerStrip IIFE: render(groups, {large, showCount}) where groups = [{name, lights:['R','B','S','U','E',...]}]; fromVmGroups(groupsWithVms) (sorted by group then VM sequencePosition, VmStatus -> R/B/S/U/E); fromGroupStates(groupStates) (U05-T03 counts: running, busy, problem, unknown, stopped in that order per group); counts(running, total, opts) for rows that only know two numbers. Compact (7x14) strips with more than 24 lights render the 80x8 bar; large (10x20) never does. Wrapper role=img, aria-label '44 of 54 VMs running, 1 failed, 2 starting'; each group carries its name as title
- `src/main/resources/static/css/components/power-strip.css` (new) — From the stash, aligned to PowerStrip.dc.html: light sizes/radii, group gap 3px and separator (margin-left 5px, padding-left 8px, 1px #D4DAE5), R green with #0D7A4E border and soft halo, B gold, E red, U hatched, S white with #B9C2D2 border, mono count (13px compact / 15px large, running number in ink 500); legend row helper
- `src/main/resources/static/index.html` (modify) — Link power-strip.css after components/loading.css and load js/ui/power-strip.js after table-cards.js (before the feature scripts); bump ?v=
- `src/main/resources/static/js/features/environments.js` (modify) — buildListHtml: the col-running cell uses PowerStrip.counts(runningVms, totalVms) instead of the status-badge pill (as in the stash diff)
- `src/main/resources/static/js/features/dashboard.js` (modify) — Environment table running cell uses PowerStrip.counts(running, total) (stash diff ~L313)

**Steps**

1. Start from the stash files (git show 'stash@{0}^3:src/main/resources/static/js/ui/power-strip.js' and the css) and rename the API to render/fromVmGroups/fromGroupStates/counts.
2. Map VmStatus: RUNNING->R, STARTING/STOPPING->B, STOPPED->S, ERROR/NOT_FOUND/TERMINATED->E, UNKNOWN or never synced (lastStateSyncAt null)->U.
3. Apply the 24-light rule only to compact strips; large strips always draw every light.
4. Build the aria-label from the counts (running of total, plus failed/starting/not-read parts when non-zero); escape group names with Utils.escapeHtml.
5. Swap the two existing running/total cells to PowerStrip.counts and add the CSS/JS to index.html; bump ?v=.

**Acceptance criteria**

- Given groups 'RRR|RR|SS', when rendered compact, then 7 lights show in three separated groups followed by '5/7', and the strip's accessible name is '5 of 7 VMs running'.
- Given 54 VMs, when rendered compact, then an 80 px bar filled to the running share replaces the lights; when rendered large, then all 54 lights show.
- Given a VM in ERROR and one never synced, when rendered, then they show as a red light and a hatched light and the label mentions '1 failed' and '1 not read from AWS yet'.

**Tests**

- src/test/js/power-strip.test.mjs (NEW, node --test with vm sandbox like notifications-modals-text.test.mjs): light counts per group, bar threshold compact vs large, status mapping, aria-label text, HTML-escaped group names
- test/tests/environments/*.spec.ts (UPDATE any assertion on the '.status-badge' running pill in the list to the power strip's role=img name)

**Notes:** Order of lights inside a group built from counts: running, starting/stopping, failed, not read, stopped (matches EnvDetailLarge 'RRRRRRRRRRES'). Home, environment list and environment page adopt it fully in U06/U07.

#### U03-T04 — Retire the old palette and theme overrides; ship light mode only

- [ ] Done · **Type:** refactor · **Estimate:** 2 d · **Risk:** medium · **Sources:** StyleGuide.dc.html, git stash@{0} (hex-to-token replacements in 12 sheets) · **Depends on:** U03-T02, U03-T03

The stylesheets carry ~594 hard-coded hex colours (most in my-account.css 41, main.css 40, notification-bell.css 39, environments.css 38, topnav.css 33, badges.css 33, locks.css 32) from the old Tailwind-like palette (#2563eb, #10b981, #ef4444, #f59e0b, #64748b, #e2e8f0, #dbeafe, #eff6ff) and 29 !important overrides, so pages drift from the tokens. There is no dark mode today and the approved mockup has none. css/index.css is a documentation-only file that is never loaded, and static/home.html (95 KB) and home-original.html (97 KB) are dead copies of the old shell (/home forwards to index.html in HomeController L19).

**Files**

- `src/main/resources/static/css/main.css` (modify) — Add color-scheme: light on :root and a header comment recording the decision (light only; tokens are the single colour source)
- `src/main/resources/static/css/components/*.css` (modify) — Replace old-palette hex values with var(--...) tokens (access, audit, cards, loading, locks, notification-bell, operations, pagination, tables, toasts ...); drop !important colour overrides the theme makes redundant (e.g. access.css .badge.bg-APPROVED)
- `src/main/resources/static/css/features/*.css` (modify) — Same replacement in my-account, environments, access-management, automation-rules, user-management, leases, cost-management, dashboard, budgets, access-requests, system-health
- `src/main/resources/static/css/layout/topnav.css` (modify) — Colours to tokens (layout itself changes in U04-T01)
- `src/main/resources/static/js/ui/charts.js` (modify) — If it hard-codes series colours, read --brand-navy/--brand-green/--brand-gold/--brand-red via getComputedStyle instead
- `src/main/resources/static/css/index.css` (delete) — Unreferenced documentation file that lists a CSS architecture that no longer exists
- `src/test/js/css-tokens.test.mjs` (new) — Fails when any css/**/*.css outside the main.css token block uses a retired palette hex
- `test/tests/navigation/navigation.spec.ts` (modify) — Comment at L6 refers to home.html; point it at index.html

**Steps**

1. Write the retired-hex list and its token mapping (e.g. #2563eb/#1d4ed8/#1e40af -> --primary-color/--primary-dark, #10b981/#059669 -> --success-color, #ef4444/#dc2626 -> --danger-color, #f59e0b -> --warning-color, #64748b -> --slate, #e2e8f0 -> --line, #f8fafc -> --canvas, #dbeafe/#eff6ff -> --brand-navy-tint).
2. Apply it file by file (the stash diff already covers 12 sheets); leave intentional one-off colours (chart series, provider logos) and record them in the allow-list of css-tokens.test.mjs.
3. Remove colour !important overrides that only existed to beat Bootstrap defaults now set through --bs-* variables.
4. Add color-scheme: light; confirm nothing reads prefers-color-scheme.
5. Delete css/index.css after grep confirms nothing references it. Keep home.html and home-original.html (the owner decided to keep legacy static files; parking lot PL-09).
6. Bump ?v= and walk the main pages at 1440x900 for regressions.

**Acceptance criteria**

- Given the stylesheets, when node --test runs css-tokens.test.mjs, then no retired palette hex is found outside the allow-list.
- Given a browser in OS dark mode, when the app loads, then it renders the light theme with light form controls.

**Tests**

- src/test/js/css-tokens.test.mjs (NEW): scans css/** for retired hex values
- test/tests/a11y/contrast.spec.ts (RUN): no contrast violations
- test/tests/navigation/navigation.spec.ts (RUN)

**Notes:** Decision: light only for this redesign; a dark theme would be a separate epic built on the same tokens. Legacy home.html, home-original.html and static/old_backup/ stay (owner decision).

## U04

### App shell and layout — Foundation

A fixed 56 px top bar with a Help '?' and a fixed 240 px side menu whose sections are always open, plus shared header, pager, tabs and master-detail pieces so every screen fits 1440x900 without page scrollbars.

Sources: TopBar.dc.html, SideNav.dc.html, Help.dc.html, Shortcuts.dc.html, EnvDetailLarge.dc.html, Environments15.dc.html, Dashboard.dc.html, AdminDashboard.dc.html · 4 tasks · 5.5 days

#### U04-T01 — Fixed top bar and always-open side menu with 30 px rows and Help '?' in the top bar

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** TopBar.dc.html, SideNav.dc.html, Dashboard.dc.html, AdminDashboard.dc.html · **Depends on:** U03-T01

The side menu is an accordion today: index.html L122-325 renders section titles as buttons with aria-expanded, Cost and Administration start collapsed, and sidebar.js remembers open/closed sections in localStorage 'vmcontrol.sidebar.sections' (readSectionState/writeSectionState/restoreSections, L103-141), with a desktop collapse to 60 px (toggleSidebar L93-101). Help is only in the account menu (user-menu.js L118). The mockup has a 56 px top bar (logo | Self-Service, sync pill, '?', bell, account with name and role) and a 240 px menu whose sections Workspace / Manage / Cost / Administration never collapse, rows 30 px, sentence-case labels, Budgets under Manage and Cost/Administration for administrators only.

**Files**

- `src/main/resources/static/index.html` (modify) — Top bar (L62-120): logo, 1px divider, 'Self-Service'; keep #sync-indicator; add <a id='topbar-help-btn' href='#/help' aria-label='Help'> 40x40 icon; bell and account unchanged ids. Sidebar (L122-325): each section = <h2 class='sidebar-section-title' id='...-title'> + <ul class='sidebar-menu' aria-labelledby>; no buttons, chevrons or aria-expanded. Labels: Home, Environments, Request access, New environment (only while provisioning is on, until U09), Approvals, My activity | Manage: Access requests (+count), Access management, VM registry, Automation rules, Approval policies, Budgets, Audit log | Cost (admin-only): Cost management, Savings, Waste, Chargeback | Administration (admin-only): Users, Teams, AWS accounts, API access, Settings, System health. Cost setup leaves the menu. #toggleSidebar shown only on phones
- `src/main/resources/static/css/layout/sidebar.css` (modify) — 240 px, padding 8px 0; title 12px/600 slate, padding 9px 20px 3px, top hairline #EEF1F5 between sections; link 30px high, margin 0 10px, padding 0 12px, radius 7, 13.5px, 18px icon, gap 10; active #ECEFF8 navy 600 with inset 3px gold marker; delete .collapsed, .toggle and .section-pending-count rules
- `src/main/resources/static/css/layout/topnav.css` (modify) — 56 px white bar with hairline; sync pill (#F3F5F9, 13px, mono minutes); 40x40 icon buttons; account trigger: 34 px navy initials avatar, name 14/500, role 12 slate, chevron
- `src/main/resources/static/css/main.css` (modify) — --topnav-height 56px, --sidebar-width 240px; remove --sidebar-collapsed-width use on desktop
- `src/main/resources/static/css/layout/content.css` (modify) — .main-content (L5-14) becomes the only scroll container: overflow-y auto, overflow-x hidden, padding 24px 32px; delete .main-content.expanded (L16-18)
- `src/main/resources/static/css/components/operations.css` (modify) — Delete the .sidebar.collapsed ~ .operation-banner rule (L298-300)
- `src/main/resources/static/css/layout/mobile.css` (modify) — Delete the desktop .sidebar.collapsed rules (L80-86); leave the phone drawer and tablet rail for U16
- `src/main/resources/static/js/ui/sidebar.js` (modify) — Delete SECTIONS_KEY, readSectionState, writeSectionState, setSectionOpen, restoreSections, handleSectionClick, expandSection, collapseAllSections and the .sidebar-section-title click binding (L77); toggleSidebar only toggles the phone drawer; remove the old localStorage key once in init (try/catch); setActiveItem sets aria-current='page' and maps environment-detail to my-environments as today; keep arrow-key navigation
- `src/main/resources/static/js/app.js` (modify) — updateSidebarForRole (L94-110): drop Sidebar.restoreSections(); Cost and Administration sections become .admin-only, Manage stays .env-admin-only
- `src/main/resources/static/js/core/realtime.js` (modify) — L211: stop updating .section-pending-count (sections never hide the row count)
- `src/main/resources/static/js/ui/user-menu.js` (modify) — Trigger shows initials, display name and a role label (Administrator / Environment admin / User); remove the Help item (L118-124), which moves to the top bar
- `src/main/resources/static/js/features/cost-management.js` (modify) — Header (L313): add a 'Cost setup' link to #/cost-setup for admins, since it leaves the menu

**Steps**

1. Rewrite the top bar and sidebar markup in index.html as listed; keep every data-content value and link id (approvals-link, templates-link, pending-requests-link ...) so the router and probes keep working.
2. Replace the section toggle code in sidebar.js with plain headings; keep the phone drawer functions and initKeyboardNav (now every link is visible, so its display:none filter can go).
3. Update app.js role handling (Cost/Administration admin-only) and remove the restoreSections call; drop the section badge update in realtime.js.
4. Restyle sidebar.css/topnav.css to the mockup numbers and make .main-content the scroll container; delete desktop collapse rules in content.css, operations.css and mobile.css.
5. Move Help out of the account menu into #topbar-help-btn; add the Cost setup link on the Cost management header.
6. Check at 1440x900 as admin with Approvals and New environment visible that the menu needs no scrollbar (about 23 rows x 30 px + 4 headings).
7. Bump ?v= and update the specs listed below.

**Acceptance criteria**

- Given an administrator at 1440x900, when any page is open, then all four sections and every link are visible with no scrollbar in the menu and no section can be collapsed.
- Given a long page, when the content scrolls, then the top bar and side menu stay in place and the document itself has no scrollbar.
- Given an environment admin, when the menu renders, then Workspace and Manage show and Cost and Administration do not; a user or viewer sees Workspace only.
- Given an earlier visit that stored 'vmcontrol.sidebar.sections', when the app loads, then the key is removed and all sections are open.
- Given a phone (390 px), when the burger or More is pressed, then the drawer still opens and Escape closes it.

**Tests**

- test/tests/navigation/shell-layout.spec.ts (NEW): 1440x900 admin: menu scrollHeight <= clientHeight, link rows 30 px, top bar 56 px and fixed while #mainContent scrolls, document.scrollingElement has no horizontal or vertical overflow, no element with aria-expanded inside #sidebar
- test/tests/a11y/keyboard-reach.spec.ts (UPDATE): replace the 'Enter opens Administration' and 'sections open and close on their own' tests with 'Tab reaches Users without opening anything'; the user-menu arrow order no longer includes #help-menu-btn
- test/tests/navigation/help.spec.ts (UPDATE L79) and test/tests/access/ask-panel.spec.ts (UPDATE L44): #help-menu-btn -> #topbar-help-btn
- test/tests/navigation/navigation.admin.spec.ts and test/tests/dashboard/dashboard.spec.ts (RUN): #admin-menu / #operations-menu visible without a click
- test/tests/mobile/phone-navigation.spec.ts (UPDATE L58): no 'collapsed' expectation; drawer still opens from #toggleSidebar

**Notes:** Env admins lose the System Health menu item (Administration is admin-only in the mockup); whether the page stays reachable by URL is decided by U02-T01. Page h1 texts ('Dashboard', 'My Environments') are renamed in U06, not here, so page-takeover.spec.ts keeps passing.

#### U04-T02 — Add fit-to-screen layout primitives: pager, tabs, master-detail, side panel and filter group

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** EnvDetailLarge.dc.html, Environments15.dc.html, Help.dc.html · **Depends on:** U03-T02

Pagination.renderNumbered (js/ui/pagination.js L41) draws First/Prev/numbers/Next/Last bars sized for long tables, and every page hand-builds its own tabs (7 files use nav-tabs/nav-pills) and its own split layouts. The new screens fit 1440x900 by paging at 10 rows ('1-10 of 54' with Previous/Next), using tabs, a 260 px list + detail column (EnvDetailLarge groups), a side panel, and an All/Running/Stopped/Problems filter group with aria-pressed buttons.

**Files**

- `src/main/resources/static/js/ui/pagination.js` (modify) — Add buildPager({page, pageSize=10, totalItems, action, key, emptyText}) -> '1–10 of 54' + Previous/Next (30 px) with data-action/data-page; add slice(list, page, pageSize) for client-side lists; export both next to renderNumbered/buildSimpleMarkup
- `src/main/resources/static/js/ui/tabs.js` (new) — Tabs.markup({id, label, tabs:[{key, label, count}], active}) -> role=tablist with role=tab buttons (aria-selected, aria-controls, roving tabindex) and Tabs.bind(root, onChange) with ArrowLeft/Right, Home/End; panels role=tabpanel
- `src/main/resources/static/js/ui/filter-group.js` (new) — FilterGroup.markup({label:'Show', options:[{key,label,count}], active, action}) -> role=group of aria-pressed buttons (navy filled when pressed), per Environments15/EnvDetailLarge
- `src/main/resources/static/css/layout/content.css` (modify) — Primitives: .page (flex column, gap 14-20px), .page-fill (takes remaining height, min-height 0), .panel (white card, overflow hidden), .panel-head (10px 16px, hairline), .panel-foot (pager row), .master-detail (grid 260px | minmax(0,1fr), gap 14, align start), .md-list buttons (full width, aria-current with #ECEFF8 + inset gold marker), .side-col (320 px right column), .tabs underline
- `src/main/resources/static/css/components/slideout.css` (modify) — Side panel restyle: 480 px, header 18/600 + 32 px close, body scrolls inside, sticky footer for actions
- `src/main/resources/static/js/ui/slideout.js` (modify) — openDynamic(title, html, options): accept options.footer (HTML for the sticky action row)
- `src/main/resources/static/js/config.js` (modify) — Config.PAGE_SIZE = 10 (the list default for new screens)
- `src/main/resources/static/index.html` (modify) — Load js/ui/tabs.js and js/ui/filter-group.js after pagination.js; bump ?v=

**Steps**

1. Add buildPager and slice to pagination.js with the same data-action contract as buildSimpleMarkup (caller registers one Actions handler).
2. Write tabs.js with full keyboard support and filter-group.js; both return markup and use Utils.html for labels.
3. Add the layout CSS primitives and the side panel restyle; give openDynamic a footer option.
4. Set Config.PAGE_SIZE = 10 and load the new scripts in index.html.
5. Do not migrate pages here (U06-U15 adopt the primitives); keep renderNumbered for existing tables.

**Acceptance criteria**

- Given 54 items and page 1 of size 10, when buildPager renders, then it reads '1–10 of 54', Previous is disabled and Next targets page 2; given 0 items it shows the empty text and no buttons.
- Given a tablist, when focus is on a tab and ArrowRight is pressed, then the next tab is focused and selected and onChange receives its key.
- Given a master-detail layout at 1440x900, when 10 rows show in the detail column, then neither the page nor the panel scrolls.

**Tests**

- src/test/js/pager-tabs.test.mjs (NEW): buildPager range text, disabled states and empty text; slice bounds; Tabs.markup ARIA attributes; FilterGroup aria-pressed
- test/tests/navigation/layout-primitives.spec.ts (NEW): inject a tabs + master-detail + pager fixture into #content-area; keyboard through tabs; check heights fit 900 px
- test/tests/a11y/slideout-dialog.spec.ts (RUN): focus trap and Escape still work with a footer

**Notes:** Filter group is placed here (not U03-T02) because it is always used with the pager.

#### U04-T03 — Add the page header pattern: breadcrumb, title, one summary line, actions on the right

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** EnvDetailLarge.dc.html, Environments15.dc.html, Dashboard.dc.html, Help.dc.html, StyleGuide.dc.html (Layout rule) · **Depends on:** U04-T01, U03-T02

25 feature files hand-write <div class='content-header'><h1><i class='fas ...'></i>Title ... HelpLink</h1><p>...</p></div> with icons in titles and inconsistent sizes; only the environment page has a breadcrumb (environments.js L700, .env-breadcrumb in content.css L306). The mockup pattern is: breadcrumb (13 px slate, '/' separators, aria-current), title 28/600, one summary line (14-15 px slate with mono numbers), actions right-aligned, and the browser tab title should follow the page.

**Files**

- `src/main/resources/static/js/ui/page-header.js` (new) — PageHeader.markup({title, summaryHtml, breadcrumb:[{label, href}], actionsHtml, helpPage}) -> <div class='content-header page-header'> with <nav aria-label='Breadcrumb'>, <h1>, <p class='page-summary'>, <div class='page-actions'>; sets document.title = title + ' · Self-Service'. Title and labels escaped; summary/actions are caller-built (Utils.raw)
- `src/main/resources/static/css/layout/content.css` (modify) — .page-header rules (flex, space-between, align flex-end, wrap, gap 16); h1 var(--fs-page-title)/600/-0.02em; summary 15px slate with .mono numbers in ink; breadcrumb 13px; hide <i> icons inside .content-header h1 (as the stash theme did) so un-migrated pages match
- `src/main/resources/static/js/features/environments.js` (modify) — Environment page header (L699-720) and list header (L375, L393) built with PageHeader.markup; keep h1 texts; breadcrumb label 'Environments'
- `src/main/resources/static/js/features/help.js` (modify) — load() header via PageHeader.markup ('Help', 'How the platform works, in short articles.')
- `src/main/resources/static/index.html` (modify) — Load js/ui/page-header.js after the ui scripts; bump ?v=

**Steps**

1. Write page-header.js; keep the outer .content-header class so existing selectors (page-takeover.spec header()) still match.
2. Add the CSS, including hiding legacy h1 icons, so all 25 pages pick up the new type immediately.
3. Migrate the environment list, environment page and Help headers to PageHeader.markup as reference implementations; other pages migrate in their own epics.
4. Set document.title from PageHeader and default it to 'Self-Service' on route change in router.js loadContent for pages not yet migrated.

**Acceptance criteria**

- Given the environment page, when it opens, then a breadcrumb 'Environments / <name>' with aria-current on the name sits above a 28 px title and the actions sit on the right of the same row.
- Given any page with the old header, when it renders, then its title shows without an icon in the new type scale.
- Given a migrated page, when it opens, then the browser tab reads '<title> · Self-Service'.

**Tests**

- src/test/js/page-header.test.mjs (NEW): markup escapes title and breadcrumb labels, omits empty parts, sets aria-current on the last crumb
- test/tests/navigation/page-takeover.spec.ts (RUN): '#content-area .content-header h1' still finds the titles
- test/tests/environments/*.spec.ts (UPDATE any '.env-breadcrumb' selector to 'nav[aria-label=Breadcrumb]')

**Notes:** HelpLink '?' buttons inside h1 stay until U02-T04 decides their placement; PageHeader accepts helpPage and renders HelpLink.button in the actions row.

#### U04-T04 — Turn the top-bar '?' into a help menu and restyle the keyboard shortcuts dialog

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** TopBar.dc.html, Shortcuts.dc.html, Help.dc.html · **Depends on:** U04-T01, U03-T02

Keyboard.showShortcutsHelp (keyboard.js L102-135) shows a Bootstrap table titled 'Keyboard Shortcuts' with labels like 'Go to Dashboard' / 'Go to My Environments', and nothing in the UI tells people it exists. The mockup's dialog groups 'Go to' (Home g d, Environments g e, Request access g r, My activity g a) and 'On any page' (Refresh r, Show this list ?, Close Esc) with a footer note. The top-bar '?' added in U04-T01 should offer help for the current page (HelpContent.PAGE_TOPICS), all help and the shortcuts; the router has no accessor for the current page today (router.js exports at L378).

**Files**

- `src/main/resources/static/js/core/router.js` (modify) — Remember the contentType in the hashchange handler (~L260) and export current() returning it
- `src/main/resources/static/js/ui/help-menu.js` (new) — HelpMenu: #topbar-help-btn becomes a button with aria-haspopup='menu'; menu items 'Help for this page' (only when HelpContent.PAGE_TOPICS[ContentRouter.current()] exists, href #/help/<topic>), 'All help topics' (#/help), 'Keyboard shortcuts' (opens the dialog; shows the ? key); arrow keys, Escape returns focus, click outside closes (same pattern as user-menu.js L29)
- `src/main/resources/static/js/core/keyboard.js` (modify) — Each shortcut gets group ('Go to' / 'On any page') and a plain label (Home, Environments, Request access, My activity, Refresh what you see, Show this list); showShortcutsHelp renders the grouped list with <kbd> keys and 'then', an Esc row, and the footer 'Shortcuts don't fire while you type in a field or while a dialog is open. Ctrl and Alt combinations stay the browser's.'; dialog title 'Keyboard shortcuts', keeps id shortcutsHelpModal
- `src/main/resources/static/index.html` (modify) — #topbar-help-btn becomes <button> plus a hidden menu container; load js/ui/help-menu.js after user-menu.js; bump ?v=
- `src/main/resources/static/css/layout/topnav.css` (modify) — Help menu dropdown styles (shared with the account dropdown)

**Steps**

1. Add ContentRouter.current().
2. Build help-menu.js and wire it in App.init after UserMenu; the menu is rebuilt on open so 'Help for this page' follows the current route.
3. Rework the shortcuts table into the grouped list in keyboard.js; keep the same shortcut keys and the overlay/typing guards.
4. Bump ?v= and update the specs.

**Acceptance criteria**

- Given the environment page, when the user opens '?', then 'Help for this page' links to the page's topic, followed by 'All help topics' and 'Keyboard shortcuts'.
- Given a page with no topic, when '?' opens, then 'Help for this page' is not shown.
- Given any page, when the user presses '?', then the 'Keyboard shortcuts' dialog lists Go to and On any page groups; Escape closes it and focus returns to where it was.

**Tests**

- test/tests/navigation/keyboard-shortcuts.spec.ts (UPDATE L92-96): expect 'Go to' group and 'Environments' instead of 'Go to My Environments'; title 'Keyboard shortcuts'
- test/tests/navigation/help.spec.ts (UPDATE): '?' menu shows the page topic on #/my-environments and opens #/help/<topic>
- test/tests/a11y/keyboard-reach.spec.ts (UPDATE): the help menu opens from the keyboard, arrows move, Escape returns focus to #topbar-help-btn

**Notes:** The mockup's Shortcuts dialog says 'My environments'; the side menu says 'Environments'. Use the menu label so the two match.

## U05

### Data the new screens need — Foundation

The API returns what Home, the environment list, the environment page, VM details, admin Home and Users need: when each VM entered its state, its instance type, per-group state counts, a 'needs attention' summary and how each person signs in.

Sources: Dashboard.dc.html, AdminDashboard.dc.html, Environments15.dc.html, EnvDetailLarge.dc.html, EnvDetail.dc.html, VmDetails.dc.html, Users.dc.html, UserDialogs.dc.html, MyAccount.dc.html · 6 tasks · 6 days

#### U05-T01 — Return when each VM entered its current state ('Running since') on the VM endpoints

- [ ] Done · **Type:** backend · **Estimate:** 1 d · **Risk:** low · **Sources:** VmDetails.dc.html, EnvDetail.dc.html, EnvDetailLarge.dc.html · **Depends on:** U01-T01

VmDTO (dto/VmDTO.java L14-39) has status and lastStateSyncAt but no time the VM entered that state, so screens cannot say 'Running since 08:05' (VmDetails, EnvDetail). vm_state_history already records every transition (VmStateHistory.changedAt = created_at, newStatus) and VmStateHistoryRepository.findLastStoppedAt (L86-88) shows the batched GROUP BY pattern. VmMgmtController builds VmDTOs in several places (L129, L185, L236, L267, L380) and enriches only privateIp via withPrivateIp (L137).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/repository/VmStateHistoryRepository.java` (modify) — Add findLastEnteredAtByStatus(vmIds): SELECT h.vm.vmId, h.newStatus, MAX(h.changedAt) FROM VmStateHistory h WHERE h.vm.vmId IN :vmIds GROUP BY h.vm.vmId, h.newStatus (uses idx_vm_state_history_vm)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmListDetailsService.java` (new) — detailsFor(List<Vm>) -> Map<vmId, Details(privateIp, stateSince)>; one inventory query (as VmInventoryService.getPrivateIpsByVmIds L65) and one history query; stateSince = the MAX changedAt whose newStatus equals the VM's current status, else null
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmDTO.java` (modify) — Add Timestamp stateSince (JSON 'stateSince') with getter/setter and a Javadoc: when the VM entered its current status; null when no history row exists (registered while running, or purged by retention)
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — Replace withPrivateIp with withDetails(dto, details) at the group listing (L129), the group page (L185), GET /{vmId} (L267), review list and update (L380); one batch call per response

**Steps**

1. Add the grouped history query to VmStateHistoryRepository.
2. Create VmListDetailsService that loads inventory snapshots and history for the given VMs in two queries and returns the Details map.
3. Add stateSince to VmDTO; leave fromEntity unchanged (no repository access in the DTO).
4. Route every VmDTO-returning endpoint in VmMgmtController through withDetails; if U01-T01 introduced a central VmDTO assembly or masking step, call it from the same place so masking applies to every path.
5. Document the null case in the OpenAPI description of GET /environments/{id}/vms.

**Acceptance criteria**

- Given a VM whose history is RUNNING at 08:05, STOPPED at 12:00, RUNNING at 14:10 and is now RUNNING, when GET /api/v1/environments/{id}/vms is called, then that VM's stateSince is 14:10.
- Given a VM with no history row for its current status, when listed, then stateSince is null and the request still succeeds.
- Given a group page of 25 VMs, when it is fetched, then the history lookup is a single query.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/service/VmListDetailsServiceTest.java (NEW, AbstractIntegrationTest): latest matching transition wins, null without history, several VMs in one call
- src/test/java/com/tcgdigital/vmcontrol/controller/VmListingIntegrationTest.java (NEW, AbstractIntegrationTest + MockMvc as in EnvironmentHierarchyIntegrationTest): GET /environments/{id}/vms, the group page and GET /{vmId} contain stateSince

**Notes:** Retention (retention.enabled, native DELETE in VmStateHistoryRepository L103) can remove the row for a VM that has run for longer than the retention window; the UI then says 'Running' without a time. Do not fall back to createdAt or lastStateSyncAt: both would show a wrong time.

#### U05-T02 — Return the instance type in VM lists

- [ ] Done · **Type:** backend · **Estimate:** 0.5 d · **Risk:** low · **Sources:** EnvDetailLarge.dc.html, EnvDetail.dc.html, VmRegistry.dc.html · **Depends on:** U05-T01

The mockup's VM tables have a Size column (t3.medium, r6g.xlarge) but VmDTO has no instance type; it lives only in vm_inventory_snapshot.instance_type (VmInventorySnapshot L29-30), already read in batch by many services through VmInventorySnapshotRepository.findInstanceTypesByVmIds. RDS inventory stores the DB class there too (RdsCloudInventoryProviderService L92/L110).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/VmDTO.java` (modify) — Add String instanceType (null until inventory has been read)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmListDetailsService.java` (modify) — Details gains instanceType, read from the same inventory snapshots already loaded for privateIp (no extra query)
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — withDetails also sets instanceType

**Steps**

1. Extend Details with instanceType and fill it from VmInventorySnapshot.getInstanceType().
2. Set it in withDetails for every VM endpoint.
3. Add the field to the OpenAPI schema description.

**Acceptance criteria**

- Given an EC2 VM whose inventory snapshot says t3.medium, when the environment's VMs are listed, then instanceType is 't3.medium'.
- Given an RDS instance, when listed, then instanceType is its DB class (e.g. db.t3.medium).
- Given a VM never read by the inventory sync, when listed, then instanceType is null.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/service/VmListDetailsServiceTest.java (UPDATE): instanceType from the snapshot; null without one; still one inventory query

**Notes:** Instance type is not sensitive (unlike providerVmId), so it is visible to everyone who can view the environment.

#### U05-T03 — Return per-group state counts (and 'your access') on the environment list and group listing

- [ ] Done · **Type:** backend · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Dashboard.dc.html, Environments15.dc.html, AdminDashboard.dc.html, EnvDetailLarge.dc.html

EnvironmentDTO only has vmCount and runningVmCount (EnvironmentService.getBatchCounts L415-430 via VmRepository.countVmsGroupedByEnvironment), so Home cards and the 15-row environment list cannot draw a grouped power strip ('RRR|RR|SS') or show starting/failed/not-read VMs. The group listing in VmMgmtController (L101-131) has total and running per group only (VmRepository.countVmsGroupedByGroup L179-184), but EnvDetailLarge's group list needs '1 failed · 2 stopped'. 'Your access' (Operator / View only) is set only on the single-environment GET (EnvironmentController L296-297), not on lists. Group-only grants must see only their groups (SecurityService.getVisibleGroupIds L291).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — Add countVmsByGroupAndStatus(environmentIds): environmentId, groupId, group displayName, sequencePosition, status, lastStateSyncAt IS NULL flag, COUNT(v) for active VMs, grouped; projection GroupStatusCount
- `src/main/java/com/tcgdigital/vmcontrol/dto/GroupStateCountsDTO.java` (new) — record(groupId, name, sequencePosition, total, running, busy, stopped, problem, unknown); busy = STARTING/STOPPING, problem = ERROR/NOT_FOUND/TERMINATED, unknown = UNKNOWN or never synced
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — getGroupStateCounts(environmentIds, visibleGroupIdsByEnv) -> Map<envId, List<GroupStateCountsDTO>> sorted by sequencePosition
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — Add List<GroupStateCountsDTO> groupStates (null unless requested)
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — GET /environments and /environments/page accept includeGroups=true: fill groupStates, and viewerCanOperate/viewerCanAdminister per row; for viewers without environment-wide view (SecurityService.hasEnvironmentWideView false) restrict groups to getVisibleGroupIds and recompute vmCount/runningVmCount from them
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmGroupDTO.java` (modify) — fromEntityWithCounts gains busy/stopped/problem/unknown counts
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — Group listing (L102, L118-121) uses the per-status counts for its environment
- `src/main/resources/static/js/config.js` (modify) — Config.API.environments.page(...) and list gain an includeGroups option

**Steps**

1. Write the grouped count query (one query for all environments on the page) and map statuses to the five buckets.
2. Add GroupStateCountsDTO and EnvironmentService.getGroupStateCounts, filtering by visible groups for group-scoped viewers.
3. Wire includeGroups into both list endpoints; add viewerCanOperate/viewerCanAdminister per row (per-environment SecurityService calls are fine for page sizes up to 15; batch if profiling shows more than 50 ms).
4. Give the group listing in VmMgmtController the same breakdown through VmGroupDTO.
5. Add the option to Config.API so U06/U07 can request it.

**Acceptance criteria**

- Given an environment with groups Database (3 running), Application (2 running) and Web (2 stopped), when GET /api/v1/environments/page?includeGroups=true is called, then groupStates lists the three groups in start order with those counts.
- Given a user with a grant on one group only, when listing environments with includeGroups, then only that group appears and vmCount/runningVmCount match its counts.
- Given a user with Operator access on one environment and View only on another, when listing, then viewerCanOperate is true and false respectively.
- Given 15 environments, when the page is listed with includeGroups, then the group counts come from one query.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentListControllerTest.java (UPDATE): includeGroups payload, bucket mapping, order by sequencePosition, absent without the flag
- src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentViewerCapabilitiesTest.java (UPDATE): viewerCanOperate on list rows per role (SecuredWebTestBase users)
- src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentServiceTest.java (UPDATE): group-scoped viewer sees only visible groups

**Notes:** Month-to-date cost per environment for Home cards already exists in GET /api/v1/users/me/cost (MyEnvironmentCostDTO.monthToDateEstimated, nextStop, leaseEndsAt); U06 combines the two calls rather than adding cost here.

#### U05-T04 — Add an admin 'needs attention' summary endpoint

- [ ] Done · **Type:** backend · **Estimate:** 2 d · **Risk:** medium · **Sources:** AdminDashboard.dc.html, SystemHealth.dc.html

Admin Home's 'Needs attention' grid and health line need, in one call: pending access requests and the oldest wait, approvals waiting, failed or stuck operations in 24 h with environment names, VMs to review (drift and discovered-pending), budgets almost used, late scheduled jobs, last state sync and drift in 24 h. Today the pieces are spread: DashboardSummaryService counts pending requests and drift (L119-137) and builds scheduler health (buildSchedulerHealth L369, statuses HEALTHY/LATE/STALE/NEVER), VmService.getReviewCounts is per environment (L388), BudgetService.list computes percentUsed/forecastPercent, StateSyncService.getSyncStatus has the last sync, and nothing counts failed operations or pending approvals. The 'plan' line on the spend chart has no concept yet.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/AttentionSummaryDTO.java` (new) — record(accessRequests{count, oldestAt}, approvals{count, oldestAt}, failedOperations{count24h, stuck, environmentNames(max 3)}, vmsToReview{drift, pendingReview}, budgets{nearLimit, top{scopeName, percentUsed}}, lateJobs{count, worst{label, lateBySeconds}}, health{jobsEnabled, jobsOnTime, lastStateSyncAt, driftEvents24h}, monthPlan{amount, budgetCount} nullable, generatedAt)
- `src/main/java/com/tcgdigital/vmcontrol/service/AttentionSummaryService.java` (new) — Builds the DTO from repositories/services listed in steps; reuses DashboardSummaryService.buildSchedulerHealth(true) (same package)
- `src/main/java/com/tcgdigital/vmcontrol/controller/DashboardController.java` (modify) — GET /api/v1/dashboard/attention, @PreAuthorize("hasRole('ADMIN')")
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentAccessRequestRepository.java` (modify) — findTopByStatusOrderByCreatedAtAsc for the oldest pending request (countByStatus exists, L76)
- `src/main/java/com/tcgdigital/vmcontrol/repository/ApprovalRequestRepository.java` (modify) — countByStatus and findTopByStatusOrderByCreatedAtAsc
- `src/main/java/com/tcgdigital/vmcontrol/repository/OperationExecutionRepository.java` (modify) — Failed or partial-success executions started since a time, with environment names (JOIN FETCH environment); in-progress executions whose lastHeartbeatAt is older than vm.operations.stale-after-minutes
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — Global counts: active VMs with stateDriftDetected, and discovery-pending VMs (same predicates as countDriftInEnvironment/countPendingInEnvironment, without the environment filter)
- `src/main/resources/static/js/config.js` (modify) — Config.API.dashboard.attention

**Steps**

1. Add the repository methods; reuse VmStateHistoryRepository.countDriftEventsInRange (L66) for drift in 24 h and StateSyncService.getSyncStatus for the last sync.
2. Budgets near the limit: BudgetService.list() rows that are enabled with percentUsed >= 90 or forecastPercent >= 100; return the count and the highest one (skip when cost.snapshot.enabled is false).
3. Late jobs: buildSchedulerHealth(true) rows with LATE, STALE or NEVER (DISABLED excluded); lateBy = freshnessSeconds - expectedIntervalSeconds; jobsOnTime = HEALTHY + RUNNING.
4. Plan decision: monthPlan = sum of enabled TEAM budgets plus enabled ENVIRONMENT budgets of environments not covered by an enabled team budget (so nothing is counted twice); null when there are no enabled budgets, and Home then drops the plan line.
5. Expose GET /api/v1/dashboard/attention for administrators; return every block with zero counts rather than omitting it.
6. Add Config.API.dashboard.attention for U14.

**Acceptance criteria**

- Given 3 pending access requests, the oldest 2 days old, when an admin calls /api/v1/dashboard/attention, then accessRequests.count is 3 and oldestAt is that request's createdAt.
- Given two failed operations in the last 24 h on Analytics QA and CRM UAT and one older failure, when called, then failedOperations.count24h is 2 and environmentNames lists both.
- Given a team budget of 6,000 covering CRM UAT and an environment budget of 1,000 on CRM UAT plus 4,000 on Payments Dev, when called, then monthPlan.amount is 10,000.
- Given an environment admin, user or viewer, when they call the endpoint, then it returns 403.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/service/AttentionSummaryServiceTest.java (NEW, AbstractIntegrationTest): each block from seeded data, zero state, plan without double counting, disabled jobs excluded
- src/test/java/com/tcgdigital/vmcontrol/controller/AttentionSummarySecurityTest.java (NEW, SecuredWebTestBase): ADMIN 200; ENV_ADMIN, USER, VIEWER 403

**Notes:** The response carries counts and facts, not sentences; U14 writes the copy ('Oldest waiting 2 days'). An environment-admin scoped variant (SecurityService.administeredEnvironmentIdsOrAll) can follow if the env-admin Home needs it.

#### U05-T05 — Say how each person signs in (Microsoft or username) on user DTOs and the profile

- [ ] Done · **Type:** backend · **Estimate:** 0.5 d · **Risk:** low · **Sources:** Users.dc.html, UserDialogs.dc.html, MyAccount.dc.html, AddUsers.dc.html

UserDTO (dto/UserDTO.java) exposes username, hasPassword and unverified, but not whether the account signs in with Microsoft; that fact is User.azureAdObjectId (model/User.java L27-28). The Users table needs a 'Sign-in' column ('Microsoft'), the user dialog says 'signs in with Microsoft', and My account shows 'Microsoft (Entra ID)'. MyAccountService builds MyProfileDTO.authMethod from the global flag (L105: entraIdEnabled ? "ENTRA_ID" : "LOCAL"), so a password user in Entra mode is wrongly shown as Entra.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/UserDTO.java` (modify) — Add boolean signsInWithMicrosoft (azureAdObjectId != null, or onboarded by hand without a password and not a service account, since those people are expected to sign in with Microsoft) and String signInMethod: MICROSOFT, PASSWORD, MICROSOFT_AND_PASSWORD or NONE (service accounts); set both in fromEntity (L45-61)
- `src/main/java/com/tcgdigital/vmcontrol/service/MyAccountService.java` (modify) — authMethod (L105) from the current Authentication: OAuth2AuthenticationToken -> ENTRA_ID, otherwise LOCAL
- `src/main/java/com/tcgdigital/vmcontrol/dto/MyProfileDTO.java` (modify) — Javadoc: authMethod is how this session signed in

**Steps**

1. Compute signInMethod and signsInWithMicrosoft in UserDTO.fromEntity from azureAdObjectId, password, onboardedBy and serviceAccount.
2. Derive MyProfileDTO.authMethod from SecurityContextHolder's Authentication type instead of the entraid.enabled flag.
3. Update the OpenAPI descriptions of GET /api/v1/users and /users/me/profile.

**Acceptance criteria**

- Given a user linked to Entra (object id set) without a password, when users are listed, then signsInWithMicrosoft is true and signInMethod is MICROSOFT.
- Given a local account with a password and no object id, when listed, then signsInWithMicrosoft is false and signInMethod is PASSWORD.
- Given a person who signed in with a username while Entra is enabled, when they open My account, then authMethod is LOCAL.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/dto/UserDTOSignInTest.java (NEW, unit, unitTest task): all four combinations and the service account
- src/test/java/com/tcgdigital/vmcontrol/service/MyAccountServiceTest.java (UPDATE): authMethod follows the authentication type
- src/test/java/com/tcgdigital/vmcontrol/controller/UserControllerTest.java (UPDATE): list JSON contains signInMethod

**Notes:** No migration needed. Department and project code (new user fields) belong to U13.

#### U05-T06 — Filter a group's VM page by state (All, Running, Stopped, Problems)

- [ ] Done · **Type:** backend · **Estimate:** 0.5 d · **Risk:** low · **Sources:** EnvDetailLarge.dc.html · **Depends on:** U05-T01

An environment with 50+ VMs shows one group's VMs 10 per page with All / Running / Stopped / Problems filters (EnvDetailLarge). GET /environments/{id}/vms/{groupId}/page (VmMgmtController L161-187) pages by sequence position but cannot filter, so a client-side filter would only see the loaded page.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — listVmsPage gains @RequestParam(required=false) String state: running, stopped, problems (ERROR, NOT_FOUND, TERMINATED, or drift flagged); 400 on other values; default size stays 25, clients ask for 10
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — getVmsByGroupIdPaged(groupId, statuses, pageable) overload
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — Paged query by group and status set (ordered by sequencePosition), plus the drift-or-status variant for problems
- `src/main/resources/static/js/config.js` (modify) — Config.API.vms.groupPage(envId, groupId, page, size, state)

**Steps**

1. Map the state parameter to a status set (problems also includes stateDriftDetected = true).
2. Add the repository query and the service overload.
3. Validate the parameter and keep the existing security checks in listVmsPage.
4. Add the Config.API helper.

**Acceptance criteria**

- Given a group of 18 VMs with 14 running and 4 stopped, when page 0 size 10 state=stopped is requested, then 4 VMs return and totalElements is 4.
- Given state=problems, when one VM is ERROR and one is drift-flagged, then both return.
- Given state=bogus, when requested, then the API answers 400 with a message naming the allowed values.

**Tests**

- src/test/java/com/tcgdigital/vmcontrol/controller/VmListingIntegrationTest.java (UPDATE, from U05-T01): state filter, totals and 400

**Notes:** Uses the same withDetails enrichment as U05-T01/T02 so filtered pages carry stateSince and instanceType.

## U06

### Home & environment list — Everyday

People who run environments land on a Home that says what needs them and shows each environment as a card with its power strip, and the Environments list fits up to 15 environments on one screen with search, Running/Stopped filters, access and cost.

Sources: Dashboard.dc.html, Environments.dc.html, Environments15.dc.html, Known gap: plan line on Home, Known gap: per-group running counts for Home cards · 6 tasks · 7.5 days

#### U06-T01 — Extend the dashboard summary with what Home needs: per-environment cost, access, lock, lease and schedule, a 'needs you' list and idle VM rates

- [ ] Done · **Type:** backend · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Dashboard.dc.html · **Depends on:** U01-T01, U05-T03

DashboardSummaryService builds DashboardSummaryDTO.EnvironmentCardDTO with counts, CPU, storage and a bare locked flag only; Home cards need month-to-date cost, the viewer's access level, who holds the lock and until when, the lease end and next scheduled start/stop. TopVmDTO (toTopVm) has no environmentId or hourly rate, so 'Running but barely used' cannot link to the VM or show $/h. EnvironmentCostService.getMyEnvironmentsCost already computes cost, myLevel, nextStart/nextStop and leaseEndsAt per environment; Home should get it in one call instead of four.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/DashboardSummaryDTO.java` (modify) — EnvironmentCardDTO: add monthToDateCost, myAccess (ADMIN/USER/VIEWER, null for group-only grants), lockedByName, lockedByMe, lockExpiresAt, leaseEndsAt, nextStart, nextStop, transitionalVms, failedVms (plus the U05-T03 per-group counts). TopVmDTO: add environmentId, groupName, hourlyRate. SummaryDTO: add monthToDateCost. New record NeedsYouDTO(kind LEASE_ENDING|ACCESS_ENDING, environmentId, environmentName, at, referenceId) and a needsYou list on the root record.
- `src/main/java/com/tcgdigital/vmcontrol/service/DashboardSummaryService.java` (modify) — For the non-admin persona merge rows from EnvironmentCostService.getMyEnvironmentsCost(user) by environmentId; read the active lock holder/expiry from the lock repository in one query; build needsYou from leases ending within 3 h on environments the user can operate and the user's grants expiring within the profile's extensionWindowDays; fill TopVmDTO.hourlyRate through PricingReferenceService.lookupHourlyRate (same lookup as OperationCostPreviewService).
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentCostService.java` (modify) — Expose the per-environment rows used by getMyEnvironmentsCost (line ~203) as a reusable method taking the environment ids, so the dashboard does not pay ADMIN_LIST_LIMIT twice.
- `src/test/java/com/tcgdigital/vmcontrol/controller/DashboardScopeIntegrationTest.java` (modify) — Assert the new card fields, needsYou items and that a viewer sees myAccess=VIEWER.

**Steps**

1. Add the fields and the NeedsYouDTO record to DashboardSummaryDTO; keep every existing field so the admin dashboard keeps working until U14 replaces it.
2. In DashboardSummaryService, when persona != ADMIN, fetch the cost rows for the visible environment ids once and copy monthToDateEstimated, myLevel, nextStart, nextStop, leaseEndsAt onto each card; sum them into summary.monthToDateCost.
3. Load active locks for the visible environments in one repository call; set lockedByName, lockedByMe (holder == current user) and lockExpiresAt.
4. Count STARTING/STOPPING and ERROR VMs per environment into transitionalVms and failedVms.
5. Build needsYou: LEASE_ENDING for leases ending within 3 h where the user may operate; ACCESS_ENDING for the user's ACTIVE grants whose expiresAt is within extensionWindowDays (referenceId = accessId). Sort by 'at'.
6. Add environmentId, groupName and hourlyRate to TopVmDTO; hourlyRate null when the price is unknown.
7. No provider instance IDs anywhere in the new fields (U01-T01 rule).

**Acceptance criteria**

- Given a user with operator access to an environment whose lease ends in 2 h, when GET /api/v1/dashboard/summary is called, then needsYou contains one LEASE_ENDING item for it with 'at' equal to the lease end.
- Given a viewer grant expiring in 3 days, when the summary is read, then needsYou has an ACCESS_ENDING item with the accessId and the card's myAccess is VIEWER.
- Given an environment locked by someone else, when the summary is read, then its card has lockedByName set and lockedByMe false.
- Given an admin, when the summary is read, then the response still carries the existing admin fields unchanged.

**Tests**

- DashboardScopeIntegrationTest (UPDATE): new card fields, needsYou for lease and access, viewer myAccess
- DashboardSummaryServiceSchedulerHealthTest (UPDATE if constructor changes): still green

**Notes:** Decision on the 'plan' line: Home shows only '$X this month' (summary.monthToDateCost); no plan/budget sum line, because budgets are per environment and owner-only. If U05-T03 already adds per-group counts to EnvironmentCardDTO, reuse them; otherwise add them here.

#### U06-T02 — Rebuild the non-admin dashboard as Home: header line, 'Needs you' strip and environment cards with power strips

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Dashboard.dc.html · **Depends on:** U06-T01, U03-T02, U03-T03, U04-T03

Dashboard.buildUserDashboard (dashboard.js ~L200) renders KPI tiles, an environment table with search, an ECharts donut and a utilization trend; the mockup Home replaces all of it with a one-line summary, actionable 'Needs you' tiles and up to four environment cards (name, slug, state chip, large power strip, one status line, cost this month, access). The refresh/auto-refresh buttons disappear; Home refreshes itself quietly.

**Files**

- `src/main/resources/static/js/features/home-model.js` (new) — HomeModel (IIFE, no DOM): chip(card) -> Running/Starting/Stopping/Stopped/Problems; cardLine(card, now) -> at most two parts joined with ' · ' (e.g. 'Web group stopped · stops at 20:00', 'Starting 1 VM · locked by you until 14:30', 'Stopped since yesterday · starts Mon 08:00', 'Locked by Rahul Verma'); accessLabel(level) -> Admin/Operator/View only; needsYouText(item); pickCards(cards, 4) ordering mine-locked/running first.
- `src/main/resources/static/js/features/dashboard.js` (modify) — For persona != ADMIN replace buildMetricStrip/buildUserDashboard/buildEnvironmentPanel with buildHome(data): h1 'Home', subtitle 'N of M VMs running across K environments · $X this month', 'Request access to an environment' button (#/request-access), Needs-you section (aria-label 'Needs you'), 'Your environments' cards grid using PowerStrip (large) and HomeModel. Drop the header Refresh/Auto toggle for this persona and run silentRefresh via RealTime.startPolling('dashboard', ..., 30000, { pageScoped: true }). Keep buildAdminDashboard untouched (U14).
- `src/main/resources/static/css/features/dashboard.css` (modify) — Home grid (4 columns at 1440, 2 below 1100), card, chip and needs-you tile styles on the U03-T01 tokens; remove rules only used by the old user table.
- `src/main/resources/static/js/features/my-account.js` (modify) — Open the 'Request extension' form for the grant named by ?extend=<accessId> once on load (the 'Ask for more time' link target).
- `src/main/resources/static/index.html` (modify) — Add js/features/home-model.js before dashboard.js; bump ?v= stamp.
- `src/test/js/home-model.test.mjs` (new) — node --test for chip, cardLine and pickCards.
- `test/tests/dashboard/dashboard-search.spec.ts` (delete) — Replaced by home-cards.spec.ts (Home has no search; the badge it checked is gone).
- `test/tests/dashboard/home-cards.spec.ts` (new) — Faked summary: cards show name, slug, chip, strip label 'N of M VMs running', line and access; a LEASE_ENDING item links to #/environments/<id>?extendLease=1.
- `test/tests/dashboard/dashboard.spec.ts` (modify) — Expect the 'Your environments' section instead of #dashboard-environments-table for a user.
- `test/tests/navigation/page-takeover.spec.ts` (modify) — Dashboard lifecycle tests: header text 'Home'; the auto-refresh test no longer types into #dashboard-env-search or clicks #dashboard-auto-refresh-toggle, it checks a silent poll re-renders without leaving the page.

**Steps**

1. Write HomeModel with the line rules: transitional first ('Starting N VMs'), then failures ('N failed to start'), then partial ('<Group> group stopped' when exactly one group is fully stopped, else 'N of M running'), then stopped ('Stopped' + ' · starts <day HH:MM>' from nextStart), then running ('stops at HH:MM' from the earlier of leaseEndsAt/nextStop); lock part 'locked by you until HH:MM' or 'Locked by <name>'. Keep two parts maximum.
2. In dashboard.js branch on persona: ADMIN keeps today's layout; others call buildHome(data).
3. Needs-you tiles: LEASE_ENDING -> '<Env> stops at HH:MM, in 2 h 15 min' + 'Extend lease' (href #/environments/<id>?extendLease=1, handled by Leases.openExtendFromRoute); ACCESS_ENDING -> 'Your access to <Env> ends 22 Oct' + 'Ask for more time' (href #/my-account?extend=<accessId>). Hide the section when empty.
4. Cards: HomeModel.pickCards(data.environments, 4); each card is one link to #/environments/<id>; PowerStrip from the U05-T03 group counts; footer '$X this month' and access label. Link 'See all as a list' to #/my-environments.
5. Empty state when the user has no environments: 'You have no environments yet.' + 'Request access to an environment'.
6. Replace the refresh toggle with a page-scoped 30 s silent poll; keep the dashboardSignature check so an unchanged summary does not re-render.
7. Update the listed specs in this task; bump the asset stamp.

**Acceptance criteria**

- Given a user with four environments, when Home opens at 1440x900, then four cards, the header line and the needs-you strip are visible without a page scrollbar.
- Given an environment with RRR|RR|SS states and a lease ending at 20:00, when Home renders, then its card shows chip 'Running', a strip labelled '5 of 7 VMs running' and the line 'Web group stopped · stops at 20:00'.
- Given a viewer-only environment, when Home renders, then the card footer says 'View only'.
- Given an admin, when Home opens, then the existing admin dashboard still renders (until U14).

**Tests**

- src/test/js/home-model.test.mjs (NEW): chip, line rules, card order
- test/tests/dashboard/home-cards.spec.ts (NEW): cards and needs-you links
- test/tests/dashboard/dashboard.spec.ts (UPDATE)
- test/tests/navigation/page-takeover.spec.ts (UPDATE): Home header and silent refresh

**Notes:** Sidebar label 'Dashboard' -> 'Home' belongs to U04-T01; this task only changes the page. Route stays #/dashboard so bookmarks and the 'g d' shortcut keep working.

#### U06-T03 — Add 'Running but barely used' and 'Your recent activity' panels to Home

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** Dashboard.dc.html · **Depends on:** U06-T02

buildListPanel('Idle VMs', ...) lists idle VMs with only name, environment and idle duration and no action; the mockup shows CPU, $/h and a Review link to the VM, and a recent-activity panel fed by GET /users/me/activity (Config.API.users.myActivity) that the dashboard does not call today.

**Files**

- `src/main/resources/static/js/features/dashboard.js` (modify) — buildIdlePanel(data.idleVms): top 2 rows '<vm> · <env> · CPU 3.9%', '$0.07/h', 'Review' link to #/environments/<envId>?vm=<vmId>; subtitle 'Under 5% CPU for 6 hours. Stopping them saves money.'; buildRecentPanel(): fetch myActivity(4) after the summary, rows time/text/result ('Done' green, 'Failed' red), 'All activity' link to #/my-account?tab=activity.
- `src/main/resources/static/js/features/home-model.js` (modify) — activityText(item): 'Started Payments Dev (5 VMs)', 'Locked Analytics QA until 14:30', 'Stopped CRM UAT (6 VMs)'; resultLabel(status).
- `src/main/resources/static/js/features/environments.js` (modify) — loadDetail: when the route query has vm=<id>, open the VM details panel for it once after render (replaceState drops the parameter).
- `src/test/js/home-model.test.mjs` (modify) — Cases for activityText and resultLabel.
- `test/tests/dashboard/home-cards.spec.ts` (modify) — Idle row Review link and recent-activity rows from a faked /users/me/activity.

**Steps**

1. Render the two panels in a 2-column row under the cards; each shows at most the rows that fit (idle 2, activity 4) so the page never scrolls.
2. Idle rows come from data.idleVms (U06-T01 adds environmentId and hourlyRate); hide $/h when null; hide the panel when there are no idle VMs and let the activity panel span both columns.
3. Load activity with ApiClient.get(Config.API.users.myActivity(4), { suppressGlobalError: true }); on failure show 'Activity is not available right now.' inside the panel.
4. Add the ?vm= deep link to Environments.loadDetail so Review opens the VM panel.
5. Extend the node test and Playwright spec.

**Acceptance criteria**

- Given an idle VM with a known price, when Home renders, then its row shows CPU, '$0.07/h' and a Review link that opens the environment with that VM's details panel open.
- Given the activity endpoint fails, when Home renders, then the rest of Home still shows and the panel says activity is not available.
- Given a failed start in my activity, when Home renders, then the row result reads 'Failed' in red.

**Tests**

- src/test/js/home-model.test.mjs (UPDATE): activity text
- test/tests/dashboard/home-cards.spec.ts (UPDATE): idle and activity panels

**Notes:** Activity text must not contain instance IDs; the server text already follows U01-T03.

#### U06-T04 — Give the environment list endpoint a Running/Stopped filter, the viewer's access, this month's cost and fleet totals

- [ ] Done · **Type:** backend · **Estimate:** 1 d · **Risk:** medium · **Sources:** Environments.dc.html, Environments15.dc.html · **Depends on:** U06-T01, U05-T03

EnvironmentController.listEnvironmentsPaged (/environments/page) supports only search + paging and returns vmCount/runningVmCount and lock fields; the list screen filters All/Running/Stopped across pages, shows 'Your access' and 'This month' per row, and its header says '15 environments · X of Y VMs running' for the whole filtered set, not one page.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — listEnvironmentsPaged (L128-162): optional state=RUNNING|STOPPED param (400 otherwise); set myAccess and monthToDateCost on each DTO; response header or wrapper with totals (environments, vmCount, runningVmCount) for the filtered set.
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — Add myAccess (String) and monthToDateCost (BigDecimal), null when not computed.
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentRepository.java` (modify) — Search queries gain an optional running filter: EXISTS / NOT EXISTS (active VM with status RUNNING) so paging stays server-side.
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentService.java` (modify) — Pass the state filter through getEnvironmentsForCurrentUser/getAllActiveEnvironments; totals query for the same filter.
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentListControllerTest.java` (modify) — state filter, myAccess, cost and totals; invalid state -> 400.

**Steps**

1. Add the state parameter and validate it.
2. Implement the EXISTS/NOT EXISTS predicate in the repository search methods used for admins and for scoped users.
3. Fill myAccess from the grants already loaded for scoping (ADMIN for platform admins), and monthToDateCost from the reusable EnvironmentCostService method added in U06-T01 (one call per page).
4. Return totals as X-Total-Vms / X-Running-Vms headers (keeps Page<EnvironmentDTO> shape for existing callers) and document them in the OpenAPI annotation.
5. Add the U05-T03 per-group counts to the page rows if U05-T03 did not.

**Acceptance criteria**

- Given 15 environments of which 9 run something, when GET /environments/page?state=RUNNING&size=15 is called, then 9 rows return and X-Running-Vms counts only those.
- Given a viewer grant, when the page is read, then the row's myAccess is VIEWER.
- Given state=FOO, when called, then the response is 400 with a message naming RUNNING and STOPPED.

**Tests**

- EnvironmentListControllerTest (UPDATE): filter, access, cost, totals, 400

**Notes:** Page size stays clamped by Paging.DEFAULT_MAX_SIZE; the front end asks for 15.

#### U06-T05 — Redesign the Environments list: 15 per page, search, All/Running/Stopped, power strips, lock, access and cost

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Environments.dc.html, Environments15.dc.html · **Depends on:** U06-T04, U06-T02, U03-T03, U04-T02

Environments.buildListHtml/buildEnvRows (environments.js L369-525) title the page 'My Environments', page 10 rows with Groups/Total VMs/Running badge columns and per-row Edit/Deactivate icons for env admins; the mockup shows Environment (name + slug), Type, Region, VMs (power strip), Lock, Your access, This month and Open, with a status filter and no pager until there are more than 15.

**Files**

- `src/main/resources/static/js/features/environments.js` (modify) — ENV_PAGE_SIZE 10 -> 15; buildListHtml: h1 'Environments', subtitle 'The environments you can see. Open one to start, stop or lock it.' plus 'N environments · X of Y VMs running' from the totals headers, labelled search input, segmented filter (role=group aria-label 'Show', aria-pressed buttons); buildEnvRows: new columns, PowerStrip, lock text 'Not locked' / 'Locked by you' / holder name, HomeModel.accessLabel, cost; remove edit-env/delete-env buttons and their handlers in bindListEvents (VM registry keeps Edit/Deactivate); footer 'N environments'; pager only when totalElements > 15.
- `src/main/resources/static/js/config.js` (modify) — environments.page(page, size, search, state) adds &state=.
- `src/main/resources/static/css/features/environments.css` (modify) — 38 px rows, column widths that fit 1440 with a 240 px side menu; mono slug/cost.
- `src/main/resources/static/index.html` (modify) — Bump ?v= stamp.
- `test/tests/environments/environment-list.spec.ts` (modify) — Rows: holder shown in the Lock column, 'Not locked' instead of 'Unlocked', access and cost cells, filter sends state=RUNNING, no per-row lock request.
- `test/tests/navigation/page-takeover.spec.ts` (modify) — Header text 'Environments' instead of 'My Environments'.
- `test/tests/a11y/contrast.spec.ts` (modify) — Entry label 'Environments'; search selector stays #env-list-search.

**Steps**

1. Keep the route #/my-environments; change visible text only (sidebar label is U04-T01).
2. Build the header with counts from the X-Total-Vms/X-Running-Vms headers (read via the jqXHR).
3. Filter buttons All/Running/Stopped reset to page 0 and call fetchEnvironmentsPage(page, search, state); keep the 300 ms debounced search; the empty-filter message is 'No environments match this filter.' and keeps the toolbar mounted.
4. Rows: the name link and the Open link both go to #/environments/<id>; drop the tooltip-heavy description title and the Groups/Total columns; the Account column rule (multiAccount) stays.
5. Render Pagination only when totalElements > 15, through the U04-T02 pager.
6. Update the listed specs; bump the stamp.

**Acceptance criteria**

- Given 15 environments, when the list opens at 1440x900, then all 15 rows show with no pager and no page scrollbar.
- Given the Running filter, when it is pressed, then only environments with a running VM show and the button has aria-pressed=true.
- Given an env admin, when the list renders, then no Edit or Deactivate buttons appear in rows.
- Given an environment locked by the viewer, when the list renders, then its Lock cell reads 'Locked by you'.

**Tests**

- test/tests/environments/environment-list.spec.ts (UPDATE)
- test/tests/navigation/page-takeover.spec.ts (UPDATE)
- test/tests/a11y/contrast.spec.ts (UPDATE)

**Notes:** Environments with more than 24 VMs show the strip as a bar (PowerStrip rule from U03-T03).

#### U06-T06 — Cover Home and the list per role and at 1440x900

- [ ] Done · **Type:** test · **Estimate:** 1 d · **Risk:** low · **Sources:** Dashboard.dc.html, Environments15.dc.html · **Depends on:** U06-T03, U06-T05

The new Home and list are role-dependent (operator, viewer, env admin) and must fit 1440x900 with up to 15 environments; nothing checks the no-scrollbar rule or the role differences today.

**Files**

- `test/tests/dashboard/home-roles.spec.ts` (new) — user-001 (operator), user-002 (viewer), dev-user-001 (env admin): Home cards, access labels, needs-you visibility; document.scrollingElement scrollHeight <= clientHeight and scrollWidth <= clientWidth at 1440x900.
- `test/tests/environments/environment-list-fit.spec.ts` (new) — Fake 15 rows incl. a 54-VM environment: no page scrollbars, bar instead of lights for the big one, pager hidden; 16 rows: pager visible.
- `test/fixtures/mock-api.ts` (modify) — Helpers to fake the new summary fields and the list totals headers.

**Steps**

1. Add mock helpers for DashboardSummaryDTO cards/needsYou and /environments/page totals.
2. Write the role spec with X-User-Id sign-in from fixtures/auth.
3. Write the fit spec at viewport 1440x900 checking both scroll axes.
4. Run against the smoke app on vmcontrol_test.

**Acceptance criteria**

- Given each dev user, when Home opens, then the page has no horizontal or vertical page scrollbar.
- Given 16 environments, when the list opens, then the pager shows '1-15 of 16'.

**Tests**

- test/tests/dashboard/home-roles.spec.ts (NEW)
- test/tests/environments/environment-list-fit.spec.ts (NEW)

## U07

### Environment page — Everyday

The environment page leads with the start order and the VMs, keeps lock, lease, schedule, idle auto-stop and cost in a side column, shows every alert in one ordered stack, scales to 50+ VMs with a group list, and renders EKS node groups properly, all within 1440x900.

Sources: EnvDetail.dc.html, EnvDetailViewer.dc.html, EnvDetailLarge.dc.html, EnvAlerts.dc.html, EksEnv.dc.html, LockLease.dc.html, Audit: 3-4 failing background requests per env page · 9 tasks · 12.5 days

#### U07-T01 — Add 24 h CPU per VM and the viewer's access end to the environment page payloads

- [ ] Done · **Type:** backend · **Estimate:** 1 d · **Risk:** low · **Sources:** EnvDetail.dc.html, EnvDetailViewer.dc.html · **Depends on:** U01-T01

The VM table shows 'CPU 24 h' per VM, but VmDTO (GET /environments/{id}/vms, VmMgmtController.listVms) carries no utilization; the viewer note says 'Your view access ends on 17 Nov', but EnvironmentDTO only has viewerCanOperate/viewerCanAdminister. VmMetricSampleRepository already has aggregateByVmForPeriod for a single grouped AVG query.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/repository/VmMetricSampleRepository.java` (modify) — New query avgCpuByVmIdsSince(vmIds, since): vmId, AVG(cpuUtilization) grouped by vm.
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmDTO.java` (modify) — Add cpuAvg24h (BigDecimal, null when no samples).
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — listVms (L71-160): one aggregate call for all listed VM ids, set cpuAvg24h.
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — Add viewerAccessLevel and viewerAccessEndsAt (detail endpoint only, like viewerCanOperate).
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — getEnvironment (L268-300): set the two viewer fields from the caller's effective grant (null for platform admins and grants without expiry).
- `src/test/java/com/tcgdigital/vmcontrol/controller/EnvironmentViewerCapabilitiesTest.java` (modify) — viewerAccessLevel/EndsAt for viewer and operator.

**Steps**

1. Write the aggregate query (JPQL, MySQL-safe) and a repository test with two VMs.
2. Call it once per listVms request with since = now - 24 h; map by vmId.
3. Resolve the caller's effective grant in getEnvironment and set the viewer fields.
4. Keep providerVmId handling as U01-T01 defines it.

**Acceptance criteria**

- Given a VM with samples of 20% and 40% in the last 24 h, when the environment's VMs are listed, then its cpuAvg24h is 30.
- Given a viewer whose grant ends 17 Nov, when the environment is read, then viewerAccessLevel is VIEWER and viewerAccessEndsAt is 17 Nov.
- Given a VM without samples, when listed, then cpuAvg24h is null.

**Tests**

- EnvironmentViewerCapabilitiesTest (UPDATE)
- VmMgmtController listVms integration test (NEW or UPDATE existing VmMgmt test): cpuAvg24h

**Notes:** Running since and instance type come from U05-T01 and U05-T02.

#### U07-T02 — Rebuild the environment page frame: breadcrumb, meta line, header actions, start-order strip and a two-column body

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** EnvDetail.dc.html, EnvDetailViewer.dc.html · **Depends on:** U07-T01, U02-T02, U02-T03, U03-T03, U04-T02, U04-T03

Environments.buildDetailHtml (environments.js L637-756) stacks breadcrumb 'My Environments', title, description, summary dot, Insights/History/Access/Notify/Start All buttons, then lock/approval/lease banners, groups accordion and a 'Cost & settings' section of seven panels that pushes the page past 900 px. The mockup has a meta line (slug · AWS EC2 · region · team), Insights/History/Stop all/'Start the N stopped' actions, a 'Start order' strip with arrows between groups, and a main column plus an 'About this environment' aside.

**Files**

- `src/main/resources/static/js/features/environments.js` (modify) — buildDetailHtml: U04-T03 page header with breadcrumb 'Environments / <name>', meta line from name, serviceType, regions, teamName; actions: Insights (#btn-env-insights), History (#btn-operation-history), 'Stop all' (#btn-env-action-stop, shown when any VM runs), primary 'Start the N stopped' or 'Start all' (#btn-env-action, shown when any VM is stopped); env admins get 'Access' and a 'Settings' button; 'Notify' moves into the Settings panel. Body: <div class=env-body><main id=env-main> + <aside id=env-aside aria-label='About this environment'>. Start-order section (aria-label 'Start order') with one labelled PowerStrip per group in sequencePosition order and arrow icons between them.
- `src/main/resources/static/js/features/env-settings-panel.js` (new) — EnvSettingsPanel.open(env): side panel (U04-T02) hosting #env-spot-panel, #env-chat-panel, #env-webhook-panel and the Notify action, rendered by SpotPanel.render, ChatChannels.renderEnvPanel, WebhooksPanel.renderEnvPanel only when opened (no background requests on page load).
- `src/main/resources/static/css/features/environments.css` (modify) — Two-column grid (main flexible, aside 320 px), start-order strip, header actions; remove group-card styles once U07-T03 lands.
- `src/main/resources/static/index.html` (modify) — Add env-settings-panel.js after environments.js; bump stamp.
- `test/tests/environments/view-only.spec.ts` (modify) — Wait for #env-detail-view .env-start-order instead of .env-detail-summary; breadcrumb href stays #/my-environments with text 'Environments'; operator sees #btn-env-action text /Start (all|the \d+ stopped)/; remove the env-section-title order check (no 'Cost & settings' section).
- `test/tests/environments/spot-panel.spec.ts` (modify) — Open the Settings panel before asserting the Spot panel.
- `test/tests/environments/chat-channels.spec.ts` (modify) — Open the Settings panel first.
- `test/tests/environments/webhooks.spec.ts` (modify) — Open the Settings panel first.

**Steps**

1. Split buildDetailHtml into buildHeader(env), buildStartOrder(env), and the body shell; keep #env-detail-view as the root id.
2. Count stopped VMs for the primary label: 'Start all' when none run, 'Start the N stopped' when some run; hide both start and stop when viewerCanOperate is false.
3. Move Spot/Teams/Webhook panels and Notify into EnvSettingsPanel; remove their render calls from bindDetailEvents so the page load makes no requests for them.
4. Keep bindOperationStatusRefresh; the re-render must keep the aside and the open side panel state.
5. Leave the old group cards in #env-main until U07-T03 replaces them.
6. Update the listed specs; bump the stamp.

**Acceptance criteria**

- Given an operator on a 7-VM environment with 2 stopped, when the page opens, then the header shows 'Start the 2 stopped' and 'Stop all', and the start order reads Database -> Application -> Web.
- Given a viewer, when the page opens, then no Start/Stop buttons render and Insights and History are still there.
- Given an env admin, when the page loads, then no Spot, Teams or webhook request is made until Settings is opened.

**Tests**

- test/tests/environments/view-only.spec.ts (UPDATE)
- test/tests/environments/spot-panel.spec.ts (UPDATE)
- test/tests/environments/chat-channels.spec.ts (UPDATE)
- test/tests/environments/webhooks.spec.ts (UPDATE)
- test/tests/navigation/background-polls.spec.ts (UPDATE if it counts env page requests)

**Notes:** U11 may later move the Settings panel contents to an environment settings page; this task only keeps them reachable.

#### U07-T03 — Replace the group accordions with one 'Groups and VMs' table: group rows with Start/Stop group, VM rows with IP, size, running since and CPU

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** EnvDetail.dc.html, EnvDetailViewer.dc.html · **Depends on:** U01-T01, U01-T05, U05-T01, U05-T02, U07-T02

buildGroupCard (environments.js L767-903) renders a collapsible card per group with VM Name/Location/Status/Order columns and icon buttons; the mockup shows one table where each group is a rowgroup header ('Database · Start order 1', 'after Database') with 'Start group'/'Stop group', and VM rows with IP address, Size, 'Running since 08:02' or 'Stopped', CPU 24 h, Details and Start/Stop.

**Files**

- `src/main/resources/static/js/features/env-vm-table.js` (new) — EnvVmTable.render(env, { canOperate }): table with <th scope=rowgroup colspan=5> group rows and VM rows; state cell: dot + 'Running since HH:MM' (U05-T01 runningSince, date when not today), 'Starting'/'Stopping', 'Failed to start' (red), 'Stopped'; IP from privateIp; Size from U05-T02 instanceType; CPU from cpuAvg24h ('—' when null); Spot and RDS badges kept; Details (data-action=vm-details), Start/Stop/Retry (data-action=start-vm|stop-vm), group action (data-action=group-action) with 'Start group'/'Stop group' (both when partial); rdsAutoStartNote moved here.
- `src/main/resources/static/js/features/environments.js` (modify) — buildDetailHtml uses EnvVmTable.render inside section#env-vms (h2 'Groups and VMs'); delete buildGroupCard and the .group-card-toggle handler; findGroup/findVm unchanged; the view-only note text becomes 'You can see this environment but not start or stop it. Your view access ends on <date>.' with 'Ask for operator access' (#/request-access?environmentId=<id>).
- `src/main/resources/static/index.html` (modify) — Add env-vm-table.js before environments.js; bump stamp.
- `test/tests/a11y/keyboard-controls.spec.ts` (modify) — Replace the .group-card-toggle test with: every row action is a focusable button with a name ('Start web-01', 'Stop group Database').
- `test/tests/environments/view-only.spec.ts` (modify) — View-only note text and link; no [data-action=group-action] and no [data-action=start-vm] for a viewer.
- `test/tests/environments/rds-auto-start.spec.ts` (modify) — Find the note in the VM row of the new table.
- `test/tests/navigation/page-takeover.spec.ts` (modify) — Any .group-card selectors -> #env-vms rows.

**Steps**

1. Move VM row and group row rendering into EnvVmTable; give every button an aria-label naming its target.
2. Group header text: '<Group> ' + small 'Start order N' + (' · after <names>' from dependsOnGroupIds, as today).
3. Use this table when VMs + groups <= 12; put the threshold in one function (useLargeLayout(env)) so U07-T04 can switch bigger environments to the group-list layout; until then they use this table too.
4. Keep data-action names so bindDetailEvents handlers keep working; bind once on #content-area with the .envDetail namespace.
5. Update the listed specs; bump the stamp.
6. RDS rows keep the 'DB instance' / 'Aurora cluster' label (read VmDTO.rdsKind from U01-T01 instead of the 'cluster:' prefix, environments.js L809) and the note for a stopped database 'AWS will restart it on <date>' with the keep-stopped wording (environments.js L75-95, Config.API.rds.status asked once).

**Acceptance criteria**

- Given the Payments Dev data (3 groups, 7 VMs), when the page opens, then the table shows 3 group rows and 7 VM rows and pay-db-primary reads 'Running since 08:02', '10.20.1.11', 'r6g.large', '23%'.
- Given a group whose VMs all run, when its row renders, then it offers 'Stop group' only.
- Given a viewer, when the page opens, then the table has Details links but no Start, Stop or group buttons.
- Given an ERROR VM, when the table renders, then its state reads 'Failed to start' and its action is 'Retry'.
- Given an environment with a stopped RDS database, then its row shows 'DB instance' or 'Aurora cluster' and when AWS will start it again, without showing the identifier to non-admins.

**Tests**

- test/tests/a11y/keyboard-controls.spec.ts (UPDATE)
- test/tests/environments/view-only.spec.ts (UPDATE)
- test/tests/environments/rds-auto-start.spec.ts (UPDATE)
- test/tests/navigation/page-takeover.spec.ts (UPDATE)
- test/tests/inventory/vm-registry-rds.spec.ts or the environment spec (UPDATE): RDS label and restart note on the new table

**Notes:** No instance IDs in the table for anyone; admins see them in VM details (U08-T05).

#### U07-T04 — Add the large-environment layout: full grouped strip, group list, one group's VMs with filters and 10 per page

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** EnvDetailLarge.dc.html · **Depends on:** U07-T03, U04-T02

With 50+ VMs, the accordion of buildGroupCard (only the first group opens when totalVms > 30) makes a page several screens tall. EnvDetailLarge shows one strip of all VMs with running/stopped/failed counts and the start order, a group list (order, name, running/size, note) and the selected group's VMs with All/Running/Stopped/Problems filters, 10 per page, and the group's Start/Stop.

**Files**

- `src/main/resources/static/js/features/env-vm-table.js` (modify) — EnvVmTable.renderLarge(env, state, { canOperate }) where state = { groupId, filter, page }: summary strip section (aria-label 'All N VMs in start order') with '<b>44</b> running · 9 stopped · 1 failed to start' and 'Start order: A -> B -> C'; nav aria-label 'Groups' of buttons with aria-current; selected group h2 '<name> · N VMs', filter group, group action, VM table (no CPU column), range 'a-b of n' or 'Nothing matches this filter', Previous/Next through the U04-T02 pager.
- `src/main/resources/static/js/features/environments.js` (modify) — Choose renderLarge when VMs + groups > 12; keep the large state per environment id across bindOperationStatusRefresh re-renders; first selected group is the first with a problem, else the first in start order.
- `src/main/resources/static/css/features/environments.css` (modify) — Group list column (260 px) + VM panel; active group marker in gold (inset 3px).
- `src/test/js/env-vm-table.test.mjs` (new) — filter + paging + counts helpers of EnvVmTable (pure functions exported as EnvVmTable._model).
- `test/tests/environments/large-environment.spec.ts` (new) — Faked 54 VMs in 4 groups: group list counts, Problems filter shows the failed VM with Retry, Next shows 11-18 of 18, no page scrollbar at 1440x900.

**Steps**

1. Write pure helpers: countByState(vms), filterVms(vms, filter), pageOf(list, page, 10).
2. Render the summary strip with PowerStrip (bar form above 24 VMs as U03-T03 defines).
3. Group buttons switch groupId and reset filter/page; filters reset page; all without refetching.
4. Keep the state when the vm-operation-status refresh re-renders the page.
5. Write the node test and the Playwright spec.

**Acceptance criteria**

- Given 54 VMs in 4 groups, when the page opens, then the summary reads '44 running', '9 stopped', '1 failed to start' and the group list shows 'Application 14/18'.
- Given the Application group with 18 VMs, when Next is pressed, then rows 11-18 show and Next is disabled.
- Given the Problems filter on a group without failures, when pressed, then the panel says 'Nothing matches this filter'.
- Given an operation finishes, when the page refreshes, then the same group, filter and page stay selected.

**Tests**

- src/test/js/env-vm-table.test.mjs (NEW)
- test/tests/environments/large-environment.spec.ts (NEW)

**Notes:** The 12-row threshold is the decision for 'fits 1440x900' on the single table; revisit if the aside grows.

#### U07-T05 — Move lock and lease into the side column and rebuild their dialogs with duration chips

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** EnvDetail.dc.html, LockLease.dc.html, EnvDetailViewer.dc.html · **Depends on:** U07-T02, U03-T02

Locks.buildLockBanner (locks.js L328-420) and Leases.buildSection (leases.js L70-120) render full-width banners above the VMs; the mockup puts them as 'Lock' and 'Lease' facts in the aside with one action each ('Lock', 'Extend'). LockLease shows the lock dialog with 'Why (others see this)' and Until chips (1 hour, 2 hours, End of day, Until I release it) and the lease dialog with 'Extend by' chips (30 min, 1 h, 2 h, 8 h, 12 h), the new stop time, extra cost and the owner reason only above the maximum. Today both use selects (#lockDuration, #lease-extend-minutes).

**Files**

- `src/main/resources/static/js/features/locks.js` (modify) — New buildLockFact(lockStatus, envId): label 'Lock', value 'Not locked' / 'Locked by you until 14:30' / 'Locked by <name>', note (reason in quotes, 'since 09:10', or 'Lock it so others can't start or stop VMs'), action Lock/Release/Extend/Break from canAcquire/canRelease/canExtend/canBreak, 'Lock history' link; acquire(): chips + reason input (label 'Why (others see this)'); End of day = minutes to 23:59 local capped by the server limit; without lockExpiryEnabled only 'Until I release it' with the existing note. Keep .lock-countdown behaviour on the fact.
- `src/main/resources/static/js/features/leases.js` (modify) — buildFact(current): 'Stops at 20:00 · in 2 h 15 min', note 'Up to N h more without approval' (policy.effectiveMaxMinutes minus elapsed), Extend action, pending pill and owner Approve/Deny rows kept (aside list); render() targets #env-fact-lease; showExtendDialog uses chips [30, 60, 120, 480, 720] filtered by platform max, 'It stops at HH:MM, in X. You're reminded 60, 15 and 5 minutes before.', new stop time, CostPreview extension cost, reason input only when needsApproval, CTA 'Extend by 2 h' or 'Ask <owner>'.
- `src/main/resources/static/js/features/environments.js` (modify) — Aside renders #env-fact-lock and #env-fact-lease; remove ${lockSection} and #env-lease-section from the main column.
- `src/main/resources/static/css/components/locks.css` (modify) — Fact row styles; chip group (role=group, aria-pressed).
- `src/main/resources/static/css/features/leases.css` (modify) — Fact row and request list in the aside.
- `test/tests/environments/lock-banner.spec.ts` (modify) — Selectors #lock-e2e .lock-banner -> the lock fact; 'Held by Alice' -> 'Locked by Alice <b>A</b>' (escaped); acquire dialog chips instead of #lockDuration; #lockDurationHelp text kept; expiring class on the fact.
- `test/tests/environments/lease-banner.spec.ts` (modify) — #env-lease-section .lease-banner -> #env-fact-lease; extend dialog chips instead of select; pending pill and owner approve flow unchanged.
- `test/tests/access/my-account-extension.spec.ts` (modify) — Only if it drives Leases.showExtendDialog: pick a chip instead of selecting an option.

**Steps**

1. Add buildLockFact next to buildLockBanner, switch the page to it, then delete buildLockBanner once no caller remains.
2. Rebuild acquire() and extend() dialogs with chip groups (buttons with aria-pressed); keep the request bodies (expectedDurationMinutes, minutes, reason).
3. Rebuild the lease fact and the extend dialog; keep openExtendFromRoute (Home links to ?extendLease=1).
4. Leases off (leaseEnabled null) -> no lease fact and no lease request (U02-T03).
5. Update the specs; bump the stamp.

**Acceptance criteria**

- Given an unlocked environment and an operator, when the aside renders, then the Lock fact reads 'Not locked' with a 'Lock' action and the note about others.
- Given the lock dialog, when '2 hours' is chosen and 'Lock environment' pressed, then POST lock/acquire sends expectedDurationMinutes=120 and the reason.
- Given a lease ending at 20:00 and a 12 h extension over the maximum, when chosen, then the reason field appears and the button reads 'Ask <owner>'.
- Given a viewer, when the aside renders, then lock and lease facts show values but no actions.

**Tests**

- test/tests/environments/lock-banner.spec.ts (UPDATE)
- test/tests/environments/lease-banner.spec.ts (UPDATE)
- test/tests/access/my-account-extension.spec.ts (UPDATE if affected)

**Notes:** Someone else's lock also raises a red alert in the stack (U07-T07); the fact stays as the place for actions.

#### U07-T06 — Put running count, schedule, idle auto-stop and the 14-day cost with budget meter into the side column

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** EnvDetail.dc.html, EnvDetailViewer.dc.html · **Depends on:** U07-T05, U02-T03

EnvCost.buildCard (env-cost.js L146-185), Budgets.envPanelHtml (budgets.js L361-385) and IdleStop.buildPanel (idle-stop.js L54-108) render three separate cards under 'Cost & settings'. The aside shows '5 of 7 VMs running', a Schedule fact ('Mon-Fri 08:00-20:00', next stop/start), an 'Idle auto-stop' fact ('Dry run · after 60 min idle', 'Would have saved $46 this month', action 'Pause or review'), and 'Cost, last 14 days $186 / $400' with daily bars (weekends lighter, today gold), a budget meter and 'Saved $142 by the schedule this month'.

**Files**

- `src/main/resources/static/js/features/env-cost.js` (modify) — renderAside(env): from EnvironmentCostDTO draw the last 14 daily[] estimates as inline SVG bars (role=img, aria-label summarising), header '$MTD / $budget' when a budget exists, schedule fact from scheduleStatus ('None · Started and stopped by hand' when ruleCount 0, env admins get 'Set one up' -> #/automation-rules), 'Saved $X by the schedule this month' from savingsMtd; keep ids #env-cost-mtd and #env-cost-schedule; ECharts trend dropped.
- `src/main/resources/static/js/features/budgets.js` (modify) — renderEnvPanel draws only the meter (role=img aria-label 'N% of the monthly budget used') and owner Edit/Set budget into #env-budget-panel inside the cost block; the freeze banner moves to U07-T07.
- `src/main/resources/static/js/features/idle-stop.js` (modify) — buildFact(env, status): 'Idle auto-stop' value 'Off' / 'On · after N min idle' / 'Dry run · after N min idle' / 'Production — excluded', note from wouldHaveSaved/savedEstimate/snoozedUntil, action 'Pause or review' (canOperate) opening IdleStop.openDialog (U08-T09; until then openRuleModal).
- `src/main/resources/static/js/features/environments.js` (modify) — Aside order: running count, Lock, Lease, Schedule, Idle auto-stop, Cost block; remove the 'Cost & settings' section markup.
- `test/tests/environments/environment-cost-card.spec.ts` (modify) — #env-cost-trend svg -> the 14-day bars svg; MTD and schedule ids kept; error retry text kept.
- `test/tests/environments/idle-stop-panel.spec.ts` (modify) — #idle-stop-card -> #env-fact-idle; 'Dry run' text; viewer has no action button; off feature -> no fact.
- `test/tests/cost/budgets.spec.ts` (modify) — #env-budget-summary assertion becomes the meter label / cost header '$X / $1,000'.

**Steps**

1. Add renderAside to EnvCost, keeping render(env) until no caller remains, then remove the card version.
2. Budget meter inside the cost block; Budgets.renderEnvPanel no longer prints the freeze alert.
3. IdleStop.buildFact replaces the card; the 60 s refresh poll stays page-scoped and only runs when the feature is on.
4. Make sure each block renders nothing (and requests nothing) when its feature is off (U02-T03).
5. Update the specs; bump the stamp.

**Acceptance criteria**

- Given 14 days of cost and a $400 budget, when the aside renders, then the header reads '$186 / $400' and the meter says '47% of the monthly budget used'.
- Given no schedule rule, when an operator views the page, then the Schedule fact reads 'None' with 'Started and stopped by hand'.
- Given idle auto-stop in dry run, when the aside renders, then the fact reads 'Dry run · after 60 min idle' and shows the would-have-saved amount.
- Given the whole aside at 1440x900, when rendered with every fact, then it fits without a page scrollbar.

**Tests**

- test/tests/environments/environment-cost-card.spec.ts (UPDATE)
- test/tests/environments/idle-stop-panel.spec.ts (UPDATE)
- test/tests/cost/budgets.spec.ts (UPDATE)

**Notes:** Carbon (EnvCost.addCarbon) and EKS workloads move to Insights/EKS views; the carbon line stays only when carbon.enabled, as a one-line note under cost.

#### U07-T07 — Show every environment alert in one stack, most urgent first: budget freeze, lock, approval, lease, cost spike, drift, stale sync

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** EnvAlerts.dc.html · **Depends on:** U07-T06

Alerts are scattered: Budgets prints .budget-freeze-banner in its card, Approvals.renderEnvBanner fills #env-approval-banner, Leases marks lease-ending-soon, CostAnomalies.renderEnvPanel lists 90 days of anomalies in #env-anomaly-panel, drift (VmDTO.stateDriftDetected) and stale lastStateSyncAt are not surfaced on the page at all. EnvAlerts defines one stack under the header, ordered red (budget freeze, someone else's lock) > navy (my request waiting) > gold (lease ends within 60 min, yesterday's spend spike, VMs changed outside the platform) > grey (states may be out of date), each with one action and a kind label; a frozen budget also disables Start with a reason.

**Files**

- `src/main/resources/static/js/features/env-alerts.js` (new) — EnvAlerts: rank(alerts) pure ordering by tone then kind; render(env) collects providers (Budgets.alertFor, Locks.alertFor, Approvals.alertFor, Leases.alertFor, CostAnomalies.alertFor, drift and sync from env VMs) as {tone, title, detail, action, href|dataAction, kind}; red ones role=alert, others role=status; #env-alerts container under the header; stale = newest lastStateSyncAt older than 3x vm.state.sync interval (15 min default).
- `src/main/resources/static/js/features/budgets.js` (modify) — alertFor(env): when startFrozenUntil -> red 'Starts are blocked.' + detail with budget and month + 'Ask to lift' (non-owner, #/budgets) or 'Lift freeze' (owner, data-action=budget-lift-freeze).
- `src/main/resources/static/js/features/approvals.js` (modify) — renderEnvBanner -> alertFor(env) returning the navy item ('Your start for 24 hours is waiting for <names>. Asked 10 min ago.' + 'Cancel request'); keep the 15 s follow-up poll and the decided toast; keep id approval-waiting on the item.
- `src/main/resources/static/js/features/cost-anomalies.js` (modify) — alertFor(env): the newest anomaly of the last 2 days -> gold 'Spend was 3x usual yesterday:' + amounts + top cause + 'Review' (#/cost-anomalies); the 90-day table moves to Insights (U08-T08) and #env-anomaly-panel is removed.
- `src/main/resources/static/js/features/locks.js` (modify) — alertFor(lockStatus): someone else's lock -> red 'Locked by <name>' + 'since 09:10, "reason", until 14:30. You can see it but not start or stop VMs.' + 'Lock history'.
- `src/main/resources/static/js/features/leases.js` (modify) — alertFor(current): lease ends within 60 min -> gold 'Stops in 12 minutes.' + 'Extend' (data-action=lease-extend).
- `src/main/resources/static/js/features/environments.js` (modify) — Header Start buttons disabled with title 'Starts are blocked by the budget' while frozen (non-admins); call EnvAlerts.render after the providers resolve; drift item links to the first drifted VM's details (History tab).
- `src/main/resources/static/index.html` (modify) — Add env-alerts.js; bump stamp.
- `src/test/js/env-alerts.test.mjs` (new) — rank order and stale-sync detection.
- `test/tests/operations/approvals.spec.ts` (modify) — #approval-waiting now inside #env-alerts; Cancel request text.
- `test/tests/cost/budgets.spec.ts` (modify) — .budget-freeze-banner -> the red alert in #env-alerts; Start button disabled with the reason.
- `test/tests/cost/cost-anomalies.spec.ts` (modify) — #env-anomaly-panel toBeEmpty -> no Cost alert in #env-alerts.
- `test/tests/environments/env-alerts.spec.ts` (new) — Faked data for all seven kinds: order and roles as in EnvAlerts.

**Steps**

1. Write EnvAlerts.rank and the stale/drift helpers with a node test.
2. Turn each module's banner into an alertFor that returns data (or null) and keep its actions registered.
3. Render the stack once all providers settle (Promise.allSettled); a failing provider just contributes nothing.
4. Disable Start while frozen for non-admins, with the reason as title and visible text in the alert.
5. Update and add the specs; bump the stamp.

**Acceptance criteria**

- Given a frozen budget, someone else's lock and a lease ending in 12 min, when the page opens, then the stack shows the budget alert first, the lock second, the lease third, and the first two have role=alert.
- Given VM states last read 2 h ago, when the page opens, then a grey alert says 'VM states may be out of date.' with a System health link for admins and no link for others.
- Given nothing to report, when the page opens, then #env-alerts is empty and takes no space.

**Tests**

- src/test/js/env-alerts.test.mjs (NEW)
- test/tests/environments/env-alerts.spec.ts (NEW)
- test/tests/operations/approvals.spec.ts (UPDATE)
- test/tests/cost/budgets.spec.ts (UPDATE)
- test/tests/cost/cost-anomalies.spec.ts (UPDATE)

**Notes:** Copy follows the brief: says what happened and what to do, no apologies. The System health link shows only for admins (U02-T01 route guard).

#### U07-T08 — Render EKS environments as node groups with node strips, size, desired count, capacity type and namespace cost

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** EksEnv.dc.html, EnvDetailViewer.dc.html · **Depends on:** U07-T06, U07-T03, U01-T01

EKS node groups are Vm rows with provider AWS_EKS; the page shows them like VMs (isEksGroup only changes the confirm wording) and VmDTO has no node data, although EksSyncService stores minSize/desiredSize/instanceType in Vm.metadata and EstimatedCostProvider.resolveEksNodeInfo parses it. EksEnv shows a node-group table (Node group, Nodes strip, Size, Running / when started, Capacity, Scale to 0 / Start), 'Scale all to 0', a namespace cost split, and facts for the cluster fee, lease, idle capacity and Spot.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/VmDTO.java` (modify) — Add eksNodeGroup {instanceType, desiredWhenRunning, currentNodes, capacityType} set only for AWS_EKS.
- `src/main/java/com/tcgdigital/vmcontrol/service/EstimatedCostProvider.java` (modify) — Make the metadata parser (parseEksNodeInfo) a public static helper (or move it to a small EksNodeGroupInfo class) so VmDTO mapping can reuse it.
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — listVms/getVm fill eksNodeGroup (currentNodes = desiredWhenRunning when RUNNING, else 0; capacityType from purchaseOption).
- `src/main/resources/static/js/features/env-vm-table.js` (modify) — renderEks(env): section h2 'Node groups · start top to bottom', rows with PowerStrip of nodes, size, 'N / M' running/when started, 'On-demand' or 'Spot, falls back to on-demand', action 'Scale to 0' (stop group) / 'Start' (start group).
- `src/main/resources/static/js/features/environments.js` (modify) — serviceType EKS: meta line 'AWS EKS · cluster <eksClusterName> · region', header 'Scale all to 0' instead of 'Stop all'; namespace cost section via EksWorkloads.mount into #env-eks-namespaces (moved from EnvCost.toggleWorkloads) shown on page load only for EKS; aside facts from EnvironmentCostDTO (fee note 'The fee runs even when nodes are at 0'), lease ('Node groups scale to 0 in reverse order'), Spot summary line when Spot is on.
- `src/main/resources/static/js/features/env-cost.js` (modify) — Remove the workloads toggle (moved); keep EksWorkloads usage via the page.
- `src/test/java/com/tcgdigital/vmcontrol/controller/VmMgmtEksNodeGroupTest.java` (new) — eksNodeGroup for running and scaled-to-0 groups; absent for EC2.
- `test/tests/cost/eks-workloads.spec.ts` (modify) — Namespace split on the EKS environment page instead of the cost card toggle.
- `test/tests/environments/eks-environment.spec.ts` (new) — Faked EKS env: node group rows, 'Scale to 0' / 'Start', viewer sees 'Scaled to 0' without actions.

**Steps**

1. Extract the metadata parser and expose eksNodeGroup on VmDTO; test it.
2. Branch the page body on serviceType === 'EKS' to renderEks; reuse group-action handlers (each node group is its own group).
3. Move the namespace cost view onto the page and the cluster-fee note into the cost fact.
4. Viewer variant: states and counts only.
5. Update and add specs; bump the stamp.

**Acceptance criteria**

- Given an EKS env with system-nodes (3 running of 3) and batch-nodes (0 of 2), when the page opens, then rows read '3 / 3' with 'Scale to 0' and '0 / 2' with 'Start'.
- Given an app-nodes group on Spot, when rendered, then Capacity reads 'Spot, falls back to on-demand'.
- Given a viewer on an EKS env scaled to 0, when the page opens, then each row says 'Scaled to 0' and no action buttons show.

**Tests**

- VmMgmtEksNodeGroupTest (NEW)
- test/tests/environments/eks-environment.spec.ts (NEW)
- test/tests/cost/eks-workloads.spec.ts (UPDATE)
- test/tests/cost/eks-control-plane.spec.ts (UPDATE if it reads the cost card)

**Notes:** 'Idle capacity' (node time not requested by pods) is shown only when the workload cost payload has an idle row; no new metric collection.

#### U07-T09 — Check the environment page per role and size at 1440x900

- [ ] Done · **Type:** test · **Estimate:** 1 d · **Risk:** low · **Sources:** EnvDetail.dc.html, EnvDetailViewer.dc.html, EnvDetailLarge.dc.html, EnvAlerts.dc.html, EksEnv.dc.html · **Depends on:** U07-T04, U07-T07, U07-T08

The page now has four shapes (small, large, viewer, EKS) and an alert stack; the no-scrollbar rule and role differences need one spec that guards them together.

**Files**

- `test/tests/environments/environment-page-fit.spec.ts` (new) — For 7 VMs, 54 VMs, EKS and a viewer: no page scrollbars at 1440x900 with all alerts and facts present; operator vs viewer controls; env admin sees Settings and Access.
- `test/tests/navigation/background-polls.spec.ts` (modify) — The env page makes no request for switched-off features and no failing background request (audit finding).

**Steps**

1. Build fakes with test/fixtures/mock-api.ts for each shape.
2. Assert both scroll axes and the controls per dev user (admin-001, dev-user-001, user-001, user-002).
3. Count network requests on load and assert none fail.

**Acceptance criteria**

- Given each shape, when opened at 1440x900, then the document has no horizontal or vertical scrollbar.
- Given a fresh env page load with features off, when the page settles, then no request returns 4xx/5xx.

**Tests**

- test/tests/environments/environment-page-fit.spec.ts (NEW)
- test/tests/navigation/background-polls.spec.ts (UPDATE)

## U08

### VM details & operations — Everyday

Starting, stopping and following operations reads as plain steps by group, history explains each run and offers a retry, and VM details, environment insights and idle auto-stop open as tabbed side panels that answer what is running, what it costs and what to do.

Sources: StartConfirm.dc.html, OperationProgress.dc.html, OperationHistory.dc.html, VmDetails.dc.html, EnvInsights.dc.html, IdleStop.dc.html · 9 tasks · 14 days

#### U08-T01 — Add group names to operation steps, store what triggered a run, page the history, and name approvers in the approval dry run

- [ ] Done · **Type:** backend · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** OperationProgress.dc.html, OperationHistory.dc.html, StartConfirm.dc.html · **Depends on:** U01-T03

OperationDetailDTO has targetName and sequencePosition but no group, so progress cannot be shown per group; OperationExecution keeps triggerSource but not the schedule name (AutomationRuleService L551 builds 'Automation rule: <name>' into StartOperationDTO.reason, which is never stored); VmOperationsController.listOperations returns getRecentExecutions top 20 as a List although the client sends page/size; ApprovalPolicyService.ApprovalDecision.PolicyMatch has policy name and approverMode but no approver names, so the start dialog cannot say 'Needs Sanjay Rao'.

**Files**

- `src/main/resources/db/migration/V91__add_operation_trigger_name.sql` (new) — ALTER TABLE operation_executions ADD COLUMN trigger_name VARCHAR(200) NULL (use the next free V number at implementation time).
- `src/main/java/com/tcgdigital/vmcontrol/model/OperationExecution.java` (modify) — triggerName field + getters/setters.
- `src/main/java/com/tcgdigital/vmcontrol/service/VmOperationsService.java` (modify) — startOperation overloads accept a triggerName (rule name for SCHEDULE/ACCESS_AUTOMATION, null otherwise) and store it; new getExecutionsPage(environmentId, Pageable).
- `src/main/java/com/tcgdigital/vmcontrol/service/AutomationRuleService.java` (modify) — Pass rule.getName() as triggerName where it calls startOperation (around L551-568).
- `src/main/java/com/tcgdigital/vmcontrol/repository/OperationExecutionRepository.java` (modify) — findByEnvironmentEnvironmentIdOrderByStartedAtDesc(String, Pageable).
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmOperationsController.java` (modify) — New GET /operations/page?page&size (Paging.of, max 50) returning Page<OperationExecutionDTO> without details; existing list endpoint unchanged.
- `src/main/java/com/tcgdigital/vmcontrol/dto/OperationExecutionDTO.java` (modify) — triggerName; scopeLabel ('whole environment', '<group> group', '<vm>') computed from details target types/count.
- `src/main/java/com/tcgdigital/vmcontrol/dto/OperationDetailDTO.java` (modify) — groupId, groupName from detail.getVm().getGroup() (null-safe).
- `src/main/java/com/tcgdigital/vmcontrol/service/ApprovalPolicyService.java` (modify) — PolicyMatch gains approverNames (display names resolved like ApprovalRequestService does for execution.approvers).
- `src/test/java/com/tcgdigital/vmcontrol/controller/VmOperationsControllerIntegrationTest.java` (modify) — page endpoint, groupName on details, triggerName for a rule-started run.
- `src/test/java/com/tcgdigital/vmcontrol/controller/ApprovalPolicyControllerIntegrationTest.java` (modify) — evaluate returns approverNames.

**Steps**

1. Add the migration (next free version, never edit V1-V89) and the entity field; run with ddl-auto=validate.
2. Thread triggerName from AutomationRuleService into the execution; manual runs leave it null.
3. Add the paged repository method and the /operations/page endpoint with assertCanView.
4. Map groupId/groupName and scopeLabel in the DTOs; error messages stay as U01-T03 sanitises them.
5. Resolve approver display names in evaluate().
6. Tests as listed.

**Acceptance criteria**

- Given a schedule rule 'Nightly batch' starts an environment, when its execution is read, then triggerSource is SCHEDULE and triggerName is 'Nightly batch'.
- Given 23 runs, when GET /operations/page?page=0&size=7 is called, then 7 rows return with totalElements 23.
- Given a step for a VM in group Application, when the execution is read with details, then the step has groupName 'Application'.
- Given a 24 h start that needs owner approval, when evaluate is called, then the match lists the owner's display name.

**Tests**

- VmOperationsControllerIntegrationTest (UPDATE)
- ApprovalPolicyControllerIntegrationTest (UPDATE)
- ApprovalPolicyServiceTest (UPDATE)

**Notes:** MCP/assistant callers of the list endpoint are untouched because the page endpoint is new. Migration numbers V90-V95 follow task order (U01-T06 V90, U08-T01 V91, U09-T09 V92, U13-T01 V93, U13-T06 V94, U15-T08 V95); take the next free number if another migration lands first.

#### U08-T02 — Rebuild the start and stop confirmations: what starts now, for how long, what it costs and who must approve

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** StartConfirm.dc.html · **Depends on:** U08-T01, U07-T03, U07-T05

showOperationConfirmDialog (environments.js L1313-1377) shows 'Start All' with a scope label, a 'Run for' select only for whole-environment starts (Leases.runForOptions), an avg/fast/slow estimate table (buildEstimateHtml) and a cost line; it never says which groups start now or whether approval is needed until after submit. StartConfirm asks 'Start 2 VMs in Payments Dev?', says 'The Web group starts now. Database and Application are already running.', shows a mini strip and the VM names with sizes, offers 'Keep them running until' chips (Schedule stop 20:00, 2 hours, 8 hours, 24 hours) each with cost and 'No approval needed' / 'Needs <name>', and the button becomes 'Send for approval' when needed.

**Files**

- `src/main/resources/static/js/features/operation-confirm.js` (new) — OperationConfirm.open({ env, opType, scope }): dialog (role=dialog, aria-labelledby) built from currentEnvironment: target VMs (stopped ones for START, running ones for STOP), sentence about groups starting now vs already running, PowerStrip with targets marked busy, names + sizes (first 6, then '+N more'), 'usually about X min' from operations.estimates; START with leases on: chips from Leases policy (schedule stop when scheduleStatus.nextStop is before the lease default, then policy values), per chip CostPreview.load(envId, scope, hours) and POST approvalPolicies.evaluate (cached per hours) -> hint line; CTA 'Start N VMs' / 'Send for approval' / 'Stop N VMs'; quota line via CostPreview.renderQuota kept; leaseMinutes sent for env, group and VM starts.
- `src/main/resources/static/js/features/environments.js` (modify) — startEnvironment/stopEnvironment/startGroup/stopGroup/startVm/stopVm call OperationConfirm.open; delete showOperationConfirm, showOperationConfirmDialog, buildEstimateHtml and the env-toggle-estimates action; the lock pre-check moves into OperationConfirm (blocked -> the lock alert text, no dialog).
- `src/main/resources/static/js/features/vm-operations.js` (modify) — startGroup/startVm accept leaseMinutes like startEnvironment.
- `src/main/resources/static/index.html` (modify) — Add operation-confirm.js; bump stamp.
- `src/test/js/operation-confirm.test.mjs` (new) — Pure helpers: targetsFor(env, opType, scope), groupSentence(env, targets), durationChips(policy, schedule, now).
- `test/tests/environments/cost-preview.spec.ts` (modify) — #confirmModal #cost-preview-line -> per-chip cost in the new dialog; no estimate alert assertion; stop dialog has no cost.
- `test/tests/environments/quotas.spec.ts` (modify) — Quota line and disabled confirm in the new dialog; close via its Cancel.
- `test/tests/operations/approvals.spec.ts` (modify) — Confirm text 'It runs as Ravi' -> chip hint 'Needs <name>' and 'Send for approval' CTA.
- `test/tests/operations/approval-held.spec.ts` (modify) — Submit through 'Send for approval'; progress dialog still not shown.
- `test/tests/navigation/page-takeover.spec.ts` (modify) — #confirmModal checks -> the operation confirm dialog id.

**Steps**

1. Write the pure helpers and their node test.
2. Build the dialog with the U03-T02 dialog component; focus the CTA, Escape cancels.
3. Load estimate, cost and approval per chip lazily (first chip immediately, others on selection) with ContentRouter.token guards.
4. Submit through VmOperations with leaseMinutes; a held approval keeps today's toast and Approvals refresh.
5. Replace the old confirm code and update the specs; bump the stamp.

**Acceptance criteria**

- Given Payments Dev with Web stopped, when 'Start the 2 stopped' is pressed, then the dialog title is 'Start 2 VMs in Payments Dev?' and lists web-01, web-02 with t3.medium.
- Given the 24 hours chip needs the owner's approval, when it is chosen, then the hint says 'Needs Sanjay Rao' and the CTA reads 'Send for approval'.
- Given someone else holds the lock, when Start is pressed, then no dialog opens and the user is told who holds it.
- Given a stop of a group, when the dialog opens, then it says 'Stop 3 VMs in <env>?' and shows no cost or duration chips.

**Tests**

- src/test/js/operation-confirm.test.mjs (NEW)
- test/tests/environments/cost-preview.spec.ts (UPDATE)
- test/tests/environments/quotas.spec.ts (UPDATE)
- test/tests/operations/approvals.spec.ts (UPDATE)
- test/tests/operations/approval-held.spec.ts (UPDATE)
- test/tests/navigation/page-takeover.spec.ts (UPDATE)

**Notes:** Without leases the duration fieldset is hidden and the dialog shows the next scheduled stop as a sentence when there is one. Approval is re-checked by the server on submit.

#### U08-T03 — Show operation progress by group: stages in start order, per-VM state and time, 'Cancel the rest' and 'Hide · keeps running'

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** OperationProgress.dc.html · **Depends on:** U08-T01, U03-T03

VmOperations.showProgressModal/updateProgressModal (vm-operations.js L157-438) show a striped bar with a percent, four counters (Completed/Pending/Skipped/Failed) and a flat step list with upper-case badges; OperationProgress shows 'Starting CRM UAT', '1:42 so far · usually about 4 min', a progressbar 'N of M VMs running', a live line ('3 of 6 running · Application is starting; Web waits for it') and one block per group (1st · Database 'Done in 0:48', 2nd · Application 'Starting', 3rd · Web 'Waits for Application') with per-VM pips and 'Running · 0:41'.

**Files**

- `src/main/resources/static/js/features/operation-stages.js` (new) — OperationStages (pure): stages(execution) groups details by groupName in sequencePosition order with tag Done in m:ss / Starting / Stopping / Waits for <previous> / N failed; vmLine(step, now); liveLine(execution); doneCount(execution).
- `src/main/resources/static/js/features/vm-operations.js` (modify) — showProgressModal: new body (elapsed + usual time, role=progressbar with aria-valuenow/max/label, aria-live line, stages container); buttons 'Cancel the rest' (#btn-cancel-operation) and 'Hide · keeps running' (#btn-close-progress); updateProgressModal renders OperationStages; terminal texts in #progress-status-text: 'Done', 'Done with problems: N failed, N skipped', 'Failed: <reason>', 'Cancelled', 'Took too long to report back'; keep summarizeExecution for the banner.
- `src/main/resources/static/css/components/operations.css` (modify) — Stage blocks, pips (running green, starting gold pulse that respects prefers-reduced-motion, waiting hollow, failed red).
- `src/main/resources/static/index.html` (modify) — Add operation-stages.js before vm-operations.js; bump stamp.
- `src/test/js/operation-stages.test.mjs` (new) — Grouping, tags and live line from fixture executions (in progress, partial, cancelled).
- `test/tests/operations/operations-ui.spec.ts` (modify) — Expected texts: 'Cancelled' (was 'Operation cancelled'), 'Done' (was 'Completed successfully'), 'Failed: All 3 steps failed' (was 'Failed — ...'); server reason texts for 403/409 unchanged; button names 'Cancel the rest' / 'Hide · keeps running'.
- `test/tests/navigation/status-widgets.spec.ts` (modify) — Re-open from the banner still shows #operationProgressModal; new button names.

**Steps**

1. Write OperationStages with a node test using steps that carry groupName (U08-T01).
2. Replace the modal body and the update function; keep modalExecutionId/modalToken/activeOperations logic and polling unchanged.
3. Fetch the usual duration once from operations.estimates when the modal opens (fails quietly).
4. Keep the operation banner, now reading 'N of M done'.
5. Update the specs; bump the stamp.

**Acceptance criteria**

- Given a start where Database is done and Application is starting, when the dialog updates, then Database shows 'Done in 0:48', Application 'Starting', Web 'Waits for Application' and the live line says '3 of 6 running'.
- Given 'Hide · keeps running' is pressed, when the run continues, then the banner shows and re-opens the same dialog.
- Given a step failed with a capacity error, when the run ends, then the VM line shows the server's plain reason and the status reads 'Done with problems: 1 failed'.

**Tests**

- src/test/js/operation-stages.test.mjs (NEW)
- test/tests/operations/operations-ui.spec.ts (UPDATE)
- test/tests/navigation/status-widgets.spec.ts (UPDATE)

#### U08-T04 — Turn operation history into a side panel: paged runs, who or which schedule, expandable steps and 'Retry <vm>'

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** OperationHistory.dc.html · **Depends on:** U08-T01, U08-T02, U04-T02

VmOperations.showHistoryModal/loadHistoryContent (vm-operations.js L745-838) open a modal table of the last 20 runs (Time, Operation badge, Status, Duration, Initiated By) without steps or reasons. OperationHistory is a side panel with 7 runs per page (when, what, who incl. 'Schedule "Nightly batch"', took, result chip), one expanded run showing steps by group with the failure reason, and 'Retry report-worker' for failed VMs; skipped runs explain 'Locked by Priya Nair; the schedule retried for 30 min, then skipped'.

**Files**

- `src/main/resources/static/js/features/operation-history.js` (new) — OperationHistory.open(env): U04-T02 side panel titled '<env> history'; rows from operations.page(envId, page, 7) as buttons with aria-expanded; what = '<Start|Stop>, <scopeLabel>'; who = initiatedByDisplayName or 'Schedule "<triggerName>"' / 'Lease' / 'Idle auto-stop' / 'Budget'; took m:ss; result chip Done / N failed / Cancelled / Skipped: <reason> / Running (with Cancel); expanding loads operations.get(envId, id) once and lists steps by groupName with errorMessage notes; failed VM steps get 'Retry <vm>' -> OperationConfirm.open({ scope: vm }); pager 'a-b of n'.
- `src/main/resources/static/js/features/vm-operations.js` (modify) — Remove showHistoryModal/loadHistoryContent/getHistory; keep confirmCancel exported for the panel.
- `src/main/resources/static/js/config.js` (modify) — operations.page(envId, page, size).
- `src/main/resources/static/js/features/environments.js` (modify) — History button opens OperationHistory.open(env).
- `src/main/resources/static/index.html` (modify) — Add operation-history.js; bump stamp.
- `test/tests/operations/operations-ui.spec.ts` (modify) — History test: .operation-history tr[data-execution-id] -> panel rows [data-execution-id]; 'Completed with failures' -> '1 failed'; 'skipped 1' shown in the expanded steps; Cancel on the running row still cancels.

**Steps**

1. Build the panel with the side panel primitive; trap focus, Close returns focus to the History button.
2. Page with the new endpoint; expanded state survives paging back.
3. Map triggerSource/triggerName to the who column.
4. Retry opens the start confirm for that one VM.
5. Update the spec; bump the stamp.

**Acceptance criteria**

- Given 23 runs, when the panel opens, then rows 1-7 show with 'Previous' disabled and '1-7 of 23'.
- Given a run started by the 'Nightly batch' schedule, when listed, then who reads 'Schedule "Nightly batch"'.
- Given a run where report-worker failed, when it is expanded, then the step shows the reason and a 'Retry report-worker' button that opens the start dialog for that VM.

**Tests**

- test/tests/operations/operations-ui.spec.ts (UPDATE)
- test/tests/operations/operation-history.spec.ts (NEW): paging, who labels, expand and retry

#### U08-T05 — Rebuild VM details as a side panel with a status header, quick stats and Overview / Use / Storage tabs

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** VmDetails.dc.html · **Depends on:** U07-T03, U01-T05, U05-T01, U05-T02, U04-T02

showVmDetails and buildVmInsightsPanel (environments.js L1700-2201) open a Slideout titled 'VM: <name>' with Instance/CPU/Storage/Last metric stats, Bootstrap tabs Overview/Utilization/Storage/History and a provider line that prints providerVmId (or 'pending-provider-id') to everyone. VmDetails shows '<name> Running since 08:05' with a Stop/Start button, stats Size / CPU now / IP address / Disk used, and tabs Overview (Size, vCPU / memory, Region / zone, Private IP, Public IP, Purchase, Purpose, Launched, State read from AWS, Cost this month; instance ID block for administrators only), Use (1 h, 6 h, 24 h, 7 days; CPU, Memory, Network in/out, Disk read/write) and Storage (volumes with size, type, used and cost).

**Files**

- `src/main/resources/static/js/features/vm-details.js` (new) — VmDetails.open(envId, vmId, { tab, window }): moves showVmDetails, buildLiveVmInsightsData, metric tiles and formatters out of environments.js; header with name, state text (U05-T01 runningSince), Start/Stop button when viewerCanOperate (goes through OperationConfirm when U08-T02 is merged, else the existing start/stop helpers); stats row; role=tablist tabs from U04-T02 (Overview, Use, Storage, Idle, History; Idle/History filled by U08-T07); instance ID only when the payload has it (U01-T05); volume IDs only for admins; 'Not collected' for disk used and memory without the CloudWatch agent.
- `src/main/resources/static/js/features/environments.js` (modify) — Remove showVmDetails and the buildVmInsights*/buildOverviewSection/buildUtilizationSection/buildStorageSection/buildHistorySection/buildMetricTile family; vm-details action and ?vm= deep link call VmDetails.open.
- `src/main/resources/static/css/features/environments.css` (modify) — Move VM panel styles to the new panel classes; drop vm-insights-* rules no longer used.
- `src/main/resources/static/index.html` (modify) — Add vm-details.js after environments.js; bump stamp.
- `test/tests/a11y/slideout-dialog.spec.ts` (modify) — VM panel: dialog name '<vm>', tabs are a tablist with arrow-key movement, Close returns focus to the Details button.
- `test/tests/environments/vm-details.spec.ts` (new) — Overview fields, Use window switch keeps the panel open on the Use tab, user does not see an instance ID, admin does.

**Steps**

1. Move the code into vm-details.js unchanged first (pure move, tests green), then restyle.
2. Build header, stats and the five tabs; windows ['1h','6h','24h','7d'] labelled '1 h', '6 h', '24 h', '7 days'; switching window re-fetches metrics only, not the VM.
3. Overview field list as in the mockup; 'State read from AWS' = relative lastStateSyncAt; Cost this month left as '—' until U08-T06.
4. Respect U01-T05: no instance ID text for non-admins, nothing like 'pending-provider-id'.
5. Update and add specs; bump the stamp.

**Acceptance criteria**

- Given a running VM, when Details is pressed, then the panel title reads '<name>' with 'Running since 08:05' and a Stop button for an operator.
- Given a user (not admin), when Overview shows, then no instance ID appears anywhere in the panel; given an admin, then the instance ID block shows.
- Given the Use tab, when '7 days' is pressed, then the metrics reload for 7 days and the Use tab stays selected.
- Given a VM without the CloudWatch agent, when Use shows, then Memory says it needs the CloudWatch agent.

**Tests**

- test/tests/environments/vm-details.spec.ts (NEW)
- test/tests/a11y/slideout-dialog.spec.ts (UPDATE)

**Notes:** environments.js shrinks by ~500 lines in this task.

#### U08-T06 — Add a VM advice endpoint and show cost this month, purchase type and the rightsizing note in VM details

- [ ] Done · **Type:** backend · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** VmDetails.dc.html · **Depends on:** U08-T05

VM details needs 'Cost this month $38.20' and 'Could be one size smaller: m6i.medium ... Saves about $31 a month. AWS Compute Optimizer agrees.' for every viewer, but rightsizing lives behind CostManagementController /rightsizing with hasRole('ADMIN') and there is no per-VM cost endpoint; CostEstimationService.findCandidate(vmId) already finds a VM's candidate.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/VmAdviceDTO.java` (new) — record VmAdviceDTO(BigDecimal monthToDateCost, BigDecimal hourlyRate, String purchaseOption, Rightsizing rightsizing) with Rightsizing(currentType, recommendedType, direction, monthlySavings, cpuPeak, memoryPeak, source, optimizerAgrees).
- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — GET /environments/{environmentId}/vms/{vmId}/advice with securityService.assertCanView and group visibility like getVm.
- `src/main/java/com/tcgdigital/vmcontrol/service/VmAdviceService.java` (new) — Combines CostEstimationService.findCandidate, EnvironmentCostService per-VM MTD (topVms data or the snapshot query by vmId) and PricingReferenceService.lookupHourlyRate.
- `src/main/resources/static/js/features/vm-details.js` (modify) — Overview: Cost this month and Purchase from advice; rightsizing note with 'Resize safely…' for admins (#/rightsizing with the VM preselected) and 'Ask an administrator to resize' for others (mailto-free: opens Ask/Help per U09 later, now a plain text hint).
- `src/main/resources/static/js/config.js` (modify) — vms.advice(envId, vmId).
- `src/test/java/com/tcgdigital/vmcontrol/controller/VmAdviceControllerTest.java` (new) — Viewer gets advice; user without access gets 403; no rightsizing -> null; no instance IDs in the payload.

**Steps**

1. Write VmAdviceService and DTO; reuse existing services, no new AWS calls.
2. Add the endpoint with the same access checks as getVm.
3. Show the values in the Overview tab; fail quietly (fields show '—').
4. Tests as listed.

**Acceptance criteria**

- Given a VM with a scale-down candidate saving $31 a month, when a viewer opens VM details, then Overview shows 'Could be one size smaller: m6i.medium' with the savings and 'Ask an administrator to resize'.
- Given an admin, when the same VM is opened, then the action reads 'Resize safely…' and links to rightsizing.
- Given a user without access to the environment, when the advice endpoint is called, then it returns 403.

**Tests**

- VmAdviceControllerTest (NEW)
- test/tests/environments/vm-details.spec.ts (UPDATE): cost and rightsizing note per role

**Notes:** The admin-only rightsizing list stays admin-only; the advice payload exposes only this VM's recommendation to people who can view it.

#### U08-T07 — Fill the VM Idle and History tabs: idle hours per day and a plain-language state history

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** VmDetails.dc.html · **Depends on:** U08-T05

The current History tab (buildHistorySection) shows at most 'Inventory refreshed' and 'Metric sample collected'; state changes already exist at GET /monitoring/vms/{vmId}/history (VmStateHistoryDTO: previous/new status, changeSource, changedByUsername, operationId, isDrift) but the page never calls it. VmDetails shows an Idle tab with idle hours per day for 7 days and the environment's idle auto-stop setting, and a History list like 'Started · Priya Nair, as part of "Start 5 VMs"', 'Stopped · Schedule "Office hours"', 'State changed outside the platform (stopped in the AWS console) · Found by the 11:00 sync'.

**Files**

- `src/main/resources/static/js/features/vm-details.js` (modify) — Idle tab: fetch metrics(envId, vmId, '7d', 3600) and compute idleHoursPerDay (CPU under 5% per hour bucket, today highlighted gold) as an SVG bar chart with a text aria-label; line 'Idle auto-stop for this environment: <mode>, after N min idle' from IdleStop status + link opening the idle dialog. History tab: fetch Config.API.monitoring.vmHistory(vmId) page 0 size 6, map to text/by/dot tone via historyText(item).
- `src/main/resources/static/js/features/vm-details-model.js` (new) — Pure helpers idleHoursPerDay(samples, threshold) and historyText(item) for node tests.
- `src/main/resources/static/index.html` (modify) — Add vm-details-model.js before vm-details.js; bump stamp.
- `src/test/js/vm-details-model.test.mjs` (new) — Idle hour buckets and history sentences for MANUAL/SCHEDULE/drift entries.
- `test/tests/environments/vm-details.spec.ts` (modify) — Idle chart label and History rows from faked endpoints; drift row text.

**Steps**

1. Write the pure helpers and their test.
2. Load each tab's data only when the tab is first shown.
3. History sentences: STARTED/STOPPED by user name or source; drift -> 'State changed outside the platform (<new> in the AWS console)' with 'Found by the HH:MM sync'.
4. The drift alert from U07-T07 opens the panel on this tab.

**Acceptance criteria**

- Given 7 days of hourly samples with weekends idle, when the Idle tab opens, then the chart's label lists 7 values and the weekend bars are full height.
- Given a drift entry, when History opens, then it reads 'State changed outside the platform' with the sync time.
- Given the history endpoint fails, when History opens, then the tab says history is not available and the rest of the panel works.

**Tests**

- src/test/js/vm-details-model.test.mjs (NEW)
- test/tests/environments/vm-details.spec.ts (UPDATE)

**Notes:** History text must not contain instance IDs (U01-T03 covers the server text).

#### U08-T08 — Rebuild environment insights as a side panel: what to do, idle now, busiest, by group and storage, for 24 h or 7 days

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** EnvInsights.dc.html · **Depends on:** U08-T05, U08-T02, U07-T07

showEnvironmentInsights (environments.js L1474-1698) opens a Slideout with stat tiles and five Bootstrap tabs (Overview, Utilization, Storage, Groups, Checks) whose 'Checks' are counts without actions; EnvironmentInsightsService.getInsights always uses the last hour (seriesStart = now - 1 h). EnvInsights is one panel with a 24 h / 7 days switch and sections 'What to do' (Stop <idle vm> saves $/h with Stop, Resize <vm> with Details, 'Stop on weekends too' with Edit schedule), 'Idle now', 'Busiest, average CPU' bars, 'By group' strips with CPU, and Storage; the 90-day anomaly table moves here from the page.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — getEnvironmentInsights: optional window=24h|7d (default 24h, 400 otherwise).
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentInsightsService.java` (modify) — Use the window for averages and busiest VMs; add actions list: STOP_IDLE (vmId, hourlyRate), RESIZE (vmId, recommendedType, monthlySavings via CostEstimationService.findCandidate), SCHEDULE_WEEKENDS when weekend idle hours exceed a threshold and the schedule covers weekdays only.
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentInsightsDTO.java` (modify) — Add window and actions (kind, vmId, vmName, detail, savingsText).
- `src/main/resources/static/js/features/env-insights.js` (new) — EnvInsights.open(env, window): side panel with the window switch, sections as in the mockup, actions wired to OperationConfirm (Stop), VmDetails.open (Details), #/automation-rules (Edit schedule, env admins only); CostAnomalies.tableHtml for the last 90 days at the bottom.
- `src/main/resources/static/js/features/environments.js` (modify) — Remove showEnvironmentInsights and the buildEnvironment*Section helpers; Insights button calls EnvInsights.open.
- `src/main/resources/static/js/config.js` (modify) — environments.insights(id, window).
- `src/main/resources/static/index.html` (modify) — Add env-insights.js; bump stamp.
- `src/test/java/com/tcgdigital/vmcontrol/service/EnvironmentInsightsServiceTest.java` (modify) — Window and actions.
- `test/tests/environments/env-insights.spec.ts` (new) — Sections render; Stop action opens the stop dialog for that VM; viewer sees no Stop/Edit schedule actions.

**Steps**

1. Add the window parameter and actions in the service with tests.
2. Build the panel; keep it within the side panel height using the sections' row limits (3 actions, 2 idle, 5 busiest, groups, storage summary).
3. Wire actions by role (viewerCanOperate / viewerCanAdminister).
4. Move the anomaly table here; remove the old insights code.
5. Specs as listed; bump the stamp.

**Acceptance criteria**

- Given an idle pay-cache VM, when Insights opens, then 'What to do' shows 'Stop pay-cache.' with '$0.07/h' and a Stop button for an operator.
- Given '7 days' is pressed, when the panel reloads, then busiest CPU values are 7-day averages.
- Given a viewer, when Insights opens, then recommendations show without Stop or Edit schedule buttons.

**Tests**

- EnvironmentInsightsServiceTest (UPDATE)
- test/tests/environments/env-insights.spec.ts (NEW)

**Notes:** Insights stays within the group visibility rules already applied by getInsights(environmentId, visibleGroupIds).

#### U08-T09 — Rebuild idle auto-stop as one dialog: recent decisions, pause for 2/4/8 hours and the rule form

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** IdleStop.dc.html, Audit: ~45 unnamed switches · **Depends on:** U07-T06, U08-T07

IdleStop.buildPanel shows a badge, one 'latest event' line and Snooze 1/4/8 h buttons, and the rule edits in a separate modal (openRuleModal); IdleStopStatusDTO carries only latestEvent although IdleStopEventRepository.findTop20ByEnvironmentIdOrderByEvaluatedAtDesc exists. IdleStop shows one dialog: 'How it is going' with the last decisions ('All 7 VMs idle for 60 min — Would stop', 'Locked by Priya Nair — Skipped: locked'), 'Working late? Pause it for 2 hours / 4 hours / 8 hours', and the rule form (named On switch, Applies to, Idle for, CPU below, Network, Mode with 'Stop idle VMs' available after 14 days of dry run on a date, Delete rule, Save rule).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/IdleStopStatusDTO.java` (modify) — Add recentEvents List<LatestEvent> (5 newest).
- `src/main/java/com/tcgdigital/vmcontrol/service/idle/IdleStopSummaryService.java` (modify) — Fill recentEvents from IdleStopEventRepository.findTop20ByEnvironmentIdOrderByEvaluatedAtDesc (limit 5).
- `src/main/java/com/tcgdigital/vmcontrol/controller/IdleStopController.java` (modify) — status() unchanged signature; also return enforceAvailableAt (dryRunStartedAt + 14 days) per rule if not derivable client-side.
- `src/main/resources/static/js/features/idle-stop.js` (modify) — openDialog(): one dialog with decisions list (tag Would stop / Stopped / Kept running / Skipped: locked / Skipped: paused), pause chips 2/4/8 h (snooze) or 'Paused until HH:MM by <name>' + 'Resume now' (endSnooze), and the rule form for canAdminister (switch labelled 'Idle auto-stop on', Applies to options from env groups, numbers with units in labels, Mode radios, enforce disabled with 'Available after 14 days of dry run, on <date>' unless allowed); operators without admin see decisions and pause only; replaces openRuleModal and the card buttons.
- `src/test/java/com/tcgdigital/vmcontrol/controller/IdleStopControllerTest.java` (modify) — recentEvents order and limit.
- `test/tests/environments/idle-stop-panel.spec.ts` (modify) — Drive the dialog from the aside fact: decisions, pause 2 hours sends hours=2, admin saves the rule, viewer cannot open the form.

**Steps**

1. Add recentEvents on the server with a test.
2. Build the dialog; snooze options change from 1/4/8 to 2/4/8 hours (server accepts any hours value).
3. Move the rule form into the dialog; name every switch and input (audit finding).
4. The aside fact (U07-T06) and the VM Idle tab (U08-T07) both open this dialog.
5. Update the spec; bump the stamp.

**Acceptance criteria**

- Given five recent evaluations, when the dialog opens, then they show newest first with their outcome tags.
- Given an operator, when 'Pause it for 2 hours' is pressed, then POST idle-stop/snooze sends hours=2 and the dialog shows 'Paused until HH:MM'.
- Given a rule in dry run for 5 days and a non-admin env admin, when the form shows, then 'Stop idle VMs' is disabled with the date it becomes available.
- Given a screen reader, when the form is reached, then the switch is announced as 'Idle auto-stop on'.

**Tests**

- IdleStopControllerTest (UPDATE)
- test/tests/environments/idle-stop-panel.spec.ts (UPDATE)

**Notes:** No change to idle evaluation or enforcement; automation.idle-stop.enabled stays off by default.

## U09

### Requests, approvals, activity, account — Everyday

Every everyday self-service screen (sign in, request access, approvals, my activity, my account, notifications, help, Ask, Teams confirm, new environment) matches the approved mockup and fits 1440x900 without page scrollbars.

Sources: Main.dc.html, RequestAccess.dc.html, Approvals.dc.html, MyActivity.dc.html, MyAccount.dc.html, Notifications.dc.html, Help.dc.html, Shortcuts.dc.html, AskPanel.dc.html, ChatAction.dc.html, NewEnvironment.dc.html, Crawl audit: wrong help link, unnamed switches · 9 tasks · 12 days

#### U09-T01 — Redesign the sign-in page: Microsoft first, username form second, platform story on the left

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** Main.dc.html · **Depends on:** U03-T01, U03-T02

login.html is a centred Bootstrap card with the username form first, an 'OR' divider and 'Sign in with Entra ID' last; js/login.js applySignInOptions() hides the form when /api/auth/options says passwordLoginEnabled=false. The mockup is a two-column page: a navy left half with the logo, a rack of power-strip lights and the one-line pitch, and a right half with 'Sign in with Microsoft' as the primary action, 'or with a username', the form, and a 'Trouble signing in?' line.

**Files**

- `src/main/resources/static/login.html` (modify) — Two <section>s (aria-label 'About the platform' / 'Sign in'); h2 'Sign in', sub 'Use your TCG Digital Microsoft account.'; Microsoft button (4-square SVG, href /oauth2/authorization/azure) before the form; divider text 'or with a username'; button text 'Sign in'; footer line 'Trouble signing in? Ask your platform administrator to check your account.'; drop Font Awesome, load the self-hosted Plex font CSS from U03-T01; bump the login.js ?v= stamp
- `src/main/resources/static/css/login.css` (modify) — Rewrite for the split layout using U03 tokens (navy #14245C panel, canvas right half); decorative rack drawn with spans (role=img with aria-label 'A rack of environments: lights show which VMs are running'); stacks to one column under 768 px with the sign-in half first
- `src/main/resources/static/js/login.js` (modify) — applySignInOptions(): when password login is off hide the form AND the divider (ids kept); showLogoutMessage(): message 'You are signed out.' stays until the user acts (no 5 s timeout, same rule as the error since a72272b); showSignInError() expired text 'Your session ended. Sign in again to carry on.'

**Steps**

1. Restructure login.html into the two sections; keep ids loginForm, username, password, submitBtn, errorAlert, errorMessage, loginDivider, logoutMessage so login.js and specs keep working.
2. Build the left panel rack: 5 rows of static pips (no data) rendered from markup, aria-hidden pips inside one role=img container.
3. Put the Microsoft link first and make it the primary (navy) button; the username form's submit is the secondary style.
4. Update login.js texts (logout and expired) and remove the 5 s logout auto-hide.
5. Restyle login.css with tokens; verify 1440x900 has no scrollbars and 390 px width stacks.
6. Update test/tests/smoke/login.spec.ts and test/tests/auth/session.spec.ts for the new button text/order.

**Acceptance criteria**

- Given password login is on, when /login.html opens, then 'Sign in with Microsoft' is the first focusable action and the username form follows the 'or with a username' divider.
- Given auth.password-login.enabled=false, when the page loads, then only the Microsoft button is shown (no form, no divider).
- Given wrong credentials, when Sign in is pressed, then 'Invalid username or password.' shows with role=alert and stays until a field is edited.
- Given a 1440x900 window, then the page has no scrollbars; at 390 px the sign-in section comes first.

**Tests**

- test/tests/smoke/login.spec.ts (UPDATE): new button order/text, error persistence, password-login-off variant
- test/tests/auth/session.spec.ts (UPDATE): expired message text

**Notes:** login.html is outside the bundle (processResources only patches index.html); it must link the U03-T01 font CSS itself. Keep the button label 'Sign in with Microsoft' (users know Microsoft, not 'Entra ID').

#### U09-T02 — Rebuild Request access as a master-detail page with an inline form and a 'Your requests' tab

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** RequestAccess.dc.html, States.dc.html · **Depends on:** U03-T02, U04-T02, U04-T03

AccessRequests.buildRequestAccessPageHtml() renders a search bar, an 'Available Environments' table (ENV_PAGE_SIZE 7) whose rows open showRequestAccessModal(), then pending and collapsible history cards; the modal never sends durationDays although CreateAccessRequestDTO accepts it. The mockup has two tabs ('Request' and 'Your requests · N'): a searchable environment list on the left (name, description, VM count, a tag 'Request waiting' / 'You can view'), and on the right an inline form 'Request access to <env>' with Operator/View only, 'Part of it' (whole environment or one group), 'For' 7/30/90 days and 'Why you need it'. The second tab lists requests as status rows with Cancel request / Open <env> / Request again.

**Files**

- `src/main/resources/static/js/features/access-requests.js` (modify) — Replace buildRequestAccessPageHtml/buildEnvironmentsList/buildEnvRows/buildMyRequestsTable (lines ~163-400) with tab + master-detail renderers; move the form out of showRequestAccessModal() (line 554) into the right column (keep the modal only for presets from other pages, or route them to #/request-access?env=&group=); submitAccessRequest() (line 676) sends durationDays; ENV_PAGE_SIZE 10; merge VIEWER grants from Config.API.access.myEnvironments so 'You can view' rows can ask for Operator
- `src/main/resources/static/js/core/router.js` (modify) — request-access route accepts ?tab=mine and ?env=<id>&group=<id> params
- `src/main/resources/static/css/features/access-requests.css` (modify) — Replace .ra-* table/filler-row styles with list rows (selected row: inset 3 px navy bar, #ECEFF8), form column, status chips (Waiting gold, Approved green, Denied red)
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentAccessRequestDTO.java` (modify) — Add reviewerNames (List<String>, up to 3 people who can decide: environment admins of the env, else 'Platform administrators') so a waiting row can say 'sent to Sanjay Rao'
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — Fill reviewerNames for /access-requests/my only (one batched query for the requester's pending requests)

**Steps**

1. Add tab state ('new' | 'mine') read from route params; tab buttons use the U04-T02 tabs primitive with counts ('Your requests · 3').
2. Left column: search input + list of buttons (aria-pressed for the selected env), 10 per page with the U04-T02 pager; rows show name, description, '<n> VMs' (or '<n> nodes' for EKS) and the tag.
3. Right column: form with radios Operator (value USER) / View only (VIEWER), 'Part of it' select filled from Config.API.groups.list(envId) (whole environment default), 'For' select 7/30/90 limited by profile.requestMaxDurationDays, textarea 10-1000 chars, 'Send request'. Inline field errors from getAccessRequestErrorMessage().
4. After sending: toast 'Request sent to <reviewers>.', switch to 'Your requests' and refresh the pending set.
5. 'Your requests' tab: one row per request with chip, sentence ('Operator on <b>env</b>, 30 days · sent 2 h ago to …' / 'approved by … on 15 Oct' / denial note in quotes) and the action (Cancel request -> cancelRequest(); Open <env> -> #/environments/<id>; Request again -> tab new with env preselected). Paginate 10.
6. Backend: reviewerNames in the DTO + mapping; integration test.
7. Empty states: no environments left to request ('You can use every environment.'), search with no match ('No environments match "x".' + Clear search).

**Acceptance criteria**

- Given user-001 opens Request access, when an environment is picked, then the right column shows 'Request access to <name>' without opening a modal.
- Given the user picks 'For 30 days' and sends, then POST /environments/{id}/access-requests carries durationDays=30 and the request appears under 'Your requests' as Waiting with the reviewer names.
- Given the user holds View only on an environment, then it is listed with 'You can view' and Operator is preselected.
- Given 25 available environments, then the list shows 10 with a pager and the page has no scrollbar at 1440x900.

**Tests**

- test/tests/access/request-access.spec.ts (NEW): pick env, inline form, durationDays sent, Your requests tab, cancel
- test/tests/navigation/navigation.spec.ts, page-takeover.spec.ts, pagination-scope.spec.ts, router-lifecycle.spec.ts, help-links.spec.ts, environments/view-only.spec.ts (UPDATE): new selectors (no request modal, no #env-count-badge)
- EnvironmentAccessControllerTest (UPDATE): /access-requests/my returns reviewerNames

**Notes:** Other pages call AccessRequests.showRequestAccessModal(envId, envName, preset) (e.g. the view-only environment page, My account extension): switch them to navigate to #/request-access?env=..&group=.. so there is one form. The admin side of this module (pending requests, grant/revoke) belongs to U10.

#### U09-T03 — Show approvals as decision cards with the requester's reason, cost and who decides

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** Approvals.dc.html · **Depends on:** U03-T02, U04-T02, U04-T03

Approvals.card() (js/features/approvals.js line 91) prints a heading, one small grey line (scope, cost, approver mode), the reasons and a bullet list of approvers; Approve and Deny are side by side and Deny uses a prompt. ApprovalRequestDTO has no field for the requester's own note even though the held payload stores it (StartOperationDTO.reason for starts, ExtensionPayload.reason in ApprovalRequestService line 81). The mockup card shows title, 'Priya Nair · asked 40 min ago', an 'Expires in 3 h 20 min' chip, the quoted reason, three facts (Why it needs approval / Estimated cost / Decided by) and 'Deny…' then 'Approve'; tab labels carry counts ('To review · 2').

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/ApprovalRequestDTO.java` (modify) — Add String requesterNote
- `src/main/java/com/tcgdigital/vmcontrol/service/ApprovalRequestService.java` (modify) — In the DTO builder (line ~566) read reason from the JSON payload (StartOperationDTO.reason or ExtensionPayload.reason), null when blank
- `src/main/resources/static/js/features/approvals.js` (modify) — card(): new layout; 'Decided by' text from approverMode + approvers ('Any one of you or Meera Iyer' / 'You and Meera Iyer (both)'); expiry chip from expiresAt (gold under 1 h); deny() opens a dialog with a required reason; tab counts from the loaded lists; 'My requests' as compact status rows (Waiting / Started / Denied / Expired) with Cancel request
- `src/main/resources/static/css/features/approvals.css` (new) — Card header, quoted note, facts grid, expiry chip, status rows (no approvals CSS exists today; .approval-card is unstyled Bootstrap)
- `src/main/resources/static/index.html` (modify) — Link css/features/approvals.css; bump ?v=

**Steps**

1. Backend: requesterNote in the DTO, parsed defensively (bad JSON -> null); extend ApprovalRequestServiceIntegrationTest.
2. Rewrite card() into header (title from what(r), requester + relative time, expiry chip), quoted note, facts grid, actions (Deny… secondary first, Approve primary last).
3. Deny…: Modals dialog 'Deny this request?' with a required reason textarea; Approve: no confirm, toast 'Approved. <env> is starting.' or 'Approved. Waiting for <name>.' when ALL mode still needs others.
4. Show decided items under the pending ones in a lighter style, paginated 10 (no slice(0, 20) cut-off).
5. Tabs show counts; empty texts 'Nothing waits for your approval.' / 'You have not asked for any approvals.'
6. Update test/tests/operations/approvals.spec.ts selectors.

**Acceptance criteria**

- Given a held 24-hour start with reason 'Overnight soak test', when an approver opens To review, then the card quotes the reason and shows 'Runs longer than 8 hours', the cost and who decides.
- Given the approver clicks Deny…, then a dialog requires a reason before the request is denied.
- Given approverMode ALL and one approval given, then 'Decided by' reads 'You and <other> (both)' and the card stays pending.

**Tests**

- ApprovalRequestServiceIntegrationTest (UPDATE): requesterNote for start and extension holds
- test/tests/operations/approvals.spec.ts (UPDATE): new card, deny dialog, tab counts

**Notes:** The access-request approvals for environment admins are U10 (Access requests page); this page is operation approvals (E25) only.

#### U09-T04 — Turn My activity into plain sentences grouped by day, with period buttons and action categories

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** MyActivity.dc.html, States.dc.html · **Depends on:** U01-T03, U03-T02, U04-T02

ActivityLogs.buildActivityLogRow() (js/features/activity-logs.js line 275) renders a six-column table (relative + absolute time, environment, action badge, target, tick icon, details cut at 50 chars) with four selects including rows-per-fetch; the action filter is one AuditAction from /audit/actions. The mockup shows Time / What / Environment / Result, day separators ('Today', 'Friday 17 October'), sentences such as 'Started 5 VMs (Database, Application)', a Done/Failed/Waiting chip, the AWS reason under failed rows, period buttons (24 hours, 7 days, 30 days, Dates…), Environment and Action (All / Start and stop / Locks / Access) selects, Export CSV and a count line.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/AuditController.java` (modify) — getMyLogs() (line 131): optional category param (OPERATIONS | LOCKS | ACCESS) that maps to a set of AuditAction values; action still wins when given
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — getUserActivityLogs() (line 279) overload taking Collection<AuditAction>; new AuditActionCategory helper (static sets: VM_*/ENVIRONMENT_* start/stop/restart, LOCK_*, ACCESS_*)
- `src/main/java/com/tcgdigital/vmcontrol/repository/AuditLogRepository.java` (modify) — findUserActivity variant with 'a.action IN :actions' (separate method, so an empty set never reaches JPQL)
- `src/main/resources/static/js/features/activity-logs.js` (modify) — New row builder: sentence from action + targetName + details (map per AuditAction, fallback actionDisplay), day separator rows, result chip (Failed when success=false with errorMessage as the note; Waiting for *_REQUESTED; else Done); period segmented control replaces #al-time-range-filter; drop the rows-per-fetch select; fixed page size 10 with the U04-T02 pager; CSV keeps all columns
- `src/main/resources/static/css/features/activity-logs.css` (modify) — Day separator, chip and note styles; remove the old filter-bar layout

**Steps**

1. Backend: AuditActionCategory + category param + repository method; AuditFilterIntegrationTest covers each category and the 'action wins' rule.
2. Front end: replace the filter bar with the period group (aria-pressed buttons; 'Dates…' reveals the two date inputs), Environment select (from myEnvironments) and Action category select.
3. Write describe(log) with a table of templates for the common actions (start/stop VM and environment, lock acquire/release/extend, access requested/granted/denied, lease extended, approval asked/decided); unknown actions use actionDisplay + targetName.
4. Render rows with day separators (local dates, 'Today', 'Yesterday', then 'Friday 17 October'), 10 per page, footer '9 actions in the last 7 days'.
5. Keep exportActivityLogs() but name the file my-activity-YYYY-MM-DD.csv and add the sentence column; toast 'Exported 9 actions.'
6. Update specs that use #al-action-type-filter / #al-time-range-filter.

**Acceptance criteria**

- Given a failed VM start, when My activity shows it, then Result is 'Failed' and the AWS reason is shown under the sentence.
- Given Action = Locks, then only LOCK_* rows are listed (server-side, so paging counts are right).
- Given 30 rows in the period, then 10 show per page and the page has no scrollbar at 1440x900.

**Tests**

- AuditFilterIntegrationTest (UPDATE): category=OPERATIONS/LOCKS/ACCESS on /audit/logs/my
- test/tests/security/activity-logs.spec.ts, a11y/contrast.spec.ts, navigation/route-params.spec.ts, navigation/toasts-modals.spec.ts (UPDATE): new filter selectors
- src/test/js/activity-describe.test.mjs (NEW): describe(log) sentences for each mapped action

**Notes:** Instance IDs in details/errorMessage are masked by U01-T03 before they reach this page. The admin-wide audit log (all-logs.js) is U12.

#### U09-T05 — Reshape the My account panel into Access, Cost, Notifications and Profile tabs

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** MyAccount.dc.html · **Depends on:** U04-T02, U05-T05, U03-T02

MyAccount (js/features/my-account.js) opens a Slideout with six tabs (TABS line 20: Overview, Access, Activity, Cost, Notifications, API tokens); Overview duplicates Home and Activity duplicates My activity. The mockup panel has a header (initials avatar, name, 'email · role', close), four tabs: Access (grant cards: env, level, 'Until 17 Nov (29 days)' or 'Ends 22 Oct — in 3 days' in gold, 'Granted by', 'Request an extension', 'Show 2 ended grants'), Cost (month-to-date total and per environment), Notifications (Tell me when / In app / Email grid) and Profile (signed in with, role, team, shortcuts hint, Sign out). MyProfileDTO has authMethod but no team.

**Files**

- `src/main/resources/static/js/features/my-account.js` (modify) — TABS -> access, cost, notifications, profile (+ api-tokens when state.tokensEnabled); remove overviewHtml/statTileHtml/recentActivityHtml/activityHtml and the activity/history loads not used by Access; open(tab) maps old ids ('overview' -> access, 'activity' -> navigate #/activity-logs); new header; grant cards; 'Show N ended grants' toggles the history list; profileHtml(); notificationsHtml() grid with named checkboxes
- `src/main/resources/static/css/features/my-account.css` (modify) — Panel header, grant card, cost list, prefs grid; drop .ma-stat/.ma-overview styles
- `src/main/java/com/tcgdigital/vmcontrol/dto/MyProfileDTO.java` (modify) — Add List<String> teamNames
- `src/main/java/com/tcgdigital/vmcontrol/service/MyAccountService.java` (modify) — getProfile() (line 85) fills teamNames from TeamMemberRepository
- `src/main/resources/static/js/ui/user-menu.js` (modify) — Account menu opens the panel on the Access tab (MyAccount.open('access'))

**Steps**

1. Backend: teamNames in MyProfileDTO; extend the my-profile controller test.
2. Panel shell: use the U04-T02 side panel (role=dialog, aria-modal, labelled by the name heading), header with initials, email · role label, close button.
3. Access tab: sortGrants() cards with level label (Operator / View only / 'Operator, Application group only'), end text (gold when isExpiring), 'Granted by <grantedByUserName>', 'Request an extension' wired to the existing extension flow; pending requests listed above; ended grants behind 'Show N ended grants'.
4. Cost tab: total 'Month to date across the environments you can use' + one row per environment (keep sparkline only if it fits; quotas stay below).
5. Notifications tab: grid with checkboxes labelled '<event>, in app' / '<event>, email'; locked/unavailable states keep their text; Save stays explicit.
6. Profile tab: Signed in with (Microsoft (Entra ID) or Username and password, from authMethod / U05-T05), Role, Team(s), Keyboard shortcuts '? to see them', Sign out (Auth.logout()).
7. Update the my-account specs and a11y specs for the new tabs.

**Acceptance criteria**

- Given user-001 opens My account, then the panel shows the Access tab with one card per grant and no Overview or Activity tab.
- Given a grant ends in 3 days, then its end text is gold and 'Request an extension' is offered.
- Given the user is a member of team Payments, when Profile opens, then Team shows 'Payments'.
- Given any checkbox in Notifications, then its accessible name includes the event and the channel (no unnamed 'on' switches).

**Tests**

- test/tests/access/my-account-cost.spec.ts, my-account-extension.spec.ts, my-account-history.spec.ts, notification-preferences.spec.ts, api-tokens.spec.ts (UPDATE): new tabs and selectors
- test/tests/a11y/slideout-dialog.spec.ts, keyboard-reach.spec.ts, names-and-targets.spec.ts (UPDATE)
- UserControllerTest or MyAccountService test (UPDATE): teamNames

**Notes:** Other modules call MyAccount.open('activity') or open('overview') (lease-banner, quotas specs, notification bell settings link): keep the mapping so they still work. API tokens stays a fifth tab only while tokens are on.

#### U09-T06 — Make the notifications popover a dialog of recent items and restyle the Ask panel

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** Notifications.dc.html, AskPanel.dc.html · **Depends on:** U01-T04, U04-T01, U04-T02

NotificationBell.loadNotifications() (js/ui/notification-bell.js line 72) loads only /notifications/unread into #notification-dropdown, which has role=menu and an empty state with an icon; the footer has only 'Notification settings'. The mockup popover (role=dialog) lists the latest items, unread ones bold with a coloured dot (gold lease, red failure, grey info), title + one-line body + time, with 'Mark all as read' and footer links 'Notification settings' and 'See all activity'. AssistantPanel.open() (js/features/assistant.js line 108) shows a disclaimer paragraph, a bare thread, a textarea and Clear/Ask; the mockup Ask panel has a one-line scope note ('It can't start, stop or change anything'), message bubbles, suggestion chips, links to environments named in an answer, an input with Ask, and 'Remembers the last 10 messages of this conversation. Start over' (matches AssistantChatService.MAX_HISTORY_TURNS = 10).

**Files**

- `src/main/resources/static/index.html` (modify) — #notification-dropdown: role=dialog, aria-labelledby a 'Notifications' h2; footer gets a second link 'See all activity' (#/activity-logs); bell aria-label updated with the unread count ('Notifications, 2 unread')
- `src/main/resources/static/js/ui/notification-bell.js` (modify) — loadNotifications() uses Config.API.notifications.list?page=0&size=8 (read and unread); buildItem() renders dot tone from typeTone(), bold when unread; opening an item marks it read (markOneRead) and follows linkFor(n); focus moves into the dialog on open and back to the bell on Escape/close; settings link -> MyAccount.open('notifications')
- `src/main/resources/static/css/components/notification-bell.css` (modify) — Popover 380 px wide under the bell, item rows, dots; remove icon-heavy empty state
- `src/main/resources/static/js/features/assistant.js` (modify) — open(): new panel layout; suggestion chips ('Cost trend for 3 months', 'Spend by service', 'Savings I could make', 'VMs that could be smaller') send the question; after an answer, link environment names the user can see (from Config.API.access.myEnvironments, loaded once) as 'Open <env>' buttons below the bubble; 'Start over' replaces Clear; the busy line reads 'Looking it up…' with a dot animation honouring prefers-reduced-motion
- `src/main/resources/static/css/features/assistant.css` (modify) — Bubbles (question right, navy tint; answer left, white), chips, footer line

**Steps**

1. Change the popover markup and role; make keyboard handling dialog-like (Tab stays inside, Escape closes, focus returns to the bell).
2. Switch the list to the all-notifications endpoint, unread first styling, 8 newest; empty text 'No notifications yet.'
3. Wire 'See all activity' and 'Notification settings'; keep 'Mark all as read' visible only when there are unread items.
4. Ask: rebuild open()/draw()/messageHtml() markup; chips shown only while the thread is empty or after an answer.
5. Ask: environment links — match environment display names in the answer text against the user's environments (case-insensitive whole words), at most 3 'Open <env>' buttons linking #/environments/<id>.
6. Update notification-bell.spec.ts and ask-panel.spec.ts.

**Acceptance criteria**

- Given one unread and three read notifications, when the bell is opened, then all four are listed, the unread one bold with a dot, inside a role=dialog labelled 'Notifications'.
- Given the popover is open, when Escape is pressed, then it closes and focus is on the bell.
- Given an Ask answer mentions 'Payments Dev' which the user can see, then an 'Open Payments Dev' link follows the answer.
- Given the Ask panel is empty, when a chip is clicked, then that question is sent.
- Given weekly in-app reports are on (notification.weekly-reports.enabled), when the weekly report arrives, then it appears in the notifications popover with its title, summary and link like any other item.

**Tests**

- test/tests/navigation/notification-bell.spec.ts (UPDATE): read+unread list, dialog role, footer links
- test/tests/access/ask-panel.spec.ts (UPDATE): chips, Start over, environment links (mocked /assistant/ask)

**Notes:** Where Ask opens from is not drawn in the mockup: keep the account-menu entry (#ask-menu-btn, shown only while assistant.chat.enabled) and add it to the top-bar help menu of U04-T04 if that menu exists by then.

#### U09-T07 — Group the Help topics, add the power-strip legend, and fill the shortcuts dialog

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** Help.dc.html, Shortcuts.dc.html · **Depends on:** U04-T04, U03-T03, U02-T04

Help.load() (js/features/help.js line 28) renders one flat topic list with search in the left nav and a card article; help-content.js has 17 topics with old page names ('Request Access', 'My Account > Access'). The mockup has search in the page header, topic groups (Using environments / Access / Cost and more, plus Manage and Administration groups for those roles), the current topic marked with a gold inset bar, and the start/stop article carries the power-strip legend. Keyboard.showShortcutsHelp() (js/core/keyboard.js line 102) prints one table; the mockup groups 'Go to' (Home g d, My environments g e, Request access g r, My activity g a) and 'On any page' (r, ?, Esc) and ends with the rule that shortcuts never fire while typing or in a dialog.

**Files**

- `src/main/resources/static/js/features/help-content.js` (modify) — Add group to every topic (using, access, cost, manage, admin); rename titles to the mockup's ('Environments and start order', 'Starting, stopping and locks'); add a legend flag on the start/stop and environments topics
- `src/main/resources/static/js/features/help.js` (modify) — Header search (label 'Search help'), grouped nav with group headings and buttons/links with aria-current=page; legend block rendered with the U03-T03 PowerStrip component ('Green is running, gold is starting or stopping, hollow is stopped, striped means the state hasn't been read from AWS yet.')
- `src/main/resources/static/css/features/help.css` (modify) — Two-column fit-to-screen layout (nav 280 px, article scrolls inside), active topic style
- `src/main/resources/static/js/core/keyboard.js` (modify) — shortcuts entries get group ('go' | 'any') and mockup labels ('Home', 'My environments', 'Request access', 'My activity', 'Refresh what you see', 'Show this list', Esc 'Close a dialog or panel'); showShortcutsHelp() renders two groups with kbd and the closing note

**Steps**

1. Add groups and renamed titles in help-content.js; keep topic ids stable so HelpLink.button() links keep working.
2. Render the grouped nav; groups with no visible topic for the role are hidden (Help.visible()).
3. Move search into the page header; a search shows matching topics across groups, 'No topic matches "x".' when none.
4. Render the legend with PowerStrip in topics that set legend: true.
5. Update Keyboard shortcut labels/groups and the dialog markup (inside whatever dialog U04-T04 opens from the top-bar help menu).
6. Update help.spec.ts, help-links.spec.ts and keyboard-shortcuts.spec.ts.

**Acceptance criteria**

- Given a user opens Help, then topics appear under 'Using environments', 'Access' and 'Cost and more', and admin-only topics are not listed.
- Given the 'Starting, stopping and locks' topic, then the power-strip legend is shown with its sentence.
- Given '?' is pressed outside a field, then the dialog lists 'Go to' and 'On any page' groups and the typing/dialog rule.

**Tests**

- test/tests/navigation/help.spec.ts (UPDATE): groups, role filtering, search
- test/tests/navigation/keyboard-shortcuts.spec.ts (UPDATE): grouped dialog
- test/tests/navigation/help-links.spec.ts (UPDATE): titles

**Notes:** Article body rewrites for the new screens are U17-T09; this task only changes structure, titles and the legend.

#### U09-T08 — Give the Teams one-click confirm page its own bare layout, a question title and clear outcome states

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** medium · **Sources:** ChatAction.dc.html · **Depends on:** U03-T02, U01-T04

ChatActionPage.load() (js/features/chat-action.js) renders inside the full app shell with heading 'Teams action', a select for the duration, 'You act as yourself…' and Cancel; failure() maps 409/403/404 to generic titles and every 400 (expired or invalid) to 'This link cannot be used'. ChatActionService.Preview has description and confirmLabel but no question title or source. The mockup is a single centred card under a slim header: 'From the Payments Dev Teams channel', a question ('Extend the lease on Payments Dev?'), the current state sentence, choices as large radio chips (1/2/4 hours), 'Signed in as <name>. The link works once and ends at <time>.', 'Open Payments Dev instead' and the primary action; outcome states Done / already used / expired / not allowed (names the environment admin).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatActionService.java` (modify) — Preview gains title (question per type: 'Extend the lease on X?', 'Stop X?', 'Snooze idle auto-stop on X?', 'Approve this access request?', 'Approve this start?', 'Create a Jira ticket for X?') and source ('From the X Teams channel'); description keeps the state sentence
- `src/main/java/com/tcgdigital/vmcontrol/service/chat/ChatActionTokenService.java` (modify) — verify(): expired/invalid/used failures carry a code (LINK_EXPIRED, LINK_INVALID, LINK_USED) via an optional code on ValidationException/ConflictException
- `src/main/java/com/tcgdigital/vmcontrol/controller/GlobalExceptionHandler.java` (modify) — Add 'code' to the error body when the exception has one (body() helper, line 39); 403 from the chat action adds adminName when the environment has an admin the caller may see
- `src/main/resources/static/js/features/chat-action.js` (modify) — frame(): bare card layout; radio chips instead of <select>; signed-in line from Auth current user and expiresAt; failure() by code (Done, 'This link was already used', 'This link has expired', 'You can't do this') with the environment link
- `src/main/resources/static/js/core/router.js` (modify) — For contentType 'chat-action' add body class 'bare-page' (removed on leave) so the sidebar, bottom nav and top-bar tools are hidden
- `src/main/resources/static/css/layout/content.css` (modify) — body.bare-page rules: hide #sidebar, .mobile-nav, bell/account; centre #content-area card (max 560 px)

**Steps**

1. Backend: title + source in Preview for each ChatActionType; error codes on the link failures; ChatActionServiceTest/ChatActionTokenServiceTest/ChatActionControllerIntegrationTest assert them.
2. Router: bare-page body class for the chat-action route, cleared via ContentRouter.onLeave.
3. Rebuild the card: source line, h1 question, description, fieldset 'Add' / 'Snooze for' with radio chips (default checked), signed-in line, 'Open <env> instead' link and the confirm button (danger style for STOP_ENVIRONMENT).
4. Outcome states as role=status (done) or role=alert (problems) with dot colours from the mockup, each with 'Open <env>'.
5. Check the page at 390 px (Teams mobile opens it on a phone).
6. Update test/tests/environments/chat-action.spec.ts.

**Acceptance criteria**

- Given a valid extend link, when it opens, then the page shows 'Extend the lease on <env>?' with 1/2/4 hour chips (2 selected) and no side menu.
- Given the link was already used, then the page says 'This link was already used' and links to the environment.
- Given an expired link, then the page says 'This link has expired' (not a generic error).
- Given a viewer opens an extend link, then 'You can't do this' explains they can view but not change the lease and names an admin when known.

**Tests**

- ChatActionControllerIntegrationTest (UPDATE): title/source in preview, error codes for used/expired/invalid
- ChatActionServiceTest (UPDATE): titles per type
- test/tests/environments/chat-action.spec.ts (UPDATE): bare layout, chips, each outcome state

**Notes:** ChatDispatchService builds /home#/chat-action/<token> links; keep that URL. Adding 'code' to error bodies is additive and safe for other callers.

#### U09-T09 — Show new-environment templates as cards with VMs, cost per day and lifetime

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** NewEnvironment.dc.html · **Depends on:** U03-T03, U04-T02

Templates.drawCatalogue() (js/features/templates.js line 109) shows cards with region, engine name, '~$x/month', 'N h by default, up to M h' and an approval badge. The mockup card shows the template name and description, a power strip of its shape, VMs, 'Per day' and 'Lifetime: Up to 30 days', and 'Request this'; the tabs are 'Templates' and 'My requests · 1'. EnvironmentTemplateDTO has estimatedMonthlyCost and maxTtlHours but nothing about the VMs a template creates.

**Files**

- `src/main/resources/db/migration/V92__add_template_vm_layout.sql` (new) — ALTER TABLE environment_template ADD COLUMN vm_layout VARCHAR(64) NULL (use the next free V number at merge time)
- `src/main/java/com/tcgdigital/vmcontrol/model/EnvironmentTemplate.java` (modify) — vmLayout field ('1|2|1' = VMs per group in start order)
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentTemplateDTO.java` (modify) — Add vmLayout; also accept it in UpsertEnvironmentTemplateDTO (validated pattern ^\d+(\|\d+)*$)
- `src/main/java/com/tcgdigital/vmcontrol/service/provisioning/TemplateService.java` (modify) — toDto() (line 277) and upsert map vmLayout
- `src/main/resources/static/js/features/templates.js` (modify) — drawCatalogue(): card layout with PowerStrip (all stopped) from vmLayout, VMs = sum, Per day = estimatedMonthlyCost/30, Lifetime = 'Up to N days' (hours when under 48 h), 'Needs approval' as a small note; tab label 'My requests · N'; 'Request this' opens the existing request form
- `src/main/resources/static/css/features/templates.css` (new) — Template card (strip, dl facts row, action) — no template CSS exists today; link it in index.html and bump ?v=

**Steps**

1. Migration + entity + DTO + upsert mapping; TemplateControllerTest round-trips vmLayout and rejects a bad pattern.
2. Card markup per the mockup; hide the strip and VM count when vmLayout is null, hide Per day when there is no estimate.
3. Tab count from the loaded 'mine' list (load it once on page open).
4. Keep the request form, cancel/extend/destroy actions; restyle the 'My requests' table as status rows if it fits, else keep the table with the U03 table style.
5. Update test/tests/environments/templates.spec.ts.

**Acceptance criteria**

- Given a template with vmLayout '1|2|1' and $294/month, then its card shows a 4-light strip in three groups, 'VMs 4' and 'Per day $9.80'.
- Given maxTtlHours 720, then Lifetime reads 'Up to 30 days'.
- Given the user has one request, then the second tab reads 'My requests · 1'.

**Tests**

- TemplateControllerTest (UPDATE): vmLayout round trip and validation
- test/tests/environments/templates.spec.ts (UPDATE): card facts and tab count

**Notes:** The admin field to set vmLayout in the templates editor belongs to U14 (templates admin); until then it is set through the API. Provisioning stays behind its existing switch (page answers 404 when off). Migration numbers V90-V95 follow task order (U01-T06 V90, U08-T01 V91, U09-T09 V92, U13-T01 V93, U13-T06 V94, U15-T08 V95); take the next free number if another migration lands first.

## U10

### Access for environment admins — Manage

Environment admins answer access requests, see and change every grant on their environments from one paged list, and grant many people access to many environments or groups in one step with a preview of what will be new, extended or skipped.

Sources: AccessRequests.dc.html, AccessManagement.dc.html, GrantAccess.dc.html, AccessChange.dc.html, Audit finding: Access Requests Approve/Deny overlap, Known gap: bulk grant endpoint · 8 tasks · 11 days

#### U10-T01 — Add one paged grants endpoint with status filters and a summary for the access management page

- [ ] Done · **Type:** backend · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** AccessManagement.dc.html

AccessManagement.fetchAccessForSelection (static/js/features/access-management.js:149) calls GET /environments/{id}/access once per environment when 'All environments' is picked (N requests, active grants only), then filters and pages in the browser. The mockup needs one paged list across the caller's environments with Active / Ending within 7 days / Ended filters, search, and header counts (grants, admins, operators, view only, end within 7 days).

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/AccessGrantController.java` (modify) — Add GET /api/v1/access-grants (params environmentId, status=ACTIVE|ENDING|ENDED, search, page, size max 100) and GET /api/v1/access-grants/summary; scope with securityService.administeredEnvironmentIdsOrAll() like AuditController.filter; an environment outside scope is 404
- `src/main/java/com/tcgdigital/vmcontrol/repository/EnvironmentAccessRepository.java` (modify) — New searchGrants(@Param allowed env ids / restricted flag, envId, status bucket, now, now+7d, text, Pageable) JPQL fetching user, environment; ENDED = REVOKED or EXPIRED or expires_at < now, ended within access.history-days (default 90); countSummary query grouped by access level
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — searchGrants(...) and summary(...) methods; reuse endedAt(access) (line 761) for ended rows
- `src/main/java/com/tcgdigital/vmcontrol/dto/AccessGrantSummaryDTO.java` (new) — record(total, admins, operators, viewers, endingWithin7Days)
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentAccessDTO.java` (modify) — Make sure grantedByDisplayName, scopeName, expiresAt, endedAt and status are present for the list row
- `src/main/resources/static/js/config.js` (modify) — access.grants(params) and access.grantsSummary(params) URLs next to accessGrants (line 68)

**Steps**

1. Add the repository query with a status bucket parameter: ACTIVE (status ACTIVE, expires null or > now+7d or any active), ENDING (ACTIVE and expires between now and now+7d), ENDED (not ACTIVE or expired, ended within the history window). Order: ENDING by expires_at asc, others by user display name.
2. Search matches user display name, email, environment display name, group name (LIKE, lower-case).
3. Service methods take the allowed environment ids from SecurityService (null = all for ADMIN; ENV_ADMIN with security.env-admin.scope=assigned gets their administered ids); empty list returns an empty page.
4. Controller endpoints @PreAuthorize hasAnyRole('ADMIN','ENV_ADMIN'); map with the existing toDtos(...) so group names resolve in one query.
5. Summary counts ACTIVE grants only, per AccessLevel (ADMIN=admins, USER=operators, VIEWER=view only) plus endingWithin7Days.
6. Add the config.js URLs; do not change the page yet (U10-T04 switches to them).

**Acceptance criteria**

- Given an ENV_ADMIN with assigned scope on Analytics QA only, when GET /api/v1/access-grants is called, then only Analytics QA grants are returned and environmentId=<CRM UAT> returns 404.
- Given a grant expiring in 3 days, when status=ENDING, then it is returned and it is not returned for status=ENDED.
- Given 29 active grants, when GET /access-grants/summary, then total=29 and the per-level counts add up to 29.
- Given a USER or VIEWER, when either endpoint is called, then 403.

**Tests**

- AccessGrantListIntegrationTest (NEW): status buckets, search, paging, scope for ADMIN vs assigned ENV_ADMIN, summary counts
- AccessGrantControllerSecurityTest (UPDATE): 403 for USER and VIEWER on the two new GETs

**Notes:** Keep GET /environments/{id}/access unchanged; it is still used by access-requests.js loadAccessList and by the environment page.

#### U10-T02 — Add the bulk grant endpoints: preview and apply for many people x many environments or groups

- [ ] Done · **Type:** backend · **Estimate:** 2 d · **Risk:** high · **Sources:** GrantAccess.dc.html, Known gap: bulk grant endpoint · **Depends on:** U10-T01

Grants are made one person and one environment per call: AccessGrantController.grant (controller/AccessGrantController.java:66) takes a single userEmail/environmentId and EnvironmentAccessService.grantScoped (service/EnvironmentAccessService.java:378) upserts through applyGrant, which also lowers an existing higher level. GrantAccess.dc.html grants several people (or a whole team) on several environments or groups at once and first shows each pair as New, Extends, Upgrades or No change.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/BulkGrantRequestDTO.java` (new) — userEmails (List, max 50), teamIds (List), targets [{environmentId, scopeType ENVIRONMENT|GROUP, groupIds}] (max 20), accessLevel, durationDays (1..AccessDurations.HARD_MAX_DAYS) or clearExpiry, notes (max 500), emailPeople (boolean)
- `src/main/java/com/tcgdigital/vmcontrol/dto/BulkGrantPlanDTO.java` (new) — record(people, environments, changes, skipped, rows[{userId, userName, userEmail, environmentId, environmentName, scopeType, scopeId, scopeName, outcome NEW|EXTEND|UPGRADE|SKIP, currentLevel, currentExpiresAt, resultLevel, resultExpiresAt, reason}])
- `src/main/java/com/tcgdigital/vmcontrol/service/BulkAccessGrantService.java` (new) — plan(actorId, dto) and apply(actorId, dto): resolve people (emails + team members via TeamMemberRepository at request time), check canManageEnvironmentAccess/canManageGroupAccess on every target, build rows, apply non-SKIP rows through EnvironmentAccessService in one transaction
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — Expose a package-visible applyBulkRow(GrantSpec) wrapper around applyGrant (line 540) and a lookup of the current active grant per (user, scope) for the plan; fire fireAccessGrantedAutomation once per changed environment
- `src/main/java/com/tcgdigital/vmcontrol/controller/AccessGrantController.java` (modify) — POST /api/v1/access-grants/bulk/preview (200, no writes) and POST /api/v1/access-grants/bulk (201, returns the applied plan)
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyBulkAccessGranted(userId, List<line>) : one in-app notification per person listing what they can now use; email only when emailPeople=true (explicit opt-in like notifyStopEnvironment)
- `src/main/resources/static/js/config.js` (modify) — access.bulkPreview and access.bulkGrant URLs

**Steps**

1. Resolve people: each email must be an existing active app user (unknown emails are a 400 listing them); a team adds its active members as of now; de-duplicate by userId.
2. Authorize every target first (no partial authorization): a GROUP target needs access.group-scope.enabled and canManageGroupAccess per group; an ENVIRONMENT target needs canManageEnvironmentAccess. Reject more than 500 rows with a 400 that says how many and the limit.
3. For each (person, scope) row compare with findActiveByUserAndScope: none -> NEW; existing level lower -> UPGRADE; same level and the new end is later (or clearExpiry over a dated grant) -> EXTEND; existing level higher, or same level with a later/no end -> SKIP with reason ('Already has Admin, which is more'); the actor's own rows -> SKIP 'You can't change your own access'. Bulk never lowers a level.
4. preview returns the plan; apply recomputes the plan inside one @Transactional call, applies NEW/EXTEND/UPGRADE rows via applyGrant (audit per row as today), and returns the same shape.
5. Notifications: suppress the per-grant notifyGrantOutcome for bulk rows and send one notifyBulkAccessGranted per person after commit (AfterCommit, like sideEffectAfterCommit at line 914); email only with emailPeople.
6. Validate durationDays against access.grant.max-duration-days through the existing applyExpiry path; the UI's 'Until a date' sends whole days computed client side.

**Acceptance criteria**

- Given Priya has Operator on CRM UAT until 22 Oct, when the preview grants Operator for 30 days on CRM UAT, then her row is EXTEND with the new end date.
- Given Neha has Admin on Payments Dev, when Operator is granted in bulk, then her row is SKIP and her grant stays Admin.
- Given an ENV_ADMIN who cannot manage one of three target environments, when apply is called, then 403 and no grant is written.
- Given 3 people x 2 environments with 1 skip, when apply succeeds, then 5 grants are written, 5 ACCESS_GRANTED/ACCESS_LEVEL_CHANGED audit rows exist and each person gets one notification.
- Given a team chosen, when a member joins the team later, then that member does not get the access.

**Tests**

- BulkAccessGrantServiceTest (NEW, integration): outcome rules NEW/EXTEND/UPGRADE/SKIP, self skip, never downgrade, team expansion, row limit, all-or-nothing on a failed authorization
- AccessGrantControllerSecurityTest (UPDATE): bulk endpoints per role (ADMIN ok, ENV_ADMIN scoped, USER/VIEWER 403)
- NotificationServiceTest (UPDATE): one notification per person, email only when emailPeople

**Notes:** Directory (Entra) people not yet in the app are out of scope here; bulk onboarding is U13. Decision: bulk never lowers a level; lowering stays a per-grant 'Change access'.

#### U10-T03 — Add one access-level vocabulary helper and use it everywhere access is shown

- [ ] Done · **Type:** refactor · **Estimate:** 0.5 d · **Risk:** low · **Sources:** GrantAccess.dc.html, AccessChange.dc.html, AccessManagement.dc.html

Access levels are labelled ad hoc: access-management.js uses STATUS_LABEL and level badges, access-requests.js describeRequest (line 737) words levels differently, and the mockups use 'View only / Operator / Admin' with the descriptions 'See state, cost, history', 'Start, stop and lock', 'Also grant access and edit'. One helper keeps the new pages consistent.

**Files**

- `src/main/resources/static/js/core/access-levels.js` (new) — AccessLevels IIFE: label(level), description(level), chipClass(level), endsText(expiresAt, now) ('No end', '20 Oct · 1 day', '16 Jan 2027'), durationOptions() (7, 30, 90 days, No end, Until a date)
- `src/main/resources/static/index.html` (modify) — Add the script after js/core/utils.js; bump the ?v= stamp
- `src/main/resources/static/js/features/access-requests.js` (modify) — describeRequest/currentAccessHtml (lines 737-760) use AccessLevels
- `src/main/resources/static/js/features/access-management.js` (modify) — buildAccessRow (line 619) uses AccessLevels.label/endsText
- `src/test/js/access-levels.test.mjs` (new) — node tests for labels and endsText

**Steps**

1. Map VIEWER -> 'View only', USER -> 'Operator', ADMIN -> 'Admin' with the mockup descriptions.
2. endsText: null -> 'No end'; within 7 days -> 'D Mon · N day(s)' with a soon flag; else 'D Mon YYYY'.
3. Replace the inline labels in the two feature files; keep behaviour otherwise unchanged.
4. Load the IIFE in node:vm in the test like src/test/js/actions.test.mjs does.

**Acceptance criteria**

- Given a grant with level USER ending tomorrow, when it is rendered anywhere, then it reads 'Operator' and '<date> · 1 day' in the warning colour.
- Given the old pages, when loaded, then no page shows the raw enum names VIEWER/USER/ADMIN.

**Tests**

- src/test/js/access-levels.test.mjs (NEW): labels, descriptions, endsText boundaries

**Notes:** AccessChange.dc.html uses 'Can view / Can use / Can manage'; this task picks the GrantAccess wording (View only / Operator / Admin) for every screen. Flag for U17 copy review.

#### U10-T04 — Rebuild Access management as a paged grants list with Grants and History tabs

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** AccessManagement.dc.html · **Depends on:** U10-T01, U10-T03, U04-T02, U04-T03, U03-T02

access-management.js buildViewHtml (line 253) renders Grants / Pending / Activity Bootstrap tabs, loads every environment's access in the browser and pages client-side. The mockup shows a header with 'Grant access', five counts, Grants and History tabs, search + environment + status filters (Active, Ending within 7 days, Ended), a 10-per-page table (Person, Environment, Level, Part, Granted by, Ends, Actions: Change, Revoke). Pending requests move to their own page (U10-T07).

**Files**

- `src/main/resources/static/js/features/access-management.js` (modify) — Replace fetchInitialData/fetchAccessForSelection (lines 86-160) with GET /access-grants + /access-grants/summary; rebuild buildViewHtml, renderAccessTable, applyFilters as server-side filters; remove the Pending tab (link to #/pending-requests with the count instead); History tab = status ENDED plus the existing access activity feed
- `src/main/resources/static/css/features/access-management.css` (modify) — Stats row, level chips, ending-soon colour, fit-to-screen table
- `src/main/resources/static/index.html` (modify) — Bump ?v= stamp
- `test/tests/access/access-management.spec.ts` (new) — Page loads with counts, filters, paging, Change/Revoke present for env admin
- `test/tests/access/edit-grant-expiry.spec.ts` (modify) — Update selectors to the new table and Change dialog

**Steps**

1. Use the U04-T03 page header (title 'Access management', subtitle 'Who can use which environment, at what level, and until when.', primary 'Grant access' -> #/access-management/grant).
2. Stats row from /access-grants/summary: grants, admins, operators, view only, end within 7 days (the last one clicks the Ending filter).
3. Filters: search (debounced 300 ms, Utils.debounce), environment select, status segmented control; every change refetches page 0 from the server.
4. Table rows: name + email, environment, level chip (AccessLevels), part ('Whole' or group name), granted by, ends (AccessLevels.endsText); actions Change (U10-T05 dialog) and Revoke (U10-T05 dialog); hide actions on the viewer's own row with the tooltip 'You can't change your own access'.
5. Use the U04-T02 pager (10 per page, 'Previous / Next', '1-10 of 29'); History tab shows ENDED grants with who ended them.
6. Keep openForEnvironment(envId) working (preselects the environment filter) for links from the environment page.

**Acceptance criteria**

- Given an env admin with 29 grants, when the page opens, then one request each to /access-grants and /access-grants/summary is made (no per-environment requests) and 10 rows show with '1-10 of 29'.
- Given the 'Ending within 7 days' filter, when chosen, then only grants ending within 7 days show, soonest first.
- Given the page at 1440x900, when loaded, then there is no page scrollbar.

**Tests**

- test/tests/access/access-management.spec.ts (NEW): counts, filter, paging, no N+1 requests (count network calls)
- test/tests/access/edit-grant-expiry.spec.ts (UPDATE): new selectors
- test/tests/navigation/navigation.spec.ts (UPDATE if it clicks the old Pending tab)

#### U10-T05 — Replace the edit and revoke modals with the Change access and Remove access dialogs

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** AccessChange.dc.html · **Depends on:** U10-T04

Editing reuses the whole grant modal in edit mode (access-management.js showGrantAccessModal(editAccess) line 1075, setKeepExpiryOption line 1060) and revoking uses a generic confirm (handleRevokeAccess line 1412). AccessChange.dc.html has a focused 'Change access for <name>' dialog (three level cards, Ends: keep current / 30 days / 90 days / no end, optional note kept in the audit log, self-change notice) and a 'Remove <name>'s access?' dialog that says what happens.

**Files**

- `src/main/resources/static/js/features/access-change-dialogs.js` (new) — AccessChangeDialogs.openChange(grant, onDone) -> PATCH /access-grants/{id} (UpdateAccessGrantDTO: accessLevel, durationDays or clearExpiry, notes); openRemove(grant, onDone) -> DELETE /access-grants/{id}
- `src/main/resources/static/js/features/access-management.js` (modify) — Change/Revoke actions call the new dialogs; delete the edit branch of showGrantAccessModal and handleRevokeAccess
- `src/main/resources/static/index.html` (modify) — Add access-change-dialogs.js before access-management.js; bump ?v=
- `test/tests/access/access-change.spec.ts` (new) — Change level and end, remove access, self row shows the notice

**Steps**

1. Build both dialogs on the U03-T02 dialog component; title 'Change access for <name>', subtitle '<env or group> · <level> · ends <date>'.
2. Level radio cards from AccessLevels with descriptions; Ends select: 'Keep <date>' (sends nothing), '30 days from today', '90 days from today', 'No end date' (clearExpiry=true).
3. Note textarea, max 500, hint 'optional, kept in the audit log'.
4. When the grant is the viewer's own, disable Save and show 'You can't change your own access; another admin has to.' (server already returns 403 SELF_CHANGE).
5. Remove dialog: 'Remove <first name>'s access?', body '<name> can no longer see or use <env>. The change is recorded in the audit log. To give access back, grant it again.', buttons 'Keep access' / 'Remove access' (danger).
6. On success toast 'Access saved' / 'Access removed' and refresh the list and summary.

**Acceptance criteria**

- Given an Operator grant ending 31 Oct, when 'Admin' and '90 days from today' are saved, then PATCH sends accessLevel=ADMIN and durationDays=90 and the row updates.
- Given 'Keep 31 Oct', when only the level changes, then durationDays and clearExpiry are not sent.
- Given the Remove dialog, when 'Keep access' is pressed, then no request is made.

**Tests**

- test/tests/access/access-change.spec.ts (NEW)
- test/tests/access/edit-grant-expiry.spec.ts (UPDATE): uses the new dialog

**Notes:** Pronoun-free copy: use the person's first name, never his/her.

#### U10-T06 — Build the Grant access page with people, environments, access, notify and a live preview

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** GrantAccess.dc.html · **Depends on:** U10-T02, U10-T03, U10-T04, U03-T03, U02-T01

Today's Grant Access modal (access-management.js showGrantAccessModal line 1075, handleGrantAccess line 1341) picks one user and one environment. GrantAccess.dc.html is a page with four steps (People with chips or a whole team; Environments with whole/only some groups; Access level + For; Let them know with a note and 'Email each person') and a 'What will happen' preview listing every person x environment as New / Extends / Upgrades / No change, with the button 'Grant access (N)'.

**Files**

- `src/main/resources/static/js/features/grant-access.js` (new) — GrantAccessPage.load(params): state {people[], teamIds[], targets[], level, duration, note, emailPeople}; debounced POST /access-grants/bulk/preview on every change; submit POST /access-grants/bulk
- `src/main/resources/static/js/core/router.js` (modify) — Add 'access-grant': '#/access-management/grant' to ROUTES and its loader; accept ?environmentId= to preselect
- `src/main/resources/static/css/features/access-management.css` (modify) — Two-column layout: steps left, preview right (fits 1440x900, preview list scrolls inside its panel)
- `src/main/resources/static/index.html` (modify) — Add grant-access.js; bump ?v=
- `src/main/resources/static/js/features/access-management.js` (modify) — Remove the old grant modal, its autocompletes (initAutocomplete line 1158 moves to grant-access.js for people search via users.search)
- `test/tests/access/grant-access.spec.ts` (new) — Two people x two environments, preview tags, grant count, success

**Steps**

1. People: typeahead on Config.API.users.search (ADMIN and ENV_ADMIN allowed) adding removable chips; 'Or a whole team' select from GET /teams showing '<name> (<n> people)' and the hint 'A team adds its members as they are today; later joiners don't get this access.'
2. Environments: search over environments the caller administers, each with a power strip (U03-T03) and 'Whole environment' or 'Only some groups' (group checkboxes from GET /environments/{id}/groups); hide 'Only some groups' when access.group-scope.enabled is off.
3. Access: three level cards (AccessLevels) and 'For' select 30 days / 7 days / 90 days / No end / Until a date (date input -> whole days from today).
4. Let them know: note (max 500, goes to the audit log) and checkbox 'Email each person what they can now use' (emailPeople).
5. Preview panel: summary '<n> people · <m> environments: <x> changes, <y> skipped' and one row per pair with tag New / Extends / Upgrades / No change and the detail sentence from the server; CTA 'Grant access (x)' or disabled 'Nothing to grant'.
6. On success toast 'Access granted to <n> people' and go back to #/access-management; on 403/400 show the server message in the panel and keep the form.

**Acceptance criteria**

- Given two people and two environments where one pair already has a higher level, when the preview loads, then it shows 3 changes and 1 'No change' and the button reads 'Grant access (3)'.
- Given the environment page link 'Who has access' for Payments Dev, when it opens this page, then Payments Dev is preselected.
- Given a viewer or user opens #/access-management/grant, then the no-access page (U02-T01) shows.

**Tests**

- test/tests/access/grant-access.spec.ts (NEW): preview tags, CTA count, apply, role guard

**Notes:** Preview requests are debounced (400 ms) and the last response wins (compare a request counter).

#### U10-T07 — Rebuild Access requests as request cards with approve-for duration and fix the Approve/Deny overlap

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** AccessRequests.dc.html, Audit finding: Access Requests Approve/Deny overlap · **Depends on:** U10-T03, U03-T02, U04-T03

AccessRequests.buildPendingRequestsPageHtml (static/js/features/access-requests.js:399) renders a table whose Approve/Deny buttons overlap (crawl audit), and approving opens showApproveModal (line 762). AccessRequests.dc.html shows one card per request, oldest first: who wants what level on which environment, the reason in quotes, part, for how long, what they have today, an inline 'Approve for' duration select, 'Approve' and 'Deny...', an environment filter, and a line linking to Approvals for start/extend requests.

**Files**

- `src/main/resources/static/js/features/access-requests.js` (modify) — Rewrite buildPendingRequestsPageHtml/bindPendingRequestsEvents (lines 399-553): cards, environment filter, oldest first, inline duration select, Approve posts ReviewAccessRequestDTO {durationDays|clearExpiry} directly; keep showApproveModal only for the 409 'already decided' refresh path
- `src/main/resources/static/css/features/access-requests.css` (modify) — Card layout, action row that wraps without overlap at all widths
- `src/main/resources/static/index.html` (modify) — Bump ?v=
- `test/tests/access/review-request.spec.ts` (modify) — New selectors; approve with a chosen duration

**Steps**

1. Header: 'Access requests', subtitle 'People asking to use environments you look after. Oldest first.', environment filter 'All (<n> waiting)' + each environment with waiting requests.
2. Line under the header: 'Requests to start or extend environments are on Approvals · <n> waiting.' using GET approvals.status (only when approvals.enabled; otherwise omit, per U02-T03).
3. Card: initials avatar, '<name> wants <level> on <env>', '<email> · asked <relative time>', quoted businessJustification, then Part / For / Has today (currentAccessLevel + currentExpiresAt via AccessLevels).
4. 'Approve for' select defaults to the requested duration and offers 7 days, 30 days, 90 days, No end; Approve sends durationDays or clearExpiry; extension requests label it '<n> more days'.
5. 'Deny...' opens the decline dialog (U10-T08); after either decision remove the card, update the sidebar badge (updatePendingBadge line 889) and toast 'Access approved' / 'Request declined'.
6. Empty state: 'No one is waiting. New requests show here.'

**Acceptance criteria**

- Given three pending requests, when the page loads, then the oldest is first and each card shows reason, part, for and has today.
- Given 'Approve for 7 days' chosen, when Approve is pressed, then POST /access-requests/{id}/approve sends durationDays=7.
- Given a 360 px wide viewport and a 1440 px one, when the card renders, then Approve and Deny do not overlap (bounding boxes do not intersect).

**Tests**

- test/tests/access/review-request.spec.ts (UPDATE): cards, duration, no overlap check via boundingBox

**Notes:** The requester-side 'Request access' page stays in U09.

#### U10-T08 — Decline a request with a reason the requester sees

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** AccessChange.dc.html, AccessRequests.dc.html · **Depends on:** U10-T07

Deny takes optional notes (ReviewAccessRequestDTO.notes, max 500) and EnvironmentAccessService.denyRequest (service/EnvironmentAccessService.java:280) stores them, but NotificationService.notifyAccessRequestDenied (line 265) only says 'has been denied' without the reason. AccessChange.dc.html's 'Decline <name>'s request' dialog requires a reason that the requester sees with the decision.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — notifyAccessRequestDenied(userId, envName, envId, reason): message 'Your request to use <env> was declined: <reason>'
- `src/main/java/com/tcgdigital/vmcontrol/service/EnvironmentAccessService.java` (modify) — denyRequest passes the reason to the notification (line ~297)
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentAccessController.java` (modify) — deny (line 376) requires non-blank notes (400 'Add a reason so the person knows what to do next')
- `src/main/resources/static/js/features/access-requests.js` (modify) — Replace showDenyModal (line 841) with the decline dialog: subtitle 'Use <env> for <n> days · "<reason>"', required Reason textarea (hint 'The person sees this with the decision'), 'Cancel' / 'Decline request'
- `src/main/resources/static/js/features/my-account.js` (modify) — Show reviewDecisionNotes under a declined request (describe(), line 848)

**Steps**

1. Make the reason required in the controller (trim, 1..500) and keep the 500 cap from ReviewAccessRequestDTO.
2. Pass it to the in-app notification and to the email (when notification.email.access-request-denied.enabled).
3. Build the dialog on U03-T02 with the textarea focused; disable 'Decline request' until text is entered.
4. Show the reason in the requester's My requests / My account history row.
5. Update API docs (@Operation) to say the reason is required.

**Acceptance criteria**

- Given a reviewer declines with 'CRM UAT is frozen until the 4.1 sign-off.', when the requester opens notifications, then the message includes that reason.
- Given a deny call with empty notes, then 400 with the message above.
- Given the dialog with an empty reason, then 'Decline request' is disabled.

**Tests**

- EnvironmentAccessControllerTest (UPDATE): deny without notes -> 400; with notes -> 200 and notification text includes the reason
- NotificationServiceTest (UPDATE): denied message carries the reason
- test/tests/access/review-request.spec.ts (UPDATE): decline flow

**Notes:** Only EnvironmentAccessController calls denyRequest today (checked: no Teams/MCP caller), so requiring a reason breaks no other client. Update the OpenAPI description.

## U11

### VM registry & environment settings — Manage

Environment admins set up environments in a master-detail registry, add many AWS VMs to a group without seeing instance IDs, manage each environment's settings on one page, and run EKS node groups on Spot from a dedicated page.

Sources: VmRegistry.dc.html, RegisterVms.dc.html, EnvSettings.dc.html, RegistryDialogs.dc.html, SpotSettings.dc.html, Audit finding: VM registry shows Create/Edit/Deactivate to users and viewers · 9 tasks · 14.5 days

#### U11-T01 — Add the 'Add VMs from AWS' backend: candidates with their status and a batch register into a group

- [ ] Done · **Type:** backend · **Estimate:** 2 d · **Risk:** high · **Sources:** RegisterVms.dc.html, Known gap: instance-ID masking · **Depends on:** U01-T01, U01-T02, U01-T03

vm-registry.js fetchEc2Instances (static/js/features/vm-registry.js:1173) lists raw instances (GET /api/ec2/instances) and matches them against GET /api/ec2/registered-ids, both exposing instance IDs; submitVm (line 1359) registers one VM per POST /environments/{id}/vms with providerVmId typed or picked. RegisterVms.dc.html picks several instances by name, shows 'Found by discovery, not in a group' / 'Already in <env>', and adds them all to a group after its current VMs.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/VmMgmtController.java` (modify) — GET /api/v1/environments/{environmentId}/vms/candidates?region&accountRef&source=EC2|RDS and POST /api/v1/environments/{environmentId}/vms/batch, both ADMIN/ENV_ADMIN with securityService.assertCanAdminister(environmentId)
- `src/main/java/com/tcgdigital/vmcontrol/dto/VmCandidateDTO.java` (new) — record(ref (opaque reference from U01-T02; the instance id only for ADMIN), name, instanceType, zone, privateIp, state, status AVAILABLE|DISCOVERED_UNGROUPED|IN_ENVIRONMENT|STALE, inEnvironmentName, note)
- `src/main/java/com/tcgdigital/vmcontrol/dto/BatchRegisterVmsDTO.java` (new) — groupId or newGroupName, region, awsAccountRef, source, items[{ref, name}] (1..50)
- `src/main/java/com/tcgdigital/vmcontrol/service/VmService.java` (modify) — listCandidates(envId, ...) joining Ec2Service.listInstances with VmRepository by provider id; registerBatch(envId, dto, actor) resolving refs server side, appending sequencePosition = max+1.. in the group, one transaction; a DISCOVERED_UNGROUPED VM is moved (moveVm line 402) instead of re-registered
- `src/main/java/com/tcgdigital/vmcontrol/service/Ec2Service.java` (modify) — Resolve an opaque ref back to the instance id for the given account/region (via U01-T02's resolver)
- `src/main/resources/static/js/config.js` (modify) — vms.candidates(envId, params), vms.batchRegister(envId)

**Steps**

1. Candidates: list instances for account+region, then look up existing VMs by provider id: in this environment's Auto-Discovered group -> DISCOVERED_UNGROUPED; in any other active group -> IN_ENVIRONMENT with that environment's display name (only names of environments the caller may view; otherwise 'another environment'); terminated/shutting-down and registered -> STALE.
2. Never return instanceId to non-admins (use U01-T02's opaque ref); name = Name tag or '(no name tag)'.
3. Batch register: validate the group belongs to the environment (or create newGroupName at the end of the start order via VmGroupService.createGroup), reject refs that are IN_ENVIRONMENT, resolve refs to instance ids server side, register each with provider AWS (or RDS) and vmType DEV, positions after the group's last VM.
4. Audit VM_REGISTERED per VM (text without instance IDs, per U01-T03) and run TagReconciliationService.reconcileNewInEnvironment after commit.
5. Return the created VmDTOs (masked by U01-T01).

**Acceptance criteria**

- Given an ENV_ADMIN, when candidates are listed, then no response field contains an 'i-' instance id.
- Given two available instances picked into 'Web' (2 VMs), when batch register runs, then they get start positions 3 and 4 in Web.
- Given a candidate already in Analytics QA, when it is sent in a batch, then 400 names it and nothing is registered.
- Given a discovered ungrouped VM picked, when added to Application, then it moves there and is no longer pending review.

**Tests**

- VmBatchRegisterIntegrationTest (NEW): positions, new group, already-registered rejection, discovered VM moved, all-or-nothing
- VmCandidatesControllerTest (NEW): masking per role, status mapping (mock Ec2Service)

**Notes:** RDS candidates reuse the existing RDS listing path (fetchRdsDatabases in vm-registry.js) behind the same endpoint with source=RDS.

#### U11-T02 — Add small registry endpoints: group start-order reorder, stop-notice audience count, and review summary across environments

- [ ] Done · **Type:** backend · **Estimate:** 1 d · **Risk:** low · **Sources:** EnvSettings.dc.html, RegistryDialogs.dc.html, VmRegistry.dc.html

Reordering groups today means editing each group's sequencePosition through PUT /groups/{id} (VmGroupController line 183), two non-atomic calls for one swap; the stop notice (EnvironmentController.notifyStop line 453) gives no count before sending, but the dialog says '9 people can use Payments Dev'; review counts exist only per environment (VmService.getReviewCounts line 388), while the registry banner says '3 VMs need a look' across all environments.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/controller/VmGroupController.java` (modify) — PUT /api/v1/environments/{environmentId}/groups/order body {groupIds:[...]} sets sequencePosition 1..n in one transaction
- `src/main/java/com/tcgdigital/vmcontrol/service/VmGroupService.java` (modify) — reorder(envId, ids, actor): ids must be exactly the active groups of the environment; re-run DependencyValidator so a group cannot start before one it waits for (400 names the pair); audit GROUP_UPDATED once
- `src/main/java/com/tcgdigital/vmcontrol/controller/EnvironmentController.java` (modify) — GET /{environmentId}/notify-stop/audience -> {count}; GET /review-summary -> [{environmentId, name, drift, pending}] for administered environments
- `src/main/java/com/tcgdigital/vmcontrol/service/NotificationService.java` (modify) — Public countEnvironmentRecipients(envId) using resolveEnvironmentRecipients (used at line 1044)
- `src/main/java/com/tcgdigital/vmcontrol/repository/VmRepository.java` (modify) — Grouped count of drift / pending per environment for a set of environment ids
- `src/main/resources/static/js/config.js` (modify) — groups.reorder(envId), environments.notifyStopAudience(envId), environments.reviewSummary

**Steps**

1. Reorder: load the environment's groups, check the id set matches, assign positions, validate dependency order with DependencyValidator, save, audit.
2. Audience: return the same recipient count notifyStopEnvironment would email (active grants with an email, env and group scope).
3. Review summary: one grouped query for drift (state drift flagged by StateSyncService) and pending (discovered, not in a group) per environment in the caller's scope (administeredEnvironmentIdsOrAll).
4. All three ADMIN/ENV_ADMIN with assertCanAdminister for the per-environment ones.

**Acceptance criteria**

- Given groups Database, Application, Web, when order [Database, Web, Application] is saved and Web waits for Application, then 400 'Web waits for Application, so it cannot start first'.
- Given 9 people with access and an email, when the audience is requested, then count=9.
- Given one drifted and two pending VMs in two environments, when review-summary is requested by their admin, then the totals are 1 and 2.

**Tests**

- VmGroupServiceTest (UPDATE): reorder happy path, wrong id set, dependency conflict
- EnvironmentControllerNotifyStopTest (NEW): audience count matches the recipients; 403 for USER
- VmRegistryReviewControllerTest (UPDATE): review-summary scope

#### U11-T03 — Rebuild the VM registry as a master-detail page: environment list left, selected environment's groups and VMs right

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** VmRegistry.dc.html, Audit finding: VM registry shows Create/Edit/Deactivate to users and viewers · **Depends on:** U11-T02, U04-T02, U04-T03, U03-T03, U05-T02, U02-T01, U02-T02

vm-registry.js renders a table of environments (renderEnvironmentsList line 172) and opens a groups modal per environment (manageGroups line 359, renderGroupsModal line 430). VmRegistry.dc.html keeps both on one screen: a searchable Active/Deactivated environment list (8 per page, each with a power strip and 'payments-dev · 3 groups · 7 VMs'), and for the selected one a group/VM tree (Size, Start order, Notes, Actions Edit/Move/Remove) with header actions Settings, Groups and order, Add VMs from AWS, Deactivate, plus a '3 VMs need a look' banner.

**Files**

- `src/main/resources/static/js/features/vm-registry.js` (modify) — Replace renderVmRegistry/renderEnvironmentsList/buildEnvironmentRow/renderGroupsModal with a master-detail layout (U04-T02); selected environment in the URL query (?env=<id>); detail loads GET /environments/{id}/groups + GET /environments/{id}/vms; keep review list (loadReviewList line 528) as a filter of the detail
- `src/main/resources/static/css/features/vm-registry.css` (modify) — Master list rows with gold active marker, tree rows (group 600 weight, VM indented), drift note in warning colour
- `src/main/resources/static/index.html` (modify) — Bump ?v=
- `test/tests/inventory/vm-registry-review.spec.ts` (modify) — New selectors for the review banner and detail
- `test/tests/inventory/vm-registry-rds.spec.ts` (modify) — New entry point to Add VMs
- `test/tests/inventory/vm-registry.spec.ts` (new) — Select environment, tree rows, actions hidden for USER/VIEWER

**Steps**

1. Header (U04-T03): 'VM registry', 'Set up environments: their groups, start order and the AWS VMs in them.', primary 'New environment' (existing Modals.showCreateEnvironment until U09 replaces it).
2. Banner from GET /environments/review-summary: '<n> VMs need a look: <d> changed outside the platform, <p> found in AWS and not in a group.' with 'Review them' selecting the first affected environment and its review filter.
3. Master list: search, Active/Deactivated toggle, 8 per page (U04-T02 pager), each row name + meta + compact power strip (U03-T03); selection keyboard-accessible (aria-current).
4. Detail: name + meta, buttons Settings (#/environments/{id}/settings, U11-T06), Groups and order (Settings section), Add VMs from AWS (U11-T04), Deactivate (opens the Settings danger section); tree of groups ('1st', '2nd · after Database') and VMs with size (U05-T02), notes (drift text such as 'Size changed in AWS to t4g.large', 'Found by discovery'), actions Edit (existing editVm), Move (U11-T05), Remove (deleteVm line 1432).
5. Large groups page their VMs 10 at a time (existing changeGroupVmPage line 413).
6. Hide every write control unless the server capability flags (U02-T02) allow; USER/VIEWER hitting #/vm-registry get the no-access page (U02-T01).

**Acceptance criteria**

- Given 21 environments, when the page opens, then 8 show with '1-8 of 21' and the first is selected with its groups and VMs on the right.
- Given a VM whose size changed in AWS, when its environment is selected, then its row shows the drift note in the warning colour.
- Given user-001 (USER), when #/vm-registry is opened, then the no-access page shows and no Create/Edit/Deactivate control renders.
- Given 1440x900, then the page has no page scrollbar.

**Tests**

- test/tests/inventory/vm-registry.spec.ts (NEW): master-detail, selection in URL, role guard
- test/tests/inventory/vm-registry-review.spec.ts (UPDATE)
- test/tests/inventory/vm-registry-rds.spec.ts (UPDATE)

#### U11-T09 — Keep every registry action in the new layout: reactivate, delete group, review queue, removed VMs, Sync EKS now

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** VmRegistry.dc.html, RegistryDialogs.dc.html, feature parity · **Depends on:** U11-T03

vm-registry.js has actions the master-detail rebuild (U11-T03) does not name: Reactivate environment (vr-reactivate-environment L237, reactivateEnvironment L337), Delete group (vr-delete-group L792, deleteGroup L936), the review queue with state filters and 'Mark as reviewed' (vr-review-state L497, loadReviewList L528, acknowledgeVm L583), removed VMs with Reactivate (vr-reactivate-vm L658, reactivateVm L638) and 'Sync EKS now' (vr-sync-eks L444, syncEksNow L392). None may be lost.

**Files**

- `src/main/resources/static/js/features/vm-registry.js` (modify) — place the existing handlers in the new layout; keep their data-action names
- `src/main/resources/static/css/features/vm-registry.css` (modify) — review filter chips, removed-VM rows
- `test/tests/inventory/vm-registry.spec.ts` (modify) — each action reachable in the new layout

**Steps**

1. Deactivated environments (the list's Deactivated filter) show 'Reactivate' in the detail header instead of Deactivate.
2. Group rows get 'Delete group' in their action menu, with the existing confirmation (only when the group has no VMs, as today).
3. 'Needs review' filter in the detail (pending review, drift, not found in AWS) using loadReviewList and the state chips; each row offers 'Mark as reviewed' (acknowledgeVm).
4. 'Show removed VMs' toggle lists soft-deleted VMs with 'Reactivate'.
5. EKS environments show 'Sync node groups now' in the detail header (syncEksNow).
6. Bump the asset stamp; update specs that clicked the old buttons.

**Acceptance criteria**

- Given a deactivated environment, when the admin selects it, then 'Reactivate' brings it back.
- Given VMs pending review or drifted, when the 'Needs review' filter is on, then they are listed and 'Mark as reviewed' clears each.
- Given a removed VM, when 'Show removed VMs' is on, then 'Reactivate' restores it to its group.
- Given an EKS environment, then 'Sync node groups now' runs the sync and says when it finished.

**Tests**

- test/tests/inventory/vm-registry.spec.ts (UPDATE): reactivate environment, delete empty group, mark as reviewed, reactivate VM, EKS sync button

**Notes:** Added in the feature-parity review (2026-10-19). Instance IDs stay hidden from non-admins in every one of these lists (U01).

#### U11-T04 — Build the 'Add VMs to <environment>' page: pick several instances by name and add them to a group

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** RegisterVms.dc.html · **Depends on:** U11-T01, U11-T03, U01-T05

The current register modal (vm-registry.js openVmForm line 963, renderEc2Instances line 1231, selectEc2Instance line 1339) registers one instance at a time and fills providerVmId into a form. RegisterVms.dc.html is a page with Servers (EC2) / Databases (RDS) tabs, account and region selects, search, a checkbox table (Name, Size, Zone, IP address, State, Note) with taken rows greyed out, 'Add to group' (with start order and 'New group...'), the hint '<n> selected · they start after the group's current VMs' and 'Add <n> VMs'.

**Files**

- `src/main/resources/static/js/features/register-vms.js` (new) — RegisterVmsPage.load(params): GET vms.candidates, client-side search and paging (10/page), selection set, POST vms.batchRegister
- `src/main/resources/static/js/core/router.js` (modify) — PARAM_ROUTES entry '#/vm-registry/{environmentId}/add' -> contentType 'register-vms'
- `src/main/resources/static/js/features/vm-registry.js` (modify) — Remove the EC2/RDS import part of openVmForm/fetchEc2Instances/renderEc2Instances/filterEc2Instances/selectEc2Instance/fetchRdsDatabases (lines 1057-1358); keep editVm for edits
- `src/main/resources/static/js/features/aws-account-options.js` (modify) — Reuse its account options for the account select; no behaviour change
- `src/main/resources/static/index.html` (modify) — Add register-vms.js; bump ?v=
- `test/tests/inventory/register-vms.spec.ts` (new) — Pick two, add to group, CTA text, taken rows disabled, no instance ids in DOM for env admin

**Steps**

1. Breadcrumb 'VM registry / <env>' and title 'Add VMs to <env>', subtitle 'Pick instances from AWS. They join a group and start in its order.'
2. Tabs EC2 / RDS (RDS tab only when the RDS probe succeeds, as probeRds line 1075 does today); account select (AwsAccountOptions) and region select default to the environment's account/region.
3. Table rows from candidates: IN_ENVIRONMENT rows disabled with note 'Already in <env>'; DISCOVERED_UNGROUPED rows note 'Found by discovery, not in a group'; STALE rows hidden behind 'Show ended instances'.
4. 'Add to group' select lists groups as '<name> · starts <ordinal>' plus 'New group...' (inline name input).
5. CTA 'Pick VMs to add' (disabled) / 'Add 1 VM' / 'Add <n> VMs'; on success toast 'Added <n> VMs to <group>' and return to the registry with the environment selected.
6. Errors: AWS credential/region failure -> 'Couldn't list instances in <region>. Check the AWS account in Settings > AWS accounts, then try again.'

**Acceptance criteria**

- Given env admin dev-user-001, when the page lists candidates, then no 'i-' instance id appears in the DOM or network responses.
- Given two instances ticked and 'Web · starts 3rd', when 'Add 2 VMs' is pressed, then one batch request is sent and the registry shows both under Web.
- Given a row 'Already in Analytics QA', then its checkbox is disabled and it cannot be selected.

**Tests**

- test/tests/inventory/register-vms.spec.ts (NEW)
- test/tests/inventory/vm-registry-rds.spec.ts (UPDATE): RDS tab on the new page

#### U11-T05 — Add the Edit group, Move VM and 'Tell everyone it's being stopped' dialogs

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** RegistryDialogs.dc.html · **Depends on:** U11-T02, U11-T03, U03-T02

Group editing uses a form with a dependency dropdown (vm-registry.js openGroupForm line 849, populateDependencyDropdown line 877) that lets you pick a loop and only fails on save; moving a VM uses openMoveVm (line 597); the stop notice is a Modals.confirm without reason or count (environments.js notifyEnvironmentMembers line 1200 posts {}). RegistryDialogs.dc.html: Edit group (name, start order, 'Waits for these groups' with '· would make a loop' disabled, description), Move <vm> (target groups with start order, 'It goes to the end of that group's order. Any VM it was set to wait for is cleared.'), and 'Tell everyone it's being stopped' with the audience count and an optional reason.

**Files**

- `src/main/resources/static/js/features/registry-dialogs.js` (new) — RegistryDialogs.editGroup(env, group, groups, onDone), moveVm(env, vm, groups, onDone), notifyStop(env) ; loop detection helper wouldMakeLoop(groupId, candidateId, dependsOn map)
- `src/main/resources/static/js/features/vm-registry.js` (modify) — Edit/Move actions call RegistryDialogs; delete openGroupForm/populateDependencyDropdown/submitGroup/openMoveVm/submitMoveVm
- `src/main/resources/static/js/features/environments.js` (modify) — notifyEnvironmentMembers (line 1200) delegates to RegistryDialogs.notifyStop
- `src/main/resources/static/index.html` (modify) — Add registry-dialogs.js; bump ?v=
- `src/test/js/group-loop.test.mjs` (new) — node tests for wouldMakeLoop
- `test/tests/inventory/registry-dialogs.spec.ts` (new) — Edit group loop disabled, move VM, notify with reason

**Steps**

1. Edit group: Name, Start order select (1st..nth), checkboxes for other groups; a group that (transitively) waits for this one is disabled with '· would make a loop'; Description; save via PUT /groups/{id} (CreateVmGroupDTO dependsOnGroupIds, sequencePosition).
2. Move VM: subtitle 'Now in <group>, start order <n>', radio list of the other groups '<name> · starts <ordinal>', the hint text, 'Move VM' -> PUT /vms/{id}/move with targetGroupId (no sequencePosition = end).
3. Notify: title 'Tell everyone it's being stopped', subtitle '<n> people can use <env>' from notify-stop/audience, optional Reason textarea (max 500), note 'Each of them gets an email and a notification in the app. Sent even when other email notices are switched off.', button 'Send to <n> people' -> POST notify-stop {reason}; toast 'Sent to <n> people'.
4. Disable 'Send' when the count is 0 and say 'No one with access has an email address.'

**Acceptance criteria**

- Given Web waits for Application, when Application is edited, then the Web checkbox is disabled with '· would make a loop'.
- Given the notify dialog with reason 'Stopping at 16:00 on Friday', when sent, then the request body contains that reason and members' notifications include it.
- Given a VM moved to Web, then it appears last in Web and its VM dependencies are cleared.

**Tests**

- src/test/js/group-loop.test.mjs (NEW)
- test/tests/inventory/registry-dialogs.spec.ts (NEW)

#### U11-T06 — Add the environment settings page with section navigation, Details and Deactivate

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** EnvSettings.dc.html · **Depends on:** U11-T03, U04-T02, U04-T03, U02-T01, U02-T02

Environment settings are scattered: edit environment is a modal from the registry (vm-registry.js editEnvironment line 301 -> Modals.showEditEnvironment), the production flag is a separate PATCH /environments/{id}/production (IdleStopController line 157) toggled from the idle panel, and deactivation is deleteEnvironment (line 319) with a plain confirm. EnvSettings.dc.html is one page '<env> settings' with a left section list (Details, Groups and start order, Running and stopping, Idle auto-stop, Budget, Who has access, Notifications, Deactivate) and a Save changes / Discard bar.

**Files**

- `src/main/resources/static/js/features/env-settings.js` (new) — EnvSettings.load({environmentId, section}): section nav, Details form, Deactivate section; dirty tracking for Save/Discard
- `src/main/resources/static/js/core/router.js` (modify) — PARAM_ROUTES '#/environments/{id}/settings' and '#/environments/{id}/settings/{section}' -> 'environment-settings' (before the environment-detail pattern)
- `src/main/resources/static/css/features/env-settings.css` (new) — Two-column settings layout, danger section
- `src/main/java/com/tcgdigital/vmcontrol/dto/EnvironmentDTO.java` (modify) — Expose isProduction and the platform cost tags (key/value list built like TagReconciliationService.buildTags) for the Details section, when not already present
- `src/main/resources/static/index.html` (modify) — Add env-settings.js and its css; bump ?v=
- `test/tests/environments/env-settings.spec.ts` (new) — Open, edit display name, save, deactivate type-to-confirm, role guard

**Steps**

1. Breadcrumb 'VM registry / <env> / Settings'; left nav items as in the mockup; Idle auto-stop, Budget and Who has access link out (idle panel section, #/budgets?scope=<env>, #/access-management/grant?environmentId=<env>).
2. Details: Display name, Short name (read-only, environment.name), Description, Team select ('<team> · <cost centre>' from GET /teams), AWS account (AwsAccountOptions), Production switch with hint 'Never stopped by idle auto-stop, and marked as production wherever it is listed.', and 'Tags on every VM · used for cost allocation' shown read-only.
3. Save changes: PUT /environments/{id} (UpdateEnvironmentDTO displayName, description, teamId, awsAccountRef) and, when production changed, PATCH /environments/{id}/production; Discard restores the loaded values; leaving with unsaved edits asks 'Discard changes?'.
4. Deactivate: text 'The environment disappears from lists and schedules and its lock is released. VMs keep running in AWS; stop them first if you want them off. An administrator can bring it back.', input 'Type <short name> to confirm', danger button 'Deactivate <env>' -> DELETE /environments/{id}; then go to #/vm-registry.
5. Only ADMIN/ENV_ADMIN with administer rights on this environment (capabilities from U02-T02); others get the no-access page.

**Acceptance criteria**

- Given an env admin edits the description and presses Save changes, then PUT is sent once and a toast says 'Settings saved'.
- Given the Deactivate section, when the typed text does not match the short name, then the button stays disabled.
- Given viewer user-002 opens #/environments/<id>/settings, then the no-access page shows.

**Tests**

- test/tests/environments/env-settings.spec.ts (NEW)
- test/tests/navigation/route-params.spec.ts (UPDATE): settings routes

**Notes:** Custom tags ('Add a tag') have no backend concept today (TagReconciliationService applies fixed tcg: tags); this task shows the platform tags read-only. Adding custom tags would need a migration (next free V number) and is left to a follow-up decision.

#### U11-T07 — Fill the settings sections Groups and start order, Running and stopping, Notifications; move the panels off the environment page

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** EnvSettings.dc.html · **Depends on:** U11-T06, U11-T05, U11-T02, U02-T03

The environment page appends many panels under 'Cost & settings' (environments.js buildDetailHtml lines 746-753: env-budget-panel, env-anomaly-panel, env-idle-panel, env-spot-panel, env-chat-panel, env-webhook-panel) and the lease policy is a modal (leases.js showPolicyDialog). EnvSettings.dc.html puts them in sections: groups list with up/down and 'Add a group', leases (stop when the lease ends, default length, longest without approval) with idle and schedule summary lines, and Notifications (email everyone, Teams channels with 'Send a test', webhooks state).

**Files**

- `src/main/resources/static/js/features/env-settings.js` (modify) — Sections groups (GET /groups/start-order, up/down -> PUT groups/order, Edit via RegistryDialogs.editGroup, Delete), leases (inline form from GET/PUT lease-policy), idle (IdleStop.renderPanel into the section), notify (ChatChannels.renderEnvPanel, WebhooksPanel.renderEnvPanel, 'Email everyone with access...' -> RegistryDialogs.notifyStop)
- `src/main/resources/static/js/features/leases.js` (modify) — Extract the policy form markup of showPolicyDialog (line ~233) into policyFormHtml(policy) so the section and the dialog share it
- `src/main/resources/static/js/features/environments.js` (modify) — Remove env-idle-panel, env-spot-panel, env-chat-panel, env-webhook-panel from buildDetailHtml and their render calls in bindDetailEvents; add a 'Settings' link for admins
- `test/tests/environments/idle-stop-panel.spec.ts` (modify) — Open the panel from the settings page
- `test/tests/environments/chat-channels.spec.ts` (modify) — Settings > Notifications
- `test/tests/environments/webhooks.spec.ts` (modify) — Settings > Notifications; switched-off message
- `test/tests/environments/lease-banner.spec.ts` (modify) — Policy now edited in Settings > Running and stopping

**Steps**

1. Groups: rows '<ordinal> · <name>', '<n> VMs', 'Starts first' / 'Waits for <group>'; up/down buttons (aria-label 'Move <group> up') call PUT groups/order and show the server's dependency error inline; 'Add a group' opens RegistryDialogs.editGroup in create mode; hint 'Groups start top to bottom and stop bottom to top. A group can also wait for specific groups.'
2. Running and stopping: lease switch 'Stop when the lease ends', Default length and 'Longest without approval' selects (Leases.runForOptions); hidden with a one-line note when lease.enabled is off (no request, per U02-T03). Summary lines 'Idle auto-stop: <dry run|on|off>, after <n> min idle' and 'Schedule: <rule>, <summary>' linking to the idle section and #/automation-rules.
3. Notifications: 'Email everyone with access...' button, Teams channels list with 'Send a test', Edit, 'Add a Teams channel'; Webhooks: when switched off show 'Webhooks are switched off for the platform. An administrator can switch them on in Settings > Integrations.' without calling the API.
4. Spot: for EKS environments add a 'Spot' nav item linking to #/environments/{id}/settings/spot (U11-T08).
5. Remove the moved panels from the environment page; budget and anomaly panels stay where U07 puts them (coordinate).

**Acceptance criteria**

- Given Database, Application, Web, when Web is moved up, then the order saves with one request and the list re-renders.
- Given lease.enabled=false, when Running and stopping opens, then no lease-policy request is made.
- Given the environment page after this task, then idle, spot, chat and webhook panels are no longer rendered there.

**Tests**

- test/tests/environments/env-settings.spec.ts (UPDATE): groups reorder, leases section, notifications
- test/tests/environments/idle-stop-panel.spec.ts, chat-channels.spec.ts, webhooks.spec.ts, lease-banner.spec.ts (UPDATE)

**Notes:** Coordinate with U07 (environment page): U07 should link to Settings instead of rendering these panels. If U07 lands first, step 5 only deletes leftovers.

#### U11-T08 — Build the Spot page for EKS node groups with per-group savings and the interruption history

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** SpotSettings.dc.html · **Depends on:** U11-T06, U03-T02

spot.js (SpotPanel, 153 lines) renders a small panel on the environment page from GET /spot-summary; SpotSummaryDTO.SpotVm (dto/SpotSummaryDTO.java:24) has no node size, no per-group saving estimate and only an interruption count. SpotSettings.dc.html is a settings sub-page: policy radio (Not allowed / Allowed for the node groups you choose, with the acknowledgement checkbox, not offered for production), a table (Node group, Runs on, On-demand standby, Saves a month, Action, Keep off Spot), how it works, interruptions in 30 days with standby run time, and money saved this month.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/SpotSummaryDTO.java` (modify) — SpotVm gains instanceType, nodeCount, estimatedMonthlySavings; summary gains recentInterruptions [{vmName, at, standbyMinutes}]
- `src/main/java/com/tcgdigital/vmcontrol/service/SpotSummaryService.java` (modify) — Fill the new fields: size from the VM inventory, savings from SpotOpportunityService for not-yet-Spot groups, interruptions from VmStateHistory with source SpotInterruptionRecorder.CHANGE_SOURCE (line ~99) in the last 30 days
- `src/main/java/com/tcgdigital/vmcontrol/service/SpotOpportunityService.java` (modify) — forEnvironment(environmentId) next to list() (line 72)
- `src/main/resources/static/js/features/spot.js` (modify) — Add SpotPage.load({environmentId}) rendering the full page; keep convert/revert/exclude/setPolicy calls (lines 98-140)
- `src/main/resources/static/js/core/router.js` (modify) — '#/environments/{id}/settings/spot' handled by environment-settings with section 'spot' delegating to SpotPage
- `test/tests/environments/spot-panel.spec.ts` (modify) — Now tests the Spot page
- `src/test/java/com/tcgdigital/vmcontrol/controller/SpotControllerTest.java` (modify) — New summary fields

**Steps**

1. Extend the DTO and service; keep 404 while spot.enabled is off (the page then shows 'Spot is switched off for the platform. An administrator can switch it on in Settings.' without retrying).
2. Page header 'Spot', text 'Run EKS node groups on spare AWS capacity for up to 70% less. AWS can take the nodes back at short notice. For EKS environments only.'
3. Policy radios: 'Not allowed — every node group stays on on-demand capacity' and 'Allowed for the node groups you choose' (disabled with 'Not offered for production environments' when production); the acknowledgement checkbox is required before Save (PUT spot-policy).
4. Table: name, '<n> x <type>', Runs on chip (Spot / On-demand / On-demand · kept off Spot), standby ('Ready at 0 nodes' / '—'), saves ('$61' or 'about $96' for estimates), action 'Move to Spot' / 'Back to on-demand' (danger) / 'Allow Spot', and 'Keep off Spot' (spot-excluded); actions only when conversionEnabled and canAdminister.
5. Side column: how the standby works, fallback text with spot.fallback.grace-minutes, interruption list '<group> · <day time> — standby ran <n> min', 'Saved $<x> on Spot this month.'
6. Non-EKS environments do not show the Spot nav item; opening the URL for one shows 'Spot is for EKS environments only.'

**Acceptance criteria**

- Given an EKS environment with app-nodes on Spot, when the page opens, then app-nodes shows 'Spot', 'Ready at 0 nodes' and its savings, and the interruptions list shows the last 30 days.
- Given a production environment, then 'Allowed' is disabled with 'Not offered for production environments'.
- Given 'Move to Spot' confirmed, then POST convert-to-spot is sent and the row shows the converting state.

**Tests**

- SpotControllerTest (UPDATE): new fields, 404 when off
- SpotSummaryServiceTest (NEW or UPDATE): savings and interruptions mapping
- test/tests/environments/spot-panel.spec.ts (UPDATE): page flow

**Notes:** No new AWS automation is added; conversion stays behind spot.eks.conversion.enabled (default off).

## U12

### Automation, approval policies, budgets, audit log — Manage

Environment admins see and edit weekly schedules on a week view, set access-triggered rules, read holiday calendars and recent runs, manage approval policies and budgets in plain sentences, and search an audit log that reads like what happened.

Sources: AutomationRules.dc.html, RuleEditor.dc.html, AutomationTabs.dc.html, ApprovalPolicies.dc.html, Budgets.dc.html, CostDialogs.dc.html, AuditLog.dc.html · 8 tasks · 13 days

#### U12-T01 — Add action categories to the audit log search and stats

- [ ] Done · **Type:** backend · **Estimate:** 1 d · **Risk:** low · **Sources:** AuditLog.dc.html, AutomationTabs.dc.html

GET /api/v1/audit/logs and /logs/stats (controller/AuditController.java:48, 84) filter by one AuditAction; AuditFilter (service/AuditFilter.java) carries a single action string. The audit log mockup filters by 'Start and stop', 'Locks', 'Access', 'Settings', and the Recent runs tab needs all AUTOMATION_RULE_TRIGGERED/SKIPPED/FAILED at once.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/model/AuditActionCategory.java` (new) — enum OPERATIONS, LOCKS, ACCESS, SETTINGS, AUTOMATION, COST with the AuditAction set of each (e.g. OPERATIONS = VM_START_*, VM_STOP_*, OPERATION_*; LOCKS = LOCK_*, LEASE_*; ACCESS = ACCESS_*, USER_*; SETTINGS = ENVIRONMENT_*, GROUP_*, VM_REGISTERED/UPDATED/DELETED, SPOT_POLICY_CHANGED, APPROVAL_POLICY_*, WEBHOOK_*; AUTOMATION = AUTOMATION_RULE_*, HOLIDAY_CALENDAR_CHANGED; COST = BUDGET_*)
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditFilter.java` (modify) — action -> Collection<String> actions (null = any)
- `src/main/java/com/tcgdigital/vmcontrol/repository/AuditLogRepository.java` (modify) — searchLogs and the stats queries use '(:anyAction = true OR a.action IN :actions)'
- `src/main/java/com/tcgdigital/vmcontrol/controller/AuditController.java` (modify) — New @RequestParam category on /logs and /logs/stats; action and category combine (intersection); GET /audit/categories lists categories with labels
- `src/main/java/com/tcgdigital/vmcontrol/service/AuditService.java` (modify) — search/stats (lines 202-215) pass the collection

**Steps**

1. Define the category enum with a static Set<AuditAction> per category and a unit test that every AuditAction is in at most one category.
2. Change AuditFilter and the repository queries; keep the existing single action param working.
3. Expose category on both endpoints; unknown category -> 400.
4. Make sure the CSV export path (all-logs.js exportAuditLogs) can pass category through filterParams.

**Acceptance criteria**

- Given category=AUTOMATION, when /audit/logs is called, then only AUTOMATION_RULE_* and HOLIDAY_CALENDAR_CHANGED rows return.
- Given category=ACCESS and success=false, then /logs/stats totals match the filtered rows.
- Given an ENV_ADMIN with assigned scope, then category filtering never widens the environment scope.

**Tests**

- AuditFilterIntegrationTest (UPDATE): category filter and combination with action
- AuditActionCategoryTest (NEW): every action in at most one category

#### U12-T02 — Rebuild the Automation rules page: tabs, counts and schedules shown on a week strip

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** AutomationRules.dc.html · **Depends on:** U04-T02, U04-T03, U03-T02

automation-rules.js renders a stat row, filters and a rules table (renderRulesTable line 303) with the schedule as text (triggerSummary line 433); the Holiday calendars tab is admin-only (render line 118). AutomationRules.dc.html shows tabs Schedules / When access is granted / Holiday calendars / Recent runs, counts ('4 rules on', 'cover 3 environments', '1 run skipped last week: environment was locked'), and each schedule as a row with a Mon-Sun 0-24 h strip (navy bars, gold where a run was skipped), summary, 'next <action> <time>' and Edit / Skip next stop / Delete.

**Files**

- `src/main/resources/static/js/ui/week-strip.js` (new) — WeekStrip.bars(windows, timezone, viewerTz) -> per-day [{fromHour, toHour, skipped}] and WeekStrip.html(days, opts) with aria-label summary
- `src/main/resources/static/js/features/automation-rules.js` (modify) — render (line 118): four tabs for ADMIN and ENV_ADMIN, tab in URL query (?tab=); Schedules tab lists SCHEDULE rules 10/page with WeekStrip; stat cards rewritten (renderStatCards line 271); actions keep Skip next stop (line 463) and delete
- `src/main/resources/static/css/features/automation-rules.css` (modify) — Week strip and rule rows
- `src/main/resources/static/index.html` (modify) — Add week-strip.js; bump ?v=
- `src/test/js/week-strip.test.mjs` (new) — Windows crossing midnight, UTC rule shown in viewer time, skipped day
- `test/tests/automation/automation-rules-ui.spec.ts` (modify) — New tabs and rows

**Steps**

1. Header 'Automation rules', subtitle 'Schedules that start and stop environments. A rule never breaks a lock: it retries, then skips and tells you.', primary 'New schedule' -> #/automation-rules/new (U12-T03).
2. Counts: enabled rules, distinct environments covered, runs skipped in the last 7 days because LOCKED (from lastRunReason, or AUTOMATION category audit if U12-T05 has landed).
3. Schedule row: name, environment, scheduleSummary + timezone + holiday text, ' · next ' + nextRunAction/nextRunAt in viewer time ('Stop today 20:00'), WeekStrip with 00/06/12/18/24 ticks; when the rule's timezone differs from the viewer's, append '(<local> here)' as in the mockup.
4. Mark a day gold when the last run on that day was SKIPPED (lastRunAt/lastRunReason) and add its reason to the summary ('skipped Wed: locked by <name>').
5. The other three tabs mount U12-T04 / U12-T05 content (placeholders until they land; hidden, not empty).
6. Disabled rules show a muted strip and 'Off' chip; the enable switch has an accessible name ('Turn <rule> on').

**Acceptance criteria**

- Given 'Office hours' Mon-Fri 08:00-20:00, then the strip shows five bars from 33% to 83% width and none on Sat/Sun.
- Given env admin dev-user-001, then all four tabs are visible.
- Given 1440x900 with 12 schedules, then 10 show with a pager and no page scrollbar.

**Tests**

- src/test/js/week-strip.test.mjs (NEW)
- test/tests/automation/automation-rules-ui.spec.ts (UPDATE)
- test/tests/automation/rule-overrides.spec.ts (UPDATE selectors for Skip next stop)

#### U12-T03 — Replace the rule modal with the schedule editor page and its week preview

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** RuleEditor.dc.html · **Depends on:** U12-T02

Schedules are created in a large modal (automation-rules.js openRuleModal line 546, buildModalHtml line 560, windowsHtml line 753) with free-form windows. RuleEditor.dc.html is a page: Name, Environment, Days as seven pill toggles, Start at / Stop at, Time zone, On holidays (Don't start on <calendar> holidays / Run as usual / Don't start or stop), a 'This week' preview with 'Runs <n> hours a week instead of 168 · first start <date>', and 'Suggest from last month's usage'.

**Files**

- `src/main/resources/static/js/features/rule-editor.js` (new) — RuleEditor.load({ruleId?}): form state -> AutomationRuleRequestDTO windows; preview via POST /automation-rules/preview (refreshPreview logic moved from automation-rules.js line 950); suggestion via GET /automation-rules/suggestion (loadSuggestion line 903); overrides list (loadOverrides line 978) on edit
- `src/main/resources/static/js/core/router.js` (modify) — ROUTES 'automation-rule-new': '#/automation-rules/new'; PARAM_ROUTES '#/automation-rules/{ruleId}' -> 'automation-rule-edit'
- `src/main/resources/static/js/features/automation-rules.js` (modify) — Delete openRuleModal/buildModalHtml/windowsHtml/collectWindows/bindModalEvents/submitForm for SCHEDULE rules; Edit navigates to the editor
- `src/main/resources/static/index.html` (modify) — Add rule-editor.js; bump ?v=
- `test/tests/automation/schedule-windows.spec.ts` (modify) — Use the editor page
- `test/tests/automation/schedule-suggestion.spec.ts` (modify) — Suggest button on the page
- `test/tests/automation/next-runs.spec.ts` (modify) — First start text

**Steps**

1. Days: seven buttons with aria-pressed; Start at / Stop at time inputs; the same times apply to every chosen day (one window per day). Rules with different times per day (existing multi-window rules) open in an 'Advanced: different times per day' list that keeps the current windows editor.
2. Time zone select (browserTimezone() default, line 800), Environment select limited to environments the caller administers; group scope optional ('Only some groups').
3. On holidays radios: calendar picked + SKIP_START, no calendar ('Run as usual'), calendar + SKIP_ALL; calendar select from GET /holiday-calendars.
4. Preview: WeekStrip (U12-T02) of the form state, hours per week computed client side, first start from the /preview response (nextRuns[0]).
5. 'Suggest from last month's usage' fills days and times from /suggestion and says what it used ('Based on 23 starts in September').
6. Save schedule -> POST or PUT /automation-rules; server validation messages show next to the field; Cancel returns to #/automation-rules?tab=schedules.

**Acceptance criteria**

- Given Mon-Fri 08:00-20:00 Asia/Kolkata, then the preview reads 'Runs 60 hours a week instead of 168'.
- Given 'Don't start or stop on India public holidays', when saved, then the request has holidayCalendarId and holidayBehavior=SKIP_ALL.
- Given an existing rule with different Saturday hours, when opened, then the advanced list shows both windows and saving keeps them.

**Tests**

- test/tests/automation/schedule-windows.spec.ts (UPDATE)
- test/tests/automation/schedule-suggestion.spec.ts (UPDATE)
- test/tests/automation/next-runs.spec.ts (UPDATE)
- test/tests/automation/rule-editor.spec.ts (NEW): create, edit, holiday options

#### U12-T04 — Build the 'When access is granted' tab and editor, and open Holiday calendars to environment admins (read only)

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** AutomationTabs.dc.html · **Depends on:** U12-T02

ACCESS_GRANT rules (AutomationTriggerType.ACCESS_GRANT with AccessGrantMode LOCK_ACQUIRE / ACCESS_APPROVED) share the generic rule modal today, and the Holiday calendars tab is rendered only for Auth.isAdmin() (automation-rules.js line 131) although GET /holiday-calendars allows ENV_ADMIN. AutomationTabs.dc.html shows an access-rule form (Name, Applies to environment/group, Start it when: someone takes the lock / an access request is approved, 'Do nothing if it's already running') and a calendars master-detail with 'Used by <n> schedules', dates list, Import CSV / Add date / Remove (admin only) and the import result line.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/HolidayCalendarDTOs.java` (modify) — CalendarDTO gains usedByRuleCount
- `src/main/java/com/tcgdigital/vmcontrol/service/HolidayCalendarService.java` (modify) — Count enabled rules per calendarId (one grouped query on AutomationRule.holidayCalendarId)
- `src/main/resources/static/js/features/automation-rules.js` (modify) — Access tab: list ACCESS_GRANT rules with a plain sentence ('Starts <env> when someone takes the lock, in their name') and an inline form panel for create/edit
- `src/main/resources/static/js/features/holiday-calendars.js` (modify) — draw/detailHtml (lines 64-138): master-detail list with count and next date; write buttons only for ADMIN; importResultHtml (line 139) wording 'Imported: <a> added, <d> already there, <n> line skipped (row 7: "31-02-2026" is not a date).'
- `test/tests/automation/holiday-calendars.spec.ts` (modify) — Env admin sees calendars read only; admin can import
- `test/tests/automation/access-rules.spec.ts` (new) — Create an access-triggered rule

**Steps**

1. Access rule form: Name, Applies to (Environment / Group radio + select), Start it when (LOCK_ACQUIRE 'Someone takes the lock' — 'Started in their name'; ACCESS_APPROVED 'An access request is approved' — 'Started in your name, as the rule's owner'), checkbox skipIfAlreadyInTargetState, note 'These rules only start. The lease or a schedule stops it.'; POST/PUT /automation-rules with triggerType ACCESS_GRANT.
2. Calendars: list (name, '<n> dates · next <date>'), detail with 'Used by <n> schedules', dates table (date, day, label, past dates muted), Import CSV / Add date / Remove / '+ New calendar' only for ADMIN.
3. Explanatory line under the dates: 'On these dates a schedule either skips its start only (stops still run, the usual choice) or skips everything. Each schedule chooses; skipped runs show "holiday" in Recent runs.'
4. Add usedByRuleCount on the server and show it.

**Acceptance criteria**

- Given env admin dev-user-001, when the Holiday calendars tab opens, then calendars and dates show and no Import/Add/Remove control is present.
- Given a calendar used by 9 enabled rules, then the detail says 'Used by 9 schedules'.
- Given an access rule with 'Someone takes the lock', when saved, then accessGrantMode=LOCK_ACQUIRE is sent.

**Tests**

- HolidayCalendarServiceTest (UPDATE): usedByRuleCount
- test/tests/automation/holiday-calendars.spec.ts (UPDATE)
- test/tests/automation/access-rules.spec.ts (NEW)

#### U12-T05 — Add the Recent runs tab from the automation audit trail

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** AutomationTabs.dc.html, AutomationRules.dc.html · **Depends on:** U12-T01, U12-T02

Rule outcomes are only on the rule (lastRunAt, lastRunStatus, lastRunReason in model/AutomationRule.java) and in a small activity feed (automation-rules.js renderActivityFeed line 378); AutomationRuleService already audits AUTOMATION_RULE_TRIGGERED / SKIPPED / FAILED (lines 474-617). The mockup's Recent runs tab lists every run with its result, including 'holiday' and 'locked by <name>' skips.

**Files**

- `src/main/resources/static/js/features/automation-rules.js` (modify) — Recent runs tab: GET /audit/logs?category=AUTOMATION&from=<30 days> paged 10, filter by environment and result; replaces renderActivityFeed
- `src/main/resources/static/js/core/audit-text.js` (modify) — Use the U12-T08 sentence helper for run rows (if T08 lands later, add the AUTOMATION sentences here first)
- `test/tests/automation/recent-runs.spec.ts` (new) — Tab lists runs with results and pages

**Steps**

1. Columns: When, Rule, Environment, Result (Started / Stopped / Skipped / Failed chip), Detail (reason text: 'environment was locked by <name>', 'holiday', 'nothing to do').
2. Filters: environment, result (Any / Skipped / Failed); last 30 days.
3. Clicking a rule name opens the editor (U12-T03).
4. Empty state: 'No runs in the last 30 days.'

**Acceptance criteria**

- Given a schedule skipped because the environment was locked, then a row 'Skipped' shows the lock holder's name.
- Given 25 runs, then 10 show with a pager.

**Tests**

- test/tests/automation/recent-runs.spec.ts (NEW)

**Notes:** No new table: the audit log is the run history. If audit retention (retention.*) purges older rows, the tab says 'Kept for <n> days'.

#### U12-T06 — Rebuild Approval policies as plain-sentence cards with a 'Check a request' panel and an approver picker for environment admins

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** ApprovalPolicies.dc.html · **Depends on:** U04-T03, U03-T02, U02-T03

approval-policies.js draws a list with condition fragments (conditions() line 89) and its form's approver options come from users.list (load line 59), which is ADMIN only, so environment admins cannot pick approvers by name. ApprovalPolicies.dc.html shows each policy as a sentence: name, scope, 'Needs approval when <condition>', 'Decided by any one of / both <names>', Edit / Delete, and a 'Check a request' side panel ('See which policy a start would meet, before anyone asks.').

**Files**

- `src/main/resources/static/js/features/approval-policies.js` (modify) — draw (line 98) -> cards; conditions() -> sentence ('a run or extension is longer than 8 hours', 'any VM matches r6i.4xlarge*', 'the estimated cost is over $50'); openForm (line 126) uses a users.search typeahead for approvers and ANY/ALL wording; dryRun (line 205) result 'Needs approval: <policy> · 24 h is longer than 8 h. Any one of <names>.' or 'No approval needed.'
- `src/main/resources/static/css/features/approval-policies.css` (new) — Cards + side panel layout
- `src/main/java/com/tcgdigital/vmcontrol/dto/ApprovalPolicyDTO.java` (modify) — Approver display names in the DTO so cards need no user list
- `src/main/resources/static/index.html` (modify) — Add css; bump ?v=
- `test/tests/operations/approvals.spec.ts` (modify) — Policy page selectors
- `test/tests/automation/approval-policies.spec.ts` (new) — Env admin creates a policy with an approver picked by search; check a request

**Steps**

1. Header 'Approval policies', 'When a start or an extension needs someone's yes before it runs.', 'New policy'.
2. Card per policy: name, scope ('All environments you look after' when admin-wide, environment or '<team> team'), 'Needs approval when ' + joined conditions (or), 'Decided by any one of X or Y' (ANY) / 'both X and Y' (ALL).
3. Form: environment or team scope, condition fields (max run hours, instance type patterns, min vCPU, max estimated cost) with plain labels, approvers via users.search (ADMIN/ENV_ADMIN allowed), mode ANY/ALL.
4. 'Check a request' panel: environment + hours, POST /approval-policies/evaluate, result sentence.
5. When approvals.enabled is off, show 'Approvals are switched off for the platform. Policies are kept but not applied.' (U02-T03 capability) above the list.

**Acceptance criteria**

- Given a policy maxRuntimeHours=8 with two ANY approvers, then its card reads 'Needs approval when a run or extension is longer than 8 hours' and 'Decided by any one of <A> or <B>'.
- Given env admin dev-user-001, when picking an approver by typing a name, then matching users appear (no 403).
- Given Payments Dev and 24 hours checked, then the panel names the policy that applies.

**Tests**

- test/tests/automation/approval-policies.spec.ts (NEW)
- ApprovalPolicyControllerIntegrationTest (UPDATE): approver display names in DTO

**Notes:** The mockup's 'team would pass 100% of its monthly hours' condition has no backend equivalent; this task maps team-scoped policies to the existing conditions and does not add a new one.

#### U12-T07 — Rebuild Budgets with month totals and forecast bars, and the New budget dialog

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** low · **Sources:** Budgets.dc.html, CostDialogs.dc.html · **Depends on:** U04-T03, U03-T02, U02-T03

budgets.js drawPage (line 100) renders a table and showForm (line 219) a generic form. Budgets.dc.html shows 'Spent so far' and 'Forecast for the month' totals with 'October · day 19 of 31', one row per budget with a spend bar, a gold forecast tick, a chip (On track / Forecast over / 96% reached) and the action in words. CostDialogs.dc.html's New budget dialog: For an environment / a team, the scope select ('You can't move a budget to something else later.'), Monthly amount, up to 4 alert thresholds as removable chips, 'Also alert when the forecast says it will go over', and three 'When the budget is reached' cards (stop not offered for team budgets).

**Files**

- `src/main/resources/static/js/features/budgets.js` (modify) — drawPage -> totals + rows (bar() line 37 gains the forecast tick and an aria-label '<p>% spent, forecast <f>%'); showForm -> new dialog; keep the 'Waiting for approval' actions panel (actionRow line 160)
- `src/main/resources/static/css/features/budgets.css` (modify) — Bar with tick, chips, dialog threshold chips
- `src/main/resources/static/index.html` (modify) — Bump ?v=
- `test/tests/cost/budgets.spec.ts` (modify) — New layout and dialog

**Steps**

1. Totals from the list: sum spendToDate and forecastMonthEnd over environment budgets (team budgets overlap them; show team totals separately or exclude, and say which).
2. Row: name, ' · environment' or ' · team · <n> environments', chip by state (percentUsed >= 100 -> '<p>% reached' red; forecastPercent > 100 -> 'Forecast over' amber; else 'On track' green), '$spent of $limit · forecast $f', action line ('Alerts only', 'When reached, an owner approves freezing starts', 'When reached, an owner approves stopping it', 'Alerts only (team budgets cannot freeze or stop)'), Edit / Delete when canEdit (line 153).
3. Dialog: segmented For (team option only when GET /budgets/config returns teams), scope select disabled on edit with the hint, amount with $ prefix, threshold chips (add up to 4, 1..200, no duplicates — mirror BudgetService.validThresholds), forecast checkbox, three action cards; STOP_ENVIRONMENT and FREEZE_STARTS disabled for TEAM with the reason text.
4. 'Create budget' / 'Save budget'; server messages ('This environment already has a budget') shown in the dialog.
5. When budgets.actions.enabled is off, the freeze/stop cards say 'Switched off by an administrator' and are disabled (U02-T03 capability).

**Acceptance criteria**

- Given Analytics QA spent 122 of 150 with forecast 205, then the chip reads 'Forecast over' and the tick sits at the right end (capped at 100%).
- Given the dialog with 4 thresholds, then the add control is disabled and shows '4 of 4'.
- Given 'A team' chosen, then 'Freeze new starts' and 'Stop the environment' are disabled with the reason.

**Tests**

- test/tests/cost/budgets.spec.ts (UPDATE)
- BudgetServiceTest (no change expected; run to confirm)

**Notes:** The 'Month for finance' half of CostDialogs.dc.html belongs to U15 (cost pages).

#### U12-T08 — Rebuild the Audit log page with summary counts, category filters and sentence rows

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** AuditLog.dc.html · **Depends on:** U12-T01, U01-T03, U04-T02, U04-T03

all-logs.js (605 lines) shows raw action names via formatActionName (line 215) with details text, a stats row (buildStatsHtml line 203) and an Export button. AuditLog.dc.html reads like what happened: counts ('230 actions in 7 days', '99% succeeded', '2 failed', 'busiest: CRM UAT'), filters (range presets + Dates..., person, environment, All actions / Start and stop / Locks / Access / Settings, Any result / Failed only), and rows When / Who / What / Environment / Result where Who can be 'Schedule "Nightly batch"' and What is 'Started 5 VMs' or 'Granted Neha Gupta operator access, 7 days' with the AWS error as a note.

**Files**

- `src/main/resources/static/js/core/audit-text.js` (new) — AuditText.who(log) and AuditText.what(log): sentence per AuditAction family from action, targetName, oldValue/newValue, details; fallback to actionDisplay
- `src/main/resources/static/js/features/all-logs.js` (modify) — buildAllLogsHtml (line 242) and buildAuditLogRow (line 345) -> new header, stats, filters (category from GET /audit/categories), 10 per page, Export CSV kept (exportAuditLogs line 485) with a 'what' column using AuditText
- `src/main/resources/static/css/features/all-logs.css` (modify) — Stats row, result chips, failure note
- `src/main/resources/static/index.html` (modify) — Add audit-text.js; bump ?v=
- `src/test/js/audit-text.test.mjs` (new) — Sentences for operations, locks, access, automation, settings; unknown action fallback
- `test/tests/security/all-logs.spec.ts` (modify) — New filters and rows

**Steps**

1. Header 'Audit log', 'Everything anyone did in the environments you look after.', 'Export CSV'.
2. Stats from /audit/logs/stats (total, successRate, failures, topEnvironment) labelled as in the mockup for the selected range.
3. Filters: range (Last 7 days default, 24 hours, 30 days, Dates... opening from/to inputs), person (users.search typeahead for ENV_ADMIN, full list for ADMIN as today), environment, category, result; every change reloads page 0.
4. Rows: when (relative day + time, full timestamp in title), who (person; for AUTOMATION_RULE_* 'Schedule "<rule>"'), what (AuditText sentence) with errorMessage as a second line note on failures, environment, Done / Failed chip.
5. Text never shows instance IDs to non-admins: the server masks (U01-T03); the helper must not re-add targetId for VMs.
6. Pager 10 per page with '1-9 of 230'.

**Acceptance criteria**

- Given an ACCESS_GRANTED row for Neha with 7 days, then What reads 'Granted Neha Gupta operator access, 7 days'.
- Given category 'Locks' and 'Failed only', then the request carries category=LOCKS&success=false and the stats match.
- Given env admin dev-user-001, then no row text contains an 'i-' instance id.

**Tests**

- src/test/js/audit-text.test.mjs (NEW)
- test/tests/security/all-logs.spec.ts (UPDATE)

**Notes:** U09 owns 'My activity'; reuse AuditText there if helpful.

## U13

### People: users, bulk onboarding, teams — Admin

Administrators add many people at once with role, team, department, project code and access in one step, manage each person through three plain dialogs, and run teams (members, Entra group, environments, monthly limits) from a list and a detail page.

Sources: Users.dc.html, AddUsers.dc.html, UserDialogs.dc.html, Teams.dc.html, TeamDetail.dc.html, Known gap: department + project code (migration), Known gap: bulk onboarding endpoint · 9 tasks · 12 days

#### U13-T01 — Add department and project code to users (migration, entity, DTOs, directory)

- [ ] Done · **Type:** backend · **Estimate:** 1 d · **Risk:** low · **Sources:** AddUsers.dc.html, BRIEF: Admin bulk onboarding

User (model/User.java) has email, displayName and companyName only, and UserDTO.fromEntity exposes neither department nor project code; the Add people screen collects both per person and the directory search (GraphDirectoryService SELECT_FIELDS = id,displayName,mail,userPrincipalName) cannot prefill department.

**Files**

- `src/main/resources/db/migration/V93__add_user_department_project_code.sql` (new) — ALTER TABLE app_user ADD COLUMN department VARCHAR(100) NULL, ADD COLUMN project_code VARCHAR(50) NULL; use the next free V number if V93 is taken by a foundation task
- `src/main/java/com/tcgdigital/vmcontrol/model/User.java` (modify) — department and projectCode fields with @Column(name=...) next to companyName (line ~36)
- `src/main/java/com/tcgdigital/vmcontrol/dto/UserDTO.java` (modify) — department, projectCode fields filled in fromEntity (line ~45)
- `src/main/java/com/tcgdigital/vmcontrol/dto/OnboardUserDTO.java` (modify) — optional department (@Size 100) and projectCode (@Size 50, pattern [A-Za-z0-9._-]+)
- `src/main/java/com/tcgdigital/vmcontrol/service/UserService.java` (modify) — createOnboardedUser(...) takes department/projectCode; new updateProfile(userId, department, projectCode, actorId) with audit entry
- `src/main/java/com/tcgdigital/vmcontrol/service/UserOnboardingService.java` (modify) — onboard() passes department (body value, else directory department) and projectCode
- `src/main/java/com/tcgdigital/vmcontrol/controller/UserController.java` (modify) — PATCH /api/v1/users/{userId}/profile (ADMIN) body {department, projectCode}
- `src/main/java/com/tcgdigital/vmcontrol/service/GraphDirectoryService.java` (modify) — SELECT_FIELDS adds department; map it into DirectoryUserDTO
- `src/main/java/com/tcgdigital/vmcontrol/dto/DirectoryUserDTO.java` (modify) — add String department component

**Steps**

1. Add the migration (next free V number) with two nullable columns on app_user; never edit an existing migration.
2. Map the columns on User and add them to UserDTO.fromEntity.
3. Extend OnboardUserDTO and UserService.createOnboardedUser; trim blank values to null.
4. Add UserService.updateProfile + PATCH /users/{id}/profile (hasRole ADMIN), audited as USER_PROFILE_UPDATED with old/new values.
5. Add department to the Graph $select and DirectoryUserDTO so the directory picker can prefill it; the body value wins over the directory value.
6. Keep reset-test-data.sql working (nullable columns, no seed change needed).

**Acceptance criteria**

- Given an admin, when POST /api/v1/users with department 'Engineering' and projectCode 'DLK-204', then GET /api/v1/users returns both on that user.
- Given an admin, when PATCH /users/{id}/profile with a project code containing spaces, then 400 with 'Project code can only use letters, digits, dot, dash and underscore.'
- Given a non-admin, when PATCH /users/{id}/profile, then 403.
- Given the test schema, when the app starts with ddl-auto=validate, then it starts (no entity/schema drift).

**Tests**

- UserControllerTest (UPDATE): profile patch, validation, fields in list
- UserControllerSecurityTest (UPDATE): profile patch is ADMIN only
- UserOnboardingServiceTest (UPDATE): department from body beats directory department

**Notes:** Project code is free text per person (no project table). Team stays team_member (U13-T02 writes it). Migration numbers V90-V95 follow task order (U01-T06 V90, U08-T01 V91, U09-T09 V92, U13-T01 V93, U13-T06 V94, U15-T08 V95); take the next free number if another migration lands first.

#### U13-T02 — Add a bulk onboarding endpoint with preview: many people, one role, access and team in one step

- [ ] Done · **Type:** backend · **Estimate:** 2 d · **Risk:** medium · **Sources:** AddUsers.dc.html, Known gap: bulk onboarding endpoint · **Depends on:** U10-T02, U13-T01

POST /api/v1/users (UserController.onboardUser -> UserOnboardingService.onboard) takes one person and at most one initialGrant on one environment; the Add people screen adds a list of people (directory + by hand), one role, several environments/groups, and a team per person, with a summary ('Add 3 new people as User, 8 access grants') before committing.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/dto/BulkOnboardRequestDTO.java` (new) — people[] {directoryObjectId?, email?, displayName?, department?, projectCode?, teamId?}, role USER|ENV_ADMIN|ADMIN, access[] {environmentId, scopeType, groupIds, accessLevel, durationDays}, notes; @Size max 100 people, 20 access rows
- `src/main/java/com/tcgdigital/vmcontrol/dto/BulkOnboardResultDTO.java` (new) — per person: email, outcome NEW|EXISTING|SKIPPED, reason, userId, grants {new, extended, skipped}; totals
- `src/main/java/com/tcgdigital/vmcontrol/service/UserOnboardingService.java` (modify) — bulkOnboard(request, actorId, dryRun): resolve each person (directory re-fetch or manual), reuse onboard() rules, apply grants via EnvironmentAccessService.grantScoped, team via TeamService.addMember
- `src/main/java/com/tcgdigital/vmcontrol/controller/UserController.java` (modify) — POST /api/v1/users/bulk-onboard?dryRun=true|false (ADMIN)
- `src/main/resources/static/js/config.js` (modify) — users.bulkOnboard(dryRun) entry

**Steps**

1. Define the request/response DTOs with bean validation (email format, max sizes, durationDays bounds from AccessDurations).
2. In bulkOnboard, dedupe people by lowercased email/directoryObjectId; classify each: NEW (no app_user), EXISTING (active user: access and team only, role and profile unchanged), SKIPPED (inactive user, missing email, directory id not found) with a reason sentence.
3. For every person x access row compute whether the grant is new, extends an existing grant, or is skipped (already broader/longer), using the same rules as EnvironmentAccessService.grantScoped; if U10's bulk grant service exists, call it instead of looping.
4. dryRun=true returns the classification without writing; dryRun=false runs everything in one @Transactional so a failing grant rolls the whole batch back (same contract as onboard()).
5. Create NEW users via UserService.createOnboardedUser with admin/envAdmin from role, department and projectCode; add team membership with TeamService.addMember (source MANUAL) when teamId is set.
6. Write one audit entry per user plus the grant audits already written by grantScoped; return totals for the summary line.

**Acceptance criteria**

- Given 3 new emails, 1 existing active user and 2 environments, when dryRun=true, then the result lists 3 NEW, 1 EXISTING and 8 new grants, and nothing is written.
- Given the same body, when dryRun=false, then 3 users exist with the chosen role, department, project code and team, and each of the 4 people holds both grants.
- Given one access row on an environment the admin cannot grant, when dryRun=false, then 400 with the environment named and no user is created.
- Given an environment admin, when calling bulk-onboard, then 403.

**Tests**

- UserControllerTest (UPDATE): bulk-onboard dryRun and commit
- UserOnboardingServiceTest (UPDATE): classification NEW/EXISTING/SKIPPED, rollback on failing grant, team membership
- UserControllerSecurityTest (UPDATE): ADMIN only

**Notes:** Decision: existing users never have their role changed by bulk onboarding (role changes go through the Change role dialog); the preview says 'already a user, access only'. Shares grant-classification logic with U10's bulk grant; whichever lands second reuses the first. Reuse the U10-T02 bulk grant service for the access part (same new/extended/skipped rules, never lowers a level); this endpoint only adds the people, role and team steps around it.

#### U13-T03 — Rebuild the Users list: counts, filters, plain role names, direct row actions, 10 per page

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Users.dc.html · **Depends on:** U04-T02, U04-T03, U05-T05, U03-T02

user-management.js buildViewHtml/buildUserRow renders role badges 'Admin/Env Admin', a Status column and an 'Actions' dropdown with Make Admin / Make Env Admin toggles; the mockup shows counts (active, administrators, environment admins, haven't signed in yet), search + role + status filters, columns Person / Role / Signs in with / Last sign-in / Actions with direct links Change role, Access or Set password, Deactivate.

**Files**

- `src/main/resources/static/js/features/user-management.js` (modify) — buildViewHtml (line 105) header with 'Add people' link to #/user-management/add; calculateStats (200) four counts; buildUserRow (239) new columns and inline actions; filterUsers (346) role values ADMIN/ENV_ADMIN/USER, status Active/Deactivated; renderPagination (318) via the U04-T02 pager at 10 per page; drop showOnboardModal entry point
- `src/main/resources/static/css/features/user-management.css` (modify) — row/table styles on U03 tokens, last-sign-in warning colour for 'Not signed in yet'
- `src/main/resources/static/js/config.js` (modify) — remove stale users.updateRole ('/role' does not exist, server is /roles) and users.deactivate ('/deactivate' does not exist, server is DELETE /users/{id}); add users.roles(id), users.profile(id)
- `src/main/resources/static/index.html` (modify) — bump ?v= asset stamp
- `test/tests/access/user-management-inactive.spec.ts` (modify) — status filter 'Deactivated', Reactivate as a row link

**Steps**

1. Page header 'Users' / 'Everyone who can sign in, and their role across the platform.' with the U04-T03 header pattern and an 'Add people' primary link.
2. Counts line from allUsers: active, admin, envAdmin && !admin, pendingFirstLogin && !serviceAccount ('haven't signed in yet').
3. Row: initials avatar, name, email; Role 'Administrator' / 'Environment admin' / 'User' (service accounts show 'Service account'); 'Signs in with' = 'Microsoft' or 'Password' from U05-T05's flag; Last sign-in absolute short time or 'Not signed in yet' in the warning colour.
4. Actions: 'Change role' (opens U13-T04 dialog), 'Access' (links to the U10 grant screen preselecting the user) or 'Set password' when the user signs in with a password, then 'Deactivate' / 'Reactivate'.
5. Pager 10 per page; filters keep their state in the hash query so Back restores them.
6. Remove the Bootstrap dropdown and tooltips (initTooltips) that the row no longer uses.

**Acceptance criteria**

- Given 42 active users, when an admin opens #/user-management, then the page shows 10 rows, a pager, and the four counts without page scroll at 1440x900.
- Given a user who signs in with a password, when the row renders, then its middle action reads 'Set password' instead of 'Access'.
- Given the role filter 'Environment admin', then only env admins who are not administrators are listed.

**Tests**

- test/tests/access/user-management-inactive.spec.ts (UPDATE): deactivated filter and Reactivate
- test/tests/access/users-list.spec.ts (NEW): counts, pager, role labels, row actions per sign-in method
- test/tests/a11y/names-and-targets.spec.ts (UPDATE): new row links have names

**Notes:** The 'Access' link target is U10's grant screen; until it lands, link to #/access-management?user=<id>.

#### U13-T04 — Replace the role toggles with Change role, Set password and Deactivate dialogs

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** medium · **Sources:** UserDialogs.dc.html · **Depends on:** U13-T03

Today roles change through two independent PATCH toggles (toggleAdmin/toggleEnvAdmin in user-management.js, setAdmin then setEnvAdmin in UserController.updateUserRoles, not one transaction), password is a single-field modal (showSetPasswordModal) and deactivation is a generic Modals.confirm; the mockup has one role radio dialog, a two-field password dialog with live checks, and a deactivate dialog that explains the consequences.

**Files**

- `src/main/resources/static/js/features/user-management.js` (modify) — new showRoleDialog(user), rework showSetPasswordModal/submitSetPassword (535-600) to two fields, showDeactivateDialog(user); bindActionEvents (434) wires data-action change-role/set-password/deactivate; remove toggleAdmin/toggleEnvAdmin
- `src/main/java/com/tcgdigital/vmcontrol/service/UserService.java` (modify) — @Transactional setRole(userId, Role role, actorId) that sets admin/envAdmin together, keeps the last-admin guard and writes one audit entry
- `src/main/java/com/tcgdigital/vmcontrol/controller/UserController.java` (modify) — updateUserRoles (line ~309) calls setRole in one call; error texts: 'There must always be at least one administrator.', 'You can't deactivate yourself.'

**Steps**

1. Change role dialog: title 'Change role for <name>', sub line '<email> · signs in with Microsoft|a password', three radio cards (User / Environment admin / Administrator) with the mockup descriptions, note 'Access to each environment is set separately, in Access management. There must always be at least one administrator.', buttons Cancel / Save role.
2. Save sends PATCH /users/{id}/roles {admin, envAdmin} once; backend setRole applies both atomically.
3. Set password dialog: 'New password', 'Type it again', live checks '12 to 72 characters' and 'both match' (PASSWORD_MIN/MAX), advice line, Set password disabled until both pass.
4. Deactivate dialog: 'Deactivate <name>?' with the consequences sentence; disabled with the reason for yourself and the last administrator (checked client-side, enforced by UserService.deactivateUser).
5. Server errors shown inside the dialog as sentences that say what to do; success toast 'Role saved.', 'Password set.', '<name> deactivated.'

**Acceptance criteria**

- Given an env admin, when the admin picks Administrator and saves, then one PATCH is sent and the user is admin=true, envAdmin=false.
- Given the only administrator, when someone tries to make that person a User, then the dialog shows 'There must always be at least one administrator.' and nothing changes.
- Given two different passwords, then Set password stays disabled and 'both match' is not ticked.

**Tests**

- UserControllerTest (UPDATE): roles patch is atomic, last-admin guard message
- UserServiceTest (UPDATE): setRole audit and guard
- test/tests/access/user-dialogs.spec.ts (NEW): role save, password checks, deactivate self disabled

**Notes:** Role descriptions mention 'Sees instance IDs' for Administrator, matching U01.

#### U13-T05 — Build the Add people page: who, their role, optional access, confirm

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** AddUsers.dc.html · **Depends on:** U13-T02, U13-T03, U04-T03

Onboarding is a modal today (showOnboardModal/onboardModalBody/submitOnboard in user-management.js) for one person, a directory-or-manual switch and one optional grant; the mockup is a full page (#/user-management/add) with a people list (directory search rows with editable department, team, project code; by-hand form; paste several addresses), a role radio, several environment/group access rows with level and duration, and a summary line before 'Add N people'.

**Files**

- `src/main/resources/static/js/features/add-people.js` (new) — AddPeople page module: state {people[], role, access[]}, directory search (Config.API directory search), manual form, paste parser, team options via Teams.options(), environment/group pickers, preview + submit to users.bulkOnboard
- `src/main/resources/static/js/core/router.js` (modify) — PARAM/route 'user-management-add' for #/user-management/add -> AddPeople.load()
- `src/main/resources/static/js/features/user-management.js` (modify) — delete showOnboardModal, onboardModalBody, bindOnboardEvents, runDirectorySearch... (606-940) once AddPeople replaces them
- `src/main/resources/static/css/features/add-people.css` (new) — three-section layout fitting 1440x900; people table scrolls inside its card
- `src/main/resources/static/index.html` (modify) — add add-people.js and add-people.css; bump ?v=

**Steps**

1. Breadcrumb 'Users / Add people', title and intro 'Add colleagues before their first sign-in, and give them access in the same step.'
2. Section '1 · Who': directory search (debounced, 2+ chars) adds rows with Source 'Directory' or 'Already a user' (DirectoryUserDTO.alreadyInApp); each row has Department input (prefilled from directory), Team select (No team + teams), Project code input, Remove; when directory lookup is off show only the by-hand form.
3. By-hand form: Email* / Name / Department / Team / Project code, 'Add to list'; pasting text with several addresses into Email splits on comma/semicolon/newline and adds one 'By hand' row each; note 'People added by hand are marked unverified until they sign in with Microsoft.'
4. Section '2 · Their role': three radio cards, default User.
5. Section '3 · Access (optional)': 'Add environment' opens an environment (+ optional groups) picker; each row has level (Operator / View only / Admin) and duration (7 / 30 / 90 days / No end) selects and Remove.
6. Confirm bar: call bulk-onboard dryRun on every change (debounced) and show 'Add 3 new people as User, 8 access grants' plus skipped reasons; CTA 'Add N people' commits, then returns to Users with a toast naming the count.
7. Unsaved list protection: leaving the page with rows asks to discard.

**Acceptance criteria**

- Given directory lookup on, when the admin finds and adds two people, sets a team for one, chooses Environment admin and two environments, then the summary reads 'Add 2 new people as Environment admin, 4 access grants'.
- Given 'a@x.com; b@x.com' pasted into Email, when 'Add to list' is pressed, then two By hand rows appear.
- Given the commit succeeds, then the Users list shows the new people with 'Not signed in yet'.

**Tests**

- test/tests/access/add-people.spec.ts (NEW): manual rows + paste, role pick, access rows, summary text, commit (smoke app, admin-001)
- src/test/js/add-people-parse.test.mjs (NEW): address paste parser
- test/tests/a11y/keyboard-reach.spec.ts (UPDATE): Add people page reachable by keyboard

**Notes:** Specs that opened the old 'Onboard User' modal must move to this page in the same task.

#### U13-T06 — Give teams their environments, VM-hour usage, Entra group name and last sync

- [ ] Done · **Type:** backend · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Teams.dc.html, TeamDetail.dc.html · **Depends on:** U13-T01

TeamDTO (dto/TeamDTO.java) carries only counts (environmentCount, memberCount) and entraGroupId; the Teams cards list each team's environments with a power strip and 'VM hours this month used / limit', and Team detail shows 'Linked to Entra group SG-Payments-Engineering · 12 members from it, last synced 09:00' and lets an admin change which environments belong to the team (today only from each environment's settings).

**Files**

- `src/main/resources/db/migration/V94__add_team_entra_group_name.sql` (new) — ALTER TABLE team ADD COLUMN entra_group_name VARCHAR(255) NULL (next free V number)
- `src/main/java/com/tcgdigital/vmcontrol/model/Team.java` (modify) — entraGroupName field
- `src/main/java/com/tcgdigital/vmcontrol/service/TeamService.java` (modify) — linkEntraGroup (line 92) stores the group display name from GraphDirectoryService; new overview(teamId) and setEnvironments(teamId, environmentIds, actorId)
- `src/main/java/com/tcgdigital/vmcontrol/dto/TeamOverviewDTO.java` (new) — team + environments [{environmentId, name}] + quotas (QuotaService.QuotaView) + entraGroupName + entraMemberCount + lastSyncedAt (max TeamMember.syncedAt where source ENTRA)
- `src/main/java/com/tcgdigital/vmcontrol/controller/TeamController.java` (modify) — GET /api/v1/teams/overview (ADMIN, all active teams in one call) and GET /teams/{id}/overview; PUT /teams/{id}/environments {environmentIds}

**Steps**

1. Add the column via the next free migration; set it in linkEntraGroup from the group search result and clear it on unlink.
2. Build TeamOverviewDTO in TeamService using EnvironmentRepository (environments with team_id) and QuotaService.usage(teamId) for this month's VM hours and cost.
3. GET /teams/overview returns all active teams in one query batch (no N+1: environments grouped by team id, quotas fetched per team only when quotas.enabled).
4. PUT /teams/{id}/environments moves the listed environments to this team and clears the team on environments removed from the list; audit each move; reject unknown or inactive environments with their names.
5. Front-end per-environment VM states for strips come from U05-T03's environment summary, not from this endpoint.

**Acceptance criteria**

- Given team Payments with 2 environments and a 4,000 h VM_HOURS quota, when GET /teams/overview, then Payments lists both environments and used/limit hours.
- Given a linked Entra group, then overview returns its display name and the latest sync time.
- Given PUT /teams/t1/environments with [env-a], then env-a belongs to t1 and t1's previous other environments have no team.

**Tests**

- TeamControllerIntegrationTest (UPDATE): overview, set environments, ADMIN only
- TeamServiceTest (UPDATE): group name stored on link, cleared on unlink
- QuotaIntegrationTest (UPDATE): overview quota numbers match usage()

**Notes:** Migration number: next free after U13-T01's. Migration numbers V90-V95 follow task order (U01-T06 V90, U08-T01 V91, U09-T09 V92, U13-T01 V93, U13-T06 V94, U15-T08 V95); take the next free number if another migration lands first.

#### U13-T07 — Rebuild the Teams list as team cards with environments, strips and monthly VM hours

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** Teams.dc.html · **Depends on:** U13-T06, U03-T03, U05-T03, U04-T02

teams.js load/drawList renders a list + side detail with allocation gaps (drawGaps); the mockup shows one card per team: name, cost centre, members line ('6 people · synced from Entra group "Payments Engineering"'), each environment with its power strip, a 'VM hours this month' bar (red over 90%), and Edit / Members / Environments links.

**Files**

- `src/main/resources/static/js/features/teams.js` (modify) — load (23) header 'Teams' + intro + 'New team'; refresh (60) reads Config.API.teams.overview; drawList (75) renders cards; Members/Environments links go to #/teams/{id}; openForm (209) kept for New team / Edit
- `src/main/resources/static/css/features/teams.css` (modify) — card grid (2 columns at 1440), quota bar
- `src/main/resources/static/js/config.js` (modify) — teams.overview, teams.teamOverview(id), teams.environments(id)
- `test/tests/environments/teams.spec.ts` (modify) — assert cards instead of the list + side detail

**Steps**

1. Fetch teams overview and the U05-T03 environment summary in parallel; map each team environment to its strip states.
2. Card: h2 name, cost centre mono, members line (entraGroupName when linked), environment rows 'name + PowerStrip', quota bar with 'used / limit' and an aria-label '<n>% of the monthly VM hours used'; no quota -> 'No monthly limit'.
3. Links: Edit (team form), Members and Environments (team detail page anchors).
4. Teams with no environments show 'No environments yet' with a link to add them; keep the allocation-gaps hint as one line above the cards when gaps exist.
5. Cards paginate at 6 per page to fit 1440x900.

**Acceptance criteria**

- Given 4 teams, when an admin opens #/teams, then 4 cards show cost centre, members line, environments with strips and the VM-hours bar.
- Given a team at 96% of its VM hours, then its bar uses the red token.
- Given quotas switched off, then no quota bar is drawn and no quota request is made.

**Tests**

- test/tests/environments/teams.spec.ts (UPDATE): cards, links to detail
- test/tests/environments/quotas.spec.ts (UPDATE): bar text on the card

#### U13-T08 — Add the Team detail page: members with Entra group, monthly limits, environments

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** TeamDetail.dc.html · **Depends on:** U13-T06, U13-T07, U04-T03

Team members, Entra linking, sync and quotas live in a side panel of the list (teams.js select/drawDetail/drawQuotas/saveQuotas, metrics labelled 'Running VM-hours' / 'Spend ($)'); the mockup is #/teams/{id} with breadcrumb, a Members card (Entra group line, Sync now, Change group, 8 per page, Remove only for 'Added by hand'), and an aside with Monthly limits (limit, warn %, 'At the limit' Warn only / Ask an owner to approve each start / Block new starts) and the environment list.

**Files**

- `src/main/resources/static/js/features/team-detail.js` (new) — TeamDetail page: overview fetch, members (Config.API.teams.members), sync, change group (searchGroups/link moved from teams.js), add people (pick existing users), remove manual members, quotas form, environments dialog
- `src/main/resources/static/js/features/teams.js` (modify) — remove select/drawDetail/drawQuotas/saveQuotas/searchGroups/link/sync/addMember/removeMember after moving them
- `src/main/resources/static/js/core/router.js` (modify) — PARAM_ROUTES entry 'team-detail' for #/teams/{teamId}
- `src/main/resources/static/index.html` (modify) — add team-detail.js; bump ?v=
- `test/tests/environments/quotas.spec.ts` (modify) — quota form on the detail page, enforcement labels

**Steps**

1. Header: breadcrumb 'Teams / <name>', h1, line '<slug> · cost centre <cc> · N environments · M members', 'Edit name and cost centre'.
2. Members card: Entra line with Sync now (POST sync, toast with added/removed counts) and Change group (group search dialog); list name/email/source tag 'Entra' or 'Added by hand'; Remove only for MANUAL; footer '1–8 of 14 · people from the Entra group are removed there' with pager.
3. 'Add people' opens a user picker (active users not in the team) and posts members one by one.
4. Monthly limits aside: one block per metric (VM hours, Cost) with used/limit bar and warn marker, Limit, Warn at %, At the limit select mapping WARN / REQUIRE_APPROVAL / BLOCK; Save limits sends PUT /teams/{id}/quotas.
5. Environments block lists names with 'Change which environments belong to <team>' opening a checklist dialog that calls PUT /teams/{id}/environments.
6. Hide the quota aside when quotas.enabled is off (no request), per U02-T03.

**Acceptance criteria**

- Given a team linked to an Entra group, when Sync now is pressed, then a toast says how many members were added and removed and the list refreshes.
- Given an Entra member, then its row has no Remove link.
- Given 'Block new starts' at 3,000 h saved, then GET /teams/{id}/quotas returns enforcement BLOCK with limit 3000.

**Tests**

- test/tests/environments/team-detail.spec.ts (NEW): members paging, manual remove, limits save, environments dialog
- test/tests/environments/quotas.spec.ts (UPDATE)
- test/tests/environments/teams.spec.ts (UPDATE): Entra link/sync moved to detail

#### U13-T09 — Email each onboarded person what they can use and how to sign in

- [ ] Done · **Type:** backend · **Estimate:** 0.5 d · **Risk:** low · **Sources:** AddUsers.dc.html · **Depends on:** U13-T02

The Add people confirm line promises 'Each person gets an email with what they can use and how to sign in'; today onboarding sends nothing of its own (only per-grant access-granted emails when notification.email.access-granted.enabled is on), so a bulk onboard would send several grant mails per person or none.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/UserOnboardingService.java` (modify) — after commit of bulkOnboard, publish one OnboardedEvent per NEW/EXISTING person with role and grants
- `src/main/java/com/tcgdigital/vmcontrol/service/EmailService.java` (modify) — sendOnboardedEmail(user, role, grants): sign-in link (Microsoft or username), environments with level and end date
- `src/main/java/com/tcgdigital/vmcontrol/service/settings/SettingsRegistry.java` (modify) — notification.email.onboarded.enabled (default false) in Notifications
- `src/main/resources/application.properties` (modify) — notification.email.onboarded.enabled=${NOTIFICATION_EMAIL_ONBOARDED_ENABLED:false}

**Steps**

1. Add the property and runtime setting, default off like every email type.
2. Send after commit (TransactionalEventListener AFTER_COMMIT, async executor) so a rolled-back batch sends nothing.
3. While this email is on, suppress the per-grant access-granted email for grants made by the same bulk onboard (one mail per person).
4. Copy: subject 'You can now use VM Control', body lists environments, level and end date, and how to sign in.

**Acceptance criteria**

- Given the switch on and a commit for 3 people, then exactly 3 emails are logged in the email log.
- Given a rolled-back batch, then no email is sent.
- Given the switch off, then behaviour is unchanged from today.

**Tests**

- UserOnboardingServiceTest (UPDATE): event after commit only
- EmailTemplatesTest (UPDATE): onboarded template renders environments and sign-in method

**Notes:** If the switch stays off in staging, the confirm line should read 'Access is ready; no email is sent.' (U13-T05 reads the setting).

## U14

### Admin home & platform — Admin

Administrators land on a Home that lists what needs attention, see platform health at a glance, and manage AWS accounts, API access, settings and environment templates on screens that fit without scrolling.

Sources: AdminDashboard.dc.html, AwsAccounts.dc.html, ApiAccess.dc.html, ApiTabs.dc.html, Settings.dc.html, SystemHealth.dc.html, TemplatesAdmin.dc.html, Known gap: admin 'needs attention' summary endpoint, Known gap: 'plan' line on Home, Crawl audit: ~45 unnamed switches, SystemHealthFleet.dc.html · 9 tasks · 13 days

#### U14-T01 — Replace the admin dashboard with Home: needs attention, fleet right now, spend, platform health

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** AdminDashboard.dc.html · **Depends on:** U05-T04, U05-T03, U03-T03, U04-T03

dashboard.js buildAdminDashboard (line 223) draws Global Utilization CPU/Memory/Network charts, Fleet State donut, Cloud Coverage, the scheduler list (buildSchedulerHealthPanel) and the risk chart (buildRiskCompliancePanel/buildRiskComplianceOption); the mockup Home for administrators has a one-line fleet+spend summary, six 'Needs attention' tiles linking to the work, 'Fleet right now' power strips, 'Spend this month' (spent / forecast / plan, top environments) and one platform-health line linking to System health.

**Files**

- `src/main/resources/static/js/features/dashboard.js` (modify) — buildDashboardHtml (130) title 'Home' and summary line for persona ADMIN; new buildAttention(), buildFleet(), buildSpend(), buildHealthLine(); remove admin-only chart builders (buildRiskComplianceOption, buildGaugeOption, trio utilization) once unused; initCharts (483) admin branch shrinks to the spend bar
- `src/main/resources/static/css/features/dashboard.css` (modify) — attention tile grid (3x2), fleet rows, spend bar with forecast and plan markers
- `src/main/resources/static/js/config.js` (modify) — use U05-T04 attention endpoint entry, costManagement.summary/forecast, budgets list
- `test/tests/dashboard/dashboard.spec.ts` (modify) — admin Home assertions (tiles, fleet, spend, health line)

**Steps**

1. Summary line: '<running> of <total> VMs running across <n> environments · $<mtd> this month, heading for $<forecast>' from DashboardSummaryDTO.summary and cost summary/forecast.
2. 'Needs attention': render the U05-T04 items (access requests, approvals, failed operations 24 h, VMs to review, budget almost used, late scheduled job) as count badge + title + detail tiles linking to their pages; tiles with 0 are hidden; all zero shows 'Nothing needs you right now.'
3. 'Fleet right now': top 7 environments by running VMs with PowerStrip (U03-T03) from U05-T03, each linking to the environment; header link 'All <n> environments'.
4. 'Spend this month': spent bar against forecast; plan marker = sum of active environment budgets (team budgets excluded to avoid double counting) labelled 'Budgets, $X'; no budgets -> marker and label omitted; two largest environments by month-to-date cost.
5. Health line: 'Platform healthy. <n> of <m> scheduled jobs on time · state synced <x> min ago · <d> drift in 24 h' (or the first problem in gold/red) linking to #/system-health.
6. Requests for cost/budget skip when those features are off (U02-T03).

**Acceptance criteria**

- Given 3 pending access requests, when an admin opens Home, then the 'Access requests' tile shows 3 and links to the access requests page.
- Given no budgets, then the spend card shows Spent and Forecast only.
- Given a late cost snapshot job, then the health line names it and links to System health.

**Tests**

- test/tests/dashboard/dashboard.spec.ts (UPDATE): admin Home sections
- test/tests/dashboard/admin-home.spec.ts (NEW): zero-attention empty state, plan marker only with budgets
- DashboardScopeIntegrationTest (UPDATE if summary fields change)

**Notes:** Decision on 'plan': sum of environment budgets, else dropped. U06 owns the non-admin Home in the same file; land after U06's dashboard.js restructure or keep the admin branch in its own builder functions to avoid conflicts. Leave the chart option builders (buildSingleTrendOption, buildDonutOption, buildGaugeOption, buildRiskCompliancePanel/Option) in dashboard.js; U14-T09 moves them to the Fleet use tab of System health.

#### U14-T02 — Rebuild System health around four now-figures and the scheduled jobs list

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** SystemHealth.dc.html · **Depends on:** U04-T02, U03-T02

system-health.js render (line 219) shows metric cards plus six report tables (audit summary, state changes, drift, lock compliance, VM operations, email log) and no scheduled-job list (that only exists on the dashboard via DashboardSummaryService.buildSchedulerHealth); the mockup shows VM state sync / Drift 24 h / Actions 24 h / Scheduled jobs on time, and a jobs table (Job, Runs, Last run, Status) with 'Problems first' / 'All' toggles.

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/SchedulerHealthService.java` (new) — move buildSchedulerHealth (DashboardSummaryService line 369) here; add label, cadence text from SchedulerJobCatalog.Job (expectedInterval / cron), status ON_TIME|LATE|OFF, lateBy
- `src/main/java/com/tcgdigital/vmcontrol/service/DashboardSummaryService.java` (modify) — delegate to SchedulerHealthService
- `src/main/java/com/tcgdigital/vmcontrol/controller/MonitoringController.java` (modify) — GET /api/v1/monitoring/scheduler-health (ADMIN)
- `src/main/resources/static/js/features/system-health.js` (modify) — render: header with 'Sync VM state now' / 'Sync EKS now' (EKS hidden when no EKS environments), four tiles, jobs table with filter and 10-per-page pager; the six report tables move to a 'Reports' tab
- `src/main/resources/static/css/features/system-health.css` (modify) — tile row, status pills ok/late/off
- `test/tests/security/system-health.spec.ts` (modify) — tiles, jobs table, Reports tab

**Steps**

1. Extract SchedulerHealthService with the existing lock-table logic; keep DashboardSummaryServiceSchedulerHealthTest green by pointing it at the new service.
2. Add cadence wording: 'Every 5 min', 'Hourly', 'Daily 06:00' (cron with fixed hour/minute), else the cron string.
3. New endpoint returns jobs sorted problems first (LATE, then OFF last).
4. Front end: tiles 'VM state sync <x> min ago · <n> VMs, every 5 min', 'Drift, 24 h', 'Actions, 24 h <n> · <p>% succeeded · Audit log', 'Scheduled jobs <on time>/<total> · <late> late'.
5. Jobs table: name, Runs, Last run (absolute short), Status pill 'On time' / '2 h late' / 'Switched off'; toggle buttons aria-pressed.
6. Reports tab keeps the existing tables unchanged (lazy-loaded only when opened).

**Acceptance criteria**

- Given a cost snapshot job 2 hours late, when an admin opens System health with 'Problems first', then it is the first row with '2 h late'.
- Given a non-admin, when GET /monitoring/scheduler-health, then 403.
- Given the page loads, then the report requests are not made until Reports is opened.

**Tests**

- SchedulerHealthServiceTest (NEW, from DashboardSummaryServiceSchedulerHealthTest): cadence text, late/off status
- MonitoringController security test (UPDATE/NEW): ADMIN only
- test/tests/security/system-health.spec.ts (UPDATE)

#### U14-T09 — Add a Fleet use tab to System health: 24 h CPU, memory and network, fleet state, metrics coverage, risk and compliance

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** SystemHealthFleet.dc.html, SystemHealth.dc.html, owner decision 2026-10-19 · **Depends on:** U14-T01, U14-T02

dashboard.js buildAdminDashboard (L223) draws Global Utilization CPU/Memory/Network (#dash-util-*), the Fleet State donut (#dash-vm-state), the Cloud Coverage gauge (#dash-coverage) and Risk & Compliance (buildRiskCompliancePanel L402, buildRiskComplianceOption L657), all from GET /dashboard/summary (DashboardSummaryDTO incl. riskCompliance). U14-T01 replaces that dashboard with the new admin Home; the owner chose to keep these views, so they move, restyled, to a 'Fleet use' tab on System health (SystemHealthFleet.dc.html).

**Files**

- `src/main/resources/static/js/features/fleet-use.js` (new) — FleetUse.render(container): fetch the dashboard summary, draw three 24 h line charts, the fleet state bar, metrics coverage and the risk list; holds the chart option builders moved from dashboard.js
- `src/main/resources/static/js/features/dashboard.js` (modify) — remove the moved builders (buildSingleTrendOption, buildDonutOption for VM state, buildGaugeOption, buildRiskCompliancePanel/Option, compactRiskText, riskToneColor) once no caller remains
- `src/main/resources/static/js/features/system-health.js` (modify) — 'Fleet use' tab between 'Now' and 'Reports', mounting FleetUse only when opened
- `src/main/resources/static/css/features/system-health.css` (modify) — fleet-use grid: three chart cards, then fleet state / coverage / risk
- `src/main/resources/static/index.html` (modify) — add js/features/fleet-use.js before system-health.js; bump the ?v= stamp
- `test/tests/security/system-health.spec.ts` (modify) — Fleet use tab

**Steps**

1. Move the chart option builders and risk helpers from dashboard.js into fleet-use.js unchanged, then switch their colours to the tokens (CPU navy, memory blue, network gear green; fleet state green / grey / gold / red).
2. Add the 'Fleet use' tab (U04-T02 tabs) after 'Now'; fetch config.endpoints.dashboard.summary only when the tab opens; dispose the charts when the tab or page is left (the chartRegistry pattern from dashboard.js).
3. Use row: cards CPU / Memory / Network, each with the 'now' value, a 24 h line with a light area and an end point, and one note line (peak and its time; for memory, how many running VMs send memory data when that field exists, else no note).
4. Fleet state: one stacked bar plus a legend with counts (Running, Stopped, Starting or stopping, Failed) from vmStatusCounts. Metrics coverage: coverage.metricsPercent with 'of running VMs reported in the last hour' and the CloudWatch explanation.
5. Risk and compliance: rows with count > 0 in plain words (title, description, bar, count), each a link to where it is fixed: encryption -> #/waste, idle -> #/waste, no metrics -> this tab, drift and not found in AWS -> #/vm-registry. No rows -> 'Nothing needs a look.'
6. Each chart gets role=img and an aria-label stating its range; bump the asset stamp.

**Acceptance criteria**

- Given an admin opens System health > Fleet use, then 24 h CPU, memory and network, fleet state, metrics coverage and the risk rows show, with the same numbers the old admin dashboard showed.
- Given an admin opens System health without opening Fleet use, then no /dashboard/summary request is made by the page.
- Given there are no risk signals, then the risk card says 'Nothing needs a look.'
- Given a risk row is clicked, then the page where it is fixed opens.

**Tests**

- test/tests/security/system-health.spec.ts (UPDATE): open Fleet use; three charts render; fleet state counts equal /dashboard/summary vmStatusCounts; risk rows link to their pages; no summary request before the tab opens
- test/tests/a11y (UPDATE): axe on the Fleet use tab

**Notes:** Owner decision 2026-10-19: keep the fleet use and risk & compliance views that were on the admin dashboard. No backend change; reuses /dashboard/summary. Admin only, like System health.

#### U14-T03 — Restyle AWS accounts: access in words, grouped account IDs, a clear last check

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** AwsAccounts.dc.html · **Depends on:** U03-T02, U04-T03

aws-accounts.js draw (line 83) renders Name / Account / Role ARN / Regions / Environments / Billing / Status / Last verified with icon-only Verify/Edit/Deactivate buttons; the mockup shows Account (with Default tag), Account ID in 4-digit groups, Access in words ('Platform credentials' or 'Assumes PlatformOperator role'), Regions, Environments, Last check (green/red dot with 'OK · today 06:00' or the failure sentence) and text actions 'Check access' / 'Edit', plus a line about AWS Organizations discovery.

**Files**

- `src/main/resources/static/js/features/aws-accounts.js` (modify) — draw: new columns and text actions; verification() (74) returns dot + sentence; Deactivate moves into the Edit dialog (openForm, 108) as a danger link; import button text 'Find accounts in AWS Organizations'
- `src/main/java/com/tcgdigital/vmcontrol/service/aws/AwsAccountAdminService.java` (modify) — map raw AssumeRole/AccessDenied lastError to a plain sentence ('Role can't be assumed') kept in a new AwsAccountDTO.lastErrorSummary; full error stays in lastError for the tooltip
- `src/main/java/com/tcgdigital/vmcontrol/dto/AwsAccountDTO.java` (modify) — add lastErrorSummary
- `test/tests/admin/aws-accounts.spec.ts` (modify) — badge title AssumeRole -> last-check sentence; deactivate inside Edit

**Steps**

1. Account ID shown as '4183 2297 0561' in mono; Access derived from roleArn (role name after 'role/'), else 'Platform credentials'.
2. Last check: dot (green when lastError null and lastVerifiedAt set), text 'OK · <time>' or lastErrorSummary; never checked -> 'Not checked yet'.
3. Actions 'Check access' (POST verify, row updates in place) and 'Edit'; Edit dialog gains 'Deactivate account' (not for the default account).
4. Footer line: when organization-candidates is unavailable, 'Finding accounts automatically from AWS Organizations is off. An administrator can switch it on in Settings › AWS.'; otherwise show the import button.
5. Add the server-side error summary mapping and unit-test it.

**Acceptance criteria**

- Given an account whose role can't be assumed, then its row shows a red dot and 'Role can't be assumed' with the raw error in a tooltip.
- Given 'Check access' succeeds, then the row shows a green dot and 'OK · today hh:mm' without reloading the page.
- Given the default account, then its Edit dialog has no Deactivate link.

**Tests**

- test/tests/admin/aws-accounts.spec.ts (UPDATE)
- AwsAccountAdminServiceTest (UPDATE/NEW): error summary mapping

**Notes:** Account IDs (not instance IDs) stay visible: this page is ADMIN only.

#### U14-T04 — Split API access into Tokens and Service accounts tabs with a switched-off state

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** ApiAccess.dc.html, ApiTabs.dc.html · **Depends on:** U04-T02, U03-T02

api-tokens.js ApiAccess load (line 206) stacks 'Active tokens' and 'Service accounts' on one page and shows 'API tokens are off. Set api-tokens.enabled (with API_TOKEN_PEPPER)…' as an info alert (off(), 235); the mockup has tabs Tokens / Service accounts / Webhooks with an 'API reference' link, a full switched-off card with 'Open Settings' and 'Setup guide', token rows Name+prefix / Owner / Can / Last used / Expires / Revoke, and service-account rows with owner, token count, status and 'New token' or 'Switch on' — but ServiceAccountController has no reactivate endpoint.

**Files**

- `src/main/resources/static/js/features/api-tokens.js` (modify) — ApiAccess.load: tabs via U04-T02 tabs primitive, ?tab= in hash; drawTokens (250) new columns with scopes in words; drawAccounts (301) cards; reveal (140) as an inline card 'Copy it now; it isn't shown again.'; off() returns the switched-off card
- `src/main/java/com/tcgdigital/vmcontrol/controller/ServiceAccountController.java` (modify) — POST /api/v1/service-accounts/{userId}/reactivate
- `src/main/java/com/tcgdigital/vmcontrol/service/ServiceAccountService.java` (modify) — reactivate(userId, actorId) with audit; tokens stay revoked
- `src/main/resources/static/js/config.js` (modify) — serviceAccounts.reactivate(id)
- `test/tests/access/api-tokens.spec.ts` (modify) — tabs, scope wording, switched-off card

**Steps**

1. Header 'API access' / 'Tokens for scripts and tools, accounts for services, and webhooks that tell other systems what happened.' with 'New token' (Tokens tab only).
2. Scope words map: environments:read -> 'Read environments', operations:write -> 'start and stop', cost:read -> 'Read cost', assistant -> 'Ask the assistant'; joined with commas.
3. Service accounts tab: 'For pipelines and tools. Each has a person who owns it.' + 'New service account'; card per account '<name> · <role>' status, 'Owner <name> · <n> active tokens', action 'New token' or 'Switch on'.
4. Add the reactivate endpoint and wire 'Switch on'.
5. Switched-off card (404 from the token endpoints): title 'API tokens are switched off', explanation, buttons 'Open Settings' (#/settings?section=int) and 'Setup guide' (help topic).
6. Webhooks tab placeholder links to U14-T05 content (same task series).

**Acceptance criteria**

- Given tokens enabled, when an admin opens #/api-access, then the Tokens tab lists tokens with prefix, owner, scopes in words, last used and expiry.
- Given a deactivated service account, when 'Switch on' is pressed, then it shows 'Active' and its old tokens stay revoked.
- Given api-tokens.enabled off, then the switched-off card shows and no tab content is requested.

**Tests**

- ServiceAccountControllerTest (UPDATE): reactivate, ADMIN only, tokens remain revoked
- test/tests/access/api-tokens.spec.ts (UPDATE)

**Notes:** My account's own token tab (ApiTokensUi in the same file) keeps working; only the admin page changes.

#### U14-T05 — Add the Webhooks tab: every webhook, its events, test and recent deliveries

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** ApiTabs.dc.html · **Depends on:** U14-T04

Webhooks are only managed per environment (webhooks.js WebhooksPanel.renderEnvPanel mounts into #env-webhook-panel), although WebhookService.list(null) already returns every webhook an admin may manage; the mockup's Webhooks tab shows a webhook with its scope ('Payments Dev only'), event chips, 'Send test' / 'Edit', recent deliveries with Delivered / Failed, retrying / Waiting / Gave up and the retry policy sentence.

**Files**

- `src/main/resources/static/js/features/webhooks.js` (modify) — extract a mount(container, {environmentId|null}) used by renderEnvPanel and by the admin tab; delivery status labels map DELIVERED/FAILED/PENDING/GAVE_UP
- `src/main/resources/static/js/features/api-tokens.js` (modify) — Webhooks tab calls WebhooksPanel.mount('#api-access-webhooks', {environmentId: null})
- `test/tests/environments/webhooks.spec.ts` (modify) — env panel still works after the refactor

**Steps**

1. Refactor WebhooksPanel so list/create/edit/test/deliveries work in any container; environmentId null lists all (GET /webhooks without environmentId).
2. Admin tab: master list of webhooks (label, host, scope 'All environments' or '<env> only', status) and a detail card for the selected one.
3. Detail: event chips, 'Send test' (toast with receiver status), 'Edit', deliveries table (type, when, status tag, detail like '503 · attempt 3, next at 09:11').
4. Footer sentence about the X-VmControl-Signature header and the retry schedule; skip the tab's requests when webhooks are off (U02-T03).

**Acceptance criteria**

- Given two webhooks on different environments, when an admin opens the Webhooks tab, then both are listed with their scope.
- Given a failed delivery being retried, then its row reads 'Failed, retrying' with the next attempt time.
- Given webhooks are off, then the tab shows the switched-off message and makes no webhook request.

**Tests**

- test/tests/environments/webhooks.spec.ts (UPDATE)
- test/tests/access/api-webhooks-tab.spec.ts (NEW): list, detail, test ping

**Notes:** Payloads already lose instance IDs via U01-T04.

#### U14-T06 — Regroup settings into eight sections, add the missing switches and a batch save endpoint

- [ ] Done · **Type:** backend · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Settings.dc.html

SettingsRegistry groups definitions as Notifications / Cost management / Automation and governance / Spot / Data retention, and several switches the mockup shows (auth.password-login.enabled, security.env-admin.scope, security.csp.report-only, lease.enabled, locks.expiry.enabled, automation.idle-stop.enabled) are not registered at all; SettingsController only has PUT /{key}, while the mockup saves a section's pending changes together ('1 change not saved · Discard · Save changes').

**Files**

- `src/main/java/com/tcgdigital/vmcontrol/service/settings/SettingsRegistry.java` (modify) — group values become Notifications, Access and security, Environments and leases, Automation, Cost, Integrations, AWS, Data retention; register the missing keys (tier 1 when the reading code uses SettingsService live, else tier 2 read-only)
- `src/main/java/com/tcgdigital/vmcontrol/service/settings/SettingsService.java` (modify) — updateAll(List<change>, actorId): validate every value first, then write all in one transaction, audit each
- `src/main/java/com/tcgdigital/vmcontrol/controller/SettingsController.java` (modify) — PUT /api/v1/settings {changes:[{key,value}]} returning the updated SettingDTOs; 400 lists every invalid key
- `src/test/java/com/tcgdigital/vmcontrol/controller/SettingsControllerIntegrationTest.java` (modify) — batch save all-or-nothing, new groups

**Steps**

1. Re-map each definition's group to one of the eight sections (Spot -> Environments and leases or Cost as fits; chat.enabled/graph/webhooks/api tokens -> Integrations; aws.region, discovery, sync -> AWS).
2. Register the six missing keys; for each check where it is read (e.g. auth.password-login.enabled in the login flow, CSP filter, LockExpiryScheduler) and make it live via SettingsService where cheap, otherwise tier 2 with the .env name shown.
3. Add updateAll: reject the whole batch if any key is unknown, read-only or out of range; one transaction; cache eviction as for single writes.
4. Keep PUT /{key} and DELETE /{key} (reset) for compatibility.
5. Keep the 'Changes apply within 30 seconds' promise true (TTL 30 s in SettingsService).

**Acceptance criteria**

- Given two valid changes and one out-of-range number, when PUT /settings, then 400 names the bad key and neither valid change is stored.
- Given GET /settings, then every setting's group is one of the eight section names.
- Given auth.password-login.enabled turned off at runtime (if tier 1), then password login is refused within 30 s.

**Tests**

- SettingsControllerIntegrationTest (UPDATE): batch, groups
- SettingsToggleIntegrationTest (UPDATE): new live keys
- SettingsServiceTest (NEW/UPDATE): updateAll validation

**Notes:** security.csp.report-only is likely startup-only (filter built once): register as tier 2 rather than make the filter dynamic.

#### U14-T07 — Rebuild the Settings page: section menu, named switches, save bar

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Settings.dc.html, Crawl audit: ~45 unnamed switches · **Depends on:** U14-T06, U02-T04, U03-T02

settings.js draw (line 108) renders every group as a stacked card and saves each switch immediately with a 10 s Undo (save/toggle, 136-163), which overflows the screen and gives no review step; the mockup has a left section menu with counts, rows with name, description, key in mono and state ('On by default' / 'Changed'), switches with role=switch named by the setting, and a 'N changes not saved · Discard · Save changes' bar.

**Files**

- `src/main/resources/static/js/features/settings.js` (modify) — load: two-column layout (section nav + section panel), ?section= in hash; row(): label for/id pairs, role=switch, aria-describedby the description; pending changes map; Save changes -> PUT /settings batch; Discard resets; RISKY confirmations kept and shown before saving
- `src/main/resources/static/css/features/settings.css` (new) — section nav with gold active marker, rows, sticky save bar
- `src/main/resources/static/js/config.js` (modify) — settings.batch
- `src/main/resources/static/index.html` (modify) — add settings.css; bump ?v=
- `test/tests/admin/settings.spec.ts` (modify) — replace '.settings-group count 4' and immediate save/Undo with section nav + Save changes

**Steps**

1. Header 'Settings' / 'Platform switches. Changes apply within 30 seconds; no restart needed.'
2. Section nav buttons (aria-current) with counts; the panel shows the section title, intro sentence and rows.
3. Row: name, description, key mono, state text 'On by default' / 'Off by default' / 'Changed' / 'Set in .env' (tier 2: read-only, no input); number rows keep min/max hints.
4. Changing a control adds it to pending; the bar shows 'N change(s) not saved' with Discard and Save changes; leaving the page with pending changes asks first.
5. Save sends the batch; on 400 show each error under its row; RISKY switches show their confirmation before the batch is sent.
6. Every switch has an accessible name equal to its label (fixes the 'on' names found by the crawl for this page).

**Acceptance criteria**

- Given an admin turns on two email switches, then nothing is saved until 'Save changes', and the bar says '2 changes not saved'.
- Given Discard, then both switches return to their stored values.
- Given a tier 2 setting, then it shows 'Set in .env' and has no control.

**Tests**

- test/tests/admin/settings.spec.ts (UPDATE)
- test/tests/a11y/names-and-targets.spec.ts (UPDATE): settings switches have names

#### U14-T08 — Turn template admin into a master-detail editor with the requests waiting alongside

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** TemplatesAdmin.dc.html · **Depends on:** U04-T02, U03-T02

templates-admin.js adds tabs to the New environment page: drawManage (line 107) lists templates in a table and openEditor (152) edits them in a large modal (#templateEditorModal), and drawApprovals (60) is a separate tab; the mockup 'New environments' page has tabs Templates / 'Requests · 2 waiting', a template list on the left (name, engine, Offered/Not offered), an inline editor (Offered switch, description, built with, product, AWS account and region selects, estimated cost, lasts / longest allowed hours, who can ask, needs approval by) and a 'Waiting for you' aside with Approve / Decline.

**Files**

- `src/main/resources/static/js/features/templates-admin.js` (modify) — drawManage -> master list + inline editor; openEditor/collect/save (152-270) reused inside the panel; parameter builder (paramRow/addParam) moves to a collapsible 'Parameters' section; AWS account select from Config.API awsAccounts options; drawApprovals rendered as the aside and as the Requests tab
- `src/main/resources/static/js/features/templates.js` (modify) — tabs()/drawTabs (60-70): admin sees 'Templates' and 'Requests · N waiting'; page title 'New environments' for admins
- `src/main/resources/static/css/features/templates.css` (new) — three-column layout (list 260 px / editor / aside 320 px); add to index.html and bump ?v=
- `test/tests/environments/templates-admin.spec.ts` (modify) — #templateEditorModal -> inline editor ids (keep #tpl-e-* ids)

**Steps**

1. Left list: templates with engine label and Offered state; selected row has the gold marker; '+ New template' starts an empty editor.
2. Editor: keep field ids (#tpl-e-name etc.) so specs adapt cheaply; 'Offered to people' maps to isActive; 'Longest allowed … hours (max 2,160)'.
3. 'Who can ask for one' checkboxes Users / Environment admins / Viewers map to allowedRoles USER / ENV_ADMIN / VIEWER; 'Needs approval by' maps requiresApproval + approverRole.
4. Save template validates inline (same messages as today) and refreshes the list without closing the editor.
5. Aside 'Waiting for you': each pending request '<env> · <template>', '<who> · for <ttl> · about $<cost>', the reason in quotes, Approve / Decline (Decline asks for a reason, as reject() does today), and the lifecycle sentence.
6. Non-admin approvers (env admins) see only the Requests content.

**Acceptance criteria**

- Given an admin selects a template and changes the longest allowed hours, when Save template is pressed, then the list stays and the template shows the new value after reload.
- Given 2 pending requests, then the tab reads 'Requests · 2 waiting' and both appear in the aside.
- Given an environment admin, then the Templates list and editor are not shown.

**Tests**

- test/tests/environments/templates-admin.spec.ts (UPDATE)
- test/tests/environments/templates.spec.ts (UPDATE): tab labels
- TemplateControllerTest (unchanged; run to confirm)

**Notes:** No API change: EnvironmentTemplate already has allowed_roles, approver_role, default/max TTL and estimated_monthly_cost.

## U15

### Cost pages — Admin

Cost management becomes a tabbed page (Overview, Rightsizing, Commitments, Carbon, Unusual spend, plus Setup) with two menus instead of seven buttons, and Savings, Waste with its fixes flow, and Chargeback with shared cost rules fit one screen each.

Sources: CostOverview.dc.html, Rightsizing.dc.html, Commitments.dc.html, Carbon.dc.html, Anomalies.dc.html, Savings.dc.html, Waste.dc.html, WasteFix.dc.html, Chargeback.dc.html, ChargebackRules.dc.html, CostSetup.dc.html, CostDialogs.dc.html · 9 tasks · 13.5 days

#### U15-T01 — Split Cost management into tabs and group the toolbar into Data actions and Export menus

- [ ] Done · **Type:** refactor · **Estimate:** 2 d · **Risk:** high · **Sources:** CostOverview.dc.html, CostDialogs.dc.html · **Depends on:** U04-T02, U03-T02, U02-T02

cost-management.js buildPageHtml (line 308) renders seven toolbar buttons (Backfill 30 Days, Reconcile Tags Now, Ingest Actual Costs, Export FOCUS CSV, Export PNG, Export PDF, Setup) and stacks 16 panel slots on one scrolling page, fetching every panel's keys at load (fetchAll); the mockup has tabs Overview / Rightsizing / Commitments / Carbon / Unusual spend with Setup, a 'Data actions' menu and an 'Export' menu (This page as PDF, This page as PNG, VM cost detail Excel, Month for finance FOCUS CSV…).

**Files**

- `src/main/resources/static/js/features/cost-management.js` (modify) — buildPageHtml: header copy, two menus, tab bar; new TABS map {overview:[kpi,trend,env,team,account,type,workloads], rightsizing:[rightsizing,spot], commitments:[reservations,commitments], carbon:[carbon], anomalies:[anomalies]}; fetchAll/fetchKeys (194-236) fetch only the active tab's keys; restorePageStateFromHash/syncHash (85-112) keep ?tab=; openFocusExport (756) restyled as 'Month for finance'
- `src/main/resources/static/js/ui/menu.js` (new) — small accessible menu button (aria-haspopup=menu, arrow keys, Escape) if U03-T02 did not provide one
- `src/main/resources/static/css/features/cost-management.css` (modify) — tab bar, menus, remove .cost-toolbar button row
- `src/main/resources/static/index.html` (modify) — bump ?v= (and add menu.js if new)
- `test/tests/cost/focus-export.spec.ts` (modify) — open Export menu, choose 'Month for finance, FOCUS CSV…'
- `test/tests/cost/cost-management-resilience.spec.ts` (modify) — #cost-backfill-btn is a menu item under Data actions

**Steps**

1. Header: 'Cost management' / 'Estimated from run time and AWS list prices; replaced by AWS billing figures where they exist.'
2. Data actions menu items keep their ids: #cost-backfill-btn 'Fill in the last 30 days', #cost-reconcile-tags-btn 'Write cost tags now', #cost-ingest-actuals-btn 'Fetch AWS billing now'; each confirms what it does, and items for switched-off features are hidden (U02-T02).
3. Export menu: #cost-export-pdf-btn 'This page as PDF', #cost-export-png-btn 'This page as PNG', 'VM cost detail, Excel' (vmDetailExport link), 'Month for finance, FOCUS CSV…' opening the dialog (Month select, Figures radio AWS billing / Estimated with the mockup explanations, Cancel / Download CSV).
4. Tab bar (U04-T02) with Overview, Rightsizing, Commitments, Carbon, Unusual spend; Setup as the last tab linking to #/cost-setup; tabs whose feature is off are hidden.
5. Panel-to-tab map: VM cost detail table is dropped from the page (Export only); idle table moves to Waste/Overview savings (U15-T02); reconciliation moves to Setup (U15-T05).
6. Update every cost spec that expected a panel on the first load to open #/cost-management?tab=<tab> (carbon, commitments, cost-anomalies, rightsizing-*, spot-opportunities, eks-*, spend-by-account, aws-forecast).
7. PDF/PNG export renders the active tab only.

**Acceptance criteria**

- Given an admin opens #/cost-management, then only Overview keys are requested and the toolbar shows two menu buttons.
- Given #/cost-management?tab=carbon is opened directly, then the Carbon tab is selected and its data loads.
- Given the Export menu, when 'Month for finance, FOCUS CSV…' is chosen and Download CSV pressed, then the FOCUS file for the chosen month and basis downloads.

**Tests**

- test/tests/cost/focus-export.spec.ts (UPDATE)
- test/tests/cost/cost-management-resilience.spec.ts (UPDATE)
- test/tests/cost/cost-tabs.spec.ts (NEW): tab routing, lazy fetch, menus keyboard
- all test/tests/cost/*.spec.ts that open the page (UPDATE): navigate to their tab

**Notes:** High risk because 2,120-line module and ~15 specs move together; keep panel builders untouched in this task, later tasks restyle each tab.

#### U15-T02 — Build the Overview tab: five figures, daily spend, by environment, biggest savings

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** CostOverview.dc.html · **Depends on:** U15-T01

buildKpiStrip (line 652) shows icon KPIs, the trend has only '14 days' / '18 months' (buildTrendChart, 713), and spend is split across pie/polar/stacked charts (buildEnvironmentPieOption, buildVmTypePolarBarOption, buildTeamStackedLineOption); the mockup shows Month to date (with % from AWS billing), Forecast (with AWS forecast), Idle waste, Rightsizing, Priced VMs; Daily spend with 14 days / 90 days / 18 months; a 'By environment' bar list; and 'Biggest savings to act on' (What / Where / Suggestion / Saves a month) with 'All N suggestions, Excel'.

**Files**

- `src/main/resources/static/js/features/cost-management.js` (modify) — buildKpiStrip -> five text tiles; buildTrendChart adds '90 days' (forecastRange 'short'|'90'|'long'); new buildByBreakdown() bar list with a 'By' select Environment / Team / Account / VM type replacing the env/type/team/account chart panels; new buildTopSavings() merging rightsizing + idle rows (top 3 by monthly saving)
- `src/main/resources/static/css/features/cost-management.css` (modify) — KPI tiles, horizontal bars
- `test/tests/cost/aws-forecast.spec.ts` (modify) — three range buttons
- `test/tests/cost/spend-by-account.spec.ts` (modify) — 'By: Account' select instead of #cost-chart-account

**Steps**

1. KPIs from CostSummaryDTO: totalMonthlyCost (note '<p>% from AWS billing' when actuals exist), forecast (note 'AWS forecasts $x' when captured), idleWasteMonthlyCost ('a month, <n> VMs barely used'), rightsizingPotentialSavings ('a month, <n> VMs too large'), costKnownVmCount/totalVmCount ('<n> sizes have no list price').
2. Trend: three aria-pressed buttons; 90 days uses the existing TREND_DAYS series, 14 days the short forecast, 18 months the long range; keep #cost-trend-subtitle.
3. By breakdown: horizontal bars, top 6 + 'Other'; the select remembers its value in the hash; the account option only when spend spans more than one account (as panel 'account' does today).
4. Biggest savings: top 3 rows across rightsizing and idle with plain suggestions ('r6i.4xlarge → r6i.2xlarge, CPU peaks at 22%', 'Add a schedule: idle every night'); link 'All <n> suggestions, Excel' to rightsizingExport.
5. EKS workloads panel stays at the bottom of Overview only when EKS environments exist.
6. Remove the now-unused pie/polar/stacked option builders.

**Acceptance criteria**

- Given actuals for 64% of the month, then the Month to date tile notes '64% from AWS billing'.
- Given '90 days' is pressed, then the chart shows 90 daily points and the button is aria-pressed=true.
- Given 'By: Team', then bars list teams by month-to-date cost.

**Tests**

- test/tests/cost/aws-forecast.spec.ts (UPDATE)
- test/tests/cost/spend-by-account.spec.ts (UPDATE)
- test/tests/cost/cost-overview.spec.ts (NEW): KPIs, breakdown select, top savings

**Notes:** Charts follow the dataviz rules from U03 tokens (navy series, gold forecast).

#### U15-T03 — Build the Rightsizing tab: kind and agreement filters, safe resize, Spot alongside

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Rightsizing.dc.html · **Depends on:** U15-T01

buildRightsizingTable/buildRightsizingRows (1505-1550) filter with All / Scale down / Scale up / Modernize (DIRECTIONS, 1549) and show an Apply button per row; the mockup headline '14 VMs are bigger than they need to be · $1,180 a month to save', filters 'Kind of change' (All suggestions / Smaller size / Newer generation / Graviton (ARM)) and 'Agreement' (Any source / AWS agrees), columns VM / Change / Peak CPU / memory / Saves a month / 'Resize safely…', 8 per page, and an aside 'How a safe resize works'.

**Files**

- `src/main/resources/static/js/features/cost-management.js` (modify) — buildRightsizingFilter -> two selects; kind classification (SCALE_DOWN -> Smaller size, MODERNIZE -> Newer generation, target family with 'g' generation suffix -> Graviton); agreementBadge (1576) -> 'AWS agrees' filter; applyButton (1606) label 'Resize safely…' opening the existing #rightsizingApplyModal; options toggle kept; Spot opportunities (buildSpotPanel, 503) rendered under the table as a second section
- `src/main/resources/static/js/features/rightsizing-kind.js` (new) — pure helper classifying a recommendation into kinds (testable with node --test)
- `test/tests/cost/rightsizing-options.spec.ts` (modify) — 'Modernize' button -> Kind of change select 'Newer generation'
- `test/tests/cost/rightsizing-apply.spec.ts` (modify) — open via 'Resize safely…'
- `test/tests/cost/spot-opportunities.spec.ts` (modify) — Spot section on the Rightsizing tab

**Steps**

1. Headline from summary: '<n> VMs are bigger than they need to be · $<x> a month to save'; scale-up candidates appear under 'Kind: Larger size' option only when present.
2. Columns: VM (link to VM details) + environment; '<from> → <to>' with kind and ' · AWS agrees'; 'CPU / memory peak' ('22% / 38%', memory '—' without the agent); Saves a month; 'Resize safely…'.
3. Pager 8 per page via U04-T02; Excel link (rightsizingExport).
4. Aside 'How a safe resize works' with the three steps and 'Suggestions use 14 days of CPU and memory. Savings count only once a resize has finished.'; the button is disabled with the reason when rightsizing.safe-resize.enabled is off.
5. Spot opportunities section below with its own heading when spot.pricing.enabled; no request otherwise.

**Acceptance criteria**

- Given 'Graviton (ARM)', then only rows whose target type is a Graviton family remain.
- Given 'AWS agrees', then only rows confirmed by Compute Optimizer remain.
- Given 'Resize safely…' on a locked VM, then the existing precheck message shows in the modal.

**Tests**

- src/test/js/rightsizing-kind.test.mjs (NEW): kind classification
- test/tests/cost/rightsizing-options.spec.ts (UPDATE)
- test/tests/cost/rightsizing-apply.spec.ts (UPDATE)
- test/tests/cost/spot-opportunities.spec.ts (UPDATE)

**Notes:** Instance IDs never appear here (VM names only); admins-only page anyway.

#### U15-T04 — Build the Commitments and Carbon tabs

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** low · **Sources:** Commitments.dc.html, Carbon.dc.html · **Depends on:** U15-T01

buildReservationsChart/buildCommitmentsPanel (874-960) and buildCarbonPanel (437) are chart-heavy stacked panels; the mockup Commitments tab shows four coverage/use figures with bars (Savings Plans coverage and use, RI coverage and use), 'What AWS suggests buying' (Type, Term · payment, Commit / hour, Upfront, Saves a month with risk line) and an aside 'Schedules and commitments' with the always-running floor; Carbon shows month select + Export CSV, market- and location-based totals, matched share, a six-month bar strip and a per-environment table with kg per $100.

**Files**

- `src/main/resources/static/js/features/cost-management.js` (modify) — buildCommitmentsPanel: four figures from CommitmentInsightDTO.coverage (spCoveragePercent, spUtilizationPercent, riCoveragePercent, riUtilizationPercent), recommendations table with riskFlags text, aside from alwaysOnHourlyFloor/floorVmCount/scheduledAvoidedHours/scheduledHoursCoveredValue; reservations chart dropped; buildCarbonPanel: three figures from CarbonFleetDTO (totalMbmKg, totalLbmKg, coveragePct), small trend bars, table with kgPerDollar*100
- `test/tests/cost/commitments.spec.ts` (modify) — keep #cost-commitments-floor/#cost-commitments-table ids; chart assertion removed
- `test/tests/cost/carbon.spec.ts` (modify) — summary text 'Market-based <n> kg CO₂e' and table columns

**Steps**

1. Commitments intro: 'Savings Plans and Reserved Instances bought in AWS. Read-only here: buy or change them in the AWS console. Last 30 days.'
2. Figures with bars: navy for coverage, green for use over 90%, gold below; notes 'of eligible compute spend', '<x>% of reserved hours unused'.
3. Recommendations rows; OVER_COMMIT flags print 'More than the always-running floor: schedules would waste part of it' under the row.
4. Aside: 'Schedules here stop VMs <h> h a month. That saves $<x>, but hours you've committed to are paid whether VMs run or not.' + 'Safe to commit, always running $<floor> / hour' + floor note.
5. Carbon: month select (availableMonths) + 'Export CSV' (carbonExport); intro sentence from the mockup; table rows with 'Not matched to an environment' last in italic.
6. Hide each tab when its feature is off (cost.reservations.enabled, carbon.enabled) without requests.

**Acceptance criteria**

- Given 38% Savings Plans coverage, then the first figure reads '38%' with a bar at 38%.
- Given an OVER_COMMIT recommendation, then its row shows the risk sentence.
- Given June selected on Carbon, then the table and totals reload for June.

**Tests**

- test/tests/cost/commitments.spec.ts (UPDATE)
- test/tests/cost/carbon.spec.ts (UPDATE)

#### U15-T05 — Build the Unusual spend tab and move Cost setup into the cost tabs

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Anomalies.dc.html, CostSetup.dc.html · **Depends on:** U15-T01

buildAnomaliesPanel (561) embeds CostAnomalies' table with an environment select, and cost-setup.js is a separate page titled 'Cost Setup' with checks, probes and notes; the mockup's Unusual spend tab has Period and Environment filters, columns Environment · when / Spent / Usual / Top cause / 'Was this expected?' (Unexpected / Planned / Not unusual) / Ticket, and Setup is a tab listing eight features with status pills ('Working', 'No permission' with the fix sentence, 'Off') and 'Check again'.

**Files**

- `src/main/resources/static/js/features/cost-anomalies.js` (modify) — fleet table variant: verdict segmented radios (role=radio) labelled Unexpected / Planned / Not unusual posting /cost-anomalies/{id}/feedback; ticket column ('Open a Jira issue' or the key); period 30/90 days
- `src/main/resources/static/js/features/cost-setup.js` (modify) — render inside the cost page header + tab bar (Setup selected); feature rows name / what / fix sentence / status pill from CostSetupStatusDTO.features and checks; '6 of 8 cost features work' line; #cost-setup-check-btn 'Check again'; probes and the estimate-vs-billing reconciliation table go under a 'Details' disclosure
- `src/main/resources/static/js/features/cost-management.js` (modify) — expose buildHeaderAndTabs(activeTab) for cost-setup.js; buildReconciliationTable moved to cost-setup
- `test/tests/cost/cost-anomalies.spec.ts` (modify) — 'Planned' is a radio, not a button
- `test/tests/cost/cost-setup.spec.ts` (modify) — feature rows and pills
- `test/tests/cost/jira-tickets.spec.ts` (modify) — ticket link in the new column

**Steps**

1. Unusual spend intro: 'AWS Cost Anomaly Detection, checked every 12 hours. Environment owners are told when the extra spend is $10 or more…' (threshold from the anomaly settings).
2. Rows: '<env>' and '<date range> · <n> days', spent, '+$extra', usual, top cause, verdict radios (selected = navy fill), ticket link; footer '<n> of <m> in the last 30 days' + link to Setup.
3. Keep the environment page's anomaly list (renderEnvPanel) unchanged apart from the shared verdict labels.
4. Setup tab: '<ok> of <n> cost features work · Checked AWS permissions <x> min ago' + 'Check again'; each feature row maps its denied check to 'AWS refused <action>. Ask your AWS admin to add it to the platform role.'
5. Footer: 'Switch features on or off in Settings › Cost. The IAM permissions each one needs are in the setup guide.' linking #/settings?section=cost.
6. Cost setup keeps every per-feature check with its status sentence and the 'Copy policy' button for each missing IAM permission (cost-setup-copy-policy, CostSetup.copyPolicy, cost-setup.js L147/L202).

**Acceptance criteria**

- Given an anomaly, when 'Planned' is chosen, then feedback PLANNED is stored and the radio stays selected after reload.
- Given ce:GetCostAndUsage denied, then 'AWS billing figures' shows 'No permission' and the fix sentence.
- Given #/cost-setup, then the cost tab bar shows with Setup selected.
- Given a cost feature lacks an IAM permission, when an admin opens the Setup tab, then the check says which and 'Copy policy' copies the policy JSON, as today.

**Tests**

- test/tests/cost/cost-anomalies.spec.ts (UPDATE)
- test/tests/cost/cost-setup.spec.ts (UPDATE)
- test/tests/cost/jira-tickets.spec.ts (UPDATE)

#### U15-T06 — Restyle Savings: four figures, saved by cause, efficiency trend, by environment with team

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** Savings.dc.html · **Depends on:** U03-T02, U04-T02

savings.js renders a daily stacked 'Net savings by cause' chart (#savings-cause-chart) and a 'Cost Efficiency %' trend plus breakdown tables; the mockup shows Saved net (gross before running storage and IPs), Still on the table, Cost efficiency (with the AWS median), Based on (billed share), a 'Saved by' bar list per cause, the 90-day efficiency line and a By environment table with Team, Hours off, Gross, Net.

**Files**

- `src/main/resources/static/js/features/savings.js` (modify) — header with Month select (#savings-month) and 'Export month, Excel' (savings/export); KPI tiles from SavingsSummaryDTO; 'Saved by' bars from /savings/by-cause replacing the daily stacked chart; efficiency trend kept; by-environment table adds Team (from /savings/by-environment rows' team or /savings/by-team join) and paginates 10
- `src/main/resources/static/css/features/savings.css` (modify) — tiles and green cause bars
- `test/tests/cost/savings-page.spec.ts` (modify) — #savings-cause-chart -> cause bar list; Net sort button kept

**Steps**

1. Copy: 'What stopping and resizing saved, after the storage and IP costs that keep running.'
2. Tiles: 'Saved, net' with '$<gross> before running storage and IPs'; 'Still on the table' '<x> a month, each VM counted once'; 'Cost efficiency' '<p>%' with the median note; 'Based on' 'Mostly AWS billing' / 'Estimates' from ActualsCheck.
3. Saved by: Schedules, Idle auto-stop, Leases ending, Stopped by hand, Rightsizing as green bars with values.
4. By environment: Environment (link) / Team / Hours off / Gross / Net, sortable Net, 10 per page.
5. Each panel keeps its own error + Retry.
6. Keep the two ledger actions in a header 'Data actions' menu: 'Export month, Excel' (savings-export, Config.API.savings.export, savings.js exportMonth L258) and 'Rebuild the last 30 days' (savings-backfill, backfill L268, with its confirmation).

**Acceptance criteria**

- Given October selected, then the four tiles and the cause bars show October figures.
- Given the by-environment table, then each row shows its team or '—'.
- Given the export link, then the month's Excel downloads.
- Given an admin opens Savings, then 'Export month, Excel' downloads the month's ledger and 'Rebuild the last 30 days' asks first and then rebuilds it, as today.

**Tests**

- test/tests/cost/savings-page.spec.ts (UPDATE)
- test/tests/cost/savings.spec.ts (UPDATE): export and rebuild still work from the new menu

**Notes:** If by-environment rows lack a team, add teamName to SavingsBreakdownRowDTO in this task (one join on environment.team).

#### U15-T07 — Rebuild the Waste list: type cards as filters, plain fix labels per row

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** Waste.dc.html · **Depends on:** U03-T02, U04-T02

waste.js draw/drawFilters/drawTable (106-230) shows a summary, a fix-actions card, select filters (type, region, status) and a findings table with 'Run sweep now'; the mockup shows '$318 a month across 27 open findings', pressable type cards (Everything, Unattached volumes, Unused public IPs, gp2 → gp3, Stopped over 30 days, Old snapshots) with cost and count, a table Resource / Region / Environment / Idle for / A month / Actions (e.g. 'Snapshot and delete…', 'Release…', 'Move to gp3…', Dismiss) and 'Check again now' / 'Export, Excel'.

**Files**

- `src/main/resources/static/js/features/waste.js` (modify) — load header copy with schedule ('Checked daily at 06:00; last check …'); drawFilters -> type cards from WasteSummaryDTO.byType (aria-pressed); drawTable rows with fix label per WasteFindingType; drawActions card replaced by a 'Fixes · <n> waiting' link to #/waste/fixes; #waste-sweep-btn text 'Check again now'
- `src/main/resources/static/css/features/waste.css` (modify) — type card grid
- `test/tests/cost/waste.spec.ts` (modify) — sweep button text, type card filter, Dismiss flow unchanged

**Steps**

1. Header 'Waste' / 'Things in AWS that cost money and do nothing.' with the sweep schedule and last run; buttons 'Export, Excel' (findings/export) and 'Check again now'.
2. Type cards: Everything + one per type with monthly cost and '<n> open'; pressing filters the table (status open by default; a small 'Show dismissed' toggle replaces the status select).
3. Rows: resource id + kind sentence ('Unattached volume, 500 GiB gp3'), region, environment or '—', idle for, monthly cost, fix link (opens the propose dialog from U15-T08) and 'Dismiss <id>' (keeps the prompt for a reason).
4. Footer: 'Fixes go through an approval and keep a snapshot first, so they can be undone.'; hidden when waste actions are off.
5. Pager 10 per page.

**Acceptance criteria**

- Given 'Unused public IPs' is pressed, then only IP findings show and the card is aria-pressed=true.
- Given sweeps are off, then 'Check again now' is disabled with the reason (current #waste-summary text).
- Given 'Dismiss vol-0abc' with a reason, then the row leaves the open list.

**Tests**

- test/tests/cost/waste.spec.ts (UPDATE)

**Notes:** Resource IDs (vol-, eipalloc-, i-) appear on this admin-only page, which matches the U01 rule.

#### U15-T08 — Add the Waste fixes page: propose with a reason, approve by a different admin, roll back

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** WasteFix.dc.html · **Depends on:** U15-T07, U04-T03

Fix actions are a small card inside the Waste page (waste.js drawActions, 133) and proposing takes only a type (WasteController ProposeRequest, WasteAction has no reason column); the mockup page 'Waste / Fixes' explains the flow (Proposed → Approved → Applied → Roll back), lists fixes with Proposed by, Saves a month, Status (Waiting for approval / Being applied / Done / Failed / Rolled back / Rejected) and Approve / Reject / Roll back / Details, and a 'Propose a fix' dialog with fix choice and Reason.

**Files**

- `src/main/resources/db/migration/V95__add_waste_action_reason.sql` (new) — ALTER TABLE waste_action ADD COLUMN reason VARCHAR(500) NULL (next free V number)
- `src/main/java/com/tcgdigital/vmcontrol/model/WasteAction.java` (modify) — reason field
- `src/main/java/com/tcgdigital/vmcontrol/controller/WasteController.java` (modify) — ProposeRequest (line 102) gains optional reason (max 500); passed to actionService.propose
- `src/main/java/com/tcgdigital/vmcontrol/dto/WasteActionDTO.java` (modify) — reason, proposedByName, monthlySaving
- `src/main/resources/static/js/features/waste-fixes.js` (new) — Fixes page: step strip, table with status tags and actions (approve/reject/rollback endpoints), Details shows errorMessage; propose dialog used by waste.js rows
- `src/main/resources/static/js/core/router.js` (modify) — PARAM route #/waste/fixes
- `src/main/resources/static/index.html` (modify) — add waste-fixes.js; bump ?v=

**Steps**

1. Migration + entity + DTO for reason; propose stores it and the audit entry includes it.
2. Page header with breadcrumb 'Waste / Fixes' and 'One administrator proposes a fix, a different one approves it, then the platform makes the change in AWS.'; four-step strip.
3. Table: fix + what, proposed by + when, saves a month, status tag, actions: Approve/Reject for PROPOSED when proposedBy != me, 'Another admin approves' when it's mine, 'Roll back' for EXECUTED with rollbackSupported, "Can't be undone" otherwise, Details for FAILED.
4. Propose dialog (from a Waste row): finding summary line, fix radios valid for the type (e.g. 'Delete the volume' with snapshot note, 'Change to gp3'), Reason input, note 'Another admin approves it', 'Propose fix'.
5. When waste actions are off (actions/config enabled=false) the page says so and shows history only; AWS changes still happen only after approval (existing flag stays default off).

**Acceptance criteria**

- Given admin A proposes 'Delete the volume' with a reason, then admin B sees Approve and admin A sees 'Another admin approves'.
- Given an executed gp3 change, when 'Roll back' is confirmed, then the status becomes 'Rolled back'.
- Given the reason is 600 characters, then 400 'Keep the reason under 500 characters.'

**Tests**

- WasteControllerTest (UPDATE): reason stored, length validation
- WasteActionServiceTest (UPDATE): reason in audit
- test/tests/cost/waste-fixes.spec.ts (NEW): propose, two-admin approval rule (admin-001 vs a second admin in smoke data), rollback button states

**Notes:** Migration number: next free after the U13 migrations. Needs a second admin in the smoke seed for the approval spec; add one in the spec setup via the API if the seed has only admin-001. Migration numbers V90-V95 follow task order (U01-T06 V90, U08-T01 V91, U09-T09 V92, U13-T01 V93, U13-T06 V94, U15-T08 V95); take the next free number if another migration lands first.

#### U15-T09 — Rebuild Chargeback: statement with billing share, missing-team banner, shared cost rules editor, cost tags

- [ ] Done · **Type:** feature · **Estimate:** 2 d · **Risk:** medium · **Sources:** Chargeback.dc.html, ChargebackRules.dc.html · **Depends on:** U04-T02, U03-T02

chargeback.js has Statement and Shared cost rules tabs (showTab, 88) with rules edited in a modal (openRuleForm, 307) and finalize without sending (ChargebackService.finalize; only the monthly finalizeAndSendPreviousMonth emails finance); the mockup statement has 'From AWS billing' per team, a banner '2 environments have no team, so $212 sits in Unassigned' with 'Assign teams', 'Finalise and send to finance', a 'Missing tags · N' tab, and the rules page is a master-detail editor (split Evenly / Fixed percentages / By own cost, shares with live 100% check, From month) with asides for cost with no team and VM cost tags ('47 tagged 6 missing', 'Tag the 6 now').

**Files**

- `src/main/resources/static/js/features/chargeback.js` (modify) — drawStatement (126) columns Team / Cost centre / Own environments / Share of shared / Total / From AWS billing, Unassigned and Total rows styled, banner from allocation gaps; tabs Statement / Shared cost rules / Missing tags · N; drawRules (262) -> rule list + inline editor reusing readRule/validateRule/saveRule; aside with unassigned cost and tags status (Config.API costManagement tags status / reconcile)
- `src/main/java/com/tcgdigital/vmcontrol/service/ChargebackService.java` (modify) — finalize(month, actorId, send) emails the workbook to chargeback.recipients when send=true (shared with finalizeAndSendPreviousMonth); no recipients -> finalize and return a 'not sent' flag
- `src/main/java/com/tcgdigital/vmcontrol/controller/CostManagementController.java` (modify) — POST /chargeback/{month}/finalize?send=true (line 388); response says whether it was emailed
- `src/main/java/com/tcgdigital/vmcontrol/dto/ChargebackDTO.java` (modify) — per-team actualSharePercent if not present
- `test/tests/cost/chargeback.spec.ts` (modify) — finalise button text, table columns, rule editor inline

**Steps**

1. Statement header: Month select (#chargeback-month), 'Download, Excel', 'Finalise and send to finance' (keeps the type-the-month confirmation); draft footer 'Draft. It is finalised on 1 November and emailed to finance; after that the numbers don't change.'
2. Banner when allocation gaps exist: '<n> environments have no team, so $<x> sits in Unassigned.' + 'Assign teams' (#/teams).
3. 'Missing tags · N' tab lists environments without a team and VMs without cost tags (tags/status untagged count), with 'Tag the N now' calling POST tags/reconcile (hidden when cost.tagging.enabled is off).
4. Rules: left list 'One rule per environment' (env, method · n teams, last month's cost), '+ New rule'; editor with method radio cards, shares table (team, % input, last month amount, remove), '+ Add team', live sum '100% ✓', From month, Delete rule / Save rule.
5. Finalise with send: backend emails when recipients are set; toast 'September finalised and sent to finance.' or 'September finalised. No finance address is set, so nothing was sent.'

**Acceptance criteria**

- Given a draft month, when 'Finalise and send to finance' is confirmed with recipients configured, then the status is FINAL and one email is logged.
- Given fixed percentages that sum to 95%, then Save rule is disabled and the sum reads '95%, needs 100%'.
- Given 6 VMs without tags and tagging on, when 'Tag the 6 now' is pressed, then reconcile runs and the count refreshes.

**Tests**

- ChargebackServiceTest (UPDATE): finalize with send, no recipients
- ChargebackControllerIntegrationTest (UPDATE): send flag
- test/tests/cost/chargeback.spec.ts (UPDATE)

**Notes:** Cost tag writes change AWS tags: they stay behind cost.tagging.enabled (default false).

## U16

### Phone — Finish

On a 390 px phone people can see their environments, start/stop/extend, approve and check activity through a phone top bar, a four-item bottom bar and a full-screen menu, with no horizontal scrolling anywhere.

Sources: PhoneHome.dc.html, PhoneEnv.dc.html, PhoneMenu.dc.html, ChatAction.dc.html · 5 tasks · 7.5 days

#### U16-T01 — Build the phone shell: compact top bar, four-item bottom bar and a full-screen menu

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** PhoneHome.dc.html, PhoneMenu.dc.html · **Depends on:** U04-T01, U03-T02

Under 768 px css/layout/mobile.css turns #sidebar into an off-screen drawer (sidebar.js toggleDrawer, PHONE_QUERY) and index.html line 633 has a bottom bar Dashboard / Environments / Activity / More with Font Awesome icons. The mockup phone top bar has the logo, the bell with its count and the initials avatar; the bottom bar reads Home / Environments / Activity / More; More opens a full-screen menu with the user's name and role, the same sections as the desktop side menu with count badges (Approvals 2, Access requests 3), and Help and Sign out at the bottom.

**Files**

- `src/main/resources/static/index.html` (modify) — Bottom bar labels Home / Environments / Activity / More with inline SVG icons (U03 icon set), aria-label 'Main'; the More button controls the phone menu
- `src/main/resources/static/css/layout/mobile.css` (modify) — Rewrite the <=768 px block: 56 px top bar (logo, bell, avatar only; sync indicator and hamburger hidden), 64 px bottom bar with safe-area padding, full-screen menu sheet (header with initials, name, role, close), 44 px rows, badges; remove the 769-991 px 'tablet sidebar' rules if U04-T01 covers that width
- `src/main/resources/static/js/ui/sidebar.js` (modify) — Phone menu = the same #sidebar markup shown full-screen (no second nav to keep in sync); header injected with the user name/role; focus trap, Escape and close button; bottom-bar active item logic (line 218) maps routes to Home/Environments/Activity/More
- `src/main/resources/static/js/ui/user-menu.js` (modify) — On phone the avatar opens My account directly (no dropdown)

**Steps**

1. Replace bottom-bar markup and icons; keep #mobile-more-btn id for existing specs.
2. Restyle #sidebar.mobile-open as a full-screen sheet with the user header, section titles (Workspace, Manage, Cost, Administration as the role allows) and Help / Sign out pinned at the bottom.
3. Show the Approvals and Access requests counts as badges in the sheet (reuse the existing pending-count polls; no new requests).
4. Top bar on phone: logo, bell (opens the notifications dialog full-width), avatar (opens My account full-screen).
5. Update phone-navigation.spec.ts for the new labels and the full-screen menu.

**Acceptance criteria**

- Given a 390x844 viewport, then the top bar shows only logo, bell and avatar, and the bottom bar shows Home, Environments, Activity, More.
- Given More is tapped, then a full-screen menu shows the user's name and role, the role's sections and Help and Sign out; Escape or Close returns focus to More.
- Given an environment admin with 3 pending access requests, then 'Access requests' shows a 3 badge in the menu.

**Tests**

- test/tests/mobile/phone-navigation.spec.ts (UPDATE): labels, full-screen menu, badges, focus return

**Notes:** Built after U06-U15 so the menu reflects the final side menu.

#### U16-T02 — Lay out Home for the phone: summary line, lease alert and environment cards with large strips

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** PhoneHome.dc.html · **Depends on:** U03-T03, U05-T03, U16-T01

The phone layout today just narrows the desktop dashboard (mobile.css .metric-card and .content-header rules). The phone Home mockup shows 'Home', a one-line summary ('8/24 VMs running · $412.80 this month'), a gold alert row for the nearest lease end with 'Extend', then one card per environment with name, state chip, a large power strip and one status line (lease end, lock holder, next schedule).

**Files**

- `src/main/resources/static/js/features/dashboard.js` (modify) — Phone variant of the Home renderer chosen with matchMedia('(max-width: 768px)') and re-rendered on change: summary line, nearest lease alert, cards (data already loaded for desktop Home; no extra requests)
- `src/main/resources/static/css/features/dashboard.css` (modify) — Phone card styles (16 px gutter, 44 px minimum tap height, large strip)

**Steps**

1. Reuse the Home data from the U06 Home tasks (environment summary from U05-T03, leases); add a phone render function.
2. Summary line from running/total VMs and month-to-date cost; hide the cost part for viewers if desktop Home hides it.
3. Lease alert: the earliest active lease ending within 4 h, 'Extend' opens the lease extend dialog in a bottom sheet.
4. Cards: whole card is one link to the environment; chip Running/Starting/Stopped; status line priority lease > lock > schedule.
5. Extend phone-layout.spec.ts: Home fits 390 px width, cards are links with names.

**Acceptance criteria**

- Given a phone viewport and three environments, then Home shows the summary line and three cards each with a large strip and one status line.
- Given a lease ends at 20:00 within 4 hours, then a gold row 'Payments Dev stops at 20:00 · Extend' sits above the cards.

**Tests**

- test/tests/mobile/phone-layout.spec.ts (UPDATE): Home phone layout, no horizontal scroll

**Notes:** Depends in practice on the U06 Home tasks (desktop Home) landing first.

#### U16-T03 — Lay out the environment page for the phone: state, two big actions, details and collapsible groups

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** PhoneEnv.dc.html · **Depends on:** U03-T03, U05-T01, U02-T02, U16-T01

On a phone the environment page reuses desktop markup squeezed by mobile.css (phone-layout.spec only checks that Start/Stop buttons stay on screen). The PhoneEnv mockup: header with back arrow, environment name and 'History'; a state section ('5 of 7 VMs running', large strip with group labels underneath, 'Start the 2 stopped' primary and 'Stop all'); details rows Lease (stops 20:00 · Extend), Lock (Not locked · Lock), This month cost; then one collapsible row per group with its small strip that expands to its VMs.

**Files**

- `src/main/resources/static/js/features/environments.js` (modify) — Phone render path for the environment detail (matchMedia), reusing the loaded environment/groups/VMs and the existing operation, lease and lock actions
- `src/main/resources/static/css/features/environments.css` (modify) — Phone section styles; group rows are buttons with aria-expanded; VM rows inside a group show name, state and a menu for VM actions
- `src/main/resources/static/css/layout/mobile.css` (modify) — Phone page header (back, title, History) pattern shared with other detail pages

**Steps**

1. Add the phone header: back goes to the previous route or Environments; History opens the environment's operation history.
2. State section: running count, PowerStrip large with group labels, primary action adapts ('Start the N stopped' / 'Start all' / 'Stop all') and goes through the same start confirm dialog (as a bottom sheet) as desktop.
3. Details rows with inline links to the existing extend-lease and lock dialogs; cost row only when cost is visible to the role.
4. Group rows collapsed by default; expanding shows that group's VMs (10 at a time with 'Show more').
5. Viewers see the same page without action buttons (follow server capability flags, U02-T02).
6. Update phone-layout.spec.ts 'Environment Detail keeps every Start/Stop button on screen' for the new layout.

**Acceptance criteria**

- Given an operator on a phone with 2 stopped VMs, then 'Start the 2 stopped' and 'Stop all' are visible without scrolling and open the start/stop confirm.
- Given a group row is tapped, then it expands (aria-expanded=true) and lists its VMs.
- Given a viewer, then no start/stop/lock/extend control is shown.

**Tests**

- test/tests/mobile/phone-layout.spec.ts (UPDATE): environment phone layout for operator and viewer

**Notes:** Depends in practice on the U07/U08 environment page tasks. Instance IDs stay hidden for non-admins on phone too (U01).

#### U16-T04 — Fit the everyday screens to the phone: requests, approvals, activity, account, notifications, Ask, Teams confirm

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** RequestAccess.dc.html, Approvals.dc.html, MyActivity.dc.html, MyAccount.dc.html, Notifications.dc.html, AskPanel.dc.html, ChatAction.dc.html · **Depends on:** U09-T02, U09-T03, U09-T04, U09-T05, U09-T06, U09-T08, U16-T01

The U09 screens are desktop master-detail and table layouts. On a phone the Request access list and form must stack (list, then form on its own screen with Back), My activity rows must become cards (table-cards.js already turns tables into labelled cards), side panels (My account, Ask) and the notifications dialog must be full-screen sheets, and the Teams confirm page (opened from Teams mobile) must work one-handed.

**Files**

- `src/main/resources/static/css/layout/mobile.css` (modify) — Shared rules: side panel and dialogs full-screen under 768 px, master-detail stacks, period/segment controls scroll inside themselves, 44 px targets
- `src/main/resources/static/js/features/access-requests.js` (modify) — On phone picking an environment shows the form as its own view with a Back button
- `src/main/resources/static/js/features/activity-logs.js` (modify) — Rows rendered through TableCards (js/ui/table-cards.js) with data-label for Environment and Result
- `src/main/resources/static/css/features/access-requests.css` (modify) — Phone stacking
- `src/main/resources/static/css/features/my-account.css` (modify) — Panel full-screen, tabs scroll horizontally inside the tab bar only

**Steps**

1. Add the shared full-screen sheet rules for the U04-T02 side panel and dialogs.
2. Request access: list view -> form view with Back; Your requests rows stack action under text.
3. Approvals: card facts stack; Approve full-width, Deny… beside it.
4. My activity: cards via TableCards; day separators stay; filters collapse into one 'Filters' button opening a sheet.
5. Notifications, My account, Ask: full-screen with a close button in the header; the Ask input sticks to the bottom above the keyboard.
6. Teams confirm: chips full width, primary action at the bottom.
7. Add each route/panel to phone-layout.spec.ts no-horizontal-scroll loop.

**Acceptance criteria**

- Given a 390 px viewport, when each of request-access, approvals, activity-logs, help, templates and chat-action is opened, then document width equals viewport width (no horizontal scroll).
- Given My account is opened on a phone, then it covers the screen and Close returns focus to the avatar.
- Given Request access on a phone, when an environment is tapped, then the form shows on its own with Back to the list.

**Tests**

- test/tests/mobile/phone-layout.spec.ts (UPDATE): everyday routes and panels at 390x844 for user-001 and dev-user-001

#### U16-T05 — Make the manage and admin pages usable on a phone and lock it in with a per-persona phone sweep

- [ ] Done · **Type:** test · **Estimate:** 2 d · **Risk:** medium · **Sources:** PhoneMenu.dc.html, Crawl audit: page fit · **Depends on:** U04-T02, U16-T01

phone-layout.spec.ts checks a fixed list of admin tables; most manage/admin pages (registry, access management, automation, budgets, cost, users, settings) were built for desktop and the new paginated tables/master-detail layouts from U10-U15 need phone rules. The brief requires no horizontal scrolling and readable tables: tables become labelled cards, master-detail stacks, dialogs become sheets.

**Files**

- `src/main/resources/static/js/ui/table-cards.js` (modify) — Apply card mode to every .table-baseline table under 768 px (data-label from the header cells), including tables rendered after load
- `src/main/resources/static/css/layout/mobile.css` (modify) — Generic stacking for U04-T02 master-detail and side columns; pager compact form ('2 / 5')
- `test/tests/mobile/phone-layout.spec.ts` (modify) — Loop over every route in the router for each persona (admin-001, dev-user-001, user-001, user-002) at 390x844: no horizontal overflow, no element wider than the viewport, tap targets >= 44 px for buttons and links in content

**Steps**

1. Write the sweep first (route list from ContentRouter's route table) to get the failure list.
2. Fix overflow page by page with shared rules first (tables, toolbars, filter bars, charts with width: 100%).
3. Charts (ECharts) resize on orientation change (window resize already disposes/recreates? check charts.js and call resize).
4. Admin dialogs with wide forms become single-column sheets.
5. Remove page-specific phone hacks made obsolete by the shared rules.

**Acceptance criteria**

- Given any persona and any route the persona may open, when viewed at 390x844, then there is no horizontal scroll and no clipped primary action.
- Given a paginated table on a phone, then each row is a card whose fields are labelled.

**Tests**

- test/tests/mobile/phone-layout.spec.ts (UPDATE): full route x persona sweep with overflow and tap-size checks

**Notes:** Run after U10-U15 land. Pages the persona cannot open show the U02-T01 no-access page, which must also fit.

## U17

### States, copy, accessibility & clean-up — Finish

Every page shows the same empty, loading, error, toast, reconnecting and signed-out patterns with plain copy, passes an axe scan on every screen, matches the mockup at 1440x900 without page scrollbars, and carries no leftover code from the old look.

Sources: States.dc.html, NoAccess.dc.html, StyleGuide.dc.html, Crawl audit: blank pages, unnamed switches, failing background requests, UAT L18 axe findings · 9 tasks · 13.5 days

#### U17-T01 — Add one shared state helper for empty, filtered-empty, loading outlines and couldn't-load

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** States.dc.html · **Depends on:** U03-T02

Every feature builds its own empty and error markup (ra-empty-state, ma-empty, dashboard-empty-small, vm-empty-state, access-empty-panel; Retry panels in activity-logs.js 531, approvals.js 87, budgets.js 97, chargeback.js 133/267, waste.js 103, teams.js 71 and more), and js/ui/loading.js has skeleton helpers that show immediately. The States board defines four patterns: 'nothing yet' (title, one sentence, one action), 'filters hide everything' (says which filter and offers to clear it), loading outlines that appear only after 300 ms and match the layout, and 'couldn't load' ('VMs didn't load. The server didn't answer. The rest of the page still works.' + Try again + a short ref). Error bodies from GlobalExceptionHandler have no reference to quote.

**Files**

- `src/main/resources/static/js/ui/states.js` (new) — UiStates.empty({title, text, action}), UiStates.filtered({title, text, clearLabel, onClear}), UiStates.loading(container, shape) with a 300 ms delay and aria-busy, UiStates.failed({what, xhr, onRetry}) that reads the ref from the error body; all text escaped
- `src/main/resources/static/css/components/states.css` (new) — Styles for the four patterns (outline blocks #EEF1F5, centred empty block with the three-light glyph); prefers-reduced-motion honoured
- `src/main/resources/static/index.html` (modify) — Add states.js after loading.js and states.css after loading.css; bump ?v=
- `src/main/java/com/tcgdigital/vmcontrol/controller/GlobalExceptionHandler.java` (modify) — For 5xx responses generate a short ref (8 hex chars), log it with the stack trace and return it as 'ref' in the body
- `src/main/resources/static/js/ui/loading.js` (modify) — showTableSkeleton/showCardSkeleton delegate to UiStates.loading so the delay rule applies everywhere

**Steps**

1. Write UiStates with the four builders returning HTML strings plus a mount helper that wires the action buttons through Actions (no inline handlers, CSP-safe).
2. Loading: render nothing for 300 ms, then outlines; cancel the timer when data arrives.
3. Failed: message 'X didn't load.' + reason by status (0: 'The server didn't answer.', 403: 'You can't see this.', 5xx: 'Something failed on the server.') + 'The rest of the page still works.' + Try again + 'ref <id>' when present.
4. Backend ref in GlobalExceptionHandler's generic handler; unit test.
5. Node test for the builders (escaping, delay timer, ref display).
6. Adopt it on the U09 pages (Request access, Approvals, My activity, Help, Templates) in this task as the reference usage.

**Acceptance criteria**

- Given a request that answers within 300 ms, then no loading outline flashes.
- Given a 500 error with ref 7f3a92c1, then the panel shows 'didn't load', Try again and 'ref 7f3a92c1', and the server log has the same ref.
- Given a filter that hides every row, then the message names the filter and offers to show all.

**Tests**

- src/test/js/ui-states.test.mjs (NEW): builders escape text, 300 ms delay, ref rendering
- GlobalExceptionHandler test (NEW or UPDATE): 5xx body has ref, 4xx does not

#### U17-T02 — Replace every page's own empty, loading and retry markup with the shared states

- [ ] Done · **Type:** refactor · **Estimate:** 2 d · **Risk:** medium · **Sources:** States.dc.html, Crawl audit: blank pages instead of no-access · **Depends on:** U17-T01

After U17-T01 the per-feature variants remain in ~20 modules (grep for 'Retry</button>', '-empty', 'fa-spinner fa-spin' in js/features). The crawl audit also found blank pages where a load failed silently. One pass moves every list and panel onto UiStates so the messages and behaviour are the same everywhere.

**Files**

- `src/main/resources/static/js/features/*.js` (modify) — dashboard, environments, vm-registry, access-management, access-requests (admin side), user-management, teams, automation-rules, approval-policies, budgets, savings, waste, chargeback, cost-management, cost-setup, env-cost, system-health, settings, all-logs, aws-accounts, api-tokens, webhooks: swap local empty/error/spinner markup for UiStates; remove local helpers (system-health.js emptyState(), cost-management.js retryPanel())
- `src/main/resources/static/css/features/*.css` (modify) — Delete the now unused *-empty* and retry panel rules

**Steps**

1. List every occurrence (rg 'Retry</button>|empty-state|-empty|fa-spinner fa-spin' js/features) and record it in the PR description.
2. Convert module by module; each empty state gets a sentence that says what to do and at most one action.
3. Make sure each page's top-level load failure renders UiStates.failed (no blank content area).
4. Delete local helpers and CSS rules that no longer have users.
5. Update specs that look for the old texts (e.g. 'Retry' buttons -> 'Try again').

**Acceptance criteria**

- Given any page whose main request fails (route intercepted to 500), then the content area shows the couldn't-load state with Try again, never a blank area.
- Given rg 'Retry</button>' in js/features, then there are no matches.

**Tests**

- test/tests/navigation/status-widgets.spec.ts (UPDATE) or test/tests/navigation/load-failures.spec.ts (NEW): intercept each route's main request with 500 and expect the failed state
- Existing specs using 'Retry' texts (UPDATE)

**Notes:** Run after U06-U15 so the new pages are converted once, not twice.

#### U17-T03 — Make toasts say what happened with an optional action, stay 6 s, keep errors until closed, and add the partly-done message

- [ ] Done · **Type:** feature · **Estimate:** 1 d · **Risk:** low · **Sources:** States.dc.html · **Depends on:** U03-T02, U17-T01

Notifications.show() (js/ui/notifications.js line 60) defaults to 3 s (success/info), 4 s (warning) and 5 s (error); showError() adds Retry/Help buttons and 8 s. The States board says: bottom right, 6 s, errors stay until closed, a coloured bar by kind and one optional action link ('Watch', 'Details'); plus a 'Partly done' alert ('Started 5 of 6 VMs. pay-api-02 didn't start: …' with 'Retry pay-api-02' and 'See steps').

**Files**

- `src/main/resources/static/js/ui/notifications.js` (modify) — show(message, type, duration, options): default 6000 for success/info/warning, 0 (stay) for error; options.action {label, onClick | href}; showError() becomes a thin wrapper; pause the timer while hovered or focused
- `src/main/resources/static/css/components/toasts.css` (modify) — Left colour bar (green success, navy info, gold warning, red error), action link style, max width 420 px
- `src/main/resources/static/js/ui/states.js` (modify) — UiStates.partial({done, total, failures: [{name, reason}], onRetry, stepsHref}) role=alert block
- `src/main/resources/static/js/features/vm-operations.js` (modify) — Use UiStates.partial when an operation ends PARTIALLY_COMPLETED / with failed steps

**Steps**

1. Change defaults and add the action option; callers that pass explicit short durations are reviewed and mostly dropped.
2. Hover/focus pauses auto-dismiss (WCAG 2.2.1).
3. Partly-done block on the operation result.
4. Update src/test/js/notifications-modals-text.test.mjs (action label escaped, error stays) and test/tests/navigation/toasts-modals.spec.ts.

**Acceptance criteria**

- Given a success toast, then it disappears after 6 s unless hovered or focused.
- Given an error toast, then it stays until closed and has role=alert.
- Given an environment start where 1 of 6 VMs fails, then the result shows 'Started 5 of 6 VMs.' with the VM's reason, a retry for that VM and 'See steps'.

**Tests**

- src/test/js/notifications-modals-text.test.mjs (UPDATE): action option, durations
- test/tests/navigation/toasts-modals.spec.ts (UPDATE)

#### U17-T04 — Show 'Reconnecting…' under the top bar and a 'You've been signed out' dialog instead of a toast and a jump

- [ ] Done · **Type:** feature · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** States.dc.html, Main.dc.html · **Depends on:** U04-T01, U03-T02

RealTime.pollGet() (js/core/realtime.js line 83) ignores network failures and only reacts to 401 with a sticky warning toast ('Your session has expired. Reload the page…'); showConnectionStatus() (line 218) is exported but never called. ApiClient (js/core/api-client.js line 147) shows an error toast on 401 and redirects to /login.html after 2 s, losing anything typed. The States board: after 10 s without a successful live update show a role=status strip under the top bar ('Reconnecting… VM lights may be a minute behind.') that clears itself; on session end show an alertdialog 'You've been signed out' ('After 60 minutes without activity. Sign in again to carry on where you were.') with 'Sign in again'.

**Files**

- `src/main/resources/static/js/core/realtime.js` (modify) — pollGet(): track lastSuccessAt; on status 0/502/503/504 failures for >10 s show the reconnect strip, hide on the next success; replace showConnectionStatus() with showReconnecting(bool); onSessionLost() opens the signed-out dialog
- `src/main/resources/static/js/core/api-client.js` (modify) — 401 handler: no toast and no timed redirect; call RealTime/SessionEnded.show() once; keep vmcontrol.intendedRoute
- `src/main/resources/static/js/ui/session-ended.js` (new) — Alert dialog (role=alertdialog, focus on 'Sign in again', not dismissable by Escape) that sends the user to /login.html?expired with the hash saved; text uses the idle timeout from Config (60 min default)
- `src/main/resources/static/css/components/loading.css` (modify) — Remove #connection-status rules (lines ~130-160); add .reconnect-strip under the fixed top bar
- `src/main/resources/static/index.html` (modify) — Add the strip container under the top bar and session-ended.js; bump ?v=

**Steps**

1. Add the strip markup (role=status, hidden) under the top bar; content area offset does not change (strip overlays the top of content).
2. Track poll successes/failures in RealTime; 10 s threshold; first success hides the strip.
3. Build the signed-out dialog and route both RealTime.onSessionLost and ApiClient 401 to it (single instance).
4. login.html?expired message comes from U09-T01; after sign-in the saved route is reopened (existing intendedRoute logic).
5. Remove the dead showConnectionStatus code and CSS.
6. Specs: intercept polls with aborted requests for 11 s -> strip visible, then allow -> strip gone; intercept with 401 -> dialog.

**Acceptance criteria**

- Given polls fail with network errors for more than 10 s, then 'Reconnecting… VM lights may be a minute behind.' shows under the top bar and goes away after the next successful poll.
- Given any request answers 401, then the signed-out dialog appears once, focus is on 'Sign in again', and no timed redirect happens.
- Given the user signs in again from the dialog, then they return to the page they were on.

**Tests**

- test/tests/auth/session.spec.ts (UPDATE): 401 -> dialog, return to route
- test/tests/navigation/reconnect.spec.ts (NEW): strip appears after 10 s of failed polls and clears
- test/tests/navigation/background-polls.spec.ts (UPDATE) if it asserted the old toast

#### U17-T05 — Do a copy pass over every screen and server message

- [ ] Done · **Type:** fix · **Estimate:** 1.5 d · **Risk:** low · **Sources:** States.dc.html, StyleGuide.dc.html, Brief: copy rules · **Depends on:** U17-T02

Copy still mixes Title Case and old names ('My Activity Logs', 'Request Environment Access', 'Available Environments', 'Request Pending'), says 'successfully' and 'Please' ('Activity logs exported successfully', 'Your session has expired. Please log in again.' in api-client.js ERROR_MESSAGES line 16), and some server messages are vague. The brief: sentence case, verbs that say what happens, errors say what happened and what to do, no apologies, no gendered pronouns.

**Files**

- `src/test/js/copy-lint.test.mjs` (new) — Scans static/js/**/*.js and index.html string literals for banned patterns: /\b(please|sorry|successfully|oops)\b/i, Title Case headings in <h1>-<h3>, ' his | her | he | she ' in UI strings, 'Are you sure' (dialogs must name the action); an allowlist file for justified exceptions
- `src/main/resources/static/js/core/api-client.js` (modify) — ERROR_MESSAGES rewritten (401 'You've been signed out.', 403 'You can't do this here. Ask the environment admin for access.', 0 'The server didn't answer. Check your connection and try again.')
- `src/main/resources/static/js/features/*.js` (modify) — Headings, buttons, toasts and dialog texts fixed per the lint and a manual read
- `src/main/java/com/tcgdigital/vmcontrol/**/*.java` (modify) — User-facing exception messages (ValidationException/ConflictException texts surfaced in toasts) reviewed for what-happened + what-to-do; test assertions on exact texts updated

**Steps**

1. Write the copy lint test with an allowlist; run it to get the list.
2. Fix the front-end strings (headings sentence case, button verbs such as 'Start all', 'Save access', confirm dialogs naming the action and the target).
3. Review server messages users see (grep 'new ValidationException("' and 'new ConflictException("'), rewrite vague ones; update Java tests asserting them.
4. Check that no copy uses gendered pronouns for people.
5. Update Playwright specs that match the changed texts.

**Acceptance criteria**

- Given node --test, then copy-lint passes with an allowlist of at most a handful of justified entries.
- Given any error toast from a server 4xx, then it states what happened and what to do next.

**Tests**

- src/test/js/copy-lint.test.mjs (NEW)
- Java tests asserting exception messages (UPDATE)
- Playwright specs matching old texts (UPDATE)

**Notes:** Uses the design:ux-copy checklist as the reference. Run after all screen epics so new copy is included.

#### U17-T06 — Scan every screen, panel and dialog with axe for each persona and fix what it finds

- [ ] Done · **Type:** test · **Estimate:** 2 d · **Risk:** medium · **Sources:** UAT L18 axe findings, Crawl audit: ~45 unnamed switches · **Depends on:** U17-T02, U16-T05

Axe runs only in test/tests/a11y/contrast.spec.ts (color-contrast rule on 4 pages) and names-and-targets.spec.ts; test/uat/08-axe-detail.uat.ts (untracked) ran a full scan against staging and found the L18 issues. The redesign changes every screen, so a full WCAG 2.2 AA scan over all routes, the side panels (My account, Ask), the notifications dialog, the shortcuts dialog, the start confirm and the phone layout is needed, with findings fixed.

**Files**

- `test/tests/a11y/axe-all-screens.spec.ts` (new) — For each persona (admin-001, dev-user-001, user-001, user-002) and each route from ContentRouter's table (plus seeded environment and VM pages): AxeBuilder with tags wcag2a, wcag2aa, wcag21aa, wcag22aa; also opens My account (each tab), notifications, Ask, shortcuts, start confirm; repeats at 390x844 for the everyday routes; fails on serious/critical, reports moderate
- `test/tests/a11y/contrast.spec.ts` (delete) — Covered by the full scan
- `test/fixtures/axe.ts` (new) — Shared helper: run axe, format violations, known-exception list with reasons
- `src/main/resources/static/**` (modify) — Fixes for the findings (names, roles, landmarks, focus order, contrast)

**Steps**

1. Write the shared helper and the sweep; seed one environment with groups and VMs (fixtures/seed.ts).
2. Run it, triage findings by rule, fix shared components first (U03 components, U04 shell), then pages.
3. Check keyboard: every panel/dialog traps focus and returns it (extend slideout-dialog.spec.ts if a new panel type exists).
4. Keep an exceptions list only for third-party markup (ECharts canvas) with reasons.
5. Run the uat axe script against staging once and attach the result.

**Acceptance criteria**

- Given the sweep runs on the smoke app, then there are no serious or critical axe violations on any route, panel or dialog for any persona.
- Given any switch or checkbox, then it has an accessible name that says what it controls.

**Tests**

- test/tests/a11y/axe-all-screens.spec.ts (NEW)
- test/tests/a11y/slideout-dialog.spec.ts (UPDATE) for new panels

#### U17-T07 — Compare each screen with its mockup board at 1440x900 and enforce no page scrollbars

- [ ] Done · **Type:** test · **Estimate:** 1.5 d · **Risk:** low · **Sources:** canvas.json, all *.dc.html boards, Brief: no page scrollbars · **Depends on:** U04-T02, U17-T02

The approved mockup (74 boards, canvas.json maps file -> title -> page) is the target, and the brief's rule is no horizontal or vertical page scrollbar at 1440x900. test/ux-review/seed-and-shoot.mjs (untracked) already seeds data and takes screenshots; nothing compares them to the boards or checks page scroll.

**Files**

- `docs/design/mockup/` (new) — Check in the approved mockup boards (*.dc.html, canvas.json, support.js and images) so the comparison is reproducible
- `test/ux-review/compare-mockup.mjs` (new) — Renders each board and the matching app route (map file, persona per board's role prop) at 1440x900, writes side-by-side PNGs and an HTML report to test-results/mockup-compare/
- `test/ux-review/board-routes.json` (new) — Board -> route + persona + ready selector + setup (open panel/dialog)
- `test/tests/navigation/fit-to-screen.spec.ts` (new) — For each route and persona at 1440x900: document.scrollingElement scrollHeight <= innerHeight and scrollWidth <= innerWidth with seeded data (15 environments, 50+ VM environment)
- `test/ux-review/seed-and-shoot.mjs` (modify) — Check in (currently untracked) and reuse its seeding

**Steps**

1. Check in the mockup under docs/design/mockup (fonts come from the app's self-hosted copies, not Google).
2. Write board-routes.json for every board that has an app equivalent (building-block boards like PowerStrip, StyleGuide map to the style guide page if any, else skipped).
3. Write compare-mockup.mjs producing the report; review it and list deviations as fixes or accepted differences in the PR.
4. Write fit-to-screen.spec.ts with seeded scale data; fix pages that scroll.
5. Fix the deviations that are bugs (spacing, order, missing elements).

**Acceptance criteria**

- Given the seeded smoke app, when fit-to-screen.spec runs, then no route for any persona has a page scrollbar at 1440x900.
- Given compare-mockup runs, then the report lists every board with its app screenshot and each listed deviation is fixed or marked accepted.

**Tests**

- test/tests/navigation/fit-to-screen.spec.ts (NEW)
- test/ux-review/compare-mockup.mjs (NEW, manual report)

**Notes:** Pixel diffs are not meaningful (mock data differs); the report is side-by-side for review. The mockup's Google font links are replaced by local font files when checked in (no Google CDN).

#### U17-T08 — Remove the old look's dead pages, CSS and JS

- [ ] Done · **Type:** refactor · **Estimate:** 1.5 d · **Risk:** medium · **Sources:** Brief: clean-up, git stash@{0} visual-refresh-draft (read only) · **Depends on:** U03-T04, U17-T02

static/home.html (marked 'DEPRECATED - LEGACY MOCKUP FILE') and home-original.html are unused prototypes kept only by a LEGACY set in src/test/js/actions.test.mjs line 110; css/index.css is a documentation-only file not linked by index.html; RealTime.showConnectionStatus() and #connection-status CSS are unused (if U17-T04 has not removed them); after the redesign many feature selectors (.ra-*, .ma-stat, old card/badge overrides) and Font Awesome icons may have no users.

**Files**

- `src/test/js/actions.test.mjs` (modify) — Drop the LEGACY set (line 110)
- `src/test/js/unused-css.test.mjs` (new) — Lists class selectors in static/css/**/*.css that appear in no js/html file (string or template literal); fails on new unused selectors beyond an allowlist (Bootstrap state classes, dynamic prefixes)
- `src/main/resources/static/css/**/*.css` (modify) — Delete unused rules found by the test
- `src/main/resources/static/vendor/fontawesome` (modify) — If U03 replaced all icons with the SVG set, remove the Font Awesome link and vendor folder; otherwise list remaining uses

**Steps**

1. css/index.css was removed in U03-T04. Do not delete home.html, home-original.html or static/old_backup/ (owner decision, PL-09); leave actions.test.mjs LEGACY set as is.
2. Write the unused-selector test; run it and delete dead rules feature by feature.
3. Grep for exported but unused JS functions in core/ui modules (e.g. Loading.showMetricsSkeleton, RealTime.showConnectionStatus) and remove them.
4. Remove Bootstrap overrides that U03-T04 made obsolete; confirm the bundle (buildFrontend) still builds and both bundle sizes went down.
5. Full Playwright run to catch anything still referenced.

**Acceptance criteria**

- Given ./gradlew build and node --test, then they pass, and home.html, home-original.html and static/old_backup/ are still present.
- Given unused-css.test.mjs, then it passes and dist/app.css is smaller than before the task.

**Tests**

- src/test/js/unused-css.test.mjs (NEW)
- src/test/js/actions.test.mjs (UPDATE)
- Full Playwright suite (RUN)

**Notes:** The stash@{0} draft is only read for reference; never applied or dropped here.

#### U17-T09 — Update help articles and docs for the new screens, then run and fix the whole e2e suite

- [ ] Done · **Type:** test · **Estimate:** 1.5 d · **Risk:** low · **Sources:** Help.dc.html, Brief: docs/help text updates, final e2e pass · **Depends on:** U17-T05, U17-T06, U17-T08

help-content.js articles still describe old names and paths (e.g. 'Open Request Access and choose…', 'Check My Account > Access', 'VM registry under Needs attention'), the docs in docs/ (cost-management-setup.md, provisioning.md, ai-assistant.md, webhooks.md) and CLAUDE.md describe the old UI, and test/README.md lists old specs. After all epics the full Playwright suite (88 specs) must pass against the smoke app for every persona.

**Files**

- `src/main/resources/static/js/features/help-content.js` (modify) — Rewrite every article body to the new page names, menu sections and flows (Request access tabs, My account › Access, power strip, Approvals, start confirm), plain copy rules
- `docs/*.md` (modify) — Screen names, menu paths and screenshots updated where the UI is described
- `CLAUDE.md` (modify) — Frontend section: new shared modules (states.js, session-ended.js, power strip), fit-to-screen and axe specs
- `test/README.md` (modify) — Spec list and how to run the axe, fit-to-screen and mockup comparison checks
- `test/tests/**/*.spec.ts` (modify) — Fix remaining failures from old selectors/texts

**Steps**

1. Walk every help topic against the live app and rewrite the bodies; check HelpLink topics per page still match (help-links.spec).
2. Update docs and CLAUDE.md where they name screens or menu paths.
3. Run ./gradlew test, node --test and the full Playwright suite for all personas; fix failures (no skips added without a reason in the spec).
4. Run graphify update . so the knowledge graph reflects the redesign.
5. Record the run summary (passed/failed/skipped counts) in the PR.
6. Parity check: list every data-action / data-action-change in today's front end (171 at planning time: grep -rhoE 'data-action(-change)?="[a-z0-9-]+"' static/js) and confirm each one still has a control on the new screens, or was knowingly replaced (record the mapping in the PR description).

**Acceptance criteria**

- Given the help page, then no article mentions a page name or menu path that no longer exists.
- Given the smoke app, when the full Playwright suite runs, then every spec passes or is skipped only for an environment reason stated in the spec.
- Given the parity list from before the redesign, then every control maps to a control on the new screens or to a documented replacement; none is lost silently.

**Tests**

- test/tests/navigation/help.spec.ts and help-links.spec.ts (UPDATE)
- Full Playwright suite, ./gradlew test, node --test (RUN)

## UPL

### Parking lot — Parked

Ideas and decisions the product owner deferred. Not scheduled: each item is picked up only after it is discussed.

#### UPL-01 — LLM keys for each environment's agentic plane (Claude, OpenAI, Gemini): storage, delivery, tracking, limits

- [ ] Done · **Type:** design · **Estimate:** 0 d · **Risk:** high · **Sources:** owner request 2026-10-19

Environments are expected to get an agentic plane in 2-3 months that needs LLM provider keys. Brainstormed on 2026-10-19; the owner parked it because it needs special attention.

**Steps**

1. Decide: real provider keys in the environment, or platform keys through an LLM gateway.
2. Decide: where agents run (environment VMs/EKS -> AWS Secrets Manager + IAM role), one key per environment or per agent, Bedrock/Vertex instead of keys, monthly limit behaviour, suspend keys while stopped, who sees key details.
3. Then draw the screens (AI providers, AI keys inventory, Environment settings > AI agents, request/approval, AI spend) and plan an epic.

**Notes:** Options discussed: A store keys (encrypted, delivered to the env's Secrets Manager), B provider-side keys tracked via each provider's admin/usage APIs, C LLM gateway with platform-issued keys, D Bedrock/Vertex IAM access without keys. Suggested staging A -> B -> C. Rules: show a key once, then last 4 + fingerprint; audit every create/rotate/disable; 90-day rotation with overlap; disable on environment deactivation.

