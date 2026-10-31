package io.github.qhung13.asep.codedelivery;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProtectedEnrollmentInspectionTest {
    @TempDir Path root;
    @ParameterizedTest @ValueSource(strings={"valid","403","malformed","missing-permissions","owner-reader","missing-branch-field","stale","corrupt-inventory"})
    void actualInspectionCombinesProtectedRecordsAndStrictLimitedReader(String fault)throws Exception{
        var request=new TestProjectDeliveryRequest(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),3,UUID.randomUUID(),"inspection-fixture",1,"a".repeat(40),"a".repeat(40),"b".repeat(40),"c".repeat(40),"d".repeat(64),UUID.randomUUID(),UUID.randomUUID(),"e".repeat(64),"f".repeat(64));
        var records=new ProtectedEnrollmentFixture(root,request);
        if(fault.equals("stale"))records.clock.advance(301);
        if(fault.equals("corrupt-inventory")){
            var fact=records.enrollment.require(request).facts().get("project-group-instance-runners");records.write("evidence/"+fact.evidenceSha256()+".json","{}".getBytes());
        }
        var project=new HashMap<String,Object>(Map.of("id",87786140,"path_with_namespace","qhung-13/asep-m01-sandbox","visibility","private","archived",false,"default_branch","main","builds_access_level","disabled","shared_runners_enabled",false,"permissions",Map.of("project_access",Map.of("access_level",fault.equals("owner-reader")?50:30))));
        if(fault.equals("missing-permissions"))project.remove("permissions");
        var branch=new HashMap<String,Object>(Map.of("name","main","commit",Map.of("id",request.expectedTargetSha()),"protected",true,"can_push",false));
        if(fault.equals("missing-branch-field"))branch.remove("protected");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{try{
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(fault.equals("403")){exchange.sendResponseHeaders(403,-1);return;}
            byte[] bytes=fault.equals("malformed")?"{".getBytes():JdbcDeliveryLedger.JSON.writeValueAsBytes(exchange.getRequestURI().getPath().endsWith("main")?branch:project);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
        }finally{exchange.close();}});server.start();
        try(var transport=DeliveryHttpTransport.offlineLoopback(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),Duration.ofSeconds(2))){
            var reader=new ProtectedEnrollmentInspection(transport,records.enrollment,request,records.clock);
            if(fault.equals("valid")){var result=reader.inspect(request.projectId(),"main");assertEquals(30,result.project().effectiveAccessLevel());assertTrue(result.runners().complete());assertEquals(0,result.runners().count());assertEquals(request.expectedTargetSha(),result.target().headSha());}
            else assertThrows(RuntimeException.class,()->reader.inspect(request.projectId(),"main"));
        }finally{server.stop(0);}
    }
}
