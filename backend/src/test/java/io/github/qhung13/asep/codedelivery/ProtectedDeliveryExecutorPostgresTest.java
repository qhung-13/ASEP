package io.github.qhung13.asep.codedelivery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedDeliveryExecutorPostgresTest {
    @TempDir Path root;
    final class Scenario implements AutoCloseable {
        final NativeGitLabFixture remote;
        final ControllerEvidenceStore store;
        final ProtectedEnrollmentFixture records;
        final ProtectedDeliveryExecutor.Context context;
        final OwnerGrantAuthority issuer;
        Scenario()throws Exception{
            var database=DeliveryDatabaseFixture.runtime();remote=new NativeGitLabFixture(root.resolve("git"));
            var controller=new ProtectedCandidateControllerTest();controller.root=Files.createDirectory(root.resolve("candidate"));
            store=controller.controller(controller.actualProcess()).produce(new ProtectedCandidateController.Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),3,UUID.randomUUID(),"runtime-fixture",1,remote.baseSha,"d".repeat(64)));
            var request=store.request();records=new ProtectedEnrollmentFixture(root.resolve("enrollment"),request);
            issuer=new OwnerGrantAuthority(DeliveryDatabaseFixture.issuer(),"asep_delivery_issuer");issuer.approve(request,300);
            var owner=new TestProjectOwnerGrantVerifier(new JdbcOwnerGrantSource(database),records.clock);
            var policy=new TestProjectCurrentPolicyVerifier((run,attempt)->new TestProjectCurrentPolicyVerifier.Snapshot(run,attempt,request.runVersion(),"DELIVERY_PENDING",request.policyId(),request.policyEpoch(),request.projectId(),"main",request.baseSha(),request.expectedTargetSha(),true,records.clock.instant(),request.operationId(),request.bodyHash(),records.clock.instant().plusSeconds(60)),records.clock,Duration.ofSeconds(10));
            UUID message=UUID.randomUUID();var envelope=new DeliveryIntentInbox.Envelope(message,request.operationId(),request.bodyHash(),DeliveryIntentInbox.Envelope.hash(message,request.operationId(),request.bodyHash()),UUID.randomUUID(),request);
            var channel=new DeliveryMessagePump.Channel(){public DeliveryIntentInbox.Envelope claim(){return envelope;}public void acknowledge(UUID id,String hash,UUID token){}public void outcome(TestProjectDeliveryRecord.Callback callback){}};
            context=new ProtectedDeliveryExecutor.Context(store,controller.profile,records.enrollment,records,database,new TestProjectTrustedEvidenceComposite(owner,policy),channel,remote.origin(),records.clock);
        }
        @Override public void close()throws Exception{remote.close();}
    }
    @Test void actualAssemblyCompletesAndReconcilesAfterGrantPermitAndWriterRemoval()throws Exception{
        try(var s=new Scenario()){
            try(var executor=ProtectedDeliveryExecutor.open(s.context,true,true)){assertTrue(executor.run().exactRemoteCandidateObserved());assertTrue(executor.publishOutcomes()>0);}
            assertEquals(1,s.remote.pushes.get());assertEquals(1,s.remote.creates.get());
            s.issuer.revoke(s.store.request());Files.delete(s.records.root.resolve(s.store.request().operationId()+".json"));Files.delete(s.records.root.resolve("write.credential"));
            int writes=s.records.writeLoads.get();s.records.clock.advance(360);
            try(var executor=ProtectedDeliveryExecutor.open(s.context,false,false)){assertTrue(executor.run().exactRemoteCandidateObserved());executor.publishOutcomes();}
            assertEquals(writes,s.records.writeLoads.get());assertEquals(1,s.remote.pushes.get());assertEquals(1,s.remote.creates.get());
        }
    }
    @ParameterizedTest @ValueSource(strings={"disabled","tampered-report","missing-report","read-fingerprint","write-fingerprint","wrong-project","expired"})
    void invalidCapabilitiesCannotSendOrReserveLedger(String fault)throws Exception{
        try(var s=new Scenario()){
            var p=s.records.enrollment.require(s.store.request());var fact=p.facts().values().iterator().next();Path report=s.records.root.resolve("evidence/"+fact.evidenceSha256()+".json");
            switch(fault){case "tampered-report"->Files.writeString(report,"{}");case "missing-report"->Files.delete(report);
                case "read-fingerprint"->s.records.write("read.credential","changed".getBytes());case "write-fingerprint"->s.records.write("write.credential","changed".getBytes());
                case "wrong-project"->s.remote.wrongProject=true;case "expired"->s.records.clock.advance(301);default->{}}
            assertThrows(RuntimeException.class,()->{try(var executor=ProtectedDeliveryExecutor.open(s.context,true,!fault.equals("disabled"))){executor.run();}});
            assertEquals(0,s.remote.pushes.get());assertEquals(0,s.remote.creates.get());
            assertThrows(DeliveryException.class,()->new JdbcTestProjectDeliveryLedger(s.context.database()).find(s.store.request().operationId()));
            if(fault.equals("disabled")){assertEquals(0,s.records.readLoads.get());assertEquals(0,s.records.writeLoads.get());}
        }
    }
    @ParameterizedTest @ValueSource(strings={"expiry","write-rotation","read-rotation","lost-push","lost-mr"})
    void revalidationAndUnknownRecoveryNeverRepeatWrites(String fault)throws Exception{
        try(var s=new Scenario()){
            s.remote.losePush=fault.equals("lost-push");s.remote.loseMr=fault.equals("lost-mr");
            s.remote.beforePush=()->{try{switch(fault){case "expiry"->s.records.clock.advance(301);case "write-rotation"->s.records.write("write.credential","rotated".getBytes());case "read-rotation"->s.records.write("read.credential","rotated".getBytes());default->{}}}catch(Exception failure){throw new IllegalStateException(failure);}};
            try(var executor=ProtectedDeliveryExecutor.open(s.context,true,true)){assertFalse(executor.run().exactRemoteCandidateObserved()&&!fault.equals("lost-mr"));}
            assertEquals(1,s.remote.pushes.get());assertEquals(fault.equals("lost-mr")?1:0,s.remote.creates.get());
            Files.delete(s.records.root.resolve(s.store.request().operationId()+".json"));int writes=s.records.writeLoads.get();
            for(int i=0;i<2;i++)try(var recovery=ProtectedDeliveryExecutor.open(s.context,false,false)){recovery.run();recovery.publishOutcomes();}
            assertEquals(writes,s.records.writeLoads.get());assertEquals(1,s.remote.pushes.get());assertEquals(fault.equals("lost-mr")?1:0,s.remote.creates.get());
        }
    }
}
