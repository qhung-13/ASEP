package io.github.qhung13.asep.codedelivery;

/** Rendering accepts validated IDs, never a ticket, prompt, branch label or caller's free text. */
public record ControlledDraft(String title, String body) {
    public static ControlledDraft render(DeliveryIntent intent) {
        return new ControlledDraft("Draft: ASEP delivery " + intent.operationId(),
                "Synthetic verified candidate: `" + intent.candidateCommitSha() + "`\n"
                + "Manifest: `" + intent.manifestSha256() + "`\n"
                + "Operation: `" + intent.operationId() + "`\nHuman review and merge required.");
    }
    public ControlledDraft {
        if (title == null || body == null || !title.matches("Draft: ASEP delivery [0-9a-f-]{36}")
                || !body.matches("Synthetic verified candidate: `[0-9a-f]{40}`\\nManifest: `[0-9a-f]{64}`\\nOperation: `"
                        + java.util.regex.Pattern.quote(title.substring("Draft: ASEP delivery ".length()))
                        + "`\\nHuman review and merge required\\.")) {
            throw new IllegalArgumentException("Unapproved Draft MR rendering");
        }
    }
}
