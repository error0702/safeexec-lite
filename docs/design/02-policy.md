# 02 · Policy Engine

## What goes wrong without it
Limits end up as `if` statements inside tools, each tool parsing its own JSON, nobody able to say what the current
rules are, and a missing config file meaning "no rules".

## Mechanism
Tools translate their input into `PolicyFacts` (money, group, attributes). The engine sees facts and
`AgentContext` (environment, actor attributes) and nothing else. Evaluation order, first match wins:

```text
kill_switch (global | group)   → DENY
context_rules[i]               → rule's decision
limits[tool] | limits[group:g] → REQUIRE_APPROVAL above max, DENY on currency mismatch or missing fact
defaults[level]                → ALLOW / REQUIRE_APPROVAL / DENY
```

The file is strict YAML: unknown keys, unknown decisions, non-numeric limits are parse errors. An unparseable
policy is **discarded**, not kept: the engine reports `POLICY_UNAVAILABLE` and denies WRITE and IRREVERSIBLE
(READ follows `read-on-failure`, default ALLOW). Every decision records the policy version it was made under.

No expression language. Context rules are structured fields (`environment`, `action_level`, `group`, `tool`,
`actor_attributes`). Daily quotas are out of scope until reservations exist (v0.2).

## Invariants
1. The engine never reads business input.
2. A rule id (`limits.createPurchase`, `context_rules[1]`, `kill_switch.global`, `fail_closed`) accompanies every decision.
3. Kill switch and policy have no tool surface; changes go through `/api/admin/**` (SAFEEXEC_ADMIN) or the file.

## Proved by
`PolicyDay4Test`: 299 vs 301, currency mismatch, missing fact, kill switch global and per group, version per
decision, environment rule, actor-attribute rule, corrupted policy fails closed, strict parsing.
`AdminEndpointTest`: admin flips the switch, reviewer cannot, anonymous is 401.
