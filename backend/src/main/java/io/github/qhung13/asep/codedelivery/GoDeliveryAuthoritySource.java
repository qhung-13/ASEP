package io.github.qhung13.asep.codedelivery;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import javax.net.ssl.SSLContext;

/** Exact, independently queried authority over the protected internal mTLS channel.
 * No default wiring, bearer fallback, SQL writes or caller-provided success flags.
 */
public final class GoDeliveryAuthoritySource implements TestProjectCurrentPolicyVerifier.AuthoritySource,AutoCloseable {
    private final InternalDeliveryHttp channel;private final UUID operation;
    public GoDeliveryAuthoritySource(URI origin,UUID operation,SSLContext identity,String pin,Duration deadline){this.operation=java.util.Objects.requireNonNull(operation);channel=new InternalDeliveryHttp(origin,identity,pin,deadline);}
    @Override public TestProjectCurrentPolicyVerifier.Snapshot read(UUID run,UUID attempt){
        try{
            if(run==null||attempt==null)throw new IllegalStateException();
            byte[] body=channel.exchange("GET","/internal/v1/delivery-authority/"+operation+"/"+run+"/"+attempt,new byte[0]);
            var result=JdbcDeliveryLedger.JSON.readValue(body,TestProjectCurrentPolicyVerifier.Snapshot.class);
            if(!operation.equals(result.operationId())||!run.equals(result.runId())||!attempt.equals(result.attemptId()))throw new IllegalStateException();return result;
        }catch(RuntimeException denied){throw new IllegalStateException("Workflow authority unavailable or denied");}
    }
    @Override public void close(){channel.close();}
}
