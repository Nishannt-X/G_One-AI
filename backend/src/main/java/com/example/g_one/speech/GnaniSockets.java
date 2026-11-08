package com.example.g_one.speech;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/** Shared plumbing for the Gnani WebSocket clients. */
final class GnaniSockets {
    private GnaniSockets() {}

    /** HTTP/1.1 only, like the REST clients: the WebSocket upgrade is an HTTP/1.1 request. */
    static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /** The WebSocket address for a REST base URL: {@code https} becomes {@code wss}, {@code http} becomes {@code ws}. */
    static URI wsUri(String baseUrl, String path) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (base.startsWith("https://")) base = "wss://" + base.substring("https://".length());
        else if (base.startsWith("http://")) base = "ws://" + base.substring("http://".length());
        return URI.create(base + path);
    }
}
