package fun.autorun.safeexec.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of tools the agent may request. Registration is guarded: the policy engine and the kill switch
 * must never be reachable as tools, so any tool whose name or group starts with a reserved prefix is rejected
 * and the application fails to start.
 */
public final class ToolRegistry {
    public static final List<String> RESERVED_PREFIXES = List.of("policy", "killswitch", "kill_switch", "safeexec");

    private final Map<String, Tool<?, ?>> tools = new LinkedHashMap<>();

    public synchronized void register(Tool<?, ?> tool) {
        String name = tool.name();
        if (name == null || name.isBlank()) throw new ToolRegistrationException("tool name is blank");
        String lower = name.toLowerCase(Locale.ROOT);
        for (String p : RESERVED_PREFIXES) {
            if (lower.startsWith(p) || lower.contains("killswitch") || lower.contains("kill_switch") || lower.contains("policy")) {
                throw new ToolRegistrationException("tool '" + name + "' uses a reserved name: policy and kill switch are never agent tools");
            }
        }
        if (tools.putIfAbsent(name, tool) != null) throw new ToolRegistrationException("duplicate tool name: " + name);
    }

    public Optional<Tool<?, ?>> find(String name) { return Optional.ofNullable(tools.get(name)); }
    public Map<String, Tool<?, ?>> all() { return Collections.unmodifiableMap(tools); }
}
