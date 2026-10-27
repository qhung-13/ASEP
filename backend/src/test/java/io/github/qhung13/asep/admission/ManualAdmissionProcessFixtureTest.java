package io.github.qhung13.asep.admission;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exports Maven's resolved TEST classpath only when explicitly requested by the private harness. */
class ManualAdmissionProcessFixtureTest {
    @Test void fixtureIsAvailableOnlyInTestOutput() throws Exception {
        var location = ManualAdmissionProcessFixture.class.getProtectionDomain().getCodeSource().getLocation();
        assertTrue(location.getPath().endsWith("/test-classes/"));
        assertFalse(Files.exists(Path.of("target/classes/io/github/qhung13/asep/admission/ManualAdmissionProcessFixture.class")));
        String destination = System.getProperty("asep.manualBridge.classpathFile");
        if (destination != null) {
            String classpath = System.getProperty("surefire.test.class.path");
            assertNotNull(classpath);
            Files.writeString(Path.of(destination), classpath);
        }
    }
}
