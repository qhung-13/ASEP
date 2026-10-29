package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader.Inspection;
import java.util.List;
import java.util.Objects;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.State;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.Prepared;
import static io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.*;

/**
 * VN-T14 delivery protocol with explicitly injected ports. There is deliberately NO Spring
 * component/bean, credential provider or GitLab mutation transport. Only synthetic test ports
 * exist in this repository. A future live implementation requires a separate Owner-authorized
 * enrollment, controller, credential and transport review.
 *
 * A persisted send claim is an irreversible MAY-HAVE-SENT boundary. On resumption this
 * coordinator only reads/reconciles that step; it never sends it a second time.
 */
public final class TestProjectDeliveryCoordinator {
    /** Independent read capability; must not retain or require a delivery credential. */
    public interface ReadPort {
        Inspection inspect(TestProjectDeliveryRequest request);
        /** Scoped, complete read evidence; absence is not proof when reads are incomplete. */
        Observation read(TestProjectDeliveryRequest request);
    }

    /** Privileged write capability. No implementation exists outside deterministic tests. */
    public interface WritePort {
        /** Never retry a send after an error or a committed send claim. */
        void push(TestProjectDeliveryRequest request, Prepared candidate);
        void createDraft(TestProjectDeliveryRequest request, Prepared candidate);
    }

    private enum Step { PUSH, DRAFT_MR }
    private final JdbcTestProjectDeliveryLedger ledger;
    private final TestProjectDeliveryGate gate;
    private final TestProjectDeliveryPreparation preparation;
    private final ReadPort reader;
    private final WritePort writer;

    public TestProjectDeliveryCoordinator(JdbcTestProjectDeliveryLedger ledger, TestProjectDeliveryGate gate,
            TestProjectDeliveryPreparation preparation, ReadPort reader, WritePort writer) {
        this.ledger = Objects.requireNonNull(ledger);
        this.gate = Objects.requireNonNull(gate);
        this.preparation = Objects.requireNonNull(preparation);
        this.reader = Objects.requireNonNull(reader);
        this.writer = Objects.requireNonNull(writer);
    }

    /**
     * Bounded single pass: verify admission authority BEFORE any new ledger row, then
     * reconcile and send at most once per distinct step. This entry point requires a
     * current write grant even on replay. Use reconcileOnly for revoked/expired grants.
     * A fault before or after the send can leave UNKNOWN; a subsequent call only performs
     * independent reads for a previously claimed step. No automatic resend, force or delete.
     */
    public TestProjectDeliveryRecord runOnce(TestProjectDeliveryRequest request) {
        Objects.requireNonNull(request);
        requireTrustedAdmission(request); // Never reserve operation/run identity for an unauthorized request.
        TestProjectDeliveryRecord state = reconcile(ledger, reader, ledger.admit(request));
        if (eligibleForClaimPush(state)) {
            if (preSend(request, Step.PUSH) == null) return state;
            var claim = ledger.claimPush(state);
            state = claim.record();
            if (!claim.applied()) return state;
            // Recheck after the durable claim: the claim itself NEVER authorizes a send.
            Prepared candidate = preSend(request, Step.PUSH);
            if (candidate == null) return reconcile(ledger, reader, state);
            boolean responseLost = false;
            try { writer.push(request, candidate); }
            catch (RuntimeException unknownOutcome) { responseLost = true; }
            state = reconcile(ledger, reader, state);
            if (responseLost) return state; // Do not chain another mutation after an unknown response.
        }
        if (state.pushClaimed()) state = reconcile(ledger, reader, state);
        if (eligibleForClaimMr(state)) {
            if (preSend(request, Step.DRAFT_MR) == null) return state;
            var claim = ledger.claimMr(state);
            state = claim.record();
            if (!claim.applied()) return state;
            Prepared candidate = preSend(request, Step.DRAFT_MR);
            if (candidate == null) return reconcile(ledger, reader, state);
            boolean responseLost = false;
            try { writer.createDraft(request, candidate); }
            catch (RuntimeException unknownOutcome) { responseLost = true; }
            state = reconcile(ledger, reader, state);
            if (responseLost) return state;
        }
        if (state.mrClaimed()) state = reconcile(ledger, reader, state);
        return state;
    }

