package io.github.qhung13.asep.codedelivery;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Explicit installed command. No default worker, HTTP agent endpoint or startup
 * property enables it. `check` authenticates installation without reading secrets
 * or making network calls. Actual invocation/install/permit is NOT authorized by
 * adding this code. `reconcile` never reads the write credential or sends a POST
 * to GitLab, including after authority/grant expiry or revocation.
 */
public final class DeliveryRuntimeCommand {
    private DeliveryRuntimeCommand(){}
    record CancelJob(UUID runId,long expectedRunVersion){}
    record ControllerJob(UUID operationId,UUID grantId,String policyId,long policyEpoch,String baseSha,String safeCiProfileSha256){}
    public static void main(String[] args){
        try{
            if(args.length==2&&args[0].equals("check")){
                var c=ProtectedDeliveryRuntime.authenticate(args[1]);
                System.out.println("Protected installation authenticated; live writes "+(c.liveWritesEnabled()?"require per-operation enrollment and every gate":"disabled"));return;
            }
            if(args.length!=2||!Set.of("prepare","cancel","once","reconcile").contains(args[0]))throw ProtectedDeliveryRuntime.denied();
            if(args[0].equals("cancel")){
                var c=ProtectedDeliveryRuntime.authenticate("candidate-controller");var job=JdbcDeliveryLedger.JSON.readValue(ProtectedDeliveryRuntime.file(Path.of(args[1]),0,4096,false),CancelJob.class);
                try(var go=new GoDeliveryMessages(URI.create(c.goOrigin()),ProtectedDeliveryRuntime.identity(c),c.goServerCertificateSha256(),Duration.ofSeconds(5))){go.cancel(job.runId(),job.expectedRunVersion());}return;
            }
            if(args[0].equals("prepare")){
                var c=ProtectedDeliveryRuntime.authenticate("candidate-controller");Path jobPath=Path.of(args[1]);
                // Root imports/reviews the job. Agent-writable request files never enter this command.
                var job=JdbcDeliveryLedger.JSON.readValue(ProtectedDeliveryRuntime.file(jobPath,0,4096,false),ControllerJob.class);
                var identity=ProtectedDeliveryRuntime.identity(c);
                try(var go=new GoDeliveryMessages(URI.create(c.goOrigin()),identity,c.goServerCertificateSha256(),Duration.ofSeconds(5))){
                    ProtectedOperatorFiles.hierarchy(c.baseObjectsRoot(),0);
                    TestProjectDeliveryPreparation.Artifacts base=(type,sha,limit)->{
                        if(type==null||sha==null||!sha.matches("[0-9a-f]{40}")||limit<1||limit>1048576)throw ProtectedDeliveryRuntime.denied();
                        Path object=c.baseObjectsRoot().resolve(type.name().toLowerCase(java.util.Locale.ROOT)).resolve(sha);
                        return ProtectedOperatorFiles.read(c.baseObjectsRoot(),object,0,limit,false);
                    };
                    ProtectedOperatorFiles.hierarchy(c.verifierJobsRoot(),c.controllerUid());
                    var verifier=new IsolatedProbeVerifier(ProtectedDeliveryRuntime.JAR,c.harnessSha256(),c.imageSha256(),Duration.ofSeconds(30),c.verifierJobsRoot(),c.controllerUid());
                    verifier.recoverOwnedJobs();
                    var controller=new ProtectedCandidateController(c.evidenceRoot(),c.controllerUid(),c.profile(),base,verifier,Clock.systemUTC());
                    var store=new ControllerDeliveryFlow(go,controller).prepare(job.operationId(),job.grantId(),job.policyId(),job.policyEpoch(),job.baseSha(),job.safeCiProfileSha256());
                    System.out.println(JdbcDeliveryLedger.JSON.writeValueAsString(store.request()));
                }return;
            }
            execute(args[0],UUID.fromString(args[1]));
        }catch(Exception denied){System.err.println("Protected delivery unavailable or denied; retain durable facts and use read-only reconciliation after uncertainty.");System.exit(1);}
    }
    private static void execute(String mode,UUID operation)throws Exception{
        var c=ProtectedDeliveryRuntime.authenticate("delivery-executor");boolean mutation=mode.equals("once");
        if(mutation&&!c.liveWritesEnabled())throw ProtectedDeliveryRuntime.denied(); // Before all secret/network access.
        var store=new ControllerEvidenceStore(c.evidenceRoot(),c.controllerUid(),operation);var request=store.request();
        var identity=ProtectedDeliveryRuntime.identity(c);var database=ProtectedDeliveryRuntime.database(c);
        try(var authority=new GoDeliveryAuthoritySource(URI.create(c.goOrigin()),operation,identity,c.goServerCertificateSha256(),Duration.ofSeconds(5));
            var go=new GoDeliveryMessages(URI.create(c.goOrigin()),identity,c.goServerCertificateSha256(),Duration.ofSeconds(5))){
            var owner=new TestProjectOwnerGrantVerifier(new JdbcOwnerGrantSource(database),Clock.systemUTC());
            var policy=new TestProjectCurrentPolicyVerifier(authority,Clock.systemUTC(),Duration.ofSeconds(10));
            var credentials=new ProtectedDeliveryExecutor.Credentials(){
                public String read(String digest){return ProtectedDeliveryRuntime.credential(c.readCredential(),c.serviceUid(),digest);}
                public String write(String digest){return ProtectedDeliveryRuntime.credential(c.writeCredential(),c.serviceUid(),digest);}
            };
            var channel=new DeliveryMessagePump.Channel(){public DeliveryIntentInbox.Envelope claim(){return go.claim(operation);}public void acknowledge(UUID id,String hash,UUID token){go.acknowledge(id,hash,token);}public void outcome(TestProjectDeliveryRecord.Callback callback){go.outcome(callback);}};
            var context=new ProtectedDeliveryExecutor.Context(store,c.profile(),new ProtectedDeliveryEnrollment(c.enrollmentRoot(),Clock.systemUTC()),
                    credentials,database,new TestProjectTrustedEvidenceComposite(owner,policy),channel,URI.create("https://gitlab.com"),Clock.systemUTC());
            try(var executor=ProtectedDeliveryExecutor.open(context,mutation,c.liveWritesEnabled())){
                var result=executor.run();executor.publishOutcomes();
                System.out.println(JdbcDeliveryLedger.JSON.writeValueAsString(Map.of("operationId",operation,"revision",result.revision(),"push",result.push(),"mergeRequest",result.mergeRequest(),"knownMrIids",result.knownMrIids(),"residualRace",result.residualRace(),"exactRemoteCandidateObserved",result.exactRemoteCandidateObserved(),"workflowAcceptance","inspect authenticated Go outcome")));
            }
        }
    }
}
