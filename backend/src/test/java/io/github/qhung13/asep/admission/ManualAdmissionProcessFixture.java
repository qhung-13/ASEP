package io.github.qhung13.asep.admission;

import io.github.qhung13.asep.integration.JiraTicketReader;
import io.github.qhung13.asep.integration.internal.jira.FixtureJiraTicketReader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static io.github.qhung13.asep.admission.SyntheticManualTicketAdmission.*;

/** Test-classpath-only pipe peer. No network listener, SQL access or service credentials. */
public final class ManualAdmissionProcessFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private ManualAdmissionProcessFixture() {}

    public static void main(String[] args) throws Exception {
        // The Go parent deliberately passes only LANG/TZ. Fail if that isolation regresses.
        if (System.getenv().keySet().stream().anyMatch(key -> !key.equals("LANG") && !key.equals("TZ"))) {
            throw new IllegalStateException("private fixture environment not isolated");
        }
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        JsonNode config = JSON.readTree(input.readLine());
        var selected = new JiraTicketReader.Selection("synthetic-site", config.get("projectId").asString(), config.get("issueId").asString());
        var limits = new JiraTicketReader.Limits(8192, 256, 1024, 8, 128);
        var body = new AtomicReference<>(config.get("body").asString());
        var reads = new AtomicInteger();
        var policy = new Policy(config.get("prefix").asString(), "synthetic-manual-v1", 1, true,
                "synthetic-owner", "synthetic-site", "10001", new Repository("42", "0".repeat(40), "main", "1".repeat(40)), 3, limits);
        var current = new AtomicReference<>(policy);
        var unavailable = new java.util.concurrent.atomic.AtomicBoolean();
        var port = new WorkflowPort() {
            private Outcome exchange(String kind, Command command) {
                try {
                    emit(Map.of("kind", kind, "command", command));
                    JsonNode answer = JSON.readTree(input.readLine());
                    State state = State.valueOf(answer.get("state").asString());
                    Receipt receipt = null;
                    if (state == State.ACCEPTED) {
                        JsonNode r = answer.get("receipt");
                        receipt = new Receipt(r.get("runId").asString(), r.get("taskId").asString(), r.get("attemptId").asString(), r.get("replayed").asBoolean());
                    }
                    return new Outcome(state, receipt);
                } catch (Exception failure) { throw new IllegalStateException("private pipe unavailable"); }
            }
            @Override public Outcome submit(Command command) { return exchange("submit", command); }
            @Override public Outcome reconcile(Command command) { return exchange("reconcile", command); }
        };
        var service = new SyntheticManualTicketAdmission((s, l) -> {
            reads.incrementAndGet();
            return new FixtureJiraTicketReader(Map.of(s, new FixtureJiraTicketReader.Response(200, "application/json",
                    body.get().getBytes(StandardCharsets.UTF_8)))).read(s, l);
        }, () -> new Actor("synthetic-owner", Role.OWNER), () -> {
            if (unavailable.get()) throw new IllegalStateException("fixture policy unavailable");
            return current.get();
        }, port);
        var prepared = new java.util.HashMap<String, Prepared>();
        String line;
        while ((line = input.readLine()) != null) {
            JsonNode action = JSON.readTree(line);
            String kind = action.get("action").asString();
            if (kind.equals("policy")) {
                String mode = action.get("mode").asString();
                unavailable.set(mode.equals("unavailable"));
                current.set(mode.equals("missing") ? null : new Policy(policy.fixturePrefix(), policy.id(), policy.epoch(),
                        !mode.equals("revoked"), policy.actorId(), policy.siteId(), policy.projectId(),
                        mode.equals("unmapped") ? null : mode.equals("remapped")
                                ? new Repository("43", "0".repeat(40), "main", "1".repeat(40)) : policy.repository(), policy.budget(), policy.limits()));
                emit(Map.of("kind", "policy-set"));
                continue;
            }
            if (kind.equals("body")) {
                body.set(action.get("body").asString());
                emit(Map.of("kind", "body-set"));
                continue;
            }
            String slot = action.get("slot").asString();
            try {
                Outcome outcome;
                if (kind.equals("prepare")) {
                    prepared.put(slot, service.prepare(action.get("operationId").asString(), selected));
                    emit(Map.of("kind", "prepared", "reads", reads.get(), "command", prepared.get(slot).command()));
                    continue;
                } else if (kind.equals("resume")) {
                    // Test-owned journal contains ONLY the original canonical command. Rebuilding
                    // preparation after restart is safe only when its complete identity still matches.
                    Command original = JSON.treeToValue(action.get("command"), Command.class);
                    Prepared restored = service.prepare(original.commandId(), selected);
                    if (!restored.command().equals(original)) throw new Denied();
                    prepared.put(slot, restored);
                    outcome = service.reconcile(restored); // Never submit a replacement on restart.
                } else if (kind.equals("submit")) {
                    outcome = service.submit(prepared.get(slot));
                } else if (kind.equals("reconcile")) {
                    outcome = service.reconcile(prepared.get(slot));
                } else throw new IllegalArgumentException("invalid fixture action");
                emit(outcome.receipt() == null
                        ? Map.of("kind", "outcome", "state", outcome.state().name(), "reads", reads.get())
                        : Map.of("kind", "outcome", "state", outcome.state().name(), "reads", reads.get(), "receipt", outcome.receipt()));
            } catch (Denied denied) {
                // Refusal to reconstruct an unresolved journal cannot prove the original failed.
                emit(Map.of("kind", "outcome", "state", kind.equals("resume") ? "UNKNOWN" : "DENIED", "reads", reads.get()));
            }
        }
    }
    private static void emit(Object message) {
        System.out.println(JSON.writeValueAsString(message));
        System.out.flush();
    }
}
