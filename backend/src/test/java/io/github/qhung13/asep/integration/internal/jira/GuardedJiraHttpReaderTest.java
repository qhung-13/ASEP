package io.github.qhung13.asep.integration.internal.jira;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.qhung13.asep.integration.JiraReadException;
import io.github.qhung13.asep.integration.JiraTicketReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GuardedJiraHttpReaderTest {
    private static final JiraTicketReader.Selection SELECTED = new JiraTicketReader.Selection("synthetic-site", "10001", "10002");
    private static final JiraTicketReader.Limits LIMITS = new JiraTicketReader.Limits(8192, 256, 1024, 8, 128);
    private static final String PRIVATE = "FAKE_PRIVATE_HTTP_BODY";

    private static byte[] body() throws Exception {
        try (var in = GuardedJiraHttpReaderTest.class.getResourceAsStream("/contracts/manual-jira-fixture-v1.json")) {
            return JsonMapper.builder().build().readTree(in).get("responseBody").asString().getBytes(StandardCharsets.UTF_8);
        }
    }
    private static void rejected(Runnable action, JiraReadException.Reason reason) {
        var failure = assertThrowsExactly(JiraReadException.class, action::run);
        assertEquals(reason, failure.reason());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(PRIVATE));
        assertFalse(failure.toString().contains("http://"));
    }
    @FunctionalInterface private interface Handler { void handle(HttpExchange exchange) throws Exception; }
    private static final class Fixture implements AutoCloseable {
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicInteger requests = new AtomicInteger();
        final AtomicReference<String> request = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final CountDownLatch handlerCompleted = new CountDownLatch(1);
        Fixture(Handler handler) throws IOException {
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                request.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
                try { handler.handle(exchange); }
                catch (IOException disconnected) { /* Expected when a bounded reader cancels. */ }
                catch (Throwable unexpected) { failure.set(unexpected); }
                finally {
                    exchange.close();
                    handlerCompleted.countDown();
                }
            });
            server.setExecutor(executor);
            server.start();
        }
        URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
        GuardedJiraHttpReader reader() { return GuardedJiraHttpReader.syntheticLoopback("synthetic-site", origin(), Duration.ofSeconds(2)); }
        @Override public void close() throws Exception {
            server.stop(0);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
            assertNull(failure.get());
        }
    }
    private static void respond(HttpExchange exchange, int status, byte[] body, boolean chunked) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, chunked ? 0 : body.length);
        exchange.getResponseBody().write(body);
    }

    @Test void enrolledGetUsesOnlyAllowlistedFieldsAndPreservesNormalizationIdentity() throws Exception {
        byte[] raw = body();
        try (var fixture = new Fixture(e -> respond(e, 200, raw, false)); var reader = fixture.reader()) {
            var actual = reader.read(SELECTED, LIMITS);
            var expected = new FixtureJiraTicketReader(Map.of(SELECTED, new FixtureJiraTicketReader.Response(200, "application/json", raw))).read(SELECTED, LIMITS);
            assertEquals(expected, actual);
            assertEquals(expected.fingerprint(), actual.fingerprint());
            assertEquals("GET /rest/api/3/issue/10002?fields=" + GuardedJiraHttpReader.FIELDS, fixture.request.get());
            assertEquals(1, fixture.requests.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"http://evil.invalid:80", "https://127.0.0.1:443", "file:///tmp/issue", "http://localhost:8080", "http://127.0.0.1", "http://127.0.0.1:0", "http://127.0.0.1:65536", "http://127.0.0.1:8080/", "http://127.0.0.1:8080/rest/api/3/issue/1", "http://user:secret@127.0.0.1:8080", "http://127.0.0.1:8080?url=evil", "http://127.0.0.1:8080#evil", "http://127.0.0.1:08080"})
    void rejectsUnknownOriginsPathsSchemesAndAmbiguousEnrollment(String origin) {
        rejected(() -> GuardedJiraHttpReader.syntheticLoopback("synthetic-site", URI.create(origin), Duration.ofSeconds(1)), JiraReadException.Reason.REQUEST_REJECTED);
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "get", "GET\r\nHost: evil"})
    void rejectsUnsupportedMethodsBeforeNetwork(String method) throws Exception {
        try (var fixture = new Fixture(e -> fail("unauthorized request")); var reader = fixture.reader()) {
            rejected(() -> reader.read(method, SELECTED, LIMITS), JiraReadException.Reason.REQUEST_REJECTED);
            assertEquals(0, fixture.requests.get());
        }
    }

    @Test void rejectsUnknownSiteAndCallerUrlsCannotOverrideEndpoint() throws Exception {
        try (var fixture = new Fixture(e -> fail("unauthorized request")); var reader = fixture.reader()) {
            rejected(() -> reader.read(new JiraTicketReader.Selection("other-site", "10001", "10002"), LIMITS), JiraReadException.Reason.REQUEST_REJECTED);
            for (String injected : new String[]{fixture.origin() + "/evil", "//127.0.0.1/evil", "../1", "%2fsecret", "1?fields=attachment", "1#evil", "1\r\nHost: evil"}) {
                assertThrows(IllegalArgumentException.class, () -> new JiraTicketReader.Selection("synthetic-site", "10001", injected));
                assertThrows(IllegalArgumentException.class, () -> new JiraTicketReader.Selection(injected, "10001", "10002"));
            }
            assertEquals(0, fixture.requests.get());
        }
    }

    @ParameterizedTest @ValueSource(ints = {300, 301, 302, 303, 304, 307, 308})
    void rejectsRedirectWithoutFollowingSameOrCrossOriginCanaries(int status) throws Exception {
        try (var canary = new Fixture(e -> respond(e, 200, body(), false))) {
            for (String location : new String[]{"/canary", canary.origin() + "/canary", "https://untrusted.invalid/"}) {
                try (var fixture = new Fixture(e -> {
                    e.getResponseHeaders().set("Location", location);
                    e.sendResponseHeaders(status, -1);
                }); var reader = fixture.reader()) {
                    rejected(() -> reader.read(SELECTED, LIMITS), JiraReadException.Reason.UNAVAILABLE);
                    assertEquals(1, fixture.requests.get());
                    assertEquals(0, canary.requests.get());
                }
            }
        }
    }

    @Test void attachmentAndPromptInjectionLinksNeverCauseAdditionalRequests() throws Exception {
        try (var canary = new Fixture(e -> fail("ticket content gained network capability"))) {
            byte[] malicious = new String(body(), StandardCharsets.UTF_8).replace("https://untrusted.invalid", canary.origin().toString()).getBytes(StandardCharsets.UTF_8);
            try (var fixture = new Fixture(e -> respond(e, 200, malicious, true)); var reader = fixture.reader()) {
                var ticket = reader.read(SELECTED, LIMITS);
                assertTrue(ticket.description().contains(canary.origin().toString()));
                assertEquals(1, fixture.requests.get());
                assertEquals(0, canary.requests.get());
            }
        }
    }

    @ParameterizedTest @ValueSource(strings = {"length", "chunked", "malformed", "duplicate", "trailing", "encoding", "content-type", "wrong-identity", "adf"})
    void oversizedOrMalformedResponsesFailClosed(String mode) throws Exception {
        byte[] raw = body();
        try (var fixture = new Fixture(e -> {
            if (mode.equals("encoding") || mode.equals("content-type")) {
                e.getResponseHeaders().set("Content-Type", mode.equals("content-type") ? "text/html" : "application/json");
                if (mode.equals("encoding")) e.getResponseHeaders().set("Content-Encoding", "gzip");
                e.sendResponseHeaders(200, raw.length);
                e.getResponseBody().write(raw);
            } else {
                byte[] bad = switch (mode) {
                    case "length", "chunked" -> "x".repeat(8193).getBytes(StandardCharsets.UTF_8);
                    case "malformed" -> ("{" + PRIVATE).getBytes(StandardCharsets.UTF_8);
                    case "duplicate" -> "{\"id\":\"10002\",\"id\":\"10002\"}".getBytes(StandardCharsets.UTF_8);
                    case "trailing" -> (new String(raw, StandardCharsets.UTF_8) + " {}").getBytes(StandardCharsets.UTF_8);
                    case "wrong-identity" -> new String(raw, StandardCharsets.UTF_8).replace("10002", "10003").getBytes(StandardCharsets.UTF_8);
                    case "adf" -> new String(raw, StandardCharsets.UTF_8).replace("paragraph", "media").getBytes(StandardCharsets.UTF_8);
                    default -> throw new AssertionError();
                };
                respond(e, 200, bad, mode.equals("chunked"));
            }
        }); var reader = fixture.reader()) {
            rejected(() -> reader.read(SELECTED, LIMITS), JiraReadException.Reason.INVALID_RESPONSE);
            assertEquals(1, fixture.requests.get());
        }
    }

    @ParameterizedTest @ValueSource(ints = {201, 206, 401, 403, 404, 429, 500, 503})
    void httpErrorsAreSanitizedAndNotRetried(int status) throws Exception {
        try (var fixture = new Fixture(e -> respond(e, status, PRIVATE.getBytes(StandardCharsets.UTF_8), false)); var reader = fixture.reader()) {
            rejected(() -> reader.read(SELECTED, LIMITS), JiraReadException.Reason.UNAVAILABLE);
            assertEquals(1, fixture.requests.get());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"headers", "body"})
    void deadlineBoundsHeadersAndEntireResponseBody(String mode) throws Exception {
        var release = new CountDownLatch(1);
        try (var fixture = new Fixture(e -> {
            if (mode.equals("body")) {
                e.getResponseHeaders().set("Content-Type", "application/json");
                e.sendResponseHeaders(200, 0);
                e.getResponseBody().write('{');
                e.getResponseBody().flush();
            }
            assertTrue(release.await(5, TimeUnit.SECONDS));
        }); var reader = GuardedJiraHttpReader.syntheticLoopback("synthetic-site", fixture.origin(), Duration.ofMillis(400))) {
            try {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> rejected(() -> reader.read(SELECTED, LIMITS), JiraReadException.Reason.UNAVAILABLE));
                assertEquals(1, fixture.requests.get());
            } finally {
                release.countDown();
                assertTrue(fixture.handlerCompleted.await(3, TimeUnit.SECONDS), "released handler did not complete before teardown");
            }
        }
    }

    @Test void connectionFailureIsSanitizedAndLiveAccessStaysDisabled() throws Exception {
        URI unavailable;
        try (var fixture = new Fixture(e -> fail("closed fixture"))) { unavailable = fixture.origin(); }
        try (var reader = GuardedJiraHttpReader.syntheticLoopback("synthetic-site", unavailable, Duration.ofMillis(400))) {
            rejected(() -> reader.read(SELECTED, LIMITS), JiraReadException.Reason.UNAVAILABLE);
        }
        rejected(() -> FixtureJiraTicketReader.liveAccessDisabled().read(SELECTED, LIMITS), JiraReadException.Reason.LIVE_ACCESS_DISABLED);
    }

    @Test void closedClientAndInvalidProfilesFailClosed() throws Exception {
        try (var fixture = new Fixture(e -> fail("closed/invalid client requested network"))) {
            var reader = fixture.reader();
            reader.close();
            rejected(() -> reader.read(SELECTED, LIMITS), JiraReadException.Reason.UNAVAILABLE);
            rejected(() -> GuardedJiraHttpReader.syntheticLoopback("synthetic-site", fixture.origin(), Duration.ZERO), JiraReadException.Reason.REQUEST_REJECTED);
            rejected(() -> GuardedJiraHttpReader.syntheticLoopback("synthetic-site", fixture.origin(), Duration.ofSeconds(31)), JiraReadException.Reason.REQUEST_REJECTED);
            assertEquals(0, fixture.requests.get());
        }
    }
}
