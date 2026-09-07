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
| `safeexec-core/` | `Tool`, `ReconcilableTool`, `RecoveryResult` (Applied / ConfirmedNotApplied / Indeterminate), `RetrySafety`, `AttemptState` (incl. `RETRY_RELEASED`), `IntentStatus`, `PolicyFacts`, `PolicyEngine`, `Evidence`, `ApprovalStatus`, `AuditEvent`, `AuditStage`, `ToolGateway`, `ToolRegistry` (refuses policy / kill-switch names), `AgentPlanner` |
| `docs/design/` | Six design notes: gateway, policy, intent/attempt/recovery, approval/evidence, audit, shadow |
| `SECURITY.md` | Security model and threat model |

```bash
./mvnw -q test   # or: mvn -q test
```

## What is in Pro

The Spring Boot starter that implements all of it against PostgreSQL 16: Tool Gateway with strict schema validation,
YAML Policy Engine with kill switch and fail-closed, Intent / Attempt idempotency enforced by database constraints,
three-state recovery and a crash-recovery scanner (`FOR UPDATE SKIP LOCKED`), approvals bound to immutable intents with
an eight-step execution-start transaction, append-only audit event stream, shadow mode with agreement stats, a
purchase-agent example with a chaos supplier, 80+ fault-injection tests and five runnable scenario scripts.

→ https://autorun.fun

## Security

See [SECURITY.md](SECURITY.md). Report vulnerabilities to security@autorun.fun.

## License

MIT. Copyright (c) 2026 autorun.fun.
