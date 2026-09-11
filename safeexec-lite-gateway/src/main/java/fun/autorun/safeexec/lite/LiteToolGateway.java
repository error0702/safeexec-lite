package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.*;
import fun.autorun.safeexec.lite.InMemoryIntents.Attempt;
import fun.autorun.safeexec.lite.InMemoryIntents.Intent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The smallest thing that deserves the name "gateway": one entry point, strict argument conversion, optional
 * validation, an audit event for every stage, and — for WRITE and IRREVERSIBLE tools — at-most-once execution
 * per business key with three-state recovery after a timeout:
 *
 * <pre>
 * UNKNOWN (sent, result lost)
 *   ├─ EXTERNAL_IDEMPOTENCY_KEY → RETRY_RELEASED → next attempt, same external key
 *   ├─ RECONCILE_BEFORE_RETRY  → reconcile():  Applied             → done, nothing resent
 *   │                                          ConfirmedNotApplied → next attempt, same external key
 *   │                                          Indeterminate       → HOLD, a human decides
 *   └─ NO_SAFE_RETRY           → HOLD
 * </pre>
 *
 * <p><b>What it deliberately does not do:</b> no policy, no kill switch, no approval, no shadow mode, and nothing
 * survives a process crash — intents live in memory. Pro enforces the same transitions in PostgreSQL
 * ({@code DISPATCHING} committed before the call, the CAS as a SQL UPDATE, a partial unique index, a scanner that
 * reclaims stale attempts after a restart) and adds the policy engine, approvals bound to immutable intents,
 * and shadow mode, on exactly these interfaces.</p>
 */
public final class LiteToolGateway implements ToolGateway {
    public static final int DEFAULT_MAX_ATTEMPTS = 3;

    private final ToolRegistry registry;
    private final AuditSink audit;
    private final RecordInputConverter converter = new RecordInputConverter();
    private final InputValidator validator;
    private final InMemoryIntents intents;
    private final int maxAttempts;

    public LiteToolGateway(ToolRegistry registry, AuditSink audit) { this(registry, audit, InputValidator.none()); }
    public LiteToolGateway(ToolRegistry registry, AuditSink audit, InputValidator validator) {
        this(registry, audit, validator, new InMemoryIntents(), DEFAULT_MAX_ATTEMPTS);
    }
    public LiteToolGateway(ToolRegistry registry, AuditSink audit, InputValidator validator, InMemoryIntents intents, int maxAttempts) {
        this.registry = registry; this.audit = audit; this.validator = validator; this.intents = intents;
        this.maxAttempts = Math.max(1, maxAttempts);
    }

    public InMemoryIntents intents() { return intents; }

    // ------------------------------------------------------------------ invoke

    @Override
    public ToolResult invoke(ToolCall call, AgentContext ctx) {
        String traceId = "trc_" + UUID.randomUUID();
        Tool<?, ?> tool = registry.find(call.toolName()).orElse(null);
        Ev ev = new Ev(traceId, ctx, call.toolName(), tool, sha256(canonical(call.rawArguments())), null, null, null);
        audit.append(ev.at(AuditStage.REQUEST_RECEIVED).build());
        if (tool == null) {
            audit.append(ev.at(AuditStage.UNKNOWN_TOOL).result("UNKNOWN_TOOL").build());
            return new ToolResult.UnknownTool(call.toolName());
        }
        return invokeTyped(tool, call, ctx, ev);
    }

