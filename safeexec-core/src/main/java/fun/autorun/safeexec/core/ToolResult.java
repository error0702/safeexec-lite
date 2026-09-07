package fun.autorun.safeexec.core;

public sealed interface ToolResult {
    record Executed(Object output, String intentId, String attemptId) implements ToolResult {}
    record Denied(String reason, String ruleId) implements ToolResult {}
    /** reason is null on first request; on re-approval it says why the previous approval was superseded. */
    record ApprovalRequired(String approvalId, String intentId, String reason) implements ToolResult {
        public ApprovalRequired(String approvalId, String intentId) { this(approvalId, intentId, null); }
    }
    record ValidationError(String message) implements ToolResult {}
    record UnknownTool(String toolName) implements ToolResult {}
    /** shadowLogId points at the record a reviewer later completes with the human decision. */
    record ShadowBlocked(String shadowLogId) implements ToolResult {}
    record Failed(String reason, String intentId, String attemptId) implements ToolResult {}
    record Unknown(String reason, String intentId, String attemptId) implements ToolResult {}
    record Held(String reason, String intentId) implements ToolResult {}
}
