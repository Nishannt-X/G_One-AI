package com.example.g_one.speech;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Shared plumbing for upstream calls: bounded timeouts, and a status+body result instead of throwing on 4xx/5xx. */
public final class Http {
    public record Result(int status, byte[] body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private Http() {}

    public static RestClient client(String baseUrl, int timeoutSeconds) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public static Result call(String stage, RestClient.RequestHeadersSpec<?> request) {
        return call(stage, request, -1);
    }

    /**
     * Like {@link #call(String, RestClient.RequestHeadersSpec)} but never buffers more than {@code maxBodyBytes + 1}
     * bytes of the response, however much the server sends. A body longer than {@code maxBodyBytes} comes back with
     * exactly {@code maxBodyBytes + 1} bytes, so the caller can tell "too large" without reading the rest.
     */
    public static Result call(String stage, RestClient.RequestHeadersSpec<?> request, int maxBodyBytes) {
        try {
            if (maxBodyBytes < 0) {
                return request.exchange((req, res) -> new Result(res.getStatusCode().value(), res.getBody().readAllBytes()));
            }
            // close=false: Spring's own close would first drain the rest of the body, which an endless one never ends.
            // Closing just the stream aborts the connection instead.
            return request.exchange((req, res) -> {
                try (InputStream in = res.getBody()) {
                    return new Result(res.getStatusCode().value(), in.readNBytes(maxBodyBytes + 1));
                }
            }, false);
        } catch (RestClientException e) {
            throw new UpstreamException(stage, "The service could not be reached (timeout or network error).");
        }
    }
}
