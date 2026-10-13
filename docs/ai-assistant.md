# AI assistant (MCP)

The platform runs a read-only [Model Context Protocol](https://modelcontextprotocol.io) server at `/mcp`. An MCP client such as Claude Code or Claude Desktop can then answer questions like "what did my team spend on idle environments this month?" from the platform's data. The client sees exactly what its user can see in the platform and nothing more.

The server is off by default. It needs:

- `ASSISTANT_MCP_ENABLED=true` (`assistant.mcp.enabled`), and
- `API_TOKENS_ENABLED=true` (`api-tokens.enabled`), because clients authenticate with personal API tokens.

Tokens are only checked when Entra ID sign-in is on (`entraid.enabled=true`). The dev security mode has no token authentication, so `/mcp` answers 401 there.

## Two servers, two kinds of question

| | vmcontrol MCP server | AWS Billing and Cost Management MCP server |
|---|---|---|
| Runs | in the platform, at `https://<host>/mcp` | on the user's own machine (`uvx`), from [awslabs/mcp](https://github.com/awslabs/mcp) |
| Who uses it | any user with a token | FinOps and admin users |
| Credentials | the user's API token with the `assistant:read` scope | the user's own read-only billing AWS profile; **never** the platform's credentials |
| Sees | environments, VM groups and VMs the user may view, the platform's cost estimates, the savings ledger, idle and rightsizing data | account-level AWS billing: Cost Explorer, Savings Plans and RI coverage, budgets, Compute Optimizer, across every account the profile can read |
| Knows about environments | yes: costs are per environment, team and AWS account as configured in the platform | no: only accounts, services and tags |

Use the vmcontrol server for questions about environments, teams and schedules. Use the AWS server for questions about the whole bill. A client with both connected picks the right one for each question.

## Setting up

### 1. Create a token

In the platform, open **My Account → API tokens → Create token**. Give it a name such as "Claude Code on my laptop", choose only the **assistant:read** scope and a short expiry (30 or 90 days). Copy the token when it is shown; it is not shown again.

A token with `assistant:read` can only call `/mcp`. Other scopes, `admin` included, do not open `/mcp`, so the assistant token cannot reach the rest of the API.

### 2. Claude Code

```bash
claude mcp add --transport http vmcontrol https://<host>/mcp --header "Authorization: Bearer <token>"
```

Or in a project's `.mcp.json`, reading the token from an environment variable so it never lands in the repository:

```json
{
  "mcpServers": {
    "vmcontrol": {
      "type": "http",
      "url": "https://<host>/mcp",
      "headers": { "Authorization": "Bearer ${VMCONTROL_TOKEN}" }
    }
  }
}
```

### 3. Claude Desktop

Claude Desktop starts local servers from `claude_desktop_config.json`. To reach a remote server that needs a header, use the `mcp-remote` bridge. A full example with both servers is in [examples/mcp-config.json](examples/mcp-config.json):

```json
{
  "mcpServers": {
    "vmcontrol": {
      "command": "npx",
      "args": ["-y", "mcp-remote", "https://<host>/mcp", "--header", "Authorization: Bearer ${VMCONTROL_TOKEN}"],
      "env": { "VMCONTROL_TOKEN": "<token>" }
    },
    "aws-billing": {
      "command": "uvx",
      "args": ["awslabs.billing-cost-management-mcp-server@latest"],
      "env": { "AWS_PROFILE": "<read-only-billing-profile>", "AWS_REGION": "us-east-1", "FASTMCP_LOG_LEVEL": "ERROR" }
    }
  }
}
```

The AWS server needs [uv](https://docs.astral.sh/uv/) and an AWS profile whose role only has billing read permissions (`ce:Get*`, `ce:Describe*`, `budgets:View*`, `compute-optimizer:Get*`, `pricing:Get*`, and so on). Do not give it the platform's IAM user.

### 4. Check the connection

Ask the client "who am I on vmcontrol?". It calls the `whoami` tool and answers with your name, email and role. Then try a question from the list below.

## Tools

Every tool is read-only and returns at most 50 rows. Amounts are in USD with two decimals. Each result says which figures are **estimated** (static pricing times running hours, the same as the platform's Cost Management page) and which are **actual** (from Cost Explorer, once ingested).

| Tool | Answers | Arguments |
|---|---|---|
| `whoami` | who the token belongs to | none |
| `list_environments` | the environments you can see, with VM counts, running VMs, regions, team and AWS account | `search` |
| `get_environment` | one environment | `environmentId` |
| `get_environment_cost` | this month's month-to-date estimate and actual, change against last month, month-end forecast, savings and top five VMs | `environmentId` |
| `spend_by` | estimated spend over the last 30 days by environment, team or AWS account | `dimension` |
| `cost_trend` | daily estimated and actual cost this month, for one environment or all of yours | `environmentId`, `days` |
| `savings_summary` | realised savings for a month by cause, potential savings and cost efficiency | `month` (yyyy-MM), `environmentId` |
| `idle_resources` | idle VMs, most expensive first, with how long they were idle and the monthly idle cost | `environmentId`, `limit` |
| `rightsizing_candidates` | VMs that could change instance type, largest saving first | `environmentId`, `direction`, `limit` |

## Sample questions

| Question | Server and tools |
|---|---|
| What did my team spend on idle environments this month? | vmcontrol: `idle_resources`, then `spend_by` with `team` |
| Which environments saved most from schedules last month? | vmcontrol: `savings_summary` with the month |
| Which of my environments has the biggest cost increase against last month? | vmcontrol: `list_environments`, then `get_environment_cost` for each |
| What would we save by rightsizing the payments environment? | vmcontrol: `rightsizing_candidates` with its `environmentId` |
| How is daily spend trending in the qa environment? | vmcontrol: `cost_trend` |
| What is our Savings Plans coverage across all accounts? | AWS server only. The platform does not see commitments across accounts |
| Which AWS services cost the most this quarter, including ones the platform does not manage? | AWS server only |
| How does the platform's estimate for an environment compare with the tagged actual cost? | vmcontrol `get_environment_cost` (it shows both when actuals are ingested), or both servers |

## Security model

- **Scope.** Every tool answers as the token's owner. Admins see every active environment; everyone else sees the environments they hold access to. Asking for another environment returns "not found", which does not reveal whether it exists. Results carry no other users' emails or IP addresses.
- **Read-only.** No tool starts, stops or changes anything. Tool annotations say so (`readOnlyHint`), and the endpoint has no write methods.
- **Tokens only.** `/mcp` refuses session cookies (401), so a web page cannot use a signed-in browser to call it. Tokens are hashed at rest, expire, and can be revoked from My Account. Admins see and revoke every token under **API access**. Rotate a token when a laptop is replaced and when it expires.
- **Limits.** Each user gets 60 tool calls a minute (`assistant.mcp.rate-limit-per-minute`). Each call has 10 seconds (`assistant.mcp.tool-timeout-ms`). Output over 50,000 characters is cut and marked truncated (`assistant.mcp.max-output-chars`). API tokens also have their own per-token request limit.
- **Audit.** Every call is written to the audit log as `ASSISTANT_TOOL_CALLED`, with the user, token prefix, tool, arguments (long text shortened to 200 characters), rows returned and time taken. Refused calls (bad arguments, rate limit, timeout) are logged as failed. Filter the Audit Logs page by that action.
- **Data leaves the platform.** Tool results go into the AI client's context and so to its model provider. Use only AI clients your organisation has approved for this data. Cost and environment names may be commercially sensitive.

## Limitations

- Estimates use static on-demand pricing. Savings Plans, Reserved Instances and discounts are not in them; actuals from Cost Explorer are.
- `spend_by` covers a fixed 30-day window, the same as the Cost Management page.
- `cost_trend` covers the current month.
- Only tools are offered: no resources or prompts.
- Responses are plain JSON over HTTP POST (Streamable HTTP without server-sent streams). `GET /mcp` answers 405, which the specification allows for servers that do not push messages.
- The MCP server is implemented in the platform itself (JSON-RPC 2.0, protocol versions 2025-06-18, 2025-03-26 and 2024-11-05) rather than through a library. This keeps it inside the platform's own security filter chain and adds no dependency.
