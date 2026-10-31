package io.github.qhung13.asep.codedelivery;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryGate.*;

/** One bounded, explicitly constructed delivery invocation. Installed entrypoints
 * supply protected capabilities; owned fixtures use this same assembly with
 * synthetic enrollment, authority and credentials at literal loopback only.
 * No Spring component, public factory or configuration-driven startup exists.
 */
final class ProtectedDeliveryExecutor implements AutoCloseable {
    interface Credentials { String read(String expectedDigest); String write(String expectedDigest); }
    record Context(ControllerEvidenceStore store,TestProjectDeliveryPreparation.Profile profile,
            ProtectedDeliveryEnrollment enrollment,Credentials credentials,DataSource database,
            TrustedEvidenceVerifier authority,DeliveryMessagePump.Channel channel,URI origin,Clock clock) {}
    private final Context context;
    private final boolean mutation;
    private final DeliveryHttpTransport reads,writes;
    private final TestProjectDeliveryCoordinator coordinator;
    private final DeliveryMessagePump pump;

    static ProtectedDeliveryExecutor open(Context context,boolean mutation,boolean liveWritesEnabled){
        if(mutation&&!liveWritesEnabled)throw ProtectedDeliveryRuntime.denied();
        return new ProtectedDeliveryExecutor(context,mutation);
    }
    private ProtectedDeliveryExecutor(Context c,boolean mutation){
        this.context=c;this.mutation=mutation;
        var request=c.store().request();var enrollment=c.enrollment();
        var permit=mutation?enrollment.require(request):null;
        // Recovery never consults enrollment or the write credential.
        String read=c.credentials().read(permit==null?null:permit.readCredentialSha256());
        reads=new DeliveryHttpTransport(c.origin(),Duration.ofSeconds(5),path->{
            if(permit!=null)c.credentials().read(permit.readCredentialSha256());
            return Map.of("PRIVATE-TOKEN",read);
        });
        DeliveryHttpTransport writer=null;
        try{
            var preparation=new TestProjectDeliveryPreparation(c.store(),c.store(),c.profile(),c.clock());
            var ledger=new JdbcTestProjectDeliveryLedger(c.database());
            var inspection=new ProtectedEnrollmentInspection(reads,enrollment,request,c.clock());
            var gate=new TestProjectDeliveryGate((r,i)->{
                var base=c.authority().verify(r,i);
                if(!base.verified().equals(Set.of(Requirement.OWNER_WRITE_AUTHORIZATION,Requirement.CURRENT_POLICY))
                        ||i.project()==null||i.project().effectiveAccessLevel()!=30)return new Evidence(Set.of(),null);
                preparation.prepare(r);var current=enrollment.require(r);
                if(permit==null||!permit.readCredentialSha256().equals(current.readCredentialSha256())
                        ||!permit.writeCredentialSha256().equals(current.writeCredentialSha256()))throw ProtectedDeliveryRuntime.denied();
                c.credentials().read(current.readCredentialSha256());
                var verified=EnumSet.copyOf(base.verified());verified.addAll(ProtectedDeliveryEnrollment.established());
                verified.add(Requirement.SEALED_INDEPENDENTLY_VERIFIED_CANDIDATE);
                return new Evidence(verified,base.ownerGrant());
            },c.clock(),Duration.ofMinutes(5));
            if(mutation){gate.requireBeforeMutation(request,inspection.inspect(request.projectId(),request.targetBranch()));preparation.prepare(request);}
            String write=mutation?c.credentials().write(permit.writeCredentialSha256()):null;
            writer=new DeliveryHttpTransport(c.origin(),Duration.ofSeconds(5),path->{
                if(!mutation||write==null)throw ProtectedDeliveryRuntime.denied();
                var current=enrollment.require(request);
                if(!current.writeCredentialSha256().equals(permit.writeCredentialSha256()))throw ProtectedDeliveryRuntime.denied();
                c.credentials().write(current.writeCredentialSha256());
                return path.startsWith("/api/v4/")?Map.of("PRIVATE-TOKEN",write):Map.of("Authorization","Basic "+Base64.getEncoder().encodeToString(("oauth2:"+write).getBytes(StandardCharsets.US_ASCII)));
            });
            var adapter=new GitLabDeliveryAdapter(reads,writer,c.store(),inspection,(r,step)->{
                if(!mutation)throw ProtectedDeliveryRuntime.denied();
                var record=ledger.find(r.operationId());
                if(!record.request().equals(r)||(step==GitLabDeliveryAdapter.Mutation.BRANCH_CREATE?!record.pushClaimed():!record.mrClaimed()))throw ProtectedDeliveryRuntime.denied();
                gate.requireBeforeMutation(r,inspection.inspect(r.projectId(),r.targetBranch()));preparation.prepare(r);
            },GitLabDeliveryAdapter.retainIn(ledger,request));
            coordinator=new TestProjectDeliveryCoordinator(ledger,gate,preparation,adapter,adapter);
            pump=new DeliveryMessagePump(c.channel(),new DeliveryIntentInbox(c.database()),ledger);
            writes=writer;
        }catch(RuntimeException failed){reads.close();if(writer!=null)writer.close();throw failed;}
    }
    TestProjectDeliveryRecord run(){
        var request=context.store().request();
        if(!mutation)return coordinator.reconcileOnly(request);
        pump.receiveOne();
        if(!new DeliveryIntentInbox(context.database()).contains(request))throw ProtectedDeliveryRuntime.denied();
        return coordinator.runOnce(request);
    }
    int publishOutcomes(){return pump.publishOutcomes(context.store().request().operationId(),100);}
    @Override public void close(){try{writes.close();}finally{reads.close();}}
}
