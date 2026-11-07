package com.example.g_one.speech;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** The shared upstream call: bounded reads for callers that ask, unchanged full reads for the rest. */
class HttpTest {
    private static final int LIMIT = 256 * 1024;
    private HttpServer server;
    private RestClient client;
    private final AtomicLong sent = new AtomicLong();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 1 MiB: bigger than the limit, small enough to read in full.
        server.createContext("/finite", x -> {
            byte[] body = new byte[1024 * 1024];
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        // Endless chunked body: only stops when the client hangs up (or after 512 MiB, so a broken test cannot hang).
        server.createContext("/endless", x -> {
            x.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[64 * 1024];
            try {
                for (int i = 0; i < 8192; i++) {
                    x.getResponseBody().write(chunk);
                    x.getResponseBody().flush();
                    sent.addAndGet(chunk.length);
                }
            } catch (IOException clientHungUp) {
                // expected once the client stops reading
            } finally {
                x.close();
            }
        });
        server.start();
        client = Http.client("http://127.0.0.1:" + server.getAddress().getPort(), 5);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void aBoundedCallStopsReadingAnEndlessResponse() throws Exception {
        Http.Result r = Http.call("test", client.get().uri("/endless"), LIMIT);

        assertThat(r.ok()).isTrue();
        assertThat(r.body()).hasSize(LIMIT + 1); // enough for the caller to see "too large", nothing more buffered
        Thread.sleep(300); // let the server notice the client has gone
        // Without the bound the call would buffer until the 512 MiB cap; with it, only socket buffers' worth is sent.
        assertThat(sent.get()).isLessThan(32L * 1024 * 1024);
    }

    @Test
    void aBodyWithinTheBoundIsReturnedWhole() {
        Http.Result r = Http.call("test", client.get().uri("/finite"), 2 * 1024 * 1024);
        assertThat(r.body()).hasSize(1024 * 1024);
    }

    @Test
    void theUnboundedCallStillReadsTheWholeBodyForSpeechAndLlmCallers() {
        Http.Result r = Http.call("test", client.get().uri("/finite"));
        assertThat(r.body()).hasSize(1024 * 1024);
    }
}
