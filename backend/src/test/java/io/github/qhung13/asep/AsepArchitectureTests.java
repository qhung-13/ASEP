package io.github.qhung13.asep;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class AsepArchitectureTests {

    @Test
    void verifiesModuleBoundaries() {
        ApplicationModules.of(AsepApplication.class).verify();
    }
}
