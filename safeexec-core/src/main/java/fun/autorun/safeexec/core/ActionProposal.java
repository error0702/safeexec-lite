package fun.autorun.safeexec.core;

import java.util.List;

/** Output of an AgentPlanner: zero or more tool calls plus a human-readable rationale. Proposals never execute themselves. */
public record ActionProposal(List<ToolCall> calls, String rationale) {}
