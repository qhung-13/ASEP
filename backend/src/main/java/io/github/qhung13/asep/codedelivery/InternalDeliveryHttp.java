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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Explicit read-only mTLS authority capability. Protected host wiring supplies the
 * dedicated service trust roots/client identity, origin and reviewed server leaf pin.
 * No Spring activation, synthetic bearer, database access or GitLab write capability.
 */
final class InternalDeliveryHttp implements AutoCloseable {
    private final URI origin;
    private final HttpClient client;
    private final String certificateSha256;
    private final Duration deadline;
    InternalDeliveryHttp(URI origin, SSLContext mutualTls, String certificateSha256, Duration deadline) {
        if (origin==null || !origin.toString().equals("https://127.0.0.1:"+origin.getPort()) || origin.getPort()<1 || origin.getPort()>65535
                || certificateSha256==null || !certificateSha256.matches("[0-9a-f]{64}") || deadline==null || deadline.isNegative() || deadline.isZero()
                || deadline.compareTo(Duration.ofSeconds(5))>0) throw denied();
        this.origin=origin;this.certificateSha256=certificateSha256;this.deadline=deadline;
        var parameters=new SSLParameters();parameters.setProtocols(new String[]{"TLSv1.3"});parameters.setEndpointIdentificationAlgorithm("HTTPS");
        this.client=HttpClient.newBuilder().sslContext(Objects.requireNonNull(mutualTls)).sslParameters(parameters)
                .connectTimeout(deadline).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI target) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI target,SocketAddress address,IOException failure) { }
                }).build();
    }
    byte[] exchange(String method,String path,byte[] body) {
        if(!allowed(method,path)||body.length>8192)throw denied();
        var request=HttpRequest.newBuilder(origin.resolve(path)).timeout(deadline).header("Accept","application/json").header("Content-Type","application/json")
                .method(method,body.length==0?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofByteArray(body)).build();
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=client.sendAsync(request,info->{
                if(info.statusCode()==204)return new BoundedBody();
                if(info.statusCode()!=200 || !info.headers().firstValue("Content-Type").orElse("").matches("(?i)application/json(?:;\\s*charset=utf-8)?")
                        || !info.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")
                        || info.headers().firstValueAsLong("Content-Length").orElse(0)>8192) throw denied();
                return new BoundedBody();
            });
            var response=pending.get(deadline.toNanos(),TimeUnit.NANOSECONDS);
            var session=response.sslSession().orElseThrow(InternalDeliveryHttp::denied);
            if (!"TLSv1.3".equals(session.getProtocol()) || !certificateSha256.equals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(session.getPeerCertificates()[0].getEncoded())))) throw denied();
            if(response.statusCode()==204) {if(response.body().length!=0)throw denied();return null;}
            return response.body();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw denied(); }
        catch (Exception unavailable) { throw denied(); }
        finally { if(pending!=null && !pending.isDone()) pending.cancel(true); }
    }
    private static boolean allowed(String method,String path) {
        if(path==null)return false;
        if(method.equals("GET"))return path.matches("/internal/v1/delivery-authority/[0-9a-f-]{36}/[0-9a-f-]{36}/[0-9a-f-]{36}");
        return method.equals("POST")&&java.util.Set.of("/internal/v1/controlled-admissions","/internal/v1/controlled-claim","/internal/v1/controlled-review","/internal/v1/controlled-heartbeat","/internal/v1/delivery-intents/claim","/internal/v1/delivery-intents/ack","/internal/v1/delivery-outcomes","/internal/v1/controlled-cancel").contains(path);
    }
    @Override public void close() { client.shutdownNow(); }
    private static IllegalStateException denied() { return new IllegalStateException("Workflow authority unavailable or denied"); }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription=value;value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            try {
                for(var buffer:buffers) {
                    if(buffer.remaining()>8192-bytes.size()) { subscription.cancel();body.completeExceptionally(denied());return; }
                    byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
                }
                subscription.request(1);
            } catch(RuntimeException failure) { subscription.cancel();body.completeExceptionally(denied()); }
        }
        @Override public void onError(Throwable failure) { body.completeExceptionally(denied()); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
