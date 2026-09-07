# Security Policy

## Supported Versions

| Version | Security fixes until |
|---|---|
| 0.1.x | 12 months from release |

## Reporting a Vulnerability

Email security@autorun.fun. Do not open a public issue.
We acknowledge reports within 48 hours and aim to ship a fix within 14 days for confirmed issues.

## Security Model

- **No telemetry.** Nothing is sent to the vendor at runtime. There is no opt-out because there is nothing to opt out of.
- **No runtime license check.** `LICENSE-PRO.txt` and `distribution.properties` are informational. SafeExec never contacts a license server and never fails because of licensing.
- **No vendor cloud dependency.** SafeExec runs entirely inside your infrastructure. Your production data, API keys, and agent actions never leave it.
- **Authentication by default.** All management endpoints (`/api/approvals/**`, `/api/audit/**`, `/api/shadow/**`, `/api/admin/**`, `/ui/**`) require authentication. `safeexec.security.enabled=false` is honored only under the `demo` profile; elsewhere it is ignored with a warning.
- **Localhost by default.** The example binds to `127.0.0.1`. Exposing it is a deliberate configuration change.
- **Identity from the security context.** Approver, rejecter, and Shadow reviewer identities come from `SecurityContextHolder`. Any "who am I" field in a request body is ignored.
- **Roles.** `SAFEEXEC_APPROVER` approves and rejects. `SAFEEXEC_REVIEWER` records Shadow decisions. `SAFEEXEC_ADMIN` controls the Kill Switch. Policy files have no write endpoint at all.
- **REST and UI separated.** `/api/**` uses token or Basic auth, stateless, no CSRF. `/ui/**` uses sessions with CSRF enabled. CSRF is never globally disabled. No default CORS `*`.
- **Policy and Kill Switch are never agent tools.** Registering a tool whose name or group starts with `policy`, `killswitch`, or `safeexec` fails at startup.
- **Fail closed.** If the policy file is missing or invalid, WRITE and IRREVERSIBLE actions are denied (`POLICY_UNAVAILABLE`). If the audit store is unavailable, nothing executes (`AUDIT_UNAVAILABLE`). A broken security configuration fails startup rather than degrading to no auth.

## Secret Handling

Audit input is redacted by default. Redaction is recursive over nested objects and arrays and case-insensitive on field names. Default patterns:

```
phone, address, card, token, secret, password, passwd, authorization, cookie,
apiKey, api_key, accessToken, refreshToken, privateKey, credential, credentials
```

`safeexec.audit.input-mode` controls what is stored:

| Mode | Stored |
|---|---|
| NONE | only `input_hash` |
| HASH_ONLY | only `input_hash` |
| REDACTED (default) | redacted input plus `input_hash` |
| FULL | raw input plus `input_hash` (not recommended) |

## Audit Data

Audit events are append-only. A database trigger rejects `UPDATE` and `DELETE` on `audit_event`. An optional Flyway script (`V900__optional_audit_writer_role.sql`, disabled by default) creates an `audit_writer` role with `INSERT` and `SELECT` only, for a second layer of protection.

## Threat Model

| Threat | Mitigation |
|---|---|
| LLM produces malformed or out-of-schema arguments | JSON Schema validation before anything else |
| Prompt injection requests a high-risk action | Policy Engine; IRREVERSIBLE defaults to REQUIRE_APPROVAL |
| Unauthorized write action | Policy + authenticated identity + roles |
| Network timeout causes duplicate execution | Intent CAS, fixed `external_idempotency_key`, three-state recovery (`Applied` / `ConfirmedNotApplied` / `Indeterminate`), `RETRY_RELEASED` |
| Parameters change after approval | Immutable Intent + content hash |
| Evidence goes stale after approval | `valid_until` checked at execution |
| Policy tightens after approval | Re-evaluation at execution time |
| Agent misbehaves during rollout | Shadow mode blocks writes and records what would have happened |
| Safety component itself fails | Fail closed |
| Incident cannot be reconstructed | Append-only audit event stream with DB trigger |
| Secrets leak into logs | Recursive redaction, HASH_ONLY mode |
| Agent modifies policy or disables Kill Switch | Never exposed as a tool; registration fails |
| Process crashes mid-call | `DISPATCHING` is committed before the external call; RecoveryScanner reclaims stale attempts |

## Responsible Disclosure

We credit reporters in the changelog unless they prefer otherwise. We do not pursue legal action against good-faith research.
