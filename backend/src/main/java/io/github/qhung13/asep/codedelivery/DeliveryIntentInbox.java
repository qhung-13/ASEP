package io.github.qhung13.asep.codedelivery;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Durable receipt of authenticated Go intent. It never issues a grant, creates a
 * ledger send claim or authorizes mutation. Call only after service TLS validation.
 */
public final class DeliveryIntentInbox {
    public record Envelope(UUID messageId,UUID operationId,String requestHash,String messageHash,UUID claimToken,TestProjectDeliveryRequest request){
        public Envelope{if(messageId==null||operationId==null||claimToken==null||request==null||!operationId.equals(request.operationId())||!request.bodyHash().equals(requestHash)||!hash(messageId,operationId,requestHash).equals(messageHash))throw new DeliveryException(DeliveryException.Kind.CONFLICT);}
        static String hash(UUID message,UUID operation,String requestHash){return DeliveryIntent.hash(List.of("asep-delivery-intent-v1",message.toString(),operation.toString(),requestHash));}
        @Override public String toString(){return "AuthenticatedDeliveryIntent[identity redacted]";}
    }
    private final DataSource database;
    public DeliveryIntentInbox(DataSource database){this.database=java.util.Objects.requireNonNull(database);}
    public void accept(Envelope envelope){
        try(var db=database.getConnection()){
            db.setAutoCommit(false);
            try{
                try(var statement=db.prepareStatement("INSERT INTO code_delivery.delivery_intent_inbox(message_id,operation_id,request_hash,message_hash,request_json) VALUES(?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING")){
                    statement.setObject(1,envelope.messageId());statement.setObject(2,envelope.operationId());statement.setString(3,envelope.requestHash());statement.setString(4,envelope.messageHash());statement.setString(5,JdbcDeliveryLedger.JSON.writeValueAsString(envelope.request()));statement.executeUpdate();
                }
                try(var statement=db.prepareStatement("SELECT operation_id,request_hash,message_hash,request_json FROM code_delivery.delivery_intent_inbox WHERE message_id=?")){
                    statement.setObject(1,envelope.messageId());try(var row=statement.executeQuery()){
                        if(!row.next()||!envelope.operationId().equals(row.getObject(1,UUID.class))||!envelope.requestHash().equals(row.getString(2))||!envelope.messageHash().equals(row.getString(3))||!envelope.request().equals(JdbcDeliveryLedger.JSON.readValue(row.getString(4),TestProjectDeliveryRequest.class)))throw new DeliveryException(DeliveryException.Kind.CONFLICT);
                    }
                }
                db.commit();
            }catch(Exception failure){try{db.rollback();}catch(Exception ignored){}throw new DeliveryException(DeliveryException.Kind.CONFLICT);}
        }catch(java.sql.SQLException unavailable){throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);}
    }
    public List<TestProjectDeliveryRequest> requests(int limit){
        if(limit<1||limit>100)throw new IllegalArgumentException();
        try(var db=database.getConnection();var statement=db.prepareStatement("SELECT request_json,request_hash FROM code_delivery.delivery_intent_inbox ORDER BY received_at,message_id LIMIT ?")){
            statement.setInt(1,limit);try(var rows=statement.executeQuery()){var result=new java.util.ArrayList<TestProjectDeliveryRequest>();while(rows.next()){
                var r=JdbcDeliveryLedger.JSON.readValue(rows.getString(1),TestProjectDeliveryRequest.class);if(!r.bodyHash().equals(rows.getString(2)))throw new DeliveryException(DeliveryException.Kind.CONFLICT);result.add(r);
            }return List.copyOf(result);}
        }catch(java.sql.SQLException unavailable){throw new DeliveryException(DeliveryException.Kind.UNAVAILABLE);}
    }
}
