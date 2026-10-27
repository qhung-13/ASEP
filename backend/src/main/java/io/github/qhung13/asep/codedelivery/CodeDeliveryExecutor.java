package io.github.qhung13.asep.codedelivery;

import java.util.Objects;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.*;

/**
 * Trusted, fake-only Java capability. No Spring bean, REST endpoint, worker API or real GitLab transport.
 * Default-deny trusted ports must be supplied independently of intent/agent data.
 */
public final class CodeDeliveryExecutor {
    private final JdbcDeliveryLedger ledger;
    private final DeliveryChecks checks;
    private final FakeGitLab remote;
    public CodeDeliveryExecutor(JdbcDeliveryLedger ledger, DeliveryChecks checks, FakeGitLab remote) {
        this.ledger = Objects.requireNonNull(ledger);
        this.checks = Objects.requireNonNull(checks);
        this.remote = Objects.requireNonNull(remote);
    }

    public DeliveryRecord executeJson(byte[] body) {
        if (body == null || body.length > 8192) throw new DeliveryException(DeliveryException.Kind.INVALID);
        DeliveryIntent intent;
        try { intent = JdbcDeliveryLedger.JSON.readValue(body, DeliveryIntent.class); }
        catch (RuntimeException invalid) { throw new DeliveryException(DeliveryException.Kind.INVALID); }
        if (intent == null) throw new DeliveryException(DeliveryException.Kind.INVALID);
        return execute(intent);
    }

    public DeliveryRecord execute(DeliveryIntent intent) {
        DeliveryRecord record = ledger.admit(Objects.requireNonNull(intent));
        record = step(record, true);
        if (record.push() != State.CONFIRMED
                || (record.residualRace() && record.mergeRequest() == State.PLANNED)) return record;
        return step(record, false);
    }

    public void acknowledge(Callback callback) {
        boolean trusted;
        try { trusted = checks.trustedCallbackAcknowledgement(); }
        catch (RuntimeException unavailable) { trusted = false; }
        if (!trusted) throw new DeliveryException(DeliveryException.Kind.INVALID);
        ledger.acknowledge(Objects.requireNonNull(callback));
    }

