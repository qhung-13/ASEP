package io.github.qhung13.asep.codedelivery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static io.github.qhung13.asep.codedelivery.ProtectedCandidateController.*;

/** Trusted controller capability only. Docker is held by the controller, never
 * mounted into the verifier. No pulls, network, candidate executable or inherited
 * credentials. The fixed image and protected installed harness must be pinned.
 */
public final class IsolatedProbeVerifier implements IndependentVerifier {
    private static final String LABEL="io.github.qhung13.asep.verification-job";
    @FunctionalInterface interface Commands { byte[] run(List<String> args,Duration limit,int bound)throws Exception; }
    private final Path harness;private final String harnessHash,image;private final Duration timeout;private final Path jobs;private final long owner;
    private final Commands docker;
    public IsolatedProbeVerifier(Path reviewedHarness,String harnessSha256,String imageSha256,Duration timeout){
        this(reviewedHarness,harnessSha256,imageSha256,timeout,null,-1);
    }
    public IsolatedProbeVerifier(Path reviewedHarness,String harnessSha256,String imageSha256,Duration timeout,Path protectedJobs,long controllerUid){
        this(reviewedHarness,harnessSha256,imageSha256,timeout,protectedJobs,controllerUid,IsolatedProbeVerifier::command);
    }
    IsolatedProbeVerifier(Path reviewedHarness,String harnessSha256,String imageSha256,Duration timeout,Path protectedJobs,long controllerUid,Commands docker){
        jobs=protectedJobs;owner=controllerUid;
        this.docker=java.util.Objects.requireNonNull(docker);
        this.harness=reviewedHarness.toAbsolutePath().normalize();this.harnessHash=harnessSha256;this.image=imageSha256;
        if(!harnessSha256.matches("[0-9a-f]{64}")||!imageSha256.matches("[0-9a-f]{64}")||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(60))>0)throw ControllerEvidenceStore.denied();
        this.timeout=timeout;
    }
    @Override public VerificationReport verify(Path frozen,TestProjectDeliveryRequest request){
        java.nio.channels.FileChannel leader=null;java.nio.channels.FileLock held=null;
        Path privateJob=null;String cid=null;String nonce=UUID.randomUUID().toString();String phase="harness";
        boolean createAttempted=false;
        try{
            if(jobs!=null){ProtectedOperatorFiles.directory(jobs,owner,true);leader=java.nio.channels.FileChannel.open(jobs.resolve(".leader"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS);held=leader.tryLock();if(held==null)throw ControllerEvidenceStore.denied();recoverLocked();}
            if(!Files.isRegularFile(harness,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(harness)>134217728
                    ||!Files.getPosixFilePermissions(harness).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ)
                    ||!ControllerEvidenceStore.digest(Files.readAllBytes(harness)).equals(harnessHash))throw ControllerEvidenceStore.denied();
            privateJob=jobs==null?Files.createTempDirectory("asep-verifier-"):Files.createTempDirectory(jobs,"job-");
            ProtectedCandidateController.write(privateJob.resolve("proof"),(nonce+"\n"+image+"\n").getBytes(StandardCharsets.US_ASCII));
            sync(privateJob);if(jobs!=null)sync(jobs);
            Path cidfile=privateJob.resolve("container.cid");
            // Image ID is content addressed. Docker create does not pull this sha256 ID.
            phase="create";createAttempted=true;docker.run(List.of("/usr/bin/docker","create","--pull=never","--cidfile",cidfile.toString(),"--label",LABEL+"="+nonce,
                    "--network","none","--user","65534:65534","--cap-drop","ALL","--security-opt","no-new-privileges:true",
                    "--read-only","--tmpfs","/tmp:rw,nosuid,nodev,noexec,size=16777216","--memory","256m","--cpus","1","--pids-limit","64",
                    "--mount","type=bind,source="+harness+",target=/verifier.jar,readonly",
                    "--mount","type=bind,source="+frozen.toAbsolutePath().normalize()+",target=/candidate,readonly",
                    "--entrypoint","java","sha256:"+image,"-XX:-HeapDumpOnOutOfMemoryError","-Dloader.main=io.github.qhung13.asep.codedelivery.ProbeVerificationMain",
                    "-cp","/verifier.jar","org.springframework.boot.loader.launch.PropertiesLauncher","/candidate"),Duration.ofSeconds(5),2048);
            phase="cid";cid=Files.readString(cidfile).strip();if(!cid.matches("[0-9a-f]{64}"))throw ControllerEvidenceStore.denied();
            phase="start";byte[] output=docker.run(List.of("/usr/bin/docker","start","--attach",cid),timeout,4096);
            phase="result";var result=JdbcDeliveryLedger.JSON.readValue(output,ProbeVerificationMain.Result.class);
            return new VerificationReport(result.discovered(),result.executed(),result.skipped(),result.failed(),0,false,ControllerEvidenceStore.digest(output),harnessHash,image);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw ControllerEvidenceStore.denied();}
        catch(Exception denied){throw new IllegalStateException("Isolated verifier denied at "+phase);}
        finally{
            boolean interrupted=Thread.interrupted();
            // If create succeeded but its response was lost, recover the owned CID file.
            try{
                if(cid==null&&privateJob!=null&&Files.isRegularFile(privateJob.resolve("container.cid")))cid=Files.readString(privateJob.resolve("container.cid")).strip();
                // An interrupted create may have reached the daemon before a CID
                // was returned. Retain the durable nonce, including malformed CIDs;
                // the next exclusive recovery pass resolves it by nonce + image.
                if(createAttempted&&(cid==null||!cid.matches("[0-9a-f]{64}")))throw ControllerEvidenceStore.denied();
                if(cid!=null&&cid.matches("[0-9a-f]{64}")){
                    String actual=new String(docker.run(List.of("/usr/bin/docker","inspect","--format","{{index .Config.Labels \""+LABEL+"\"}} {{.Image}}",cid),Duration.ofSeconds(5),256),StandardCharsets.US_ASCII).strip();
                    if(!actual.equals(nonce+" sha256:"+image))throw ControllerEvidenceStore.denied();
                    docker.run(List.of("/usr/bin/docker","rm","--force",cid),Duration.ofSeconds(5),1024);
                }
                if(privateJob!=null){Files.deleteIfExists(privateJob.resolve("container.cid"));Files.deleteIfExists(privateJob.resolve("proof"));Files.delete(privateJob);}
            }catch(InterruptedException cleanupInterrupted){interrupted=true;throw ControllerEvidenceStore.denied();}
            catch(Exception cleanupFailed){throw ControllerEvidenceStore.denied();}
            finally{try{if(held!=null)held.close();if(leader!=null)leader.close();}catch(Exception lockFailure){throw ControllerEvidenceStore.denied();}finally{if(interrupted)Thread.currentThread().interrupt();}}
        }
    }
    /** Restart cleanup is confined to this protected journal while no verifier
     * owns the controller lock. Only exact recorded nonce + image can be removed.
     * Missing/ambiguous proof is retained for manual inspection, never pruned.
     */
    public void recoverOwnedJobs(){
        if(jobs==null)throw ControllerEvidenceStore.denied();
        try {ProtectedOperatorFiles.directory(jobs,owner,true);}catch(Exception unsafe){throw ControllerEvidenceStore.denied();}
        try(var lock=java.nio.channels.FileChannel.open(jobs.resolve(".leader"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS);var held=lock.tryLock()){
            if(held==null)throw ControllerEvidenceStore.denied();recoverLocked();
        }catch(Exception failed){throw ControllerEvidenceStore.denied();}
    }
    private void recoverLocked()throws Exception{
        try(var list=Files.list(jobs)){
            var paths=list.filter(p->p.getFileName().toString().startsWith("job-")).limit(101).toList();if(paths.size()>100)throw ControllerEvidenceStore.denied();
            for(Path job:paths){
                ProtectedOperatorFiles.directory(job,owner,true);
                String[] proof=new String(ProtectedOperatorFiles.read(job,job.resolve("proof"),owner,128,false),StandardCharsets.US_ASCII).split("\n");
                if(proof.length!=2||!proof[0].matches("[0-9a-f-]{36}")||!proof[1].matches("[0-9a-f]{64}"))throw ControllerEvidenceStore.denied();
                String found=new String(docker.run(List.of("/usr/bin/docker","ps","-aq","--no-trunc","--filter","label="+LABEL+"="+proof[0]),Duration.ofSeconds(5),1024),StandardCharsets.US_ASCII).strip();
                // Absence cannot resolve a create/remove whose daemon outcome
                // was never acknowledged. Preserve proof for protected inspection.
                if(found.isEmpty())throw ControllerEvidenceStore.denied();
                if(!found.isEmpty()){
                    if(!found.matches("[0-9a-f]{64}"))throw ControllerEvidenceStore.denied();
                    String actual=new String(docker.run(List.of("/usr/bin/docker","inspect","--format","{{index .Config.Labels \""+LABEL+"\"}} {{.Image}}",found),Duration.ofSeconds(5),256),StandardCharsets.US_ASCII).strip();
                    if(!actual.equals(proof[0]+" sha256:"+proof[1]))throw ControllerEvidenceStore.denied();
                    docker.run(List.of("/usr/bin/docker","rm","--force",found),Duration.ofSeconds(5),1024);
                }
                // The directory is private, immutable to candidates; delete only our known files.
                Files.deleteIfExists(job.resolve("container.cid"));Files.delete(job.resolve("proof"));Files.delete(job);
            }
        }
    }
    private static void sync(Path directory)throws Exception{
        try(var channel=java.nio.channels.FileChannel.open(directory,java.nio.file.StandardOpenOption.READ)){channel.force(true);}
    }
    private static byte[] command(List<String> args,Duration limit,int bound)throws Exception{
        var builder=new ProcessBuilder(args);builder.environment().clear();builder.environment().put("PATH","/usr/bin:/bin");builder.environment().put("LANG","C.UTF-8");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);var process=builder.start();
        try(var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()){
            var bytes=executor.submit(()->{try(var stream=process.getInputStream()){byte[] output=stream.readNBytes(bound+1);if(output.length>bound)throw ControllerEvidenceStore.denied();return output;}});
            try{
                if(!process.waitFor(limit.toNanos(),TimeUnit.NANOSECONDS))throw ControllerEvidenceStore.denied();
                byte[] output=bytes.get(1,TimeUnit.SECONDS);if(process.exitValue()!=0)throw ControllerEvidenceStore.denied();return output;
            }finally{process.destroyForcibly();process.waitFor(2,TimeUnit.SECONDS);bytes.cancel(true);}
        }
    }
}
