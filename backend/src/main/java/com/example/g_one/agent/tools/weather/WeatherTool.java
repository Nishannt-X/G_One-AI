package com.example.g_one.agent.tools.weather;

import com.example.g_one.agent.tools.ToolArgs;
import com.example.g_one.core.CallContext;
import com.example.g_one.core.Tool;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * {@code get_weather(district)}: the Open-Meteo forecast for today and tomorrow as a short English summary with numbers
 * as words ({@link WeatherSpeech}). An unknown district lists known ones; a failed fetch is an error result the model
 * apologises for.
 */
@Component
public class WeatherTool implements Tool {
    public static final String NAME = "get_weather";
    private static final JsonNode SCHEMA = ToolArgs.schema("""
            {"type": "object",
             "properties": {"district": {"type": "string", "description": "The district as the farmer said it, for example Nashik"}},
             "required": ["district"]}""");

    private final WeatherClient client;
    private final WeatherSpeech speech;

    @Autowired
    public WeatherTool(WeatherClient client) {
        this(client, new WeatherSpeech());
    }

    public WeatherTool(WeatherClient client, WeatherSpeech speech) {
        this.client = client;
        this.speech = speech;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Get the weather forecast for a district for today and tomorrow. Call it whenever the farmer asks "
                + "about weather or rain.";
    }

    @Override
    public JsonNode jsonSchema() {
        return SCHEMA;
    }

    @Override
    public ToolResult invoke(JsonNode args, CallContext ctx) {
        Optional<String> district = ToolArgs.text(args, "district");
        if (district.isEmpty()) return ToolResult.error("district is required.");
        WeatherResult result = client.forecast(district.get());
        String text = speech.spoken(result);
        return result instanceof WeatherResult.Unavailable ? ToolResult.error(text) : ToolResult.ok(text);
    }
}
