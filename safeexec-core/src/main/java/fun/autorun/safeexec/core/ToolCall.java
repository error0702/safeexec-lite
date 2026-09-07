package fun.autorun.safeexec.core;

import java.util.Map;

/**
 * What the planner (LLM or rules) asked for. Raw arguments are validated before anything else.
 *
 * @param businessKey optional key that identifies the business action ("order-123:line-1"). Two calls with the
 *                    same business key map to the same Intent and therefore can execute at most once. When null,
 *                    the gateway derives one from the tool name and a hash of the arguments.
 */
public record ToolCall(String toolName, Map<String, Object> rawArguments, String businessKey) {
    public ToolCall(String toolName, Map<String, Object> rawArguments) { this(toolName, rawArguments, null); }
}