    /** Read-only recovery is permitted without any live grant or writer capability. */
    public TestProjectDeliveryRecord reconcileOnly(TestProjectDeliveryRequest request) {
        return reconcileOnly(ledger, reader, request);
    }

    /** A trusted recovery caller needs only a scoped reader and durable ledger, never a writer. */
    public static TestProjectDeliveryRecord reconcileOnly(JdbcTestProjectDeliveryLedger ledger,
            ReadPort reader, TestProjectDeliveryRequest request) {
        Objects.requireNonNull(ledger);
        Objects.requireNonNull(reader);
        Objects.requireNonNull(request);
        var stored = ledger.find(request.operationId());
        if (!stored.request().equals(request)) throw new DeliveryException(DeliveryException.Kind.CONFLICT);
        return reconcile(ledger, reader, stored);
    }

    /** Fails closed before any admission INSERT, even if remote inventory looks harmless. */
    private void requireTrustedAdmission(TestProjectDeliveryRequest request) {
        gate.requireBeforeMutation(request, inspectOrUnavailable(request));
        preparation.prepare(request); // A caller-supplied seal/receipt reference is not evidence.
    }

    private Inspection inspectOrUnavailable(TestProjectDeliveryRequest request) {
        try { return reader.inspect(request); }
        catch (RuntimeException failedRead) { throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE); }
    }

    private static TestProjectDeliveryRecord reconcile(JdbcTestProjectDeliveryLedger ledger,
            ReadPort reader, TestProjectDeliveryRecord record) {
        return ledger.observe(record, readOrUnavailable(reader, record.request())).record();
    }

    private static Observation readOrUnavailable(ReadPort reader, TestProjectDeliveryRequest request) {
        try {
            Observation observed = reader.read(request);
            return observed == null ? unavailable() : observed;
        } catch (RuntimeException cannotRead) {
            return unavailable(); // A failed read is not evidence of absence/non-execution.
        }
    }

    private static Observation unavailable() {
        return new Observation(false, false, null, false, List.of(), null);
    }

    /**
     * Every pre-send check is repeated after the committed claim. Remote reads and inspection
     * are separately obtained, so this is a bounded last-minute check, not a remote CAS.
     */
    private Prepared preSend(TestProjectDeliveryRequest request, Step step) {
        Inspection inspection = inspectOrUnavailable(request);
        gate.requireBeforeMutation(request, inspection);
        Prepared candidate = preparation.prepare(request);
        Observation observed = readOrUnavailable(reader, request);
        if (!complete(observed) || !request.expectedTargetSha().equals(observed.targetHead())
                || inspection == null || inspection.target() == null
                || !Objects.equals(inspection.target().headSha(), observed.targetHead())
                || !observed.mergeRequests().isEmpty()) return null;
        if (step == Step.PUSH) {
            return observed.branch() == null ? candidate : null;
        }
        Branch branch = observed.branch();
        return branch != null && branch.projectId() == request.projectId()
                && request.sourceBranch().equals(branch.name())
                && request.candidateCommitSha().equals(branch.head()) ? candidate : null;
    }

    private static boolean complete(Observation observed) {
        return observed.readable() && observed.branchComplete() && observed.mrListComplete()
                && observed.targetHead() != null;
    }

    private static boolean eligibleForClaimPush(TestProjectDeliveryRecord state) {
        return !state.pushClaimed() && state.push() == State.PLANNED
                && !state.mrClaimed() && state.mergeRequest() == State.PLANNED && !state.residualRace();
    }

    private static boolean eligibleForClaimMr(TestProjectDeliveryRecord state) {
        return state.pushClaimed() && state.push() == State.CONFIRMED
                && state.request().candidateCommitSha().equals(state.lastConfirmedPushHead())
                && !state.mrClaimed() && state.mergeRequest() == State.PLANNED && !state.residualRace();
    }
}
