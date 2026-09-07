package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.*;

import java.util.Map;

/** Run: mvn -q -pl safeexec-lite-gateway exec:java  (or just run main from your IDE). */
public final class Demo {
    public record PurchaseRequest(String orderId, String supplierId, long total) {}

    public static void main(String[] args) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool<PurchaseRequest, String>() {
            public String name() { return "createPurchase"; }
            public ActionLevel level() { return ActionLevel.WRITE; }
            public RetrySafety retrySafety() { return RetrySafety.RECONCILE_BEFORE_RETRY; }
            public Class<PurchaseRequest> inputType() { return PurchaseRequest.class; }
            public PolicyFacts policyFacts(PurchaseRequest in, ToolContext ctx) { return PolicyFacts.builder().money("amount", Money.of("USD", in.total())).group("purchase").build(); }
            public String execute(PurchaseRequest in, ToolContext ctx) {
                if (in.total() > 1000) throw new RuntimeException("read timed out");   // the interesting case
                return "PO-" + in.orderId();
            }
        });
        InMemoryAuditSink audit = new InMemoryAuditSink();
        ToolGateway gateway = new LiteToolGateway(registry, audit);
        AgentContext agent = AgentContext.of("demo-agent", "agent:demo", "local");

        System.out.println(gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o1", "supplierId", "SUP-01", "total", 100)), agent));
        System.out.println(gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o2", "supplierId", "SUP-01", "total", 100, "discount", "HACK")), agent));
        System.out.println(gateway.invoke(new ToolCall("createPurchase", Map.of("orderId", "o3", "supplierId", "SUP-01", "total", 5000)), agent));
        System.out.println();
        for (AuditEvent e : audit.all()) System.out.printf("%s %-18s %-12s %s%n", e.traceId().substring(0, 12), e.stage(), e.result() == null ? "" : e.result(), e.resultDetail() == null ? "" : e.resultDetail());
    }
}
