package io.github.qhung13.asep.integration;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepositoryFileContractsTest {

    private static final String SHA = "a".repeat(40);

    @Test
    void preservesProvenancePathAndTextWithAccurateUtf8Size() {
        String path = "src/ spaced %?# +.txt ";
        String text = "\uFEFFXin chào 🙂\r\n\tline two\n";
        RepositoryFileContent content = new RepositoryFileContent(91, SHA, path, text,
                text.getBytes(StandardCharsets.UTF_8).length);

        assertEquals(91, content.projectId());
        assertEquals(SHA, content.commitSha());
        assertEquals(path, content.filePath());
        assertEquals(text, content.text());
        assertEquals(text.getBytes(StandardCharsets.UTF_8).length, content.byteSize());
    }

    @Test
    void emptyTextIsValid() {
        assertEquals("", new RepositoryFileContent(42, SHA, "empty.txt", "", 0).text());
    }

    @Test
    void acceptsFullSha256WithoutChangingCase() {
        String sha = "ABCD".repeat(16);
        assertEquals(sha, new RepositoryFileContent(42, sha, "file", "x", 1).commitSha());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void requiresPositiveProject(long project) {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryFileContent(project, SHA, "file", "", 0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"HEAD", "main", "abc123"})
    void requiresFullImmutableCommit(String sha) {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryFileContent(42, sha, "file", "", 0));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n"})
    void requiresPath(String path) {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryFileContent(42, SHA, path, "", 0));
    }

    @Test
    void requiresText() {
        assertThrows(NullPointerException.class, () -> new RepositoryFileContent(42, SHA, "file", null, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 3})
    void byteSizeMustMatchUtf8RatherThanCharacterCount(int size) {
        assertThrows(IllegalArgumentException.class, () -> new RepositoryFileContent(42, SHA, "file", "é", size));
    }

    @Test
    void toStringDoesNotExposeSourceOrPath() {
        String path = "SYNTHETIC_PRIVATE_PATH";
        String source = "SYNTHETIC_PRIVATE_SOURCE FAKE_TEST_CREDENTIAL";
        String output = new RepositoryFileContent(42, SHA, path, source,
                source.getBytes(StandardCharsets.UTF_8).length).toString();
        assertFalse(output.contains(path));
        assertFalse(output.contains("SYNTHETIC_PRIVATE_SOURCE"));
        assertFalse(output.contains("FAKE_TEST_CREDENTIAL"));
    }
}