    private <I, O> ToolResult invokeTyped(Tool<I, O> tool, ToolCall call, AgentContext ctx, Ev ev) {
        // 1. Schema: unknown fields, missing fields and wrong types stop here
        I input;
        try {
            input = converter.convert(call.rawArguments(), tool.inputType());
        } catch (RecordInputConverter.InputConversionException e) {
            audit.append(ev.at(AuditStage.VALIDATION_FAILED).result("VALIDATION_ERROR").resultDetail(e.getMessage()).build());
            return new ToolResult.ValidationError(e.getMessage());
        }
        List<String> problems = validator.validate(input);
        if (!problems.isEmpty()) {
            String msg = String.join("; ", problems);
            audit.append(ev.at(AuditStage.VALIDATION_FAILED).result("VALIDATION_ERROR").resultDetail(msg).build());
            return new ToolResult.ValidationError(msg);
        }
        audit.append(ev.at(AuditStage.VALIDATED).build());

        // 2. READ: no side effect, no intent
        if (tool.level() == ActionLevel.READ) {
            ToolContext readCtx = new ToolContext(ev.traceId, null, null, 0, null, ctx);
            try {
                O out = tool.execute(input, readCtx);
                audit.append(ev.at(AuditStage.SUCCEEDED).result("SUCCEEDED").build());
                return new ToolResult.Executed(out, null, null);
            } catch (RuntimeException e) {
                audit.append(ev.at(AuditStage.DEFINITIVE_FAILED).result("FAILED").resultDetail(rootMessage(e)).build());
                return new ToolResult.Failed(rootMessage(e), null, null);
            }
        }

        // 3. Intent: one business key → at most one side effect
        String businessKey = call.businessKey() != null ? call.businessKey() : tool.name() + ":" + ev.inputHash;
        Intent intent;
        try {
            intent = intents.findOrCreate(businessKey, tool.name());
        } catch (IllegalStateException e) {
            audit.append(ev.at(AuditStage.HELD).result("HELD").resultDetail(e.getMessage()).build());
            return new ToolResult.Held(e.getMessage(), null);
        }
        ev = ev.intent(intent);

        // 4. Reserve the first attempt (CAS OPEN → EXECUTING) and run
        Attempt attempt;
        try {
            attempt = intents.reserve(intent);
        } catch (InMemoryIntents.IntentNotOpenException e) {
            audit.append(ev.at(AuditStage.HELD).result("HELD").resultDetail(e.getMessage()).build());
            return new ToolResult.Held(e.getMessage(), intent.id());
        }
        return runAttempts(tool, input, intent, attempt, ev, ctx);
    }

    @Override public ToolResult resume(String approvalId, AgentContext ctx) { throw new UnsupportedOperationException("approvals are part of SafeExec Pro"); }
    @Override public ToolResult retry(String intentId, AgentContext ctx) { throw new UnsupportedOperationException("operator / scanner retry is part of SafeExec Pro"); }

    // ------------------------------------------------------------------ attempts

