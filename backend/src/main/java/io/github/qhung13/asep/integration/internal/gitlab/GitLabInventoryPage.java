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
        if (!collectionShape(body)) return false;
        return io.github.qhung13.asep.integration.GitLabTestProjectReader.CollectionObservation.singlePage(body.size(),headers.asMultiValueMap()).complete();
    }

}
