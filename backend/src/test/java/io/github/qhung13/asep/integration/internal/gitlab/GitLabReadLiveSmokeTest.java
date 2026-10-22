package io.github.qhung13.asep.integration.internal.gitlab;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import io.github.qhung13.asep.integration.ChangedFileDiff;
import io.github.qhung13.asep.integration.DiffVersionSummary;
import io.github.qhung13.asep.integration.MergeRequestDiffVersion;
import io.github.qhung13.asep.integration.MergeRequestMetadata;
import io.github.qhung13.asep.integration.MergeRequestMetadata.DiffReferences;
import io.github.qhung13.asep.integration.RepositoryFileContent;
import io.github.qhung13.asep.integration.RepositoryFileReadException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner-run sandbox verification; normal test runs never construct a live client. */
@EnabledIfSystemProperty(named = "asep.live.gitlab", matches = "true")
@Isolated("Temporarily suppresses library logging while using a live credential")
class GitLabReadLiveSmokeTest {

    private static final long PROJECT_ID = 87786140L;
    private static final long MR_IID = 2L;
    private static final long VERSION_ID = 2125062951L;
    private static final String HEAD_SHA = "bb5a5531d7f4f95bf341740be99e120f524f4afc";

    @Test
    void readsSandboxRevisionAndNewFixtureThroughSecureAdapters() {
        String token = System.getenv("ASEP_GITLAB_READ_TOKEN");
        assertTrue(token != null && !token.isBlank(),
                "Live GitLab smoke test requires ASEP_GITLAB_READ_TOKEN in the environment");

        String stage = "secure client setup";
        try (QuietLibraryLogging ignored = new QuietLibraryLogging();
             GitLabReadHttpClientFactory factory = new GitLabReadHttpClientFactory()) {
            RestClient client = factory.createClient(token);
            stage = "metadata adapter";
            MergeRequestMetadata metadata = new GitLabRestReadAdapter(client)
                    .getMergeRequest(PROJECT_ID, MR_IID);
            // projectId is target provenance; the adapter also validates target_project_id.
            assertTrue(metadata.projectId() == PROJECT_ID, "MR target project must match the sandbox");
            assertTrue(metadata.iid() == MR_IID, "MR IID must match the sandbox");
            assertTrue("opened".equals(metadata.state()), "Sandbox MR must remain opened");
            assertTrue(metadata.sourceProjectId().isPresent(), "MR source project must be known");
            long sourceProjectId = metadata.sourceProjectId().getAsLong();
            assertTrue(sourceProjectId > 0, "MR source project must be positive");
            assertTrue(metadata.diffReferences().isPresent(), "MR revision must be available");
            DiffReferences revision = metadata.diffReferences().orElseThrow();
            assertTrue(HEAD_SHA.equals(revision.headSha()), "MR head must match the sandbox revision");
            System.out.println("GitLab live smoke: metadata adapter passed");

            stage = "diff version list";
            GitLabRestDiffReadAdapter diffs = new GitLabRestDiffReadAdapter(client);
            List<DiffVersionSummary> matches = diffs.listDiffVersions(PROJECT_ID, MR_IID).stream()
                    .filter(version -> version.versionId() == VERSION_ID
                            && version.revision().equals(revision))
                    .toList();
            assertTrue(matches.size() == 1, "Expected version and complete MR revision must match uniquely");
            DiffVersionSummary selected = matches.getFirst();
            System.out.println("GitLab live smoke: diff version list passed");

            stage = "diff version detail";
            MergeRequestDiffVersion detail = diffs.getDiffVersion(PROJECT_ID, MR_IID, selected.versionId());
            assertTrue(detail.projectId() == PROJECT_ID, "Diff target project must match the sandbox");
            assertTrue(detail.mergeRequestIid() == MR_IID, "Diff MR IID must match the sandbox");
            assertTrue(detail.versionId() == selected.versionId(), "Diff must be the selected version");
            assertTrue(detail.revision().equals(selected.revision()), "Diff revision must match the selected summary");
            assertTrue(detail.state().equals(selected.state()), "Diff state must match the selected summary");
            assertFalse(detail.files().isEmpty(), "Selected revision must contain changed-file evidence");
            // No fixture path was supplied: fail on ambiguity rather than choosing an arbitrary file.
            List<ChangedFileDiff> newFiles = detail.files().stream().filter(ChangedFileDiff::newFile).toList();
            assertTrue(newFiles.size() == 1, "Expected exactly one newly added sandbox fixture");
            ChangedFileDiff fixture = newFiles.getFirst();
            assertFalse(fixture.deletedFile() || fixture.renamedFile(), "Fixture must be a new surviving file");
            assertFalse(fixture.collapsed() || fixture.tooLarge(), "Fixture diff must be available in full");
            assertTrue(fixture.diffText().isPresent() && !fixture.diffText().orElseThrow().isBlank(),
                    "Fixture must have nonempty diff evidence");
            assertTrue(fixture.diffText().orElseThrow().lines()
                            .anyMatch(line -> line.startsWith("+") && !line.startsWith("+++")),
                    "Fixture diff must contain added-line evidence");
            System.out.println("GitLab live smoke: diff version detail passed");

            stage = "repository file adapter";
            RepositoryFileContent file = new GitLabRestRepositoryFileAdapter(client)
                    .readTextFile(sourceProjectId, selected.revision().headSha(), fixture.newPath());
            assertTrue(file.projectId() == sourceProjectId, "File must retain actual source project provenance");
            assertTrue(file.commitSha().equals(selected.revision().headSha()), "File must retain selected head SHA");
            // Boolean assertions prevent JUnit from rendering a path or source on mismatch.
            assertTrue(file.filePath().equals(fixture.newPath()), "File must retain the discovered fixture path");
            assertFalse(file.text().isBlank(), "Fixture source must be nonempty");
            assertTrue(StandardCharsets.UTF_8.newEncoder().canEncode(file.text()), "Fixture must be valid UTF-8 text");
            assertTrue(file.text().codePoints().noneMatch(character -> Character.isISOControl(character)
                            && character != '\t' && character != '\n' && character != '\r'),
                    "Fixture must contain supported text characters");
            assertTrue(file.byteSize() > 0
                            && file.byteSize() == file.text().getBytes(StandardCharsets.UTF_8).length,
                    "Fixture byte size must match its UTF-8 content");
            System.out.println("GitLab live smoke: repository file adapter passed");
            stage = "secure client close";
        } catch (RuntimeException exception) {
            // Never retain upstream messages, causes or suppressed exceptions in JUnit reports.
            throw new AssertionError("GitLab live smoke failed at " + stage + ": " + safeReason(exception));
        }
        System.out.println("GitLab live smoke: secure client closed; all live checks passed");
    }

    private static String safeReason(RuntimeException exception) {
        if (exception instanceof RepositoryFileReadException fileFailure) {
            return fileFailure.reason().name();
        }
        String message = exception.getMessage();
        if (message != null && message.matches("GitLab (MR|diff) read returned HTTP [0-9]{3}")) {
            return "HTTP " + message.substring(message.length() - 3);
        }
        return "read or client setup failed; upstream details suppressed";
    }

    /** Uses the existing Logback dependency; also blocks explicitly enabled HTTP wire/header logs. */
    private static final class QuietLibraryLogging implements AutoCloseable {

        private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        private final TurboFilter filter = new TurboFilter() {
            @Override
            public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                                      Object[] parameters, Throwable throwable) {
                return FilterReply.DENY;
            }
        };

        private QuietLibraryLogging() {
            filter.start();
            // Run before any existing filter that might force verbose transport logging.
            context.getTurboFilterList().add(0, filter);
        }

        @Override
        public void close() {
            context.getTurboFilterList().remove(filter);
            filter.stop();
        }
    }
}
