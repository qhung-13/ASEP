package io.github.qhung13.asep.integration;

import java.util.Objects;

/** Fixed, sanitized error messages; upstream exceptions and bodies are never retained. */
public final class RepositoryFileReadException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private final Reason reason;

    public RepositoryFileReadException(Reason reason) {
        super(Objects.requireNonNull(reason, "reason").message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        FILE_UNAVAILABLE("GitLab repository file is unavailable"),
        ACCESS_DENIED("GitLab repository file access was denied"),
        RATE_LIMITED("GitLab repository file read was rate limited"),
        TOO_LARGE("GitLab repository file exceeds the byte limit"),
        UNSUPPORTED_CONTENT("GitLab repository file content is not supported text"),
        LFS_POINTER("GitLab repository file is an unsupported LFS pointer"),
        INVALID_RESPONSE("GitLab repository file response is invalid"),
        UPSTREAM_FAILURE("GitLab repository file read failed");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }
}
