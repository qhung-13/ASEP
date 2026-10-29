package io.github.qhung13.asep.integration.internal.gitlab;

import java.util.List;
import org.springframework.http.HttpHeaders;
import tools.jackson.databind.JsonNode;

/** Conservative proof of a complete first/only offset page. Never follows response links.
 * Missing totals/headers, full pages, conflicting metadata or any nonempty Link stay incomplete.
 */
final class GitLabInventoryPage {
    private GitLabInventoryPage() {}
    static boolean collectionShape(JsonNode body) {
        if (body == null || !body.isArray()) return false;
        for (JsonNode item : body) if (!item.isObject()) return false;
        return true;
    }
    static boolean complete(JsonNode body, HttpHeaders headers) {
        if (!collectionShape(body) || body.size() >= 100) return false;
        String page = single(headers, "X-Page");
        String perPage = single(headers, "X-Per-Page");
        String total = single(headers, "X-Total");
        String pages = single(headers, "X-Total-Pages");
        String next = single(headers, "X-Next-Page");
        if (!"1".equals(page) || !"100".equals(perPage) || !Integer.toString(body.size()).equals(total)
                || !("1".equals(pages) || body.isEmpty() && "0".equals(pages)) || next == null || !next.isEmpty()) return false;
        // Do not interpret or follow arbitrary relation URLs. Even non-next links can
        // contradict the single-page metadata; any nonempty Link is conservatively incomplete.
        List<String> links = headers.get("Link");
        if (links != null && links.stream().anyMatch(link -> !link.isBlank())) return false;
        return true;
    }
    private static String single(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        return values != null && values.size() == 1 ? values.getFirst().trim() : null;
    }
}
