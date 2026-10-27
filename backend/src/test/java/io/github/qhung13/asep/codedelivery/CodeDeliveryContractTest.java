package io.github.qhung13.asep.codedelivery;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CodeDeliveryContractTest {
    static DeliveryIntent intent() {
        return new DeliveryIntent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7,
                "synthetic-owner", "synthetic-policy", 2, "synthetic-site", "10001", "10002",
                "a".repeat(64), "42", "0".repeat(40), "main", "1".repeat(40), "2".repeat(40),
                "3".repeat(40), "b".repeat(64), UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64), "d".repeat(64));
    }
    static DeliveryIntent change(DeliveryIntent intent, String field, String value) {
        var node = (tools.jackson.databind.node.ObjectNode) JdbcDeliveryLedger.JSON.valueToTree(intent);
        node.put(field, value);
        return JdbcDeliveryLedger.JSON.treeToValue(node, DeliveryIntent.class);
    }
    @Test void hashIsVersionedStableAndBindsAllIdentityFields() {
        var intent = intent();
        assertEquals(intent.bodyHash(), JdbcDeliveryLedger.JSON.readValue(JdbcDeliveryLedger.JSON.writeValueAsBytes(intent), DeliveryIntent.class).bodyHash());
        for (var field : new String[]{"jiraSnapshotSha256", "manifestSha256", "verifierProfileSha256", "safeCiProfileSha256"}) {
            assertNotEquals(intent.bodyHash(), change(intent, field, "e".repeat(64)).bodyHash());
        }
        assertNotEquals(intent.bodyHash(), change(intent, "baseSha", "4".repeat(40)).bodyHash());
        assertNotEquals(intent.bodyHash(), change(intent, "candidateCommitSha", "5".repeat(40)).bodyHash());
        assertNotEquals(intent.bodyHash(), change(intent, "repositoryId", "43").bodyHash());
        assertEquals("asep/delivery/" + intent.operationId(), intent.sourceBranch());
    }
    @ParameterizedTest @ValueSource(strings = {"/merge", " /approve", "\n/delete", "hello\n/label ~secret", "arbitrary body"})
    void rejectsCallerBodyAndQuickActions(String untrusted) {
        var intent = intent();
        assertThrows(IllegalArgumentException.class, () -> new ControlledDraft(ControlledDraft.render(intent).title(), untrusted));
        var node = (tools.jackson.databind.node.ObjectNode) JdbcDeliveryLedger.JSON.valueToTree(intent);
        node.put("body", untrusted);
        assertThrows(RuntimeException.class, () -> JdbcDeliveryLedger.JSON.treeToValue(node, DeliveryIntent.class));
        assertThrows(RuntimeException.class, () -> change(intent, "actorId", untrusted));
    }
    @Test void renderingAndCapabilityExposeNoCredentialOrLiveTransport() {
        var intent = intent();
        var draft = ControlledDraft.render(intent);
        assertTrue(draft.title().startsWith("Draft: "));
        assertFalse(draft.body().lines().anyMatch(line -> line.stripLeading().startsWith("/")));
        assertEquals(0, CodeDeliveryExecutor.class.getAnnotations().length);
        assertFalse(DeliveryChecks.unavailable().access("42").writeAllowed());
        assertNull(DeliveryChecks.unavailable().current(intent.operationId()));
        for (var field : DeliveryIntent.class.getRecordComponents()) {
            assertFalse(field.getName().toLowerCase().matches(".*(password|token|credential|body|description).*"));
        }
        for (var field : FakeGitLab.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().startsWith("java.net"));
            assertFalse(field.getName().toLowerCase().matches(".*(password|token|credential).*"));
        }
    }
}
