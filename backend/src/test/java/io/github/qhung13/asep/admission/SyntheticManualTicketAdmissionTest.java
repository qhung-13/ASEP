package io.github.qhung13.asep.admission;

import io.github.qhung13.asep.integration.JiraTicketReader;
import io.github.qhung13.asep.integration.JiraTicketSnapshot;
import io.github.qhung13.asep.integration.internal.jira.FixtureJiraTicketReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.github.qhung13.asep.admission.SyntheticManualTicketAdmission.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticManualTicketAdmissionTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OPERATION = "00000000-0000-4000-8000-000000000001";
    private static final JiraTicketReader.Selection SELECTED = new JiraTicketReader.Selection("synthetic-site", "10001", "10002");
    private static final String PRIVATE = "FAKE_PRIVATE_TICKET_CONTENT_DO_NOT_LOG";
    private final AtomicReference<Actor> actor = new AtomicReference<>();
    private final AtomicReference<Policy> policy = new AtomicReference<>();
    private final AtomicInteger reads = new AtomicInteger();
    private Policy originalPolicy;
    private JiraTicketSnapshot ticket;
    private JsonNode vector;
    private Port port;
    private SyntheticManualTicketAdmission service;

    private static final class Port implements WorkflowPort {
        int submits, lookups;
        Command submitted, reconciled;
        boolean unavailable;
        Outcome outcome = new Outcome(State.ACCEPTED, new Receipt(
                "00000000-0000-4000-8000-000000000010", "00000000-0000-4000-8000-000000000011",
                "00000000-0000-4000-8000-000000000012", false));
        @Override public Outcome submit(Command command) {
            submits++; submitted = command;
            if (unavailable) throw new IllegalStateException(PRIVATE);
            return outcome;
        }
        @Override public Outcome reconcile(Command command) { lookups++; reconciled = command; return outcome; }
    }

    @BeforeEach void setUp() throws Exception {
        try (var in = getClass().getResourceAsStream("/contracts/manual-jira-fixture-v1.json")) { vector = JSON.readTree(in); }
        var limits = new JiraTicketReader.Limits(8192, 256, 1024, 8, 128);
        ticket = new FixtureJiraTicketReader(Map.of(SELECTED, new FixtureJiraTicketReader.Response(200,
                "application/json", vector.get("responseBody").asString().getBytes(StandardCharsets.UTF_8)))).read(SELECTED, limits);
        actor.set(new Actor("synthetic-owner", Role.OWNER));
        originalPolicy = new Policy("synthetic:vn-t10-it-" + "0".repeat(32), "synthetic-manual-v1", 1, true,
                "synthetic-owner", "synthetic-site", "10001", new Repository("42", "0".repeat(40), "main", "1".repeat(40)), 3, limits);
        policy.set(originalPolicy);
        port = new Port();
        service = service((selected, bounded) -> { reads.incrementAndGet(); return ticket; });
    }

    private SyntheticManualTicketAdmission service(JiraTicketReader reader) {
        return new SyntheticManualTicketAdmission(reader, actor::get, policy::get, port);
    }
    private Policy changed(boolean enabled, long epoch, Repository repo) {
        Policy p = originalPolicy;
        return new Policy(p.fixturePrefix(), p.id(), epoch, enabled, p.actorId(), p.siteId(), p.projectId(), repo, p.budget(), p.limits());
    }

    @ParameterizedTest @CsvSource({"Task,To Do", "Task,In Progress", "Bug,To Do", "Bug,In Progress"})
    void admitsOnlyExplicitlySelectedEligibleFixture(String type, String status) {
        ticket = new JiraTicketSnapshot(SELECTED, type, status, ticket.summary(), ticket.description(), ticket.fixtureRevisionSha256());
        var prepared = service.prepare(OPERATION, SELECTED);
        assertEquals(State.ACCEPTED, service.submit(prepared).state());
        assertEquals(1, reads.get());
        assertEquals(1, port.submits);
        assertEquals(OPERATION, port.submitted.commandId());
        assertEquals("synthetic-owner", port.submitted.actorId());
        assertEquals("42", port.submitted.gitLabProjectId());
        assertEquals(ticket.fingerprint(), port.submitted.sourceFingerprintSha256());
    }

    @Test void matchesExistingGoAdmissionContractVectorWithoutPersistingText() {
        var prepared = service.prepare(OPERATION, SELECTED);
        assertEquals(vector.get("command"), JSON.readTree(JSON.writeValueAsBytes(prepared.command())));
        assertFalse(JSON.writeValueAsString(prepared.command()).contains(ticket.summary()));
        assertFalse(JSON.writeValueAsString(prepared.command()).contains("untrusted.invalid"));
        assertFalse(prepared.toString().contains(ticket.description()));
    }

    @ParameterizedTest @ValueSource(strings = {"Viewer", "Worker", "missing", "wrong-owner", "missing-policy", "revoked", "zero-epoch", "missing-repository"})
    void rejectsAbsentAuthorityBeforeAnyJiraRead(String mode) {
        switch (mode) {
            case "Viewer" -> actor.set(new Actor("synthetic-owner", Role.VIEWER));
            case "Worker" -> actor.set(new Actor("synthetic-owner", Role.WORKER));
            case "missing" -> actor.set(null);
            case "wrong-owner" -> actor.set(new Actor("synthetic-other-owner", Role.OWNER));
            case "missing-policy" -> policy.set(null);
            case "revoked" -> policy.set(changed(false, 1, originalPolicy.repository()));
            case "zero-epoch" -> policy.set(changed(true, 0, originalPolicy.repository()));
            case "missing-repository" -> policy.set(changed(true, 1, null));
        }
        var error = assertThrowsExactly(Denied.class, () -> service.prepare(OPERATION, SELECTED));
        assertNull(error.getCause());
        assertEquals(0, reads.get());
        assertEquals(0, port.submits);
    }

    @ParameterizedTest @CsvSource({"Task,Done", "Epic,To Do", "Subtask,To Do", "Story,In Progress", "Bug,Closed"})
    void rejectsUnsupportedTypeOrStatus(String type, String status) {
        ticket = new JiraTicketSnapshot(SELECTED, type, status, ticket.summary(), ticket.description(), ticket.fixtureRevisionSha256());
        assertThrowsExactly(Denied.class, () -> service.prepare(OPERATION, SELECTED));
        assertEquals(0, port.submits);
    }

    @Test void rejectsUnregisteredSiteOrProjectAndUntrustedReturnedIdentity() {
        assertThrowsExactly(Denied.class, () -> service.prepare(OPERATION, new JiraTicketReader.Selection("other-site", "10001", "10002")));
        assertThrowsExactly(Denied.class, () -> service.prepare(OPERATION, new JiraTicketReader.Selection("synthetic-site", "20001", "10002")));
        assertEquals(0, reads.get());
        ticket = new JiraTicketSnapshot(new JiraTicketReader.Selection("synthetic-site", "10001", "10003"), "Task", "To Do", "text", "", "a".repeat(64));
        assertThrowsExactly(Denied.class, () -> service.prepare(OPERATION, SELECTED));
    }

    @ParameterizedTest @ValueSource(strings = {"revoked", "epoch", "source", "owner"})
    void rechecksAuthorityAfterReadAndBeforeSubmission(String mode) {
        var prepared = service.prepare(OPERATION, SELECTED);
        switch (mode) {
            case "revoked" -> policy.set(changed(false, 1, originalPolicy.repository()));
            case "epoch" -> policy.set(changed(true, 2, originalPolicy.repository()));
            case "source" -> policy.set(changed(true, 1, new Repository("42", "0".repeat(40), "main", "2".repeat(40))));
            case "owner" -> actor.set(new Actor("synthetic-owner", Role.VIEWER));
        }
        assertEquals(State.DENIED, service.submit(prepared).state());
        assertEquals(0, port.submits);
        assertEquals(1, reads.get());
    }

    @Test void rejectsRevocationDuringReadAndSanitizesUnavailablePolicyOrReader() {
        var revoking = service((selected, limits) -> { policy.set(changed(false, 1, originalPolicy.repository())); return ticket; });
        assertThrowsExactly(Denied.class, () -> revoking.prepare(OPERATION, SELECTED));
        var unavailable = new SyntheticManualTicketAdmission((s, l) -> ticket, actor::get,
                () -> { throw new IllegalStateException(PRIVATE); }, port);
        assertThrowsExactly(Denied.class, () -> unavailable.prepare(OPERATION, SELECTED));
        policy.set(originalPolicy);
        var readFailure = service((selected, limits) -> { throw new IllegalStateException(PRIVATE); });
        var error = assertThrowsExactly(Denied.class, () -> readFailure.prepare(OPERATION, SELECTED));
        assertNull(error.getCause());
        assertFalse(error.toString().contains(PRIVATE));
        assertEquals(0, port.submits);
    }

    @Test void retainsOriginalCommandAcrossUnknownOutcomeAndChangedJiraData() {
        var prepared = service.prepare(OPERATION, SELECTED);
        port.unavailable = true;
        assertEquals(State.UNKNOWN, service.submit(prepared).state());
        var original = port.submitted;
        ticket = new JiraTicketSnapshot(SELECTED, "Task", "To Do", PRIVATE, "changed", "e".repeat(64));
        port.unavailable = false;
        assertEquals(State.ACCEPTED, service.reconcile(prepared).state());
        assertEquals(original, port.reconciled);
        assertEquals(1, port.submits);
        assertEquals(1, reads.get());
        assertNotEquals(original.sourceFingerprintSha256(), service.prepare(OPERATION, SELECTED).command().sourceFingerprintSha256());
        // A changed snapshot produces a different Go canonical body under the same operation ID.
        // Only Go may decide replay/conflict; the Application does not manufacture acceptance.
    }

    @Test void keepsUncertainIdentityWhenPolicyIsUnavailableAndNeverBlindlyRetries() {
        var prepared = service.prepare(OPERATION, SELECTED);
        port.unavailable = true;
        assertEquals(State.UNKNOWN, service.submit(prepared).state());
        policy.set(null);
        assertEquals(State.UNKNOWN, service.reconcile(prepared).state());
        assertEquals(0, port.lookups);
        policy.set(originalPolicy);
        var accepted = port.outcome;
        port.outcome = new Outcome(State.DENIED, null);
        assertEquals(State.UNKNOWN, service.reconcile(prepared).state());
        port.outcome = accepted;
        assertEquals(State.ACCEPTED, service.reconcile(prepared).state());
        assertEquals(prepared.command(), port.reconciled);
        assertEquals(1, port.submits);
    }

    @Test void hasNoEndpointOrUnverifiedRealDataRevisionShortcut() {
        assertEquals(0, SyntheticManualTicketAdmission.class.getAnnotations().length);
        assertThrows(IllegalArgumentException.class, () -> new JiraTicketSnapshot(SELECTED, "Task", "To Do", "summary", "", "2026-10-27T00:00:00Z"));
        assertThrowsExactly(Denied.class, () -> service.prepare("not-an-operation-id", SELECTED));
        var disabled = service(FixtureJiraTicketReader.liveAccessDisabled());
        assertThrowsExactly(Denied.class, () -> disabled.prepare(OPERATION, SELECTED));
        assertEquals(0, port.submits);
    }
}
