package io.github.qhung13.asep.codedelivery;

import io.github.qhung13.asep.integration.GitLabTestProjectReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import static io.github.qhung13.asep.codedelivery.TestProjectRemoteReconciliation.*;

/** Narrow Git smart-HTTP creation + Draft MR API adapter. Authority, durable claims
 * and independent inspection are mandatory separate capabilities. No live instance
 * is configured by default application startup; offline factory is loopback only.
 * Neither write acknowledgement nor a caller-supplied Prepared is completion proof.
 */
public final class GitLabDeliveryAdapter implements TestProjectDeliveryCoordinator.ReadPort,TestProjectDeliveryCoordinator.WritePort {
    public enum Mutation { BRANCH_CREATE,DRAFT_CREATE }
    public interface MutationBoundary {void require(TestProjectDeliveryRequest request,Mutation mutation);}
    private static final String API="/api/v4/projects/87786140",GIT="/qhung-13/asep-m01-sandbox.git";
    private final DeliveryHttpTransport reads,writes;private final TestProjectDeliveryPreparation.Artifacts objects;
    private final GitLabTestProjectReader inspection;private final MutationBoundary boundary;private final Consumer<MergeRequest> effects;
    public GitLabDeliveryAdapter(DeliveryHttpTransport readTransport,DeliveryHttpTransport writeTransport,
            TestProjectDeliveryPreparation.Artifacts objects,GitLabTestProjectReader inspection,MutationBoundary boundary,Consumer<MergeRequest> effects){
        this.reads=Objects.requireNonNull(readTransport);this.writes=Objects.requireNonNull(writeTransport);this.objects=Objects.requireNonNull(objects);
        this.inspection=Objects.requireNonNull(inspection);this.boundary=Objects.requireNonNull(boundary);this.effects=Objects.requireNonNull(effects);
    }
    @Override public GitLabTestProjectReader.Inspection inspect(TestProjectDeliveryRequest r){scope(r);return inspection.inspect(r.projectId(),r.targetBranch());}
    @Override public Observation read(TestProjectDeliveryRequest r){
        scope(r);project();var target=target(r);var source=reads.exchange("GET",API+"/repository/branches/"+encoded(r.sourceBranch()),null,new byte[0]);
        boolean branchComplete=false;Branch branch=null;
        if(source.status()==404)branchComplete=true;
        else if(source.status()==200){var node=json(source);if(!r.sourceBranch().equals(text(node,"name")))throw invalid();branch=new Branch(r.projectId(),r.sourceBranch(),sha(node.get("commit"),"id"));branchComplete=true;}
        var response=reads.exchange("GET",API+"/merge_requests?scope=all&state=all&source_branch="+encoded(r.sourceBranch())+"&per_page=100&page=1",null,new byte[0]);
        if(response.status()!=200) return new Observation(true,branchComplete,branch,false,List.of(),target);
        var list=json(response);if(!list.isArray())return new Observation(true,branchComplete,branch,false,List.of(),target);
        var mrs=new ArrayList<MergeRequest>();for(JsonNode node:list)mrs.add(mr(node));
        boolean complete=GitLabTestProjectReader.CollectionObservation.singlePage(list.size(),response.headers()).complete();
        return new Observation(true,branchComplete,branch,complete,mrs,target);
    }
    @Override public void push(TestProjectDeliveryRequest r,TestProjectDeliveryPreparation.Prepared candidate){
        prepared(r,candidate);boundary.require(r,Mutation.BRANCH_CREATE);project();target(r);
        var advertisement=writes.exchange("GET",GIT+"/info/refs?service=git-receive-pack",null,new byte[0]);
        if(advertisement.status()!=200||!"application/x-git-receive-pack-advertisement".equals(advertisement.header("Content-Type")))throw invalid();
        var refs=GitReceivePack.advertisement(advertisement.body());
        if(!r.expectedTargetSha().equals(refs.refs().get("refs/heads/main"))||refs.refs().containsKey("refs/heads/"+r.sourceBranch()))throw conflict();
        byte[] pack=GitReceivePack.createRequest(r,objects);boundary.require(r,Mutation.BRANCH_CREATE);
        var result=writes.exchange("POST",GIT+"/git-receive-pack","application/x-git-receive-pack-request",pack);
        if(result.status()!=200||!"application/x-git-receive-pack-result".equals(result.header("Content-Type")))throw invalid();
        GitReceivePack.confirmed(result.body(),r.sourceBranch());
    }
    @Override public void createDraft(TestProjectDeliveryRequest r,TestProjectDeliveryPreparation.Prepared candidate){
        prepared(r,candidate);boundary.require(r,Mutation.DRAFT_CREATE);var current=read(r);
        if(!current.readable()||!current.branchComplete()||!current.mrListComplete()||!current.mergeRequests().isEmpty()
                ||current.branch()==null||!r.candidateCommitSha().equals(current.branch().head())||!r.expectedTargetSha().equals(current.targetHead()))throw conflict();
        byte[] body=JdbcDeliveryLedger.JSON.writeValueAsBytes(Map.of("source_branch",r.sourceBranch(),"target_branch","main","title",candidate.draft().title(),"description",candidate.draft().body(),"remove_source_branch",false,"squash",false));
        boundary.require(r,Mutation.DRAFT_CREATE);
        var response=writes.exchange("POST",API+"/merge_requests","application/json",body);
        if(response.status()!=201)throw invalid();MergeRequest observed=mr(json(response));
        // Persist actual server identity/head even if source drifted during creation.
        if(observed.projectId()!=r.projectId()||observed.sourceProjectId()!=r.projectId()||observed.targetProjectId()!=r.projectId()
                ||!r.sourceBranch().equals(observed.source())||!candidate.draft().title().equals(observed.title())||!candidate.draft().body().equals(observed.body()))throw invalid();
        effects.accept(observed);
        if(!r.candidateCommitSha().equals(observed.head())||!"main".equals(observed.target())||!observed.draft()||!"opened".equals(observed.state()))throw conflict();
    }
    /** Effect retention is local Java state only; it cannot confirm an incomplete read. */
    public static Consumer<MergeRequest> retainIn(JdbcTestProjectDeliveryLedger ledger,TestProjectDeliveryRequest r){
        return mr->{
            for(int n=0;n<3;n++) {
                var current=ledger.find(r.operationId());if(!current.request().equals(r)||!current.mrClaimed())throw conflict();
                var update=ledger.observe(current,new Observation(true,false,null,false,List.of(mr),null));
                if(update.applied()||update.record().equals(current)) {
                    if(!update.record().knownMrIids().contains(mr.iid()))throw conflict();return;
                }
            }
            throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);
        };
    }
    private void project(){var response=reads.exchange("GET",API,null,new byte[0]);if(response.status()!=200)throw invalid();var p=json(response);
        if(number(p,"id")!=87786140||!"qhung-13/asep-m01-sandbox".equals(text(p,"path_with_namespace"))||!"main".equals(text(p,"default_branch"))||bool(p,"archived"))throw conflict();}
    private String target(TestProjectDeliveryRequest r){var response=reads.exchange("GET",API+"/repository/branches/main",null,new byte[0]);if(response.status()!=200)throw invalid();var p=json(response);
        if(!"main".equals(text(p,"name"))||!bool(p,"protected")||bool(p,"can_push"))throw conflict();String head=sha(p.get("commit"),"id");
        // Read-only reconciliation must retain the moved target as an observation.
        return head;}
    private static MergeRequest mr(JsonNode n){String head=n.get("sha")==null||n.get("sha").isNull()?null:sha(n,"sha");
        return new MergeRequest(number(n,"project_id"),number(n,"source_project_id"),number(n,"target_project_id"),number(n,"iid"),text(n,"source_branch"),text(n,"target_branch"),head,bool(n,"draft"),text(n,"title"),text(n,"description"),text(n,"state"));}
    private static JsonNode json(DeliveryHttpTransport.Response r){String type=r.header("Content-Type");if(type==null||!type.matches("(?i)application/json(?:;\\s*charset=utf-8)?"))throw invalid();try{return JdbcDeliveryLedger.JSON.readTree(r.body());}catch(RuntimeException malformed){throw invalid();}}
    private static String text(JsonNode n,String key){if(n==null||!n.isObject()||n.get(key)==null||!n.get(key).isString())throw invalid();return n.get(key).asString();}
    private static long number(JsonNode n,String key){if(n==null||n.get(key)==null||!n.get(key).isIntegralNumber()||!n.get(key).canConvertToLong()||n.get(key).asLong()<1)throw invalid();return n.get(key).asLong();}
    private static boolean bool(JsonNode n,String key){if(n==null||n.get(key)==null||!n.get(key).isBoolean())throw invalid();return n.get(key).asBoolean();}
    private static String sha(JsonNode n,String key){String value=text(n,key);if(!value.matches("[0-9a-f]{40}"))throw invalid();return value;}
    private static String encoded(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    private static void scope(TestProjectDeliveryRequest r){if(r==null||r.projectId()!=87786140||!"main".equals(r.targetBranch())||!r.sourceBranch().equals("test/vn-t14/"+r.operationId()))throw conflict();}
    private static void prepared(TestProjectDeliveryRequest r,TestProjectDeliveryPreparation.Prepared p){scope(r);if(p==null||!r.equals(p.request())||!TestProjectDeliveryPreparation.render(r).equals(p.draft()))throw conflict();}
    private static DeliveryException conflict(){return new DeliveryException(DeliveryException.Kind.CONFLICT);}
    private static DeliveryException invalid(){return new DeliveryException(DeliveryException.Kind.UNAVAILABLE);}
}
