package com.example.g_one.memory;

import com.example.g_one.core.InMemoryMemoryStore;
import com.example.g_one.core.MemoryStore;
import com.example.g_one.speech.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;

/** Wires the farmer memory: the store chosen by {@code g-one.memory.store}, the gist writer and the optional seed. */
@Configuration
public class MemoryConfig {
    private static final Logger log = LoggerFactory.getLogger(MemoryConfig.class);
    private static final String CLASSPATH = "classpath:";

    /** Spring closes the SQLite store on shutdown (inferred destroy method: it is AutoCloseable). */
    @Bean
    MemoryStore memoryStore(MemoryProperties props) {
        if (props.store().equals("in-memory")) {
            log.info("Farmer memory: in-memory store (lost on restart)");
            return new InMemoryMemoryStore();
        }
        Path dir = Path.of(props.dataDir()).toAbsolutePath().normalize();
        log.info("Farmer memory: SQLite at {}", dir.resolve(SqliteMemoryStore.FILE_NAME));
        return SqliteMemoryStore.open(dir);
    }

    @Bean
    CallSummarizer callSummarizer(LlmClient llm, ObjectMapper json) {
        return new LlmCallSummarizer(llm, json);
    }

    @Bean
    GistWriter gistWriter(MemoryStore store, CallSummarizer summarizer) {
        return new GistWriter(store, summarizer);
    }

    /** Loads the synthetic seed farmer at startup when {@code G_ONE_MEMORY_SEED_FILE} is set. */
    @Bean
    ApplicationRunner memorySeedRunner(MemoryProperties props, MemoryStore store, ObjectMapper json, Environment env) {
        return args -> {
            if (props.seedFile().isEmpty()) {
                return;
            }
            MemorySeeder.Result r = MemorySeeder.seed(store, readSeed(props.seedFile(), json), env::getProperty,
                    props.salt());
            switch (r.status()) {
                case SEEDED -> log.info("Farmer memory seeded from {}: {} facts, {} gists", props.seedFile(),
                        r.facts(), r.gists());
                case SKIPPED_NO_PHONE -> log.warn("Farmer memory seed skipped: the seed's phone env var is not set");
                case SKIPPED_NO_HMAC_KEY -> log.warn("Farmer memory seed skipped: G_ONE_MEMORY_HMAC_KEY is not set");
            }
        };
    }

    static JsonNode readSeed(String location, ObjectMapper json) {
        try {
            if (location.startsWith(CLASSPATH)) {
                try (InputStream in = new ClassPathResource(location.substring(CLASSPATH.length())).getInputStream()) {
                    return json.readTree(in);
                }
            }
            return json.readTree(Files.readAllBytes(Path.of(location)));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the memory seed file " + location, e);
        }
    }
}
