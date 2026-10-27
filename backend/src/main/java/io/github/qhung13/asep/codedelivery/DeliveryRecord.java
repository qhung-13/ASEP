package io.github.qhung13.asep.codedelivery;

import java.util.List;
import java.util.UUID;

public record DeliveryRecord(DeliveryIntent intent, State push, State mergeRequest, Long mrIid,
        boolean residualRace, Reason reason, long revision) {
    public enum State { PLANNED, IN_PROGRESS, CONFIRMED, UNKNOWN, BLOCKED, MANUAL_RECONCILIATION_REQUIRED }
    public enum Reason { NONE, PREREQUISITE, REMOTE_MISMATCH, REMOTE_UNCERTAIN, REMOTE_REJECTED, READ_REVOKED, POST_SEND_CHANGE }
    public boolean draftMrComplete() { return push == State.CONFIRMED && mergeRequest == State.CONFIRMED && !residualRace; }

    /** A durable Java observation for Go; it is never a Go workflow transition. */
    public record Callback(UUID callbackId, UUID operationId, UUID runId, long expectedRunVersion,
            String intentHash, long revision, State push, State mergeRequest, Long mrIid, boolean residualRace) {
        public String bodyHash() {
            return DeliveryIntent.hash(List.of("asep-code-delivery-callback-fake-v1", callbackId.toString(),
                    operationId.toString(), runId.toString(), Long.toString(expectedRunVersion), intentHash,
                    Long.toString(revision), push.name(), mergeRequest.name(), mrIid == null ? "" : mrIid.toString(),
                    Boolean.toString(residualRace)));
        }
    }
}
