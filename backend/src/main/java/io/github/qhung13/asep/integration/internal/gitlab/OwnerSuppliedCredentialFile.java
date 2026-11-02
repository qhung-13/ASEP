package io.github.qhung13.asep.integration.internal.gitlab;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.function.Function;
import org.springframework.web.client.RestClient;

/** Explicit trusted-host read session only. No environment lookup, token DTO, bean or child-process handoff. */
final class OwnerSuppliedCredentialFile {
    private OwnerSuppliedCredentialFile() {}
    static <T> T withReadClient(Path supplied, GitLabReadHttpClientFactory transport, Function<RestClient, T> read) {
        byte[] bytes = new byte[4097];
        try {
            Path path = supplied.toAbsolutePath().normalize();
            Path parent = path.getParent();
            if (parent == null || Files.isSymbolicLink(parent) || Files.isSymbolicLink(path)) throw invalid();
            var directory = Files.readAttributes(parent, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            var file = Files.readAttributes(path, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!directory.isDirectory() || !file.isRegularFile()
                    || !directory.permissions().equals(PosixFilePermissions.fromString("rwx------"))
                    || !file.permissions().equals(PosixFilePermissions.fromString("rw-------"))
                    || !directory.owner().equals(file.owner())
                    || !file.owner().getName().equals(System.getProperty("user.name"))
                    || file.size() < 1 || file.size() > 4096
                    || ((Number) Files.getAttribute(path, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).intValue() != 1) throw invalid();
            int count = 0;
            try (var channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                var buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    int readBytes = channel.read(buffer);
                    if (readBytes < 0) break;
                    count += readBytes;
                }
            }
            if (count == 0 || count > 4096) throw invalid();
            // Only a single trailing LF/CRLF is tolerated; never normalize embedded whitespace.
            if (bytes[count - 1] == '\n') {
                count--;
                if (count > 0 && bytes[count - 1] == '\r') count--;
            }
            if (count == 0) throw invalid();
            for (int n = 0; n < count; n++) if (bytes[n] < 33 || bytes[n] > 126) throw invalid();
            String credential = new String(bytes, 0, count, StandardCharsets.US_ASCII);
            // The library retains its header String for the scoped client lifetime. The
            // trusted caller must close its factory and terminate the isolated read process.
            return read.apply(transport.createClient(credential));
        } catch (IOException | RuntimeException unavailable) {
            throw invalid(); // Do not expose paths, credential content or source-bearing causes.
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }
    private static IllegalStateException invalid() { return new IllegalStateException("Owner credential/read session unavailable or unsafe"); }
}
