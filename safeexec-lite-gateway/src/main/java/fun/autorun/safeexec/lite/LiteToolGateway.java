package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.*;

import java.util.List;
import java.util.UUID;

/**
 * The smallest thing that deserves the name "gateway": one entry point, strict argument conversion, optional
 * validation, and an audit event for every stage, including the exception paths.
 *
 * <p><b>What it deliberately does not do:</b> no policy, no kill switch, no Intent / Attempt idempotency, no
 * three-state recovery, no approval, no shadow mode. A WRITE or IRREVERSIBLE tool executes as soon as its
 * arguments are valid, and a timeout is recorded as UNKNOWN and left there. Those guarantees are what
 * SafeExec Pro adds on top of exactly these interfaces.</p>
 */
public final class LiteToolGateway implements ToolGateway {
    private final ToolRegistry registry;
    private final AuditSink audit;
    private final RecordInputConverter converter = new RecordInputConverter();
    private final InputValidator validator;

    public LiteToolGateway(ToolRegistry registry, AuditSink audit) { this(registry, audit, InputValidator.none()); }
    public LiteToolGateway(ToolRegistry registry, AuditSink audit, InputValidator validator) {
        this.registry = registry; this.audit = audit; this.validator = validator;
    }

    @Override
    public ToolResult invoke(ToolCall call, AgentContext ctx) {
        String traceId = "trc_" + UUID.randomUUID();
        Tool<?, ?> tool = registry.find(call.toolName()).orElse(null);
        audit.append(ev(traceId, AuditStage.REQUEST_RECEIVED, ctx, call.toolName(), tool).build());
        if (tool == null) {
            audit.append(ev(traceId, AuditStage.UNKNOWN_TOOL, ctx, call.toolName(), null).result("UNKNOWN_TOOL").build());
            return new ToolResult.UnknownTool(call.toolName());
        }
        return invokeTyped(tool, call, ctx, traceId);
    }

    private <I, O> ToolResult invokeTyped(Tool<I, O> tool, ToolCall call, AgentContext ctx, String traceId) {
        I input;
        try {
            input = converter.convert(call.rawArguments(), tool.inputType());
        } catch (RecordInputConverter.InputConversionException e) {
            audit.append(ev(traceId, AuditStage.VALIDATION_FAILED, ctx, tool.name(), tool).result("VALIDATION_ERROR").resultDetail(e.getMessage()).build());
            return new ToolResult.ValidationError(e.getMessage());
        }
        List<String> problems = validator.validate(input);
        if (!problems.isEmpty()) {
            String msg = String.join("; ", problems);
            audit.append(ev(traceId, AuditStage.VALIDATION_FAILED, ctx, tool.name(), tool).result("VALIDATION_ERROR").resultDetail(msg).build());
            return new ToolResult.ValidationError(msg);
        }
        audit.append(ev(traceId, AuditStage.VALIDATED, ctx, tool.name(), tool).build());

        ToolContext tctx = new ToolContext(traceId, null, null, 1, null, ctx);
        audit.append(ev(traceId, AuditStage.DISPATCHING, ctx, tool.name(), tool).build());
        try {
            O out = tool.execute(input, tctx);
            audit.append(ev(traceId, AuditStage.SUCCEEDED, ctx, tool.name(), tool).result("SUCCEEDED").build());
            return new ToolResult.Executed(out, null, null);
        } catch (DefinitiveFailureException e) {
            audit.append(ev(traceId, AuditStage.DEFINITIVE_FAILED, ctx, tool.name(), tool).result("DEFINITIVE_FAILED").resultDetail(e.getMessage()).build());
            return new ToolResult.Failed(e.getMessage(), null, null);
        } catch (RuntimeException e) {
            // Lite records the truth and stops. Pro reconciles (Applied / ConfirmedNotApplied / Indeterminate) before deciding anything.
            String reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
            audit.append(ev(traceId, AuditStage.UNKNOWN, ctx, tool.name(), tool).result("UNKNOWN").resultDetail(reason + " — side effect may exist; Lite does not reconcile").build());
            return new ToolResult.Unknown(reason, null, null);
        }
    }

    @Override public ToolResult resume(String approvalId, AgentContext ctx) { throw new UnsupportedOperationException("approvals are part of SafeExec Pro"); }
    @Override public ToolResult retry(String intentId, AgentContext ctx) { throw new UnsupportedOperationException("intent retry is part of SafeExec Pro"); }

    private static AuditEvent.Builder ev(String traceId, AuditStage stage, AgentContext ctx, String toolName, Tool<?, ?> tool) {
        return AuditEvent.builder(traceId, stage).actor(ctx.actor()).actorSource("AGENT").agentName(ctx.agentName())
                .toolName(toolName).level(tool == null ? null : tool.level());
    }
}
