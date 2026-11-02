package io.github.qhung13.asep.codedelivery;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;
import org.postgresql.ds.PGSimpleDataSource;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;
import static io.github.qhung13.asep.integration.GitLabTestProjectReader.*;

/** Private trusted component peer, TEST output only. It is never an agent entry
 * point. Logical identities share a fixture OS user; operational isolation is NOT
 * claimed. DB/client keys stay in this parent; the verifier container receives none.
 */
public final class DeliveryProcessFixture {
    record Config(String directory,String goOrigin,String goPin,String serverCa,String controllerCertificate,String controllerKey,
            String executorCertificate,String executorKey,String jdbcUrl,String runtimePasswordFile,String issuerPasswordFile,
            String nonce,String harnessJar,String imageSha256){}
    private final Config config;private final Path directory;private final NativeGitLabFixture remote;
    private final PGSimpleDataSource runtime,issuerDatabase;private final Profile profile;private final ProtectedCandidateController controller;
    private final GoDeliveryMessages controllerGo,executorGo;private final SSLContext executorIdentity;
    private ControllerEvidenceStore store;private TestProjectDeliveryRequest request;private ProtectedDeliveryExecutor.Context execution;
    private JdbcTestProjectDeliveryLedger ledger;private DeliveryMessagePump pump;private GoDeliveryAuthoritySource authority;
    private boolean loseCallbackAck;
    private DeliveryProcessFixture(Config config)throws Exception{
        this.config=config;directory=Path.of(config.directory());Files.createDirectories(directory);
        remote=new NativeGitLabFixture(directory.resolve("remote"));Path evidence=directory.resolve("evidence");if(!Files.exists(evidence))Files.createDirectory(evidence);Files.setPosixFilePermissions(evidence,PosixFilePermissions.fromString("rwx------"));
        String password=Files.readString(Path.of(config.runtimePasswordFile())).strip();String issuerPassword=Files.readString(Path.of(config.issuerPasswordFile())).strip();
        if(!config.jdbcUrl().matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/asep_vnt11_[0-9a-f]{32}"))throw new IllegalStateException();
        runtime=DeliveryDatabaseFixture.source(config.jdbcUrl(),"asep_delivery_runtime",password);issuerDatabase=DeliveryDatabaseFixture.source(config.jdbcUrl(),"asep_delivery_issuer",issuerPassword);
        try(var db=runtime.getConnection();var query=db.prepareStatement("SELECT nonce=? AND database_name=current_database() AND server_started_at=pg_postmaster_start_time() AND current_user='asep_delivery_runtime' FROM asep_test_harness.instance WHERE singleton")){
            query.setString(1,config.nonce());try(var row=query.executeQuery()){if(!row.next()||!row.getBoolean(1))throw new IllegalStateException();}
            try(var queryGo=db.createStatement()){try{queryGo.executeQuery("SELECT state FROM orchestration.workflow_runs LIMIT 0");throw new IllegalStateException("cross-domain SQL permitted");}catch(java.sql.SQLException denied){if(!"42501".equals(denied.getSQLState()))throw new IllegalStateException();}}
        }
        byte[] root=Files.readAllBytes(Path.of(config.serverCa()));
        var controllerIdentity=identity(config.controllerCertificate(),config.controllerKey(),root,"spiffe://asep.local/candidate-controller");
        executorIdentity=identity(config.executorCertificate(),config.executorKey(),root,"spiffe://asep.local/code-delivery");
        controllerGo=new GoDeliveryMessages(URI.create(config.goOrigin()),controllerIdentity,config.goPin(),Duration.ofSeconds(5));
        executorGo=new GoDeliveryMessages(URI.create(config.goOrigin()),executorIdentity,config.goPin(),Duration.ofSeconds(5));
        String harness=ControllerEvidenceStore.digest(Files.readAllBytes(Path.of(config.harnessJar())));
        profile=new Profile("a".repeat(64),harness,config.imageSha256(),2,Duration.ofMinutes(5));long uid=((Number)Files.getAttribute(evidence,"unix:uid")).longValue();
        controller=new ProtectedCandidateController(evidence,uid,profile,remote.baseSource(),new IsolatedProbeVerifier(Path.of(config.harnessJar()),harness,config.imageSha256(),Duration.ofSeconds(20),verifierJobs(),uid),Clock.systemUTC());
    }
    private Path verifierJobs()throws Exception{
        Path jobs=directory.resolve("verifier-jobs");Files.createDirectories(jobs);
        Files.setPosixFilePermissions(jobs,PosixFilePermissions.fromString("rwx------"));return jobs;
    }
    private static SSLContext identity(String certificate,String key,byte[] ca,String uri)throws Exception{
        byte[] privateKey=Files.readAllBytes(Path.of(key));try{return DeliveryTlsIdentity.context(Files.readAllBytes(Path.of(certificate)),privateKey,ca,uri);}finally{java.util.Arrays.fill(privateKey,(byte)0);}
    }
    private void bind()throws Exception{
        request=store.request();ledger=new JdbcTestProjectDeliveryLedger(runtime);
        authority=new GoDeliveryAuthoritySource(URI.create(config.goOrigin()),request.operationId(),executorIdentity,config.goPin(),Duration.ofSeconds(5));
        var owner=new TestProjectOwnerGrantVerifier(new JdbcOwnerGrantSource(runtime),Clock.systemUTC());var policy=new TestProjectCurrentPolicyVerifier(authority,Clock.systemUTC(),Duration.ofSeconds(10));
        var inbox=new DeliveryIntentInbox(runtime);
        var channel=new DeliveryMessagePump.Channel(){
            public DeliveryIntentInbox.Envelope claim(){return executorGo.claim(request.operationId());}
            public void acknowledge(UUID id,String hash,UUID token){executorGo.acknowledge(id,hash,token);}
            public void outcome(TestProjectDeliveryRecord.Callback callback){executorGo.outcome(callback);if(loseCallbackAck){loseCallbackAck=false;throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);}}
        };
        pump=new DeliveryMessagePump(channel,inbox,ledger);
        var enrollment=new ProtectedEnrollmentFixture(directory.resolve("synthetic-enrollment"),request);
        execution=new ProtectedDeliveryExecutor.Context(store,profile,enrollment.enrollment,enrollment,runtime,
                new TestProjectTrustedEvidenceComposite(owner,policy),channel,remote.origin(),Clock.systemUTC());
    }
    private Object action(tools.jackson.databind.JsonNode command)throws Exception{
        String action=command.get("action").asString();
        switch(action){
            case "prepare"->{
                UUID operation=UUID.fromString(command.get("operationId").asString()),grant=UUID.fromString(command.get("grantId").asString());
                store=new ControllerDeliveryFlow(controllerGo,controller).prepare(operation,grant,command.get("policyId").asString(),1,remote.baseSha,"d".repeat(64));bind();return Map.of("kind","prepared","request",request);
            }
            case "resume"->{store=new ControllerEvidenceStore(directory.resolve("evidence"),((Number)Files.getAttribute(directory.resolve("evidence"),"unix:uid")).longValue(),UUID.fromString(command.get("operationId").asString()));bind();return Map.of("kind","resumed","request",request);}
            case "approve"->{new OwnerGrantAuthority(issuerDatabase,"asep_delivery_issuer").approve(request,command.get("seconds").asInt());return Map.of("kind","approved");}
            case "await-expiry"->{
                long deadline=System.nanoTime()+Duration.ofSeconds(6).toNanos();
                while(new JdbcOwnerGrantSource(runtime).find(request.ownerGrantId()).expiresAt().isAfter(Instant.now())){if(System.nanoTime()>deadline)throw new IllegalStateException();Thread.sleep(25);}
                return Map.of("kind","expired");
            }
            case "revoke"->{new OwnerGrantAuthority(issuerDatabase,"asep_delivery_issuer").revoke(request);return Map.of("kind","revoked");}
            case "receive"->{return Map.of("kind","received","received",pump.receiveOne());}
            case "fault"->{String fault=command.get("mode").asString();
                switch(fault){case "cancel-before"->controllerGo.cancel(request.runId(),request.runVersion());
                    case "wrong-project"->remote.wrongProject=true;
                    case "existing-branch"->remote.move(request.sourceBranch(),remote.baseSha);
                    case "target-moved"->{try{String moved=remote.put(ObjectType.COMMIT,new String(remote.base,StandardCharsets.US_ASCII).replace("Base\n","Changed target\n").getBytes(StandardCharsets.US_ASCII));remote.move("main",moved);}catch(Exception failure){throw new IllegalStateException();}}
                    case "lost-push"->remote.losePush=true;case "lost-mr"->remote.loseMr=true;case "source-drift"->remote.beforeMr=()->remote.move(request.sourceBranch(),remote.baseSha);
                    case "cancel-mr"->remote.beforeMr=()->controllerGo.cancel(request.runId(),request.runVersion());case "callback-ack"->loseCallbackAck=true;case "rate-limit"->remote.mrStatus=429;case "none"->{remote.loseMr=false;remote.losePush=false;remote.beforeMr=()->{};remote.mrStatus=201;}default->throw new IllegalArgumentException();}
                return Map.of("kind","fault-set");}
            case "execute","reconcile"->{
                boolean denied=false;TestProjectDeliveryRecord result=null;
                try(var executor=ProtectedDeliveryExecutor.open(execution,action.equals("execute"),true)){result=executor.run();}catch(RuntimeException unavailable){denied=true;}
                return Map.of("kind","observed","denied",denied,"complete",result!=null&&result.exactRemoteCandidateObserved(),"race",result!=null&&result.residualRace(),"pushes",remote.pushes.get(),"mrs",remote.mrs.size(),"mrCreates",remote.creates.get(),"sourceHead",remote.head(request.sourceBranch())==null?"":remote.head(request.sourceBranch()));
            }
            case "publish"->{boolean unknown=false;try{pump.publishOutcomes(request.operationId(),100);}catch(DeliveryException lost){unknown=true;}return Map.of("kind","published","unknown",unknown);}
            default->throw new IllegalArgumentException();
        }
    }
    public static void main(String[] args){
        DeliveryProcessFixture fixture=null;int exit=0;
        try(var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))){
            String line=input.readLine();if(line==null||line.length()>16384)throw new IllegalStateException();var config=JdbcDeliveryLedger.JSON.readValue(line,Config.class);fixture=new DeliveryProcessFixture(config);
            System.out.println(JdbcDeliveryLedger.JSON.writeValueAsString(Map.of("kind","ready","baseSha",fixture.remote.baseSha,"harnessSha256",fixture.profile.harnessSha256(),"imageSha256",fixture.profile.imageSha256())));
            while((line=input.readLine())!=null){if(line.length()>16384)throw new IllegalStateException();var command=JdbcDeliveryLedger.JSON.readTree(line);System.out.println(JdbcDeliveryLedger.JSON.writeValueAsString(fixture.action(command)));}
        }catch(Exception denied){System.err.println("Private delivery fixture failed: "+denied.getClass().getSimpleName()+((denied.getMessage()!=null&&denied.getMessage().startsWith("Controller issuance denied at "))?" "+denied.getMessage():""));for(var frame:denied.getStackTrace())if(frame.getClassName().startsWith("io.github.qhung13.asep"))System.err.println(frame.getClassName()+":"+frame.getLineNumber());exit=1;}
        finally{if(fixture!=null){try{if(fixture.authority!=null)fixture.authority.close();fixture.controllerGo.close();fixture.executorGo.close();fixture.remote.close();}catch(Exception cleanup){System.err.println("Private fixture cleanup failed");exit=1;}}}
        if(exit!=0)System.exit(exit);
    }
}
