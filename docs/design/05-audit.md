# 05 · Audit

## What goes wrong without it
"Write a log line after it succeeds." The process dies after the supplier confirmed and before the line.
The one record that mattered is the one that is missing. Or: the log is a table someone can UPDATE.

## Mechanism
The audit is an **event stream**, not a summary. `JpaAuditSink.append` runs in `REQUIRES_NEW` and commits before
returning; the gateway appends one event per stage. `DISPATCHING` therefore exists before the external call is
made, which is what makes crash recovery possible at all.

`audit_event` is append-only by database trigger (`UPDATE`/`DELETE` raise `restrict_violation`). An optional role
script (`db/optional/audit_writer_role.sql`) adds a privilege-based second layer.

Input handling: every event carries `input_hash`. `safeexec.audit.input-mode` selects `NONE`, `HASH_ONLY`,
`REDACTED` (default, recursive and case-insensitive over nested objects and arrays) or `FULL`.

`GET /api/audit/trace/{traceId}` replays a trace in sequence. Traces link to intent, attempt, approval and the
external key, so an incident can be reconstructed from the business key alone.

## Invariants
1. No stage of the gateway or the scanner runs without an event.
2. Events are never modified; the database enforces it.
3. Secrets do not reach the table under the default mode.

## Proved by
`DatabaseGuardsTest` (UPDATE/DELETE rejected), `GatewayDay3Test` (event order, nested redaction),
`AuditHashOnlyModeTest`, `AuditFullModeTest`, `RecoveryScannerTest` (crash trace continues under `rcv_`),
scenario 5 script (12-step replay on a live instance).
