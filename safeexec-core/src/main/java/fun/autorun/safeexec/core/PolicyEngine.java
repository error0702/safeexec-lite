package fun.autorun.safeexec.core;

/** Decides whether a tool call may proceed. Sees only PolicyFacts, never business input. Must fail closed. */
public interface PolicyEngine {
    PolicyDecision evaluate(Tool<?, ?> tool, PolicyFacts facts, AgentContext ctx);
    int currentVersion();
}
