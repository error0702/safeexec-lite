package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.AttemptState;
import fun.autorun.safeexec.core.IntentStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Intent / Attempt bookkeeping in memory, with the same transitions Pro enforces in PostgreSQL.
 *
 * <p>An <b>Intent</b> is one business action ("purchase for order 123"), identified by a business key and carrying
 * one external idempotency key that every attempt sends unchanged. An <b>Attempt</b> is one try. Reserving an
 * attempt is a compare-and-set {@code OPEN → EXECUTING}; a second live attempt is refused.</p>
 *
 * <p>Lite proves the semantics inside one process. Pro makes them survive a crash: rows committed before the
 * external call, the CAS as a single SQL {@code UPDATE}, a partial unique index as a second guard, and a scanner
 * that reclaims stale {@code DISPATCHING} attempts after a restart.</p>
 */
public final class InMemoryIntents {

    public static final class Intent {
        private final String id, businessKey, toolName, externalIdempotencyKey;
        private volatile IntentStatus status = IntentStatus.OPEN;
        private volatile String holdReason;
        private final List<Attempt> attempts = new CopyOnWriteArrayList<>();

        private Intent(String id, String businessKey, String toolName, String externalIdempotencyKey) {
            this.id = id; this.businessKey = businessKey; this.toolName = toolName; this.externalIdempotencyKey = externalIdempotencyKey;
        }
        public String id() { return id; }
        public String businessKey() { return businessKey; }
        public String toolName() { return toolName; }
        public String externalIdempotencyKey() { return externalIdempotencyKey; }
        public IntentStatus status() { return status; }
        public Optional<String> holdReason() { return Optional.ofNullable(holdReason); }
        public List<Attempt> attempts() { return List.copyOf(attempts); }
        @Override public String toString() { return id + " " + status + " attempts=" + attempts; }
    }

    public static final class Attempt {
        private final String id; private final int attemptNo;
        private volatile AttemptState state = AttemptState.CREATED;
        private volatile String detail;
        private Attempt(String id, int attemptNo) { this.id = id; this.attemptNo = attemptNo; }
        public String id() { return id; }
        public int attemptNo() { return attemptNo; }
        public AttemptState state() { return state; }
        public Optional<String> detail() { return Optional.ofNullable(detail); }
        @Override public String toString() { return "#" + attemptNo + " " + state; }
    }

    /** Thrown by {@link #reserve} when the CAS fails: the intent is not OPEN, or a live attempt already exists. */
    public static final class IntentNotOpenException extends RuntimeException {
        public IntentNotOpenException(String m) { super(m); }
    }

    private final Map<String, Intent> byBusinessKey = new LinkedHashMap<>();
    private final Map<String, Intent> byId = new LinkedHashMap<>();

    public synchronized Intent findOrCreate(String businessKey, String toolName) {
        Intent existing = byBusinessKey.get(businessKey);
        if (existing != null) {
            if (!existing.toolName.equals(toolName))
                throw new IllegalStateException("business key '" + businessKey + "' already belongs to tool " + existing.toolName);
            return existing;
        }
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Intent i = new Intent("int_" + suffix, businessKey, toolName, "idem_" + suffix);
        byBusinessKey.put(businessKey, i);
        byId.put(i.id, i);
        return i;
    }

    /** CAS {@code OPEN → EXECUTING} and create the next attempt. Pro does this in one SQL UPDATE plus a partial unique index. */
    public synchronized Attempt reserve(Intent i) {
        if (i.status != IntentStatus.OPEN)
            throw new IntentNotOpenException("intent " + i.id + " is " + i.status + (i.holdReason == null ? "" : " (" + i.holdReason + ")"));
        if (i.attempts.stream().anyMatch(a -> a.state.isLive()))
            throw new IntentNotOpenException("intent " + i.id + " already has a live attempt");
        i.status = IntentStatus.EXECUTING;
        Attempt a = new Attempt("att_" + UUID.randomUUID().toString().substring(0, 8), i.attempts.size() + 1);
        i.attempts.add(a);
        return a;
    }

    public synchronized void dispatching(Attempt a) { a.state = AttemptState.DISPATCHING; }
    public synchronized void succeeded(Intent i, Attempt a, String detail) { a.state = AttemptState.SUCCEEDED; a.detail = detail; i.status = IntentStatus.FULFILLED; }
    public synchronized void definitiveFailed(Intent i, Attempt a, String detail) { a.state = AttemptState.DEFINITIVE_FAILED; a.detail = detail; i.status = IntentStatus.OPEN; }
    public synchronized void unknown(Attempt a, String detail) { a.state = AttemptState.UNKNOWN; a.detail = detail; }
    public synchronized void reconciling(Attempt a) { a.state = AttemptState.RECONCILING; }
    /** Reconcile could not answer: the attempt stays UNKNOWN (live, so no new attempt) and the intent goes to HOLD. */
    public synchronized void indeterminate(Intent i, Attempt a, String reason) { a.state = AttemptState.UNKNOWN; a.detail = reason; hold(i, reason); }
    /** The external system honours our idempotency key: the next attempt may resend with the same key. */
    public synchronized void releaseForRetry(Intent i, Attempt a) { a.state = AttemptState.RETRY_RELEASED; i.status = IntentStatus.OPEN; }
    public synchronized void hold(Intent i, String reason) { i.status = IntentStatus.HOLD; i.holdReason = reason; }

    public synchronized Optional<Intent> find(String intentId) { return Optional.ofNullable(byId.get(intentId)); }
    public synchronized Optional<Intent> findByBusinessKey(String businessKey) { return Optional.ofNullable(byBusinessKey.get(businessKey)); }
    public synchronized List<Intent> all() { return Collections.unmodifiableList(new ArrayList<>(byId.values())); }
}
