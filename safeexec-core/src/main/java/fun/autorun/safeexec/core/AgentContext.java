package fun.autorun.safeexec.core;

import java.util.Map;

/** Who is acting and where. Actor identity must come from the security context, never from the model. */
public record AgentContext(String agentName, String actor, String environment, Map<String, String> attributes) {
    public static AgentContext of(String agentName, String actor, String environment) {
        return new AgentContext(agentName, actor, environment, Map.of());
    }
}
