package io.github.qhung13.asep.integration.internal.gitlab;

import io.github.qhung13.asep.integration.MergeRequestMetadata;
import io.github.qhung13.asep.integration.MergeRequestMetadata.DiffReferences;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitLabRestReadAdapterTest {

    private static final String BASE_URL = "https://gitlab.example.test/api/v4";
    private static final String FAKE_TOKEN = "FAKE_TEST_CREDENTIAL_DO_NOT_USE";
    private static final String RAW_BODY_SENTINEL = "SYNTHETIC_PRIVATE_API_RESPONSE";
    private static final String SOURCE_SENTINEL = "SYNTHETIC_PRIVATE_SOURCE_CONTENT";
    private static final String SENSITIVE_BODY = RAW_BODY_SENTINEL + " "
            + FAKE_TOKEN + " " + SOURCE_SENTINEL;
    private static final String BASE_SHA = "a".repeat(40);
    private static final String START_SHA = "b".repeat(40);
    private static final String HEAD_SHA = "c".repeat(40);
    private static final List<String> SHA_FIELDS =
            List.of("base_sha", "start_sha", "head_sha");
    private static final List<String> REQUIRED_FIELDS = List.of(
            "project_id", "target_project_id", "iid", "title", "state", "source_branch", "target_branch"
    );
    // Only used to build JSON fixtures. The adapter uses RestClient's own converters.
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private GitLabRestReadAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("PRIVATE-TOKEN", FAKE_TOKEN);
        // Binding replaces the request factory: no real GitLab/network calls occur.
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new GitLabRestReadAdapter(builder.build());
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void readsMergeRequestMetadataAndDiffReferences() {
        expectJson(metadata());

        MergeRequestMetadata actual = adapter.getMergeRequest(42L, 17L);

        assertEquals(new MergeRequestMetadata(
                42L, 17L, "Fix login bug", "opened", "feature/login", "main",
                Optional.of(new DiffReferences(BASE_SHA, START_SHA, HEAD_SHA)), OptionalLong.of(42)
        ), actual);
    }

    @ParameterizedTest
    @ValueSource(longs = {42, 91, 9_876_543_210L})
    void preservesSameProjectOrForkSourceWithoutChangingTargetOrRevision(long sourceProjectId) {
        expectJson(metadata().put("source_project_id", sourceProjectId).put("id", 99999));

        MergeRequestMetadata actual = adapter.getMergeRequest(42, 17);

        assertEquals(42, actual.projectId());
        assertEquals(17, actual.iid());
        assertEquals(OptionalLong.of(sourceProjectId), actual.sourceProjectId());
        assertEquals("feature/login", actual.sourceBranch());
        assertEquals("main", actual.targetBranch());
        assertEquals(Optional.of(new DiffReferences(BASE_SHA, START_SHA, HEAD_SHA)), actual.diffReferences());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesUnknownSourceProjectWithoutInferringFromTargetOrBranch(boolean explicitNull) {
        ObjectNode response = metadata();
        if (explicitNull) {
            response.putNull("source_project_id");
        } else {
            response.remove("source_project_id");
        }
        expectJson(response);

        MergeRequestMetadata actual = adapter.getMergeRequest(42, 17);

        assertEquals(OptionalLong.empty(), actual.sourceProjectId());
        assertEquals(42, actual.projectId());
        assertEquals("feature/login", actual.sourceBranch());
        assertEquals(Optional.of(new DiffReferences(BASE_SHA, START_SHA, HEAD_SHA)), actual.diffReferences());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonpositiveSourceProject(long sourceProjectId) {
        expectJson(metadata().put("source_project_id", sourceProjectId));

        assertReadFailure("GitLab MR response source project ID must be positive");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 91})
    void rejectsTargetProjectThatDoesNotMatchEndpoint(long targetProjectId) {
        expectJson(metadata().put("target_project_id", targetProjectId));

        assertReadFailure("GitLab MR response identifiers do not match request");
    }

    @Test
    void readsMergeRequestWhenDiffReferencesAreUnavailable() {
        ObjectNode response = metadata();
        response.putNull("diff_refs");
        expectJson(response);

        MergeRequestMetadata actual = adapter.getMergeRequest(42L, 17L);

        assertEquals(Optional.empty(), actual.diffReferences());
        assertEquals("Fix login bug", actual.title());
    }

    @Test
    void readsRequestedIdentifiersBeyondIntegerRange() {
        long projectId = 9_876_543_210L;
        long iid = 8_765_432_109L;
        ObjectNode response = metadata().put("project_id", projectId).put("iid", iid)
                .put("source_project_id", projectId).put("target_project_id", projectId);
        expectRead(projectId, iid, withSuccess(response.toString(), MediaType.APPLICATION_JSON));

        MergeRequestMetadata actual = adapter.getMergeRequest(projectId, iid);

        assertEquals(projectId, actual.projectId());
        assertEquals(iid, actual.iid());
    }

    @Test
    void ignoresAdditionalGitLabFieldsOutsideTheMetadataContract() {
        ObjectNode response = metadata()
                .put("id", 12345)
                .put("description", "Untrusted MR description")
                .put("web_url", "https://gitlab.example.test/team/project/-/merge_requests/17")
                .put("created_at", "2026-10-19T10:00:00Z")
                .put("draft", false)
                .put("merge_status", "can_be_merged");
        response.putObject("author").put("id", 7).put("username", "test-author");
        response.putArray("labels").add("bug");
        ((ObjectNode) response.get("diff_refs")).put("future_field", "ignored");
        expectJson(response);

        assertEquals(new MergeRequestMetadata(
                42L, 17L, "Fix login bug", "opened", "feature/login", "main",
                Optional.of(new DiffReferences(BASE_SHA, START_SHA, HEAD_SHA)), OptionalLong.of(42)
        ), adapter.getMergeRequest(42L, 17L));
    }

    @Test
    void readsMergeRequestWhenDiffReferencesPropertyIsOmitted() {
        ObjectNode response = metadata();
        response.remove("diff_refs");
        expectJson(response);

        assertEquals(Optional.empty(), adapter.getMergeRequest(42L, 17L).diffReferences());
    }

    // Characterization, not an approved business rule: deciding whether an empty
    // object/all-missing SHAs means unavailable or invalid still requires Owner review.
    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"base_sha\":null,\"start_sha\":null,\"head_sha\":null}",
            "{\"base_sha\":\"\",\"start_sha\":\"\",\"head_sha\":\"\"}",
            "{\"base_sha\":\" \",\"start_sha\":\" \",\"head_sha\":\" \"}"
    })
    void currentlyTreatsAllMissingShasAsUnavailable(String diffRefs) {
        ObjectNode response = metadata();
        response.set("diff_refs", JSON.readTree(diffRefs));
        expectJson(response);

        assertEquals(Optional.empty(), adapter.getMergeRequest(42L, 17L).diffReferences());
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"base_sha", "start_sha", "head_sha"})
    void rejectsOneMissingSha(String field) {
        ObjectNode response = metadata();
        ((ObjectNode) response.get("diff_refs")).remove(field);
        expectJson(response);

        assertReadFailure("GitLab MR diff references are incomplete");
    }

    @ParameterizedTest(name = "{0}: value [{1}]")
    @MethodSource("nullAndBlankShas")
    void rejectsNullOrBlankShaWhenOtherShasArePresent(String field, String value) {
        ObjectNode response = metadata();
        ((ObjectNode) response.get("diff_refs")).put(field, value);
        expectJson(response);

        assertReadFailure("GitLab MR diff references are incomplete");
    }

    static Stream<Arguments> nullAndBlankShas() {
        return SHA_FIELDS.stream().flatMap(field -> Stream.of(
                Arguments.of(field, null),
                Arguments.of(field, ""),
                Arguments.of(field, " \t\n")
        ));
    }

    @ParameterizedTest
    @CsvSource({"base_sha,start_sha", "base_sha,head_sha", "start_sha,head_sha"})
    void rejectsTwoMissingShas(String first, String second) {
        ObjectNode response = metadata();
        ((ObjectNode) response.get("diff_refs")).remove(List.of(first, second));
        expectJson(response);

        assertReadFailure("GitLab MR diff references are incomplete");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null"})
    void rejectsEmptyOrJsonNullResponse(String body) {
        expectRead(withSuccess(body, MediaType.APPLICATION_JSON));

        assertReadFailure("GitLab MR response is missing required fields");
    }

    @ParameterizedTest(name = "{0}, explicit null = {1}")
    @MethodSource("missingRequiredFields")
    void rejectsMissingOrNullRequiredMetadata(String field, boolean explicitNull) {
        ObjectNode response = metadata();
        if (explicitNull) {
            response.putNull(field);
        } else {
            response.remove(field);
        }
        expectJson(response);

        assertReadFailure("GitLab MR response is missing required fields");
    }

    static Stream<Arguments> missingRequiredFields() {
        return REQUIRED_FIELDS.stream().flatMap(field -> Stream.of(
                Arguments.of(field, false), Arguments.of(field, true)
        ));
    }

    @ParameterizedTest
    @CsvSource({"43,17", "42,18", "0,17", "42,0", "-1,17", "42,-1"})
    void rejectsResponseIdentifiersThatDoNotMatchRequest(long projectId, long iid) {
        expectJson(metadata().put("project_id", projectId).put("iid", iid));

        assertReadFailure("GitLab MR response identifiers do not match request");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"project_id\":", "[1,2]", "\"unexpected scalar\"", " \t\n"})
    void sanitizesMalformedJsonOrInvalidRootShape(String body) {
        expectRead(withSuccess(body, MediaType.APPLICATION_JSON));

        assertReadFailure("GitLab MR read failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"project_id", "target_project_id", "source_project_id", "iid", "title", "state", "source_branch", "target_branch"})
    void rejectsObjectWhereScalarMetadataIsExpected(String field) {
        ObjectNode response = metadata();
        response.putObject(field).put("private_data", SENSITIVE_BODY);
        expectJson(response);

        assertReadFailure("GitLab MR read failed");
    }

    @ParameterizedTest
    @CsvSource({"project_id,not-a-number", "iid,not-a-number",
            "project_id,9223372036854775808", "iid,9223372036854775808"})
    void rejectsNonNumericOrOverflowingIdentifiers(String field, String value) {
        expectJson(metadata().put(field, value));

        assertReadFailure("GitLab MR read failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "\"not-an-object\"", "42"})
    void rejectsInvalidDiffReferencesJsonType(String value) {
        ObjectNode response = metadata();
        response.set("diff_refs", JSON.readTree(value));
        expectJson(response);

        assertReadFailure("GitLab MR read failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"base_sha", "start_sha", "head_sha"})
    void rejectsObjectWhereShaStringIsExpected(String field) {
        ObjectNode response = metadata();
        ((ObjectNode) response.get("diff_refs")).putObject(field).put("private_data", SENSITIVE_BODY);
        expectJson(response);

        assertReadFailure("GitLab MR read failed");
    }

    // Characterization only: required strings are currently checked for null,
    // not blanks. These assertions do not establish an approved validation policy.
    @ParameterizedTest(name = "{0}: blank value [{1}]")
    @MethodSource("blankRequiredStrings")
    void currentlyPreservesBlankRequiredStrings(String field, String value) {
        expectJson(metadata().put(field, value));

        MergeRequestMetadata actual = adapter.getMergeRequest(42L, 17L);

        assertEquals(field.equals("title") ? value : "Fix login bug", actual.title());
        assertEquals(field.equals("state") ? value : "opened", actual.state());
        assertEquals(field.equals("source_branch") ? value : "feature/login", actual.sourceBranch());
        assertEquals(field.equals("target_branch") ? value : "main", actual.targetBranch());
    }

    static Stream<Arguments> blankRequiredStrings() {
        return Stream.of("title", "state", "source_branch", "target_branch")
                .flatMap(field -> Stream.of(
                        Arguments.of(field, ""),
                        Arguments.of(field, " \t\n")
                ));
    }

    // Characterization only: nonblank SHAs have no format validation today.
    @ParameterizedTest
    @MethodSource("malformedShaFormats")
    void currentlyPreservesNonblankMalformedShaFormats(String value) {
        ObjectNode response = metadata();
        ((ObjectNode) response.get("diff_refs")).put("head_sha", value);
        expectJson(response);

        assertEquals(new DiffReferences(BASE_SHA, START_SHA, value),
                adapter.getMergeRequest(42L, 17L).diffReferences().orElseThrow());
    }

    static Stream<String> malformedShaFormats() {
        return Stream.of("short", "g".repeat(40), " " + HEAD_SHA + " ");
    }

    @ParameterizedTest
    @CsvSource({"0,17", "-1,17", "42,0", "42,-1"})
    void rejectsInvalidArgumentsBeforeAnyHttpRequest(long projectId, long iid) {
        // No expectation: any attempted HTTP request fails the test immediately.
        IllegalArgumentException failure = assertThrowsExactly(IllegalArgumentException.class,
                () -> adapter.getMergeRequest(projectId, iid));

        assertEquals("Project ID and MR IID must be positive", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 202, 206, 302})
    void rejectsNon200StatusesEvenWithValidMergeRequestJson(int status) {
        ObjectNode response = metadata().put("title", SENSITIVE_BODY);
        expectRead(withStatus(HttpStatus.valueOf(status))
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.toString()));

        assertReadFailure("GitLab MR read returned HTTP " + status);
    }

    @Test
    void rejectsNon200StatusBeforeDeserializingResponseBody() {
        expectRead(withStatus(HttpStatus.PARTIAL_CONTENT)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{" + SENSITIVE_BODY));

        assertReadFailure("GitLab MR read returned HTTP 206");
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500, 502, 503})
    void sanitizesHttpErrorsWithoutRetryingOrWriting(int status) {
        expectRead(withStatus(HttpStatus.valueOf(status))
                .header(HttpHeaders.RETRY_AFTER, "120")
                .contentType(MediaType.TEXT_PLAIN)
                .body(SENSITIVE_BODY));

        assertReadFailure("GitLab MR read returned HTTP " + status);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transportFailures")
    void sanitizesSimulatedTransportFailuresWithoutRetrying(String scenario, IOException failure) {
        // Deterministic fault injection, not a test of actual client timeout settings.
        expectRead(withException(failure));

        assertReadFailure("GitLab MR read failed");
    }

    static Stream<Arguments> transportFailures() {
        return Stream.of(
                Arguments.of("connection failure", new ConnectException(SENSITIVE_BODY)),
                Arguments.of("socket timeout", new SocketTimeoutException(SENSITIVE_BODY))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/html", "application/octet-stream"})
    void sanitizesUnsupportedContentTypes(String contentType) {
        // Keep the body valid so the failure isolates media-type handling.
        ObjectNode response = metadata().put("title", SENSITIVE_BODY);
        expectRead(withSuccess(response.toString(), MediaType.parseMediaType(contentType)));

        assertReadFailure("GitLab MR read failed");
    }

    @Test
    void sanitizesDeserializationFailureContainingSensitiveInput() {
        // GitLab/proxies can return HTML or private diagnostics mislabeled as JSON.
        expectRead(withSuccess("<html>" + SENSITIVE_BODY + "</html>", MediaType.APPLICATION_JSON));

        assertReadFailure("GitLab MR read failed");
    }

    private static ObjectNode metadata() {
        ObjectNode response = JSON.createObjectNode()
                .put("project_id", 42L)
                .put("source_project_id", 42L)
                .put("target_project_id", 42L)
                .put("iid", 17L)
                .put("title", "Fix login bug")
                .put("state", "opened")
                .put("source_branch", "feature/login")
                .put("target_branch", "main");
        response.putObject("diff_refs")
                .put("base_sha", BASE_SHA)
                .put("start_sha", START_SHA)
                .put("head_sha", HEAD_SHA);
        return response;
    }

    private void expectJson(ObjectNode response) {
        expectRead(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
    }

    private void expectRead(ResponseCreator response) {
        expectRead(42L, 17L, response);
    }

    private void expectRead(long projectId, long iid, ResponseCreator response) {
        server.expect(ExpectedCount.once(), requestTo(BASE_URL + "/projects/" + projectId
                        + "/merge_requests/" + iid))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("PRIVATE-TOKEN", FAKE_TOKEN))
                .andRespond(response);
    }

    private void assertReadFailure(String message) {
        IllegalStateException failure = assertThrowsExactly(IllegalStateException.class,
                () -> adapter.getMergeRequest(42L, 17L));
        assertEquals(message, failure.getMessage());

        // Includes causes and suppressed exceptions, not only the top-level message.
        StringWriter stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));
        for (String sentinel : List.of(FAKE_TOKEN, RAW_BODY_SENTINEL, SOURCE_SENTINEL)) {
            assertFalse(stackTrace.toString().contains(sentinel),
                    "Exception must not expose synthetic credentials or private response content");
        }
    }
}
