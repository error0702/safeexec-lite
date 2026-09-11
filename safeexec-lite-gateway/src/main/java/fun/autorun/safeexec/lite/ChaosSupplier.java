package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.DefinitiveFailureException;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A supplier you can break on purpose. It is idempotent on its side (same key → same purchase order), like any
 * well-behaved payment or order API. The interesting modes are the ones where the request is applied and the
 * response is lost.
 */
public final class ChaosSupplier {

    public enum Mode {
        NORMAL,                   // creates the PO and returns it
        REJECT,                   // explicit 4xx-style refusal, nothing created
        TIMEOUT_NOT_APPLIED,      // throws, and nothing was created
        TIMEOUT_APPLIED,          // creates the PO, then throws (the classic "did it go through?")
        TIMEOUT_APPLIED_THEN_LAG, // creates the PO, throws, and from then on lookups are empty and NOT authoritative
        LOOKUP_LAGS               // create works, but lookups are empty and not authoritative (read replica lag)
    }

    public record PurchaseOrder(String poNumber, String orderId, String supplierId, long total) {}

    private Mode mode = Mode.NORMAL;
    /** How many more create() calls the current mode applies to before reverting to NORMAL. -1 = forever. */
    private int remaining = -1;
    private final Map<String, PurchaseOrder> byKey = new LinkedHashMap<>();
    private final List<String> requestsReceived = new ArrayList<>();
    private int lookups;

    public synchronized void setMode(Mode m) { mode = m; remaining = -1; }
    /** Apply the mode to the next {@code times} create() calls, then revert to NORMAL. */
    public synchronized void setMode(Mode m, int times) { mode = m; remaining = times; }
    public synchronized Mode mode() { return mode; }
    public synchronized void reset() { mode = Mode.NORMAL; remaining = -1; byKey.clear(); requestsReceived.clear(); lookups = 0; }

    /** Every idempotency key the supplier has seen, in order, duplicates included. */
    public synchronized List<String> requestsReceived() { return List.copyOf(requestsReceived); }
    /** Purchase orders that actually exist on the supplier's side. */
    public synchronized int effectivePurchases() { return byKey.size(); }
    public synchronized int lookupCalls() { return lookups; }

    private Mode consumeMode() {
        Mode m = mode;
        if (remaining > 0 && --remaining == 0) mode = Mode.NORMAL;
        return m;
    }

    /** May throw {@link DefinitiveFailureException} (explicit refusal) or any RuntimeException (result unknown). */
    public synchronized PurchaseOrder create(String orderId, String supplierId, long total, String idempotencyKey) {
        requestsReceived.add(idempotencyKey);
        Mode m = consumeMode();
        switch (m) {
            case REJECT -> throw new DefinitiveFailureException("supplier refused: 422 UNPROCESSABLE");
            case TIMEOUT_NOT_APPLIED -> throw new RuntimeException(new SocketTimeoutException("read timed out"));
            default -> { }
        }
        PurchaseOrder po = byKey.computeIfAbsent(idempotencyKey,
                k -> new PurchaseOrder("PO-" + UUID.randomUUID().toString().substring(0, 8), orderId, supplierId, total));
        if (m == Mode.TIMEOUT_APPLIED_THEN_LAG) {
            mode = Mode.LOOKUP_LAGS; remaining = -1;
            throw new RuntimeException(new SocketTimeoutException("read timed out after commit"));
        }
        if (m == Mode.TIMEOUT_APPLIED) throw new RuntimeException(new SocketTimeoutException("read timed out after commit"));
        return po;
    }

    public synchronized Optional<PurchaseOrder> lookupByKey(String idempotencyKey) {
        lookups++;
        if (mode == Mode.LOOKUP_LAGS) return Optional.empty();
        return Optional.ofNullable(byKey.get(idempotencyKey));
    }

    /** Whether an empty lookup is proof of absence. A lagging read replica means "not found" can trail "created". */
    public synchronized boolean lookupIsAuthoritative() { return mode != Mode.LOOKUP_LAGS; }
}
