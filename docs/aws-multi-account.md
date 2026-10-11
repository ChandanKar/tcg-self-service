# Running across several AWS accounts

This guide sets up the platform to start, stop, discover and cost EC2 and EKS resources in more than one AWS account. Follow it from top to bottom; nothing changes until you switch `AWS_MULTI_ACCOUNT_ENABLED` on in step 4 of the rollout.

## How it works

- **Base identity.** The platform runs with one AWS identity: either the static key in `AWS_ACCESS_KEY` / `AWS_SECRET_KEY`, or, with both blank and `AWS_USE_DEFAULT_CREDENTIAL_CHAIN=true`, the instance role of the host (EC2 instance profile, ECS task role or EKS IRSA). The account this identity lives in is the **default account**. Everything that existed before multi-account belongs to it.
- **Member accounts.** For every other account you create an IAM role (for example `VmControlAccess`) that trusts the base identity. The platform calls `sts:AssumeRole` on that role, with an external ID, and uses the temporary credentials for every EC2, EKS, CloudWatch and Compute Optimizer call in that account. Sessions last one hour and are renewed automatically before they expire.
- **Billing account.** Cost Explorer (actual costs, coverage, commitment recommendations, anomalies) and AWS Organizations are read from one account: the account marked *Billing* on the AWS Accounts page. Normally this is the payer (management) account, or an account that is a delegated administrator for Cost Explorer and Organizations. If no member account is marked, the default account is used.
- **Where things live.** Every environment belongs to one account; its VMs and EKS node groups use that account unless a VM was registered into another one (for example a shared-services instance). State sync, metrics, inventory, tagging, resizing and discovery all call the VM's own account.

## 1. Run the platform on an IAM role (optional, recommended)

Instead of a long-lived access key:

1. Attach a role to the host the platform runs on (EC2 instance profile, ECS task role, or an EKS service account with IRSA).
2. Give it the permissions in [aws-iam-permissions.md](aws-iam-permissions.md) for the features you use, plus `sts:AssumeRole` on the member roles (section 3) and `sts:GetCallerIdentity`.
3. Set in `.env`:

   ```
   AWS_ACCESS_KEY=
   AWS_SECRET_KEY=
   AWS_USE_DEFAULT_CREDENTIAL_CHAIN=true
   ```

4. Restart. The log says `AWS credentials: SDK default credential chain (instance role, profile or environment)`, and a minute later the default account's number appears on the AWS Accounts page (it is read from `sts:GetCallerIdentity`).

## 2. Choose an external ID

Pick a random string of 16 or more characters, for example from `openssl rand -hex 16`. You enter it on the AWS Accounts page (it is stored, and shown only as its last four characters) and in each member role's trust policy. It stops anyone else who knows the role name from using it through a confused-deputy service.

## 3. Create the member role in each account

### Trust policy

Replace `111111111111` with the default account's number and `BASE_ROLE_OR_USER` with the base identity: the platform's role name (`role/VmControlPlatform`) or, with static keys, the IAM user (`user/vmcontrol`).

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "AWS": "arn:aws:iam::111111111111:BASE_ROLE_OR_USER" },
    "Action": "sts:AssumeRole",
    "Condition": { "StringEquals": { "sts:ExternalId": "YOUR-EXTERNAL-ID" } }
  }]
}
```

### Permission policy

What the platform does in a member account. Remove the lines for features you leave off.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "Ec2Operations",
      "Effect": "Allow",
      "Action": ["ec2:DescribeInstances", "ec2:DescribeInstanceStatus", "ec2:StartInstances", "ec2:StopInstances",
                 "ec2:DescribeVolumes", "ec2:DescribeTags"],
      "Resource": "*"
    },
    {
      "Sid": "EksOperations",
      "Effect": "Allow",
      "Action": ["eks:ListClusters", "eks:ListNodegroups", "eks:DescribeNodegroup", "eks:UpdateNodegroupConfig"],
      "Resource": "*"
    },
    {
      "Sid": "Metrics",
      "Effect": "Allow",
      "Action": ["cloudwatch:GetMetricData"],
      "Resource": "*"
    },
    {
      "Sid": "TaggingAndResize",
      "Effect": "Allow",
      "Action": ["ec2:CreateTags", "eks:TagResource", "ec2:ModifyInstanceAttribute"],
      "Resource": "*"
    },
    {
      "Sid": "Rightsizing",
      "Effect": "Allow",
      "Action": ["compute-optimizer:GetEC2InstanceRecommendations", "compute-optimizer:GetEnrollmentStatus"],
      "Resource": "*"
    }
  ]
}
```

The billing account additionally needs the Cost Explorer actions listed under *Cost features* in [aws-iam-permissions.md](aws-iam-permissions.md), and `organizations:ListAccounts` for the import in step 5 of the rollout.

