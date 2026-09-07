package fun.autorun.safeexec.lite;

import fun.autorun.safeexec.core.AuditEvent;
import fun.autorun.safeexec.core.AuditSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Append-only in memory. Pro replaces this with a PostgreSQL event stream committed per stage and guarded by a trigger. */
public final class InMemoryAuditSink implements AuditSink {
    private final List<AuditEvent> events = new CopyOnWriteArrayList<>();
    @Override public void append(AuditEvent event) { events.add(event); }
    public List<AuditEvent> all() { return List.copyOf(events); }
    public List<AuditEvent> trace(String traceId) { return events.stream().filter(e -> e.traceId().equals(traceId)).toList(); }
}
