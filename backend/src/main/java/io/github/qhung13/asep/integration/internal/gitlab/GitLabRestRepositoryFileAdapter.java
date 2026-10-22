package io.github.qhung13.asep.integration.internal.gitlab;

import io.github.qhung13.asep.integration.GitLabRepositoryFileReader;
import io.github.qhung13.asep.integration.RepositoryFileContent;
import io.github.qhung13.asep.integration.RepositoryFileReadException;
import io.github.qhung13.asep.integration.RepositoryFileReadException.Reason;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Bounded, transient source reads. The injected transport's redirect and buffering
 * configuration requires separate verification; this adapter never follows redirects itself.
 */
public final class GitLabRestRepositoryFileAdapter implements GitLabRepositoryFileReader {

    private static final int DEFAULT_MAX_BYTES = 256 * 1024;
    private final RestClient restClient;
    private final int maxBytes;

    public GitLabRestRepositoryFileAdapter(RestClient restClient) {
        this(restClient, DEFAULT_MAX_BYTES);
    }

    public GitLabRestRepositoryFileAdapter(RestClient restClient, int maxBytes) {
        this.restClient = Objects.requireNonNull(restClient, "restClient");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("File byte limit must be positive");
        }
        this.maxBytes = maxBytes;
    }

    @Override
    public RepositoryFileContent readTextFile(long projectId, String commitSha, String filePath) {
        validateInput(projectId, commitSha, filePath);
        try {
            return restClient.get().uri(builder -> {
                URI base = builder.path("/projects/" + projectId + "/repository/files/").build();
                // Encode the logical path exactly once as one component, including '/'.
                // Returning a URI avoids a second template expansion of encoded '%' bytes.
                return UriComponentsBuilder.fromUri(base)
                        .path(UriUtils.encode(filePath, StandardCharsets.UTF_8)).path("/raw")
                        .replaceQuery(null).queryParam("ref", commitSha).build(true).toUri();
            }).header(HttpHeaders.ACCEPT_ENCODING, "identity").exchange((request, response) -> {
                int status = response.getStatusCode().value();
                if (status != 200) {
                    throw httpFailure(status);
                }
                long declaredSize = validateHeaders(response.getHeaders());
                byte[] bytes;
                try (InputStream body = response.getBody()) {
                    if (body == null) {
                        throw new RepositoryFileReadException(Reason.INVALID_RESPONSE);
                    }
                    bytes = body.readNBytes(maxBytes);
                    if (body.read() != -1) {
                        throw new RepositoryFileReadException(Reason.TOO_LARGE);
                    }
                }
                if (declaredSize >= 0 && declaredSize != bytes.length) {
                    throw new RepositoryFileReadException(Reason.INVALID_RESPONSE);
                }
                return new RepositoryFileContent(projectId, commitSha, filePath,
                        decodeText(bytes), bytes.length);
            });
        } catch (RepositoryFileReadException exception) {
            // A failing stream.close() may attach a sensitive suppressed exception.
            throw new RepositoryFileReadException(exception.reason());
        } catch (RestClientException exception) {
            // Transport errors can include request URLs, credentials or source in causes.
            throw new RepositoryFileReadException(Reason.UPSTREAM_FAILURE);
        }
    }

    private long validateHeaders(HttpHeaders headers) {
        if (headers.containsHeader(HttpHeaders.CONTENT_RANGE)) {
            throw new RepositoryFileReadException(Reason.INVALID_RESPONSE);
        }
        String encoding = headers.getFirst(HttpHeaders.CONTENT_ENCODING);
        if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
            throw new RepositoryFileReadException(Reason.UNSUPPORTED_CONTENT);
        }
        List<String> lengths = headers.getOrEmpty(HttpHeaders.CONTENT_LENGTH);
        if (lengths.isEmpty()) {
            return -1;
        }
        if (lengths.size() != 1 || !lengths.getFirst().matches("[0-9]+")) {
            throw new RepositoryFileReadException(Reason.INVALID_RESPONSE);
        }
        long size;
        try {
            size = Long.parseLong(lengths.getFirst());
        } catch (NumberFormatException exception) {
            throw new RepositoryFileReadException(Reason.INVALID_RESPONSE);
        }
        if (size > maxBytes) {
            throw new RepositoryFileReadException(Reason.TOO_LARGE);
        }
        return size;
    }

    private static String decodeText(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new RepositoryFileReadException(Reason.UNSUPPORTED_CONTENT);
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            // Only TAB, LF and CR are supported among ISO control characters.
            if (Character.isISOControl(character) && character != '\t' && character != '\n' && character != '\r') {
                throw new RepositoryFileReadException(Reason.UNSUPPORTED_CONTENT);
            }
        }
        String firstLine = text.lines().findFirst().orElse("");
        if (firstLine.startsWith("\uFEFF")) {
            firstLine = firstLine.substring(1);
        }
        if (firstLine.equals("version https://git-lfs.github.com/spec/v1")
                || firstLine.equals("version https://hawser.github.com/spec/v1")) {
            // Fail closed on the pointer signature, including incomplete pointers.
            throw new RepositoryFileReadException(Reason.LFS_POINTER);
        }
        return text;
    }

    private static void validateInput(long projectId, String commitSha, String filePath) {
        if (projectId <= 0) {
            throw new IllegalArgumentException("Project ID must be positive");
        }
        if (commitSha == null || !commitSha.matches("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")) {
            throw new IllegalArgumentException("Commit SHA must be a full hexadecimal hash");
        }
        if (filePath == null || filePath.isBlank() || filePath.startsWith("/")
                || filePath.indexOf('\\') >= 0 || filePath.matches("[A-Za-z]:.*")
                || !StandardCharsets.UTF_8.newEncoder().canEncode(filePath)
                || filePath.chars().anyMatch(Character::isISOControl)) {
            throw invalidPath();
        }
        for (String component : filePath.split("/", -1)) {
            if (component.isBlank() || component.equals(".") || component.equals("..")) {
                throw invalidPath();
            }
        }
    }

    private static IllegalArgumentException invalidPath() {
        return new IllegalArgumentException("File path must be an unambiguous repository-relative path");
    }

    private static RepositoryFileReadException httpFailure(int status) {
        Reason reason = switch (status) {
            case 401, 403 -> Reason.ACCESS_DENIED;
            case 404 -> Reason.FILE_UNAVAILABLE;
            case 429 -> Reason.RATE_LIMITED;
            case 413 -> Reason.TOO_LARGE;
            case 408 -> Reason.UPSTREAM_FAILURE;
            default -> status >= 500 && status <= 599 ? Reason.UPSTREAM_FAILURE : Reason.INVALID_RESPONSE;
        };
        return new RepositoryFileReadException(reason);
    }
}
