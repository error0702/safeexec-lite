# 06 · Shadow

## What goes wrong without it
Staging passed. Production has different data, different volumes and real consequences. The first real
decision is also the first irreversible one.

## Mechanism
`ConfiguredShadowMode` blocks WRITE and IRREVERSIBLE calls globally or per policy group. A blocked call still
passes schema and policy, then writes a `shadow_log` row (tool, params, facts, policy result, `agent_decision =
EXECUTE`) and returns `ShadowBlocked(shadowLogId)`. No Intent is created; the supplier sees nothing.

Reviewers record what actually happened (`EXECUTE`, `SKIP`, `EXECUTE_MODIFIED`) through `/api/shadow/{id}/human-decision`.
`/api/shadow/stats` reports samples and agreement per tool. Releasing a group (`PUT /api/admin/shadow`) is how
permission is granted, one class of action at a time, against a measured number rather than a feeling.

The thresholds (95% for information tools, 99% for purchases, 100% for commitment detection) live in the release
checklist, not in code: they are a management decision.

## Invariants
1. Shadow never touches READ tools.
2. A shadow log cannot be decided twice.
3. Reviewer identity comes from the principal.

## Proved by
`ShadowDay11Test` (10 calls → 0 side effects, 8/2 → 0.8, per group, reviewer endpoint, admin release),
scenario 4 script on a live instance.
