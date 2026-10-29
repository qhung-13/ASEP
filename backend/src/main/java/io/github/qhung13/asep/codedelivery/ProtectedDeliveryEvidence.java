package io.github.qhung13.asep.codedelivery;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;

/** Reads immutable controller-owned evidence; never issues a seal or a verifier PASS.
 * The trusted host binds this root/owner through protected configuration. Agent-owned files
 * are not eligible in the operator deployment. Receipt IDs remain references, not authority.
 */
public final class ProtectedDeliveryEvidence implements Artifacts, Receipts {
    private final Path root;
    private final long controllerUid;
    public ProtectedDeliveryEvidence(Path root,long controllerUid) {
        this.root=Objects.requireNonNull(root).toAbsolutePath().normalize();
        if (controllerUid<0) throw ProtectedOperatorFiles.denied();
        this.controllerUid=controllerUid;
    }
    @Override public byte[] read(ObjectType type,String sha,int maximumBytes) {
        if (type==null || sha==null || !sha.matches("[0-9a-f]{40}") || maximumBytes<1 || maximumBytes>1048576) throw ProtectedOperatorFiles.denied();
        return ProtectedOperatorFiles.read(root,root.resolve("objects").resolve(type.name().toLowerCase(Locale.ROOT)).resolve(sha),controllerUid,maximumBytes,false);
    }
    @Override public Seal seal(UUID id) { return receipt("seals",id,Seal.class); }
    @Override public Verification verification(UUID id) { return receipt("verifications",id,Verification.class); }
    private <T> T receipt(String kind,UUID id,Class<T> type) {
        try {
            Objects.requireNonNull(id);
            byte[] bytes=ProtectedOperatorFiles.read(root,root.resolve(kind).resolve(id+".json"),controllerUid,8192,false);
            return JdbcDeliveryLedger.JSON.readValue(bytes,type);
        } catch (RuntimeException unavailable) { throw ProtectedOperatorFiles.denied(); }
    }
}
