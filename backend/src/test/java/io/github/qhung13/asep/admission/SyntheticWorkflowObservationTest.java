package io.github.qhung13.asep.admission;

import io.github.qhung13.asep.integration.JiraTicketReader;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static io.github.qhung13.asep.admission.SyntheticManualTicketAdmission.*;
import static io.github.qhung13.asep.admission.SyntheticWorkflowObservation.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticWorkflowObservationTest {
    @Test void callerAuthorizationPrecedesReadAndOutcomesRemainIndependent() {
        var actor=new AtomicReference<>(new Actor("synthetic-owner",Role.OWNER));var calls=new AtomicInteger();
        var policy=new Policy("synthetic:vn-t10-it-"+"a".repeat(32),"synthetic-policy",1,true,"synthetic-owner","synthetic-site","10001",new Repository("87786140","a".repeat(40),"main","a".repeat(40)),4,new JiraTicketReader.Limits(8192,256,1024,8,128));
        var selection=new JiraTicketReader.Selection("synthetic-site","10001","15001");
        var json=("{\"id\":\"15001\",\"key\":\"SYN-1\",\"fields\":{\"project\":{\"id\":\"10001\"},\"issuetype\":{\"name\":\"Task\"},\"status\":{\"name\":\"To Do\"},\"summary\":\"Synthetic probe\",\"description\":null}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var reader=new io.github.qhung13.asep.integration.internal.jira.FixtureJiraTicketReader(java.util.Map.of(selection,new io.github.qhung13.asep.integration.internal.jira.FixtureJiraTicketReader.Response(200,"application/json",json)));
        var admission=new SyntheticManualTicketAdmission(reader,actor::get,()->policy,new WorkflowPort(){public Outcome submit(Command c){throw new AssertionError();}public Outcome reconcile(Command c){throw new AssertionError();}});
        var prepared=admission.prepare(UUID.randomUUID().toString(),selection);String run=UUID.randomUUID().toString();var c=prepared.command();
        var projection=new SyntheticWorkflowObservation(actor::get,()->policy,(id,subject)->{calls.incrementAndGet();return new View(new Workflow(id,subject,c.policyId(),1,c.sourceFingerprintSha256(),"COMPLETED_DRAFT_MR",7,4,0,3,384,120,0,List.of("synthetic:test:protected")),new Delivery("CONFIRMED","CONFIRMED","NO_PIPELINE","NOT_MERGED",true));});
        var view=projection.read(prepared,run);assertEquals("NO_PIPELINE",view.delivery().ci());assertEquals("NOT_MERGED",view.delivery().humanMerge());assertTrue(view.delivery().synthetic());assertEquals(1,calls.get());
        for(var role:List.of(Role.VIEWER,Role.WORKER)){actor.set(new Actor("synthetic-owner",role));assertThrows(Denied.class,()->projection.read(prepared,run));}
        actor.set(new Actor("synthetic-different-owner",Role.OWNER));assertThrows(Denied.class,()->projection.read(prepared,run));assertEquals(1,calls.get());
    }
}
