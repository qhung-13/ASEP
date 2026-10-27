package io.github.qhung13.asep.integration;

/** Deliberately carries no response body, URL, credential or underlying exception. */
public final class JiraReadException extends RuntimeException {
    public enum Reason { INVALID_RESPONSE, UNAVAILABLE, LIVE_ACCESS_DISABLED, REQUEST_REJECTED }
    private final Reason reason;

    public JiraReadException(Reason reason) {
        super("Jira read rejected: " + reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
