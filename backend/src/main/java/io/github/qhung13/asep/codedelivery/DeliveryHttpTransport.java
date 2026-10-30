package io.github.qhung13.asep.codedelivery;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/** Bounded transport owned by a trusted executor. No public live factory, token
 * discovery or startup flag. Deployment wiring must supply an independently gated
 * endpoint/credential capability. Tests can construct only literal loopback HTTP.
 */
public final class DeliveryHttpTransport implements AutoCloseable {
    record Response(int status,Map<String,List<String>> headers,byte[] body){
        String header(String name){var values=headers.entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(List.of());return values.size()==1?values.getFirst():null;}
    }
    interface Authorization {Map<String,String> headers(String path);}
    private final URI origin;private final Duration deadline;private final HttpClient client;private final Authorization authorization;
    public static DeliveryHttpTransport offlineLoopback(URI origin,Duration deadline){
        if(origin==null||!origin.toString().equals("http://127.0.0.1:"+origin.getPort())||origin.getPort()<1||origin.getPort()>65535)throw GitReceivePack.invalid();
        return new DeliveryHttpTransport(origin,deadline,path->Map.of());
    }
    // Package-private: only the protected runtime can wire credentials after all
    // gates. Nothing in the default application constructs a live transport.
    DeliveryHttpTransport(URI origin,Duration deadline,Authorization authorization){
        if(!origin.toString().matches("https://gitlab\\.com|http://127\\.0\\.0\\.1:[0-9]{1,5}")||deadline==null||deadline.isZero()||deadline.isNegative()||deadline.compareTo(Duration.ofSeconds(30))>0)throw GitReceivePack.invalid();
        this.origin=origin;this.deadline=deadline;this.authorization=authorization;
        client=HttpClient.newBuilder().connectTimeout(deadline).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
            .proxy(new ProxySelector(){@Override public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}@Override public void connectFailed(URI uri,SocketAddress address,IOException failure){}}).build();
    }
    Response exchange(String method,String path,String contentType,byte[] body){
        if(!("GET".equals(method)||"POST".equals(method))||!allowed(method,path)||body.length>2097152)throw GitReceivePack.invalid();
        var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(deadline).header("Accept-Encoding","identity");
        authorization.headers(path).forEach(builder::header);if(contentType!=null)builder.header("Content-Type",contentType);
        builder.method(method,body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body));
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try{
            pending=client.sendAsync(builder.build(),info->{if(!"identity".equalsIgnoreCase(info.headers().firstValue("Content-Encoding").orElse("identity"))||info.headers().firstValueAsLong("Content-Length").orElse(0)>262144)throw GitReceivePack.invalid();return new Body();});
            var response=pending.get(deadline.toNanos(),TimeUnit.NANOSECONDS);return new Response(response.statusCode(),response.headers().map(),response.body());
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw GitReceivePack.invalid();}
        catch(Exception unavailable){throw GitReceivePack.invalid();}
        finally{if(pending!=null&&!pending.isDone())pending.cancel(true);}
    }
    static boolean allowed(String method,String path){
        if(path==null||path.contains("#")||path.contains("..")||path.contains("\\")||path.chars().anyMatch(c->c<32||c>126))return false;
        String git="/qhung-13/asep-m01-sandbox.git";
        if(method.equals("POST"))return path.equals(git+"/git-receive-pack")||path.equals("/api/v4/projects/87786140/merge_requests");
        return path.equals(git+"/info/refs?service=git-receive-pack")||path.equals("/api/v4/projects/87786140")
            ||path.equals("/api/v4/projects/87786140/repository/branches/main")
            ||path.matches("/api/v4/projects/87786140/repository/branches/test%2Fvn-t14%2F[0-9a-f-]{36}")
            ||path.matches("/api/v4/projects/87786140/merge_requests\\?scope=all&state=all&source_branch=test%2Fvn-t14%2F[0-9a-f-]{36}&per_page=100&page=1");
    }
    @Override public void close(){client.shutdownNow();}
    private static final class Body implements HttpResponse.BodySubscriber<byte[]>{
        final CompletableFuture<byte[]> result=new CompletableFuture<>();final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return result;}
        @Override public void onSubscribe(Flow.Subscription value){subscription=value;value.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers){for(var buffer:buffers){if(buffer.remaining()>262144-bytes.size()){subscription.cancel();result.completeExceptionally(GitReceivePack.invalid());return;}byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);}subscription.request(1);}
        @Override public void onError(Throwable failure){result.completeExceptionally(GitReceivePack.invalid());}
        @Override public void onComplete(){result.complete(bytes.toByteArray());}
    }
}
