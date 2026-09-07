package fun.autorun.safeexec.core;

import java.time.Instant;

/** One immutable line of the audit event stream. Input is already redacted or hashed according to the configured mode. */
public record AuditEvent(
        String eventId,
        String traceId,
        AuditStage stage,
        String actor,
        String actorRoles,
        String actorSource,
        String agentName,
        String toolName,
        ActionLevel level,
        String inputHash,
        String inputRedacted,
        Integer policyVersion,
        String policyResult,
        String approvalId,
        String intentId,
        String attemptId,
        String externalIdempotencyKey,
        String result,
        String resultDetail,
        Long costMicros,
        Instant createdAt) {

    public static Builder builder(String traceId, AuditStage stage) { return new Builder(traceId, stage); }

    public static final class Builder {
        private final String traceId; private final AuditStage stage;
        private String actor, actorRoles, actorSource, agentName, toolName, inputHash, inputRedacted, policyResult,
                approvalId, intentId, attemptId, externalIdempotencyKey, result, resultDetail;
        private ActionLevel level; private Integer policyVersion; private Long costMicros;
        private Builder(String traceId, AuditStage stage) { this.traceId = traceId; this.stage = stage; }
        public Builder actor(String v) { actor = v; return this; }
        public Builder actorRoles(String v) { actorRoles = v; return this; }
        public Builder actorSource(String v) { actorSource = v; return this; }
        public Builder agentName(String v) { agentName = v; return this; }
        public Builder toolName(String v) { toolName = v; return this; }
        public Builder level(ActionLevel v) { level = v; return this; }
        public Builder inputHash(String v) { inputHash = v; return this; }
        public Builder inputRedacted(String v) { inputRedacted = v; return this; }
        public Builder policyVersion(Integer v) { policyVersion = v; return this; }
        public Builder policyResult(String v) { policyResult = v; return this; }
        public Builder approvalId(String v) { approvalId = v; return this; }
        public Builder intentId(String v) { intentId = v; return this; }
        public Builder attemptId(String v) { attemptId = v; return this; }
        public Builder externalIdempotencyKey(String v) { externalIdempotencyKey = v; return this; }
        public Builder result(String v) { result = v; return this; }
        public Builder resultDetail(String v) { resultDetail = v; return this; }
        public Builder costMicros(Long v) { costMicros = v; return this; }
        public AuditEvent build() {
            return new AuditEvent("evt_" + java.util.UUID.randomUUID(), traceId, stage, actor, actorRoles, actorSource,
                    agentName, toolName, level, inputHash, inputRedacted, policyVersion, policyResult, approvalId,
                    intentId, attemptId, externalIdempotencyKey, result, resultDetail, costMicros, Instant.now());
        }
    }
}
