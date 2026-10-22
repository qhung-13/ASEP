package io.github.qhung13.asep.integration.internal.gitlab;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.qhung13.asep.integration.ChangedFileDiff;
import io.github.qhung13.asep.integration.DiffVersionSummary;
import io.github.qhung13.asep.integration.GitLabMergeRequestDiffReader;
import io.github.qhung13.asep.integration.MergeRequestDiffVersion;
import io.github.qhung13.asep.integration.MergeRequestMetadata.DiffReferences;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

public final class GitLabRestDiffReadAdapter implements GitLabMergeRequestDiffReader {

    // Bound one call to 100 requests / at most 10,000 version summaries. This is
    // a resource guard, not a limit that permits returning a partial list.
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGES = 100;
    private static final Pattern LINK = Pattern.compile(
            "\\s*<([^<>]+)>\\s*;\\s*rel=\"(first|prev|next|last)\"\\s*"
    );
    private final RestClient restClient;

    public GitLabRestDiffReadAdapter(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    @Override
    public List<DiffVersionSummary> listDiffVersions(long projectId, long mergeRequestIid) {
        validateIds(projectId, mergeRequestIid);
        List<DiffVersionSummary> versions = new ArrayList<>();
        Set<Long> seenIds = new HashSet<>();
        Integer knownTotalPages = null;
        RestClient.RequestHeadersSpec<?> request = restClient.get().uri(
                "/projects/{projectId}/merge_requests/{iid}/versions?per_page={size}&page=1",
                projectId, mergeRequestIid, PAGE_SIZE
        );

        for (int page = 1; page <= MAX_PAGES; page++) {
            ReadResponse<VersionResponse[]> response = read(request, VersionResponse[].class);
            if (response.body() == null) {
                throw invalidResponse();
            }
            for (VersionResponse version : response.body()) {
                DiffVersionSummary summary = mapSummary(version);
                if (!seenIds.add(summary.versionId())) {
                    throw new IllegalStateException("GitLab diff version list contains duplicate identifiers");
                }
                versions.add(summary);
            }
            Pagination pagination = nextPage(response, page, knownTotalPages);
            knownTotalPages = pagination.totalPages();
            URI next = pagination.next();
            if (next == null) {
                return List.copyOf(versions);
            }
            if (page == MAX_PAGES) {
                throw new IllegalStateException("GitLab diff version pagination limit exceeded");
            }
            request = restClient.get().uri(next);
        }
        throw new IllegalStateException("GitLab diff version pagination limit exceeded");
    }

    @Override
    public MergeRequestDiffVersion getDiffVersion(long projectId, long mergeRequestIid, long versionId) {
        validateIds(projectId, mergeRequestIid, versionId);
        VersionResponse response = read(restClient.get().uri(
                "/projects/{projectId}/merge_requests/{iid}/versions/{versionId}",
                projectId, mergeRequestIid, versionId
        ), VersionResponse.class).body();
        DiffVersionSummary summary = mapSummary(response);
        if (summary.versionId() != versionId) {
            throw new IllegalStateException("GitLab diff version identifier does not match request");
        }
        if (response.diffs() == null) {
            // Absence is not an empty list, even for a without_files response.
            throw invalidResponse();
        }
        List<ChangedFileDiff> files = response.diffs().stream()
                .map(GitLabRestDiffReadAdapter::mapFile).toList();
        // GitLab returns a global merge_request_id, not project_id / MR IID.
        // Preserve endpoint provenance rather than comparing that global ID to IID.
        return new MergeRequestDiffVersion(projectId, mergeRequestIid, versionId,
                summary.revision(), summary.state(), Optional.ofNullable(response.realSize()), files);
    }

    private <T> ReadResponse<T> read(RestClient.RequestHeadersSpec<?> request, Class<T> responseType) {
        try {
            // exchange exposes the resolved request URI for pagination origin checks.
            // It bypasses RestClient status handlers, so check status before reading a body.
            return request.exchange((httpRequest, response) -> {
                if (response.getStatusCode().value() != 200) {
                    throw new IllegalStateException("GitLab diff read returned HTTP "
                            + response.getStatusCode().value());
                }
                return new ReadResponse<>(response.bodyTo(responseType),
                        HttpHeaders.copyOf(response.getHeaders()), httpRequest.getURI());
            });
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException("GitLab diff read returned HTTP "
                    + exception.getStatusCode().value());
        } catch (RestClientException exception) {
            // Do not retain causes: converter/transport exceptions can contain secrets or source.
            throw new IllegalStateException("GitLab diff read failed");
        }
    }

    private static DiffVersionSummary mapSummary(VersionResponse response) {
        if (response == null || response.id() == null || response.id() <= 0
                || missing(response.baseSha()) || missing(response.startSha())
                || missing(response.headSha()) || missing(response.state())) {
            throw invalidResponse();
        }
        return new DiffVersionSummary(response.id(), new DiffReferences(
                response.baseSha(), response.startSha(), response.headSha()), response.state());
    }

    private static ChangedFileDiff mapFile(FileResponse file) {
        if (file == null || missing(file.oldPath()) || missing(file.newPath())) {
            throw invalidResponse();
        }
        if (file.newFile() == null || file.deletedFile() == null || file.renamedFile() == null
                || file.collapsed() == null || file.tooLarge() == null) {
            // collapsed/too_large were introduced in GitLab 18.4. The current
            // boolean contract cannot represent unknown values on older instances.
            throw new IllegalStateException("GitLab diff response is missing required file flags");
        }
        return new ChangedFileDiff(file.oldPath(), file.newPath(), Optional.ofNullable(file.diff()),
                file.newFile(), file.deletedFile(), file.renamedFile(), file.collapsed(),
                file.tooLarge(), Optional.ofNullable(file.generatedFile()));
    }

    private static Pagination nextPage(ReadResponse<VersionResponse[]> response, int page, Integer knownTotalPages) {
        HttpHeaders headers = response.headers();
        String current = singleHeader(headers, "X-Page");
        String pageSize = singleHeader(headers, "X-Per-Page");
        String total = singleHeader(headers, "X-Total-Pages");
        String next = singleHeader(headers, "X-Next-Page");
        if ((current != null && positivePage(current) != page)
                || (pageSize != null && positivePage(pageSize) != PAGE_SIZE)
                || response.body().length > PAGE_SIZE) {
            throw invalidPagination();
        }

        URI linkedNext = null;
        Integer linkedLast = null;
        Set<String> relations = new HashSet<>();
        List<String> links = headers.getOrEmpty(HttpHeaders.LINK);
        for (String header : links) {
            for (String entry : header.split(",", -1)) {
                Matcher match = LINK.matcher(entry);
                if (!match.matches() || !relations.add(match.group(2))) {
                    throw invalidPagination();
                }
                URI target = validatePageLink(response.uri(), match.group(1));
                int targetPage = positivePage(UriComponentsBuilder.fromUri(target)
                        .build().getQueryParams().getFirst("page"));
                switch (match.group(2)) {
                    case "next" -> {
                        if (targetPage != page + 1) {
                            throw invalidPagination();
                        }
                        linkedNext = target;
                    }
                    case "last" -> linkedLast = targetPage;
                    case "first" -> {
                        if (targetPage != 1) {
                            throw invalidPagination();
                        }
                    }
                    case "prev" -> {
                        if (targetPage != page - 1) {
                            throw invalidPagination();
                        }
                    }
                    default -> throw invalidPagination();
                }
            }
        }

        Integer nextNumber = next == null || next.isEmpty() ? null : positivePage(next);
        if (nextNumber != null && nextNumber != page + 1) {
            // Offset pages must advance exactly once; reject cycles and skipped pages.
            throw invalidPagination();
        }
        if (!links.isEmpty() && next != null
                && ((linkedNext != null) != (nextNumber != null))) {
            throw invalidPagination();
        }
        boolean hasNext = linkedNext != null || nextNumber != null;
        if (linkedLast != null && (linkedLast < page || (linkedLast > page) != hasNext)) {
            throw invalidPagination();
        }
        Integer totalPages = linkedLast;
        if (total != null) {
            totalPages = total.equals("0") ? 0 : positivePage(total);
        }
        // Missing headers cannot erase evidence established earlier in this traversal.
        if (knownTotalPages != null) {
            if (totalPages != null && !knownTotalPages.equals(totalPages)) {
                throw invalidPagination();
            }
            totalPages = knownTotalPages;
        }
        if (totalPages != null) {
            if ((totalPages == 0 && (page != 1 || response.body().length != 0 || hasNext))
                    || (totalPages > 0 && (totalPages < page || (totalPages > page) != hasNext))
                    || (linkedLast != null && !linkedLast.equals(totalPages))) {
                throw invalidPagination();
            }
        }
        if (linkedNext != null) {
            return new Pagination(linkedNext, totalPages);
        }
        if (nextNumber != null) {
            return new Pagination(UriComponentsBuilder.fromUri(response.uri())
                    .replaceQueryParam("page", nextNumber).build().toUri(), totalPages);
        }
        if (next == null && links.isEmpty() && totalPages == null && response.body().length == PAGE_SIZE) {
            // A full page with no continuation/end evidence is ambiguous.
            throw invalidPagination();
        }
        return new Pagination(null, totalPages);
    }

    private static URI validatePageLink(URI requestUri, String value) {
        try {
            URI next = requestUri.resolve(URI.create(value));
            if (!Objects.equals(requestUri.getScheme(), next.getScheme())
                    || requestUri.getHost() == null || next.getHost() == null
                    || !requestUri.getHost().equalsIgnoreCase(next.getHost())
                    || effectivePort(requestUri) != effectivePort(next)
                    || next.getRawUserInfo() != null || next.getRawFragment() != null
                    || !Objects.equals(requestUri.getRawPath(), next.getRawPath())) {
                throw invalidPagination();
            }
            var query = UriComponentsBuilder.fromUri(next).build().getQueryParams();
            boolean hasRouteParameters = query.keySet().equals(Set.of("page", "per_page", "id", "merge_request_iid"));
            if ((!query.keySet().equals(Set.of("page", "per_page")) && !hasRouteParameters)
                    || query.values().stream().anyMatch(values -> values.size() != 1)
                    || positivePage(query.getFirst("per_page")) != PAGE_SIZE) {
                throw invalidPagination();
            }
            // Match canonical route IDs against the trusted endpoint without narrowing long IDs to int.
            if (hasRouteParameters && !requestUri.getRawPath().endsWith(
                    "/projects/" + query.getFirst("id") + "/merge_requests/"
                            + query.getFirst("merge_request_iid") + "/versions")) {
                throw invalidPagination();
            }
            positivePage(query.getFirst("page"));
            return next;
        } catch (IllegalArgumentException exception) {
            // URI parser messages can embed an untrusted URL with credentials.
            throw invalidPagination();
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : ("https".equals(uri.getScheme()) ? 443 : 80);
    }

    private static String singleHeader(HttpHeaders headers, String name) {
        List<String> values = headers.getOrEmpty(name);
        if (values.size() > 1) {
            throw invalidPagination();
        }
        return values.isEmpty() ? null : values.getFirst();
    }

    private static int positivePage(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) {
            throw invalidPagination();
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidPagination();
        }
    }

    private static void validateIds(long... ids) {
        for (long id : ids) {
            if (id <= 0) {
                throw new IllegalArgumentException("Project ID, MR IID and version ID must be positive");
            }
        }
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalStateException invalidResponse() {
        return new IllegalStateException("GitLab diff response has missing or invalid required fields");
    }

    private static IllegalStateException invalidPagination() {
        return new IllegalStateException("GitLab diff version pagination is invalid");
    }

    private record ReadResponse<T>(T body, HttpHeaders headers, URI uri) {}

    private record Pagination(URI next, Integer totalPages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VersionResponse(
            Long id,
            @JsonProperty("base_commit_sha") String baseSha,
            @JsonProperty("start_commit_sha") String startSha,
            @JsonProperty("head_commit_sha") String headSha,
            String state,
            @JsonProperty("real_size") String realSize,
            List<FileResponse> diffs
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FileResponse(
            @JsonProperty("old_path") String oldPath,
            @JsonProperty("new_path") String newPath,
            String diff,
            @JsonProperty("new_file") Boolean newFile,
            @JsonProperty("deleted_file") Boolean deletedFile,
            @JsonProperty("renamed_file") Boolean renamedFile,
            Boolean collapsed,
            @JsonProperty("too_large") Boolean tooLarge,
            @JsonProperty("generated_file") Boolean generatedFile
    ) {}
}
