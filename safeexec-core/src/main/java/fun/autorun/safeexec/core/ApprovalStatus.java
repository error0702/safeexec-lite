package fun.autorun.safeexec.core;

/** BOUND means the approval has been atomically consumed by an Intent; retries under that Intent never re-approve. */
public enum ApprovalStatus { PENDING, APPROVED, REJECTED, EXPIRED, SUPERSEDED, BOUND }
