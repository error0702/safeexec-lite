package fun.autorun.safeexec.core;

/**
 * A side-effecting capability the agent may request. Agent code never holds a Tool instance;
 * every call goes through {@link ToolGateway}.
 */
public interface Tool<I, O> {
    String name();
    ActionLevel level();
    RetrySafety retrySafety();
    Class<I> inputType();
    /** Translate business input into the generic facts the Policy Engine understands. */
    PolicyFacts policyFacts(I input, ToolContext ctx);
    /**
     * The external facts this action depends on (a quote, a stock level, an exchange rate). They are shown to the
     * approver, hashed into the approval, and re-checked for freshness and equality right before execution.
     */
    default java.util.List<Evidence> evidence(I input, ToolContext ctx) { return java.util.List.of(); }
    O execute(I input, ToolContext ctx);
}
