package com.example.g_one.speech;

import com.example.g_one.GOneProperties;
import com.example.g_one.core.AudioFrame;
import com.example.g_one.core.Language;
import com.example.g_one.core.LegId;
import com.example.g_one.core.LegRole;
import com.example.g_one.core.SpeechToText;
import com.example.g_one.core.SttListener;
import com.example.g_one.core.SttSession;
import com.example.g_one.core.TextToSpeech;
import com.example.g_one.core.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manual live probe, run by a human with the key in the environment. It speaks one synthetic phrase through Timbre,
 * listens to it through Prisma, uploads one long turn, and prints latencies and pass/fail only: never the transcript
 * and never the key. Usage: {@code SpeechProbe [rest|realtime]} (default rest). It is not run by any test.
 */
public final class SpeechProbe {
    private static final Language HINDI = new Language("hi-IN", "Hindi", "Nalini");
    private static final String PHRASE = "नमस्ते, आज गेहूं का भाव क्या है";

    private SpeechProbe() {}

    public static void main(String[] args) throws Exception {
        String key = System.getenv("GNANI_API_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("GNANI_API_KEY is not set. Load .env.local first.");
            System.exit(2);
        }
        String mode = args.length > 0 ? args[0] : "rest";
        boolean realtime = mode.equals("realtime");
        GOneProperties.Gnani cfg = new GOneProperties.Gnani(env("GNANI_BASE_URL", "https://api.vachana.ai"), key,
                env("GNANI_STT_MODEL", ""), env("GNANI_TTS_MODEL", "timbre-v2.5"), 30);
        ObjectMapper json = new ObjectMapper();
        GOneProperties props = new GOneProperties(List.of("hi-IN:Hindi:Nalini", "en-IN:English:Kaveri"), "", null, cfg,
                new GOneProperties.Llm("", "", "", 30));
        PrismClient prism = new PrismClient(props, json);
        System.out.println("mode=" + mode);

        TextToSpeech tts = realtime ? new TimbreRealtimeTts(cfg, json) : new TimbreRestTts(new TimbreClient(props));
        SpeechToText stt = realtime ? new PrismRealtimeStt(cfg, json) : new PrismRestStt(prism);

        // 1. Text to speech: time to the first frame, total time, and how much audio came back.
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        long t0 = System.nanoTime();
        long[] first = {-1};
        CompletableFuture<Void> done = tts.speak(PHRASE, HINDI, 8000, f -> {
            if (first[0] < 0) first[0] = System.nanoTime();
            audio.writeBytes(f.pcm16le());
        }).done();
        boolean ttsOk = await(done, 30);
        System.out.println("tts.ok=" + ttsOk);
        if (!ttsOk) System.exit(1);
        System.out.println("tts.first_audio_ms=" + (first[0] - t0) / 1_000_000);
        System.out.println("tts.total_ms=" + (System.nanoTime() - t0) / 1_000_000);
        System.out.println("tts.audio_ms=" + audio.size() / 2 * 1000L / 8000);

        // 2. Speech to text: feed that audio in real time, between silences, and wait for the turn.
        long[] speechStart = {-1};
        long[] finalAt = {-1};
        CompletableFuture<Turn> turn = new CompletableFuture<>();
        SttSession session = stt.open(LegId.random(), LegRole.FARMER, HINDI, new SttListener() {
            @Override
            public void onSpeechStart() {
                speechStart[0] = System.nanoTime();
            }

            @Override
            public void onFinalTurn(Turn t) {
                finalAt[0] = System.nanoTime();
                turn.complete(t);
            }

            @Override
            public void onError(Exception error) {
                System.out.println("stt.error=" + error.getClass().getSimpleName());
            }
        });
        long fedAt = System.nanoTime();
        byte[] all = join(silence(300), audio.toByteArray());
        feedRealTime(session, all);
        long audioEnd = System.nanoTime();
        feedRealTime(session, silence(1500));
        boolean sttOk = await(turn, 20);
        System.out.println("stt.ok=" + sttOk);
        if (sttOk) {
            Turn t = turn.get();
            System.out.println("stt.speech_start_seen=" + (speechStart[0] > 0));
            System.out.println("stt.speech_end_to_final_ms=" + (finalAt[0] - audioEnd) / 1_000_000);
            System.out.println("stt.transcript_nonempty=" + !t.text().isBlank());
            System.out.println("stt.fed_to_final_ms=" + (finalAt[0] - fedAt) / 1_000_000);
        }
        session.close();

        // 3. Long uploads: the old build failed above about 48 KB over TLS (SSLException bad_record_mac). The same
        // phrase repeated 2, 3 and 4 times makes turns of growing size.
        if (!realtime) {
            byte[] one = audio.toByteArray();
            for (int repeats : new int[] {2, 3, 4}) {
                ByteArrayOutputStream longTurn = new ByteArrayOutputStream();
                for (int i = 0; i < repeats; i++) longTurn.writeBytes(one);
                byte[] wav = Wav.wrap(longTurn.toByteArray(), 8000);
                long u0 = System.nanoTime();
                try {
                    prism.transcribe(wav, HINDI.code());
                    System.out.println("upload.ok=true bytes=" + wav.length + " ms=" + (System.nanoTime() - u0) / 1_000_000);
                } catch (UpstreamException e) {
                    System.out.println("upload.ok=false bytes=" + wav.length + " stage=" + e.stage() + " ms="
                            + (System.nanoTime() - u0) / 1_000_000);
                }
            }
        }
        System.exit(ttsOk && sttOk ? 0 : 1);
    }

    private static void feedRealTime(SttSession session, byte[] pcm) throws InterruptedException {
        long start = System.nanoTime();
        int frames = 0;
        for (AudioFrame f : frames(pcm)) {
            session.feed(f);
            frames++;
            long due = start + frames * 20_000_000L;
            long wait = due - System.nanoTime();
            if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait);
        }
    }

    private static List<AudioFrame> frames(byte[] pcm) {
        int bytes = 8000 * 20 / 1000 * 2;
        java.util.ArrayList<AudioFrame> out = new java.util.ArrayList<>();
        for (int pos = 0, i = 0; pos + bytes <= pcm.length; pos += bytes, i++) {
            out.add(new AudioFrame(java.util.Arrays.copyOfRange(pcm, pos, pos + bytes), 8000, i * 20L));
        }
        return out;
    }

    private static boolean await(CompletableFuture<?> f, int seconds) {
        try {
            f.get(seconds, TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] join(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static byte[] silence(int ms) {
        return new byte[8000 * ms / 1000 * 2];
    }
}
