package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.*;
import fun.autorun.safeexec.lite.InMemoryIntents.Intent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The Lite counterpart of Pro's RecoveryScenariosTest: same names, same assertions, in memory instead of PostgreSQL. */
class LiteRecoveryTest {
    ChaosSupplier supplier;
    InMemoryAuditSink audit;
    LiteToolGateway gateway;
    final AgentContext agent = AgentContext.of("t", "agent:t", "test");

    @BeforeEach void setUp() { supplier = new ChaosSupplier(); gateway = gatewayFor(RetrySafety.RECONCILE_BEFORE_RETRY); }

    private LiteToolGateway gatewayFor(RetrySafety retrySafety) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new PurchaseTool(supplier, retrySafety));
        audit = new InMemoryAuditSink();
        return new LiteToolGateway(registry, audit, InputValidator.none(), new InMemoryIntents(), 3);
    }

    private static ToolCall purchase(String orderId) {
        return new ToolCall("createPurchase", Map.of("orderId", orderId, "supplierId", "SUP-01", "total", 100), "order:" + orderId);
    }
    private List<AuditStage> stages() { return audit.all().stream().map(AuditEvent::stage).toList(); }
    private Intent intentOf(ToolResult r) {
        String id = switch (r) {
            case ToolResult.Executed e -> e.intentId();
            case ToolResult.Held h -> h.intentId();
            case ToolResult.Failed f -> f.intentId();
            default -> throw new AssertionError("no intent on " + r);
        };
        return gateway.intents().find(id).orElseThrow();
    }
    private List<AttemptState> attemptStates(Intent i) { return i.attempts().stream().map(InMemoryIntents.Attempt::state).toList(); }

    @Test
    @DisplayName("Timeout but actually succeeded; reconcile → Applied: no resend, intent FULFILLED, supplier saw one request")
    void timeoutButAppliedIsNotResent() {
        supplier.setMode(ChaosSupplier.Mode.TIMEOUT_APPLIED, 1);
        ToolResult r = gateway.invoke(purchase("o1"), agent);

        assertThat(r).isInstanceOf(ToolResult.Executed.class);
        assertThat(((ToolResult.Executed) r).output()).isInstanceOf(ChaosSupplier.PurchaseOrder.class);
        assertThat(supplier.requestsReceived()).hasSize(1);
        assertThat(supplier.effectivePurchases()).isEqualTo(1);
        assertThat(supplier.lookupCalls()).isEqualTo(1);
        Intent i = intentOf(r);
        assertThat(i.status()).isEqualTo(IntentStatus.FULFILLED);
        assertThat(attemptStates(i)).containsExactly(AttemptState.SUCCEEDED);
        assertThat(stages()).containsSubsequence(AuditStage.DISPATCHING, AuditStage.UNKNOWN, AuditStage.RECONCILING, AuditStage.RECONCILED)
                .doesNotContain(AuditStage.RETRY_RELEASED);
    }

    @Test
    @DisplayName("Timeout and not executed; reconcile → ConfirmedNotApplied: exactly one resend, both requests carry the same external key")
    void confirmedNotAppliedResendsOnceWithSameKey() {
        supplier.setMode(ChaosSupplier.Mode.TIMEOUT_NOT_APPLIED, 1);
        ToolResult r = gateway.invoke(purchase("o1"), agent);

        assertThat(r).isInstanceOf(ToolResult.Executed.class);
        assertThat(supplier.requestsReceived()).hasSize(2);
        assertThat(supplier.requestsReceived().stream().distinct()).hasSize(1);
        assertThat(supplier.effectivePurchases()).isEqualTo(1);
        Intent i = intentOf(r);
        assertThat(i.status()).isEqualTo(IntentStatus.FULFILLED);
        assertThat(attemptStates(i)).containsExactly(AttemptState.DEFINITIVE_FAILED, AttemptState.SUCCEEDED);
        assertThat(supplier.requestsReceived().get(0)).isEqualTo(i.externalIdempotencyKey());
    }

    @Test
    @DisplayName("Timeout; reconcile → Indeterminate (lookup lags): attempt UNKNOWN, intent HOLD, no automatic retry, and a repeat call is refused")
    void indeterminateHoldsAndNeverRetries() {
        supplier.setMode(ChaosSupplier.Mode.TIMEOUT_APPLIED_THEN_LAG, 1);
        ToolResult r = gateway.invoke(purchase("o1"), agent);

        assertThat(r).isInstanceOf(ToolResult.Held.class);
        assertThat(((ToolResult.Held) r).reason()).contains("Indeterminate");
        assertThat(supplier.requestsReceived()).hasSize(1);
        Intent i = intentOf(r);
        assertThat(i.status()).isEqualTo(IntentStatus.HOLD);
        assertThat(attemptStates(i)).containsExactly(AttemptState.UNKNOWN);
        assertThat(stages()).doesNotContain(AuditStage.RETRY_RELEASED);

        // the agent (or a naive retry loop) asks again: refused, nothing sent
        assertThat(gateway.invoke(purchase("o1"), agent)).isInstanceOf(ToolResult.Held.class);
        assertThat(supplier.requestsReceived()).hasSize(1);
    }

    @Test
    @DisplayName("EXTERNAL_IDEMPOTENCY_KEY tool times out: #1 UNKNOWN → RETRY_RELEASED, #2 resends with the same key, reconcile never called")
    void externalKeyToolReleasesAndResends() {
        gateway = gatewayFor(RetrySafety.EXTERNAL_IDEMPOTENCY_KEY);
        supplier.setMode(ChaosSupplier.Mode.TIMEOUT_APPLIED, 1);
        ToolResult r = gateway.invoke(purchase("o1"), agent);

        assertThat(r).isInstanceOf(ToolResult.Executed.class);
        assertThat(supplier.requestsReceived()).hasSize(2);
        assertThat(supplier.requestsReceived().stream().distinct()).hasSize(1);
        assertThat(supplier.effectivePurchases()).as("supplier deduplicates on the key").isEqualTo(1);
        assertThat(supplier.lookupCalls()).isZero();
        assertThat(attemptStates(intentOf(r))).containsExactly(AttemptState.RETRY_RELEASED, AttemptState.SUCCEEDED);
        assertThat(stages()).contains(AuditStage.RETRY_RELEASED);
    }

    @Test
    @DisplayName("EXTERNAL_IDEMPOTENCY_KEY tool keeps timing out: bounded by max attempts, then HOLD")
    void externalKeyRetriesAreBounded() {
        gateway = gatewayFor(RetrySafety.EXTERNAL_IDEMPOTENCY_KEY);
        supplier.setMode(ChaosSupplier.Mode.TIMEOUT_NOT_APPLIED);
        ToolResult r = gateway.invoke(purchase("o1"), agent);

        assertThat(r).isInstanceOf(ToolResult.Held.class);
        assertThat(((ToolResult.Held) r).reason()).contains("max attempts");
        assertThat(supplier.requestsReceived()).hasSize(3);
        Intent i = intentOf(r);
        assertThat(i.status()).isEqualTo(IntentStatus.HOLD);
        assertThat(attemptStates(i)).containsExactly(AttemptState.RETRY_RELEASED, AttemptState.RETRY_RELEASED, AttemptState.RETRY_RELEASED);
    }

    @Test
    @DisplayName("NO_SAFE_RETRY tool times out: intent HOLD immediately, reconcile never called")
    void noSafeRetryGoesStraightToHold() {
        gateway = gatewayFor(RetrySafety.NO_SAFE_RETRY);
        supplier.setMode(ChaosSupplier.Mode.TIMEOUT_APPLIED, 1);
        ToolResult r = gateway.invoke(purchase("o1"), agent);

        assertThat(r).isInstanceOf(ToolResult.Held.class);
        assertThat(supplier.requestsReceived()).hasSize(1);
        assertThat(supplier.lookupCalls()).isZero();
        assertThat(intentOf(r).status()).isEqualTo(IntentStatus.HOLD);
    }

    @Test
    @DisplayName("Same business key twice: the second call is refused because the intent is FULFILLED, supplier saw one request")
    void sameBusinessKeyExecutesAtMostOnce() {
        ToolResult first = gateway.invoke(purchase("o1"), agent);
        ToolResult second = gateway.invoke(purchase("o1"), agent);

        assertThat(first).isInstanceOf(ToolResult.Executed.class);
        assertThat(second).isInstanceOf(ToolResult.Held.class);
        assertThat(((ToolResult.Held) second).reason()).contains("FULFILLED");
        assertThat(supplier.requestsReceived()).hasSize(1);
    }

    @Test
    @DisplayName("Without a business key the intent is derived from tool name + argument hash: identical arguments execute once, different arguments are a new intent")
    void businessKeyDerivedFromArgumentsWhenAbsent() {
        Map<String, Object> args = Map.of("orderId", "o1", "supplierId", "SUP-01", "total", 100);
        assertThat(gateway.invoke(new ToolCall("createPurchase", args), agent)).isInstanceOf(ToolResult.Executed.class);
        assertThat(gateway.invoke(new ToolCall("createPurchase", args), agent)).isInstanceOf(ToolResult.Held.class);
        assertThat(gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o1", "supplierId", "SUP-01", "total", 101)), agent)).isInstanceOf(ToolResult.Executed.class);
        assertThat(supplier.requestsReceived()).hasSize(2);
    }

    @Test
    @DisplayName("Explicit refusal is DEFINITIVE_FAILED: no side effect, the intent is OPEN again and a later call may try once more")
    void explicitRefusalLeavesIntentOpen() {
        supplier.setMode(ChaosSupplier.Mode.REJECT, 1);
        ToolResult refused = gateway.invoke(purchase("o1"), agent);
        assertThat(refused).isInstanceOf(ToolResult.Failed.class);
        assertThat(intentOf(refused).status()).isEqualTo(IntentStatus.OPEN);

        ToolResult again = gateway.invoke(purchase("o1"), agent);
        assertThat(again).isInstanceOf(ToolResult.Executed.class);
        assertThat(intentOf(again)).isSameAs(intentOf(refused));
        assertThat(supplier.requestsReceived()).hasSize(2);
        assertThat(supplier.effectivePurchases()).isEqualTo(1);
    }
}
