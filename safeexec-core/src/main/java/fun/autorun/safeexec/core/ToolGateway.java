package fun.autorun.safeexec.core;

/** The single entry point for executing a tool call. Schema → Policy → Idempotency → Approval → Execute, with audit at every stage. */
public interface ToolGateway {
    /** Propose and, if policy allows, execute. REQUIRE_APPROVAL returns ApprovalRequired and executes nothing. */
    ToolResult invoke(ToolCall call, AgentContext ctx);

    /**
     * Continue an approved action. Runs the execution-start transaction (approval valid → evidence fresh →
     * content hash unchanged → policy re-evaluated → approval bound → intent CAS → attempt created → commit)
     * and only then touches the outside world.
     */
    ToolResult resume(String approvalId, AgentContext ctx);

    /**
     * Continue an OPEN Intent that already has history (a previous attempt ended DEFINITIVE_FAILED or
     * RETRY_RELEASED). Runs the retry gate, then the bounded attempt loop. Used by the RecoveryScanner after
     * a crash; never re-approves.
     */
    ToolResult retry(String intentId, AgentContext ctx);
}
