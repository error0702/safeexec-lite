package fun.autorun.safeexec.core;

import java.util.List;
import java.util.Map;

/** A business event the agent should react to, plus the tools it may propose. */
public record PlanningRequest(String eventType, Map<String, Object> event, List<ToolDescriptor> tools, AgentContext ctx) {}
