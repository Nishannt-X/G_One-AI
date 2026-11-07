package com.example.g_one.agent.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** A function the model may call: name, description and a JSON Schema object for its parameters. */
public record ToolSpec(String name, String description, JsonNode parameters) {}
