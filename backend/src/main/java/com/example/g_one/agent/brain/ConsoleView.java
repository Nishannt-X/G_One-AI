package com.example.g_one.agent.brain;

import com.example.g_one.agent.tools.Redact;
import com.example.g_one.agent.tools.memory.ConfirmNameTool;
import com.example.g_one.agent.tools.memory.MemoryGate;
import com.example.g_one.core.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;

/**
 * What the console may show about one tool call. The model's arguments and the tool's result are written for the model
 * and can hold what a caller said, so the console gets a redacted copy:
 *
 * <ul>
 *   <li>a tool that does not exist is shown as {@value #UNKNOWN_TOOL}, and its arguments are not shown;</li>
 *   <li>a rejected call shows no arguments at all (a refused phone number or OTP must not reach the console);</li>
 *   <li>accepted arguments and every result have long digit runs hidden ({@link Redact#digits}) and are capped;</li>
 *   <li>{@code confirm_name} shows only {@value ConfirmNameTool#CONFIRMED} or {@value ConfirmNameTool#DENIED}.</li>
 * </ul>
 *
 * The model itself still receives the real result; only the published copy is redacted.
 */
final class ConsoleView {
    static final String UNKNOWN_TOOL = "unknown_tool";
    static final int MAX_TEXT_CHARS = 120;
    static final int MAX_RESULT_CHARS = 400;
    private static final int MAX_DEPTH = 3;

    private ConsoleView() {}

    static String tool(String name, boolean known) {
        return known ? name : UNKNOWN_TOOL;
    }

    static JsonNode args(ObjectMapper json, JsonNode args, boolean known, ToolResult result) {
        ObjectNode out = json.createObjectNode();
        if (!known || !result.ok() || args == null || !args.isObject()) return out;
        copy(args, out, 0);
        return out;
    }

    static String result(String tool, ToolResult result, MemoryGate.State after) {
        if (ConfirmNameTool.NAME.equals(tool)) {
            return result.ok() && after == MemoryGate.State.CONFIRMED ? ConfirmNameTool.CONFIRMED : ConfirmNameTool.DENIED;
        }
        return cap(Redact.digits(result.content()), MAX_RESULT_CHARS);
    }

    private static void copy(JsonNode from, ObjectNode to, int depth) {
        Iterator<Map.Entry<String, JsonNode>> it = from.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String key = cap(Redact.digits(e.getKey()), 40);
            JsonNode v = e.getValue();
            if (v.isTextual()) {
                to.put(key, cap(Redact.digits(v.asText()), MAX_TEXT_CHARS));
            } else if (v.isNumber()) {
                // A bare number can be a phone or account number written as a JSON number.
                to.put(key, Redact.digits(v.asText()));
            } else if (v.isBoolean() || v.isNull()) {
                to.set(key, v);
            } else if (v.isObject() && depth < MAX_DEPTH) {
                copy(v, to.putObject(key), depth + 1);
            } else {
                to.put(key, "[omitted]");
            }
        }
    }

    private static String cap(String s, int max) {
        if (s.codePointCount(0, s.length()) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max)) + "…";
    }
}
