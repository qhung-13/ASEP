package io.github.qhung13.asep.integration.internal.gitlab;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.qhung13.asep.integration.GitLabMergeRequestReader;
import io.github.qhung13.asep.integration.MergeRequestMetadata;
import io.github.qhung13.asep.integration.MergeRequestMetadata.DiffReferences;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public final class GitLabRestReadAdapter
        implements GitLabMergeRequestReader {

    private final RestClient restClient;

    public GitLabRestReadAdapter(RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient);
    }

    @Override
    public MergeRequestMetadata getMergeRequest(
            long projectId,
            long mergeRequestIid
    ) {
        if (projectId <= 0 || mergeRequestIid <= 0) {
            throw new IllegalArgumentException(
                    "Project ID and MR IID must be positive"
            );
        }

        GitLabMrResponse response;

        try {
            response = restClient.get()
                    .uri(
                            "/projects/{projectId}/merge_requests/{iid}",
                            projectId,
                            mergeRequestIid
                    )
                    .retrieve()
                    .onStatus(status -> status.value() != 200, (request, httpResponse) -> {
                        throw new IllegalStateException(
                                "GitLab MR read returned HTTP " + httpResponse.getStatusCode().value()
                        );
                    })
                    .body(GitLabMrResponse.class);
        } catch (RestClientResponseException exception) {
            throw new IllegalStateException(
                    "GitLab MR read returned HTTP "
                            + exception.getStatusCode().value()
            );
        } catch (RestClientException exception) {
            throw new IllegalStateException(
                    "GitLab MR read failed"
            );
        }

        if (response == null
                || response.projectId() == null
                || response.targetProjectId() == null
                || response.iid() == null
                || response.title() == null
                || response.state() == null
                || response.sourceBranch() == null
                || response.targetBranch() == null) {
            throw new IllegalStateException(
                    "GitLab MR response is missing required fields"
            );
        }

        if (response.projectId() != projectId
                || response.targetProjectId() != projectId
                || response.iid() != mergeRequestIid) {
            throw new IllegalStateException(
                    "GitLab MR response identifiers do not match request"
            );
        }

        if (response.sourceProjectId() != null && response.sourceProjectId() <= 0) {
            throw new IllegalStateException("GitLab MR response source project ID must be positive");
        }

        return new MergeRequestMetadata(
                response.projectId(),
                response.iid(),
                response.title(),
                response.state(),
                response.sourceBranch(),
                response.targetBranch(),
                mapDiffReferences(response.diffRefs()),
                response.sourceProjectId() == null ? OptionalLong.empty() : OptionalLong.of(response.sourceProjectId())
        );
    }

    private static Optional<DiffReferences> mapDiffReferences(
            GitLabDiffRefs response
    ) {
        if (response == null) {
            return Optional.empty();
        }

        boolean missingBase = isMissing(response.baseSha());
        boolean missingStart = isMissing(response.startSha());
        boolean missingHead = isMissing(response.headSha());

        if (missingBase && missingStart && missingHead) {
            return Optional.empty();
        }

        if (missingBase || missingStart || missingHead) {
            throw new IllegalStateException(
                    "GitLab MR diff references are incomplete"
            );
        }

        return Optional.of(new DiffReferences(
                response.baseSha(),
                response.startSha(),
                response.headSha()
        ));
    }

    private static boolean isMissing(String value) {
        return value == null || value.isBlank();
    }

    private record GitLabMrResponse(
            @JsonProperty("project_id") Long projectId,
            Long iid,
            @JsonProperty("source_project_id") Long sourceProjectId,
            @JsonProperty("target_project_id") Long targetProjectId,
            String title,
            String state,
            @JsonProperty("source_branch") String sourceBranch,
            @JsonProperty("target_branch") String targetBranch,
            @JsonProperty("diff_refs") GitLabDiffRefs diffRefs
    ) {}

    private record GitLabDiffRefs(
            @JsonProperty("base_sha") String baseSha,
            @JsonProperty("start_sha") String startSha,
            @JsonProperty("head_sha") String headSha
    ) {}
}
