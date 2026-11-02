package io.github.qhung13.asep.codedelivery;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class RestrictedOperatorRuntimeTest {
    @TempDir Path directory;
    @Test void kernelIdentityRequiresEveryUidToBeTheDistinctOperator() {
        assertTrue(RestrictedOperatorRuntime.uidsMatch("Name:\ttest\nUid:\t1001\t1001\t1001\t1001\n",1001,1000));
        assertFalse(RestrictedOperatorRuntime.uidsMatch("Uid:\t1000\t1000\t1000\t1000\n",1000,1000));
        assertFalse(RestrictedOperatorRuntime.uidsMatch("Uid:\t1001\t1000\t1001\t1001\n",1001,1000));
        assertFalse(RestrictedOperatorRuntime.uidsMatch("Uid:\t1001\t1001\t1001\n",1001,1000));
        assertFalse(RestrictedOperatorRuntime.uidsMatch("Uid:\t1001 1001 1001 1001\nUid:\t1001 1001 1001 1001",1001,1000));
        assertFalse(RestrictedOperatorRuntime.uidsMatch("user.name=owner",1001,1000));
    }
    @Test void executableMustComeFromTheFixedInstalledArtifact() {
        assertTrue(RestrictedOperatorRuntime.installedLocation("file:///opt/asep/owner-approval/asep.jar"));
        assertTrue(RestrictedOperatorRuntime.installedLocation("jar:nested:/opt/asep/owner-approval/asep.jar/!BOOT-INF/classes/!/"));
        assertFalse(RestrictedOperatorRuntime.installedLocation("file:/tmp/asep.jar"));
        assertFalse(RestrictedOperatorRuntime.installedLocation("file:///tmp/opt/asep/owner-approval/asep.jar"));
    }
    @ParameterizedTest @ValueSource(strings={"symlink","hardlink","writable-file","writable-root","oversize","wrong-owner"})
    void protectedEvidenceFilesRejectUntrustedStorage(String fault) throws Exception {
        Path file=directory.resolve("evidence");Files.writeString(file,"PRIVATE_UNTRUSTED_RECORD");
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
        long uid=((Number)Files.getAttribute(file,"unix:uid")).longValue();
        if(fault.equals("symlink")) { Path link=directory.resolve("link");Files.createSymbolicLink(link,file);file=link; }
        if(fault.equals("hardlink")) Files.createLink(directory.resolve("other"),file);
        if(fault.equals("writable-file")) Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-rw----"));
        if(fault.equals("writable-root")) Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwxrwx---"));
        Path selected=file;long owner=fault.equals("wrong-owner")?uid+123:uid;
        var failure=assertThrows(IllegalStateException.class,()->ProtectedOperatorFiles.read(directory,selected,owner,fault.equals("oversize")?1:256,false));
        assertNull(failure.getCause());assertFalse(failure.toString().contains("PRIVATE"));
    }
    @Test void validOwnedReceiptCanBeReadWithoutIssuingAnything() throws Exception {
        long uid=((Number)Files.getAttribute(directory,"unix:uid")).longValue();
        Path seals=Files.createDirectory(directory.resolve("seals"));
        Files.setPosixFilePermissions(directory,PosixFilePermissions.fromString("rwx------"));
        Files.setPosixFilePermissions(seals,PosixFilePermissions.fromString("rwx------"));
        UUID receipt=UUID.randomUUID(),run=UUID.randomUUID(),attempt=UUID.randomUUID();
        var now=java.time.Instant.parse("2026-10-29T00:00:00Z");
        var seal=new TestProjectDeliveryPreparation.Seal(receipt,run,attempt,"a".repeat(40),"b".repeat(40),"c".repeat(40),"d".repeat(64),now,now,now.plusSeconds(300));
        Files.writeString(seals.resolve(receipt+".json"),JdbcDeliveryLedger.JSON.writeValueAsString(seal));
        Files.setPosixFilePermissions(seals.resolve(receipt+".json"),PosixFilePermissions.fromString("rw-------"));
        assertEquals(seal,JdbcDeliveryLedger.JSON.readValue(JdbcDeliveryLedger.JSON.writeValueAsBytes(seal),TestProjectDeliveryPreparation.Seal.class));
        var evidence=new ProtectedDeliveryEvidence(directory,uid);
        assertEquals(seal,evidence.seal(receipt));
        assertThrows(IllegalStateException.class,()->evidence.seal(UUID.randomUUID()));
    }
}
