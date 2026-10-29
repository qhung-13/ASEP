package io.github.qhung13.asep.integration.internal.gitlab;

import io.github.qhung13.asep.integration.GitLabTestProjectReader;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static io.github.qhung13.asep.integration.GitLabTestProjectReader.Gap.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GitLabRestTestProjectReadAdapterTest {
    private static final String BASE = "https://gitlab.test.invalid/api/v4";
    private static final String TOKEN = "SYNTHETIC_READ_CREDENTIAL";
    private static final String PRIVATE = "SYNTHETIC_PRIVATE_VALUE_DO_NOT_RETURN";
    private static final String SHA = "1".repeat(40);
    private static final String PROJECT_JSON = """
            {"id":42,"path_with_namespace":"synthetic/test","visibility":"private","archived":false,
            "default_branch":"main","builds_access_level":"disabled","shared_runners_enabled":false,
            "permissions":{"project_access":{"access_level":40},"group_access":null}}
            """;
    private static final String BRANCH = "{\"name\":\"main\",\"commit\":{\"id\":\"" + SHA + "\"},\"protected\":true,\"can_push\":false}";
    private MockRestServiceServer server;
    private GitLabRestTestProjectReadAdapter adapter;
    private RestClient client;

    @BeforeEach void setUp() {
        var builder = RestClient.builder().baseUrl(BASE).defaultHeader("PRIVATE-TOKEN", TOKEN);
        // Entire transport replaced by local fixtures; no DNS or GitLab connection.
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        adapter = new GitLabRestTestProjectReadAdapter(client, Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC));
    }
    @AfterEach void verifyRequests() { server.verify(); }
    private void expect(String path, ResponseCreator response) {
        server.expect(requestTo(BASE + path)).andExpect(method(HttpMethod.GET))
                .andExpect(header("PRIVATE-TOKEN", TOKEN)).andRespond(response);
    }
    private static ResponseCreator json(String body) {
        var response = withSuccess(body, MediaType.APPLICATION_JSON);
        try {
            var parsed = JsonMapper.builder().build().readTree(body);
            if (parsed.isArray()) response.header("X-Page", "1").header("X-Per-Page", "100").header("X-Next-Page", "")
                    .header("X-Total", Integer.toString(parsed.size())).header("X-Total-Pages", "1");
        } catch (RuntimeException invalidFixture) { /* Deliberately malformed response test. */ }
        return response;
    }
    private GitLabTestProjectReader.Inspection inspect(Map<String, ResponseCreator> overrides) {
        String prefix = "/projects/42";
        var paths = new String[]{prefix, prefix + "/repository/branches/main", prefix + "/protected_branches?per_page=100",
                prefix + "/runners?per_page=100", prefix + "/variables?per_page=100", prefix + "/environments?per_page=100",
                prefix + "/pipelines?per_page=100&sha=" + SHA};
        for (int n = 0; n < paths.length; n++) {
            // A malformed branch means the adapter deliberately cannot scope a pipeline request.
            if (n == 6 && overrides.containsKey("branch-failed")) continue;
            String body = n == 0 ? PROJECT_JSON : n == 1 ? BRANCH : "[]";
            expect(paths[n], overrides.getOrDefault(paths[n], json(body)));
        }
        return adapter.inspect(42, "main");
    }

    @Test void collectsScopedReadOnlyMetadataWithoutInferringSafetyTierOrCiSuccess() {
        var result = inspect(Map.of("/projects/42/variables?per_page=100", json("[{\"key\":\"PRIVATE\",\"value\":\"" + PRIVATE + "\",\"protected\":true}]"),
                "/projects/42/pipelines?per_page=100&sha=" + SHA,
                json("[{\"id\":9,\"sha\":\"" + SHA + "\",\"status\":\"pending\",\"source\":\"push\"}]")));
        assertTrue(result.gaps().isEmpty());
        assertEquals(42, result.project().id());
        assertEquals(40, result.project().effectiveAccessLevel());
        assertEquals(SHA, result.target().headSha());
        assertTrue(result.target().protectedBranch()); assertFalse(result.target().canPush());
        assertEquals(1, result.variables().count());
        assertEquals("pending", result.observedPipelines().getFirst().status());
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), result.startedAt());
        String projection = JsonMapper.builder().build().writeValueAsString(result);
        assertFalse(projection.contains(PRIVATE)); assertFalse(projection.contains(TOKEN));
        assertFalse(result.toString().contains(PRIVATE));
    }
    @Test void encodesAnAllowedNestedTargetAsOneBranchSelection() {
        expect("/projects/42", json(PROJECT_JSON));
        expect("/projects/42/repository/branches/release%2Ftest", json(BRANCH.replace("main", "release/test")));
        for (String route : new String[]{"protected_branches", "runners", "variables", "environments"}) {
            expect("/projects/42/" + route + "?per_page=100", json("[]"));
        }
        expect("/projects/42/pipelines?per_page=100&sha=" + SHA, json("[]"));
        var result = adapter.inspect(42, "release/test");
        assertTrue(result.gaps().isEmpty()); assertEquals("release/test", result.target().name());
    }
    @ParameterizedTest @ValueSource(ints = {301, 302, 307, 401, 403, 404, 429, 500})
    void unavailablePermissionOrRedirectIsUnknownNotEmptyEvidence(int status) {
        var result = inspect(Map.of("/projects/42/variables?per_page=100",
                withStatus(HttpStatusCode.valueOf(status)).header("Location", "https://untrusted.invalid/").body(PRIVATE)));
        assertTrue(result.gaps().contains(VARIABLES));
        assertFalse(result.variables().complete());
        assertFalse(result.toString().contains(PRIVATE));
    }
    @ParameterizedTest @ValueSource(strings = {"{", "null", "[]", "{\"id\":42,\"id\":42}", "{\"id\":99}"})
    void malformedOrMismatchedProjectCannotBeEnrollmentEvidence(String body) {
        var result = inspect(Map.of("/projects/42", json(body)));
        assertTrue(result.gaps().contains(PROJECT)); assertNull(result.project());
    }
    @Test void paginationIsIncompleteAndNeverFollowsRemoteLinks() {
        var result = inspect(Map.of("/projects/42/runners?per_page=100",
                withSuccess("[]", MediaType.APPLICATION_JSON).header("X-Next-Page", "2")
                        .header("Link", "<https://untrusted.invalid/page>; rel=next")));
        assertTrue(result.gaps().contains(RUNNERS)); assertFalse(result.runners().complete());
    }
    @Test void oversizedResponseIsUnavailableAndNeverExposesRawContent() {
        var result = inspect(Map.of("/projects/42/variables?per_page=100", json(PRIVATE.repeat(4096))));
        assertTrue(result.gaps().contains(VARIABLES)); assertFalse(result.variables().complete());
    }
    @Test void wrongPipelineShaCannotBePresentedAsCandidateCi() {
        var result = inspect(Map.of("/projects/42/pipelines?per_page=100&sha=" + SHA,
                json("[{\"id\":9,\"sha\":\"" + "2".repeat(40) + "\",\"status\":\"success\",\"source\":\"push\"}]")));
        assertTrue(result.gaps().contains(PIPELINES)); assertTrue(result.observedPipelines().isEmpty());
        assertFalse(result.pipelines().complete());
    }
    @Test void targetFailureDoesNotFabricateFreshnessOrIssueUnscopedPipelineRead() {
        var result = inspect(Map.of("/projects/42/repository/branches/main", withResourceNotFound(), "branch-failed", json("[]")));
        assertTrue(result.gaps().contains(TARGET)); assertTrue(result.gaps().contains(PIPELINES)); assertNull(result.target());
    }
    @Test void unknownPermissionsRemainUnknown() {
        var result = inspect(Map.of("/projects/42", json(PROJECT_JSON.replace("\"access_level\":40", "\"access_level\":null"))));
        assertTrue(result.gaps().contains(PROJECT));
    }
    @ParameterizedTest @ValueSource(strings = {"https://evil.invalid", "../main", "main?override=1", "//host/main", "main#fragment", "main.lock", "main\r\nHost: evil"})
    void rejectsUnsafeSelectionBeforeAnyRequest(String branch) {
        assertThrows(IllegalArgumentException.class, () -> adapter.inspect(42, branch));
    }
    @ParameterizedTest @ValueSource(strings = {"link-no-next", "link-empty-next", "link-multi-rel", "missing-all", "X-Page", "X-Per-Page", "X-Next-Page", "X-Total", "X-Total-Pages", "partial-page", "wrong-page", "duplicate-header", "full-page"})
    void incompleteInventoryNeverBecomesCompleteOrFollowsALink(String fault) {
        String body = fault.equals("full-page") ? "[" + String.join(",", java.util.Collections.nCopies(100, "{}")) + "]" : "[]";
        ResponseCreator page = request -> {
            var response = json(body).createResponse(request);
            var headers = response.getHeaders();
            if (fault.startsWith("link-")) headers.add("Link", "<https://untrusted.invalid/page>; rel=\"" + (fault.equals("link-multi-rel") ? "prev next" : "next") + "\"");
            if (fault.equals("link-no-next")) headers.remove("X-Next-Page");
            if (fault.startsWith("X-")) headers.remove(fault);
            if (fault.equals("missing-all")) for (String name : java.util.List.of("X-Page", "X-Per-Page", "X-Next-Page", "X-Total", "X-Total-Pages")) headers.remove(name);
            if (fault.equals("partial-page")) { headers.set("X-Total", "3"); headers.set("X-Total-Pages", "2"); }
            if (fault.equals("wrong-page")) headers.set("X-Page", "2");
            if (fault.equals("duplicate-header")) headers.add("X-Total", "1");
            return response;
        };
        var result = inspect(Map.of("/projects/42/runners?per_page=100", page));
        assertTrue(result.gaps().contains(RUNNERS)); assertFalse(result.runners().complete());
    }
    @Test void nonNextLinksAlsoRemainIncompleteWithoutInterpretingArbitraryUrls() {
        var result = inspect(Map.of("/projects/42/runners?per_page=100", request -> {
            var response = json("[]").createResponse(request);
            response.getHeaders().add("Link", "<https://untrusted.invalid/first>; rel=\"first\", <https://untrusted.invalid/last>; rel=\"last\"");
            return response;
        }));
        assertFalse(result.runners().complete()); // No linked URL was requested; server.verify covers all requests.
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "42", "\"text\"", "[null]", "[42]", "[[]]"})
    void wrongCollectionShapeCannotEstablishInventory(String body) {
        var result = inspect(Map.of("/projects/42/variables?per_page=100", json(body)));
        assertTrue(result.gaps().contains(VARIABLES)); assertFalse(result.variables().complete());
    }
    @Test void beginningAndEndSurviveAStagedSlowInspection() {
        var start = Instant.parse("2026-10-28T00:00:00Z");
        var time = new java.util.concurrent.atomic.AtomicReference<>(start);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        adapter = new GitLabRestTestProjectReadAdapter(client, clock);
        var result = inspect(Map.of("/projects/42", request -> {
            var response = json(PROJECT_JSON).createResponse(request);
            time.set(start.plusSeconds(45)); // Later requests complete recently; metadata was obtained at the old time.
            return response;
        }));
        assertEquals(start, result.startedAt()); assertEquals(start.plusSeconds(45), result.completedAt());
        assertTrue(result.gaps().isEmpty());
    }

}
