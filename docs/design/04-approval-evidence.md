# 04 · Approval and Evidence

## What goes wrong without it
A human approves what they saw. Between the click and the execution, the agent re-plans, a quote changes, a
policy tightens, or the same approval is replayed twice. The record says "approved"; the outcome is not what
was approved.

## Mechanism
An approval is a statement about an **immutable Intent**, never about a network attempt. It freezes:

* the Intent parameters (immutable by trigger),
* the `PolicyFacts`,
* the **values** of every `Evidence` the tool declared (a quote, a stock level), each with `observed_at` and `valid_until`,

into one `content_hash`. The approver sees exactly these on `/ui/approvals`. Identity comes from the security
context; a body field claiming to be the CEO is ignored.

`ToolGateway.resume(approvalId)` runs the execution-start transaction. All eight steps commit together or not at all:

```text
1 approval APPROVED, unexpired, unconsumed
2 every approved evidence still within valid_until      → else STALE_EVIDENCE
3 hash(params, facts, CURRENT evidence values) == approved  → else HASH_MISMATCH (with diff)
4 current policy re-evaluated, DENY fails               → POLICY_DENIED_AT_EXECUTION
5 approval atomically BOUND to the intent (UPDATE ... WHERE status=APPROVED AND consumed IS NULL)
6 intent CAS OPEN → EXECUTING
7 attempt CREATED
8 commit
```

2–4 failing supersedes the approval and creates a new PENDING one carrying the reason; 1 and 5 refuse; 6 failing
rolls the bind back. Retries of a bound Intent re-run 2 and 4 only (`startRetry`); they never re-approve, because
a network retry is not a new decision.

## Invariants
1. `APPROVED ≠ STILL_SAFE_TO_EXECUTE`: the same hash with stale evidence, or a tightened policy, does not execute.
2. One approval executes at most once (`bind` is a single-row CAS).
3. Approval is never in the runtime path of READ tools or of ALLOW decisions.

## Proved by
`ApprovalDay7Test` (9 scenarios incl. CSRF and identity) and `ApprovalDay8Test` (retry without re-approval,
stale at retry, denied at retry, concurrent resume, rollback on CAS failure).
