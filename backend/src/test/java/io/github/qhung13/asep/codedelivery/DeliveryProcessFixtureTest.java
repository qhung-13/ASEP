package io.github.qhung13.asep.codedelivery;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DeliveryProcessFixtureTest {
    @Test void privilegedFixtureIsNeverPackagedAsProductionEntry(){
        assertTrue(DeliveryProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("/test-classes/"));
        assertFalse(Files.exists(Path.of("target/classes/io/github/qhung13/asep/codedelivery/DeliveryProcessFixture.class")));
    }
}