    private DeliveryRecord step(DeliveryRecord record, boolean push) {
        State state = push ? record.push() : record.mergeRequest();
        if (state == State.CONFIRMED || state == State.BLOCKED || state == State.MANUAL_RECONCILIATION_REQUIRED) return record;
        DeliveryIntent intent = record.intent();
        DeliveryChecks.Access access = access(intent);
        if (!access.readAllowed()) return save(record, push,
                state == State.PLANNED ? State.BLOCKED : State.MANUAL_RECONCILIATION_REQUIRED,
                null, false, Reason.READ_REVOKED);

        // Always inspect remote ownership/head before a new send or recovery. Absence
        // while IN_PROGRESS/UNKNOWN is not proof of no effect and never authorizes retry.
        FakeGitLab.Branch branch = remote.branch(intent);
        if (push && branch != null) {
            if (!matches(intent, branch)) return save(record, true, State.BLOCKED, null, false, Reason.REMOTE_MISMATCH);
            return save(record, true, State.CONFIRMED, null, false, Reason.NONE);
        }
        if (!push) {
            // Read the actual MR even when the source drifted: an earlier send may
            // already have created it, and its identity must survive reconciliation.
            var matches = remote.mergeRequests(intent);
            if (!matches.isEmpty()) {
                if (matches.size() != 1) return save(record, false, State.UNKNOWN, null, !matches(intent, branch), Reason.REMOTE_UNCERTAIN);
                FakeGitLab.MergeRequest mr = matches.getFirst();
                if (!matches(intent, branch) || !matches(intent, mr)) return save(record, false, State.BLOCKED,
                        owned(intent, mr) ? mr.iid() : null, true, Reason.REMOTE_MISMATCH);
                return save(record, false, State.CONFIRMED, mr.iid(), false, Reason.NONE);
            }
            if (!matches(intent, branch)) return save(record, false, State.BLOCKED, null, state != State.PLANNED, Reason.REMOTE_MISMATCH);
        }
        if (state != State.PLANNED) return state == State.UNKNOWN ? record
                : save(record, push, State.UNKNOWN, null, false, Reason.REMOTE_UNCERTAIN);
        if (!access.writeAllowed() || !valid(intent)) return save(record, push, State.BLOCKED, null, false, Reason.PREREQUISITE);

        // Claim is a short committed transaction. Only the CAS winner sends; a crash
        // after this commit leaves recoverable IN_PROGRESS, never an unlocked PLANNED send.
        var claimed = ledger.change(record, push ? State.IN_PROGRESS : record.push(),
                push ? record.mergeRequest() : State.IN_PROGRESS, record.mrIid(), record.residualRace(), Reason.NONE);
        if (!claimed.applied()) return claimed.record();
        record = claimed.record();
        if (!access(intent).writeAllowed() || !valid(intent)) return save(record, push, State.BLOCKED, null, false, Reason.PREREQUISITE);
        State outcome;
        Long iid = null;
        Reason reason = Reason.NONE;
        boolean mrMismatch = false;
        try {
            if (push) {
                if (!matches(intent, remote.push(intent))) {
                    return save(record, true, State.BLOCKED, null, true, Reason.REMOTE_MISMATCH);
                }
            } else {
                // Recheck confirmed branch immediately before the MR send as well.
                if (!matches(intent, remote.branch(intent))) return save(record, false, State.BLOCKED, null, false, Reason.REMOTE_MISMATCH);
                var mr = remote.create(intent, ControlledDraft.render(intent));
                iid = owned(intent, mr) ? mr.iid() : null;
                mrMismatch = !matches(intent, mr) || !matches(intent, remote.branch(intent));
            }
            outcome = mrMismatch ? State.BLOCKED : State.CONFIRMED;
            if (mrMismatch) reason = Reason.REMOTE_MISMATCH;
        } catch (FakeGitLab.Rejected rejected) {
            outcome = State.BLOCKED;
            reason = Reason.REMOTE_REJECTED;
        } catch (RuntimeException uncertain) {
            // Even an unexpected transport/adapter error after entering the send path
            // cannot prove rollback. Do not retain its raw exception.
            outcome = State.UNKNOWN;
            reason = Reason.REMOTE_UNCERTAIN;
        }
        boolean race = mrMismatch || !access(intent).writeAllowed() || !valid(intent)
                || (!push && !matches(intent, remote.branch(intent)));
        if (race && !mrMismatch) reason = Reason.POST_SEND_CHANGE;
        if (outcome == State.UNKNOWN && !access(intent).readAllowed()) {
            outcome = State.MANUAL_RECONCILIATION_REQUIRED;
            reason = Reason.READ_REVOKED;
        }
        DeliveryRecord result = save(record, push, outcome, iid, race, reason);
        if (race && !result.residualRace()) {
            // A competing read-based reconciliation may have won our CAS. Preserve
            // its proven facts while still recording the observed post-send race.
            result = ledger.change(result, result.push(), result.mergeRequest(), result.mrIid(), true, Reason.POST_SEND_CHANGE).record();
            if (!result.residualRace()) throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);
        }
        return result;
    }

    private DeliveryRecord save(DeliveryRecord record, boolean push, State state, Long iid, boolean race, Reason reason) {
        if ((push ? record.push() : record.mergeRequest()) == state
                && Objects.equals(push ? record.mrIid() : iid, record.mrIid())
                && (!race || record.residualRace()) && record.reason() == reason) return record;
        return ledger.change(record, push ? state : record.push(), push ? record.mergeRequest() : state,
                push ? record.mrIid() : iid, record.residualRace() || race, reason).record();
    }

    private DeliveryChecks.Access access(DeliveryIntent intent) {
        try {
            var access = checks.access(intent.repositoryId());
            return access == null ? new DeliveryChecks.Access(false, false) : access;
        } catch (RuntimeException unavailable) { return new DeliveryChecks.Access(false, false); }
    }

    private boolean valid(DeliveryIntent i) {
        try {
            var current = checks.current(i.operationId());
            if (current == null || !i.equals(current.approvedIntent()) || !current.committedGoIntent()
                    || !current.ownerAuthorized() || !current.safeCi()
                    || !i.safeCiProfileSha256().equals(current.safeCiProfileSha256())
                    || !i.expectedTargetSha().equals(current.observedTargetSha())
                    || !i.expectedTargetSha().equals(remote.target(i.repositoryId()))) return false;
            var candidate = current.candidate();
            var evidence = current.verification();
            return candidate != null && candidate.writerStopped() && candidate.runId().equals(i.runId())
                    && candidate.attemptId().equals(i.attemptId()) && candidate.baseSha().equals(i.baseSha())
                    && candidate.commitSha().equals(i.candidateCommitSha()) && candidate.treeSha().equals(i.candidateTreeSha())
                    && candidate.manifestSha256().equals(i.manifestSha256()) && candidate.sealReceiptId().equals(i.sealReceiptId())
                    && evidence != null && evidence.independentController() && evidence.runId().equals(i.runId())
                    && evidence.attemptId().equals(i.attemptId()) && evidence.manifestSha256().equals(i.manifestSha256())
                    && evidence.receiptId().equals(i.verificationReceiptId()) && evidence.profileSha256().equals(i.verifierProfileSha256())
                    && evidence.discovered() > 0 && evidence.executed() > 0 && evidence.failed() == 0
                    && evidence.skipped() == 0 && evidence.executed() == evidence.discovered() && !evidence.timedOut();
        } catch (RuntimeException unavailable) { return false; }
    }

    private static boolean matches(DeliveryIntent i, FakeGitLab.Branch b) {
        return b != null && i.repositoryId().equals(b.repository()) && i.sourceBranch().equals(b.name())
                && i.candidateCommitSha().equals(b.head()) && i.operationId().equals(b.owner()) && i.bodyHash().equals(b.intentHash());
    }
    private static boolean matches(DeliveryIntent i, FakeGitLab.MergeRequest m) {
        return m.iid() > 0 && m.draft() && i.repositoryId().equals(m.repository()) && i.sourceBranch().equals(m.source())
                && i.targetBranch().equals(m.target()) && i.candidateCommitSha().equals(m.head())
                && i.operationId().equals(m.owner()) && i.bodyHash().equals(m.intentHash());
    }

    private static boolean owned(DeliveryIntent i, FakeGitLab.MergeRequest m) {
        return m.iid() > 0 && i.repositoryId().equals(m.repository()) && i.sourceBranch().equals(m.source())
                && i.operationId().equals(m.owner()) && i.bodyHash().equals(m.intentHash());
    }
}
