package io.github.qhung13.asep.codedelivery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;

class GitLabDeliveryAdapterTest {
    @TempDir Path directory;
    ControllerEvidenceStore candidate(NativeGitLabFixture remote)throws Exception{
        var source=new ProtectedCandidateControllerTest();source.root=Files.createDirectory(directory.resolve("evidence"));
        Files.setPosixFilePermissions(source.root,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        return new ProtectedCandidateController(source.root,source.uid(),source.profile,remote.baseSource(),source.actualProcess(),Clock.systemUTC())
            .produce(new ProtectedCandidateController.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),3,UUID.randomUUID(),"offline-writer",1,remote.baseSha,"d".repeat(64)));
    }
    @Test void nativeReceivePackCreatesExactCommitAndDraftWithoutTouchingMain()throws Exception{
        try(var remote=new NativeGitLabFixture(directory.resolve("remote"));var http=DeliveryHttpTransport.offlineLoopback(remote.origin(),Duration.ofSeconds(2))){
            var objects=candidate(remote);var r=objects.request();var effects=new ArrayList<TestProjectRemoteReconciliation.MergeRequest>();
            var adapter=new GitLabDeliveryAdapter(http,http,objects,(project,target)->null,(request,step)->{},effects::add);
            var prepared=new TestProjectDeliveryPreparation(objects,objects,new ProtectedCandidateControllerTest().profile,Clock.systemUTC()).prepare(r);
            assertNull(adapter.read(r).branch());adapter.push(r,prepared);
            assertEquals(r.candidateCommitSha(),remote.head(r.sourceBranch()));assertEquals(remote.baseSha,remote.head("main"));
            adapter.createDraft(r,prepared);assertEquals(1,remote.pushes.get());assertEquals(1,remote.creates.get());assertEquals(1,effects.size());
            assertEquals(r.candidateCommitSha(),effects.getFirst().head());assertTrue(effects.getFirst().draft());assertEquals("main",effects.getFirst().target());assertEquals(0,remote.forbidden.get());
            assertEquals(1,adapter.read(r).mergeRequests().size());
        }
    }
    @ParameterizedTest @ValueSource(strings={"existing-branch","collision-at-server","wrong-project","changed-main","denied-boundary"})
    void refusesUnsafePushWithoutOverwritingRemote(String fault)throws Exception{
        try(var remote=new NativeGitLabFixture(directory.resolve("remote"));var http=DeliveryHttpTransport.offlineLoopback(remote.origin(),Duration.ofSeconds(2))){
            var objects=candidate(remote);var r=objects.request();var prepared=new TestProjectDeliveryPreparation.Prepared(r,TestProjectDeliveryPreparation.render(r));
            if(fault.equals("existing-branch"))remote.move(r.sourceBranch(),remote.baseSha);
            if(fault.equals("collision-at-server")){String branch=r.sourceBranch();remote.beforePush=()->remote.move(branch,remote.baseSha);}
            if(fault.equals("wrong-project"))remote.wrongProject=true;
            if(fault.equals("changed-main")){
                r=new TestProjectDeliveryRequest(r.operationId(),r.runId(),r.attemptId(),r.runVersion(),r.ownerGrantId(),r.policyId(),r.policyEpoch(),"9".repeat(40),"9".repeat(40),r.candidateCommitSha(),r.candidateTreeSha(),r.manifestSha256(),r.sealReceiptId(),r.verificationReceiptId(),r.verifierProfileSha256(),r.safeCiProfileSha256());prepared=new TestProjectDeliveryPreparation.Prepared(r,TestProjectDeliveryPreparation.render(r));}
            var adapter=new GitLabDeliveryAdapter(http,http,objects,(project,target)->null,(request,step)->{if(fault.equals("denied-boundary"))throw new DeliveryException(DeliveryException.Kind.INVALID);},mr->fail("no MR effect"));
            var selected=r;var candidate=prepared;assertThrows(DeliveryException.class,()->adapter.push(selected,candidate));
            if(fault.equals("existing-branch")||fault.equals("collision-at-server"))assertEquals(remote.baseSha,remote.head(r.sourceBranch()));
            assertEquals(remote.baseSha,remote.head("main"));assertEquals(0,remote.creates.get());assertEquals(0,remote.forbidden.get());
        }
    }
    @ParameterizedTest @ValueSource(strings={"normal","lost-response"})
    void sourceDriftAtMrCreationRetainsActualEffectAndNeverReportsCandidate(String mode)throws Exception{
        try(var remote=new NativeGitLabFixture(directory.resolve("remote"));var http=DeliveryHttpTransport.offlineLoopback(remote.origin(),Duration.ofSeconds(2))){
            var objects=candidate(remote);var r=objects.request();var prepared=new Prepared(r,render(r));var effects=new ArrayList<TestProjectRemoteReconciliation.MergeRequest>();
            var adapter=new GitLabDeliveryAdapter(http,http,objects,(project,target)->null,(request,step)->{},effects::add);
            adapter.push(r,prepared);assertEquals(r.candidateCommitSha(),remote.head(r.sourceBranch()));
            remote.beforeMr=()->remote.move(r.sourceBranch(),remote.baseSha);remote.loseMr=mode.equals("lost-response");
            assertThrows(DeliveryException.class,()->adapter.createDraft(r,prepared));
            var observed=adapter.read(r);assertEquals(1,observed.mergeRequests().size());assertEquals(remote.baseSha,observed.mergeRequests().getFirst().head());assertNotEquals(r.candidateCommitSha(),observed.mergeRequests().getFirst().head());
            assertEquals(1,remote.creates.get());if(mode.equals("normal"))assertEquals(1,effects.size());
            // Reconciliation contains the server's real IID/head; it does not retry the POST.
            var recorded=new TestProjectRemoteReconciliation.RecordedSend(r.operationId(),r.bodyHash(),true,true,java.util.Set.of(),r.candidateCommitSha(),false);
            var result=TestProjectRemoteReconciliation.reconcile(r,recorded,observed);assertFalse(result.exactRemoteCandidateObserved());assertTrue(result.residualRace());assertEquals(java.util.Set.of(1L),result.retainedMrIids());
        }
    }
    @Test void lostPushResponseIsObservedWithoutMrChainingOrOverwrite()throws Exception{
        try(var remote=new NativeGitLabFixture(directory.resolve("remote"));var http=DeliveryHttpTransport.offlineLoopback(remote.origin(),Duration.ofSeconds(2))){
            var objects=candidate(remote);var r=objects.request();remote.losePush=true;
            var adapter=new GitLabDeliveryAdapter(http,http,objects,(project,target)->null,(request,step)->{},mr->{});
            assertThrows(DeliveryException.class,()->adapter.push(r,new Prepared(r,render(r))));
            assertEquals(r.candidateCommitSha(),adapter.read(r).branch().head());assertEquals(1,remote.pushes.get());assertEquals(0,remote.creates.get());
        }
    }
    @Test void rateLimitIsNotAutomaticallyRetriedAndLinkPaginationIsIncomplete()throws Exception{
        try(var remote=new NativeGitLabFixture(directory.resolve("remote"));var http=DeliveryHttpTransport.offlineLoopback(remote.origin(),Duration.ofSeconds(2))){
            var objects=candidate(remote);var r=objects.request();var adapter=new GitLabDeliveryAdapter(http,http,objects,(project,target)->null,(request,step)->{},mr->{});var prepared=new Prepared(r,render(r));adapter.push(r,prepared);
            remote.incompleteMrs=true;assertFalse(adapter.read(r).mrListComplete());assertThrows(DeliveryException.class,()->adapter.createDraft(r,prepared));assertEquals(0,remote.creates.get());
            remote.incompleteMrs=false;remote.mrStatus=429;assertThrows(DeliveryException.class,()->adapter.createDraft(r,prepared));assertEquals(1,remote.creates.get());assertTrue(remote.mrs.isEmpty());
        }
    }
    @Test void noLiveFactoryOrDangerousEndpointInOfflineTransport(){
        assertThrows(DeliveryException.class,()->DeliveryHttpTransport.offlineLoopback(java.net.URI.create("https://gitlab.com"),Duration.ofSeconds(1)));
        assertFalse(DeliveryHttpTransport.allowed("DELETE","/api/v4/projects/87786140/merge_requests/1"));assertFalse(DeliveryHttpTransport.allowed("POST","/api/v4/projects/42/merge_requests"));assertFalse(DeliveryHttpTransport.allowed("POST","/api/v4/projects/87786140/merge_requests/1/merge"));
    }
}
