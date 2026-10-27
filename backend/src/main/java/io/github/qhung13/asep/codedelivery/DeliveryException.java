package io.github.qhung13.asep.codedelivery;

/** Sanitized: never carries SQL, external response, ticket content or credentials. */
public final class DeliveryException extends RuntimeException {
    public enum Kind { INVALID, CONFLICT, UNAVAILABLE }
    private final Kind kind;
    public DeliveryException(Kind kind) { super("Code Delivery rejected: " + kind.name()); this.kind = kind; }
    public Kind kind() { return kind; }
}
