package io.github.qhung13.asep.integration.internal.gitlab;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class VnT14ReadOnlyPreflightTest {
    private static final String BASE = "https://gitlab.invalid/api/v4";
    private static final String PREFIX = "/projects/87786140";
    private static final String SECRET = "SYNTHETIC_PRIVATE_RESPONSE_MUST_NOT_BE_REPORTED";
    private static final String SHA = "a".repeat(40);
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final List<String> COLLECTIONS = List.of("protected_branches", "runners", "variables", "environments", "protected_environments", "deploy_keys", "hooks", "pipelines");
    @TempDir Path outputDirectory;
    private static String path(String collection) { return PREFIX + "/" + collection + "?per_page=100" + (collection.equals("pipelines") ? "&sha=" + SHA : ""); }
    private static ResponseCreator json(String body) {
        var response = withSuccess(body, MediaType.APPLICATION_JSON);
        try {
            var parsed = JsonMapper.builder().build().readTree(body);
            if (parsed.isArray()) response.header("X-Page", "1").header("X-Per-Page", "100").header("X-Next-Page", "")
                    .header("X-Total", Integer.toString(parsed.size())).header("X-Total-Pages", "1");
        } catch (RuntimeException deliberatelyMalformed) { /* Negative response fixture. */ }
        return response;
    }
    private Map<String, Object> inspect(Map<String, ResponseCreator> overrides, Clock clock) {
        var builder = RestClient.builder().baseUrl(BASE);
        var server = MockRestServiceServer.bindTo(builder).build();
        var paths = List.of("/user", "/personal_access_tokens/self", PREFIX, PREFIX + "/repository/branches/main",
                path("protected_branches"), path("runners"), path("variables"), path("environments"), path("protected_environments"),
                path("deploy_keys"), path("hooks"), path("pipelines"), PREFIX + "/repository/files/.gitlab-ci.yml?ref=" + SHA);
        for (String path : paths) {
            String body = "[{\"value\":\"" + SECRET + "\",\"url\":\"" + SECRET + "\"}]";
            if (path.equals("/user")) body = "{\"bot\":true,\"private_email\":\"" + SECRET + "\"}";
            else if (path.equals("/personal_access_tokens/self")) body = "{\"active\":true,\"revoked\":false,\"expires_at\":\"2026-11-04\",\"scopes\":[\"api\",\"write_repository\",\"" + SECRET + "\"]}";
            else if (path.equals(PREFIX)) body = "{\"id\":87786140,\"path_with_namespace\":\"qhung-13/asep-m01-sandbox\",\"permissions\":{\"project_access\":{\"access_level\":30}},\"archived\":false,\"shared_runners_enabled\":true,\"auto_devops_enabled\":false}";
            else if (path.endsWith("/branches/main")) body = "{\"protected\":true,\"can_push\":false,\"commit\":{\"id\":\"" + SHA + "\"}}";
            else if (path.contains("protected_branches")) body = "[{\"name\":\"main\",\"push_access_levels\":[{\"access_level\":40,\"description\":\"" + SECRET + "\"}],\"merge_access_levels\":[],\"allow_force_push\":false}]";
            else if (path.contains("pipelines")) body = "[{\"id\":7,\"sha\":\"" + SHA + "\",\"status\":\"pending\"}]";
            else if (path.contains("/repository/files/")) body = "{}";
            server.expect(requestTo(BASE + path)).andExpect(method(HttpMethod.GET)).andRespond(overrides.getOrDefault(path, json(body)));
        }
        var result = VnT14ReadOnlyPreflight.inspect(builder.build(), clock);
        server.verify();
        return result;
    }
    private Map<String, Object> inspect(Map<String, ResponseCreator> overrides) { return inspect(overrides, Clock.fixed(NOW, ZoneOffset.UTC)); }
    @ParameterizedTest @ValueSource(ints = {200, 403, 302})
    void inspectionReportsGapsAndSanitizesEvenSuccessAndErrorBodies(int restrictedStatus) {
        ResponseCreator restricted = restrictedStatus == 200 ? json("[{\"value\":\"" + SECRET + "\"}]")
                : withStatus(HttpStatusCode.valueOf(restrictedStatus)).contentType(MediaType.APPLICATION_JSON)
                        .header("Location", "https://untrusted.invalid/" + SECRET).body(SECRET);
        var report = inspect(Map.of(path("variables"), restricted, path("runners"), restricted));
        assertEquals("BLOCKED", report.get("live_write_gate")); assertEquals(0, report.get("mutations_performed"));
        assertEquals(30, report.get("effective_access_level")); assertEquals(false, report.get("credential_can_push_target"));
        assertEquals(SHA, report.get("target_head"));
        var node = JsonMapper.builder().build().valueToTree(report);
        assertEquals(restrictedStatus, node.path("requests").path("project_variables").path("http_status").asInt());
        assertEquals(restrictedStatus == 200, node.path("requests").path("project_variables").path("complete").booleanValue());
        if (restrictedStatus != 200) assertTrue(node.path("requests").path("project_variables").path("count").isNull());
        assertFalse(node.toString().contains(SECRET)); assertFalse(node.toString().contains("untrusted.invalid"));
        assertEquals("pending", node.path("observed_pipelines").get(0).path("status").asString());
        assertFalse(node.toString().contains("CI_PASS"));
    }
    static Stream<Arguments> unexpectedShapes() {
        return COLLECTIONS.stream().flatMap(route -> Stream.of("{}", "null", "42", "\"text\"", "[null]", "[42]", "[[]]").map(body -> Arguments.of(route, body)));
    }
    @ParameterizedTest @MethodSource("unexpectedShapes")
    void everyCollectionEndpointRejectsWrongShapeEvenOnHttp200(String route, String body) {
        var report = inspect(Map.of(path(route), json(body)));
        String label = route.equals("variables") ? "project_variables" : route.equals("pipelines") ? "target_pipelines" : route;
        var observation = JsonMapper.builder().build().valueToTree(report).path("requests").path(label);
        assertEquals(200, observation.path("http_status").asInt());
        assertFalse(observation.path("complete").booleanValue()); assertTrue(observation.path("count").isNull());
        assertEquals("BLOCKED", report.get("live_write_gate"));
    }
    @ParameterizedTest @ValueSource(strings = {"link-no-next", "link-empty-next", "link-multi-rel", "missing-all", "X-Page", "X-Per-Page", "X-Next-Page", "X-Total", "X-Total-Pages", "partial-page", "wrong-page", "full-page", "duplicate-header"})
    void unknownPaginationRemainsIncompleteAndNoRemoteLinkIsFollowed(String fault) {
        String body = fault.equals("full-page") ? "[" + String.join(",", java.util.Collections.nCopies(100, "{}")) + "]" : "[]";
        var report = inspect(Map.of(path("variables"), request -> {
            var response = json(body).createResponse(request); var headers = response.getHeaders();
            if (fault.startsWith("link-")) headers.add("Link", "<https://untrusted.invalid/page>; rel=\"" + (fault.equals("link-multi-rel") ? "prev next" : "next") + "\"");
            if (fault.equals("link-no-next")) headers.remove("X-Next-Page");
            if (fault.startsWith("X-")) headers.remove(fault);
            if (fault.equals("missing-all")) for (String name : List.of("X-Page", "X-Per-Page", "X-Next-Page", "X-Total", "X-Total-Pages")) headers.remove(name);
            if (fault.equals("partial-page")) { headers.set("X-Total", "3"); headers.set("X-Total-Pages", "2"); }
            if (fault.equals("wrong-page")) headers.set("X-Page", "2");
            if (fault.equals("duplicate-header")) headers.add("X-Total", "1");
            return response;
        }));
        var observation = JsonMapper.builder().build().valueToTree(report).path("requests").path("project_variables");
        assertEquals(200, observation.path("http_status").asInt()); assertFalse(observation.path("complete").booleanValue());
        assertFalse(report.toString().contains("untrusted.invalid"));
    }
    @ParameterizedTest @ValueSource(strings = {"{", "[{\"id\":1,\"id\":2}]", "[] []"})
    void malformedCollectionJsonKeepsActualStatusAndNeverExportsRawContent(String body) {
        var report = inspect(Map.of(path("variables"), json(body)));
        var observation = JsonMapper.builder().build().valueToTree(report).path("requests").path("project_variables");
        assertEquals(200, observation.path("http_status").asInt());
        assertFalse(observation.path("complete").booleanValue()); assertTrue(observation.path("count").isNull());
    }
    @Test void stagedInspectionRecordsOldBeginningAndRecentCompletion() {
        var time = new java.util.concurrent.atomic.AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return time.get(); }
        };
        var report = inspect(Map.of(path("runners"), request -> {
            var response = json("[]").createResponse(request); time.set(NOW.plusSeconds(45)); return response;
        }), clock);
        assertEquals(NOW.toString(), report.get("inspection_started_at"));
        assertEquals(NOW.plusSeconds(45).toString(), report.get("inspection_completed_at"));
        assertFalse(report.containsKey("observed_at"));
    }
    @Test void reportIsExclusivelyCreatedWithPrivatePermissionsAndCompleteUtf8Content() throws Exception {
        Path file = outputDirectory.resolve("report.json");
        VnT14ReadOnlyPreflight.writeReport(file, Map.of("live_write_gate", "BLOCKED", "mutations_performed", 0));
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        assertEquals("BLOCKED", JsonMapper.builder().build().readTree(Files.readString(file)).path("live_write_gate").asString());
        assertTrue(Files.readString(file).endsWith("\n"));
        String before = Files.readString(file);
        assertThrows(FileAlreadyExistsException.class, () -> VnT14ReadOnlyPreflight.writeReport(file, Map.of("overwrite", true)));
        assertEquals(before, Files.readString(file));
    }
    @ParameterizedTest @ValueSource(strings = {"symlink", "dangling-symlink", "hardlink", "directory"})
    void existingOutputTargetsCannotBeFollowedOrOverwritten(String kind) throws Exception {
        Path victim = outputDirectory.resolve("victim"); Files.writeString(victim, "UNCHANGED");
        Path output = outputDirectory.resolve("report.json");
        switch (kind) {
            case "symlink" -> Files.createSymbolicLink(output, victim);
            case "dangling-symlink" -> Files.createSymbolicLink(output, outputDirectory.resolve("absent"));
            case "hardlink" -> Files.createLink(output, victim);
            case "directory" -> Files.createDirectory(output);
        }
        assertThrows(java.io.IOException.class, () -> VnT14ReadOnlyPreflight.writeReport(output, Map.of("unsafe", true)));
        assertEquals("UNCHANGED", Files.readString(victim)); assertFalse(Files.exists(outputDirectory.resolve("absent")));
    }
    @Test void concurrentReportCreationHasOneWinnerAndNeverReopensTheLosersPath() throws Exception {
        var start = new CountDownLatch(1); var executor = Executors.newFixedThreadPool(2);
        Path output = outputDirectory.resolve("report.json");
        try {
            var tasks = Stream.of(1, 2).map(id -> executor.submit(() -> {
                start.await();
                try { VnT14ReadOnlyPreflight.writeReport(output, Map.of("winner", id)); return true; }
                catch (FileAlreadyExistsException exists) { return false; }
            })).toList();
            start.countDown();
            int wins = 0; for (var task : tasks) if (task.get(5, TimeUnit.SECONDS)) wins++;
            assertEquals(1, wins);
            int winner = JsonMapper.builder().build().readTree(Files.readString(output)).path("winner").asInt();
            assertTrue(winner == 1 || winner == 2);
        } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
