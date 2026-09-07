# 01 · Tool Gateway

## What goes wrong without it
The agent holds tool objects and calls them. Arguments come straight from the model. Nothing between "the model
said so" and "money left the account".

## Mechanism
`DefaultToolGateway` is the only path. Agent code gets a `ToolGateway`, never a `Tool`.

```text
REQUEST_RECEIVED → tool lookup → convert + validate → POLICY_DECIDED → shadow?
→ READ: execute directly (no intent)
→ WRITE/IRREVERSIBLE: intent → [approval?] → attempt → DISPATCHING (committed) → execute
→ SUCCEEDED | DEFINITIVE_FAILED | UNKNOWN → recovery
```

* Conversion uses a strict Jackson mapper (`FAIL_ON_UNKNOWN_PROPERTIES`) into the tool's declared input type, then
  Bean Validation. A model that adds a field it was not given fails validation before anything else.
* The gateway is deliberately **not** transactional. Every stage commits its own audit event; Intent/Attempt
  transitions commit before the external call. A crash between stages leaves a recoverable, visible state.
* `ToolResult` is a sealed type. There is no exception path that skips audit.

## Invariants
1. No tool executes without a `VALIDATED` and a `POLICY_DECIDED` event in its trace.
2. `DISPATCHING` is durable before the external system is called.
3. READ tools never create Intents; WRITE/IRREVERSIBLE never execute without one.
4. Tools named like the policy or kill switch cannot be registered (startup fails).

## Proved by
`GatewayDay3Test`: schema mismatch, unknown field, unknown tool, shadow blocks writes but not reads, event order,
same business key → HELD, explicit refusal → DEFINITIVE_FAILED, timeout → UNKNOWN then HELD, nested secret redacted.
`ToolRegistryTest`: `toggleKillSwitch` / `updatePolicy` rejected.
