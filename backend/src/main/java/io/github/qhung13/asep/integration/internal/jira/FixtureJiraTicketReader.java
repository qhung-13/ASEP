package io.github.qhung13.asep.integration.internal.jira;

import io.github.qhung13.asep.integration.JiraReadException;
import io.github.qhung13.asep.integration.JiraTicketReader;
import io.github.qhung13.asep.integration.JiraTicketSnapshot;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Offline transport only. No HTTP client, credential, Spring bean or production Jira wiring. */
public final class FixtureJiraTicketReader implements JiraTicketReader {
    public record Response(int status, String contentType, byte[] body) {
        public Response { body = body.clone(); }
        @Override public byte[] body() { return body.clone(); }
        @Override public String toString() { return "FixtureJiraResponse[redacted]"; }
    }
    // Transport and normalization are separate from the Application's eligibility
    // gate. Only a copied offline map can be installed through this constructor.
    private interface Transport { Response read(Selection selection); }
    private final Transport transport;
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public FixtureJiraTicketReader(Map<Selection, Response> fixtures) {
        Map<Selection, Response> copied = Map.copyOf(new HashMap<>(fixtures));
        this.transport = copied::get;
    }

    /** There is deliberately no opt-in switch that enables a live transport. */
    public static JiraTicketReader liveAccessDisabled() {
        return (selection, limits) -> { throw new JiraReadException(JiraReadException.Reason.LIVE_ACCESS_DISABLED); };
    }

    @Override public JiraTicketSnapshot read(Selection selection, Limits limits) {
        if (selection == null || limits == null) throw invalid();
        Response response = transport.read(selection);
        if (response == null || response.status() != 200) {
            throw new JiraReadException(JiraReadException.Reason.UNAVAILABLE);
        }
        byte[] body = response.body();
        if (!"application/json".equalsIgnoreCase(response.contentType()) || body.length > limits.responseBytes()) {
            throw invalid();
        }
        return normalize(selection, limits, body);
    }

    static JiraTicketSnapshot normalize(Selection selection, Limits limits, byte[] body) {
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject() || !text(root.get("id")).equals(selection.issueId())) throw invalid();
            JsonNode fields = root.get("fields");
            if (fields == null || !fields.isObject()
                    || !text(fields.path("project").get("id")).equals(selection.projectId())) throw invalid();
            String summary = text(fields.get("summary"));
            bounded(summary, limits.summaryBytes());
            JsonNode subtask = fields.path("issuetype").get("subtask");
            if (subtask != null && (!subtask.isBoolean() || subtask.booleanValue())) throw invalid();
            JsonNode description = fields.get("description");
            String normalized = "";
            if (description != null && !description.isNull()) {
                if (!description.isObject() || !"doc".equals(text(description.get("type")))
                        || !description.path("version").isInt() || description.path("version").intValue() != 1) throw invalid();
                StringBuilder result = new StringBuilder();
                adf(description, 0, new int[]{0}, result, limits);
                normalized = result.toString();
            }
            return new JiraTicketSnapshot(selection, text(fields.path("issuetype").get("name")),
                    text(fields.path("status").get("name")), summary, normalized, JiraTicketSnapshot.sha256(body));
        } catch (RuntimeException failure) {
            throw invalid(); // Never retain the parser's raw source-bearing cause.
        }
    }

    private static void adf(JsonNode node, int depth, int[] nodes, StringBuilder text, Limits limits) {
        if (!node.isObject() || depth > limits.adfDepth() || ++nodes[0] > limits.adfNodes()) throw invalid();
        String type = text(node.get("type"));
        switch (type) {
            case "text" -> {
                JsonNode value = node.get("text");
                if (value == null || !value.isString() || value.asString().isEmpty()) throw invalid();
                text.append(value.asString());
                if (node.has("content")) throw invalid();
            }
            case "hardBreak" -> {
                if (node.has("content") || node.has("text")) throw invalid();
                text.append('\n');
            }
            case "doc", "paragraph", "heading", "blockquote", "bulletList", "orderedList", "listItem", "codeBlock" -> {
                JsonNode content = node.get("content");
                if (content == null || !content.isArray()) throw invalid();
                for (JsonNode child : content) {
                    String childType = text(child.get("type"));
                    boolean allowed = switch (type) {
                        case "paragraph", "heading", "codeBlock" -> childType.equals("text") || childType.equals("hardBreak");
                        case "bulletList", "orderedList" -> childType.equals("listItem");
                        default -> java.util.Set.of("paragraph", "heading", "blockquote", "bulletList", "orderedList", "codeBlock").contains(childType);
                    };
                    if (!allowed) throw invalid();
                    adf(child, depth + 1, nodes, text, limits);
                }
                if (!"doc".equals(type) && !"text".equals(type)) text.append('\n');
            }
            default -> throw invalid(); // Media, cards and unknown node types cannot add capabilities.
        }
        bounded(text.toString(), limits.descriptionBytes());
    }

    private static String text(JsonNode value) {
        if (value == null || !value.isString() || value.asString().isBlank()) throw invalid();
        return value.asString();
    }
    private static void bounded(String text, int maximum) {
        if (text.getBytes(StandardCharsets.UTF_8).length > maximum || text.indexOf('\0') >= 0) throw invalid();
    }
    private static JiraReadException invalid() { return new JiraReadException(JiraReadException.Reason.INVALID_RESPONSE); }
}
