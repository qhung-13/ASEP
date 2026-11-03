package io.github.qhung13.asep.admission;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Exports Maven's resolved TEST classpath only when explicitly requested by the private harness. */
class ManualAdmissionProcessFixtureTest {
    @Test void actualPrivatePeerMainPreparesSelectedSyntheticTicket() throws Exception {
        String body="{\"id\":\"15001\",\"fields\":{\"project\":{\"id\":\"10001\"},\"issuetype\":{\"name\":\"Task\"},\"status\":{\"name\":\"To Do\"},\"summary\":\"Synthetic probe\",\"description\":null}}";
        var json=tools.jackson.databind.json.JsonMapper.builder().build();
        String config=json.writeValueAsString(java.util.Map.of("issueId","15001","projectId","10001","prefix","synthetic:vn-t10-it-"+"a".repeat(32),"body",body,"repositoryBaseSha","a".repeat(40),"policyId","synthetic-slice"));
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),ManualAdmissionProcessFixture.class.getName());
        builder.environment().clear();builder.environment().put("LANG","C.UTF-8");builder.environment().put("TZ","UTC");builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        var child=builder.start();try{
            try(var input=child.outputWriter()){input.write(config+"\n"+json.writeValueAsString(java.util.Map.of("action","prepare","slot","original","operationId",java.util.UUID.randomUUID().toString()))+"\n");}
            assertTrue(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0,child.exitValue());byte[] output=child.getInputStream().readNBytes(8193);assertTrue(output.length<=8192);
            var prepared=json.readTree(output);assertEquals("prepared",prepared.get("kind").asString());
            assertEquals("87786140",prepared.get("command").get("gitLabProjectId").asString());
        }finally{child.destroyForcibly();assertTrue(child.waitFor(2,java.util.concurrent.TimeUnit.SECONDS));}
    }
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
