package fun.autorun.safeexec.core;

/**
 * Thrown by a tool when the external system explicitly refused the request (e.g. HTTP 4xx business error).
 * The attempt is DEFINITIVE_FAILED: no side effect exists and a new attempt is allowed.
 * Any other exception is treated as UNKNOWN: a side effect may exist.
 */
public class DefinitiveFailureException extends RuntimeException {
    public DefinitiveFailureException(String message) { super(message); }
    public DefinitiveFailureException(String message, Throwable cause) { super(message, cause); }
}
