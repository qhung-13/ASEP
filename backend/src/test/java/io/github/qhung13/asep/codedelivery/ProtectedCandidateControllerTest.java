package io.github.qhung13.asep.codedelivery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;
import static io.github.qhung13.asep.codedelivery.ProtectedCandidateController.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ProtectedCandidateControllerTest {
    @TempDir Path root;
    final Profile profile=new Profile("a".repeat(64),"b".repeat(64),"c".repeat(64),2,Duration.ofMinutes(5));
    final String tree=gitId(ObjectType.TREE,new byte[0]);
    final byte[] base=("tree "+tree+"\nauthor Base <base@invalid> 1793145600 +0000\ncommitter Base <base@invalid> 1793145600 +0000\n\nBase\n").getBytes(StandardCharsets.US_ASCII);
    long uid()throws Exception{return ((Number)Files.getAttribute(root,"unix:uid")).longValue();}
    Job job(){return new Job(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),3,UUID.randomUUID(),"controller-profile",1,gitId(ObjectType.COMMIT,base),"d".repeat(64));}
    Artifacts source(){return(type,sha,bound)->type==ObjectType.COMMIT?base.clone():new byte[0];}
    IndependentVerifier actualProcess(){return (path,request)->{
        try{
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),ProbeVerificationMain.class.getName(),path.toString());
            process.environment().clear();process.environment().put("LANG","C.UTF-8");process.redirectError(ProcessBuilder.Redirect.DISCARD);
            var child=process.start();try{
                assertTrue(child.waitFor(10,TimeUnit.SECONDS));byte[] output=child.getInputStream().readNBytes(4097);assertTrue(output.length<=4096);assertEquals(0,child.exitValue());
                var result=JdbcDeliveryLedger.JSON.readValue(output,ProbeVerificationMain.Result.class);
                return new VerificationReport(result.discovered(),result.executed(),result.skipped(),result.failed(),child.exitValue(),false,ControllerEvidenceStore.digest(output),profile.harnessSha256(),profile.imageSha256());
            }finally{child.destroyForcibly();assertTrue(child.waitFor(2,TimeUnit.SECONDS));}
        }catch(Exception failure){throw new IllegalStateException("Harness failed");}
    };}
    ProtectedCandidateController controller(IndependentVerifier verifier)throws Exception{
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        return new ProtectedCandidateController(root,uid(),profile,source(),verifier,Clock.systemUTC());
    }
    @Test void realHarnessPublishesExactImmutableBatchAndRestartReusesOriginal()throws Exception{
        Job job=job();var stored=controller(actualProcess()).produce(job);var request=stored.request();
        assertEquals(job.runId(),request.runId());assertEquals(job.producerAttemptId(),request.attemptId());
        assertEquals(request,new TestProjectDeliveryPreparation(stored,stored,profile,Clock.systemUTC()).prepare(request).request());
        var again=controller((path,r)->{throw new AssertionError("published evidence must not be reissued");}).produce(job);
        assertEquals(request,again.request());assertEquals(stored.verification(request.verificationReceiptId()),again.verification(request.verificationReceiptId()));
    }
    @ParameterizedTest @ValueSource(strings={"receipt","object","profile","expired","wrong-run","wrong-operation"})
    void rejectsAlteredOrMismatchedPublishedEvidence(String fault)throws Exception{
        Job job=job();var stored=controller(actualProcess()).produce(job);var r=stored.request();Path batch=root.resolve("operations").resolve(job.operationId().toString());
        if(fault.equals("receipt")){
            Files.writeString(batch.resolve("verification.json"),"{}");assertThrows(IllegalStateException.class,()->new ControllerEvidenceStore(root,uid(),job.operationId()));return;
        }
        if(fault.equals("object")){
            Path object=batch.resolve("objects/commit/"+r.candidateCommitSha());Files.setPosixFilePermissions(object,PosixFilePermissions.fromString("rw-------"));Files.writeString(object,"tampered");
        }
        if(fault.equals("wrong-operation")){
            assertThrows(IllegalStateException.class,()->new ControllerEvidenceStore(root,uid(),UUID.randomUUID()));return;
        }
        if(fault.equals("wrong-run")){
            Job wrong=new Job(job.operationId(),UUID.randomUUID(),job.producerAttemptId(),job.deliveryRunVersion(),job.grantId(),job.policyId(),job.policyEpoch(),job.baseSha(),job.safeCiProfileSha256());
            assertThrows(IllegalStateException.class,()->controller(actualProcess()).produce(wrong));return;
        }
        var selected=fault.equals("profile")?new Profile("e".repeat(64),profile.harnessSha256(),profile.imageSha256(),2,profile.maximumReceiptAge()):profile;
        var clock=fault.equals("expired")?Clock.offset(Clock.systemUTC(),Duration.ofHours(1)):Clock.systemUTC();
        assertThrows(IllegalStateException.class,()->new TestProjectDeliveryPreparation(stored,stored,selected,clock).prepare(r));
    }
    @ParameterizedTest @ValueSource(strings={"no-tests","skips","failed","wrong-image","interrupted"})
    void interruptedOrInvalidVerificationNeverPublishesAnyReceipt(String fault)throws Exception{
        Job job=job();IndependentVerifier delegate=actualProcess();
        var broken=controller((path,r)->{
            if(fault.equals("interrupted"))throw new IllegalStateException("interrupted controller");
            var report=delegate.verify(path,r);
            return new VerificationReport(fault.equals("no-tests")?0:report.discovered(),fault.equals("no-tests")?0:report.executed(),fault.equals("skips")?1:0,fault.equals("failed")?1:0,0,false,report.logsSha256(),report.harnessSha256(),fault.equals("wrong-image")?"0".repeat(64):report.imageSha256());
        });
        assertThrows(IllegalStateException.class,()->broken.produce(job));assertFalse(Files.exists(root.resolve("operations").resolve(job.operationId().toString())));
        assertNotNull(controller(actualProcess()).produce(job));
    }
    @Test void untrustedStorageOwnerCannotIssue()throws Exception{
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        var untrusted=new ProtectedCandidateController(root,uid()+1,profile,source(),actualProcess(),Clock.systemUTC());
        assertThrows(IllegalStateException.class,()->untrusted.produce(job()));assertFalse(Files.exists(root.resolve("operations")));
    }
    @Test void networklessUnprivilegedDockerVerifierRunsActualPinnedHarness()throws Exception{
        assumeTrue(Boolean.getBoolean("asep.delivery.controller.docker"),"Use owned delivery harness");
        Path jar=Path.of("target/asep-backend-0.0.1-SNAPSHOT.jar").toAbsolutePath();String hash=ControllerEvidenceStore.digest(Files.readAllBytes(jar));
        String image=System.getProperty("asep.delivery.controller.image");assertNotNull(image);assertTrue(image.matches("[0-9a-f]{64}"));
        var actual=new Profile("a".repeat(64),hash,image,2,Duration.ofMinutes(5));
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        var isolated=new IsolatedProbeVerifier(jar,hash,image,Duration.ofSeconds(20));
        var stored=new ProtectedCandidateController(root,uid(),actual,source(),isolated,Clock.systemUTC()).produce(job());
        var report=stored.verification(stored.request().verificationReceiptId());assertEquals(2,report.executed());assertEquals(0,report.failed());assertEquals(image,report.imageSha256());
    }
}
