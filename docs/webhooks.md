# Webhooks

Webhooks send platform events to your own systems as signed HTTPS POST requests (E34-T06/T07).

- **Who manages them.** Admins manage global webhooks, which receive events from every
  environment. Environment admins manage the webhooks of their own environments, from the
  environment page under **Webhooks**.
- **Switching them on.** Webhooks are off by default. Set `WEBHOOKS_ENABLED=true`. `CHAT_SECRET_KEY`
  must also be set, because it encrypts the signing secrets.

## Request

```
POST <your https URL>
Content-Type: application/json
User-Agent: VmControl-Webhooks/1
X-VmControl-Event: environment.stopped
X-VmControl-Delivery: 7f0c…            (unique per delivery; use it to drop duplicates)
X-VmControl-Signature: t=1760353200,v1=5d1c…

{
  "id": "c1a8…",                       (the event id; the same for every webhook that receives it)
  "type": "environment.stopped",
  "occurredAt": "2026-10-13T12:00:00Z",
  "environment": { "id": "6f2e…", "name": "qa" },   (null for events without an environment)
  "data": { … }                        (depends on the type, see below)
}
```

Answer with any `2xx` status within 5 seconds (`webhooks.timeout-ms`). Redirects are not followed;
a `3xx` answer counts as a failure.

## Events

| Type | When | `data` |
|---|---|---|
| `operation.completed` | A start, stop or restart operation finished (fully or partly) | `operationId`, `operationType`, `outcome` (`COMPLETED`, `PARTIAL_SUCCESS`), `targets`, `failedOrSkipped`, `initiatedBy`, `triggerSource` |
| `operation.failed` | An operation failed | `operationId`, `operationType`, `error`, `initiatedBy` |
| `environment.started` | A start operation finished with every step done | same as `operation.completed` |
| `environment.stopped` | A stop operation finished with every step done | same as `operation.completed` |
| `lease.expiring` | An environment's lease is about to end (once per warning window) | `leaseId`, `endsAt`, `minutesLeft` |
| `lease.expired` | A lease ended | `leaseId`, `stopped`, `operationId` |
| `access.granted` | Someone was given access to the environment | `userId`, `accessLevel` |
| `access.revoked` | Someone's access was removed | `userId`, `revokedBy` |
| `idle.stopped` | Idle auto-stop stopped the environment | `operationId`, `idleMinutes`, `vmCount`, `idleSince` |
| `budget.threshold` | A budget passed one of its thresholds; one event per environment of the budget | `budgetId`, `scope`, `month`, `thresholdPercent`, `spend`, `amount` |
| `provisioning.ready` | An environment requested from a template is ready | `requestId`, `templateId`, `requestedBy`, `destroyAt` |
| `provisioning.destroyed` | A provisioned environment was destroyed | `requestId`, `requestedBy` |
| `ping` | **Test** was pressed on the webhook | `webhookId` |

Events are queued only after the action that caused them is saved. An action that is rolled back
sends nothing.

## Verifying the signature

The signature header reads `t=<unix seconds>,v1=<hex>`, where `<hex>` is
HMAC-SHA256(secret, `t + "." + raw body`) in hex. The secret (`whsec_…`) is shown once, when the
webhook is created. To check a request:

1. Recompute the HMAC over the raw body exactly as it was received.
2. Compare it with `v1` in constant time.
3. Reject requests whose `t` is more than 5 minutes old, to block replays.

Node.js:

```js
const crypto = require('crypto');

function verify(secret, header, rawBody, toleranceSeconds = 300) {
  const parts = Object.fromEntries(header.split(',').map(p => p.split('=')));
  const expected = crypto.createHmac('sha256', secret).update(`${parts.t}.${rawBody}`).digest('hex');
  const fresh = Math.abs(Date.now() / 1000 - Number(parts.t)) <= toleranceSeconds;
  return fresh && expected.length === parts.v1.length
    && crypto.timingSafeEqual(Buffer.from(expected), Buffer.from(parts.v1));
}
```

Python:

```python
import hashlib, hmac, time

def verify(secret: str, header: str, raw_body: bytes, tolerance_seconds: int = 300) -> bool:
    parts = dict(p.split("=", 1) for p in header.split(","))
    signed = parts["t"].encode() + b"." + raw_body
    expected = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
    fresh = abs(time.time() - int(parts["t"])) <= tolerance_seconds
    return fresh and hmac.compare_digest(expected, parts["v1"])
```

## Retries

A delivery that does not get a `2xx` answer is retried after 1 minute, 5 minutes, 30 minutes,
2 hours and 12 hours. That makes 6 attempts in all (`webhooks.max-attempts`); after the last
failure the delivery is given up.

- The delivery job runs every 30 seconds, so a retry can come a little later than scheduled.
- After 50 deliveries in a row are given up, the webhook is disabled and its creator is told. Fix
  the receiver, then enable the webhook again on the environment page.
- **Deliveries** shows the latest attempts, with their status and response code.

## Safety rules

- The URL must be `https` and must not contain credentials.
- Every address the host resolves to must be public. The following are refused, both when the
  webhook is saved and again before every send (DNS can change in between):
  - loopback;
  - private networks: 10/8, 172.16/12, 192.168/16, fc00::/7;
  - carrier-grade NAT: 100.64/10;
  - link-local, including the cloud metadata address 169.254.169.254;
  - multicast and unspecified addresses.
- Lists show only `scheme://host`. The rest of the URL can hold the receiver's own secret, so it is
  never shown again after saving.
