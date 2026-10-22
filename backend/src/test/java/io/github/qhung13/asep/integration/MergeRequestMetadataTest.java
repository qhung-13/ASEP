package io.github.qhung13.asep.integration;

import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

class MergeRequestMetadataTest {

    @Test
    void recordPreservesUnknownSourceProject() {
        MergeRequestMetadata metadata = metadata(OptionalLong.empty());

        assertEquals(42, metadata.projectId());
        assertEquals(OptionalLong.empty(), metadata.sourceProjectId());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void recordRejectsNonpositiveKnownSourceProject(long sourceProjectId) {
        assertThrowsExactly(IllegalArgumentException.class, () -> metadata(OptionalLong.of(sourceProjectId)));
    }

    @Test
    void recordRejectsNullSourceProjectContainer() {
        assertThrowsExactly(NullPointerException.class, () -> metadata(null));
    }

    private static MergeRequestMetadata metadata(OptionalLong sourceProjectId) {
        return new MergeRequestMetadata(42, 17, "Title", "opened", "feature", "main", Optional.empty(), sourceProjectId);
    }
}
