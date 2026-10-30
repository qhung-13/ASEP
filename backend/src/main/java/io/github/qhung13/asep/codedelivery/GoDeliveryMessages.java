package io.github.qhung13.asep.codedelivery;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.SSLContext;

/** Authenticated, bounded application/control contracts. Client identity determines
 * permitted routes on Go; agent request fields cannot select a privileged role.
 */
public final class GoDeliveryMessages implements DeliveryMessagePump.Channel,AutoCloseable {
    public record ControlledStart(UUID operationId,String policyId,long policyEpoch,String baseSha){}
    public record ControlledReceipt(UUID runId,UUID producerAttemptId,UUID taskId,long runVersion,String state,long leaseEpoch){}
    public record Lease(UUID runId,UUID taskId,UUID attemptId,String stage,String claimantId,long leaseEpoch,int deliveryCount,java.time.Instant expiresAt){}
    public record Submission(UUID evidenceId,UUID producerTaskId,long leaseEpoch,TestProjectDeliveryRequest request,TestProjectDeliveryPreparation.Seal seal,TestProjectDeliveryPreparation.Verification verification){}
    private final InternalDeliveryHttp channel;
    public GoDeliveryMessages(URI origin,SSLContext identity,String certificatePin,Duration deadline){channel=new InternalDeliveryHttp(origin,identity,certificatePin,deadline);}
    private <T>T post(String path,Object body,Class<T> type){try{byte[] response=channel.exchange("POST",path,JdbcDeliveryLedger.JSON.writeValueAsBytes(body));return response==null?null:JdbcDeliveryLedger.JSON.readValue(response,type);}catch(RuntimeException denied){throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);}}
    public DeliveryIntentInbox.Envelope claim(UUID operation){return post("/internal/v1/delivery-intents/claim",Map.of("operationId",operation),DeliveryIntentInbox.Envelope.class);}
    @Override public DeliveryIntentInbox.Envelope claim(){return post("/internal/v1/delivery-intents/claim",Map.of(),DeliveryIntentInbox.Envelope.class);}
    @Override public void acknowledge(UUID message,String hash,UUID token){post("/internal/v1/delivery-intents/ack",Map.of("messageId",message,"messageHash",hash,"claimToken",token),Map.class);}
    @Override public void outcome(TestProjectDeliveryRecord.Callback callback){post("/internal/v1/delivery-outcomes",Map.of("callback",callback,"payloadHash",callback.bodyHash()),Map.class);}
    public ControlledReceipt start(ControlledStart body){return post("/internal/v1/controlled-admissions",body,ControlledReceipt.class);}
    public Lease claimControlled(UUID run){return post("/internal/v1/controlled-claim",Map.of("runId",run),Lease.class);}
    public void heartbeat(Lease lease){post("/internal/v1/controlled-heartbeat",lease,Map.class);}
    public TestProjectCurrentPolicyVerifier.Snapshot submit(Submission body){return post("/internal/v1/controlled-review",body,TestProjectCurrentPolicyVerifier.Snapshot.class);}
    public void cancel(UUID run,long version){post("/internal/v1/controlled-cancel",Map.of("runId",run,"expectedRunVersion",version),Map.class);}
    @Override public void close(){channel.close();}
}
