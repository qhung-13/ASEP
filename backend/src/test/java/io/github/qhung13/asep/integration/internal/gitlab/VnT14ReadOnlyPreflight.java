package io.github.qhung13.asep.integration.internal.gitlab;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.web.client.RestClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit trusted Java host entry point, never a JUnit test or application bean.
 * No mutation transport, subprocess, environment credential discovery or live gate grant.
 * Responses are bounded and reduced to allowlisted facts before leaving this process.
 */
public final class VnT14ReadOnlyPreflight {
    private static final String PROJECT = "/projects/87786140";
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private record Read(int status, JsonNode body, boolean complete) {}
    private VnT14ReadOnlyPreflight() {}

    public static void main(String[] args) {
        // Exact Owner-enrolled destination is compiled in; the argument is only a file path.
        if (args.length != 3 || !"--owner-read-only".equals(args[0])) {
            System.err.println("Explicit Owner read-only mode, credential file and private report path required");
            System.exit(2);
        }
        try (var quiet = new QuietLibraryLogging(); var transport = new GitLabReadHttpClientFactory()) {
            Map<String, Object> report = OwnerSuppliedCredentialFile.withReadClient(Path.of(args[1]), transport,
                    VnT14ReadOnlyPreflight::inspect);
            Path output = Path.of(args[2]);
            writeReport(output, report);
            System.out.println("Read-only inspection recorded; live-write gate BLOCKED; mutations performed: 0");
        } catch (Exception unavailable) {
            System.err.println("Trusted read-only inspection unavailable; no live mutation attempted");
            System.exit(1); // No source-bearing exception, credential or path is printed.
        }
    }

