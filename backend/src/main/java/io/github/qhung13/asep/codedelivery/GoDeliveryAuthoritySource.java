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
public final class GoDeliveryAuthoritySource implements TestProjectCurrentPolicyVerifier.AuthoritySource, AutoCloseable {
    private final URI origin;
    private final UUID operation;
    private final HttpClient client;
    private final String certificateSha256;
    private final Duration deadline;
    public GoDeliveryAuthoritySource(URI origin, UUID operation, SSLContext mutualTls, String certificateSha256, Duration deadline) {
        if (origin==null || !origin.toString().equals("https://127.0.0.1:"+origin.getPort()) || origin.getPort()<1 || origin.getPort()>65535
                || certificateSha256==null || !certificateSha256.matches("[0-9a-f]{64}") || deadline==null || deadline.isNegative() || deadline.isZero()
                || deadline.compareTo(Duration.ofSeconds(5))>0) throw denied();
        this.origin=origin;this.operation=Objects.requireNonNull(operation);this.certificateSha256=certificateSha256;this.deadline=deadline;
        var parameters=new SSLParameters();parameters.setProtocols(new String[]{"TLSv1.3"});parameters.setEndpointIdentificationAlgorithm("HTTPS");
        this.client=HttpClient.newBuilder().sslContext(Objects.requireNonNull(mutualTls)).sslParameters(parameters)
                .connectTimeout(deadline).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI target) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI target,SocketAddress address,IOException failure) { }
                }).build();
    }
    @Override public TestProjectCurrentPolicyVerifier.Snapshot read(UUID run,UUID attempt) {
        if(run==null || attempt==null) throw denied();
        var request=HttpRequest.newBuilder(origin.resolve("/internal/v1/delivery-authority/"+operation+"/"+run+"/"+attempt))
                .timeout(deadline).header("Accept","application/json").GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            pending=client.sendAsync(request,info->{
                if(info.statusCode()!=200 || !info.headers().firstValue("Content-Type").orElse("").matches("(?i)application/json(?:;\\s*charset=utf-8)?")
                        || !info.headers().firstValue("Content-Encoding").orElse("identity").equalsIgnoreCase("identity")
                        || info.headers().firstValueAsLong("Content-Length").orElse(0)>8192) throw denied();
                return new BoundedBody();
            });
            var response=pending.get(deadline.toNanos(),TimeUnit.NANOSECONDS);
            var session=response.sslSession().orElseThrow(GoDeliveryAuthoritySource::denied);
            if (!"TLSv1.3".equals(session.getProtocol()) || !certificateSha256.equals(HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(session.getPeerCertificates()[0].getEncoded())))) throw denied();
            var result=JdbcDeliveryLedger.JSON.readValue(response.body(),TestProjectCurrentPolicyVerifier.Snapshot.class);
            if (!operation.equals(result.operationId()) || !run.equals(result.runId()) || !attempt.equals(result.attemptId())) throw denied();
            return result;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt();throw denied(); }
        catch (Exception unavailable) { throw denied(); }
        finally { if(pending!=null && !pending.isDone()) pending.cancel(true); }
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
