package com.example.g_one.console;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.g_one.core.CallId;
import com.example.g_one.core.ConsoleBus;
import com.example.g_one.core.ConsoleEvent;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.LegSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/** The console feed end to end on a real port: WebSocket, active-calls list and the dev replay (enabled here). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "g-one.console.replay-enabled=true")
class ConsoleFeedTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    ConsoleBus bus;
    @Autowired
    TestRestTemplate http;
    @LocalServerPort
    int port;

    private BlockingQueue<JsonNode> connect(WebSocketSession[] holder) throws Exception {
        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        WebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                received.add(JSON.readTree(message.getPayload()));
            }
        };
        holder[0] = new StandardWebSocketClient()
                .execute(handler, null, URI.create("ws://localhost:" + port + "/ws/console")).get(5, TimeUnit.SECONDS);
        return received;
    }

    @Test
    void liveEventsArriveAsJsonWithTheTypeDiscriminator() throws Exception {
        WebSocketSession[] ws = new WebSocketSession[1];
        BlockingQueue<JsonNode> received = connect(ws);
        CallId call = CallId.random();
        bus.publish(new ConsoleEvent.CallStarted(call, 1L, "hi-IN"));

        JsonNode first = received.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first.get("type").asText()).isEqualTo("call_started");
        assertThat(first.get("callId").asText()).isEqualTo(call.value());

        bus.publish(new ConsoleEvent.CallEnded(call, 2L, 1L, "hangup"));
        assertThat(received.poll(5, TimeUnit.SECONDS).get("type").asText()).isEqualTo("call_ended");
        ws[0].close(CloseStatus.NORMAL);
    }

    @Test
    void aConsoleOpenedMidCallGetsTheCallSoFarAndActiveCallsListsIt() throws Exception {
        CallId call = CallId.random();
        LegId leg = LegId.random();
        bus.publish(new ConsoleEvent.CallStarted(call, 10L, "hi-IN"));
        bus.publish(new ConsoleEvent.LegJoined(call, 11L, leg, LegRole.FARMER, LegSource.SIMULATOR, "hi-IN"));

        WebSocketSession[] ws = new WebSocketSession[1];
        BlockingQueue<JsonNode> received = connect(ws);
        assertThat(received.poll(5, TimeUnit.SECONDS).get("type").asText()).isEqualTo("call_started");
        assertThat(received.poll(5, TimeUnit.SECONDS).get("type").asText()).isEqualTo("leg_joined");

        JsonNode active = JSON.readTree(http.getForObject("/api/calls/active", String.class));
        JsonNode ours = null;
        for (JsonNode c : active) if (c.get("callId").asText().equals(call.value())) ours = c;
        assertThat(ours).isNotNull();
        assertThat(ours.get("language").asText()).isEqualTo("hi-IN");
        assertThat(ours.get("legs")).hasSize(1);
        assertThat(ours.get("legs").get(0).get("role").asText()).isEqualTo("farmer");

        bus.publish(new ConsoleEvent.CallEnded(call, 12L, 2L, "hangup"));
        JsonNode after = JSON.readTree(http.getForObject("/api/calls/active", String.class));
        for (JsonNode c : after) assertThat(c.get("callId").asText()).isNotEqualTo(call.value());
        ws[0].close(CloseStatus.NORMAL);
    }

    @Test
    void replayPlaysAWholeCallThatMatchesTheContract() throws Exception {
        WebSocketSession[] ws = new WebSocketSession[1];
        BlockingQueue<JsonNode> received = connect(ws);

        assertThat(http.postForEntity("/api/dev/console/replay?speed=1000", null, String.class)
                .getStatusCode().value()).isEqualTo(202);

        List<String> types = new java.util.ArrayList<>();
        JsonNode event;
        do {
            event = received.poll(10, TimeUnit.SECONDS);
            assertThat(event).as("the replay ends with call_ended").isNotNull();
            types.add(event.get("type").asText());
        } while (!event.get("type").asText().equals("call_ended"));

        assertThat(types).startsWith("call_started", "leg_joined", "memory_loaded");
        assertThat(types).contains("turn_heard", "turn_translated", "assistant_said", "whisper_sent", "tool_called",
                "deal_updated");
        ws[0].close(CloseStatus.NORMAL);
    }
}
