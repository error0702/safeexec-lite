package fun.autorun.safeexec.core;

/** Appends one event and durably commits it before returning. Never updates, never deletes. */
public interface AuditSink {
    void append(AuditEvent event);
}
