package io.github.qhung13.asep.integration.internal.gitlab;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class OwnerSuppliedCredentialFileTest {
    private static final String TOKEN = "SYNTHETIC_CREDENTIAL_NEVER_REAL";
    @TempDir Path privateDirectory;
    private Path credential(String body) throws Exception {
        Files.setPosixFilePermissions(privateDirectory, PosixFilePermissions.fromString("rwx------"));
        Path file = privateDirectory.resolve("credential");
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        return file;
    }
    @ParameterizedTest @ValueSource(strings = {"", "\n", "\r\n"})
    void trustedReadUsesHeaderOnlyAndCannotMutate(String trailing) throws Exception {
        var requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v4/projects/42", exchange -> {
            requests.incrementAndGet();
            boolean valid = exchange.getRequestMethod().equals("GET") && TOKEN.equals(exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
            exchange.sendResponseHeaders(valid ? 200 : 400, -1); exchange.close();
        });
        server.start();
        try (var transport = GitLabReadHttpClientFactory.forLoopbackTesting(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v4"), Duration.ofSeconds(1), Duration.ofSeconds(1))) {
            Path file = credential(TOKEN + trailing);
            int status = OwnerSuppliedCredentialFile.withReadClient(file, transport, client -> {
                assertThrows(IllegalArgumentException.class, () -> client.post().uri("/projects/42").retrieve().toBodilessEntity());
                return client.get().uri("/projects/42").exchange((request, response) -> response.getStatusCode().value());
            });
            assertEquals(200, status); assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }
    @ParameterizedTest @ValueSource(strings = {"file-mode", "directory-mode", "symlink", "parent-symlink", "hardlink", "empty", "embedded-newline", "space", "oversize"})
    void unsafeStorageOrHeaderIsRejectedWithoutExposingContent(String mode) throws Exception {
        Path file = credential(TOKEN);
        switch (mode) {
            case "file-mode" -> Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            case "directory-mode" -> Files.setPosixFilePermissions(privateDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));
            case "symlink" -> { Path link = privateDirectory.resolve("link"); Files.createSymbolicLink(link, file); file = link; }
            case "parent-symlink" -> { Path link = privateDirectory.resolve("directory-link"); Files.createSymbolicLink(link, privateDirectory); file = link.resolve("credential"); }
            case "hardlink" -> Files.createLink(privateDirectory.resolve("other-link"), file);
            case "empty" -> Files.writeString(file, "");
            case "embedded-newline" -> Files.writeString(file, TOKEN + "\r\nPRIVATE-TOKEN: injected");
            case "space" -> Files.writeString(file, " " + TOKEN);
            case "oversize" -> Files.writeString(file, TOKEN.repeat(4096));
        }
        Path selected = file;
        try (var transport = new GitLabReadHttpClientFactory()) {
            var failure = assertThrows(IllegalStateException.class, () -> OwnerSuppliedCredentialFile.withReadClient(selected, transport, client -> fail("unsafe credential reached reader")));
            assertFalse(failure.toString().contains(TOKEN)); assertFalse(failure.toString().contains(file.toString())); assertNull(failure.getCause());
        }
    }
    @Test void classpathExportIsExplicitAndContainsNoCredential() throws Exception {
        String output = System.getProperty("asep.vnt14.read.classpathFile");
        if (output != null) {
            String classpath = System.getProperty("surefire.test.class.path");
            assertNotNull(classpath); assertFalse(classpath.contains(TOKEN));
            Files.writeString(Path.of(output), classpath);
        }
    }
}
