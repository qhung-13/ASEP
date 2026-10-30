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
    private final Path harness;private final String harnessHash,image;private final Duration timeout;
    public IsolatedProbeVerifier(Path reviewedHarness,String harnessSha256,String imageSha256,Duration timeout){
        this.harness=reviewedHarness.toAbsolutePath().normalize();this.harnessHash=harnessSha256;this.image=imageSha256;
        if(!harnessSha256.matches("[0-9a-f]{64}")||!imageSha256.matches("[0-9a-f]{64}")||timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(60))>0)throw ControllerEvidenceStore.denied();
        this.timeout=timeout;
    }
    @Override public VerificationReport verify(Path frozen,TestProjectDeliveryRequest request){
        Path privateJob=null;String cid=null;String nonce=UUID.randomUUID().toString();
        try{
            if(!Files.isRegularFile(harness,java.nio.file.LinkOption.NOFOLLOW_LINKS)||Files.size(harness)>134217728
                    ||!ControllerEvidenceStore.digest(Files.readAllBytes(harness)).equals(harnessHash))throw ControllerEvidenceStore.denied();
            privateJob=Files.createTempDirectory("asep-verifier-");Path cidfile=privateJob.resolve("container.cid");
            // Image ID is content addressed. Docker create does not pull this sha256 ID.
            command(List.of("/usr/bin/docker","create","--pull=never","--cidfile",cidfile.toString(),"--label",LABEL+"="+nonce,
                    "--network","none","--user","65534:65534","--cap-drop","ALL","--security-opt","no-new-privileges:true",
                    "--read-only","--tmpfs","/tmp:rw,nosuid,nodev,noexec,size=16777216","--memory","256m","--cpus","1","--pids-limit","64",
                    "--mount","type=bind,source="+harness+",target=/verifier.jar,readonly",
                    "--mount","type=bind,source="+frozen.toAbsolutePath().normalize()+",target=/candidate,readonly",
                    "--entrypoint","java","sha256:"+image,"-XX:-HeapDumpOnOutOfMemoryError","-Dloader.main=io.github.qhung13.asep.codedelivery.ProbeVerificationMain",
                    "-cp","/verifier.jar","org.springframework.boot.loader.launch.PropertiesLauncher","/candidate"),Duration.ofSeconds(5),2048);
            cid=Files.readString(cidfile).strip();if(!cid.matches("[0-9a-f]{64}"))throw ControllerEvidenceStore.denied();
            byte[] output=command(List.of("/usr/bin/docker","start","--attach",cid),timeout,4096);
            var result=JdbcDeliveryLedger.JSON.readValue(output,ProbeVerificationMain.Result.class);
            return new VerificationReport(result.discovered(),result.executed(),result.skipped(),result.failed(),0,false,ControllerEvidenceStore.digest(output),harnessHash,image);
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw ControllerEvidenceStore.denied();}
        catch(Exception denied){throw ControllerEvidenceStore.denied();}
        finally{
            boolean interrupted=Thread.interrupted();
            // If create succeeded but its response was lost, recover the owned CID file.
            try{
                if(cid==null&&privateJob!=null&&Files.isRegularFile(privateJob.resolve("container.cid")))cid=Files.readString(privateJob.resolve("container.cid")).strip();
                if(cid!=null&&cid.matches("[0-9a-f]{64}")){
                    String actual=new String(command(List.of("/usr/bin/docker","inspect","--format","{{index .Config.Labels \""+LABEL+"\"}}",cid),Duration.ofSeconds(5),256),StandardCharsets.US_ASCII).strip();
                    if(!actual.equals(nonce))throw ControllerEvidenceStore.denied();
                    command(List.of("/usr/bin/docker","rm","--force",cid),Duration.ofSeconds(5),1024);
                }
                if(privateJob!=null){Files.deleteIfExists(privateJob.resolve("container.cid"));Files.delete(privateJob);}
            }catch(InterruptedException cleanupInterrupted){interrupted=true;throw ControllerEvidenceStore.denied();}
            catch(Exception cleanupFailed){throw ControllerEvidenceStore.denied();}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
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