    /** Bounded loop. A new iteration happens ONLY when recovery says a resend is safe; every attempt sends the same external key. */
    private <I, O> ToolResult runAttempts(Tool<I, O> tool, I input, Intent intent, Attempt attempt, Ev ev, AgentContext ctx) {
        for (int round = 1; ; round++) {
            if (attempt == null) {
                try {
                    attempt = intents.reserve(intent);
                } catch (InMemoryIntents.IntentNotOpenException e) {
                    audit.append(ev.at(AuditStage.HELD).result("HELD").resultDetail("retry gate: " + e.getMessage()).build());
                    return new ToolResult.Held("retry gate: " + e.getMessage(), intent.id());
                }
            }
            Ev at = ev.attempt(attempt);
            audit.append(at.at(AuditStage.ATTEMPT_CREATED).build());

            // Pro commits DISPATCHING to the database BEFORE the external call, so a crash leaves evidence that a side effect may exist
            intents.dispatching(attempt);
            audit.append(at.at(AuditStage.DISPATCHING).build());

            ToolContext execCtx = new ToolContext(ev.traceId, intent.id(), attempt.id(), attempt.attemptNo(), intent.externalIdempotencyKey(), ctx);
            try {
                O out = tool.execute(input, execCtx);
                intents.succeeded(intent, attempt, null);
                audit.append(at.at(AuditStage.SUCCEEDED).result("SUCCEEDED").build());
                return new ToolResult.Executed(out, intent.id(), attempt.id());
            } catch (DefinitiveFailureException e) {
                // The far side explicitly refused: no side effect, the intent is OPEN again for a new attempt
                intents.definitiveFailed(intent, attempt, e.getMessage());
                audit.append(at.at(AuditStage.DEFINITIVE_FAILED).result("DEFINITIVE_FAILED").resultDetail(e.getMessage()).build());
                return new ToolResult.Failed(e.getMessage(), intent.id(), attempt.id());
            } catch (RuntimeException e) {
                // Anything else after DISPATCHING means a side effect MAY exist. UNKNOWN ≠ FAILED.
                String reason = rootMessage(e);
                intents.unknown(attempt, reason);
                audit.append(at.at(AuditStage.UNKNOWN).result("UNKNOWN").resultDetail(reason).build());

                Outcome outcome = recover(tool, intent, attempt, execCtx, at);
                switch (outcome) {
                    case Outcome.Recovered r -> { return new ToolResult.Executed(r.result(), intent.id(), attempt.id()); }
                    case Outcome.Held h -> { return new ToolResult.Held(h.reason(), intent.id()); }
                    case Outcome.Retryable r -> {
                        if (round >= maxAttempts) {
                            intents.hold(intent, "max attempts (" + maxAttempts + ") reached");
                            audit.append(ev.at(AuditStage.HELD).result("HELD").resultDetail("max attempts (" + maxAttempts + ") reached after " + r.reason()).build());
                            return new ToolResult.Held("max attempts (" + maxAttempts + ") reached", intent.id());
                        }
                        attempt = null; // next attempt, same external idempotency key
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ recovery

    private sealed interface Outcome {
        record Recovered(Object result) implements Outcome {}
        record Retryable(String reason) implements Outcome {}
        record Held(String reason) implements Outcome {}
    }

    /** The only place that decides what happens after UNKNOWN. Never resends by itself; returns what the loop may do. */
    private Outcome recover(Tool<?, ?> tool, Intent intent, Attempt attempt, ToolContext ctx, Ev at) {
        return switch (tool.retrySafety()) {
            case EXTERNAL_IDEMPOTENCY_KEY -> {
                intents.releaseForRetry(intent, attempt);
                audit.append(at.at(AuditStage.RETRY_RELEASED).result("RETRY_RELEASED")
                        .resultDetail("external system honours the idempotency key; next attempt may resend with the same key").build());
                yield new Outcome.Retryable("RETRY_RELEASED");
            }
            case NO_SAFE_RETRY -> {
                intents.hold(intent, "NO_SAFE_RETRY: result unknown and no safe way to find out");
                audit.append(at.at(AuditStage.HELD).result("HELD").resultDetail("NO_SAFE_RETRY").build());
                yield new Outcome.Held("NO_SAFE_RETRY: result unknown and no safe way to find out");
            }
            case RECONCILE_BEFORE_RETRY -> reconcile(tool, intent, attempt, ctx, at);
        };
    }

    private Outcome reconcile(Tool<?, ?> tool, Intent intent, Attempt attempt, ToolContext ctx, Ev at) {
        if (!(tool instanceof ReconcilableTool<?, ?> reconcilable)) {
            intents.hold(intent, "RECONCILE_BEFORE_RETRY but tool is not a ReconcilableTool");
            audit.append(at.at(AuditStage.HELD).result("HELD").resultDetail("not reconcilable").build());
            return new Outcome.Held("tool is not reconcilable");
        }
        intents.reconciling(attempt);
        audit.append(at.at(AuditStage.RECONCILING).build());

        RecoveryResult<?> result;
        try {
            result = reconcilable.reconcile(intent.externalIdempotencyKey(), ctx);
        } catch (RuntimeException e) {
            result = new RecoveryResult.Indeterminate<>("reconcile threw " + rootMessage(e));
        }

        return switch (result) {
            case RecoveryResult.Applied<?> applied -> {
                intents.succeeded(intent, attempt, "Applied by reconcile");
                audit.append(at.at(AuditStage.RECONCILED).result("SUCCEEDED").resultDetail("Applied: side effect confirmed, nothing resent").build());
                yield new Outcome.Recovered(applied.result());
            }
            case RecoveryResult.ConfirmedNotApplied<?> ignored -> {
                intents.definitiveFailed(intent, attempt, "ConfirmedNotApplied by reconcile");
                audit.append(at.at(AuditStage.RECONCILED).result("DEFINITIVE_FAILED").resultDetail("ConfirmedNotApplied: safe to resend with the same key").build());
                yield new Outcome.Retryable("ConfirmedNotApplied");
            }
            case RecoveryResult.Indeterminate<?> ind -> {
                intents.indeterminate(intent, attempt, "Indeterminate: " + ind.reason());
                audit.append(at.at(AuditStage.HELD).result("HELD").resultDetail("Indeterminate: " + ind.reason()).build());
                yield new Outcome.Held("Indeterminate: " + ind.reason());
            }
        };
    }

    // ------------------------------------------------------------------ helpers

    /** Immutable audit-event prefix for one trace, progressively enriched with intent and attempt. */
    private record Ev(String traceId, AgentContext ctx, String toolName, Tool<?, ?> tool, String inputHash,
                      String intentId, String externalKey, String attemptId) {
        Ev intent(Intent i) { return new Ev(traceId, ctx, toolName, tool, inputHash, i.id(), i.externalIdempotencyKey(), attemptId); }
        Ev attempt(Attempt a) { return new Ev(traceId, ctx, toolName, tool, inputHash, intentId, externalKey, a.id()); }
        AuditEvent.Builder at(AuditStage stage) {
            return AuditEvent.builder(traceId, stage).actor(ctx.actor()).actorSource("AGENT").agentName(ctx.agentName())
                    .toolName(toolName).level(tool == null ? null : tool.level()).inputHash(inputHash)
                    .intentId(intentId).externalIdempotencyKey(externalKey).attemptId(attemptId);
        }
    }

    private static String canonical(Map<String, Object> raw) { return new TreeMap<>(raw).toString(); }

    private static String rootMessage(Throwable t) {
        Throwable r = t; while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getClass().getSimpleName() + (r.getMessage() == null ? "" : ": " + r.getMessage());
    }

    static String sha256(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
