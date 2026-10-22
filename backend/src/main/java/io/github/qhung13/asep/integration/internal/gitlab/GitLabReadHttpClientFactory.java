package io.github.qhung13.asep.integration.internal.gitlab;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Explicit read-only transport. The owner closes this factory after its adapters
 * stop using the clients. No clients or credentials are registered as Spring beans.
 */
public final class GitLabReadHttpClientFactory implements AutoCloseable {

    private static final URI TRUSTED_API = URI.create("https://gitlab.com/api/v4");
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(20);
    private final URI apiBase;
    private final CloseableHttpClient httpClient;
    private final HttpComponentsClientHttpRequestFactory requestFactory;

    public GitLabReadHttpClientFactory() {
        this(TRUSTED_API, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public GitLabReadHttpClientFactory(URI apiBase, Duration connectTimeout, Duration readTimeout) {
        this(apiBase, connectTimeout, readTimeout, false);
    }

    private GitLabReadHttpClientFactory(URI apiBase, Duration connectTimeout, Duration readTimeout, boolean testOnly) {
        validateBase(apiBase, testOnly);
        Timeout connect = Timeout.ofMilliseconds(timeoutMillis(connectTimeout));
        Timeout read = Timeout.ofMilliseconds(timeoutMillis(readTimeout));
        this.apiBase = apiBase;
        // Default TLS strategy retains certificate and hostname verification.
        var connections = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(connect).setSocketTimeout(read).build())
                .build();
        this.httpClient = HttpClients.custom().setConnectionManager(connections)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(read).setResponseTimeout(read).build())
                .disableRedirectHandling().disableAutomaticRetries()
                .disableCookieManagement().disableAuthCaching().disableContentCompression()
                .build();
        this.requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);
    }

    // Package-private, literal loopback only; not an alternative production URL setting.
    static GitLabReadHttpClientFactory forLoopbackTesting(URI apiBase, Duration connectTimeout, Duration readTimeout) {
        return new GitLabReadHttpClientFactory(apiBase, connectTimeout, readTimeout, true);
    }

    public RestClient createClient() {
        return clientBuilder().build();
    }

    /** Integration Management must supply an authorized read credential; scopes are not inferred here. */
    public RestClient createClient(String readCredential) {
        if (readCredential == null || readCredential.isBlank()
                || readCredential.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalArgumentException("Read credential must be a nonblank header-safe value");
        }
        return clientBuilder().defaultHeader("PRIVATE-TOKEN", readCredential).build();
    }

    private RestClient.Builder clientBuilder() {
        return RestClient.builder().baseUrl(apiBase.toASCIIString())
                .requestFactory((uri, method) -> {
                    validateRequest(uri, method);
                    return requestFactory.createRequest(uri, method);
                })
                .requestInterceptor((request, body, execution) -> {
                    try {
                        return execution.execute(request, body);
                    } catch (IOException exception) {
                        throw new RestClientException("GitLab read transport failed");
                    }
                })
                .defaultStatusHandler(status -> status.isError(), (request, response) -> {
                    // retrieve() must not expose error bodies; exchange() adapters retain
                    // their own status handling and application-specific classifications.
                    throw new RestClientResponseException("GitLab read returned HTTP " + response.getStatusCode().value(),
                            response.getStatusCode().value(), "", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8);
                });
    }

    private void validateRequest(URI uri, HttpMethod method) {
        if (method != HttpMethod.GET) {
            throw new IllegalArgumentException("GitLab read client permits GET only");
        }
        if (!apiBase.getScheme().equalsIgnoreCase(uri.getScheme())
                || !apiBase.getHost().equalsIgnoreCase(uri.getHost())
                || effectivePort(apiBase) != effectivePort(uri)
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getRawPath() == null || !uri.getRawPath().startsWith("/api/v4/")
                || !uri.equals(uri.normalize())) {
            throw new IllegalArgumentException("GitLab read request must stay within the trusted API origin");
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
    }

    private static void validateBase(URI uri, boolean testOnly) {
        if (!testOnly) {
            if (!TRUSTED_API.equals(uri)) {
                throw new IllegalArgumentException("Only the trusted GitLab.com API base is supported");
            }
        } else if (uri == null || !"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getPort() < 1 || uri.getPort() > 65535 || !"/api/v4".equals(uri.getRawPath())
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Test API base must be an explicit loopback origin");
        }
    }

    private static long timeoutMillis(Duration timeout) {
        if (timeout != null) {
            try {
                long millis = timeout.toMillis();
                if (!timeout.isNegative() && millis >= 1 && millis <= Integer.MAX_VALUE) {
                    return millis;
                }
            } catch (ArithmeticException ignored) {
                // Do not expose supplied configuration through an overflow cause.
            }
        }
        throw new IllegalArgumentException("Timeout must be between 1 millisecond and 2147483647 milliseconds");
    }

    @Override
    public void close() {
        try {
            httpClient.close();
        } catch (IOException exception) {
            throw new IllegalStateException("GitLab read client close failed");
        }
    }
}
