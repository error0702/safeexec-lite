package fun.autorun.safeexec.core;

import java.time.Instant;

/**
 * An external fact a decision depends on, with provenance and a validity window.
 * Tools return evidence without an id; the harness assigns ids and persists them with the approval.
 * Stale evidence blocks execution even when its value is unchanged: APPROVED ≠ STILL_SAFE_TO_EXECUTE.
 */
public record Evidence(String evidenceId, String factType, String subjectId, String value, String source,
                       String sourceVersion, Instant observedAt, Instant validUntil) {
    public static Evidence of(String factType, String subjectId, String value, String source, String sourceVersion, Instant observedAt, Instant validUntil) {
        return new Evidence(null, factType, subjectId, value, source, sourceVersion, observedAt, validUntil);
    }
    public boolean isFreshAt(Instant now) { return validUntil == null || now.isBefore(validUntil); }
    public Evidence withId(String id) { return new Evidence(id, factType, subjectId, value, source, sourceVersion, observedAt, validUntil); }
}
