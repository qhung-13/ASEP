package io.github.qhung13.asep.integration;

import java.util.Objects;
import java.util.Optional;

public record ChangedFileDiff(
        String oldPath,
        String newPath,
        Optional<String> diffText,
        boolean newFile,
        boolean deletedFile,
        boolean renamedFile,
        boolean collapsed,
        boolean tooLarge,
        Optional<Boolean> generatedFile
) {

    public ChangedFileDiff {
        if (oldPath == null || oldPath.isBlank()
                || newPath == null || newPath.isBlank()) {
            throw new IllegalArgumentException(
                    "Changed file paths must not be blank"
            );
        }

        Objects.requireNonNull(diffText, "diffText");
        Objects.requireNonNull(generatedFile, "generatedFile");
    }

    @Override
    public String toString() {
        return "ChangedFileDiff["
                + "diffTextPresent=" + diffText.isPresent()
                + ", newFile=" + newFile
                + ", deletedFile=" + deletedFile
                + ", renamedFile=" + renamedFile
                + ", collapsed=" + collapsed
                + ", tooLarge=" + tooLarge
                + ", generatedFileKnown=" + generatedFile.isPresent()
                + "]";
    }
}