package io.github.qhung13.asep.codedelivery;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;

/** One atomically published controller batch. Ownership/anchor is established by
 * protected runtime configuration, never by the delivery JSON. Digests detect
 * corruption; protection from replacing the complete batch depends on host isolation.
 */
public final class ControllerEvidenceStore implements Artifacts, Receipts {
    record Index(String requestSha256,String sealSha256,String verificationSha256) {}
    private final Path root;private final long owner;
    private final TestProjectDeliveryRequest request;private final Seal seal;private final Verification verification;
    public ControllerEvidenceStore(Path evidenceRoot,long controllerUid,UUID operation) {
        try {
            root=evidenceRoot.resolve("operations").resolve(operation.toString()).toAbsolutePath().normalize();owner=controllerUid;
            byte[] indexBytes=readFile("index.json",2048);
            var index=JdbcDeliveryLedger.JSON.readValue(indexBytes,Index.class);
            request=JdbcDeliveryLedger.JSON.readValue(checked("request.json",index.requestSha256()),TestProjectDeliveryRequest.class);
            seal=JdbcDeliveryLedger.JSON.readValue(checked("seal.json",index.sealSha256()),Seal.class);
            verification=JdbcDeliveryLedger.JSON.readValue(checked("verification.json",index.verificationSha256()),Verification.class);
            if(!operation.equals(request.operationId())||!request.sealReceiptId().equals(seal.receiptId())
                    ||!request.verificationReceiptId().equals(verification.receiptId())) throw denied();
        } catch(RuntimeException invalid){throw denied();}
    }
    public TestProjectDeliveryRequest request(){return request;}
    @Override public byte[] read(ObjectType type,String sha,int maximumBytes){
        if(type==null||sha==null||!sha.matches("[0-9a-f]{40}")||maximumBytes<1||maximumBytes>1048576)throw denied();
        return readFile("objects/"+type.name().toLowerCase(Locale.ROOT)+"/"+sha,maximumBytes);
    }
    @Override public Seal seal(UUID id){return request.sealReceiptId().equals(id)?seal:null;}
    @Override public Verification verification(UUID id){return request.verificationReceiptId().equals(id)?verification:null;}
    private byte[] checked(String file,String expected){byte[] bytes=readFile(file,8192);if(!digest(bytes).equals(expected))throw denied();return bytes;}
    private byte[] readFile(String relative,int bound){return ProtectedOperatorFiles.read(root,root.resolve(relative),owner,bound,false);}
    static String digest(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception impossible){throw denied();}}
    static IllegalStateException denied(){return new IllegalStateException("Controller evidence unavailable or invalid");}
}
