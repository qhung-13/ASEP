package io.github.qhung13.asep.codedelivery;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class IsolatedVerifierRecoveryTest {
    @TempDir Path root;
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing-cid","partial-cid"})
    void uncertainCreateFinallyPreservesProofUntilOwnedRecovery(String fault)throws Exception{
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));long uid=((Number)Files.getAttribute(root,"unix:uid")).longValue();
        Path harness=root.resolve("harness.jar");Files.writeString(harness,"nonexecuted fixture");Files.setPosixFilePermissions(harness,PosixFilePermissions.fromString("rw-r--r--"));
        String image="a".repeat(64),cid="b".repeat(64);var nonce=new java.util.concurrent.atomic.AtomicReference<String>();var removed=new java.util.concurrent.atomic.AtomicBoolean();
        IsolatedProbeVerifier.Commands docker=(args,limit,bound)->{
            switch(args.get(1)){
                case "create"->{String label=args.get(args.indexOf("--label")+1);nonce.set(label.substring(label.indexOf('=')+1));
                    if(fault.equals("partial-cid"))Files.writeString(Path.of(args.get(args.indexOf("--cidfile")+1)),"partial");
                    throw new java.io.IOException("simulated lost create acknowledgement");}
                case "ps"->{return cid.getBytes(StandardCharsets.US_ASCII);}
                case "inspect"->{return (nonce.get()+" sha256:"+image).getBytes(StandardCharsets.US_ASCII);}
                case "rm"->{assertEquals(cid,args.getLast());removed.set(true);return new byte[0];}
                default->throw new AssertionError("Unexpected fixture command");
            }
        };
        var verifier=new IsolatedProbeVerifier(harness,ControllerEvidenceStore.digest(Files.readAllBytes(harness)),image,Duration.ofSeconds(5),root,uid,docker);
        assertThrows(IllegalStateException.class,()->verifier.verify(root,null));assertFalse(removed.get());
        Path job;try(var paths=Files.list(root)){job=paths.filter(p->p.getFileName().toString().startsWith("job-")).findFirst().orElseThrow();}
        assertTrue(Files.readString(job.resolve("proof")).startsWith(nonce.get()+"\n"));
        verifier.recoverOwnedJobs();assertTrue(removed.get());assertFalse(Files.exists(job));
    }
    @Test void unacknowledgedAbsenceRemainsManualRatherThanErasingProof()throws Exception{
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));long uid=((Number)Files.getAttribute(root,"unix:uid")).longValue();
        Path job=Files.createDirectory(root.resolve("job-uncertain"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        ProtectedCandidateController.write(job.resolve("proof"),(UUID.randomUUID()+"\n"+"a".repeat(64)+"\n").getBytes(StandardCharsets.US_ASCII));
        var verifier=new IsolatedProbeVerifier(root.resolve("unused.jar"),"a".repeat(64),"a".repeat(64),Duration.ofSeconds(5),root,uid,(args,limit,bound)->new byte[0]);
        assertThrows(IllegalStateException.class,verifier::recoverOwnedJobs);assertTrue(Files.exists(job.resolve("proof")));
    }
    @Test void restartScavengesOnlyRecordedNonceAndImage()throws Exception{
        assumeTrue(Boolean.getBoolean("asep.delivery.controller.docker"),"Owned Docker fixture required");
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));long uid=((Number)Files.getAttribute(root,"unix:uid")).longValue();
        String image=System.getProperty("asep.delivery.controller.image");assertNotNull(image);assertTrue(image.matches("[0-9a-f]{64}"));
        Path job=Files.createDirectory(root.resolve("job-interrupted"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        String nonce=UUID.randomUUID().toString(),other=UUID.randomUUID().toString();
        String owned=docker("create","--pull=never","--label","io.github.qhung13.asep.verification-job="+nonce,"--network","none","--entrypoint","java","sha256:"+image,"-version");
        String foreign=docker("create","--pull=never","--label","io.github.qhung13.asep.verification-job="+other,"--network","none","--entrypoint","java","sha256:"+image,"-version");
        try{
            ProtectedCandidateController.write(job.resolve("proof"),(nonce+"\n"+image+"\n").getBytes(StandardCharsets.US_ASCII));
            // Missing CID simulates a crash before Docker's create reply was recorded.
            var verifier=new IsolatedProbeVerifier(Path.of("target/nonexecuted.jar"),"a".repeat(64),image,Duration.ofSeconds(5),root,uid);
            verifier.recoverOwnedJobs();assertFalse(Files.exists(job));assertEquals(other,docker("inspect","--format","{{index .Config.Labels \"io.github.qhung13.asep.verification-job\"}}",foreign));
            assertFalse(exists(owned));
            Path ambiguous=Files.createDirectory(root.resolve("job-wrong-image"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            ProtectedCandidateController.write(ambiguous.resolve("proof"),(other+"\n"+"0".repeat(64)+"\n").getBytes(StandardCharsets.US_ASCII));
            assertThrows(IllegalStateException.class,verifier::recoverOwnedJobs);assertTrue(exists(foreign));assertTrue(Files.exists(ambiguous.resolve("proof")));
        }finally{if(exists(owned))docker("rm","-fv",owned);if(exists(foreign))docker("rm","-fv",foreign);}
    }
    private static boolean exists(String cid)throws Exception{try{docker("inspect","--format","{{.Id}}",cid);return true;}catch(AssertionError gone){return false;}}
    private static String docker(String...args)throws Exception{
        var command=new java.util.ArrayList<String>();command.add("/usr/bin/docker");command.addAll(java.util.List.of(args));var builder=new ProcessBuilder(command);builder.environment().clear();builder.environment().put("PATH","/usr/bin:/bin");builder.redirectError(ProcessBuilder.Redirect.DISCARD);var p=builder.start();
        try{assertTrue(p.waitFor(10,TimeUnit.SECONDS));byte[] bytes=p.getInputStream().readNBytes(1025);assertTrue(bytes.length<=1024);assertEquals(0,p.exitValue());return new String(bytes,StandardCharsets.US_ASCII).strip();}finally{p.destroyForcibly();assertTrue(p.waitFor(2,TimeUnit.SECONDS));}
    }
}
