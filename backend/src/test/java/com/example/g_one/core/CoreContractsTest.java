package com.example.g_one.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.g_one.fakes.FakeLeg;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
    void aLegThatEndedBeforeRegistrationIsNeitherAnnouncedNorKept() {
        LegRegistry registry = new LegRegistry();
        List<Leg> announced = new ArrayList<>();
        registry.onLegAdded(announced::add);
        FakeLeg gone = FakeLeg.farmer(CallId.random());
        gone.remoteHangup();

        registry.register(gone);

        assertThat(announced).isEmpty();
        assertThat(registry.find(gone.id())).isEmpty();
        assertThat(registry.activeCalls()).isEmpty();
    }

    @Test
    void aListenerSeesTheLegBeforeItsEndEvenWhenItEndsDuringTheAnnouncement() {
        LegRegistry registry = new LegRegistry();
        List<String> seen = new ArrayList<>();
        FakeLeg farmer = FakeLeg.farmer(CallId.random());
        registry.onLegAdded(l -> farmer.remoteHangup());
        registry.onLegAdded(l -> {
            seen.add("added");
            l.onEnded(() -> seen.add("ended"));
        });

        registry.register(farmer);

        assertThat(seen).containsExactly("added", "ended");
        assertThat(registry.find(farmer.id())).isEmpty();
    }

    @Test
    void concurrentRegistrationsAreAnnouncedOneAtATime() throws Exception {
        LegRegistry registry = new LegRegistry();
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        List<Leg> announced = java.util.Collections.synchronizedList(new ArrayList<>());
        registry.onLegAdded(l -> {
            maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            announced.add(l);
            inside.decrementAndGet();
        });
        int legs = 40;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < legs; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    registry.register(FakeLeg.farmer(CallId.random()));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(announced).hasSize(legs);
        assertThat(maxInside.get()).isEqualTo(1);
    }

    @Test
    void aLegRegisteredFromAListenerIsAnnouncedAfterTheCurrentOneWithoutNesting() {
        LegRegistry registry = new LegRegistry();
        List<String> seen = new ArrayList<>();
        AtomicInteger depth = new AtomicInteger();
        AtomicInteger maxDepth = new AtomicInteger();
        CallId call = CallId.random();
        FakeLeg farmer = FakeLeg.farmer(call);
        FakeLeg buyer = FakeLeg.buyer(call);
        FakeLeg third = FakeLeg.buyer(CallId.random());
        registry.onLegAdded(l -> {
            maxDepth.accumulateAndGet(depth.incrementAndGet(), Math::max);
            seen.add("a:" + l.role().wire());
            if (l == farmer) {
                registry.register(buyer);
                registry.register(third);
                seen.add("a:registered");
                assertThat(registry.find(buyer.id())).as("kept at once, announced later").contains(buyer);
            }
            depth.decrementAndGet();
        });
        registry.onLegAdded(l -> {
            maxDepth.accumulateAndGet(depth.incrementAndGet(), Math::max);
            seen.add("b:" + l.role().wire());
            l.onEnded(() -> seen.add("ended:" + l.role().wire()));
            depth.decrementAndGet();
        });

        registry.register(farmer);

        assertThat(seen).containsExactly(
                "a:farmer", "a:registered", "b:farmer", "a:buyer", "b:buyer", "a:buyer", "b:buyer");
        assertThat(maxDepth.get()).as("listeners never run inside each other").isEqualTo(1);

        buyer.remoteHangup();
        assertThat(seen).endsWith("ended:buyer");
    }

    @Test
    void aQueuedLegThatEndsBeforeItsTurnIsNeitherAnnouncedNorKept() {
        LegRegistry registry = new LegRegistry();
        List<Leg> announced = new ArrayList<>();
        CallId call = CallId.random();
        FakeLeg farmer = FakeLeg.farmer(call);
        FakeLeg buyer = FakeLeg.buyer(call);
        registry.onLegAdded(l -> {
            if (l == farmer) {
                registry.register(buyer);
                buyer.remoteHangup();
            }
        });
        registry.onLegAdded(announced::add);

        registry.register(farmer);

        assertThat(announced).containsExactly(farmer);
        assertThat(registry.find(buyer.id())).isEmpty();
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
        assertThatThrownBy(() -> ConsoleEvent.MemoryLoaded.of(call, FarmerMemory.withheld(), 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aToolContextExposesTheCallersKey() {
        CallId call = CallId.of("call-1");
        FakeLeg farmer = FakeLeg.farmer(call);
        CallContext ctx = new CallContext(call, farmer, farmer.language(),
                FarmerMemory.empty(farmer.callerKey().orElseThrow()));

        assertThat(ctx.callerKey()).contains(FakeLeg.FARMER_KEY);
        assertThat(ToolResult.ok("x").ok()).isTrue();
        assertThat(ToolResult.error("price not available").ok()).isFalse();
    }

    @Test
    void aToolContextRejectsMemoryThatIsNotTheFarmerLegs() {
        CallId call = CallId.of("call-1");
        FakeLeg farmer = FakeLeg.farmer(call);
        FakeLeg withheld = FakeLeg.withheldFarmer(call);
        FakeLeg buyer = FakeLeg.buyer(call);

        assertThatThrownBy(() -> new CallContext(call, farmer, farmer.language(), FarmerMemory.empty(FakeLeg.BUYER_KEY)))
                .as("another caller's memory").isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(FakeLeg.BUYER_KEY.value()).hasMessageNotContaining(FakeLeg.FARMER_KEY.value());
        assertThatThrownBy(() -> new CallContext(call, farmer, farmer.language(), FarmerMemory.withheld()))
                .as("known caller with withheld memory").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallContext(call, withheld, withheld.language(),
                FarmerMemory.empty(FakeLeg.FARMER_KEY)))
                .as("withheld caller with keyed memory").isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(FakeLeg.FARMER_KEY.value());
        assertThatThrownBy(() -> new CallContext(CallId.of("call-2"), farmer, farmer.language(),
                FarmerMemory.empty(FakeLeg.FARMER_KEY)))
                .as("leg from another call").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CallContext(call, buyer, buyer.language(), FarmerMemory.empty(FakeLeg.BUYER_KEY)))
                .as("buyer leg as the farmer").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aWithheldCallerHasNoKeyAndMemoryCannotBeFilledIn() {
        CallId call = CallId.of("call-1");
        FakeLeg farmer = FakeLeg.withheldFarmer(call);
        CallContext ctx = new CallContext(call, farmer, farmer.language(), FarmerMemory.withheld());

        assertThat(farmer.callerKey()).isEmpty();
        assertThat(ctx.callerKey()).isEmpty();
        assertThat(ctx.memory().isWithheld()).isTrue();
        assertThat(ctx.memory().isEmpty()).isTrue();
        assertThat(FarmerMemory.empty(FakeLeg.FARMER_KEY).isWithheld()).isFalse();
        ProfileFact fact = new ProfileFact("name", "Suresh", Provenance.said(call, 1));
        assertThatThrownBy(() -> new FarmerMemory(java.util.Optional.empty(), List.of(fact), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
