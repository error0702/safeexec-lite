package fun.autorun.safeexec.core;

/** A tool that can ask the external system whether a previous attempt actually took effect. */
public interface ReconcilableTool<I, O> extends Tool<I, O> {
    RecoveryResult<O> reconcile(String externalIdempotencyKey, ToolContext ctx);
}