### Deploying the role to every account at once

With AWS Organizations, a service-managed CloudFormation StackSet creates the role in every member account (and in accounts added later):

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Parameters:
  PlatformPrincipalArn: { Type: String, Description: 'arn:aws:iam::<default account>:role/<platform role>' }
  ExternalId: { Type: String, NoEcho: true }
Resources:
  VmControlAccess:
    Type: AWS::IAM::Role
    Properties:
      RoleName: VmControlAccess
      MaxSessionDuration: 3600
      AssumeRolePolicyDocument:
        Version: '2012-10-17'
        Statement:
          - Effect: Allow
            Principal: { AWS: !Ref PlatformPrincipalArn }
            Action: sts:AssumeRole
            Condition: { StringEquals: { 'sts:ExternalId': !Ref ExternalId } }
      Policies:
        - PolicyName: vmcontrol-member
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Effect: Allow
                Action: [ec2:DescribeInstances, ec2:DescribeInstanceStatus, ec2:StartInstances, ec2:StopInstances,
                         ec2:DescribeVolumes, ec2:DescribeTags, ec2:CreateTags, ec2:ModifyInstanceAttribute,
                         eks:ListClusters, eks:ListNodegroups, eks:DescribeNodegroup, eks:UpdateNodegroupConfig, eks:TagResource,
                         cloudwatch:GetMetricData,
                         compute-optimizer:GetEC2InstanceRecommendations, compute-optimizer:GetEnrollmentStatus]
                Resource: '*'
```

Create the StackSet from the management account (CloudFormation → StackSets → *Service-managed permissions*, automatic deployment on), target the organization root or the OUs you want, and pass the two parameters.

## Rollout checklist

1. **Deploy with the switches off.** Defaults are safe: `AWS_MULTI_ACCOUNT_ENABLED=false`, `AWS_ORGANIZATIONS_ENABLED=false`. Existing environments are already in the default account.
2. **Check the default account.** Open *Admin → AWS Accounts*: the default account shows its 12-digit number. If it does not, the base identity cannot call `sts:GetCallerIdentity`; fix that first. Click *Verify* on it.
3. **Create the member roles** (section 3) in every account you want to reach.
4. **Switch multi-account on.** Set `AWS_MULTI_ACCOUNT_ENABLED=true` and restart. Until then, member accounts would be reached with the base credentials, so they are not scanned.
5. **Add the accounts.**
   - By hand: *Add account* → display name, the role ARN (`arn:aws:iam::<account>:role/VmControlAccess`), the external ID, and the regions to scan (leave empty for the platform's default regions).
   - Or from AWS Organizations: set `AWS_ORGANIZATIONS_ENABLED=true` (and `AWS_ORGANIZATIONS_MEMBER_ROLE_NAME` if your role is not called `VmControlAccess`), mark the management account as *Billing*, restart, then *Import from AWS Organizations*, tick the accounts and enter the shared external ID.
6. **Verify each account.** *Verify* calls `sts:GetCallerIdentity` through the account's role. A green *Verified* means the role, trust policy and external ID are right. A red badge shows the AWS error; *Role resolved to account …* means the role ARN points to a different account than the one registered.
7. **Mark the billing account** (the payer or delegated administrator) so actual costs and recommendations cover every linked account.
8. **Turn on discovery** if you use it (`VM_DISCOVERY_ENABLED`, `EKS_SYNC_AUTO_REGISTER_ENABLED`). Discovery now scans every active account in its own regions; a broken role is logged and the other accounts are still scanned. New environments and VMs keep the account they were found in.
9. **Choose accounts in the forms.** With more than one active account, *New environment*, *Edit environment* and the VM registry's *Fetch Instances* show an *AWS account* choice, and the environment list and Cost Management show an account column and *Spend by Account*.

## Troubleshooting

| What you see | Cause and fix |
|---|---|
| `AccessDenied ... not authorized to perform: sts:AssumeRole` on Verify | The base identity lacks `sts:AssumeRole` on the member role, or the trust policy names another principal. |
| `... is not authorized to perform: sts:AssumeRole on resource` with the right principal | The external ID does not match the trust policy's `sts:ExternalId`. Edit the account and enter it again. |
| *Multi-account is disabled* on Verify of a member account | `AWS_MULTI_ACCOUNT_ENABLED` is still false. |
| Import says the account is not part of an organization | The billing account is not the management (or delegated administrator) account. Mark the right one as *Billing*. |
| A warning `Cost tagged with environment 'X' was billed to account N, but the environment is registered in account M` | A resource carries environment X's cost tag in another account. Retag it, or move the environment to that account. |
| An account cannot be deactivated | It still has active environments. Move or deactivate them first; the default account can never be deactivated. |
