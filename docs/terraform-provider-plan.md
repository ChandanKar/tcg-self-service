# Terraform provider plan

This is the design for `terraform-provider-vmcontrol`, a small Terraform provider for environments,
their groups and VMs, schedules and access grants (E34-T08). The provider code will live in a
separate repository; this document is the agreed design, and the API it relies on.

## Repository and stack

| | |
|---|---|
| Repository | `terraform-provider-vmcontrol` |
| Language | Go, `terraform-plugin-framework` (not the older SDKv2) |
| Registry | `tcgdigital/vmcontrol` on the Terraform Registry, with GPG-signed releases from GoReleaser |
| Versioning | SemVer. 0.x while the API may change; 1.0 once the gaps below are closed |

## Provider configuration

```hcl
provider "vmcontrol" {
  endpoint = "https://selfservice.example.com"   # or VMCONTROL_ENDPOINT
  token    = var.vmcontrol_token                 # or VMCONTROL_TOKEN; never logged
}
```

- **Token.** The token is a personal or service-account API token (E34-T01/T03), sent as
  `Authorization: Bearer vmc_...`. CI pipelines should use a service account.
- **Scopes.** A token's scopes limit which resources it can manage. The owner's own rights also
  apply, so a token never does more than its owner may.
- **Retries.** The provider retries `429` responses after the `Retry-After` header and treats
  `401` as a configuration error.

## Resources

Each resource maps to existing endpoints. Every request also needs the token scope shown
(`ApiScopeRegistry`) and the owner's role or environment access.

### `vmcontrol_environment`

| Attribute | API field | Notes |
|---|---|---|
| `name` | `name` | Required, forces replacement; stored normalized (lower case, `-`) |
| `display_name` | `displayName` | |
| `description` | `description` | |
| `aws_account` | `awsAccountRef` | An AWS account ref from `vmcontrol_aws_account` |
| `team_id` | `teamId` | |
| `id` (computed) | `environmentId` | |

There is no environment-level region: regions belong to VMs.

| Operation | Endpoint | Scope |
|---|---|---|
| Create | `POST /api/v1/environments` | `admin` |
| Read | `GET /api/v1/environments/{id}` | `environments:read` |
| Update | `PUT /api/v1/environments/{id}` | `admin` |
| Delete | `DELETE /api/v1/environments/{id}`. This soft-deletes (deactivates) the environment and releases its lock | `admin` |
| Import | by id, or by name through `GET /api/v1/environments/by-name/{name}` (added in this task) | `environments:read` |

### `vmcontrol_vm_group`

The attributes are `environment_id`, `name`, `display_name`, `description`, `sequence_position`
and `depends_on` (group ids).

| Operation | Endpoint | Scope |
|---|---|---|
| Create | `POST /api/v1/environments/{envId}/groups` | `admin` |
| Read | `GET /api/v1/environments/{envId}/groups/{groupId}` | `environments:read` |
| Update | `PUT /api/v1/environments/{envId}/groups/{groupId}` | `admin` |
| Delete | `DELETE /api/v1/environments/{envId}/groups/{groupId}` | `admin` |
| Import | `<envId>/<groupId>` | |

### `vmcontrol_vm` (registers an existing instance)

The attributes are:

- `environment_id`, `group_id`, `name`, `display_name`, `description`;
- `provider` (`AWS`, `AWS_EKS` or `AWS_RDS`), `region`, `provider_vm_id`;
- `aws_account`, `sequence_position`, `depends_on` (VM ids).

The provider does not create instances; it registers them.

| Operation | Endpoint | Scope |
|---|---|---|
| Create | `POST /api/v1/environments/{envId}/vms` | `admin` |
| Read | `GET /api/v1/environments/{envId}/vms/{vmId}` | `environments:read` |
| Update | `PUT /api/v1/environments/{envId}/vms/{vmId}` | `admin` |
| Delete | `DELETE /api/v1/environments/{envId}/vms/{vmId}`. This soft-deletes the VM; `POST .../reactivate` brings it back | `admin` |
| Import | `<envId>/<vmId>` | |

### `vmcontrol_schedule` (an automation rule)

The attributes are:

- `name`, `description`, `environment_id`;
- `scope_type` (`ENVIRONMENT` or `GROUP`) and `scope_id`;
- a `window` block, repeatable: `days_of_week`, `start_time`, `stop_time`;
- `timezone`, `holiday_calendar_id`, `skip_if_already_in_target_state`, `enabled`.

