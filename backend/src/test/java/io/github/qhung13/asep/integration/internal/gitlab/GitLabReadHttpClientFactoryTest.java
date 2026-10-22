package io.github.qhung13.asep.integration.internal.gitlab;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.github.qhung13.asep.integration.MergeRequestMetadata;
import io.github.qhung13.asep.integration.RepositoryFileContent;
import io.github.qhung13.asep.integration.RepositoryFileReadException;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitLabReadHttpClientFactoryTest {

    private static final URI TRUSTED_API = URI.create("https://gitlab.com/api/v4");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);
    private static final String TOKEN = "FAKE_READ_TRANSPORT_CREDENTIAL_DO_NOT_USE";
    private static final String SOURCE = "SYNTHETIC_PRIVATE_TRANSPORT_SOURCE";
    private static final String PATH = "src/SYNTHETIC_PRIVATE_TRANSPORT_PATH.java";
    private static final String SENSITIVE = TOKEN + " " + SOURCE + " " + PATH;
    private static final String BASE_SHA = "a".repeat(40);
    private static final String START_SHA = "b".repeat(40);
    private static final String HEAD_SHA = "c".repeat(40);

    @Test
    void supportsAllReadAdaptersWithRealLoopbackTransportAndForkProvenance() throws Exception {
        String version = """
                {"id":93,"base_commit_sha":"%s","start_commit_sha":"%s",
                 "head_commit_sha":"%s","state":"collected"}
                """.formatted(BASE_SHA, START_SHA, HEAD_SHA).strip();
        String metadata = """
                {"id":99999,"project_id":42,"target_project_id":42,"source_project_id":91,"iid":17,
                 "title":"Fork change","state":"opened","source_branch":"feature","target_branch":"main",
                 "diff_refs":{"base_sha":"%s","start_sha":"%s","head_sha":"%s"}}
                """.formatted(BASE_SHA, START_SHA, HEAD_SHA);
        String detail = version.substring(0, version.length() - 1) + ",\"diffs\":[]}";
        try (LoopbackServer server = new LoopbackServer(exchange -> {
            String body = switch (exchange.getRequestURI().getRawPath()) {
                case "/api/v4/projects/42/merge_requests/17" -> metadata;
                case "/api/v4/projects/42/merge_requests/17/versions" -> "[" + version + "]";
                case "/api/v4/projects/42/merge_requests/17/versions/93" -> detail;
                case "/api/v4/projects/91/repository/files/src%2FSYNTHETIC_PRIVATE_TRANSPORT_PATH.java/raw" -> SOURCE + "\r\né🙂\n";
                default -> throw new AssertionError("Unexpected local test request");
            };
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE,
                    exchange.getRequestURI().getRawPath().endsWith("/raw") ? "application/octet-stream" : "application/json");
            respond(exchange, 200, body);
        }); GitLabReadHttpClientFactory factory = localFactory(server)) {
            RestClient client = factory.createClient(TOKEN);
            MergeRequestMetadata mr = new GitLabRestReadAdapter(client).getMergeRequest(42, 17);
            GitLabRestDiffReadAdapter diffs = new GitLabRestDiffReadAdapter(client);

            assertEquals(42, mr.projectId());
            assertEquals(OptionalLong.of(91), mr.sourceProjectId());
            assertEquals(93, diffs.listDiffVersions(42, 17).getFirst().versionId());
            assertEquals(HEAD_SHA, diffs.getDiffVersion(42, 17, 93).revision().headSha());
            RepositoryFileContent file = new GitLabRestRepositoryFileAdapter(client)
                    .readTextFile(91, HEAD_SHA, PATH);
            assertEquals(91, file.projectId());
            assertEquals(HEAD_SHA, file.commitSha());
            assertEquals(SOURCE + "\r\né🙂\n", file.text());
            assertEquals(4, server.requests.get());
            assertEquals(List.of(TOKEN, TOKEN, TOKEN, TOKEN), server.credentials);
            assertEquals("/api/v4/projects/91/repository/files/src%2FSYNTHETIC_PRIVATE_TRANSPORT_PATH.java/raw?ref="
                    + HEAD_SHA, server.targets.getLast());
        }
    }

    @ParameterizedTest
    @CsvSource({"302,false", "307,false", "308,false", "302,true", "307,true", "308,true"})
    void doesNotFollowRedirectsOrForwardCredentials(int status, boolean differentOrigin) throws Exception {
        try (LoopbackServer destination = new LoopbackServer(exchange -> respond(exchange, 200, SOURCE));
             LoopbackServer origin = new LoopbackServer(exchange -> {
                 if (exchange.getRequestURI().getPath().endsWith("/destination")) {
                     respond(exchange, 200, SOURCE);
                     return;
                 }
                 String host = differentOrigin ? destination.base().toString()
                         : "http://127.0.0.1:" + exchange.getLocalAddress().getPort() + "/api/v4";
                 exchange.getResponseHeaders().set(HttpHeaders.LOCATION, host + "/destination");
                 exchange.sendResponseHeaders(status, -1);
             }); GitLabReadHttpClientFactory factory = localFactory(origin)) {
            RestClient client = factory.createClient(TOKEN);

            int actual = client.get().uri("/redirect")
                    .exchange((request, response) -> response.getStatusCode().value());

            assertEquals(status, actual);
            assertEquals(1, origin.requests.get());
            assertEquals(List.of(TOKEN), origin.credentials);
            assertEquals(0, destination.requests.get());
            assertTrue(destination.credentials.isEmpty());
        }
    }

    @Test
    void readTimeoutAppliesToAStalledResponseBodyAndErrorsStaySanitized() throws Exception {
        CountDownLatch partialBodySent = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (LoopbackServer server = new LoopbackServer(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(SENSITIVE.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            partialBodySent.countDown();
            await(release);
        }); GitLabReadHttpClientFactory factory = GitLabReadHttpClientFactory.forLoopbackTesting(
                server.base(), CONNECT_TIMEOUT, Duration.ofMillis(250))) {
            try {
                var adapter = new GitLabRestRepositoryFileAdapter(factory.createClient(TOKEN));
                assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
                    RepositoryFileReadException failure = assertThrowsExactly(RepositoryFileReadException.class,
                            () -> adapter.readTextFile(91, HEAD_SHA, PATH));
                    assertEquals(RepositoryFileReadException.Reason.UPSTREAM_FAILURE, failure.reason());
                    assertSanitized(failure);
                });
                assertTrue(partialBodySent.await(1, TimeUnit.SECONDS), "Must exercise a body read after HTTP headers");
                assertEquals(1, server.requests.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void doesNotAutomaticallyRetryServiceUnavailable() throws Exception {
        try (LoopbackServer server = new LoopbackServer(exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.RETRY_AFTER, "1");
            respond(exchange, 503, SENSITIVE);
        }); GitLabReadHttpClientFactory factory = localFactory(server)) {
            var adapter = new GitLabRestRepositoryFileAdapter(factory.createClient(TOKEN));

            RepositoryFileReadException failure = assertThrowsExactly(RepositoryFileReadException.class,
                    () -> adapter.readTextFile(91, HEAD_SHA, PATH));

            assertEquals(RepositoryFileReadException.Reason.UPSTREAM_FAILURE, failure.reason());
            assertSanitized(failure);
            assertEquals(1, server.requests.get());
        }
    }

    @Test
    void doesNotAutomaticallyRetryAConnectionClosedBeforeResponseHeaders() throws Exception {
        try (LoopbackServer server = new LoopbackServer(HttpExchange::close);
             GitLabReadHttpClientFactory factory = localFactory(server)) {
            RestClientException failure = assertThrowsExactly(RestClientException.class,
                    () -> factory.createClient(TOKEN).get().uri("/closed").retrieve().body(String.class));

            assertSanitized(failure);
            assertEquals(1, server.requests.get());
        }
    }

    @Test
    void retrieveErrorsDoNotExposeRawBodiesOrCredentials() throws Exception {
        try (LoopbackServer server = new LoopbackServer(exchange -> respond(exchange, 500, SENSITIVE));
             GitLabReadHttpClientFactory factory = localFactory(server)) {
            RestClientResponseException failure = assertThrowsExactly(RestClientResponseException.class,
                    () -> factory.createClient(TOKEN).get().uri("/failure").retrieve().body(String.class));

            assertEquals(500, failure.getStatusCode().value());
            assertEquals("", failure.getResponseBodyAsString());
            assertSanitized(failure);
        }
    }

    @Test
    void credentialsAreNotImplicitlyAddedToUnauthenticatedClients() throws Exception {
        try (LoopbackServer server = new LoopbackServer(exchange -> respond(exchange, 200, "ok"));
             GitLabReadHttpClientFactory factory = localFactory(server)) {
            assertEquals("ok", factory.createClient().get().uri("/ping").retrieve().body(String.class));
            assertEquals(1, server.requests.get());
            assertNull(server.credentials.getFirst());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void blocksWritesBeforeNetworkOrCredentialTransmission(String method) throws Exception {
        try (LoopbackServer server = new LoopbackServer(exchange -> respond(exchange, 200, "unexpected"));
             GitLabReadHttpClientFactory factory = localFactory(server)) {
            IllegalArgumentException failure = assertThrowsExactly(IllegalArgumentException.class,
                    () -> factory.createClient(TOKEN).method(HttpMethod.valueOf(method)).uri("/projects")
                            .body(SENSITIVE).retrieve().toBodilessEntity());

            assertSanitized(failure);
            assertEquals(0, server.requests.get());
        }
    }

    @Test
    void blocksAbsoluteRequestsToAnotherOriginBeforeSendingCredentials() throws Exception {
        try (LoopbackServer source = new LoopbackServer(exchange -> respond(exchange, 200, "unexpected"));
             LoopbackServer destination = new LoopbackServer(exchange -> respond(exchange, 200, "unexpected"));
             GitLabReadHttpClientFactory factory = localFactory(source)) {
            IllegalArgumentException failure = assertThrowsExactly(IllegalArgumentException.class,
                    () -> factory.createClient(TOKEN).get().uri(destination.base() + "/secret")
                            .retrieve().toBodilessEntity());

            assertSanitized(failure);
            assertEquals(0, source.requests.get());
            assertEquals(0, destination.requests.get());
            assertTrue(destination.credentials.isEmpty());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"http://gitlab.com/api/v4", "https://other.example.test/api/v4",
            "http://127.0.0.1:12345/api/v4", "https://gitlab.com.evil.example.test/api/v4",
            "https://FAKE_READ_TRANSPORT_CREDENTIAL_DO_NOT_USE@gitlab.com/api/v4",
            "https://gitlab.com:444/api/v4", "https://gitlab.com/api/v4?private_token=secret",
            "https://gitlab.com/api/v4#fragment", "https://gitlab.com/other"})
    void rejectsUntrustedProductionBasesWithoutMakingRequests(String value) {
        URI uri = value == null ? null : URI.create(value);
        assertSanitized(assertThrowsExactly(IllegalArgumentException.class,
                () -> new GitLabReadHttpClientFactory(uri, CONNECT_TIMEOUT, READ_TIMEOUT)));
    }

    @ParameterizedTest
    @MethodSource("invalidTimeouts")
    void rejectsInvalidTimeoutsBeforeCreatingTransport(Duration timeout) {
        assertSanitized(assertThrowsExactly(IllegalArgumentException.class,
                () -> new GitLabReadHttpClientFactory(TRUSTED_API, timeout, READ_TIMEOUT)));
        assertSanitized(assertThrowsExactly(IllegalArgumentException.class,
                () -> new GitLabReadHttpClientFactory(TRUSTED_API, CONNECT_TIMEOUT, timeout)));
    }

    static Stream<Arguments> invalidTimeouts() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(Duration.ZERO),
                Arguments.of(Duration.ofMillis(-1)), Arguments.of(Duration.ofNanos(1)),
                Arguments.of(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "token with spaces", "token\r\nInjected: header"})
    void rejectsInvalidCredentialHeadersWithoutNetwork(String credential) {
        try (GitLabReadHttpClientFactory factory = new GitLabReadHttpClientFactory()) {
            assertSanitized(assertThrowsExactly(IllegalArgumentException.class, () -> factory.createClient(credential)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://gitlab.com/api/v4", "http://other.example.test:12345/api/v4",
            "http://127.0.0.1.evil.example.test:12345/api/v4", "http://localhost:12345/api/v4",
            "http://127.0.0.1:0/api/v4", "http://127.0.0.1:12345/other"})
    void loopbackTestSeamCannotBeUsedForArbitraryOrigins(String uri) {
        assertSanitized(assertThrowsExactly(IllegalArgumentException.class,
                () -> GitLabReadHttpClientFactory.forLoopbackTesting(URI.create(uri), CONNECT_TIMEOUT, READ_TIMEOUT)));
    }

    private static GitLabReadHttpClientFactory localFactory(LoopbackServer server) {
        return GitLabReadHttpClientFactory.forLoopbackTesting(server.base(), CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void await(CountDownLatch release) {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertSanitized(Throwable failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        for (String sentinel : List.of(TOKEN, SOURCE, "SYNTHETIC_PRIVATE_TRANSPORT_PATH")) {
            assertFalse(output.toString().contains(sentinel), "Error output must not expose private input");
        }
    }

    private static final class LoopbackServer implements AutoCloseable {
        private final HttpServer server;
        private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final AtomicInteger requests = new AtomicInteger();
        private final List<String> credentials = new CopyOnWriteArrayList<>();
        private final List<String> targets = new CopyOnWriteArrayList<>();

        private LoopbackServer(HttpHandler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                credentials.add(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
                targets.add(exchange.getRequestURI().toASCIIString());
                try (exchange) {
                    handler.handle(exchange);
                }
            });
            server.start();
        }

        private URI base() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4");
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
            executor.close();
        }
    }
}
