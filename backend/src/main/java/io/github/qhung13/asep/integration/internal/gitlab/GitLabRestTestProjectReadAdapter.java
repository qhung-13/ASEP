package io.github.qhung13.asep.integration.internal.gitlab;

import io.github.qhung13.asep.integration.GitLabTestProjectReader;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Objects;
import org.springframework.web.client.RestClient;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Trusted Java GET-only inspection. Supply a client from GitLabReadHttpClientFactory;
 * no credential discovery, mutations, Spring wiring or real-test activation exists here.
 */
public final class GitLabRestTestProjectReadAdapter implements GitLabTestProjectReader {
    private static final int MAXIMUM_BYTES = 65536;
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final RestClient client;
    private final Clock clock;
    public GitLabRestTestProjectReadAdapter(RestClient client) { this(client, Clock.systemUTC()); }
    GitLabRestTestProjectReadAdapter(RestClient client, Clock clock) {
        this.client = Objects.requireNonNull(client); this.clock = Objects.requireNonNull(clock);
    }
    private record Response(JsonNode body, boolean complete) {}

    @Override public Inspection inspect(long projectId, String targetBranch) {
        if (projectId < 1 || targetBranch == null || !targetBranch.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,127}")
                || targetBranch.contains("..") || targetBranch.contains("//") || targetBranch.endsWith("/")
                || targetBranch.endsWith(".lock")) throw new IllegalArgumentException("Invalid test-project selection");
        var startedAt = clock.instant();
        String path = "/projects/" + projectId;
        var gaps = EnumSet.noneOf(Gap.class);
        Project project = null;
        Target target = null;
        Response metadata = read(path);
        try {
            JsonNode p = metadata.body;
            if (!metadata.complete || !p.isObject() || number(p, "id") != projectId) throw invalid();
            JsonNode permissions = p.get("permissions");
            int access = -1;
            if (permissions != null && permissions.isObject()) {
                for (String field : new String[]{"project_access", "group_access"}) {
                    JsonNode role = permissions.get(field);
                    if (role != null && !role.isNull()) {
                        long level = number(role, "access_level");
                        if (level < 0 || level > 50) throw invalid();
                        access = Math.max(access, (int) level);
                    }
                }
            }
            if (access < 0) gaps.add(Gap.PERMISSIONS);
            JsonNode shared = p.get("shared_runners_enabled");
            project = new Project(projectId, text(p, "path_with_namespace"), text(p, "visibility"),
                    bool(p, "archived"), text(p, "default_branch"), text(p, "builds_access_level"),
                    shared != null && shared.isBoolean() ? shared.booleanValue() : null, access);
        } catch (RuntimeException unavailable) { gaps.add(Gap.PROJECT); }
        Response branch = read(path + "/repository/branches/{branch}", targetBranch);
        try {
            JsonNode b = branch.body;
            if (!branch.complete || !targetBranch.equals(text(b, "name"))) throw invalid();
            String sha = text(b.get("commit"), "id");
            if (!sha.matches("[0-9a-f]{40}")) throw invalid();
            target = new Target(targetBranch, sha, bool(b, "protected"), bool(b, "can_push"));
        } catch (RuntimeException unavailable) { gaps.add(Gap.TARGET); }
        var protection = collection(path + "/protected_branches?per_page=100", Gap.PROTECTED_BRANCHES, gaps);
        var runners = collection(path + "/runners?per_page=100", Gap.RUNNERS, gaps);
        // Variable keys, values, descriptions and environment scopes are never returned/persisted.
        var variables = collection(path + "/variables?per_page=100", Gap.VARIABLES, gaps);
        var environments = collection(path + "/environments?per_page=100", Gap.ENVIRONMENTS, gaps);
        Response pipelineResponse = target == null ? new Response(null, false)
                : read(path + "/pipelines?per_page=100&sha=" + target.headSha());
        var pipelines = observation(pipelineResponse, Gap.PIPELINES, gaps);
        var observed = new ArrayList<Pipeline>();
        if (pipelineResponse.body != null && pipelineResponse.body.isArray()) {
            try {
                for (JsonNode p : pipelineResponse.body) {
                    String sha = text(p, "sha");
                    if (target == null || !sha.equals(target.headSha()) || number(p, "id") < 1) throw invalid();
                    String status = text(p, "status");
                    String source = text(p, "source");
                    if (!status.matches("[a-z_]{1,40}") || !source.matches("[a-z_]{1,40}")) throw invalid();
                    observed.add(new Pipeline(number(p, "id"), sha, status, source));
                }
            } catch (RuntimeException malformed) { gaps.add(Gap.PIPELINES); observed.clear(); pipelines = CollectionObservation.unavailable(); }
        }
        return new Inspection(projectId, targetBranch, project, target, protection, runners, variables,
                environments, pipelines, observed, gaps, startedAt, clock.instant());
    }

    private CollectionObservation collection(String path, Gap gap, EnumSet<Gap> gaps) {
        return observation(read(path), gap, gaps);
    }
    private static CollectionObservation observation(Response response, Gap gap, EnumSet<Gap> gaps) {
        if (!GitLabInventoryPage.collectionShape(response.body)) { gaps.add(gap); return CollectionObservation.unavailable(); }
        if (!response.complete || response.body.size() >= 100) gaps.add(gap);
        return new CollectionObservation(response.body.size(), response.complete && response.body.size() < 100);
    }
    private Response read(String path, Object... variables) {
        try {
            return client.get().uri(path, variables).exchange((request, response) -> {
                if (response.getStatusCode().value() != 200) return new Response(null, false);
                String type = response.getHeaders().getFirst("Content-Type");
                if (type == null || !type.matches("(?i)application/json(?:;\\s*charset=utf-8)?")) return new Response(null, false);
                long length = response.getHeaders().getContentLength();
                if (length > MAXIMUM_BYTES) return new Response(null, false);
                byte[] body = response.getBody().readNBytes(MAXIMUM_BYTES + 1);
                if (body.length > MAXIMUM_BYTES) return new Response(null, false);
                JsonNode parsed = JSON.readTree(body);
                boolean collection = path.contains("?per_page=100"); // Fixed inventory routes only, never caller URLs.
                return new Response(parsed, collection ? GitLabInventoryPage.complete(parsed, response.getHeaders()) : parsed != null && parsed.isObject());
            });
        } catch (RuntimeException unavailable) {
            // Never retain a source-bearing parser/HTTP error, response body or credential.
            return new Response(null, false);
        }
    }
    private static long number(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid();
        return value.longValue();
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank() || value.asString().length() > 512
                || value.asString().codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return value.asString();
    }
    private static boolean bool(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isBoolean()) throw invalid();
        return value.booleanValue();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid inspection response"); }
}
