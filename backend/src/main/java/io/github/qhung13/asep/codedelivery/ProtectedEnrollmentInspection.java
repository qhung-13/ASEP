package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Combines independently collected Owner-only inventories with current Developer
 * read identity project/branch checks. A privileged inventory reader's permission
 * to push main must never be mistaken for the Developer writer's capability.
 * Reports are protected, exact-request bound and fresh from their earliest read.
 */
final class ProtectedEnrollmentInspection implements GitLabTestProjectReader {
    private final DeliveryHttpTransport reads;private final ProtectedDeliveryEnrollment enrollment;
    private final TestProjectDeliveryRequest request;private final Clock clock;
    ProtectedEnrollmentInspection(DeliveryHttpTransport reads,ProtectedDeliveryEnrollment enrollment,TestProjectDeliveryRequest request,Clock clock){this.reads=reads;this.enrollment=enrollment;this.request=request;this.clock=clock;}
    @Override public Inspection inspect(long project,String target){
        if(project!=87786140||!"main".equals(target))throw ProtectedDeliveryRuntime.denied();
        var started=clock.instant();var p=read("/api/v4/projects/87786140");var t=read("/api/v4/projects/87786140/repository/branches/main");
        if(!p.isObject()||!t.isObject())throw ProtectedDeliveryRuntime.denied();
        int role=0;var permissions=p.get("permissions");if(permissions==null||!permissions.isObject())throw ProtectedDeliveryRuntime.denied();
        for(String key:new String[]{"project_access","group_access"}){
            var value=permissions.get(key);if(value!=null&&!value.isNull()){long access=number(value,"access_level");if(access>50)throw ProtectedDeliveryRuntime.denied();role=Math.max(role,(int)access);}
        }
        // Dedicated read credential must be separately attested as Developer like
        // the writer; broad Owner inspection occurs outside this read capability.
        if(role!=30)throw ProtectedDeliveryRuntime.denied();
        var permit=enrollment.require(request);if(permit.inspectionStartedAt().isBefore(started))started=permit.inspectionStartedAt();
        var metadata=new Project(number(p,"id"),text(p,"path_with_namespace"),text(p,"visibility"),bool(p,"archived"),text(p,"default_branch"),text(p,"builds_access_level"),bool(p,"shared_runners_enabled"),role);
        String head=text(t.get("commit"),"id");if(!head.matches("[0-9a-f]{40}"))throw ProtectedDeliveryRuntime.denied();
        var branch=new Target(text(t,"name"),head,bool(t,"protected"),bool(t,"can_push"));
        return new Inspection(project,target,metadata,branch,count(permit,"protected-branches"),count(permit,"project-group-instance-runners"),count(permit,"variable-metadata-all-levels"),count(permit,"environments-deployment-capabilities"),count(permit,"source-push-ci-behavior"),List.of(),Set.of(),started,clock.instant());
    }
    private CollectionObservation count(ProtectedDeliveryEnrollment.Permit p,String subject){return new CollectionObservation(enrollment.inventoryCount(p,subject),true);}
    private JsonNode read(String path){var response=reads.exchange("GET",path,null,new byte[0]);if(response.status()!=200||response.header("Content-Type")==null||!response.header("Content-Type").matches("(?i)application/json(?:;\\s*charset=utf-8)?"))throw ProtectedDeliveryRuntime.denied();return JdbcDeliveryLedger.JSON.readTree(response.body());}
    private static String text(JsonNode n,String key){if(n==null||n.get(key)==null||!n.get(key).isString())throw ProtectedDeliveryRuntime.denied();return n.get(key).asString();}
    private static long number(JsonNode n,String key){if(n==null||n.get(key)==null||!n.get(key).isIntegralNumber()||!n.get(key).canConvertToLong()||n.get(key).asLong()<0)throw ProtectedDeliveryRuntime.denied();return n.get(key).asLong();}
    private static boolean bool(JsonNode n,String key){if(n==null||n.get(key)==null||!n.get(key).isBoolean())throw ProtectedDeliveryRuntime.denied();return n.get(key).asBoolean();}
}
