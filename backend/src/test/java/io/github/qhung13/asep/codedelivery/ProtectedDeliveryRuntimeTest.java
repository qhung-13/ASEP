package io.github.qhung13.asep.codedelivery;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedDeliveryRuntimeTest {
    @Test void checkoutCannotAcquireEitherProtectedServiceIdentity(){
        assertThrows(IllegalStateException.class,()->ProtectedDeliveryRuntime.authenticate("candidate-controller"));
        assertThrows(IllegalStateException.class,()->ProtectedDeliveryRuntime.authenticate("delivery-executor"));
        assertThrows(IllegalStateException.class,()->ProtectedDeliveryRuntime.authenticate("agent"));
    }
    @Test void noDefaultCredentialOrEnvironmentFallback(){
        assertThrows(IllegalStateException.class,()->ProtectedDeliveryRuntime.credential(java.nio.file.Path.of("/etc/asep/unprovisioned"),0,null));
        assertThrows(RuntimeException.class,()->DeliveryTlsIdentity.context(new byte[0],new byte[0],new byte[0],"spiffe://asep.local/code-delivery"));
    }
    @ParameterizedTest @ValueSource(strings={"valid","403","partial","wrong-request","missing-time","old-time"})
    void reportDigestDoesNotConvertIncompleteObservationsIntoSafety(String fault){
        Instant start=Instant.parse("2026-10-30T00:00:00Z");String hash="d".repeat(64);
        var permit=new ProtectedDeliveryEnrollment.Permit(hash,87786140,"qhung-13/asep-m01-sandbox","test/vn-t14/fixture","main","e".repeat(64),"a".repeat(64),"b".repeat(64),30,Set.of("api","write_repository"),start.plusSeconds(300),start,start.plusSeconds(1),start.plusSeconds(60),Map.of());
        var fact=new ProtectedDeliveryEnrollment.Fact("VERIFIED","c".repeat(64),"Owner collector");
        var observation=new ProtectedDeliveryEnrollment.ReadOnlyObservation("candidate-ci-includes","Owner collector","GET",fault.equals("403")?403:200,!fault.equals("partial"),fault.equals("missing-time")?null:fault.equals("old-time")?start.minusSeconds(1):start,start.plusSeconds(1),"SAFE_FOR_EXACT_PROBE",fault.equals("wrong-request")?"f".repeat(64):hash,null);
        if(fault.equals("valid"))assertDoesNotThrow(()->ProtectedDeliveryEnrollment.validateObservation(observation,"candidate-ci-includes",fact,permit));
        else assertThrows(IllegalStateException.class,()->ProtectedDeliveryEnrollment.validateObservation(observation,"candidate-ci-includes",fact,permit));
    }
    @ParameterizedTest @ValueSource(strings={"valid","duplicate-uid","zero-uid","verifier-uid","wrong-service","wrong-origin","invalid-port","shared-credential","invalid-profile","overage"})
    void runtimeSettingsAreStrictAndDoNotAcceptDeclaredIdentityAlone(String fault)throws Exception{
        var map=JdbcDeliveryLedger.JSON.readValue(java.nio.file.Files.readString(java.nio.file.Path.of("../deploy/delivery/delivery-executor.json.example")),java.util.Map.class);
        @SuppressWarnings("unchecked") var values=new java.util.HashMap<String,Object>((Map<String,Object>)map);
        values.put("serviceUid",5004);values.put("agentUid",5001);values.put("controllerUid",5002);values.put("operatorUid",5003);values.put("executorUid",5004);values.put("orchestratorUid",5005);
        for(String key:java.util.List.of("installedJarSha256","goServerCertificateSha256","profileSha256","harnessSha256","imageSha256"))values.put(key,"a".repeat(64));
        switch(fault){case "duplicate-uid"->values.put("operatorUid",5004);case "zero-uid"->values.put("agentUid",0);case "verifier-uid"->values.put("agentUid",65534);case "wrong-service"->values.put("serviceUid",5001);case "wrong-origin"->values.put("goOrigin","https://localhost:9443");case "invalid-port"->values.put("goOrigin","https://127.0.0.1:99999");case "shared-credential"->values.put("writeCredential",values.get("readCredential"));case "invalid-profile"->values.put("profileSha256","declared-success");case "overage"->values.put("maximumReceiptAgeSeconds",3601);default->{}}
        var settings=JdbcDeliveryLedger.JSON.convertValue(values,ProtectedDeliveryRuntime.Settings.class);
        if(fault.equals("valid"))assertDoesNotThrow(()->ProtectedDeliveryRuntime.validate(settings,"delivery-executor"));
        else assertThrows(RuntimeException.class,()->ProtectedDeliveryRuntime.validate(settings,"delivery-executor"));
        // Valid structure does not authenticate an OS identity or installed artifact.
        assertThrows(IllegalStateException.class,()->ProtectedDeliveryRuntime.authenticate("delivery-executor"));
    }
    // This is a pure protected-record validation fixture, not actual enrollment.
    @ParameterizedTest @ValueSource(strings={"valid","wrong-project","wrong-path","wrong-source","wrong-target","stale-start","future-end","expired","incomplete","403","missing-provenance","reused-credential","overprivileged","wrong-request"})
    void rootPermitIsExactCompleteAndFreshFromStart(String fault){
        var r=new TestProjectDeliveryRequest(java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),3,java.util.UUID.randomUUID(),"runtime-fixture",1,"a".repeat(40),"a".repeat(40),"b".repeat(40),"c".repeat(40),"d".repeat(64),java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),"e".repeat(64),"f".repeat(64));Instant now=Instant.parse("2026-10-30T00:00:00Z");
        var facts=new HashMap<String,ProtectedDeliveryEnrollment.Fact>();
        ProtectedDeliveryEnrollment.REQUIRED.forEach(key->facts.put(key,new ProtectedDeliveryEnrollment.Fact("VERIFIED","f".repeat(64),"Owner fixture observation")));
        if(fault.equals("incomplete"))facts.remove("inherited-group-compliance");
        if(fault.equals("403"))facts.put("variable-metadata-all-levels",new ProtectedDeliveryEnrollment.Fact("UNKNOWN_HTTP_403","f".repeat(64),"Owner fixture observation"));
        if(fault.equals("missing-provenance"))facts.put("candidate-ci-includes",new ProtectedDeliveryEnrollment.Fact("VERIFIED","f".repeat(64),null));
        var permit=new ProtectedDeliveryEnrollment.Permit(fault.equals("wrong-request")?"0".repeat(64):r.bodyHash(),fault.equals("wrong-project")?42:87786140,
            fault.equals("wrong-path")?"other/repo":"qhung-13/asep-m01-sandbox",fault.equals("wrong-source")?"main":r.sourceBranch(),fault.equals("wrong-target")?"other":"main",
            r.safeCiProfileSha256(),"a".repeat(64),fault.equals("reused-credential")?"a".repeat(64):"b".repeat(64),fault.equals("overprivileged")?40:30,Set.of("api","write_repository"),now.plusSeconds(300),
            fault.equals("stale-start")?now.minusSeconds(301):now.minusSeconds(5),fault.equals("future-end")?now.plusSeconds(1):now.minusSeconds(1),fault.equals("expired")?now:now.plusSeconds(60),facts);
        if(fault.equals("valid"))assertDoesNotThrow(()->ProtectedDeliveryEnrollment.validate(permit,r,now));
        else assertThrows(IllegalStateException.class,()->ProtectedDeliveryEnrollment.validate(permit,r,now));
    }
}
