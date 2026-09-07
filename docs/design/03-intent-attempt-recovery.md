# 03 · Intent, Attempt, Recovery

## What goes wrong without it
A timeout is treated as a failure and retried. The supplier had already processed the first request. Two purchase
orders exist and nobody knows why.

## Mechanism
Two entities. **Intent** = one business action, keyed by `business_key`, carrying one immutable
`external_idempotency_key`. **Attempt** = one try, keyed by `intent_id + attempt_no`.

Intent status: `OPEN → EXECUTING → FULFILLED | HOLD | CANCELLED`. Reserving an attempt is a compare-and-set
`OPEN → EXECUTING` in SQL; losers get `IntentConflictException`. A second guard, the partial unique index
`ux_one_live_attempt`, rejects a second LIVE attempt even if application code is bypassed.

Attempt state is classified by *could a side effect exist*, not by success:

| state | side effect possible | next |
|---|---|---|
| CREATED, DISPATCHING | ·, yes | in flight |
| SUCCEEDED | yes | Intent FULFILLED |
| DEFINITIVE_FAILED, ABORTED_BEFORE_SEND | no | Intent OPEN, resend allowed |
| UNKNOWN, RECONCILING | yes | recovery decides |
| RETRY_RELEASED | unknown, but same-key replay is safe | next attempt |

After UNKNOWN, `RecoveryService` decides by the tool's `RetrySafety`:

```text
EXTERNAL_IDEMPOTENCY_KEY → RETRY_RELEASED → resend with the same key
RECONCILE_BEFORE_RETRY  → reconcile(): Applied | ConfirmedNotApplied | Indeterminate
NO_SAFE_RETRY           → HOLD
```

Only `ConfirmedNotApplied` permits a resend. `Indeterminate` (lookup empty but not authoritative, lookup failed)
holds. Resends are bounded (`max-attempts`, enforced in the retry gate) and each one re-checks evidence
freshness and current policy. `RecoveryScanner` reclaims stale `DISPATCHING` rows after a crash with
`FOR UPDATE SKIP LOCKED`, so several instances never double-process.

Intent parameters are immutable once the Intent leaves OPEN, by trigger. A changed parameter is a new Intent.

## Invariants
1. One business key → at most one non-cancelled Intent → at most one side effect.
2. All attempts of an Intent send the same external key.
3. No automatic resend after `Indeterminate` or under `NO_SAFE_RETRY`.
4. Recovery never happens inside the gateway's transaction; every transition is committed on its own.

## Proved by
`IntentCasTest`, `DatabaseGuardsTest`, `IntentImmutabilityTest`, `RecoveryScenariosTest` (7 scenarios incl.
lagging lookup), `RecoveryScannerTest` (crash before/after send, NO_SAFE_RETRY, fresh untouched, two scanners).
