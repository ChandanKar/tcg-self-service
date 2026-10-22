# Every region: manual check on staging (U19-T09)

The automated tests cover every commercial region without AWS (`RegionMatrixTest`, the sweep, discovery,
price list and region notes tests). This list is the real-AWS check, done by hand on staging with a US
region and an opt-in region (for example `us-east-1` and `me-central-1`). GovCloud and China are out of scope.

Start and stop **only** the approved test VMs: `i-0c80978a6e1c5b6eb`, `i-037a979ccc26fb41e`,
`i-076bbf4c25618c4d3`. Everything else in this list only reads.

## Before you start

The staging role needs, besides what it has today (see `aws-iam-permissions.md`):
`ec2:DescribeRegions`, `ec2:DescribeAvailabilityZones`, `pricing:GetProducts`,
`iam:SimulatePrincipalPolicy`, and optionally `resource-explorer-2:ListIndexes` / `resource-explorer-2:Search`.

| # | Do | Expect | If not |
|---|---|---|---|
| 1 | Administration › AWS accounts › **Check access** on the default account | The check passes; the Regions column says *Every enabled region · N* (hover lists them). An opt-in region you turned on is in the list | *Not read yet*: `ec2:DescribeRegions` is missing (Check access says why) |
| 2 | System health › **Regions** | Each account with its enabled regions and when they were read | Empty accounts list: no active account |
| 3 | Cost management › Setup › **Check AWS permissions** | One *Calls in &lt;region&gt;* line per enabled region, all allowed for the regions you use | A line names the calls the policy leaves out in that region |
| 4 | VM registry › **Found in AWS** › Look again | Instances and clusters that no environment holds, in the US region and the opt-in region, with account, zone and size. *Couldn't read …* names any region AWS refused | A region missing without a note: send the System health › Regions table |
| 5 | Filter Found in AWS by the opt-in region; **Ignore** one item, then *Ignored (1)* › **Bring back** | It disappears, then comes back after Look again | |
| 6 | New environment from EKS (if a cluster exists in either region) | The cluster is listed with its region; after it is made, *Sync node groups now* fills its groups | *This cluster's AWS region isn't known* on the page: set it in Settings › Details |
| 7 | An environment › Add VMs › Region **Every enabled region** | The account's instances from every region, with their zones | *Couldn't list instances in every enabled region*: no region answered |
| 8 | Start, then stop, one approved VM (each one in its own region) | Start and stop finish; VM details show *Region / zone* with the zone ID, e.g. `us-east-1a (use1-az4)` | A capacity error now says what to try |
| 9 | VM details › Cost this month for a VM outside us-east-1, us-west-2, ap-south-1, eu-west-1, a day after it was first estimated | No *Approximate price* note | Still approximate: `pricing:GetProducts` is missing |
| 10 | Environments and VM registry › **Region** filter | Only environments with a VM in that region | |
| 11 | Settings › AWS › **Regions to leave out**: add the opt-in region, save; Found in AWS › Look again | Nothing from that region; System health › Regions shows no new scan there. Then **Reset** | |
| 12 | System health › Regions › *Services by region* | Compute Optimizer in a region where it is not turned on reads *Not turned on* (a note, no failed job) | A failed job or an alert instead: report it |

Send the results (and a screenshot of System health › Regions) back with the build number you tested.
