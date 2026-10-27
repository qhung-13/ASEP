package io.github.qhung13.asep.integration.internal.jira;

import io.github.qhung13.asep.integration.JiraReadException;
import io.github.qhung13.asep.integration.JiraTicketReader;
import io.github.qhung13.asep.integration.JiraTicketSnapshot;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

class FixtureJiraTicketReaderTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JiraTicketReader.Selection SELECTED = new JiraTicketReader.Selection("synthetic-site", "10001", "10002");
    private static final JiraTicketReader.Limits LIMITS = new JiraTicketReader.Limits(8192, 256, 1024, 8, 128);
    private static final String PRIVATE = "FAKE_PRIVATE_JIRA_TEXT_DO_NOT_LOG";

    private static ObjectNode vector() throws Exception {
        try (var in = FixtureJiraTicketReaderTest.class.getResourceAsStream("/contracts/manual-jira-fixture-v1.json")) {
            return (ObjectNode) JSON.readTree(in);
        }
    }
    private static ObjectNode payload() throws Exception {
        return (ObjectNode) JSON.readTree(vector().get("responseBody").asString());
    }
    private static FixtureJiraTicketReader reader(int status, String contentType, byte[] body) {
        return new FixtureJiraTicketReader(Map.of(SELECTED, new FixtureJiraTicketReader.Response(status, contentType, body)));
    }
    private static JiraTicketSnapshot read(ObjectNode payload) {
        return reader(200, "application/json", JSON.writeValueAsBytes(payload)).read(SELECTED, LIMITS);
    }
    private static void rejected(Runnable read, JiraReadException.Reason reason) {
        JiraReadException error = assertThrowsExactly(JiraReadException.class, read::run);
        assertEquals(reason, error.reason());
        assertNull(error.getCause());
        var trace = new StringWriter();
        error.printStackTrace(new PrintWriter(trace));
        assertFalse(trace.toString().contains(PRIVATE));
    }

    @Test void preservesAllowedTextAndSyntheticProvenanceWithoutFollowingLinks() throws Exception {
        var v = vector();
        byte[] body = v.get("responseBody").asString().getBytes(StandardCharsets.UTF_8);
        var transport = reader(200, "application/json", body);
        body[0] = 0; // The fixture owns a defensive copy.
        var ticket = transport.read(SELECTED, LIMITS);
        assertEquals(SELECTED, ticket.identity());
        assertEquals("Task", ticket.issueType());
        assertEquals("To Do", ticket.status());
        assertEquals(v.get("normalizedDescription").asString(), ticket.description());
        assertEquals(v.path("command").get("sourceFingerprintSha256").asString(), ticket.fingerprint());
        assertTrue(ticket.description().contains("https://untrusted.invalid/"));
        assertFalse(ticket.description().contains("FAKE_UNAPPROVED_FIELD"));
        assertFalse(ticket.toString().contains(ticket.summary()));
        assertFalse(ticket.toString().contains(ticket.description()));
    }

    @ParameterizedTest @ValueSource(ints = {201, 206, 302, 401, 403, 404, 429, 500, 503})
    void rejectsUnsupportedStatusWithoutRetryOrBodyLeak(int status) {
        rejected(() -> reader(status, "application/json", PRIVATE.getBytes(StandardCharsets.UTF_8)).read(SELECTED, LIMITS), JiraReadException.Reason.UNAVAILABLE);
    }

    @ParameterizedTest @ValueSource(strings = {"id", "project", "issuetype", "status", "summary"})
    void rejectsMissingRequiredFields(String field) throws Exception {
        var payload = payload();
        if (field.equals("id")) payload.remove(field); else ((ObjectNode) payload.get("fields")).remove(field);
        rejected(() -> read(payload), JiraReadException.Reason.INVALID_RESPONSE);
    }

    @ParameterizedTest @ValueSource(strings = {"wrong-issue", "wrong-project", "null-summary", "number-summary", "unpaired-surrogate", "subtask", "invalid-subtask-flag"})
    void rejectsInvalidIdentityAndText(String mode) throws Exception {
        var payload = payload();
        var fields = (ObjectNode) payload.get("fields");
        switch (mode) {
            case "wrong-issue" -> payload.put("id", "10003");
            case "wrong-project" -> ((ObjectNode) fields.get("project")).put("id", "10003");
            case "null-summary" -> fields.putNull("summary");
            case "number-summary" -> fields.put("summary", 3);
            case "unpaired-surrogate" -> fields.put("summary", "\ud800");
            case "subtask" -> ((ObjectNode) fields.get("issuetype")).put("subtask", true);
            case "invalid-subtask-flag" -> ((ObjectNode) fields.get("issuetype")).put("subtask", "false");
        }
        rejected(() -> read(payload), JiraReadException.Reason.INVALID_RESPONSE);
    }

    @ParameterizedTest @ValueSource(strings = {"{bad", "[]", "null", "{\"id\":\"10002\",\"id\":\"10002\"}"})
    void rejectsMalformedOrDuplicateJson(String body) {
        rejected(() -> reader(200, "application/json", body.getBytes(StandardCharsets.UTF_8)).read(SELECTED, LIMITS), JiraReadException.Reason.INVALID_RESPONSE);
    }

    @ParameterizedTest @ValueSource(strings = {"{}", "null", "FAKE_PRIVATE_JIRA_TEXT_DO_NOT_LOG"})
    void rejectsTrailingDocumentsOrText(String trailing) throws Exception {
        byte[] body = (vector().get("responseBody").asString() + " " + trailing).getBytes(StandardCharsets.UTF_8);
        rejected(() -> reader(200, "application/json", body).read(SELECTED, LIMITS), JiraReadException.Reason.INVALID_RESPONSE);
    }

    @ParameterizedTest @ValueSource(strings = {"version", "content", "media", "nested-doc", "missing-text", "text-type", "text-children", "hard-break-children", "scalar"})
    void rejectsMalformedOrUnsupportedAdf(String mode) throws Exception {
        var payload = payload();
        var fields = (ObjectNode) payload.get("fields");
        var doc = (ObjectNode) fields.get("description");
        var paragraph = (ObjectNode) doc.withArray("content").get(0);
        var leaf = (ObjectNode) paragraph.withArray("content").get(0);
        switch (mode) {
            case "version" -> doc.put("version", 2);
            case "content" -> doc.put("content", PRIVATE);
            case "media" -> leaf.put("type", "media");
            case "nested-doc" -> paragraph.put("type", "doc");
            case "missing-text" -> leaf.remove("text");
            case "text-type" -> leaf.put("text", true);
            case "text-children" -> leaf.putArray("content");
            case "hard-break-children" -> leaf.put("type", "hardBreak").putArray("content");
            case "scalar" -> fields.put("description", PRIVATE);
        }
        rejected(() -> read(payload), JiraReadException.Reason.INVALID_RESPONSE);
    }

    @Test void enforcesIndependentByteDepthAndNodeLimits() throws Exception {
        byte[] bytes = vector().get("responseBody").asString().getBytes(StandardCharsets.UTF_8);
        for (var limits : new JiraTicketReader.Limits[]{
                new JiraTicketReader.Limits(bytes.length - 1, 256, 256, 8, 128),
                new JiraTicketReader.Limits(8192, 1, 1024, 8, 128),
                new JiraTicketReader.Limits(8192, 256, 1, 8, 128),
                new JiraTicketReader.Limits(8192, 256, 1024, 1, 128),
                new JiraTicketReader.Limits(8192, 256, 1024, 8, 2)}) {
            rejected(() -> reader(200, "application/json", bytes).read(SELECTED, limits), JiraReadException.Reason.INVALID_RESPONSE);
        }
        rejected(() -> reader(200, "text/plain", bytes).read(SELECTED, LIMITS), JiraReadException.Reason.INVALID_RESPONSE);
        rejected(() -> reader(200, "application/json", new byte[]{(byte) 0xff}).read(SELECTED, LIMITS), JiraReadException.Reason.INVALID_RESPONSE);
    }

    @Test void acceptsNullDescriptionAndWhitespaceAsData() throws Exception {
        var payload = payload();
        ((ObjectNode) payload.get("fields")).putNull("description");
        assertEquals("", read(payload).description());
        payload = payload();
        ((ObjectNode) payload.path("fields").path("description").path("content").get(0).path("content").get(0)).put("text", " ");
        assertEquals(" \n", read(payload).description());
    }

    @Test void deniesLiveAccessAndUrlSelections() {
        rejected(() -> FixtureJiraTicketReader.liveAccessDisabled().read(SELECTED, LIMITS), JiraReadException.Reason.LIVE_ACCESS_DISABLED);
        assertThrows(IllegalArgumentException.class, () -> new JiraTicketReader.Selection("https://evil.invalid", "10001", "10002"));
        assertThrows(IllegalArgumentException.class, () -> new JiraTicketReader.Selection("synthetic-site", "10001", "../../secret"));
        rejected(() -> new FixtureJiraTicketReader(Map.of()).read(SELECTED, LIMITS), JiraReadException.Reason.UNAVAILABLE);
    }
}
