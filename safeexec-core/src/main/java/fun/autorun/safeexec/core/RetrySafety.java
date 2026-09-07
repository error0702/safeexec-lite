package fun.autorun.safeexec.core;

/**
 * What SafeExec may do when an attempt ends in UNKNOWN (e.g. timeout after send).
 *
 * <ul>
 *   <li>{@link #EXTERNAL_IDEMPOTENCY_KEY}: the external system honours our idempotency key, so
 *       resending the same key is safe. UNKNOWN → RETRY_RELEASED → new attempt, same key.</li>
 *   <li>{@link #RECONCILE_BEFORE_RETRY}: we must ask the external system first via
 *       {@link ReconcilableTool#reconcile}. Only ConfirmedNotApplied allows a resend.</li>
 *   <li>{@link #NO_SAFE_RETRY}: any UNKNOWN goes straight to HOLD for a human.</li>
 * </ul>
 */
public enum RetrySafety { EXTERNAL_IDEMPOTENCY_KEY, RECONCILE_BEFORE_RETRY, NO_SAFE_RETRY }
