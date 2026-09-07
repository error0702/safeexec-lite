package fun.autorun.safeexec.core;

/**
 * Intent is the final guard for "this business action happens at most once".
 * Only OPEN → EXECUTING is allowed via an atomic compare-and-set.
 */
public enum IntentStatus { OPEN, EXECUTING, HOLD, FULFILLED, CANCELLED }
