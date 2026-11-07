package com.example.g_one.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.fakes.FakeLeg;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Behaviour of the small concrete pieces in core: audio frames, the leg registry and the console bus. */
class CoreContractsTest {

    @Test
    void aTwentyMillisecondTelephonyFrameIs160Samples() {
        AudioFrame f = AudioFrame.silence(8000, 20, 40);

        assertThat(f.samples()).isEqualTo(160);
        assertThat(f.pcm16le()).hasSize(320);
        assertThat(f.durationMs()).isEqualTo(20);
        assertThat(f).isEqualTo(AudioFrame.silence(8000, 20, 40));
    }

    @Test
    void anAudioFrameRejectsHalfSamplesAndBadRates() {
        assertThatThrownBy(() -> new AudioFrame(new byte[3], 8000, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioFrame(new byte[2], 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AudioFrame(new byte[2], 8000, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void legRolesKnowTheOtherSideAndTheirWireName() {
        assertThat(LegRole.FARMER.other()).isEqualTo(LegRole.BUYER);
        assertThat(LegRole.BUYER.other()).isEqualTo(LegRole.FARMER);
        assertThat(LegRole.BUYER.wire()).isEqualTo("buyer");
        assertThat(LegSource.SIMULATOR.wire()).isEqualTo("simulator");
    }

    @Test
    void theRegistryAnnouncesNewLegsFindsThemPerCallAndForgetsEndedOnes() {
        LegRegistry registry = new LegRegistry();
        List<Leg> announced = new ArrayList<>();
        registry.onLegAdded(announced::add);
        CallId call = CallId.random();
        FakeLeg farmer = FakeLeg.farmer(call);
        FakeLeg buyer = FakeLeg.buyer(call);
        FakeLeg elsewhere = FakeLeg.farmer(CallId.random());

        registry.register(buyer);
        registry.register(farmer);
        registry.register(elsewhere);

        assertThat(announced).containsExactly(buyer, farmer, elsewhere);
        assertThat(registry.legs(call)).containsExactly(farmer, buyer);
        assertThat(registry.find(call, LegRole.BUYER)).contains(buyer);
        assertThat(registry.find(farmer.id())).contains(farmer);
        assertThat(registry.activeCalls()).containsExactlyInAnyOrder(call, elsewhere.callId());
        assertThatThrownBy(() -> registry.register(farmer)).isInstanceOf(IllegalStateException.class);

        buyer.remoteHangup();

        assertThat(registry.find(call, LegRole.BUYER)).isEmpty();
        assertThat(registry.legs(call)).containsExactly(farmer);
    }

    @Test
    void aFailingLegListenerDoesNotStopTheOthers() {
        LegRegistry registry = new LegRegistry();
        List<Leg> announced = new ArrayList<>();
        registry.onLegAdded(l -> {
            throw new IllegalStateException("boom");
        });
        registry.onLegAdded(announced::add);
        FakeLeg farmer = FakeLeg.farmer(CallId.random());

        registry.register(farmer);

        assertThat(announced).containsExactly(farmer);
    }

    @Test
    void theBusDeliversToEverySubscriberUntilItUnsubscribes() {
        ConsoleBus bus = new ConsoleBus();
        List<ConsoleEvent> a = new ArrayList<>();
        List<ConsoleEvent> b = new ArrayList<>();
        bus.subscribe(e -> {
            throw new IllegalStateException("a broken subscriber");
        });
        ConsoleBus.Subscription subA = bus.subscribe(a::add);
        bus.subscribe(b::add);
        ConsoleEvent started = new ConsoleEvent.CallStarted(CallId.of("call-1"), 1, "hi-IN");
        ConsoleEvent ended = new ConsoleEvent.CallEnded(CallId.of("call-1"), 2, 1, "hangup");

        bus.publish(started);
        subA.close();
        subA.close();
        bus.publish(ended);

        assertThat(a).containsExactly(started);
        assertThat(b).containsExactly(started, ended);
    }

    @Test
    void eventFactoriesCopyTheirSource() {
        CallId call = CallId.of("call-1");
        FakeLeg farmer = FakeLeg.farmer(call);
        Turn turn = new Turn(farmer.id(), LegRole.FARMER, FakeLeg.HINDI, "namaste", 10, 20);

        assertThat(ConsoleEvent.LegJoined.of(farmer, 5))
                .isEqualTo(new ConsoleEvent.LegJoined(call, 5, farmer.id(), LegRole.FARMER, LegSource.TWILIO, "hi-IN"));
        assertThat(ConsoleEvent.TurnHeard.of(call, turn, 30)).isEqualTo(
                new ConsoleEvent.TurnHeard(call, 30, farmer.id(), LegRole.FARMER, "hi-IN", "namaste", 10, 20));
        assertThat(ConsoleEvent.MemoryLoaded.of(call, FarmerMemory.empty(FakeLeg.FARMER_KEY), 7).returning())
                .isFalse();
    }

    @Test
    void aToolContextExposesTheCallersKey() {
        CallId call = CallId.of("call-1");
        FakeLeg farmer = FakeLeg.farmer(call);
        CallContext ctx = new CallContext(call, farmer, farmer.language(), FarmerMemory.empty(farmer.callerKey()));

        assertThat(ctx.callerKey()).isEqualTo(FakeLeg.FARMER_KEY);
        assertThat(ToolResult.ok("x").ok()).isTrue();
        assertThat(ToolResult.error("price not available").ok()).isFalse();
    }
}
