package io.github.qhung13.asep.codedelivery;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.State;
import static io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.RecordedSend;

/** Durable VN-T14 Java observations only. No member constitutes mutation or Go-transition authority. */
public record TestProjectDeliveryRecord(TestProjectDeliveryRequest request, State push, State mergeRequest,
        boolean pushClaimed, boolean mrClaimed, String lastConfirmedPushHead, Set<Long> knownMrIids,
        boolean residualRace, long revision) {
    public TestProjectDeliveryRecord {
        Objects.requireNonNull(request);
        Objects.requireNonNull(push);
        Objects.requireNonNull(mergeRequest);
        knownMrIids = Set.copyOf(Objects.requireNonNull(knownMrIids));
        if (revision < 1 || (!mrClaimed && !knownMrIids.isEmpty()) || (mrClaimed && !pushClaimed)
                || (lastConfirmedPushHead != null && (!pushClaimed || !lastConfirmedPushHead.equals(request.candidateCommitSha())))
                || knownMrIids.stream().anyMatch(id -> id == null || id < 1)) throw new IllegalArgumentException("Invalid VN-T14 durable record");
    }

    RecordedSend recordedSend() {
        return new RecordedSend(request.operationId(), request.bodyHash(), pushClaimed, mrClaimed,
                knownMrIids, lastConfirmedPushHead, residualRace);
    }

    /** Exact remote observation is not permission to write or claim workflow completion. */
    public boolean exactRemoteCandidateObserved() {
        return push == State.CONFIRMED && mergeRequest == State.CONFIRMED && !residualRace;
    }

    /** Trusted Java-to-Go observation; receiving it never authorizes a Go state transition. */
    public record Callback(UUID callbackId, UUID operationId, UUID runId, long expectedRunVersion,
            String requestHash, long revision, State push, State mergeRequest, boolean pushClaimed,
            boolean mrClaimed, String lastConfirmedPushHead, List<Long> knownMrIids, boolean residualRace) {
        public Callback {
            Objects.requireNonNull(callbackId);
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(runId);
            Objects.requireNonNull(requestHash);
            Objects.requireNonNull(push);
            Objects.requireNonNull(mergeRequest);
            knownMrIids = List.copyOf(Objects.requireNonNull(knownMrIids));
            if (expectedRunVersion < 1 || revision < 1 || !requestHash.matches("[0-9a-f]{64}")
                    || knownMrIids.stream().anyMatch(id -> id == null || id < 1)) throw new IllegalArgumentException("Invalid VN-T14 callback");
        }

        public String bodyHash() {
            List<String> fields = new ArrayList<>(List.of("asep-vn-t14-callback-v1", callbackId.toString(),
                    operationId.toString(), runId.toString(), Long.toString(expectedRunVersion), requestHash,
                    Long.toString(revision), push.name(), mergeRequest.name(), Boolean.toString(pushClaimed),
                    Boolean.toString(mrClaimed), lastConfirmedPushHead == null ? "" : lastConfirmedPushHead,
                    Boolean.toString(residualRace)));
            knownMrIids.forEach(iid -> fields.add(Long.toString(iid)));
            return DeliveryIntent.hash(fields);
        }

        static UUID id(UUID operationId, long revision) {
            return UUID.nameUUIDFromBytes(("asep-vn-t14-callback-v1:" + operationId + ":" + revision)
                    .getBytes(StandardCharsets.UTF_8));
        }
    }
}