    static void writeReport(Path output, Map<String, Object> report) throws IOException {
        byte[] content = (JSON.writeValueAsString(report) + "\n").getBytes(StandardCharsets.UTF_8);
        // Atomic exclusive creation; all writes use this no-follow handle, never reopen the path.
        try (var channel = FileChannel.open(output,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
            var bytes = ByteBuffer.wrap(content);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }
    static Map<String, Object> inspect(RestClient client) { return inspect(client, Clock.systemUTC()); }
    static Map<String, Object> inspect(RestClient client, Clock clock) {
        var startedAt = clock.instant();
        var facts = new LinkedHashMap<String, Object>();
        var requests = new LinkedHashMap<String, Object>();
        facts.put("project_id", 87786140L);
        facts.put("permitted_source", "test/vn-t14/<operation-id>");
        facts.put("permitted_target", "main");
        facts.put("owner_grant", "User-supplied 2026-10-28; Draft MR test only");
        facts.put("live_write_gate", "BLOCKED");
        facts.put("mutations_performed", 0);
        facts.put("requests", requests);
        facts.put("unestablished_requirements", List.of("safe CI including inherited settings", "sealed candidate and independent verification receipt",
                "trusted live executor wiring and controlled fault windows"));
        var user = get(client, "/user", "identity", requests);
        facts.put("identity_is_bot", flag(user.body, "bot"));
        var token = get(client, "/personal_access_tokens/self", "credential_metadata", requests);
        facts.put("credential_active", flag(token.body, "active"));
        facts.put("credential_revoked", flag(token.body, "revoked"));
        facts.put("credential_expiration_matches_owner", token.body != null && "2026-11-04".equals(token.body.path("expires_at").asString()));
        var scopes = new ArrayList<String>();
        if (token.body != null && token.body.path("scopes").isArray()) {
            for (JsonNode scope : token.body.path("scopes")) {
                String value = scope.isString() ? scope.asString() : "";
                scopes.add(List.of("api", "read_api", "write_repository", "read_repository").contains(value) ? value : "OTHER_SCOPE");
            }
        }
        facts.put("credential_scopes", scopes);
        var project = get(client, PROJECT, "project", requests);
        JsonNode p = project.body;
        facts.put("project_identity_matches_owner", p != null && p.path("id").asLong() == 87786140L
                && "qhung-13/asep-m01-sandbox".equals(p.path("path_with_namespace").asString()));
        facts.put("archived", flag(p, "archived"));
        facts.put("shared_runners_enabled", flag(p, "shared_runners_enabled"));
        facts.put("auto_devops_enabled", flag(p, "auto_devops_enabled"));
        facts.put("ci_config_override_present", p == null || !p.has("ci_config_path") ? null
                : !p.path("ci_config_path").isNull() && !p.path("ci_config_path").asString("").isBlank());
        facts.put("effective_access_level", effectiveAccess(p));
        var branch = get(client, PROJECT + "/repository/branches/main", "target_branch", requests);
        facts.put("target_protected", flag(branch.body, "protected"));
        facts.put("credential_can_push_target", flag(branch.body, "can_push"));
        String head = branch.body == null ? "" : branch.body.path("commit").path("id").asString("");
        if (!head.matches("[0-9a-f]{40}")) head = "";
        facts.put("target_head", head.isEmpty() ? null : head);
        var protectedBranches = getCollection(client, PROJECT + "/protected_branches?per_page=100", "protected_branches", requests);
        var access = new ArrayList<Object>();
        if (protectedBranches.complete && protectedBranches.body != null && protectedBranches.body.isArray()) {
            for (JsonNode b : protectedBranches.body) {
                // Arbitrary names, descriptions and user identities never leave this reader.
                if ("main".equals(b.path("name").asString(""))) {
                    access.add(Map.of("push_levels", levels(b.path("push_access_levels")), "merge_levels", levels(b.path("merge_access_levels")),
                            "allow_force_push", Boolean.TRUE.equals(flag(b, "allow_force_push"))));
                }
            }
        }
        facts.put("main_protection", access);
        getCollection(client, PROJECT + "/runners?per_page=100", "runners", requests);
        getCollection(client, PROJECT + "/variables?per_page=100", "project_variables", requests);
        getCollection(client, PROJECT + "/environments?per_page=100", "environments", requests);
        getCollection(client, PROJECT + "/protected_environments?per_page=100", "protected_environments", requests);
        getCollection(client, PROJECT + "/deploy_keys?per_page=100", "deploy_keys", requests);
        getCollection(client, PROJECT + "/hooks?per_page=100", "hooks", requests);
        if (!head.isEmpty()) {
            var pipelines = getCollection(client, PROJECT + "/pipelines?per_page=100&sha=" + head, "target_pipelines", requests);
            var observations = new ArrayList<Object>();
            if (pipelines.complete && pipelines.body != null && pipelines.body.isArray()) {
                for (JsonNode pipeline : pipelines.body) {
                    String sha = pipeline.path("sha").asString("");
                    String status = pipeline.path("status").asString("");
                    if (sha.equals(head) && status.matches("[a-z_]{1,40}")) observations.add(Map.of("id", pipeline.path("id").asLong(), "sha", sha, "status", status));
                }
            }
            facts.put("observed_pipelines", observations);
            var ci = get(client, PROJECT + "/repository/files/{file}?ref=" + head, "target_ci_file", requests, ".gitlab-ci.yml");
            facts.put("default_target_ci_file_present", ci.status == 200 && ci.complete ? true : ci.status == 404 ? false : null);
        }
        if (p != null && p.path("namespace").path("id").isIntegralNumber()) {
            long namespace = p.path("namespace").path("id").asLong();
            if (namespace > 0) {
                var account = get(client, "/namespaces/" + namespace, "namespace", requests);
                String plan = account.body == null ? "" : account.body.path("plan").asString("");
                facts.put("visible_plan", List.of("free", "bronze", "silver", "gold", "premium", "ultimate").contains(plan) ? plan : "UNKNOWN");
            }
        }
        facts.put("inspection_started_at", startedAt.toString());
        facts.put("inspection_completed_at", clock.instant().toString());
        return facts;
    }
    private static List<Long> levels(JsonNode entries) {
        var levels = new ArrayList<Long>();
        if (entries.isArray()) for (JsonNode e : entries) if (e.path("access_level").isIntegralNumber()) levels.add(e.path("access_level").asLong());
        return levels;
    }
    private static Integer effectiveAccess(JsonNode p) {
        if (p == null) return null;
        int level = -1;
        for (String kind : List.of("project_access", "group_access")) {
            JsonNode value = p.path("permissions").path(kind).path("access_level");
            if (value.isIntegralNumber() && value.asInt() >= 0 && value.asInt() <= 50) level = Math.max(level, value.asInt());
        }
        return level < 0 ? null : level;
    }
    private static Boolean flag(JsonNode p, String field) {
        return p != null && p.path(field).isBoolean() ? p.path(field).booleanValue() : null;
    }
    private static Read get(RestClient client, String path, String label, Map<String, Object> requests, Object... variables) {
        return read(client, path, label, requests, false, variables);
    }
    private static Read getCollection(RestClient client, String path, String label, Map<String, Object> requests) {
        return read(client, path, label, requests, true);
    }
    private static Read read(RestClient client, String path, String label, Map<String, Object> requests, boolean collection, Object... variables) {
        Read result;
        try {
            result = client.get().uri(path, variables).exchange((request, response) -> {
                int code = response.getStatusCode().value();
                if (code != 200) return new Read(code, null, false);
                String type = response.getHeaders().getFirst("Content-Type");
                if (type == null || !type.matches("(?i)application/json(?:;\\s*charset=utf-8)?") || response.getHeaders().getContentLength() > 65536)
                    return new Read(code, null, false);
                byte[] bytes = response.getBody().readNBytes(65537);
                if (bytes.length > 65536) return new Read(code, null, false);
                JsonNode body;
                try { body = JSON.readTree(bytes); }
                catch (RuntimeException malformed) { return new Read(code, null, false); }
                if (collection ? !GitLabInventoryPage.collectionShape(body) : body == null || !body.isObject()) return new Read(code, null, false);
                return new Read(code, body, !collection || GitLabInventoryPage.complete(body, response.getHeaders()));
            });
        } catch (RuntimeException unavailable) { result = new Read(0, null, false); }
        var summary = new LinkedHashMap<String, Object>();
        summary.put("http_status", result.status);
        summary.put("complete", result.complete);
        summary.put("count", result.body != null && result.body.isArray() ? result.body.size() : null);
        requests.put(label, summary);
        return result;
    }
    private static final class QuietLibraryLogging implements AutoCloseable {
        private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        private final TurboFilter filter = new TurboFilter() {
            @Override public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] params, Throwable failure) { return FilterReply.DENY; }
        };
        QuietLibraryLogging() { filter.start(); context.getTurboFilterList().add(0, filter); }
        @Override public void close() { context.getTurboFilterList().remove(filter); filter.stop(); }
    }
}
