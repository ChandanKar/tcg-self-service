# Environments from templates

Users can request a whole environment from an approved template instead of registering VMs by
hand (E32). An admin publishes the template. The request is approved (or needs no approval), and
the provisioning job builds it with AWS Service Catalog or HCP Terraform / Terraform Enterprise.
When the build finishes, the environment is created under the requested name, the new instances
are registered and the requester gets ADMIN access for the lifetime. At the end of the lifetime the
environment is destroyed.

## How it works

1. **Template.** Admins manage templates under **New Environment → Manage templates**. A template
   holds:
   - the engine and its reference (product and version, or Terraform organization and workspace
     prefix);
   - the parameter schema shown to requesters;
   - the AWS account and region;
   - the default and maximum lifetime;
   - whether approval is needed and who approves (admins or environment admins);
   - which roles may request it;
   - an estimated monthly cost.

   Retiring a template hides it from requesters. Environments already built from it are not
   affected.
2. **Request.** On **New Environment → Templates** a user picks a template and fills in:
   - the environment name;
   - the lifetime;
   - the template's parameters;
   - a justification.

   The server checks every value against the schema. The client runs the same checks first.
3. **Approval.** Approvers see waiting requests under **New Environment → Approvals** and approve
   or reject them. A rejection needs a reason, and nobody can approve their own request.
4. **Build.** The provisioning job runs every minute (`provisioning.cron`). It starts approved
   requests and polls the ones that are running. A build that takes longer than
   `provisioning.timeout-minutes` (60) fails, and nothing is created.
5. **Ready.** The environment is created with the template's account. Its instances are found by
   tag and registered. The requester gets ADMIN access until the destroy time. The requester and
   the environment's admins can extend the lifetime up to the template's maximum. Admins can go
   beyond the maximum.
6. **Destroy.** The requester is warned a day and an hour before the destroy time. With
   `provisioning.destroy.enabled`, the environment is destroyed once the destroy time has passed
   and nothing in it is running. The environment is deactivated only when the engine reports the
   destroy finished.

## Tagging convention (required for auto-registration)

New instances are found by the discovery tag `vm.discovery.tag.environment-key` (default
`tcg:environment`). Its value is the environment's **normalized name**: lower case, with spaces and
punctuation turned into `-`. For example, `Feature X` becomes `feature-x`.

- **Service Catalog**: the provisioned product is launched with three tags:
  - `tcg:environment=<normalized name>`;
  - `tcg:managed-by=vmcontrol`;
  - `tcg:provisioning-request=<request id>`.

  CloudFormation propagates stack tags to resources that support tags. Resources created
  indirectly don't get them, for example instances launched by an Auto Scaling group whose
  template does not copy them. The product must tag those itself. Declare a parameter named
  `EnvironmentName` in the template's schema. Pass it to the resources as
  `tcg:environment=<EnvironmentName>`, entered in its normalized form.
- **Terraform**: every run gets these workspace variables:
  - `environment_name` (the normalized name);
  - `provisioning_request_id`;
  - an HCL `tags` map containing the three tags above.

  The configuration must declare `variable "environment_name"`, `variable "provisioning_request_id"`
  and `variable "tags" { type = map(string) }`. It must apply `var.tags` to every resource, for
  example through the provider's `default_tags`.

Instances that aren't tagged are not registered. The environment is still created, but empty.

## Publishing a Service Catalog product

1. Create the product (a CloudFormation template) in a portfolio in the target account and region.
   Note the product id (`prod-…`) and the provisioning artifact (version) id (`pa-…`).
2. Give the portfolio a **launch constraint** with an IAM role that can create everything in the
   template. Service Catalog uses this role, not the app's credentials, to build and terminate the
   stack. The app's role only needs the Service Catalog calls listed below.
3. Share the portfolio with the principal the app runs as (the app's role, or the cross-account
   role in `docs/aws-multi-account.md`).
4. Add the template in the app:
   - Engine: AWS Service Catalog.
   - Product id and artifact id.
   - The launch path id, only if the product has more than one launch path.
   - The AWS account ref and region.
   - One parameter row per CloudFormation parameter the requester should set. The key must match
     the CloudFormation parameter name.

## Publishing a Terraform template

1. In HCP Terraform / Terraform Enterprise, create an API team token that can manage workspaces,
   variables and runs. Put it in `.env` as `TFC_TOKEN`, together with `TFC_BASE_URL` (default
   `https://app.terraform.io`) and `TFC_ORGANIZATION`. The token is sent as a bearer header and is
   never logged.
2. Set `provisioning.terraform.enabled=true` (`PROVISIONING_TERRAFORM_ENABLED`) and restart.
3. Add the template in the app:
   - Engine: Terraform.
   - The organization and a workspace prefix. Each environment gets the workspace
     `<prefix><normalized name>`.
   - Either a VCS repository (`org/repo`) with its OAuth token id, or a no-code module id.
   - Optionally a Terraform version and an agent pool id.
4. Runs are queued with auto-apply. With `provisioning.terraform.delete-workspace`
   (`TFC_DELETE_WORKSPACE`), the workspace is deleted after its destroy run has been applied.

## Switches

| Property | Env var | Default | Effect |
|---|---|---|---|
| `provisioning.enabled` | `PROVISIONING_ENABLED` | false | Catalogue, requests and the provisioning job. Off means every `/templates` and `/provisioning-requests` call returns 404, and the sidebar item is hidden |
| `provisioning.cron` | `PROVISIONING_CRON` | every minute | Provisioning job schedule |
| `provisioning.timeout-minutes` | `PROVISIONING_TIMEOUT_MINUTES` | 60 | A build running longer fails |
| `provisioning.destroy.enabled` | `PROVISIONING_DESTROY_ENABLED` | false | Destroy environments past their destroy time (Settings asks for confirmation) |
| `provisioning.terraform.enabled` | `PROVISIONING_TERRAFORM_ENABLED` | false | Terraform engine |
| `provisioning.terraform.delete-workspace` | `TFC_DELETE_WORKSPACE` | false | Delete the workspace after a destroy |

`provisioning.enabled`, `provisioning.timeout-minutes` and `provisioning.destroy.enabled` can also be
changed at runtime in **Settings → Provisioning**.

## AWS permissions

The app's role needs these Service Catalog calls, also listed in `docs/aws-iam-permissions.md`:

- `servicecatalog:ProvisionProduct`
- `servicecatalog:DescribeRecord`
- `servicecatalog:GetProvisionedProductOutputs`
- `servicecatalog:TerminateProvisionedProduct`

It also needs the EC2 describe calls that VM discovery already uses to register the new instances.
Everything the product creates is created by the launch constraint role.

## Rollout

1. Publish one small product (one instance) and add its template with approval required.
2. Turn on `provisioning.enabled`. Request an environment, approve it, and check that it becomes
   Ready with its instance registered.
3. Destroy it from **My requests** (the environment's name must be typed exactly). Check that the
   stack is gone.
4. Only then turn on `provisioning.destroy.enabled` for automatic destroys.
