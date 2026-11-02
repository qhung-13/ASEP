package io.github.qhung13.asep.codedelivery;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;
import static io.github.qhung13.asep.codedelivery.TestProjectDeliveryPreparation.*;

/** Deliberately small SHA-1 smart-HTTP receive-pack subset for the sealed probe.
 * One zero-old-OID command creates one ref atomically. No updates, deletes,
 * force-push, negotiation redirects, submodules, hooks or Git configuration.
 * Pack v2 carries exactly the three new non-delta objects; base already exists
 * on the remotely revalidated main. Unsupported capabilities fail closed.
 */
final class GitReceivePack {
    private GitReceivePack(){}
    record Advertisement(Map<String,String> refs,boolean reportStatus){}
    static Advertisement advertisement(byte[] bytes){
        var packets=packets(bytes);if(packets.size()<3||!packets.getFirst().equals("# service=git-receive-pack\n")||!packets.get(1).isEmpty())throw invalid();
        Map<String,String> refs=new HashMap<>();boolean report=false;boolean first=true;
        for(String packet:packets.subList(2,packets.size())){
            if(packet.isEmpty())continue;
            String line=packet.stripTrailing();int nul=line.indexOf('\0');
            if(first){if(nul<0)throw invalid();String capabilities=line.substring(nul+1);var fields=List.of(capabilities.split(" "));report=fields.contains("report-status");
                for(String cap:fields)if(cap.startsWith("object-format=")&&!cap.equals("object-format=sha1"))throw invalid();line=line.substring(0,nul);first=false;
            }else if(nul>=0)throw invalid();
            int space=line.indexOf(' ');if(space!=40)throw invalid();String sha=line.substring(0,40),ref=line.substring(41);
            if(!sha.matches("[0-9a-f]{40}")||!ref.matches("[A-Za-z0-9._/{}^-]{1,1024}")||refs.putIfAbsent(ref,sha)!=null)throw invalid();
        }
        if(!report)throw invalid();return new Advertisement(Map.copyOf(refs),true);
    }
    static byte[] createRequest(TestProjectDeliveryRequest r,Artifacts store){
        try{
            var output=new ByteArrayOutputStream();
            output.writeBytes(packet("0".repeat(40)+" "+r.candidateCommitSha()+" refs/heads/"+r.sourceBranch()+"\0report-status\n"));output.writeBytes("0000".getBytes(StandardCharsets.US_ASCII));
            var pack=new ByteArrayOutputStream();pack.writeBytes("PACK".getBytes(StandardCharsets.US_ASCII));pack.writeBytes(ByteBuffer.allocate(8).putInt(2).putInt(3).array());
            String blob=gitId(ObjectType.BLOB,probeContent(r.operationId()));
            add(pack,ObjectType.COMMIT,r.candidateCommitSha(),store,8192);add(pack,ObjectType.TREE,r.candidateTreeSha(),store,1048576);add(pack,ObjectType.BLOB,blob,store,256);
            byte[] data=pack.toByteArray();output.writeBytes(data);output.writeBytes(MessageDigest.getInstance("SHA-1").digest(data));return output.toByteArray();
        }catch(Exception unavailable){throw invalid();}
    }
    private static void add(ByteArrayOutputStream pack,ObjectType type,String sha,Artifacts store,int bound)throws Exception{
        byte[] raw=store.read(type,sha,bound).clone();if(raw.length>bound||!gitId(type,raw).equals(sha))throw invalid();
        int kind=switch(type){case COMMIT->1;case TREE->2;case BLOB->3;};int length=raw.length;
        int first=(kind<<4)|(length&15);length>>>=4;pack.write(first|(length==0?0:128));
        while(length!=0){int low=length&127;length>>>=7;pack.write(low|(length==0?0:128));}
        var compressed=new ByteArrayOutputStream();try(var zlib=new DeflaterOutputStream(compressed)){zlib.write(raw);}pack.writeBytes(compressed.toByteArray());
    }
    static void confirmed(byte[] response,String source){
        var lines=packets(response);if(lines.size()!=3||!lines.get(0).equals("unpack ok\n")||!lines.get(1).equals("ok refs/heads/"+source+"\n")||!lines.get(2).isEmpty())throw invalid();
    }
    static byte[] packet(String line){byte[] bytes=line.getBytes(StandardCharsets.US_ASCII);if(bytes.length>65516)throw invalid();var out=new ByteArrayOutputStream();out.writeBytes(String.format(java.util.Locale.ROOT,"%04x",bytes.length+4).getBytes(StandardCharsets.US_ASCII));out.writeBytes(bytes);return out.toByteArray();}
    static List<String> packets(byte[] bytes){
        if(bytes==null||bytes.length>262144)throw invalid();var packets=new ArrayList<String>();int offset=0;
        while(offset<bytes.length){if(offset+4>bytes.length)throw invalid();int length;try{length=Integer.parseInt(new String(bytes,offset,4,StandardCharsets.US_ASCII),16);}catch(Exception e){throw invalid();}
            if(length==0){packets.add("");offset+=4;continue;}if(length<4||length>65520||offset+length>bytes.length)throw invalid();
            for(int i=offset+4;i<offset+length;i++)if((bytes[i]&255)>127)throw invalid();
            packets.add(new String(bytes,offset+4,length-4,StandardCharsets.US_ASCII));offset+=length;
        }
        return List.copyOf(packets);
    }
    static DeliveryException invalid(){return new DeliveryException(DeliveryException.Kind.UNAVAILABLE);}
}
