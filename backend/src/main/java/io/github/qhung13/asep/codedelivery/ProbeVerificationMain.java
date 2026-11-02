package io.github.qhung13.asep.codedelivery;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;

/** Protected, fixed acceptance harness for the harmless probe profile only. It
 * never loads candidate classes, scripts, filters, build files or MR content.
 * Run in an unprivileged network-less sandbox with candidate objects read-only.
 */
public final class ProbeVerificationMain {
    public record Result(int discovered,int executed,int skipped,int failed) {}
    private ProbeVerificationMain(){}
    public static void main(String[] args){
        int executed=0,failed=0;
        try{
            if(args.length!=1)throw new IllegalArgumentException();Path root=Path.of(args[0]);
            byte[] json=Files.readAllBytes(root.resolve("request.json"));if(json.length>8192)throw new IllegalArgumentException();
            var request=JdbcDeliveryLedger.JSON.readValue(json,TestProjectDeliveryRequest.class);
            Artifacts objects=(type,sha,bound)->{
                try{
                    Path path=root.resolve("objects").resolve(type.name().toLowerCase(java.util.Locale.ROOT)).resolve(sha);
                    if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException();
                    try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] body=input.readNBytes(bound+1);if(body.length>bound)throw new IllegalStateException();return body;}
                }catch(Exception unavailable){throw new IllegalStateException("Invalid sealed object");}
            };
            // Discovered check 1: complete permitted object/change graph on exact base.
            executed++;
            var dummyProfile=new Profile(request.verifierProfileSha256(),"0".repeat(64),"0".repeat(64),2,java.time.Duration.ofSeconds(1));
            new TestProjectDeliveryPreparation(objects,Receipts.unavailable(),dummyProfile,java.time.Clock.systemUTC()).inspectObjects(request);
            // Discovered check 2: independently reconstruct the exact human-reviewable text.
            executed++;
            byte[] expected=("VN-T14 isolated delivery probe\nOperation: "+request.operationId()+"\n").getBytes(StandardCharsets.US_ASCII);
            if(!Arrays.equals(expected,objects.read(ObjectType.BLOB,gitId(ObjectType.BLOB,expected),256)))throw new IllegalStateException();
        }catch(Exception invalid){failed++;}
        System.out.println(JdbcDeliveryLedger.JSON.writeValueAsString(new Result(2,executed,0,failed)));
        if(failed!=0||executed!=2)System.exit(1);
    }
}
