package fun.autorun.safeexec.core;

/**
 * Per-invocation context handed to a tool. The external idempotency key is fixed per Intent and
 * identical across every Attempt of that Intent.
 */
public record ToolContext(String traceId, String intentId, String attemptId, int attemptNo, String externalIdempotencyKey, AgentContext agent) {}
