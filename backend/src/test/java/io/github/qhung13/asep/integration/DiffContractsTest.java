package io.github.qhung13.asep.integration;

import io.github.qhung13.asep.integration.MergeRequestMetadata.DiffReferences;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiffContractsTest {

    private static final DiffReferences REVISION = new DiffReferences(
            "a".repeat(40), "b".repeat(40), "c".repeat(40));

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void summaryRequiresPositiveVersionId(long id) {
        assertThrows(IllegalArgumentException.class, () -> new DiffVersionSummary(id, REVISION, "collected"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n"})
    void bothVersionContractsRequireNonblankState(String state) {
        Class<? extends RuntimeException> type = state == null
                ? NullPointerException.class : IllegalArgumentException.class;
        assertThrows(type, () -> new DiffVersionSummary(93, REVISION, state));
        assertThrows(type, () -> version(42, 17, 93, REVISION, state, Optional.empty(), List.of()));
    }

    @Test
    void preservesUnknownStateAndDescriptiveSizeWithoutClaimingCoverage() {
        assertEquals("future_gitlab_state", new DiffVersionSummary(93, REVISION, "future_gitlab_state").state());
        MergeRequestDiffVersion version = version(42, 17, 93, REVISION,
                "overflow", Optional.of("1000+"), List.of());
        assertEquals("overflow", version.state());
        assertEquals(Optional.of("1000+"), version.gitlabReportedSize());
        assertEquals(List.of(), version.files());
    }

    @ParameterizedTest
    @CsvSource({"0,17,93", "-1,17,93", "42,0,93", "42,-1,93", "42,17,0", "42,17,-1"})
    void detailRequiresPositiveIdentifiers(long projectId, long iid, long versionId) {
        assertThrows(IllegalArgumentException.class,
                () -> version(projectId, iid, versionId, REVISION, "collected", Optional.empty(), List.of()));
    }

    @Test
    void versionContractsRejectNullRevisionAndContainers() {
        assertThrows(NullPointerException.class, () -> new DiffVersionSummary(93, null, "collected"));
        assertThrows(NullPointerException.class,
                () -> version(42, 17, 93, null, "collected", Optional.empty(), List.of()));
        assertThrows(NullPointerException.class,
                () -> version(42, 17, 93, REVISION, "collected", null, List.of()));
        assertThrows(NullPointerException.class,
                () -> version(42, 17, 93, REVISION, "collected", Optional.empty(), null));
        List<ChangedFileDiff> nullElement = new ArrayList<>();
        nullElement.add(null);
        assertThrows(NullPointerException.class,
                () -> version(42, 17, 93, REVISION, "collected", Optional.empty(), nullElement));
    }

    @Test
    void filesAreDefensivelyCopiedAndUnmodifiable() {
        ChangedFileDiff file = file("original/path", "new/path", Optional.of("diff"));
        List<ChangedFileDiff> input = new ArrayList<>(List.of(file));
        MergeRequestDiffVersion version = version(42, 17, 93, REVISION, "collected", Optional.empty(), input);
        input.clear();

        assertEquals(List.of(file), version.files());
        assertThrows(UnsupportedOperationException.class, () -> version.files().clear());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n"})
    void changedFileRequiresBothPaths(String path) {
        assertThrows(IllegalArgumentException.class, () -> file(path, "new/path", Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> file("old/path", path, Optional.empty()));
    }

    @Test
    void changedFileRequiresOptionalContainers() {
        assertThrows(NullPointerException.class, () -> file("old", "new", null));
        assertThrows(NullPointerException.class, () -> new ChangedFileDiff("old", "new", Optional.empty(),
                false, false, false, false, false, null));
    }

    @Test
    void pathsAndDiffTextArePreservedWithoutNormalization() {
        ChangedFileDiff file = file(" ./old/../private path ", " ../new path ", Optional.of(" \n+source\r\n"));
        assertEquals(" ./old/../private path ", file.oldPath());
        assertEquals(" ../new path ", file.newPath());
        assertEquals(Optional.of(" \n+source\r\n"), file.diffText());
        assertNotEquals(file("old", "new", Optional.empty()), file("old", "new", Optional.of("")));
    }

    @Test
    void fileAndContainingVersionToStringDoNotExposePathsOrSource() {
        String oldPath = "SYNTHETIC_PRIVATE_OLD_PATH";
        String newPath = "SYNTHETIC_PRIVATE_NEW_PATH";
        String source = "SYNTHETIC_PRIVATE_SOURCE";
        ChangedFileDiff file = file(oldPath, newPath, Optional.of(source));
        MergeRequestDiffVersion version = version(42, 17, 93, REVISION,
                "collected", Optional.empty(), List.of(file));

        for (String output : List.of(file.toString(), version.toString())) {
            for (String privateValue : List.of(oldPath, newPath, source)) {
                assertFalse(output.contains(privateValue));
            }
        }
    }

    private static ChangedFileDiff file(String oldPath, String newPath, Optional<String> diff) {
        return new ChangedFileDiff(oldPath, newPath, diff, false, false, true, false, false, Optional.empty());
    }

    private static MergeRequestDiffVersion version(long projectId, long iid, long versionId,
            DiffReferences revision, String state, Optional<String> size, List<ChangedFileDiff> files) {
        return new MergeRequestDiffVersion(projectId, iid, versionId, revision, state, size, files);
    }
}
