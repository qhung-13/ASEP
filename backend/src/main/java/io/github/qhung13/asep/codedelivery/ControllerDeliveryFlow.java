package io.github.qhung13.asep.codedelivery;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Deployable controller-side orchestration of actual evidence and Go contracts.
 * No Owner issuer or GitLab credential is held here. Heartbeat loss/cancellation
 * interrupts the bounded verifier, preventing publication/advance on a lost lease.
 */
public final class ControllerDeliveryFlow {
    private final GoDeliveryMessages go;private final ProtectedCandidateController controller;
    public ControllerDeliveryFlow(GoDeliveryMessages authenticatedGo,ProtectedCandidateController protectedController){go=authenticatedGo;controller=protectedController;}
    public ControllerEvidenceStore prepare(UUID operation,UUID ownerGrant,String policy,long epoch,String base,String safeCiReference){
        var admitted=go.start(new GoDeliveryMessages.ControlledStart(operation,policy,epoch,base));
        var job=new ProtectedCandidateController.Job(operation,admitted.runId(),admitted.producerAttemptId(),admitted.runVersion()+2,ownerGrant,policy,epoch,base,safeCiReference);
        if(!"IMPLEMENTING".equals(admitted.state())){
            var store=controller.existing(job);var r=store.request();
            go.submit(new GoDeliveryMessages.Submission(operation,admitted.taskId(),admitted.leaseEpoch(),r,store.seal(r.sealReceiptId()),store.verification(r.verificationReceiptId())));return store;
        }
        var lease=go.claimControlled(admitted.runId());
        if(!admitted.runId().equals(lease.runId())||!admitted.producerAttemptId().equals(lease.attemptId())||!admitted.taskId().equals(lease.taskId())||!"IMPLEMENTING".equals(lease.stage()))throw ControllerEvidenceStore.denied();
        try(var work=Executors.newVirtualThreadPerTaskExecutor();var heartbeats=Executors.newSingleThreadScheduledExecutor()){
            var result=work.submit(()->controller.produce(job));
            var heartbeat=heartbeats.scheduleAtFixedRate(()->{try{go.heartbeat(lease);}catch(RuntimeException lost){result.cancel(true);}},5,5,TimeUnit.SECONDS);
            try{
                var store=result.get(90,TimeUnit.SECONDS);var r=store.request();
                var snapshot=go.submit(new GoDeliveryMessages.Submission(operation,lease.taskId(),lease.leaseEpoch(),r,store.seal(r.sealReceiptId()),store.verification(r.verificationReceiptId())));
                if(snapshot==null||!snapshot.deliveryPolicyApproved()||!r.bodyHash().equals(snapshot.authorizedRequestHash())||!r.operationId().equals(snapshot.operationId()))throw ControllerEvidenceStore.denied();
                return store;
            }finally{heartbeat.cancel(true);result.cancel(true);}
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw ControllerEvidenceStore.denied();}
        catch(java.util.concurrent.ExecutionException failure){if(failure.getCause() instanceof IllegalStateException denied)throw denied;throw ControllerEvidenceStore.denied();}
        catch(Exception denied){throw ControllerEvidenceStore.denied();}
    }
}
