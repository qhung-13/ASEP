package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.codedelivery.DeliveryRecord.State;
import io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.ObjectType;
import io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.Prepared;
import io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.Profile;
import io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.Receipts;
import io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.Seal;
import io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.Verification;
import io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.Branch;
import io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.MergeRequest;
import io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.Observation;
import io.github.qhung13.asep.integration.GitLabTestProjectReader.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import static io.github.qhung13.asep.codedelivery.DeliveryRecord.State;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;
import static io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the ACTUAL VN-T14 coordinator with PostgreSQL + a test-only in-memory port.
 * No GitLab HTTP client, credential, Spring wiring, trusted receipt issuer or real remote effect.
 */
class TestProjectDeliveryCoordinatorPostgresTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    private static final String OTHER_HEAD = "9".repeat(40);
    private PGSimpleDataSource database;
    private Fixture fixture;

    @BeforeEach void ownedPostgresOnly() throws Exception {
        assumeTrue(Boolean.getBoolean("asep.delivery.postgres"), "Use scripts/ci_verify_delivery.sh");
        String url = System.getenv("ASEP_DELIVERY_JDBC_URL");
        assertEquals("true", System.getenv("ASEP_DELIVERY_DISPOSABLE"));
        assertNotNull(url);
        assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/asep_delivery_test_[0-9a-f]{32}"));
        assertEquals("asep_delivery_runtime", System.getenv("ASEP_DELIVERY_DB_USER"));
        String nonce = System.getenv("ASEP_DELIVERY_NONCE");
        assertNotNull(nonce);
        assertTrue(nonce.matches("[0-9a-f]{64}"));
        database = new PGSimpleDataSource();
        database.setUrl(url);
        database.setUser(System.getenv("ASEP_DELIVERY_DB_USER"));
        database.setPassword(System.getenv("ASEP_DELIVERY_DB_PASSWORD"));
        database.setConnectTimeout(3);
        database.setSocketTimeout(5);
        database.setOptions("-c statement_timeout=4000 -c lock_timeout=3000");
        try (var db = database.getConnection(); var query = db.prepareStatement("""
                SELECT current_user='asep_delivery_runtime' AND database_name=current_database()
                    AND server_started_at=pg_postmaster_start_time() AND nonce=?
                FROM asep_delivery_test_proof.instance WHERE singleton
                """)) {
            query.setString(1, nonce);
            try (var rows = query.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getBoolean(1));
                assertFalse(rows.next());
            }
        }
        fixture = new Fixture();
    }

    private final class Fixture {
        final UUID operationId = UUID.randomUUID();
        final UUID runId;
        final UUID attemptId = UUID.randomUUID();
        final UUID grantId = UUID.randomUUID();
        final UUID sealId = UUID.randomUUID();
        final UUID verifierId = UUID.randomUUID();
        final Profile profile = new Profile("1".repeat(64), "2".repeat(64), "3".repeat(64), 2, Duration.ofMinutes(5));
        final Map<String, byte[]> objects = new HashMap<>();
        final TestProjectDeliveryRequest request;
        final TestProjectDeliveryGate gate;
        final TestProjectDeliveryPreparation preparation;
        final FakeRemote remote = new FakeRemote();
        boolean grantAvailable = true;
        boolean sealAvailable = true;

        Fixture() { this(UUID.randomUUID()); }
        Fixture(UUID runId) {
            this.runId = runId;
            String originalTree = put(ObjectType.TREE, new byte[0]);
            String base = commit(originalTree, null, "Base test fixture\n");
            String blob = put(ObjectType.BLOB, probeContent(operationId));
            String path = "vn-t14-probe-" + operationId + ".txt";
            byte[] prefix = ("100644 " + path + "\0").getBytes(StandardCharsets.US_ASCII);
            byte[] treeBytes = new byte[prefix.length + 20];
            System.arraycopy(prefix, 0, treeBytes, 0, prefix.length);
            System.arraycopy(HexFormat.of().parseHex(blob), 0, treeBytes, prefix.length, 20);
            String tree = put(ObjectType.TREE, treeBytes);
            String candidate = commit(tree, base, "VN-T14 isolated test " + operationId + "\n");
            var partial = new TestProjectDeliveryRequest(operationId, runId, attemptId, 1, grantId,
                    "synthetic-only", 1, base, base, candidate, tree, "0".repeat(64), sealId,
                    verifierId, profile.profileSha256(), "4".repeat(64));
            request = new TestProjectDeliveryRequest(operationId, runId, attemptId, 1, grantId,
                    "synthetic-only", 1, base, base, candidate, tree, manifestHash(partial), sealId,
                    verifierId, profile.profileSha256(), "4".repeat(64));
            var seal = new Seal(sealId, runId, attemptId, base, candidate, tree, request.manifestSha256(),
                    NOW.minusSeconds(5), NOW.minusSeconds(4), NOW.plusSeconds(60));
            var verified = new Verification(verifierId, runId, attemptId, sealId, request.manifestSha256(),
                    profile.profileSha256(), profile.harnessSha256(), profile.imageSha256(), "5".repeat(64),
                    2, 2, 0, 0, 0, false, NOW.minusSeconds(1), NOW.plusSeconds(60));
            preparation = new TestProjectDeliveryPreparation((type, sha, bound) -> {
                byte[] bytes = objects.get(type + ":" + sha);
                return bytes == null ? null : bytes.clone();
            }, new Receipts() {
                @Override public Seal seal(UUID id) { return sealAvailable && sealId.equals(id) ? seal : null; }
                @Override public Verification verification(UUID id) {
                    return sealAvailable && verifierId.equals(id) ? verified : null;
                }
            }, profile, Clock.fixed(NOW, ZoneOffset.UTC));
            gate = new TestProjectDeliveryGate((r, inspection) -> {
                if (!grantAvailable) return new TestProjectDeliveryGate.Evidence(EnumSet.noneOf(TestProjectDeliveryGate.Requirement.class), null);
                var grant = new TestProjectDeliveryGate.OwnerGrant(grantId, operationId, request.bodyHash(),
                        request.projectId(), "qhung-13/asep-m01-sandbox", request.sourceBranch(), request.targetBranch());
                return new TestProjectDeliveryGate.Evidence(EnumSet.allOf(TestProjectDeliveryGate.Requirement.class), grant);
            }, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(30));
            remote.targetHead = request.expectedTargetSha();
        }

        String put(ObjectType type, byte[] bytes) {
            String sha = gitId(type, bytes);
            objects.put(type + ":" + sha, bytes.clone());
            return sha;
        }
        String commit(String tree, String parent, String message) {
            return put(ObjectType.COMMIT, ("tree " + tree + "\n"
                    + (parent == null ? "" : "parent " + parent + "\n")
                    + "author Fixture <fixture@invalid> 1793145600 +0000\n"
                    + "committer Fixture <fixture@invalid> 1793145600 +0000\n\n" + message)
                    .getBytes(StandardCharsets.US_ASCII));
        }
        JdbcTestProjectDeliveryLedger ledger() { return new JdbcTestProjectDeliveryLedger(database); }
        TestProjectDeliveryCoordinator coordinator() {
            return new TestProjectDeliveryCoordinator(ledger(), gate, preparation,
                    remote.readPort(), remote.writePort());
        }

        final class FakeRemote {
            String targetHead;
            Branch branch;
            final List<MergeRequest> mrs = new ArrayList<>();
            boolean readable = true;
            boolean failInspect;
            boolean branchComplete = true;
            boolean mrListComplete = true;
            boolean losePushReply;
            boolean loseMrReply;
            Runnable onThirdInspect = () -> {};
            Runnable afterPush = () -> {};
            Runnable afterMr = () -> {};
            int inspectCount;
            int pushWrites;
            int mrWrites;
            TestProjectDeliveryCoordinator.ReadPort readPort() {
                return new TestProjectDeliveryCoordinator.ReadPort() {
                    @Override public Inspection inspect(TestProjectDeliveryRequest request) {
                        return FakeRemote.this.inspect(request);
                    }
                    @Override public Observation read(TestProjectDeliveryRequest request) {
                        return FakeRemote.this.read(request);
                    }
                };
            }
            TestProjectDeliveryCoordinator.WritePort writePort() {
                return new TestProjectDeliveryCoordinator.WritePort() {
                    @Override public void push(TestProjectDeliveryRequest request, Prepared candidate) {
                        FakeRemote.this.push(request, candidate);
                    }
                    @Override public void createDraft(TestProjectDeliveryRequest request, Prepared candidate) {
                        FakeRemote.this.createDraft(request, candidate);
                    }
                };
            }
            Inspection inspect(TestProjectDeliveryRequest request) {
                if (failInspect) throw new IllegalStateException("SECRET_INSPECTION");
                inspectCount++;
                if (inspectCount == 3) onThirdInspect.run();
                var empty = new CollectionObservation(0, true);
                return new Inspection(request.projectId(), "main",
                        new Project(request.projectId(), "qhung-13/asep-m01-sandbox", "private", false,
                                "main", "disabled", false, 30),
                        new Target("main", targetHead, true, false), empty, empty, empty, empty, empty,
                        List.of(), java.util.Set.of(), NOW, NOW);
            }
            Observation read(TestProjectDeliveryRequest request) {
                return new Observation(readable, branchComplete && readable, readable ? branch : null,
                        mrListComplete && readable, readable ? List.copyOf(mrs) : List.of(),
                        readable ? targetHead : null);
            }
            void push(TestProjectDeliveryRequest r, Prepared candidate) {
                assertEquals(request, r);
                assertEquals(request, candidate.request());
                if (branch != null) throw new IllegalStateException("Fake collision");
                branch = new Branch(r.projectId(), r.sourceBranch(), r.candidateCommitSha());
                pushWrites++;
                afterPush.run();
                if (losePushReply) throw new IllegalStateException("SIMULATED_LOST_PUSH_REPLY");
            }
            void createDraft(TestProjectDeliveryRequest r, Prepared candidate) {
                assertEquals(request, r);
                assertEquals(TestProjectDeliveryPreparation.render(r), candidate.draft());
                if (branch == null || !r.candidateCommitSha().equals(branch.head()))
                    throw new IllegalStateException("Fake wrong head");
                mrs.add(new MergeRequest(r.projectId(), r.projectId(), r.projectId(), 1000L + mrWrites,
                        r.sourceBranch(), r.targetBranch(), branch.head(), true, candidate.draft().title(),
                        candidate.draft().body()));
                mrWrites++;
                afterMr.run();
                if (loseMrReply) throw new IllegalStateException("SIMULATED_LOST_MR_REPLY");
            }
        }
    }

    @Test void actualCoordinatorPersistsCompleteFlowAndNeverResends() {
        var first = fixture.coordinator().runOnce(fixture.request);
        assertTrue(first.exactRemoteCandidateObserved());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(1, fixture.remote.mrWrites);
        assertEquals(first, fixture.coordinator().runOnce(fixture.request));
        assertEquals(first, fixture.ledger().find(fixture.operationId));
        assertEquals(5, fixture.ledger().pendingCallbacks(fixture.operationId).size());
    }

    @Test void failedPushResponseAndUnreadableLookupCannotCauseBlindResend() {
        fixture.remote.losePushReply = true;
        fixture.remote.afterPush = () -> fixture.remote.readable = false;
        var unknown = fixture.coordinator().runOnce(fixture.request);
        assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, unknown.push());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);
        fixture.remote.readable = true;
        var recovered = fixture.coordinator().runOnce(fixture.request);
        assertTrue(recovered.exactRemoteCandidateObserved());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(1, fixture.remote.mrWrites);
    }

    @Test void revokedGrantAfterCommittedClaimNeverReissuesPush() {
        fixture.remote.onThirdInspect = () -> fixture.grantAvailable = false;
        assertThrows(DeliveryException.class, () -> fixture.coordinator().runOnce(fixture.request));
        var claimed = fixture.ledger().find(fixture.operationId);
        assertTrue(claimed.pushClaimed());
        assertEquals(State.IN_PROGRESS, claimed.push());
        assertEquals(0, fixture.remote.pushWrites);
        fixture.grantAvailable = true;
        var resumed = fixture.coordinator().runOnce(fixture.request);
        assertEquals(State.UNKNOWN, resumed.push());
        assertEquals(0, fixture.remote.pushWrites);
    }

    @Test void targetMovesAfterCommittedClaimNoPushAndResidualRaceIsSticky() {
        fixture.remote.onThirdInspect = () -> fixture.remote.targetHead = OTHER_HEAD;
        assertThrows(DeliveryException.class, () -> fixture.coordinator().runOnce(fixture.request));
        assertTrue(fixture.ledger().find(fixture.operationId).pushClaimed());
        assertEquals(0, fixture.remote.pushWrites);
        var resumed = fixture.coordinator().reconcileOnly(fixture.request);
        assertTrue(resumed.residualRace());
        assertEquals(0, fixture.remote.pushWrites);
    }

    @Test void missingTrustedSealOrVerifierPreventsEvenInitialAdmission() {
        fixture.sealAvailable = false;
        assertThrows(IllegalStateException.class, () -> fixture.coordinator().runOnce(fixture.request));
        assertNoDurableAdmission(fixture);
        assertEquals(0, fixture.remote.pushWrites);
    }

    @Test void blockedCollisionCannotRearmAfterBranchDisappears() {
        fixture.remote.branch = new Branch(
                fixture.request.projectId(), fixture.request.sourceBranch(), OTHER_HEAD);

        var blocked = fixture.coordinator().runOnce(fixture.request);
        assertEquals(State.BLOCKED, blocked.push());
        assertFalse(blocked.pushClaimed());
        assertEquals(0, fixture.remote.pushWrites);

        // External branch deletion must not reauthorize the same operation.
        fixture.remote.branch = null;

        var replayed = fixture.coordinator().runOnce(fixture.request);
        assertEquals(State.BLOCKED, replayed.push());
        assertFalse(replayed.pushClaimed());
        assertEquals(0, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);
    }

    @Test void preexistingSourceCollisionCannotBeOverwritten() {
        fixture.remote.branch = new Branch(fixture.request.projectId(), fixture.request.sourceBranch(), OTHER_HEAD);
        var blocked = fixture.coordinator().runOnce(fixture.request);
        assertEquals(State.BLOCKED, blocked.push());
        assertFalse(blocked.pushClaimed());
        assertEquals(0, fixture.remote.pushWrites);
    }

    @Test void blockedMrCollisionCannotRearmAfterExternalMrDisappears() {
        // An unrelated MR appears after push, before ASEP claims Draft MR creation.
        fixture.remote.afterPush = () -> fixture.remote.mrs.add(
                new MergeRequest(
                        fixture.request.projectId(),
                        fixture.request.projectId(),
                        fixture.request.projectId(),
                        7000L,
                        fixture.request.sourceBranch(),
                        fixture.request.targetBranch(),
                        fixture.request.candidateCommitSha(),
                        true,
                        "Draft: unrelated external MR",
                        "Not ASEP-owned"
                )
        );

        var blocked = fixture.coordinator().runOnce(fixture.request);

        assertEquals(State.CONFIRMED, blocked.push());
        assertTrue(blocked.pushClaimed());
        assertEquals(State.BLOCKED, blocked.mergeRequest());
        assertFalse(blocked.mrClaimed());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);

        // External deletion must not reauthorize this blocked operation.
        fixture.remote.mrs.clear();

        for (int replay = 0; replay < 2; replay++) {
            var recovered = fixture.coordinator().runOnce(fixture.request);

            assertEquals(State.CONFIRMED, recovered.push());
            assertEquals(State.BLOCKED, recovered.mergeRequest());
            assertFalse(recovered.mrClaimed());
            assertEquals(1, fixture.remote.pushWrites);
            assertEquals(0, fixture.remote.mrWrites);
        }
    }

    @Test void lostMrResponseThenReadRecoveryPreservesSingleIid() {
        fixture.remote.loseMrReply = true;
        fixture.remote.afterMr = () -> fixture.remote.readable = false;
        var unknown = fixture.coordinator().runOnce(fixture.request);
        assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, unknown.mergeRequest());
        assertEquals(1, fixture.remote.mrWrites);
        fixture.remote.readable = true;
        var recovered = fixture.coordinator().runOnce(fixture.request);
        assertTrue(recovered.exactRemoteCandidateObserved());
        assertEquals(java.util.Set.of(1000L), recovered.knownMrIids());
        assertEquals(1, fixture.remote.mrWrites);
    }

    @Test void duplicateAttributedMrsRemainUnknownAndRetainBothIdentifiers() {
        fixture.remote.afterMr = () -> {
            var first = fixture.remote.mrs.getFirst();
            fixture.remote.mrs.add(new MergeRequest(first.projectId(), first.sourceProjectId(),
                    first.targetProjectId(), 2000L, first.source(), first.target(), first.head(),
                    first.draft(), first.title(), first.body()));
        };
        var ambiguous = fixture.coordinator().runOnce(fixture.request);
        assertEquals(State.UNKNOWN, ambiguous.mergeRequest());
        assertEquals(java.util.Set.of(1000L, 2000L), ambiguous.knownMrIids());
        assertEquals(1, fixture.remote.mrWrites);
        fixture.coordinator().runOnce(fixture.request);
        assertEquals(1, fixture.remote.mrWrites);
    }

    @Test void incompleteReadDoesNotGrantAnySendClaim() {
        fixture.remote.mrListComplete = false;
        var blocked = fixture.coordinator().runOnce(fixture.request);
        assertFalse(blocked.pushClaimed());
        assertEquals(0, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);
        fixture.remote.mrListComplete = true;
        assertTrue(fixture.coordinator().runOnce(fixture.request).exactRemoteCandidateObserved());
    }

    @Test void unreadablePostClaimInspectionCannotAuthorizeAPushOrRestartResend() {
        fixture.remote.onThirdInspect = () -> fixture.remote.readable = false;
        var unknown = fixture.coordinator().runOnce(fixture.request);
        assertTrue(unknown.pushClaimed());
        assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, unknown.push());
        assertEquals(0, fixture.remote.pushWrites);
        fixture.remote.readable = true;
        assertEquals(State.UNKNOWN, fixture.coordinator().runOnce(fixture.request).push());
        assertEquals(0, fixture.remote.pushWrites);
    }

    @Test void readOnlyReconciliationRejectsMismatchedIdentityWithoutSending() {
        fixture.coordinator().runOnce(fixture.request);
        var other = new TestProjectDeliveryRequest(fixture.request.operationId(), UUID.randomUUID(),
                fixture.request.attemptId(), fixture.request.runVersion(), fixture.request.ownerGrantId(),
                fixture.request.policyId(), fixture.request.policyEpoch(), fixture.request.baseSha(),
                fixture.request.expectedTargetSha(), fixture.request.candidateCommitSha(),
                fixture.request.candidateTreeSha(), fixture.request.manifestSha256(),
                fixture.request.sealReceiptId(), fixture.request.verificationReceiptId(),
                fixture.request.verifierProfileSha256(), fixture.request.safeCiProfileSha256());
        assertThrows(DeliveryException.class, () -> fixture.coordinator().reconcileOnly(other));
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(1, fixture.remote.mrWrites);
    }

    private static void assertNoDurableAdmission(Fixture f) {
        var missing = assertThrows(DeliveryException.class,
                () -> f.ledger().find(f.operationId));
        assertEquals(DeliveryException.Kind.UNAVAILABLE, missing.kind());
        assertTrue(f.ledger().pendingCallbacks(f.operationId).isEmpty());
    }

    @Test void revokedOwnerGrantCannotReserveOperationIdentityOrSend() {
        fixture.grantAvailable = false;
        var blocked = assertThrows(DeliveryException.class,
                () -> fixture.coordinator().runOnce(fixture.request));
        assertEquals(DeliveryException.Kind.INVALID, blocked.kind());
        assertNoDurableAdmission(fixture);
        assertEquals(0, fixture.remote.pushWrites);
        assertEquals(0, fixture.remote.mrWrites);
        fixture.grantAvailable = true;
        assertTrue(fixture.coordinator().runOnce(fixture.request).exactRemoteCandidateObserved());
    }

    @Test void deniedOperationCannotReserveRunIdentityForAnotherAuthorizedOperation() {
        fixture.grantAvailable = false;
        assertThrows(DeliveryException.class,
                () -> fixture.coordinator().runOnce(fixture.request));
        assertNoDurableAdmission(fixture);
        var other = new Fixture(fixture.runId);
        assertTrue(other.coordinator().runOnce(other.request).exactRemoteCandidateObserved());
        assertNoDurableAdmission(fixture);
    }

    @Test void wrongTargetAtAdmissionCannotOccupyLedgerAndCanLaterBeRevalidated() {
        fixture.remote.targetHead = OTHER_HEAD;
        assertThrows(DeliveryException.class,
                () -> fixture.coordinator().runOnce(fixture.request));
        assertNoDurableAdmission(fixture);
        fixture.remote.targetHead = fixture.request.expectedTargetSha();
        assertTrue(fixture.coordinator().runOnce(fixture.request).exactRemoteCandidateObserved());
    }

    @Test void readOnlyRecoveryAfterRevocationDoesNotNeedOrInvokeWriter() {
        fixture.remote.loseMrReply = true;
        fixture.remote.afterMr = () -> fixture.remote.readable = false;
        var unknown = fixture.coordinator().runOnce(fixture.request);
        assertEquals(State.MANUAL_RECONCILIATION_REQUIRED, unknown.mergeRequest());
        fixture.grantAvailable = false;
        fixture.remote.readable = true;
        var reader = fixture.remote.readPort();
        assertFalse(reader instanceof TestProjectDeliveryCoordinator.WritePort);
        var recovered = TestProjectDeliveryCoordinator.reconcileOnly(fixture.ledger(), reader,
                fixture.request);
        assertTrue(recovered.exactRemoteCandidateObserved());
        assertEquals(java.util.Set.of(1000L), recovered.knownMrIids());
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(1, fixture.remote.mrWrites);
        assertThrows(DeliveryException.class,
                () -> fixture.coordinator().runOnce(fixture.request));
        assertEquals(1, fixture.remote.pushWrites);
        assertEquals(1, fixture.remote.mrWrites);
    }

    @Test void failingInitialInspectionIsSanitizedAndCannotReserveIdentity() {
        fixture.remote.failInspect = true;
        var failure = assertThrows(DeliveryException.class,
                () -> fixture.coordinator().runOnce(fixture.request));
        assertEquals(DeliveryException.Kind.UNAVAILABLE, failure.kind());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("SECRET_INSPECTION"));
        assertNoDurableAdmission(fixture);
    }
}
