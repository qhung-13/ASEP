package io.github.qhung13.asep.integration.internal.jira;

import io.github.qhung13.asep.integration.JiraReadException;
import io.github.qhung13.asep.integration.JiraTicketReader;
import io.github.qhung13.asep.integration.JiraTicketSnapshot;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Trusted HTTP-read boundary, currently enrollable ONLY for synthetic loopback fixtures.
 * No bean, credentials or live activation switch. Real enrollment requires separately
 * verified runtime policy/credential controls; policy-design approval alone cannot enable it.
 */
public final class GuardedJiraHttpReader implements JiraTicketReader, AutoCloseable {
    static final String FIELDS = "project,issuetype,status,summary,description";
    private final String siteId;
    private final URI origin;
    private final Duration deadline;
    private final HttpClient client;

    public static GuardedJiraHttpReader syntheticLoopback(String siteId, URI origin, Duration deadline) {
        // Literal IPv4 only: no DNS alias, user info, path, query, fragment or scheme ambiguity.
        if (siteId == null || !siteId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,159}")
                || origin == null || !"http".equals(origin.getScheme())
                || !"127.0.0.1".equals(origin.getHost()) || origin.getPort() < 1 || origin.getPort() > 65535
                || !origin.toASCIIString().equals("http://127.0.0.1:" + origin.getPort())
                || deadline == null || deadline.isNegative() || deadline.isZero()
                || deadline.compareTo(Duration.ofSeconds(30)) > 0) throw rejected();
        return new GuardedJiraHttpReader(siteId, origin, deadline);
    }

    private GuardedJiraHttpReader(String siteId, URI origin, Duration deadline) {
        this.siteId = siteId;
        this.origin = origin;
        this.deadline = deadline;
        this.client = HttpClient.newBuilder().connectTimeout(deadline)
                .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1)
                .proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {}
                }).build();
    }

    @Override public JiraTicketSnapshot read(Selection selection, Limits limits) {
        return read("GET", selection, limits);
    }

    // Internal guard is exercised directly; the capability API exposes GET only, never a URL.
    JiraTicketSnapshot read(String method, Selection selection, Limits limits) {
        if (!"GET".equals(method) || selection == null || limits == null
                || !siteId.equals(selection.siteId())) throw rejected();
        URI endpoint = origin.resolve("/rest/api/3/issue/" + selection.issueId() + "?fields=" + FIELDS);
        if (!origin.getRawAuthority().equals(endpoint.getRawAuthority())
                || !origin.getScheme().equals(endpoint.getScheme())) throw rejected();
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(deadline)
                .header("Accept", "application/json").GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(request, info -> {
                String contentType = info.headers().firstValue("Content-Type").orElse("");
                String encoding = info.headers().firstValue("Content-Encoding").orElse("identity");
                if (info.statusCode() != 200) throw new JiraReadException(JiraReadException.Reason.UNAVAILABLE);
                if (!contentType.matches("(?i)application/json(?:;\\s*charset=utf-8)?")
                        || !"identity".equalsIgnoreCase(encoding)
                        || info.headers().firstValueAsLong("Content-Length").orElse(0) > limits.responseBytes()) {
                    throw invalid();
                }
                return new BoundedBody(limits.responseBytes());
            });
            // Unlike an InputStream handler, this deadline covers headers AND the entire body,
            // including chunked/stalled responses. The subscriber caps allocation before copying.
            byte[] body = pending.get(deadline.toNanos(), TimeUnit.NANOSECONDS).body();
            return FixtureJiraTicketReader.normalize(selection, limits, body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new JiraReadException(JiraReadException.Reason.UNAVAILABLE);
        } catch (TimeoutException timeout) {
            throw new JiraReadException(JiraReadException.Reason.UNAVAILABLE);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof JiraReadException safe) throw safe;
            throw new JiraReadException(JiraReadException.Reason.UNAVAILABLE);
        } catch (JiraReadException safe) {
            throw safe;
        } catch (RuntimeException failure) {
            throw new JiraReadException(JiraReadException.Reason.UNAVAILABLE);
        } finally {
            if (pending != null) pending.cancel(true);
        }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int maximum;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int maximum) { this.maximum = maximum; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > maximum - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(invalid());
                    return;
                }
                byte[] part = new byte[buffer.remaining()];
                buffer.get(part);
                bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    @Override public void close() { client.shutdownNow(); }
    private static JiraReadException rejected() { return new JiraReadException(JiraReadException.Reason.REQUEST_REJECTED); }
    private static JiraReadException invalid() { return new JiraReadException(JiraReadException.Reason.INVALID_RESPONSE); }
}
