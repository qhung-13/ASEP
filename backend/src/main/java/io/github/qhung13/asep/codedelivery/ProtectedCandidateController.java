package io.github.qhung13.asep.codedelivery;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;

/** Controlled single-file probe producer. It does not impersonate agent implementation,
 * Jira investigation or planning. The controller owns the only writer, closes it before
 * sealing and verifies a private read-only snapshot in a separate process boundary.
 * No receipt is published until the complete batch passes and is atomically renamed.
 */
public final class ProtectedCandidateController {
    public record Job(UUID operationId,UUID runId,UUID producerAttemptId,long deliveryRunVersion,
            UUID grantId,String policyId,long policyEpoch,String baseSha,String safeCiProfileSha256) {}
    public record VerificationReport(int discovered,int executed,int skipped,int failed,int exitCode,
            boolean timedOut,String logsSha256,String harnessSha256,String imageSha256) {}
    public interface IndependentVerifier {VerificationReport verify(Path frozenCandidate,TestProjectDeliveryRequest request);}
    private final Path root;private final long controllerUid;private final Profile profile;
    private final Artifacts baseSource;private final IndependentVerifier verifier;private final Clock clock;
    public ProtectedCandidateController(Path root,long controllerUid,Profile profile,Artifacts baseSource,IndependentVerifier verifier,Clock clock){
        this.root=root.toAbsolutePath().normalize();this.controllerUid=controllerUid;this.profile=profile;
        this.baseSource=baseSource;this.verifier=verifier;this.clock=clock;
    }
    public ControllerEvidenceStore existing(Job job){
        var old=new ControllerEvidenceStore(root,controllerUid,job.operationId());
        if(!matches(job,old.request()))throw ControllerEvidenceStore.denied();
        new TestProjectDeliveryPreparation(old,old,profile,clock).prepare(old.request());return old;
    }
    public ControllerEvidenceStore produce(Job job){
        Path staging=null;String phase="start";
        try {
            ProtectedOperatorFiles.directory(root,controllerUid,false);
            Path operations=root.resolve("operations");directory(operations);
            Path published=operations.resolve(job.operationId().toString());
            // Exact retry reads the original batch; it never refreshes receipts or TTL.
            if(Files.exists(published,LinkOption.NOFOLLOW_LINKS)){
                var old=new ControllerEvidenceStore(root,controllerUid,job.operationId());
                if(!matches(job,old.request()))throw ControllerEvidenceStore.denied();
                new TestProjectDeliveryPreparation(old,old,profile,clock).prepare(old.request());return old;
            }
            phase="stage";staging=Files.createTempDirectory(operations,".issuing-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            phase="base";byte[] suppliedBase=baseSource.read(ObjectType.COMMIT,job.baseSha(),8192);
            if(suppliedBase==null||suppliedBase.length>8192)throw ControllerEvidenceStore.denied();
            byte[] base=suppliedBase.clone();
            if(base.length>8192||!gitId(ObjectType.COMMIT,base).equals(job.baseSha()))throw ControllerEvidenceStore.denied();
            var parsed=commit(base);byte[] suppliedTree=baseSource.read(ObjectType.TREE,parsed.tree(),1048576);
            if(suppliedTree==null||suppliedTree.length>1048576)throw ControllerEvidenceStore.denied();
            byte[] originalTree=suppliedTree.clone();
            if(originalTree.length>1048576||!gitId(ObjectType.TREE,originalTree).equals(parsed.tree()))throw ControllerEvidenceStore.denied();
            var entries=new ArrayList<>(tree(originalTree));String path="vn-t14-probe-"+job.operationId()+".txt";
            if(entries.stream().anyMatch(e->e.name().equals(path)))throw ControllerEvidenceStore.denied();
            byte[] probe=probeContent(job.operationId());String blob=gitId(ObjectType.BLOB,probe);
            entries.add(new Entry("100644",path,blob));entries.sort(Comparator.comparing(e->e.name()+(e.mode().equals("40000")?"/":"")));
            var output=new ByteArrayOutputStream();for(var e:entries){output.writeBytes((e.mode()+" "+e.name()+"\0").getBytes(StandardCharsets.US_ASCII));output.writeBytes(HexFormat.of().parseHex(e.sha()));}
            byte[] tree=output.toByteArray();String candidateTree=gitId(ObjectType.TREE,tree);
            long seconds=clock.instant().getEpochSecond();
            byte[] candidate=("tree "+candidateTree+"\nparent "+job.baseSha()+"\nauthor ASEP Controller <controller@invalid> "+seconds+" +0000\ncommitter ASEP Controller <controller@invalid> "+seconds+" +0000\n\nVN-T14 isolated test "+job.operationId()+"\n").getBytes(StandardCharsets.US_ASCII);
            UUID sealId=UUID.randomUUID(),verificationId=UUID.randomUUID();String sha=gitId(ObjectType.COMMIT,candidate);
            var partial=request(job,sha,candidateTree,"0".repeat(64),sealId,verificationId);
            var request=request(job,sha,candidateTree,manifestHash(partial),sealId,verificationId);
            phase="objects";for(var e:Map.of("commit/"+job.baseSha(),base,"commit/"+sha,candidate,"tree/"+parsed.tree(),originalTree,"tree/"+candidateTree,tree,"blob/"+blob,probe).entrySet()){
                Path object=staging.resolve("objects").resolve(e.getKey());directory(object.getParent().getParent());directory(object.getParent());write(object,e.getValue());
            }
            write(staging.resolve("request.json"),JdbcDeliveryLedger.JSON.writeValueAsBytes(request));
            // The controller is the only producer. Every object descriptor is now closed;
            // the independent verifier receives this snapshot read-only, never the writer.
            Instant stopped=clock.instant(),sealed=clock.instant();
            var seal=new Seal(sealId,job.runId(),job.producerAttemptId(),job.baseSha(),sha,candidateTree,request.manifestSha256(),stopped,sealed,sealed.plus(profile.maximumReceiptAge()));
            // The child UID can read only this mount, not the private controller parent.
            try(var frozen=Files.walk(staging)) {for(Path p:frozen.toList())Files.setPosixFilePermissions(p,PosixFilePermissions.fromString(Files.isDirectory(p)?"r-xr-xr-x":"r--r--r--"));}
            phase="verify";VerificationReport report;
            try {report=verifier.verify(staging,request);} finally {Files.setPosixFilePermissions(staging,PosixFilePermissions.fromString("rwxr-x---"));}
            phase="receipts";Instant verified=clock.instant();
            if(report==null||report.timedOut()||report.exitCode()!=0||report.discovered()<profile.minimumTests()
                    ||report.executed()!=report.discovered()||report.failed()!=0||report.skipped()!=0
                    ||!profile.harnessSha256().equals(report.harnessSha256())||!profile.imageSha256().equals(report.imageSha256())
                    ||report.logsSha256()==null||!report.logsSha256().matches("[0-9a-f]{64}"))throw ControllerEvidenceStore.denied();
            var verification=new Verification(verificationId,job.runId(),job.producerAttemptId(),sealId,request.manifestSha256(),profile.profileSha256(),profile.harnessSha256(),profile.imageSha256(),report.logsSha256(),report.discovered(),report.executed(),report.skipped(),report.failed(),report.exitCode(),report.timedOut(),verified,verified.plus(profile.maximumReceiptAge()));
            byte[] sealBytes=JdbcDeliveryLedger.JSON.writeValueAsBytes(seal),verificationBytes=JdbcDeliveryLedger.JSON.writeValueAsBytes(verification);
            write(staging.resolve("seal.json"),sealBytes);write(staging.resolve("verification.json"),verificationBytes);
            byte[] requestBytes=ProtectedOperatorFiles.read(staging,staging.resolve("request.json"),controllerUid,8192,false);
            write(staging.resolve("index.json"),JdbcDeliveryLedger.JSON.writeValueAsBytes(new ControllerEvidenceStore.Index(ControllerEvidenceStore.digest(requestBytes),ControllerEvidenceStore.digest(sealBytes),ControllerEvidenceStore.digest(verificationBytes))));
            phase="validate";
            // Validate the actual persisted snapshot and real report before publication.
            var evidence=loadStaging(staging,request,seal,verification);new TestProjectDeliveryPreparation(evidence,evidence,profile,clock).prepare(request);
            phase="publish";
            // Serialize publishers on this controller-owned directory. ATOMIC_MOVE is not
            // exclusive on every filesystem; the lock + second existence check prevent replacement.
            try(var lock=FileChannel.open(operations.resolve(".publish.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);var held=lock.lock()){
                if(Files.exists(published,LinkOption.NOFOLLOW_LINKS))throw ControllerEvidenceStore.denied();
                Files.move(staging,published,StandardCopyOption.ATOMIC_MOVE);staging=null;
                try(var dir=FileChannel.open(operations,StandardOpenOption.READ)){dir.force(true);}
            }
            return new ControllerEvidenceStore(root,controllerUid,job.operationId());
        }catch(Exception denied){throw new IllegalStateException("Controller issuance denied at "+phase+" ("+denied.getClass().getSimpleName()+")"+(denied.getMessage()!=null&&denied.getMessage().startsWith("Isolated verifier denied at ")?" "+denied.getMessage():""));}
        finally{if(staging!=null)removeOwnStaging(staging);}
    }
    private ProtectedDeliveryEvidence loadStaging(Path staged,TestProjectDeliveryRequest r,Seal seal,Verification verification)throws Exception{
        directory(staged.resolve("seals"));directory(staged.resolve("verifications"));
        write(staged.resolve("seals").resolve(r.sealReceiptId()+".json"),JdbcDeliveryLedger.JSON.writeValueAsBytes(seal));
        write(staged.resolve("verifications").resolve(r.verificationReceiptId()+".json"),JdbcDeliveryLedger.JSON.writeValueAsBytes(verification));
        return new ProtectedDeliveryEvidence(staged,controllerUid);
    }
    private TestProjectDeliveryRequest request(Job j,String commit,String tree,String manifest,UUID seal,UUID verification){return new TestProjectDeliveryRequest(j.operationId(),j.runId(),j.producerAttemptId(),j.deliveryRunVersion(),j.grantId(),j.policyId(),j.policyEpoch(),j.baseSha(),j.baseSha(),commit,tree,manifest,seal,verification,profile.profileSha256(),j.safeCiProfileSha256());}
    private boolean matches(Job j,TestProjectDeliveryRequest r){return j.operationId().equals(r.operationId())&&j.runId().equals(r.runId())&&j.producerAttemptId().equals(r.attemptId())&&j.deliveryRunVersion()==r.runVersion()&&j.grantId().equals(r.ownerGrantId())&&j.policyId().equals(r.policyId())&&j.policyEpoch()==r.policyEpoch()&&j.baseSha().equals(r.baseSha())&&j.safeCiProfileSha256().equals(r.safeCiProfileSha256());}
    private void directory(Path path)throws Exception{if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwxr-x---")));ProtectedOperatorFiles.directory(path,controllerUid,false);}
    static void write(Path path,byte[] bytes)throws Exception{try(var channel=FileChannel.open(path,java.util.Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-r-----")))){var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}}
    private void removeOwnStaging(Path path){try(var directories=Files.walk(path)){for(Path p:directories.filter(f->Files.isDirectory(f,LinkOption.NOFOLLOW_LINKS)).toList())Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));}catch(Exception ignored){} try(var files=Files.walk(path)){for(Path p:files.sorted(Comparator.reverseOrder()).toList()){if(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rwx------"));Files.delete(p);}}catch(Exception ignored){/* No success claim: unpublished .issuing-* requires protected scavenging. */}}
}
