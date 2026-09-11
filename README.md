# SafeExec Lite

SafeExec prevents AI agents from turning retries, stale approvals, and unsafe tool calls into production incidents.

This is the **Lite** edition (MIT): the framework-free domain model of SafeExec for Spring Boot, plus the design notes.
It has no dependency on Spring, Spring AI or JPA, and it is what the Pro starter is built on.

```text
UNKNOWN ≠ FAILED
NOT_FOUND ≠ CONFIRMED_NOT_EXECUTED
APPROVED ≠ STILL_SAFE_TO_EXECUTE
```

## What is here

| Path | Content |
|---|---|
| `safeexec-lite-gateway/` | `LiteToolGateway`: strict record-based argument conversion (unknown fields rejected), pluggable validator, in-memory audit event stream, **in-memory Intent / Attempt idempotency and three-state recovery after a timeout**, `ChaosSupplier` (a supplier you can break on purpose), `Demo.main` |
| `safeexec-core/` | `Tool`, `ReconcilableTool`, `RecoveryResult` (Applied / ConfirmedNotApplied / Indeterminate), `RetrySafety`, `AttemptState` (incl. `RETRY_RELEASED`), `IntentStatus`, `PolicyFacts`, `PolicyEngine`, `Evidence`, `ApprovalStatus`, `AuditEvent`, `AuditStage`, `ToolGateway`, `ToolRegistry` (refuses policy / kill-switch names), `AgentPlanner` |
| `docs/design/` | Six design notes: gateway, policy, intent/attempt/recovery, approval/evidence, audit, shadow |
| `SECURITY.md` | Security model and threat model |

## Run it

Java 21. No database, no API key, no network.

```bash
mvn -q -DskipTests install
mvn -q -pl safeexec-lite-gateway exec:java
mvn -q test
```

The demo is scenario 1 from the landing page: **"The request timed out. Did you just purchase twice?"**

```text
3. The supplier applies the purchase, then the response is lost (timeout)
   → Executed PurchaseOrder[poNumber=PO-3523594d, orderId=o-1003, ...]  (intent int_9a3447df, attempt att_af74af0f)
   supplier requests: +1   purchase orders: +1

4. The agent asks for order o-1003 again
   → Held: intent int_9a3447df is FULFILLED  — nothing was sent
   supplier requests: +0   purchase orders: +0

What the audit stream recorded for order o-1003:
   ATTEMPT_CREATED
   DISPATCHING
   UNKNOWN          UNKNOWN            SocketTimeoutException: read timed out after commit
   RECONCILING
   RECONCILED       SUCCEEDED          Applied: side effect confirmed, nothing resent
   HELD             HELD               intent int_9a3447df is FULFILLED

Supplier: 2 requests received, 2 purchase orders exist, for 2 distinct orders. Not 3.
```

What happens after `UNKNOWN` depends on the tool's `RetrySafety`:

```text
UNKNOWN (sent, result lost)
  ├─ EXTERNAL_IDEMPOTENCY_KEY → RETRY_RELEASED → next attempt, same external key
  ├─ RECONCILE_BEFORE_RETRY  → reconcile():  Applied             → done, nothing resent
  │                                          ConfirmedNotApplied → next attempt, same external key
  │                                          Indeterminate       → HOLD, a human decides
  └─ NO_SAFE_RETRY           → HOLD
```

`LiteRecoveryTest` covers each branch with the same names as Pro's `RecoveryScenariosTest`: timeout-but-applied
is not resent, confirmed-not-applied resends once with the same key, a lagging lookup holds instead of
duplicating, external-key retries are bounded, `NO_SAFE_RETRY` holds immediately, and the same business key
executes at most once.

## Where Lite stops

Lite proves the semantics inside one process. Everything is in memory, so a crash between the HTTP call and
the bookkeeping loses the evidence that a side effect may exist — which is exactly the case Pro is built for:

| | Lite (MIT) | Pro |
|---|---|---|
| Strict schema, audit stream, `UNKNOWN ≠ FAILED` | ✓ | ✓ |
| Intent / Attempt at-most-once, three-state reconcile, same-key resend | in memory | PostgreSQL: `DISPATCHING` committed before the call, CAS as SQL, partial unique index |
| Crash recovery (`RecoveryScanner`, `FOR UPDATE SKIP LOCKED`) | | ✓ |
| Manual reconcile of a held intent | | ✓ |
| YAML policy engine, kill switch, fail-closed | | ✓ |
| Approval bound to an immutable intent, evidence freshness | | ✓ |
| Shadow mode with agreement stats | | ✓ |
| Append-only audit by trigger, trace replay | | ✓ |

## What is in Pro

The Spring Boot starter that implements all of it against PostgreSQL 16, with a purchase-agent example, 80+
fault-injection tests and five runnable scenario scripts.

→ https://autorun.fun

## Security

See [SECURITY.md](SECURITY.md). Report vulnerabilities to security@autorun.fun.

## License

MIT. Copyright (c) 2026 autorun.fun.
