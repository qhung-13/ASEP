package io.github.qhung13.asep.codedelivery;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded durable message transfer. SQL commit precedes ACK. Lost response uses
 * the same message/callback identity; this class never retries a GitLab mutation.
 */
public final class DeliveryMessagePump {
    public interface Channel {
        DeliveryIntentInbox.Envelope claim();
        void acknowledge(UUID messageId,String messageHash,UUID claimToken);
        void outcome(TestProjectDeliveryRecord.Callback callback);
    }
    private final Channel channel;private final DeliveryIntentInbox inbox;private final JdbcTestProjectDeliveryLedger ledger;
    public DeliveryMessagePump(Channel authenticatedChannel,DeliveryIntentInbox inbox,JdbcTestProjectDeliveryLedger ledger){this.channel=Objects.requireNonNull(authenticatedChannel);this.inbox=Objects.requireNonNull(inbox);this.ledger=Objects.requireNonNull(ledger);}
    /** False means no pending message. A failed ACK does not erase the committed inbox. */
    public boolean receiveOne(){var envelope=channel.claim();if(envelope==null)return false;inbox.accept(envelope);channel.acknowledge(envelope.messageId(),envelope.messageHash(),envelope.claimToken());return true;}
    public int publishOutcomes(UUID operationId,int budget){
        if(budget<1||budget>100)throw new IllegalArgumentException();int sent=0;
        for(var callback:ledger.pendingCallbacks(operationId)){
            if(sent==budget)break;channel.outcome(callback);ledger.acknowledge(callback);sent++;
        }
        return sent;
    }
}
