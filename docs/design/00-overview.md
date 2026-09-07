# SafeExec design notes

Six pages, one per module. Each page answers the same four questions: what goes wrong without it, what the
mechanism is, which invariants hold, and which tests prove them. The tests are the specification; if a page and
a test disagree, the test wins.

```text
UNKNOWN ≠ FAILED
NOT_FOUND ≠ CONFIRMED_NOT_EXECUTED
APPROVED ≠ STILL_SAFE_TO_EXECUTE
```

| Page | Module | Proves |
|---|---|---|
| 01 | Tool Gateway | Every call passes schema → policy → shadow → intent → dispatch, and each stage commits its own audit event |
| 02 | Policy Engine | Facts not JSON, first-match rules, single-action limits, kill switch, fail closed |
| 03 | Intent, Attempt, Recovery | At-most-once by database constraint; three-state reconcile; bounded retries; crash recovery |
| 04 | Approval and Evidence | Approval binds to an immutable Intent; eight-step start transaction; re-approval on drift |
| 05 | Audit | Event stream committed per stage, append-only by trigger, redacted by default, replayable |
| 06 | Shadow | Real decisions without side effects; measured agreement before any permission is released |

Non-goals, on purpose: no orchestration, no prompt management, no UI beyond one approval page, no telemetry,
no license server, no expression language in policy, no daily quotas (needs reservations; v0.2).
