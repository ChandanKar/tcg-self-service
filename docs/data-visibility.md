# Data visibility: AWS ids are for administrators

Rule (owner decision, 19 October 2026): AWS instance ids and other AWS resource ids are shown to
**administrators only** (role ADMIN), everywhere. IP addresses stay visible to everyone who can view the
environment. The backend enforces the rule; the screens only follow it.

## How it is enforced

| Where | How | Code |
| --- | --- | --- |
| API responses (JSON) | Id properties are left out for non-administrators (`@AdminOnly`): `VmDTO.providerVmId`, `VmInventoryDTO.providerVmId`, volume ids, `OperationDetailDTO.cloudOperationId`, EC2 choices' `instanceId` | `security/ProviderIdVisibility`, `dto/json/AdminOnly` |
| Free text in responses | Error messages, audit details, activity notes, state history, notifications, email log and provisioning errors are masked on the way out (`i-0123…` becomes `i-…`, ARNs become `arn:…`); stored text keeps the ids for administrators | `dto/json/RedactProviderIds`, `service/support/ProviderIdRedactor` |
| Webhooks, bell notifications, emails, Teams messages | Masked at the source, because their readers are not one signed-in administrator | `service/events/PlatformEvents`, `NotificationService`, `service/chat/ChatDispatchService` |
| Assistant and MCP tools | Output masked unless the user behind the call is an administrator | `service/assistant/AssistantToolGuard` |
| Adding VMs from AWS | Environment admins pick instances by name; the list carries an encrypted reference (30 minutes, bound to the user) instead of the id, and only administrators may type an id | `controller/Ec2Controller`, `service/support/InstanceRefCodec` |
| Exports with ids (FOCUS CSV, Excel, waste) | Administrators only (403 for everyone else) | `CostManagementController`, `WasteController` |

`ProviderIdExposureSecurityTest` reads every main endpoint as environment admin, user and viewer and fails if
any response contains an instance id, volume id or ARN.

AWS account numbers (12 digits) are not masked: they cannot be told apart from other numbers.

## Setting for production

`INSTANCE_REF_KEY` (property `vm.instance-ref.key`): base64 of 32 random bytes, for example
`openssl rand -base64 32`. Use the **same value on every app server**. Without it each server makes its own
key at startup, and an instance list fetched from one server cannot be used to add VMs on another (or after a
restart). `INSTANCE_REF_TTL_MINUTES` (default 30) sets how long a fetched list stays usable.
