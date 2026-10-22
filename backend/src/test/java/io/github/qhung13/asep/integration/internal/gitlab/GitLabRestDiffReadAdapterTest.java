package io.github.qhung13.asep.integration.internal.gitlab;

import io.github.qhung13.asep.integration.ChangedFileDiff;
import io.github.qhung13.asep.integration.DiffVersionSummary;
import io.github.qhung13.asep.integration.MergeRequestDiffVersion;
import io.github.qhung13.asep.integration.MergeRequestMetadata.DiffReferences;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
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
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitLabRestDiffReadAdapterTest {

    private static final String BASE_URL = "https://gitlab.example.test/api/v4";
    private static final String VERSIONS = "/projects/42/merge_requests/17/versions";
    private static final String FAKE_TOKEN = "FAKE_DIFF_TEST_CREDENTIAL_DO_NOT_USE";
    private static final String PRIVATE_SOURCE = "SYNTHETIC_PRIVATE_DIFF_SOURCE";
    private static final String PRIVATE_PATH = "SYNTHETIC_PRIVATE_REPOSITORY_PATH";
    private static final String SENSITIVE = FAKE_TOKEN + " " + PRIVATE_SOURCE + " " + PRIVATE_PATH;
    private static final String BASE_SHA = "a".repeat(40);
    private static final String START_SHA = "b".repeat(40);
    private static final String HEAD_SHA = "c".repeat(40);
    private static final DiffReferences REVISION = new DiffReferences(BASE_SHA, START_SHA, HEAD_SHA);
    private static final String INVALID_RESPONSE = "GitLab diff response has missing or invalid required fields";
    private static final String INVALID_PAGINATION = "GitLab diff version pagination is invalid";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private MockRestServiceServer server;
    private GitLabRestDiffReadAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL)
                .defaultHeader("PRIVATE-TOKEN", FAKE_TOKEN);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new GitLabRestDiffReadAdapter(builder.build());
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @Test
    void mapsVersionListWithoutSortingOrChoosingAVersion() {
        ObjectNode first = summary(93).put("merge_request_id", 9999)
                .put("created_at", "2026-10-20T00:00:00Z").put("future_field", "ignored");
        ObjectNode second = summary(101).put("state", "future_state");
        expectList(1, success(JSON.createArrayNode().add(first).add(second).toString()));

        List<DiffVersionSummary> actual = adapter.listDiffVersions(42, 17);

        assertEquals(List.of(new DiffVersionSummary(93, REVISION, "collected"),
                new DiffVersionSummary(101, REVISION, "future_state")), actual);
        assertThrows(UnsupportedOperationException.class, () -> actual.clear());
    }

    @Test
    void mapsSelectedVersionWithEndpointProvenanceAndUnmodifiedFileEvidence() {
        ObjectNode response = detail().put("merge_request_id", 9999).put("real_size", "1000+");
        response.putArray("commits").addObject().put("message", PRIVATE_SOURCE);
        ObjectNode file = (ObjectNode) response.withArray("diffs").get(0);
        file.put("old_path", " ./old/../" + PRIVATE_PATH + " ")
                .put("new_path", " ../new/" + PRIVATE_PATH + " ")
                .put("diff", "@@ -1 +1 @@\n-old\n+" + PRIVATE_SOURCE + "\r\n")
                .put("renamed_file", true).put("generated_file", true).put("future_flag", "ignored");
        expectDetail(success(response.toString()));

        MergeRequestDiffVersion actual = adapter.getDiffVersion(42, 17, 93);

        assertEquals(new MergeRequestDiffVersion(42, 17, 93, REVISION, "collected", Optional.of("1000+"),
                List.of(new ChangedFileDiff(" ./old/../" + PRIVATE_PATH + " ", " ../new/" + PRIVATE_PATH + " ",
                        Optional.of("@@ -1 +1 @@\n-old\n+" + PRIVATE_SOURCE + "\r\n"),
                        false, false, true, false, false, Optional.of(true)))), actual);
        assertFalse(actual.toString().contains(PRIVATE_SOURCE));
        assertFalse(actual.toString().contains(PRIVATE_PATH));
    }

    @Test
    void preservesLongIdentifiersInBothEndpointsAndReturnedProvenance() {
        long projectId = 9_876_543_210L;
        long iid = 8_765_432_109L;
        long versionId = 7_654_321_098L;
        String versions = "/projects/" + projectId + "/merge_requests/" + iid + "/versions";
        ObjectNode response = detail().put("id", versionId);
        expect(versions + "?per_page=100&page=1", success(list(summary(versionId))));
        expect(versions + "/" + versionId, success(response.toString()));

        assertEquals(versionId, adapter.listDiffVersions(projectId, iid).getFirst().versionId());
        MergeRequestDiffVersion actual = adapter.getDiffVersion(projectId, iid, versionId);
        assertEquals(projectId, actual.projectId());
        assertEquals(iid, actual.mergeRequestIid());
        assertEquals(versionId, actual.versionId());
    }

    @Test
    void preservesExplicitNewAndDeletedFlags() {
        ObjectNode response = detail();
        response.withArray("diffs").removeAll()
                .add(file().put("new_file", true))
                .add(file().put("deleted_file", true));
        expectDetail(success(response.toString()));

        List<ChangedFileDiff> files = adapter.getDiffVersion(42, 17, 93).files();

        assertTrue(files.get(0).newFile());
        assertFalse(files.get(0).deletedFile());
        assertFalse(files.get(1).newFile());
        assertTrue(files.get(1).deletedFile());
    }

    @Test
    void readsAllXNextPagePagesBeforeReturning() {
        expectList(1, success(list(summary(93))).header("X-Page", "1")
                .header("X-Per-Page", "100").header("X-Total-Pages", "2").header("X-Next-Page", "2"));
        expectList(2, success(list(summary(91))).header("X-Page", "2")
                .header("X-Total-Pages", "2").header("X-Next-Page", ""));

        assertEquals(List.of(93L, 91L), adapter.listDiffVersions(42, 17).stream()
                .map(DiffVersionSummary::versionId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"X-Total-Pages", "Link"})
    void rejectsMissingContinuationBeforePreviouslyKnownFinalPage(String evidence) {
        expectList(1, success(list(summary(93))).header("X-Next-Page", "2")
                .header(evidence, evidence.equals("Link") ? link(2, "next") + ", " + link(3, "last") : "3"));
        expectList(2, success(list(summary(91))));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @CsvSource({"X-Total-Pages,X-Total-Pages", "X-Total-Pages,Link",
            "Link,X-Total-Pages", "Link,Link"})
    void rejectsContradictoryFinalPageEvidenceAcrossResponses(String firstEvidence, String secondEvidence) {
        expectList(1, success(list(summary(93))).header("X-Next-Page", "2")
                .header(firstEvidence, firstEvidence.equals("Link")
                        ? link(2, "next") + ", " + link(3, "last") : "3"));
        expectList(2, success(list(summary(91)))
                .header(secondEvidence, secondEvidence.equals("Link") ? link(2, "last") : "2"));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @CsvSource({"X-Total-Pages,false", "Link,false", "X-Total-Pages,true", "Link,true"})
    void retainsKnownFinalPageWhenLaterResponsesOmitTotalEvidence(String evidence, boolean fullFinalPage) {
        expectList(1, success(fullVersionPage(1)).header("X-Next-Page", "2")
                .header(evidence, evidence.equals("Link") ? link(2, "next") + ", " + link(3, "last") : "3"));
        expectList(2, success(fullVersionPage(101)).header("X-Next-Page", "3"));
        expectList(3, success(fullFinalPage ? fullVersionPage(201) : list(summary(201))));

        List<Long> ids = adapter.listDiffVersions(42, 17).stream()
                .map(DiffVersionSummary::versionId).toList();

        assertEquals(LongStream.rangeClosed(1, fullFinalPage ? 300 : 201).boxed().toList(), ids);
    }

    @Test
    void preservesTraversalWhenNoTotalPageEvidenceWasEverSupplied() {
        expectList(1, success(list(summary(93))).header("X-Next-Page", "2"));
        expectList(2, success(list(summary(91))));

        assertEquals(List.of(93L, 91L), adapter.listDiffVersions(42, 17).stream()
                .map(DiffVersionSummary::versionId).toList());
    }

    @Test
    void followsValidatedLinksWhenOptionalXHeadersAreAbsent() {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK,
                link(2, "next") + ", " + link(2, "last")));
        expectList(2, success(list(summary(91))).header(HttpHeaders.LINK, link(1, "prev")));

        assertEquals(List.of(93L, 91L), adapter.listDiffVersions(42, 17).stream()
                .map(DiffVersionSummary::versionId).toList());
    }

    @ParameterizedTest
    @CsvSource({"42,17", "9876543210,8765432109"})
    void acceptsLiveStyleFirstAndLastLinksOnSinglePageResponse(long projectId, long iid) {
        String versions = "/projects/" + projectId + "/merge_requests/" + iid + "/versions";
        expect(versions + "?per_page=100&page=1", success(list(summary(93)))
                .header("X-Page", "1").header("X-Per-Page", "100")
                .header("X-Total-Pages", "1").header("X-Next-Page", "")
                .header(HttpHeaders.LINK, routeLink(projectId, iid, 1, "first") + ", "
                        + routeLink(projectId, iid, 1, "last")));

        List<DiffVersionSummary> actual = assertDoesNotThrow(() -> adapter.listDiffVersions(projectId, iid));

        assertEquals(List.of(new DiffVersionSummary(93, REVISION, "collected")), actual);
    }

    @Test
    void followsLiveStyleNextLinkAcrossPages() {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK,
                routeLink(42, 17, 2, "next") + ", " + routeLink(42, 17, 2, "last")));
        expect(VERSIONS + "?id=42&merge_request_iid=17&page=2&per_page=100",
                success(list(summary(91))).header(HttpHeaders.LINK,
                        routeLink(42, 17, 1, "first") + ", " + routeLink(42, 17, 2, "last")
                                + ", " + link(1, "prev")));

        List<DiffVersionSummary> actual = assertDoesNotThrow(() -> adapter.listDiffVersions(42, 17));

        assertEquals(List.of(93L, 91L), actual.stream().map(DiffVersionSummary::versionId).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "id=43&merge_request_iid=17",
            "id=42&merge_request_iid=18",
            "id=42",
            "merge_request_iid=17",
            "id=42&merge_request_iid=17&extra=private",
            "id=42&unexpected=17",
            "id=42&id=42&merge_request_iid=17",
            "id=42&id=43&merge_request_iid=17",
            "id=42&merge_request_iid=17&merge_request_iid=17",
            "id=42&merge_request_iid=17&merge_request_iid=18",
            "id=42&merge_request_iid=17&page=2",
            "id=42&merge_request_iid=17&per_page=100",
            "id=&merge_request_iid=17",
            "id&merge_request_iid=17",
            "id=0&merge_request_iid=17",
            "id=-1&merge_request_iid=17",
            "id=+42&merge_request_iid=17",
            "id=042&merge_request_iid=17",
            "id=42.0&merge_request_iid=17",
            "id=invalid&merge_request_iid=17",
            "id=9223372036854775808&merge_request_iid=17",
            "id=42&merge_request_iid=",
            "id=42&merge_request_iid",
            "id=42&merge_request_iid=0",
            "id=42&merge_request_iid=-1",
            "id=42&merge_request_iid=+17",
            "id=42&merge_request_iid=017",
            "id=42&merge_request_iid=17.0",
            "id=42&merge_request_iid=invalid",
            "id=42&merge_request_iid=9223372036854775808"
    })
    void rejectsInvalidLiveStyleQueryWithoutSendingAnotherRequest(String routeQuery) {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK,
                "<" + BASE_URL + VERSIONS + "?per_page=100&page=2&" + routeQuery + ">; rel=\"next\""));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @MethodSource("unsafeLiveStyleLinks")
    void rejectsUnsafeLiveStyleLinkWithoutSendingAnotherRequest(String url) {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK, "<" + url + ">; rel=\"next\""));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    static Stream<String> unsafeLiveStyleLinks() {
        String url = BASE_URL + VERSIONS + "?id=42&merge_request_iid=17&page=2&per_page=100";
        return Stream.of(url.replace("gitlab.example.test", "other.example.test"),
                url.replace("https://", "http://"),
                url.replace("gitlab.example.test", "gitlab.example.test:444"),
                url.replace("https://", "https://" + FAKE_TOKEN + "@"),
                url.replace("/projects/42/", "/projects/99/"),
                url.replace("/merge_requests/17/", "/merge_requests/18/"),
                url + "#private",
                url.replace("page=2&", "page=0&"),
                url.replace("per_page=100", "per_page=20"));
    }

    @Test
    void acceptsConsistentLinkAndXNextPageHeaders() {
        expectList(1, success(list(summary(93))).header("X-Next-Page", "2")
                .header(HttpHeaders.LINK, link(2, "next")));
        expectList(2, success("[]").header("X-Next-Page", ""));

        assertEquals(1, adapter.listDiffVersions(42, 17).size());
    }

    @Test
    void emptyVersionListIsAValidResultNotAClaimOfNoChanges() {
        expectList(1, success("[]").header("X-Next-Page", "").header("X-Total-Pages", "0"));

        assertEquals(List.of(), adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @ValueSource(strings = {"nonsense", "0", "-1", "1", "3", "2147483648"})
    void rejectsMalformedCyclicOrSkippedNextPage(String next) {
        expectList(1, success(list(summary(93))).header("X-Next-Page", next));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @CsvSource({"X-Page,2", "X-Per-Page,20", "X-Total-Pages,2", "X-Total-Pages,invalid"})
    void rejectsInconsistentOrMalformedPaginationHeaders(String name, String value) {
        expectList(1, success(list(summary(93))).header(name, value));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void rejectsMultipleNextPageHeaderValues() {
        expectList(1, success(list(summary(93))).header("X-Next-Page", "2", "3"));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "garbage", "<https://gitlab.example.test>; rel=\"next\",", "<bad>; rel=\"next\""})
    void rejectsMalformedLinkHeader(String link) {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK, link));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://other.example.test/api/v4/projects/42/merge_requests/17/versions?per_page=100&page=2",
            "http://gitlab.example.test/api/v4/projects/42/merge_requests/17/versions?per_page=100&page=2",
            "https://gitlab.example.test:444/api/v4/projects/42/merge_requests/17/versions?per_page=100&page=2",
            "https://FAKE_DIFF_TEST_CREDENTIAL_DO_NOT_USE@gitlab.example.test/api/v4/projects/42/merge_requests/17/versions?per_page=100&page=2",
            "https://gitlab.example.test/api/v4/projects/99/merge_requests/17/versions?per_page=100&page=2",
            "https://gitlab.example.test/api/v4/projects/42/merge_requests/17/versions?per_page=100&page=2#private",
            "https://gitlab.example.test/api/v4/projects/42/merge_requests/17/versions?per_page=100&page=2&page=3",
            "https://gitlab.example.test/api/v4/projects/42/merge_requests/17/versions?per_page=20&page=2",
            "https://gitlab.example.test/api/v4/projects/42/merge_requests/17/versions?per_page=100&page=2&extra=private"
    })
    void rejectsUnsafeOrUnexpectedNextLinkWithoutSendingAnotherRequest(String url) {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK, "<" + url + ">; rel=\"next\""));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void rejectsConflictingLinkAndNextPageHeaders() {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK, link(2, "next"))
                .header("X-Next-Page", ""));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void rejectsDuplicateNextLinks() {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK,
                link(2, "next") + ", " + link(2, "next")));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @ValueSource(strings = {"first", "prev", "last"})
    void rejectsOtherLinkRelationsThatContradictCurrentPageOrMissingContinuation(String relation) {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK, link(2, relation)));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void rejectsConflictingLastLinkAndTotalPagesHeader() {
        expectList(1, success(list(summary(93))).header(HttpHeaders.LINK,
                link(2, "next") + ", " + link(3, "last")).header("X-Total-Pages", "2"));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void detectsCycleAfterAValidPageWithoutReturningPartialResults() {
        expectList(1, success(list(summary(93))).header("X-Next-Page", "2"));
        expectList(2, success(list(summary(91))).header("X-Next-Page", "1"));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void failsRatherThanReturningPartialListWhenLaterPageFails() {
        expectList(1, success(list(summary(93))).header("X-Next-Page", "2"));
        expectList(2, withStatus(HttpStatus.SERVICE_UNAVAILABLE).body(SENSITIVE));

        assertFailure("GitLab diff read returned HTTP 503", () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void failsWhenPaginationExceedsResourceBound() {
        for (int page = 1; page <= 100; page++) {
            expectList(page, success(list(summary(page))).header("X-Next-Page", String.valueOf(page + 1)));
        }

        assertFailure("GitLab diff version pagination limit exceeded", () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void rejectsFullPageWithoutPaginationEvidence() {
        var response = JSON.createArrayNode();
        for (int id = 1; id <= 100; id++) {
            response.add(summary(id));
        }
        expectList(1, success(response.toString()));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void acceptsFullOnlyPageWhenTotalPagesConfirmsItIsFinal() {
        expectList(1, success(fullVersionPage(1)).header("X-Total-Pages", "1"));

        List<Long> ids = adapter.listDiffVersions(42, 17).stream()
                .map(DiffVersionSummary::versionId).toList();

        assertEquals(LongStream.rangeClosed(1, 100).boxed().toList(), ids);
    }

    @Test
    void acceptsFullFinalPageWhenTotalPagesConfirmsTraversalIsComplete() {
        expectList(1, success(fullVersionPage(1))
                .header("X-Total-Pages", "2").header("X-Next-Page", "2"));
        expectList(2, success(fullVersionPage(101))
                .header("X-Page", "2").header("X-Total-Pages", "2"));

        List<Long> ids = adapter.listDiffVersions(42, 17).stream()
                .map(DiffVersionSummary::versionId).toList();

        assertEquals(LongStream.rangeClosed(1, 200).boxed().toList(), ids);
    }

    @ParameterizedTest
    @CsvSource({"1,2", "2,''"})
    void rejectsFullPageWhenTotalPagesConflictsWithContinuation(String totalPages, String nextPage) {
        expectList(1, success(fullVersionPage(1))
                .header("X-Total-Pages", totalPages).header("X-Next-Page", nextPage));

        assertFailure(INVALID_PAGINATION, () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void rejectsRepeatedVersionsAcrossPagesRatherThanHidingAnUnstableList() {
        expectList(1, success(list(summary(93))).header("X-Next-Page", "2"));
        expectList(2, success(list(summary(93))));

        assertFailure("GitLab diff version list contains duplicate identifiers", () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @CsvSource({"0,17", "-1,17", "42,0", "42,-1"})
    void rejectsInvalidProjectOrIidBeforeHttp(long projectId, long iid) {
        assertThrowsExactly(IllegalArgumentException.class, () -> adapter.listDiffVersions(projectId, iid));
        assertThrowsExactly(IllegalArgumentException.class, () -> adapter.getDiffVersion(projectId, iid, 93));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidVersionIdBeforeHttp(long versionId) {
        assertThrowsExactly(IllegalArgumentException.class, () -> adapter.getDiffVersion(42, 17, versionId));
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "base_commit_sha", "start_commit_sha", "head_commit_sha", "state"})
    void rejectsMissingRequiredVersionFieldsForBothOperations(String field) {
        ObjectNode version = summary(93);
        version.remove(field);
        expectList(1, success(list(version)));
        version.set("diffs", JSON.createArrayNode());
        expectDetail(success(version.toString()));

        assertFailure(INVALID_RESPONSE, () -> adapter.listDiffVersions(42, 17));
        assertFailure(INVALID_RESPONSE, () -> adapter.getDiffVersion(42, 17, 93));
    }

    @ParameterizedTest
    @MethodSource("incompleteRevision")
    void rejectsNullOrBlankRevisionValues(String field, String value) {
        expectDetail(success(detail().put(field, value).toString()));

        assertFailure(INVALID_RESPONSE, () -> adapter.getDiffVersion(42, 17, 93));
    }

    static Stream<Arguments> incompleteRevision() {
        return Stream.of("base_commit_sha", "start_commit_sha", "head_commit_sha", "state")
                .flatMap(field -> Stream.of(Arguments.of(field, null),
                        Arguments.of(field, ""), Arguments.of(field, " \t\n")));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 94})
    void rejectsInvalidOrUnexpectedVersionIdentifier(long versionId) {
        expectDetail(success(detail().put("id", versionId).toString()));

        assertFailure(versionId <= 0 ? INVALID_RESPONSE : "GitLab diff version identifier does not match request",
                () -> adapter.getDiffVersion(42, 17, 93));
    }

    @ParameterizedTest
    @CsvSource({"new_file,false", "deleted_file,false", "renamed_file,false", "collapsed,false", "too_large,false",
            "new_file,true", "deleted_file,true", "renamed_file,true", "collapsed,true", "too_large,true"})
    void rejectsMissingOrNullCriticalFlagsRatherThanDefaultingToFalse(String field, boolean explicitNull) {
        ObjectNode response = detail();
        ObjectNode file = (ObjectNode) response.withArray("diffs").get(0);
        if (explicitNull) {
            file.putNull(field);
        } else {
            file.remove(field);
        }
        expectDetail(success(response.toString()));

        assertFailure("GitLab diff response is missing required file flags", () -> adapter.getDiffVersion(42, 17, 93));
    }

    @ParameterizedTest
    @ValueSource(strings = {"collapsed", "too_large"})
    void preservesIncompleteFileFlagsAndAbsentDiff(String flag) {
        ObjectNode response = detail();
        ObjectNode file = (ObjectNode) response.withArray("diffs").get(0);
        file.put(flag, true).remove("diff");
        expectDetail(success(response.toString()));

        ChangedFileDiff actual = adapter.getDiffVersion(42, 17, 93).files().getFirst();

        assertEquals(flag.equals("collapsed"), actual.collapsed());
        assertEquals(flag.equals("too_large"), actual.tooLarge());
        assertEquals(Optional.empty(), actual.diffText());
    }

    @Test
    void distinguishesAbsentNullAndEmptyDiffTextAndOptionalGeneratedFlag() {
        ObjectNode response = detail();
        ObjectNode missing = file();
        missing.remove("diff");
        missing.remove("generated_file");
        response.withArray("diffs").removeAll().add(missing)
                .add(file().putNull("diff").putNull("generated_file"))
                .add(file().put("diff", "").put("generated_file", false));
        expectDetail(success(response.toString()));

        List<ChangedFileDiff> files = adapter.getDiffVersion(42, 17, 93).files();

        assertEquals(Optional.empty(), files.get(0).diffText());
        assertEquals(Optional.empty(), files.get(1).diffText());
        assertEquals(Optional.of(""), files.get(2).diffText());
        assertEquals(Optional.empty(), files.get(0).generatedFile());
        assertEquals(Optional.empty(), files.get(1).generatedFile());
        assertEquals(Optional.of(false), files.get(2).generatedFile());
    }

    @ParameterizedTest
    @ValueSource(strings = {"overflow", "without_files", "future_state"})
    void preservesStateWithExplicitEmptyFilesAndMissingReportedSize(String state) {
        ObjectNode response = detail().put("state", state);
        response.withArray("diffs").removeAll();
        response.remove("real_size");
        expectDetail(success(response.toString()));

        MergeRequestDiffVersion actual = adapter.getDiffVersion(42, 17, 93);

        assertEquals(state, actual.state());
        assertEquals(List.of(), actual.files());
        assertEquals(Optional.empty(), actual.gitlabReportedSize());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectsAbsentOrNullDiffListEvenForWithoutFilesState(boolean explicitNull) {
        ObjectNode response = detail().put("state", "without_files");
        if (explicitNull) {
            response.putNull("diffs");
        } else {
            response.remove("diffs");
        }
        expectDetail(success(response.toString()));

        assertFailure(INVALID_RESPONSE, () -> adapter.getDiffVersion(42, 17, 93));
    }

    @Test
    void rejectsNullFileEntry() {
        ObjectNode response = detail();
        response.withArray("diffs").removeAll().addNull();
        expectDetail(success(response.toString()));

        assertFailure(INVALID_RESPONSE, () -> adapter.getDiffVersion(42, 17, 93));
    }

    @ParameterizedTest
    @ValueSource(strings = {"old_path", "new_path"})
    void rejectsBlankPathsWithoutExposingOtherFileContent(String field) {
        ObjectNode response = detail();
        ((ObjectNode) response.withArray("diffs").get(0)).put(field, " \t\n");
        expectDetail(success(response.toString()));

        assertFailure(INVALID_RESPONSE, () -> adapter.getDiffVersion(42, 17, 93));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403, 404, 429, 500, 502, 503})
    void sanitizesHttpFailuresForBothOperationsWithoutRetryOrWrites(int status) {
        expectList(1, withStatus(HttpStatus.valueOf(status)).header(HttpHeaders.RETRY_AFTER, "120").body(SENSITIVE));
        expectDetail(withStatus(HttpStatus.valueOf(status)).body(SENSITIVE));

        assertFailure("GitLab diff read returned HTTP " + status, () -> adapter.listDiffVersions(42, 17));
        assertFailure("GitLab diff read returned HTTP " + status, () -> adapter.getDiffVersion(42, 17, 93));
    }

    @Test
    void rejectsPartialContentVersionListEvenWithValidJson() {
        expectList(1, withStatus(HttpStatus.PARTIAL_CONTENT).contentType(MediaType.APPLICATION_JSON)
                .body(list(summary(93).put("private_data", SENSITIVE))));

        assertFailure("GitLab diff read returned HTTP 206", () -> adapter.listDiffVersions(42, 17));
    }

    @Test
    void rejectsPartialContentVersionDetailEvenWithValidJson() {
        expectDetail(withStatus(HttpStatus.PARTIAL_CONTENT).contentType(MediaType.APPLICATION_JSON)
                .body(detail().put("private_data", SENSITIVE).toString()));

        assertFailure("GitLab diff read returned HTTP 206", () -> adapter.getDiffVersion(42, 17, 93));
    }

    @Test
    void rejectsRedirectResponseWithoutFollowingLocation() {
        expectList(1, withStatus(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, "https://other.example.test/" + FAKE_TOKEN).body(SENSITIVE));

        assertFailure("GitLab diff read returned HTTP 302", () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{}", "{malformed", "\"scalar\"", "[null]"})
    void rejectsMissingOrInvalidListBody(String body) {
        expectList(1, success(body));

        assertFailure(body.isEmpty() || body.equals("null") || body.equals("[null]")
                ? INVALID_RESPONSE : "GitLab diff read failed", () -> adapter.listDiffVersions(42, 17));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "{malformed", "\"scalar\"", "<html>private</html>"})
    void rejectsMissingOrInvalidDetailBody(String body) {
        expectDetail(success(body));

        assertFailure(body.isEmpty() || body.equals("null") ? INVALID_RESPONSE : "GitLab diff read failed",
                () -> adapter.getDiffVersion(42, 17, 93));
    }

    @Test
    void sanitizesDeserializerErrorsContainingSourceAndCredentials() {
        ObjectNode response = detail();
        response.putObject("id").put("private_data", SENSITIVE);
        expectDetail(success(response.toString()));

        assertFailure("GitLab diff read failed", () -> adapter.getDiffVersion(42, 17, 93));
    }

    @Test
    void sanitizesInvalidBooleanType() {
        ObjectNode response = detail();
        ((ObjectNode) response.withArray("diffs").get(0)).put("collapsed", SENSITIVE);
        expectDetail(success(response.toString()));

        assertFailure("GitLab diff read failed", () -> adapter.getDiffVersion(42, 17, 93));
    }

    @Test
    void validJsonWithUnsupportedContentTypeFailsSanitized() {
        expectDetail(withSuccess(detail().toString(), MediaType.TEXT_PLAIN));

        assertFailure("GitLab diff read failed", () -> adapter.getDiffVersion(42, 17, 93));
    }

    @ParameterizedTest(name = "transport failure {index}")
    @MethodSource("transportFailures")
    void sanitizesSimulatedConnectionAndTimeoutFailures(IOException failure) {
        // Fault injection only; no actual network timeouts or sleeps.
        expectList(1, withException(failure));
        expectDetail(withException(failure));

        assertFailure("GitLab diff read failed", () -> adapter.listDiffVersions(42, 17));
        assertFailure("GitLab diff read failed", () -> adapter.getDiffVersion(42, 17, 93));
    }

    static Stream<IOException> transportFailures() {
        return Stream.of(new ConnectException(SENSITIVE), new SocketTimeoutException(SENSITIVE));
    }

    private static ObjectNode summary(long id) {
        return JSON.createObjectNode().put("id", id).put("base_commit_sha", BASE_SHA)
                .put("start_commit_sha", START_SHA).put("head_commit_sha", HEAD_SHA).put("state", "collected");
    }

    private static ObjectNode detail() {
        ObjectNode response = summary(93).put("real_size", "1");
        response.putArray("diffs").add(file());
        return response;
    }

    private static ObjectNode file() {
        return JSON.createObjectNode().put("old_path", PRIVATE_PATH).put("new_path", PRIVATE_PATH)
                .put("diff", "@@ -1 +1 @@\n-old\n+" + PRIVATE_SOURCE + "\n").put("new_file", false)
                .put("deleted_file", false).put("renamed_file", false).put("collapsed", false)
                .put("too_large", false).put("generated_file", false);
    }

    private static String list(ObjectNode version) {
        return JSON.createArrayNode().add(version).toString();
    }

    private static String fullVersionPage(int firstId) {
        var response = JSON.createArrayNode();
        for (int id = firstId; id < firstId + 100; id++) {
            response.add(summary(id));
        }
        return response.toString();
    }

    private static DefaultResponseCreator success(String json) {
        return withSuccess(json, MediaType.APPLICATION_JSON);
    }

    private static String link(int page, String relation) {
        return "<" + BASE_URL + VERSIONS + "?per_page=100&page=" + page + ">; rel=\"" + relation + "\"";
    }

    private static String routeLink(long projectId, long iid, int page, String relation) {
        return "<" + BASE_URL + "/projects/" + projectId + "/merge_requests/" + iid + "/versions"
                + "?id=" + projectId + "&merge_request_iid=" + iid + "&page=" + page
                + "&per_page=100>; rel=\"" + relation + "\"";
    }

    private void expectList(int page, ResponseCreator response) {
        expect(VERSIONS + "?per_page=100&page=" + page, response);
    }

    private void expectDetail(ResponseCreator response) {
        expect(VERSIONS + "/93", response);
    }

    private void expect(String path, ResponseCreator response) {
        server.expect(ExpectedCount.once(), requestTo(BASE_URL + path))
                .andExpect(method(HttpMethod.GET)).andExpect(header("PRIVATE-TOKEN", FAKE_TOKEN))
                .andRespond(response);
    }

    private void assertFailure(String message, Executable call) {
        IllegalStateException failure = assertThrowsExactly(IllegalStateException.class, call);
        assertEquals(message, failure.getMessage());
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        for (String sentinel : List.of(FAKE_TOKEN, PRIVATE_SOURCE, PRIVATE_PATH)) {
            assertFalse(output.toString().contains(sentinel), "Exceptions must not expose private input");
        }
    }
}