| Operation | Endpoint | Scope |
|---|---|---|
| Create | `POST /api/v1/automation-rules` | `schedules:write` |
| Read | `GET /api/v1/automation-rules/{ruleId}`. Returns every field above; `404` when the rule is gone | `schedules:read` |
| Update | `PUT /api/v1/automation-rules/{ruleId}` | `schedules:write` |
| Enable / disable | `PATCH /api/v1/automation-rules/{ruleId}/enabled` (or through `PUT`) | `schedules:write` |
| Delete | `DELETE /api/v1/automation-rules/{ruleId}` | `schedules:write` |

### `vmcontrol_access_grant`

The attributes are `environment_id`, `user_email`, `access_level` (`VIEWER`, `USER` or `ADMIN`),
`duration_days` and `notes`.

| Operation | Endpoint | Scope |
|---|---|---|
| Create | `POST /api/v1/environments/{envId}/access` | `access:write` |
| Read | `GET /api/v1/environments/{envId}/access` (filtered by the user) | `environments:read` |
| Update | `PATCH /api/v1/access-grants/{accessId}` | `access:write` |
| Delete | `DELETE /api/v1/environments/{envId}/access/{userId}` | `access:write` |

## Data sources

| Data source | Endpoint | Scope |
|---|---|---|
| `vmcontrol_environment` (by `name` or `id`) | `GET /api/v1/environments/by-name/{name}`, `GET /api/v1/environments/{id}` | `environments:read` |
| `vmcontrol_aws_account` (by `ref`) | `GET /api/v1/aws-accounts/options` | `admin` today (see gap 3) |

## Behaviour the provider relies on

- **Read after write.** Every resource has a GET by id that returns all its configurable fields
  and `404` once the resource is gone. The provider then removes it from state.
- **Soft deletes.** Environments and VMs are deactivated, not removed. A later create with the same
  name or instance conflicts with the inactive row (gap 2).
- **Names are normalized.** Environment names are stored in lower case with `-`. The provider
  should either normalize in its plan or mark the name as case-insensitive, to avoid
  perpetual diffs.
- **Errors.** `400` carries a `message` naming the field; the provider surfaces it as an attribute
  error.

## API gaps

Closed in this task:

- `GET /api/v1/environments/by-name/{name}`, for the data source and import by name. It answers
  `404` both when the environment does not exist and when the caller cannot see it.

Follow-ups, not built yet:

1. **An `environments:write` scope.** Today, registering groups and VMs through a token needs the
   `admin` scope, so an environment admin's or service account's token cannot manage its own
   environment's registry. Add an `environments:write` scope for those writes, checked against
   environment admin rights.
2. **Create-or-reactivate.** Re-creating a deleted environment or VM conflicts with its inactive
   row. Add `?reactivate=true` to the creates (or have the provider call `POST .../reactivate`) so
   applying the same configuration again works.
3. **A read scope for AWS account options.** `GET /api/v1/aws-accounts/options` needs `admin`. Map
   it to `environments:read` so the `vmcontrol_aws_account` data source works with ordinary tokens.
4. **Optimistic concurrency.** No resource returns an ETag or version, so two applies race and the
   last write wins. Exposing `version` (most entities already have one) and accepting `If-Match`
   would let the provider detect drift caused by the UI.

## Acceptance tests

- **Setup.** Run against a dev instance (`VMCONTROL_ENDPOINT`), with a service account token
  holding `admin`, `schedules:write` and `access:write`, in `TF_ACC=1` runs.
- **Per resource.** Create, then import by id (and by name for environments). Update every
  attribute, verifying with plan-only steps that no diff is left. Then destroy.
- **Failure cases.**
  - A token missing a scope fails with the API's 403 message.
  - A deleted resource disappears from state on refresh.
  - A rate-limited apply succeeds after its retries.
- **Unit tests.** The request and response mapping, and name normalization.

## Release

1. Tag `v0.1.0` once the four resources and two data sources pass their acceptance tests.
2. GoReleaser builds and signs the binaries; the Registry picks up the GitHub release.
3. Generate docs with `tfplugindocs`, with one example per resource. The examples use a service
   account token from `VMCONTROL_TOKEN`.
4. Release 1.0 after follow-ups 1 to 3 are in the API.
