package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiteToolGatewayTest {
    record Purchase(String orderId, long total) {}

    ToolRegistry registry; InMemoryAuditSink audit; LiteToolGateway gateway;
    AtomicInteger executions = new AtomicInteger();
    final AgentContext agent = AgentContext.of("t", "agent:t", "test");

    @BeforeEach void setUp() {
        registry = new ToolRegistry(); audit = new InMemoryAuditSink(); gateway = new LiteToolGateway(registry, audit);
        registry.register(new Tool<Purchase, String>() {
            public String name() { return "createPurchase"; }
            public ActionLevel level() { return ActionLevel.WRITE; }
            public RetrySafety retrySafety() { return RetrySafety.NO_SAFE_RETRY; }
            public Class<Purchase> inputType() { return Purchase.class; }
            public PolicyFacts policyFacts(Purchase in, ToolContext ctx) { return PolicyFacts.none(); }
            public String execute(Purchase in, ToolContext ctx) {
                executions.incrementAndGet();
                if (in.total() < 0) throw new DefinitiveFailureException("negative total refused");
                if (in.total() > 1000) throw new RuntimeException("read timed out");
                return "PO-" + in.orderId();
            }
        });
    }

    private List<AuditStage> stages() { return audit.all().stream().map(AuditEvent::stage).toList(); }

    @Test @DisplayName("Happy path: VALIDATED → DISPATCHING → SUCCEEDED, executed once")
    void happyPath() {
        ToolResult r = gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o1", "total", 100)), agent);
        assertThat(r).isInstanceOf(ToolResult.Executed.class);
        assertThat(stages()).containsExactly(AuditStage.REQUEST_RECEIVED, AuditStage.VALIDATED, AuditStage.DISPATCHING, AuditStage.SUCCEEDED);
        assertThat(executions.get()).isEqualTo(1);
    }

    @Test @DisplayName("Unknown field from the model is rejected before execution")
    void unknownFieldRejected() {
        ToolResult r = gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o1", "total", 100, "discount", "HACK")), agent);
        assertThat(r).isInstanceOf(ToolResult.ValidationError.class);
        assertThat(stages()).containsExactly(AuditStage.REQUEST_RECEIVED, AuditStage.VALIDATION_FAILED);
        assertThat(executions.get()).isZero();
    }

    @Test @DisplayName("Missing primitive field and wrong type are validation errors")
    void missingAndWrongType() {
        assertThat(gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o1")), agent)).isInstanceOf(ToolResult.ValidationError.class);
        assertThat(gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o1", "total", "lots")), agent)).isInstanceOf(ToolResult.ValidationError.class);
        assertThat(executions.get()).isZero();
    }

    @Test @DisplayName("Unknown tool is audited, nothing executed")
    void unknownTool() {
        assertThat(gateway.invoke(new ToolCall("transferFunds", Map.of()), agent)).isInstanceOf(ToolResult.UnknownTool.class);
        assertThat(stages()).containsExactly(AuditStage.REQUEST_RECEIVED, AuditStage.UNKNOWN_TOOL);
    }

    @Test @DisplayName("Explicit refusal is DEFINITIVE_FAILED; a timeout is UNKNOWN, never FAILED, and Lite does not retry")
    void failureSemantics() {
        assertThat(gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o1", "total", -1)), agent)).isInstanceOf(ToolResult.Failed.class);
        assertThat(gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o2", "total", 5000)), agent)).isInstanceOf(ToolResult.Unknown.class);
        assertThat(stages()).contains(AuditStage.DEFINITIVE_FAILED, AuditStage.UNKNOWN);
        assertThat(executions.get()).isEqualTo(2);
    }

    @Test @DisplayName("Pluggable validator runs after conversion")
    void customValidator() {
        LiteToolGateway g = new LiteToolGateway(registry, audit, in -> in instanceof Purchase p && p.orderId().isBlank() ? List.of("orderId must not be blank") : List.of());
        assertThat(g.invoke(new ToolCall("createPurchase", Map.of("orderId", "", "total", 1)), agent)).isInstanceOf(ToolResult.ValidationError.class);
    }

    @Test @DisplayName("Approval and retry are Pro features")
    void proOnly() {
        assertThatThrownBy(() -> gateway.resume("x", agent)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> gateway.retry("x", agent)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test @DisplayName("Kill switch and policy can never be registered as tools")
    void reservedNames() {
        assertThatThrownBy(() -> registry.register(new Tool<Purchase, String>() {
            public String name() { return "toggleKillSwitch"; }
            public ActionLevel level() { return ActionLevel.WRITE; }
            public RetrySafety retrySafety() { return RetrySafety.NO_SAFE_RETRY; }
            public Class<Purchase> inputType() { return Purchase.class; }
            public PolicyFacts policyFacts(Purchase in, ToolContext ctx) { return PolicyFacts.none(); }
            public String execute(Purchase in, ToolContext ctx) { return ""; }
        })).isInstanceOf(ToolRegistrationException.class);
    }
}
