package fun.autorun.safeexec.core;

import java.util.List;

/** What a planner is told about a tool: enough to call it, nothing about how it is guarded. */
public record ToolDescriptor(String name, ActionLevel level, String description, List<String> inputFields) {}
