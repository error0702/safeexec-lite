package fun.autorun.safeexec.core;

/**
 * Explicit three-state answer to "did the previous attempt take effect?".
 * NOT_FOUND is never treated as CONFIRMED_NOT_EXECUTED: only {@link ConfirmedNotApplied} permits a resend.
 */
public sealed interface RecoveryResult<O> {
    record Applied<O>(O result) implements RecoveryResult<O> {}
    record ConfirmedNotApplied<O>() implements RecoveryResult<O> {}
    record Indeterminate<O>(String reason) implements RecoveryResult<O> {}
}
