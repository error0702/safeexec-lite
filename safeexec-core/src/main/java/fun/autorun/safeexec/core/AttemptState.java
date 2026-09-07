package fun.autorun.safeexec.core;

import java.util.EnumSet;
import java.util.Set;

/**
 * Attempt states are classified by "could a side effect already exist?", not by success/failure.
 * LIVE states are covered by the partial unique index ux_one_live_attempt.
 */
public enum AttemptState {
    CREATED,               // reserved, not yet sent            – no side effect
    DISPATCHING,           // sent, awaiting result             – side effect possible
    SUCCEEDED,             // external system confirmed         – side effect
    DEFINITIVE_FAILED,     // external system explicitly refused – no side effect, new attempt allowed
    ABORTED_BEFORE_SEND,   // stopped before sending            – no side effect, new attempt allowed
    UNKNOWN,               // sent, result indeterminate        – side effect possible
    RECONCILING,           // asking the external system        – side effect possible
    RETRY_RELEASED;        // side effect unknown, but the external system guarantees same-key replay is safe → next attempt may proceed

    public static final Set<AttemptState> LIVE = EnumSet.of(CREATED, DISPATCHING, UNKNOWN, RECONCILING);
    public boolean isLive() { return LIVE.contains(this); }
}
