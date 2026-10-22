package io.github.qhung13.asep.integration.internal.gitlab;

import io.github.qhung13.asep.integration.RepositoryFileContent;
import io.github.qhung13.asep.integration.RepositoryFileReadException;
import io.github.qhung13.asep.integration.RepositoryFileReadException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitLabRestRepositoryFileAdapterTest {

    private static final String BASE_URL = "https://gitlab.example.test/api/v4";
    private static final String SHA = "a".repeat(40);
    private static final String PATH = "src/SYNTHETIC_PRIVATE_PATH.java";
    private static final String ENCODED_PATH = "src%2FSYNTHETIC_PRIVATE_PATH.java";
    private static final String FAKE_TOKEN = "FAKE_SOURCE_TEST_CREDENTIAL_DO_NOT_USE";
    private static final String SOURCE = "SYNTHETIC_PRIVATE_SOURCE";
    private static final String SENSITIVE = SOURCE + " " + PATH + " " + FAKE_TOKEN;
    private MockRestServiceServer server;
    private GitLabRestRepositoryFileAdapter adapter;
    private RestClient client;

    @BeforeEach
    void setUp() {
        bind(RestClient.builder().baseUrl(BASE_URL));
    }

    private void bind(RestClient.Builder builder) {
        builder.defaultHeader("PRIVATE-TOKEN", FAKE_TOKEN);
        server = MockRestServiceServer.bindTo(builder).build();
        client = builder.build();
        adapter = new GitLabRestRepositoryFileAdapter(client);
    }

    @AfterEach
    void verifyRequests() {
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "class Example {}\n", "Xin chào 世界 🙂", "line one\r\n\tline two\n", "\uFEFFtext with BOM"})
    void preservesUtf8TextLineEndingsAndProvenance(String text) {
        expect(textResponse(text));

        RepositoryFileContent actual = adapter.readTextFile(42, SHA, PATH);

        assertEquals(new RepositoryFileContent(42, SHA, PATH, text, text.getBytes(StandardCharsets.UTF_8).length), actual);
        assertFalse(actual.toString().contains("SYNTHETIC_PRIVATE_PATH"));
    }

    @ParameterizedTest
    @ValueSource(longs = {42, 9_876_543_210L})
    void readsFromExactlyTheCallerSelectedProject(long sourceProjectId) {
        expect(sourceProjectId, SHA, ENCODED_PATH, textResponse(SOURCE));

        assertEquals(sourceProjectId, adapter.readTextFile(sourceProjectId, SHA, PATH).projectId());
    }

    @ParameterizedTest
    @MethodSource("immutableCommits")
    void sendsAndPreservesFullImmutableCommitSha(String sha) {
        expect(42, sha, ENCODED_PATH, textResponse(SOURCE));

        assertEquals(sha, adapter.readTextFile(42, sha, PATH).commitSha());
    }

    static Stream<String> immutableCommits() {
        return Stream.of("ABCDEF0123".repeat(4), "b".repeat(64));
    }

    @ParameterizedTest
    @MethodSource("encodedPaths")
    void encodesLogicalPathExactlyOnce(String path, String encoded) {
        expect(42, SHA, encoded, textResponse(SOURCE));

        assertEquals(path, adapter.readTextFile(42, SHA, path).filePath());
    }

    static Stream<Arguments> encodedPaths() {
        return Stream.of(
                Arguments.of("src/main/Foo.java", "src%2Fmain%2FFoo.java"),
                Arguments.of("dir/a b%?# +.java", "dir%2Fa%20b%25%3F%23%20%2B.java"),
                Arguments.of("src/é中🙂.txt", "src%2F%C3%A9%E4%B8%AD%F0%9F%99%82.txt"),
                Arguments.of("literal%2Fname.txt", "literal%252Fname.txt"),
                Arguments.of("dir/[x]{y}@:$&=;,.txt", "dir%2F%5Bx%5D%7By%7D%40%3A%24%26%3D%3B%2C.txt"),
                Arguments.of(" spaced / name ", "%20spaced%20%2F%20name%20")
        );
    }

    @Test
    void pathEncodingDoesNotDependOnInjectedUriFactoryEncodingMode() {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(BASE_URL);
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        bind(RestClient.builder().uriBuilderFactory(factory));
        expect(42, SHA, "dir%2Fa%25%23%3F%2B.txt", textResponse(SOURCE));

        assertEquals("dir/a%#?+.txt", adapter.readTextFile(42, SHA, "dir/a%#?+.txt").filePath());
    }

    @Test
    void explicitlySelectsCommitAndDoesNotInheritBaseUrlLfsOrHeadParameters() {
        bind(RestClient.builder().baseUrl(BASE_URL + "?lfs=true&ref=HEAD"));
        expect(textResponse(SOURCE));

        assertEquals(SHA, adapter.readTextFile(42, SHA, PATH).commitSha());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsInvalidProjectBeforeHttp(long projectId) {
        assertInvalidInput(() -> adapter.readTextFile(projectId, SHA, PATH));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"HEAD", "main", "abc123", " \t\n"})
    void rejectsMutableMissingOrAbbreviatedRefsBeforeHttp(String sha) {
        assertInvalidInput(() -> adapter.readTextFile(42, sha, PATH));
    }

    @Test
    void rejectsNonhexAndInjectedRefsBeforeHttp() {
        assertInvalidInput(() -> adapter.readTextFile(42, "g".repeat(40), PATH));
        assertInvalidInput(() -> adapter.readTextFile(42, SHA + "?ref=" + FAKE_TOKEN, PATH));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "/absolute", "//other.example.test/file", "../secret", "dir/../file", "./file",
            "dir/./file", "dir//file", "dir/", "dir/ /file", "C:/file", "dir\\file", "dir/\nfile", "\0file", "\uD800file"})
    void rejectsInvalidOrAmbiguousLogicalPathsBeforeHttp(String path) {
        assertInvalidInput(() -> adapter.readTextFile(42, SHA, path));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void requiresPositiveByteLimit(int limit) {
        assertThrowsExactly(IllegalArgumentException.class, () -> new GitLabRestRepositoryFileAdapter(client, limit));
    }

    @Test
    void requiresInjectedClient() {
        assertThrowsExactly(NullPointerException.class, () -> new GitLabRestRepositoryFileAdapter(null, 8));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "4,8", "8,8"})
    void acceptsUnderAndExactlyAtLimitAndClosesStream(int size, int limit) {
        adapter = new GitLabRestRepositoryFileAdapter(client, limit);
        ObservedStream stream = new ObservedStream(size, 3);
        expect(request -> new MockClientHttpResponse(stream, HttpStatus.OK));

        RepositoryFileContent actual = adapter.readTextFile(42, SHA, PATH);

        assertEquals("a".repeat(size), actual.text());
        assertEquals(size, actual.byteSize());
        assertEquals(size, stream.bytesRead);
        assertTrue(stream.closed);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void defaultLimitIs256KiBAndOverflowIsNotTruncated(boolean overflow) {
        int limit = 256 * 1024;
        ObservedStream stream = new ObservedStream(limit + (overflow ? 1 : 0), 4096);
        expect(request -> new MockClientHttpResponse(stream, HttpStatus.OK));

        if (overflow) {
            assertFailure(Reason.TOO_LARGE, () -> adapter.readTextFile(42, SHA, PATH));
        } else {
            assertEquals(limit, adapter.readTextFile(42, SHA, PATH).byteSize());
        }
        assertEquals(limit + (overflow ? 1 : 0), stream.bytesRead);
        assertTrue(stream.closed);
    }

    @Test
    void limitCountsBytesRatherThanJavaCharacters() {
        adapter = new GitLabRestRepositoryFileAdapter(client, 6);
        expect(textResponse("é🙂"));

        RepositoryFileContent actual = adapter.readTextFile(42, SHA, PATH);

        assertEquals(6, actual.byteSize());
        assertEquals("é🙂", actual.text());
    }

    @Test
    void crossingLimitInsideUtf8SequenceIsTooLargeRatherThanReturnedPartialText() {
        adapter = new GitLabRestRepositoryFileAdapter(client, 1);
        expect(textResponse("é"));

        assertFailure(Reason.TOO_LARGE, () -> adapter.readTextFile(42, SHA, PATH));
    }

    @Test
    void rejectsDeclaredOversizeBeforeReadingBody() {
        adapter = new GitLabRestRepositoryFileAdapter(client, 8);
        ObservedStream stream = new ObservedStream(1_000_000, 3);
        expect(request -> {
            MockClientHttpResponse response = new MockClientHttpResponse(stream, HttpStatus.OK);
            response.getHeaders().setContentLength(1_000_000);
            return response;
        });

        assertFailure(Reason.TOO_LARGE, () -> adapter.readTextFile(42, SHA, PATH));
        assertEquals(0, stream.bytesRead);
        assertTrue(stream.closed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "misleading", "chunked"})
    void overflowReadsAtMostLimitPlusOneRegardlessOfFraming(String framing) {
        adapter = new GitLabRestRepositoryFileAdapter(client, 8);
        // Generate bytes on demand; this fixture never allocates a million-byte body.
        ObservedStream stream = new ObservedStream(1_000_000, 3);
        expect(request -> {
            MockClientHttpResponse response = new MockClientHttpResponse(stream, HttpStatus.OK);
            if (framing.equals("misleading")) {
                response.getHeaders().setContentLength(1);
            } else if (framing.equals("chunked")) {
                response.getHeaders().set(HttpHeaders.TRANSFER_ENCODING, "chunked");
            }
            return response;
        });

        assertFailure(Reason.TOO_LARGE, () -> adapter.readTextFile(42, SHA, PATH));
        assertEquals(9, stream.bytesRead);
        assertTrue(stream.closed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "6"})
    void rejectsLengthMismatchAndPrematureEnd(String length) {
        expect(textResponse("text").header(HttpHeaders.CONTENT_LENGTH, length));

        assertFailure(Reason.INVALID_RESPONSE, () -> adapter.readTextFile(42, SHA, PATH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "not-a-length", "9223372036854775808"})
    void sanitizesMalformedContentLength(String length) {
        expect(textResponse(SENSITIVE).header(HttpHeaders.CONTENT_LENGTH, length));

        assertFailure(Reason.INVALID_RESPONSE, () -> adapter.readTextFile(42, SHA, PATH));
    }

    @Test
    void rejectsDuplicateContentLengthValues() {
        expect(textResponse(SENSITIVE).header(HttpHeaders.CONTENT_LENGTH, "1", "2"));

        assertFailure(Reason.INVALID_RESPONSE, () -> adapter.readTextFile(42, SHA, PATH));
    }

    @Test
    void rejectsPartialRangeHeadersEvenWithHttp200() {
        expect(textResponse(SENSITIVE).header(HttpHeaders.CONTENT_RANGE, "bytes 0-2/100"));

        assertFailure(Reason.INVALID_RESPONSE, () -> adapter.readTextFile(42, SHA, PATH));
    }

    @Test
    void rejectsUnexpectedCompressedRepresentation() {
        expect(textResponse(SENSITIVE).header(HttpHeaders.CONTENT_ENCODING, "gzip"));

        assertFailure(Reason.UNSUPPORTED_CONTENT, () -> adapter.readTextFile(42, SHA, PATH));
    }

    @ParameterizedTest(name = "invalid UTF-8 case {index}")
    @MethodSource("invalidUtf8")
    void rejectsMalformedUtf8WithoutReplacement(byte[] bytes) {
        expect(withSuccess(bytes, null));

        assertFailure(Reason.UNSUPPORTED_CONTENT, () -> adapter.readTextFile(42, SHA, PATH));
    }

    static Stream<byte[]> invalidUtf8() {
        return Stream.of(new byte[] {(byte) 0xC3, 0x28}, new byte[] {(byte) 0xE2, (byte) 0x82},
                new byte[] {(byte) 0xC0, (byte) 0x80}, new byte[] {(byte) 0xED, (byte) 0xA0, (byte) 0x80});
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 7, 27, 127, 133})
    void rejectsBinaryControlCharactersWithoutLeakingSource(int control) {
        expect(textResponse(SENSITIVE + (char) control));

        assertFailure(Reason.UNSUPPORTED_CONTENT, () -> adapter.readTextFile(42, SHA, PATH));
    }

    @ParameterizedTest(name = "pointer variant {index}")
    @MethodSource("lfsPointers")
    void rejectsLfsPointersWithoutFollowingTheirContent(String pointer) {
        expect(textResponse(pointer));

        assertFailure(Reason.LFS_POINTER, () -> adapter.readTextFile(42, SHA, PATH));
    }

    static Stream<String> lfsPointers() {
        String pointer = "version https://git-lfs.github.com/spec/v1\n"
                + "oid sha256:" + "b".repeat(64) + "\nsize 999999999\n";
        return Stream.of(pointer, pointer.replace("\n", "\r\n"), "\uFEFF" + pointer,
                pointer.replace("git-lfs.github.com", "hawser.github.com"),
                "version https://git-lfs.github.com/spec/v1\n");
    }

    @Test
    void acceptsTextMentioningLfsWithoutPointerSignature() {
        String text = "const url = 'https://git-lfs.github.com/spec/v1';\n";
        expect(textResponse(text));

        assertEquals(text, adapter.readTextFile(42, SHA, PATH).text());
    }

    @Test
    void acceptsSafeUtf8DespiteBinaryMimeTypeAndExtension() {
        expect(42, SHA, "example.bin", withSuccess(SOURCE.getBytes(StandardCharsets.UTF_8),
                MediaType.APPLICATION_OCTET_STREAM));

        assertEquals(SOURCE, adapter.readTextFile(42, SHA, "example.bin").text());
    }

    @ParameterizedTest
    @CsvSource({"401,ACCESS_DENIED", "403,ACCESS_DENIED", "404,FILE_UNAVAILABLE", "429,RATE_LIMITED",
            "413,TOO_LARGE", "408,UPSTREAM_FAILURE", "500,UPSTREAM_FAILURE", "503,UPSTREAM_FAILURE",
            "206,INVALID_RESPONSE", "302,INVALID_RESPONSE", "307,INVALID_RESPONSE", "204,INVALID_RESPONSE",
            "201,INVALID_RESPONSE", "400,INVALID_RESPONSE"})
    void classifiesHttpFailuresWithoutRetriesWritesOrSensitiveErrors(int status, Reason reason) {
        expect(withStatus(HttpStatus.valueOf(status)).body(SENSITIVE)
                .header(HttpHeaders.RETRY_AFTER, "120")
                .header(HttpHeaders.LOCATION, "https://outside.example.test/" + FAKE_TOKEN));

        assertFailure(reason, () -> adapter.readTextFile(42, SHA, PATH));
    }

    @Test
    void neverFallsBackFromUnavailableSourceProjectToTargetRepository() {
        expect(91, SHA, ENCODED_PATH, withStatus(HttpStatus.NOT_FOUND).body(SENSITIVE));

        assertFailure(Reason.FILE_UNAVAILABLE, () -> adapter.readTextFile(91, SHA, PATH));
    }

    @Test
    void errorBodyIsNotReadAndResponseIsClosed() {
        ObservedStream stream = new ObservedStream(1_000_000, 3);
        expect(request -> new MockClientHttpResponse(stream, HttpStatus.FORBIDDEN));

        assertFailure(Reason.ACCESS_DENIED, () -> adapter.readTextFile(42, SHA, PATH));
        assertEquals(0, stream.bytesRead);
        assertTrue(stream.closed);
    }

    @ParameterizedTest(name = "simulated transport failure {index}")
    @MethodSource("transportFailures")
    void sanitizesSimulatedConnectionAndTimeoutFailures(IOException failure) {
        expect(withException(failure));

        assertFailure(Reason.UPSTREAM_FAILURE, () -> adapter.readTextFile(42, SHA, PATH));
    }

    static Stream<IOException> transportFailures() {
        return Stream.of(new ConnectException(SENSITIVE), new SocketTimeoutException(SENSITIVE));
    }

    @Test
    void sanitizesResponseStreamReadFailureAndClosesIt() {
        boolean[] closed = {false};
        InputStream stream = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException(SENSITIVE);
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        expect(request -> new MockClientHttpResponse(stream, HttpStatus.OK));

        assertFailure(Reason.UPSTREAM_FAILURE, () -> adapter.readTextFile(42, SHA, PATH));
        assertTrue(closed[0]);
    }

    @Test
    void stripsSensitiveSuppressedCloseFailureFromOverflowException() {
        adapter = new GitLabRestRepositoryFileAdapter(client, 8);
        InputStream stream = new InputStream() {
            @Override
            public int read() {
                return 'a';
            }

            @Override
            public void close() throws IOException {
                throw new IOException(SENSITIVE);
            }
        };
        expect(request -> new MockClientHttpResponse(stream, HttpStatus.OK));

        assertFailure(Reason.TOO_LARGE, () -> adapter.readTextFile(42, SHA, PATH));
    }

    private static DefaultResponseCreator textResponse(String text) {
        return withSuccess(text.getBytes(StandardCharsets.UTF_8), null);
    }

    private void expect(ResponseCreator response) {
        expect(42, SHA, ENCODED_PATH, response);
    }

    private void expect(long project, String sha, String encodedPath, ResponseCreator response) {
        server.expect(ExpectedCount.once(), requestTo(BASE_URL + "/projects/" + project
                        + "/repository/files/" + encodedPath + "/raw?ref=" + sha))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("PRIVATE-TOKEN", FAKE_TOKEN))
                .andExpect(header(HttpHeaders.ACCEPT_ENCODING, "identity"))
                .andRespond(response);
    }

    private void assertFailure(Reason reason, Executable read) {
        RepositoryFileReadException failure = assertThrowsExactly(RepositoryFileReadException.class, read);
        assertEquals(reason, failure.reason());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertSanitized(failure);
    }

    private void assertInvalidInput(Executable read) {
        assertSanitized(assertThrowsExactly(IllegalArgumentException.class, read));
    }

    private static void assertSanitized(Throwable failure) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        for (String privateValue : List.of(SOURCE, "SYNTHETIC_PRIVATE_PATH", FAKE_TOKEN)) {
            assertFalse(output.toString().contains(privateValue), "Exception must not expose private data");
        }
    }

    private static final class ObservedStream extends InputStream {
        private final int length;
        private final int chunkSize;
        private int bytesRead;
        private boolean closed;

        private ObservedStream(int length, int chunkSize) {
            this.length = length;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() {
            if (bytesRead == length) {
                return -1;
            }
            bytesRead++;
            return 'a';
        }

        @Override
        public int read(byte[] buffer, int offset, int count) {
            if (count == 0) {
                return 0;
            }
            if (bytesRead == length) {
                return -1;
            }
            int size = Math.min(Math.min(count, chunkSize), length - bytesRead);
            Arrays.fill(buffer, offset, offset + size, (byte) 'a');
            bytesRead += size;
            return size;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
