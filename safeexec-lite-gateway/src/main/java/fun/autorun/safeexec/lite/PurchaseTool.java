package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.ActionLevel;
import fun.autorun.safeexec.core.Money;
import fun.autorun.safeexec.core.PolicyFacts;
import fun.autorun.safeexec.core.ReconcilableTool;
import fun.autorun.safeexec.core.RecoveryResult;
import fun.autorun.safeexec.core.RetrySafety;
import fun.autorun.safeexec.core.ToolContext;

/** A WRITE tool with a real side effect: money leaves the account. By default RECONCILE_BEFORE_RETRY: ask before resending. */
public final class PurchaseTool implements ReconcilableTool<PurchaseTool.PurchaseRequest, ChaosSupplier.PurchaseOrder> {
    public record PurchaseRequest(String orderId, String supplierId, long total) {}

    private final ChaosSupplier supplier;
    private final RetrySafety retrySafety;

    public PurchaseTool(ChaosSupplier supplier) { this(supplier, RetrySafety.RECONCILE_BEFORE_RETRY); }
    public PurchaseTool(ChaosSupplier supplier, RetrySafety retrySafety) { this.supplier = supplier; this.retrySafety = retrySafety; }

    @Override public String name() { return "createPurchase"; }
    @Override public ActionLevel level() { return ActionLevel.WRITE; }
    @Override public RetrySafety retrySafety() { return retrySafety; }
    @Override public Class<PurchaseRequest> inputType() { return PurchaseRequest.class; }

    @Override
    public PolicyFacts policyFacts(PurchaseRequest in, ToolContext ctx) {
        return PolicyFacts.builder().money("amount", Money.of("USD", in.total())).group("purchase").attribute("supplier", in.supplierId()).build();
    }

    /** Every attempt of the same intent sends the same external idempotency key. */
    @Override
    public ChaosSupplier.PurchaseOrder execute(PurchaseRequest in, ToolContext ctx) {
        return supplier.create(in.orderId(), in.supplierId(), in.total(), ctx.externalIdempotencyKey());
    }

    /**
     * Three answers, never two. "Not found" only counts as "not applied" when the supplier's lookup is
     * authoritative; a lagging or failing lookup is Indeterminate and must never trigger a resend.
     */
    @Override
    public RecoveryResult<ChaosSupplier.PurchaseOrder> reconcile(String externalIdempotencyKey, ToolContext ctx) {
        try {
            var found = supplier.lookupByKey(externalIdempotencyKey);
            if (found.isPresent()) return new RecoveryResult.Applied<>(found.get());
            if (supplier.lookupIsAuthoritative()) return new RecoveryResult.ConfirmedNotApplied<>();
            return new RecoveryResult.Indeterminate<>("supplier lookup is not authoritative right now (replica lag)");
        } catch (RuntimeException e) {
            return new RecoveryResult.Indeterminate<>("supplier lookup failed: " + e.getMessage());
        }
    }
}
