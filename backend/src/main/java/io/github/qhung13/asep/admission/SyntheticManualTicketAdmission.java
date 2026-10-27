package io.github.qhung13.asep.admission;

import io.github.qhung13.asep.integration.JiraTicketReader;
import io.github.qhung13.asep.integration.JiraTicketSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Private offline slice, intentionally NOT a bean, endpoint or production policy issuer.
 * Fixture profiles exercise the Owner's stated direction; missing VN-T12/VN-T02
 * artifacts and service identity must be resolved before real intake is wired.
 */
final class SyntheticManualTicketAdmission {
    enum Role { OWNER, VIEWER, WORKER }
    record Actor(String id, Role role) {}
    record Repository(String projectId, String baseSha, String targetBranch, String observedHeadSha) {}
    record Policy(String fixturePrefix, String id, long epoch, boolean enabled, String actorId,
            String siteId, String projectId, Repository repository, int budget, JiraTicketReader.Limits limits) {}
    interface SecurityContext { Actor authenticatedActor(); }
    interface PolicyProvider { Policy current(); }

    /** Existing Go v1 Admission body. Go computes its own canonical payload hash. */
    record Command(String commandId, String subjectKey, String actorId, String jiraSiteId,
            String jiraProjectId, String jiraIssueId, String gitLabProjectId, String repositoryBaseSha,
            String targetBranch, String observedTargetHeadSha, String sourceFingerprintSha256,
            String policyId, long policyEpoch, int budgetLimit) {
        @Override public String toString() { return "SyntheticManualAdmissionCommand[redacted]"; }
    }
    enum State { ACCEPTED, CONFLICT, UNKNOWN, DENIED }
    record Receipt(String runId, String taskId, String attemptId, boolean replayed) {}
    record Outcome(State state, Receipt receipt) {}
    interface WorkflowPort {
        Outcome submit(Command originalCommand);
        Outcome reconcile(Command originalCommand);
    }
    static final class Prepared {
        private final Command command;
        private final Policy policy;
        private Prepared(Command command, Policy policy) { this.command = command; this.policy = policy; }
        Command command() { return command; }
        @Override public String toString() { return "PreparedSyntheticAdmission[redacted]"; }
    }
    static final class Denied extends RuntimeException {
        Denied() { super("Synthetic manual admission denied or prerequisite unavailable"); }
    }
    private record Gate(Actor actor, Policy policy) {}
    private final JiraTicketReader reader;
    private final SecurityContext security;
    private final PolicyProvider policies;
    private final WorkflowPort port;

    SyntheticManualTicketAdmission(JiraTicketReader reader, SecurityContext security,
            PolicyProvider policies, WorkflowPort port) {
        this.reader = Objects.requireNonNull(reader);
        this.security = Objects.requireNonNull(security);
        this.policies = Objects.requireNonNull(policies);
        this.port = Objects.requireNonNull(port);
    }

    Prepared prepare(String operationId, JiraTicketReader.Selection selection) {
        try {
            if (!uuid(operationId)) throw new Denied();
            Gate gate = gate(selection);
            JiraTicketSnapshot ticket = reader.read(selection, gate.policy.limits);
            if (!ticket.identity().equals(selection) || !Set.of("Task", "Bug").contains(ticket.issueType())
                    || !Set.of("To Do", "In Progress").contains(ticket.status())
                    || bytes(ticket.summary()) > gate.policy.limits.summaryBytes()
                    || bytes(ticket.description()) > gate.policy.limits.descriptionBytes()
                    || !gate.equals(gate(selection))) throw new Denied();
            Policy p = gate.policy;
            Repository r = p.repository;
            Command command = new Command(operationId, p.fixturePrefix + ":manual:" + selection.issueId(),
                    gate.actor.id, selection.siteId(), selection.projectId(), selection.issueId(), r.projectId,
                    r.baseSha, r.targetBranch, r.observedHeadSha, ticket.fingerprint(), p.id, p.epoch, p.budget);
            return new Prepared(command, p); // Retain only IDs/digests, never description or raw Jira JSON.
        } catch (RuntimeException failure) {
            throw new Denied();
        }
    }

    Outcome submit(Prepared prepared) { return invoke(prepared, false); }
    Outcome reconcile(Prepared prepared) { return invoke(prepared, true); }

    private Outcome invoke(Prepared prepared, boolean lookup) {
        try {
            if (prepared == null) throw new Denied();
            Command c = prepared.command;
            Gate current = gate(new JiraTicketReader.Selection(c.jiraSiteId, c.jiraProjectId, c.jiraIssueId));
            if (!current.policy.equals(prepared.policy) || !current.actor.id.equals(c.actorId)) throw new Denied();
        } catch (RuntimeException failure) {
            // Denying a lookup cannot prove that the original admission failed.
            return new Outcome(lookup ? State.UNKNOWN : State.DENIED, null);
        }
        try {
            // No automatic resubmission on timeout. Reconciliation uses this exact
            // immutable command, without re-reading possibly changed Jira data.
            Outcome result = lookup ? port.reconcile(prepared.command) : port.submit(prepared.command);
            if (result == null || result.state == null || (result.state != State.ACCEPTED && result.receipt != null)
                    || (result.state == State.ACCEPTED
                    && (result.receipt == null || !uuid(result.receipt.runId)
                    || !uuid(result.receipt.taskId) || !uuid(result.receipt.attemptId)))) {
                return new Outcome(State.UNKNOWN, null);
            }
            if (lookup && result.state == State.DENIED) return new Outcome(State.UNKNOWN, null);
            return result;
        } catch (RuntimeException unavailableOrUncertain) {
            return new Outcome(State.UNKNOWN, null);
        }
    }

    private Gate gate(JiraTicketReader.Selection selection) {
        Actor actor = security.authenticatedActor();
        Policy p = policies.current();
        if (actor == null || actor.role != Role.OWNER || actor.id == null || !actor.id.matches("synthetic-[A-Za-z0-9._:-]+")
                || actor.id.length() > 128 || p == null || !p.enabled || p.epoch < 1 || p.budget < 1 || p.budget > 100
                || p.fixturePrefix == null || !p.fixturePrefix.matches("synthetic:vn-t10-it-[0-9a-f]{32}")
                || !actor.id.equals(p.actorId) || !selection.siteId().equals(p.siteId)
                || !selection.projectId().equals(p.projectId) || p.id == null || !p.id.matches("synthetic-[A-Za-z0-9._:-]+")
                || p.id.length() > 128 || p.limits == null || p.repository == null) throw new Denied();
        Repository r = p.repository;
        if (r.projectId == null || !r.projectId.matches("[1-9][0-9]{0,18}")
                || !sha(r.baseSha) || !sha(r.observedHeadSha) || r.targetBranch == null
                || !r.targetBranch.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")) throw new Denied();
        return new Gate(actor, p);
    }
    private static boolean uuid(String value) {
        try { return value != null && UUID.fromString(value).toString().equals(value); }
        catch (IllegalArgumentException invalid) { return false; }
    }
    private static boolean sha(String value) { return value != null && value.matches("[0-9a-f]{40}|[0-9a-f]{64}"); }
    private static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
}
