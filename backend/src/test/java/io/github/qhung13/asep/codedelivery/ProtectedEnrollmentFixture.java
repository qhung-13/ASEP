package io.github.qhung13.asep.codedelivery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Owned synthetic records and dummy secrets. This fixture simulates the root
 * trust anchor under one test UID; it never constitutes real enrollment or an
 * independent operational approval. All actual record parsing/hashing is reused.
 */
final class ProtectedEnrollmentFixture implements ProtectedDeliveryExecutor.Credentials {
    final Path root;final long uid;final TestProjectDeliveryRequest request;
    final MutableClock clock=new MutableClock();
    final AtomicInteger readLoads=new AtomicInteger(),writeLoads=new AtomicInteger();
    final ProtectedDeliveryEnrollment enrollment;
    ProtectedEnrollmentFixture(Path root,TestProjectDeliveryRequest request)throws Exception{
        this.root=root;this.request=request;Files.createDirectories(root);
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));
        uid=((Number)Files.getAttribute(root,"unix:uid")).longValue();
        Files.createDirectories(root.resolve("evidence"),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        write("read.credential","dummy-fixture-read".getBytes(StandardCharsets.US_ASCII));
        write("write.credential","dummy-fixture-write".getBytes(StandardCharsets.US_ASCII));
        enrollment=new ProtectedDeliveryEnrollment((relative,bound)->ProtectedOperatorFiles.read(root,root.resolve(relative),uid,bound,false),clock);
        if(!Files.exists(root.resolve(request.operationId()+".json")))publish();
    }
    void publish()throws Exception{
        var facts=new HashMap<String,ProtectedDeliveryEnrollment.Fact>();Instant start=clock.instant().minusSeconds(1);
        for(String subject:ProtectedDeliveryEnrollment.REQUIRED){
            var report=new ProtectedDeliveryEnrollment.ReadOnlyObservation(subject,"synthetic owned collector","GET",200,true,start,clock.instant(),"SAFE_FOR_EXACT_PROBE",request.bodyHash(),subject.equals("protected-branches")?1:0);
            byte[] bytes=JdbcDeliveryLedger.JSON.writeValueAsBytes(report);String digest=ControllerEvidenceStore.digest(bytes);
            write("evidence/"+digest+".json",bytes);facts.put(subject,new ProtectedDeliveryEnrollment.Fact("VERIFIED",digest,"synthetic owned collector"));
        }
        var permit=new ProtectedDeliveryEnrollment.Permit(request.bodyHash(),request.projectId(),"qhung-13/asep-m01-sandbox",request.sourceBranch(),"main",request.safeCiProfileSha256(),
                digest("read.credential"),digest("write.credential"),30,Set.of("api","write_repository"),start.plusSeconds(300),start,clock.instant(),start.plusSeconds(300),facts);
        write(request.operationId()+".json",JdbcDeliveryLedger.JSON.writeValueAsBytes(permit));
    }
    void write(String relative,byte[] bytes)throws Exception{
        Path path=root.resolve(relative);Files.write(path,bytes);Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
    }
    String digest(String relative)throws Exception{return ControllerEvidenceStore.digest(Files.readAllBytes(root.resolve(relative)));}
    @Override public String read(String expected){readLoads.incrementAndGet();return credential("read.credential",expected);}
    @Override public String write(String expected){writeLoads.incrementAndGet();return credential("write.credential",expected);}
    private String credential(String relative,String expected){
        byte[] bytes=ProtectedOperatorFiles.read(root,root.resolve(relative),uid,4096,true);
        if(expected!=null&&!expected.equals(ControllerEvidenceStore.digest(bytes)))throw ProtectedDeliveryRuntime.denied();
        return new String(bytes,StandardCharsets.US_ASCII);
    }
    static final class MutableClock extends Clock {
        private Instant override;
        void advance(long seconds){override=instant().plusSeconds(seconds);}
        @Override public Instant instant(){return override==null?Instant.now():override;}
        @Override public ZoneId getZone(){return java.time.ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){if(!zone.equals(getZone()))throw new IllegalArgumentException();return this;}
    }
}
