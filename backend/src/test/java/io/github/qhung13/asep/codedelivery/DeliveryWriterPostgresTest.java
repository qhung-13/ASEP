package io.github.qhung13.asep.codedelivery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;
import static io.github.qhung13.asep.integration.GitLabTestProjectReader.*;

/** Actual JDBC/coordinator/controller/native-Git contracts; simulated enrollment
 * and workflow identity only. Real cross-process Go authority is covered separately.
 */
class DeliveryWriterPostgresTest {
    @TempDir Path directory;org.postgresql.ds.PGSimpleDataSource database;
    @BeforeEach void owned()throws Exception{database=DeliveryDatabaseFixture.runtime();}
    final class Scenario implements AutoCloseable{
        final NativeGitLabFixture remote;final DeliveryHttpTransport http;final ControllerEvidenceStore store;final TestProjectDeliveryRequest request;
        final JdbcTestProjectDeliveryLedger ledger=new JdbcTestProjectDeliveryLedger(database);final AtomicBoolean current=new AtomicBoolean(true);
        final TestProjectDeliveryCoordinator coordinator;final OwnerGrantAuthority issuer;
        Scenario(boolean grant)throws Exception{
            remote=new NativeGitLabFixture(directory.resolve(UUID.randomUUID().toString()));http=DeliveryHttpTransport.offlineLoopback(remote.origin(),Duration.ofSeconds(2));
            var helper=new ProtectedCandidateControllerTest();helper.root=Files.createTempDirectory(directory,"evidence-");Files.setPosixFilePermissions(helper.root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
            store=new ProtectedCandidateController(helper.root,helper.uid(),helper.profile,remote.baseSource(),helper.actualProcess(),Clock.systemUTC()).produce(new ProtectedCandidateController.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),3,UUID.randomUUID(),"offline-owned-fixture",1,remote.baseSha,"d".repeat(64)));request=store.request();
            issuer=new OwnerGrantAuthority(DeliveryDatabaseFixture.issuer(),"asep_delivery_issuer");if(grant)issuer.approve(request,300);
            var owner=new TestProjectOwnerGrantVerifier(new JdbcOwnerGrantSource(database),Clock.systemUTC());
            var policy=new TestProjectCurrentPolicyVerifier((run,attempt)->new TestProjectCurrentPolicyVerifier.Snapshot(request.runId(),request.attemptId(),request.runVersion(),current.get()?"DELIVERY_PENDING":"CANCEL_REQUESTED",request.policyId(),request.policyEpoch(),request.projectId(),request.targetBranch(),request.baseSha(),request.expectedTargetSha(),current.get(),Instant.now(),request.operationId(),request.bodyHash(),Instant.now().plusSeconds(300)),Clock.systemUTC(),Duration.ofSeconds(10));
            var preparation=new TestProjectDeliveryPreparation(store,store,helper.profile,Clock.systemUTC());
            var gate=new TestProjectDeliveryGate((r,inspection)->{
                var grantEvidence=owner.verify(r,inspection);var policyEvidence=policy.verify(r,inspection);
                if(!grantEvidence.verified().contains(Requirement.OWNER_WRITE_AUTHORIZATION)||!policyEvidence.verified().contains(Requirement.CURRENT_POLICY))return new Evidence(Set.of(),null);
                preparation.prepare(r);
                // Only the loopback fixture supplies non-authority enrollment facts.
                // No such all-enrolled source is constructed by production startup.
                return new Evidence(EnumSet.allOf(Requirement.class),grantEvidence.ownerGrant());
            },Clock.systemUTC(),Duration.ofSeconds(10));
            GitLabTestProjectReaderAdapter inspection=new GitLabTestProjectReaderAdapter();
            var adapter=new GitLabDeliveryAdapter(http,http,store,inspection,(r,step)->{
                var record=ledger.find(r.operationId());assertEquals(request,record.request());assertTrue(step==GitLabDeliveryAdapter.Mutation.BRANCH_CREATE?record.pushClaimed():record.mrClaimed());
                gate.requireBeforeMutation(r,inspection.inspect(r.projectId(),r.targetBranch()));preparation.prepare(r);
            },GitLabDeliveryAdapter.retainIn(ledger,request));
            coordinator=new TestProjectDeliveryCoordinator(ledger,gate,preparation,adapter,adapter);
        }
        final class GitLabTestProjectReaderAdapter implements io.github.qhung13.asep.integration.GitLabTestProjectReader{
            @Override public Inspection inspect(long project,String branch){var at=Instant.now();return new Inspection(project,branch,new Project(87786140,"qhung-13/asep-m01-sandbox","private",false,"main","disabled",false,30),new Target("main",remote.head("main"),true,false),new CollectionObservation(1,true),new CollectionObservation(0,true),new CollectionObservation(0,true),new CollectionObservation(0,true),new CollectionObservation(0,true),java.util.List.of(),Set.of(),at,Instant.now());}
        }
        @Override public void close()throws Exception{http.close();remote.close();}
    }
    @Test void protectedRuntimeDatabaseGuardRequiresDomainRoleWithoutAssumingColocatedSchemas()throws Exception{
        // Existing owned Java fixture has no orchestration schema. A separate
        // database must be permitted; any existing workflow schema must be denied.
        try(var db=database.getConnection();var query=db.createStatement();var row=query.executeQuery(ProtectedDeliveryRuntime.RUNTIME_IDENTITY_SQL)){assertTrue(row.next());assertTrue(row.getBoolean(1));}
        try(var db=DeliveryDatabaseFixture.issuer().getConnection();var query=db.createStatement();var row=query.executeQuery(ProtectedDeliveryRuntime.RUNTIME_IDENTITY_SQL)){assertTrue(row.next());assertFalse(row.getBoolean(1));}
    }
    @Test void actualWriterAndDurableLedgerCompleteOnceAcrossRestart()throws Exception{
        try(var s=new Scenario(true)){var result=s.coordinator.runOnce(s.request);assertTrue(result.exactRemoteCandidateObserved());assertEquals(Set.of(1L),result.knownMrIids());
            assertEquals(result,new JdbcTestProjectDeliveryLedger(database).find(s.request.operationId()));
            for(int i=0;i<3;i++)assertTrue(s.coordinator.runOnce(s.request).exactRemoteCandidateObserved());assertEquals(1,s.remote.pushes.get());assertEquals(1,s.remote.creates.get());assertEquals(s.remote.baseSha,s.remote.head("main"));}
    }
    @ParameterizedTest @ValueSource(strings={"normal","lost-response"})
    void mrSourceDriftPersistsActualIidAndNeverCompletesOnReplay(String response)throws Exception{
        try(var s=new Scenario(true)){s.remote.beforeMr=()->s.remote.move(s.request.sourceBranch(),s.remote.baseSha);s.remote.loseMr=response.equals("lost-response");
            var result=s.coordinator.runOnce(s.request);assertFalse(result.exactRemoteCandidateObserved());assertTrue(result.residualRace());assertEquals(Set.of(1L),result.knownMrIids());
            for(int i=0;i<3;i++){var observed=s.coordinator.reconcileOnly(s.request);assertFalse(observed.exactRemoteCandidateObserved());assertEquals(Set.of(1L),observed.knownMrIids());}
            assertEquals(1,s.remote.creates.get());assertEquals(1,s.remote.pushes.get());assertEquals(1,s.remote.mrs.size());assertEquals(s.remote.baseSha,s.remote.head(s.request.sourceBranch()));}
    }
    @ParameterizedTest @ValueSource(strings={"push","mr"})
    void lostReplyUsesReadsAndNeverResendsClaimedStep(String step)throws Exception{
        try(var s=new Scenario(true)){s.remote.losePush=step.equals("push");s.remote.loseMr=step.equals("mr");var first=s.coordinator.runOnce(s.request);
            assertEquals(1,s.remote.pushes.get());if(step.equals("push"))assertEquals(0,s.remote.creates.get());
            s.remote.losePush=false;s.remote.loseMr=false;var finalState=s.coordinator.runOnce(s.request);assertTrue(finalState.exactRemoteCandidateObserved());assertEquals(1,s.remote.pushes.get());assertEquals(1,s.remote.creates.get());}
    }
    @ParameterizedTest @ValueSource(strings={"grant","policy"})
    void cancellationOrGrantRevocationDuringSendPreservesEffectAndResidualRace(String authority)throws Exception{
        try(var s=new Scenario(true)){s.remote.beforeMr=()->{if(authority.equals("grant"))s.issuer.revoke(s.request);else s.current.set(false);};
            var result=s.coordinator.runOnce(s.request);assertFalse(result.exactRemoteCandidateObserved());assertTrue(result.residualRace());assertEquals(Set.of(1L),result.knownMrIids());assertEquals(1,s.remote.creates.get());
            var again=s.coordinator.reconcileOnly(s.request);assertTrue(again.residualRace());assertEquals(Set.of(1L),again.knownMrIids());assertEquals(1,s.remote.creates.get());}
    }
    @Test void missingGrantDoesNotReserveLedgerOrSend()throws Exception{
        try(var s=new Scenario(false)){assertThrows(DeliveryException.class,()->s.coordinator.runOnce(s.request));assertThrows(DeliveryException.class,()->s.ledger.find(s.request.operationId()));assertEquals(0,s.remote.pushes.get());assertEquals(0,s.remote.creates.get());}
    }
    @Test void closedDraftIsRetainedButCannotConfirmDelivery()throws Exception{
        try(var s=new Scenario(true)){assertTrue(s.coordinator.runOnce(s.request).exactRemoteCandidateObserved());s.remote.mrs.getFirst().put("state","closed");var observed=s.coordinator.reconcileOnly(s.request);assertFalse(observed.exactRemoteCandidateObserved());assertEquals(Set.of(1L),observed.knownMrIids());assertTrue(observed.residualRace());}
    }
    @Test void inboxAndCallbackAckRemainDurableAndDoNotAuthorizeWrites()throws Exception{
        try(var s=new Scenario(false)){UUID message=UUID.randomUUID(),token=UUID.randomUUID();var e=new DeliveryIntentInbox.Envelope(message,s.request.operationId(),s.request.bodyHash(),DeliveryIntentInbox.Envelope.hash(message,s.request.operationId(),s.request.bodyHash()),token,s.request);
            var inbox=new DeliveryIntentInbox(database);inbox.accept(e);new DeliveryIntentInbox(database).accept(e);assertTrue(inbox.requests(100).contains(s.request));assertEquals(0,s.remote.pushes.get());
            assertThrows(DeliveryException.class,()->s.coordinator.runOnce(s.request));s.issuer.approve(s.request,300);s.coordinator.runOnce(s.request);
            var delivered=new java.util.HashSet<UUID>();var fail=new AtomicBoolean(true);
            var channel=new DeliveryMessagePump.Channel(){public DeliveryIntentInbox.Envelope claim(){return e;}public void acknowledge(UUID id,String hash,UUID t){}public void outcome(TestProjectDeliveryRecord.Callback c){delivered.add(c.callbackId());if(fail.getAndSet(false))throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);}};
            var pump=new DeliveryMessagePump(channel,inbox,s.ledger);assertThrows(DeliveryException.class,()->pump.publishOutcomes(s.request.operationId(),100));assertFalse(s.ledger.pendingCallbacks(s.request.operationId()).isEmpty());
            pump.publishOutcomes(s.request.operationId(),100);assertTrue(s.ledger.pendingCallbacks(s.request.operationId()).isEmpty());assertEquals(1,s.remote.pushes.get());assertEquals(1,s.remote.creates.get());}
    }
}
