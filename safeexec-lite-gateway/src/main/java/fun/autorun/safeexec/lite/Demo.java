package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.*;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Scenario 1: "The request timed out. Did you just purchase twice?"
 * Run: mvn -q -pl safeexec-lite-gateway exec:java   (or run main from your IDE). No database, no API key.
 */
public final class Demo {

    public static void main(String[] args) {
        ChaosSupplier supplier = new ChaosSupplier();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new PurchaseTool(supplier));
        InMemoryAuditSink audit = new InMemoryAuditSink();
        LiteToolGateway gateway = new LiteToolGateway(registry, audit);
        AgentContext agent = AgentContext.of("demo-agent", "agent:demo", "local");

        System.out.println();
        System.out.println("SafeExec Lite — \"The request timed out. Did you just purchase twice?\"");
        System.out.println("A rule-based agent proposes createPurchase; the gateway decides whether it becomes a request.");
        System.out.println();

        step("1. A normal purchase", supplier,
                () -> gateway.invoke(purchase("o-1001", 100), agent));

        step("2. The model invents a field the tool does not have", supplier,
                () -> gateway.invoke(new ToolCall("createPurchase",
                        Map.of("orderId", "o-1002", "supplierId", "SUP-01", "total", 100, "discount", "HACK"), "order:o-1002"), agent));

        supplier.setMode(ChaosSupplier.Mode.TIMEOUT_APPLIED, 1);
        ToolResult timedOut = step("3. The supplier applies the purchase, then the response is lost (timeout)", supplier,
                () -> gateway.invoke(purchase("o-1003", 100), agent));

        step("4. The agent asks for order o-1003 again", supplier,
                () -> gateway.invoke(purchase("o-1003", 100), agent));

        String intentId = timedOut instanceof ToolResult.Executed e ? e.intentId() : null;
        if (intentId != null) {
            System.out.println("What the audit stream recorded for order o-1003:");
            for (AuditEvent e : audit.all()) {
                if (!intentId.equals(e.intentId())) continue;
                System.out.printf("   %-16s %-18s %s%n", e.stage(), e.result() == null ? "" : e.result(), e.resultDetail() == null ? "" : e.resultDetail());
            }
            System.out.println();
        }

        System.out.printf("Supplier: %d requests received, %d purchase orders exist, for 2 distinct orders. Not 3.%n",
                supplier.requestsReceived().size(), supplier.effectivePurchases());
        System.out.println("Pro makes the same transitions survive a crash (PostgreSQL), and adds policy, approvals and shadow mode.");
        System.out.println();
    }

    private static ToolCall purchase(String orderId, long total) {
        return new ToolCall("createPurchase", Map.of("orderId", orderId, "supplierId", "SUP-01", "total", total), "order:" + orderId);
    }

    private static ToolResult step(String title, ChaosSupplier supplier, Supplier<ToolResult> run) {
        int requestsBefore = supplier.requestsReceived().size();
        int purchasesBefore = supplier.effectivePurchases();
        ToolResult r = run.get();
        System.out.println(title);
        System.out.println("   → " + describe(r));
        System.out.printf("   supplier requests: +%d   purchase orders: +%d%n%n",
                supplier.requestsReceived().size() - requestsBefore, supplier.effectivePurchases() - purchasesBefore);
        return r;
    }

    private static String describe(ToolResult r) {
        return switch (r) {
            case ToolResult.Executed e -> "Executed " + e.output() + "  (intent " + e.intentId() + ", attempt " + e.attemptId() + ")";
            case ToolResult.ValidationError v -> "ValidationError: " + v.message() + "  — nothing was sent";
            case ToolResult.Held h -> "Held: " + h.reason() + "  — nothing was sent";
            case ToolResult.Failed f -> "Failed: " + f.reason();
            case ToolResult.Unknown u -> "Unknown: " + u.reason();
            case ToolResult.UnknownTool u -> "UnknownTool: " + u.toolName();
            case ToolResult.Denied d -> "Denied: " + d.reason();
            case ToolResult.ApprovalRequired a -> "ApprovalRequired: " + a.approvalId();
            case ToolResult.ShadowBlocked s -> "ShadowBlocked: " + s.shadowLogId();
        };
    }
}
