package io.github.qhung13.asep.integration;

/** Trusted Application read boundary. No URL, credential or attachment capability is accepted. */
public interface JiraTicketReader {
    JiraTicketSnapshot read(Selection selection, Limits limits);

    record Selection(String siteId, String projectId, String issueId) {
        public Selection {
            if (siteId == null || !siteId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                    || projectId == null || !projectId.matches("[1-9][0-9]{0,18}")
                    || issueId == null || !issueId.matches("[1-9][0-9]{0,18}")) {
                throw new IllegalArgumentException("Invalid Jira selection identity");
            }
        }
    }

    /** Explicit trusted profile, not invented production retention or size defaults. */
    record Limits(int responseBytes, int summaryBytes, int descriptionBytes, int adfDepth, int adfNodes) {
        public Limits {
            if (responseBytes < 1 || summaryBytes < 1 || descriptionBytes < 1 || adfDepth < 1 || adfNodes < 1
                    || summaryBytes > responseBytes || descriptionBytes > responseBytes) {
                throw new IllegalArgumentException("Invalid Jira read limits");
            }
        }
    }
}
