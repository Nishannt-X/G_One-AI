package com.example.g_one.room;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One notification for the room. {@code audience} null means both phones. {@code audioKey} names a stored clip that
 * only {@code audioFor} may fetch; only that participant's view carries the download URL.
 */
public record RoomEvent(String type, Role audience, Map<String, Object> data, Role audioFor, String audioKey) {

    public static RoomEvent of(String type, Role audience, Map<String, Object> data) {
        return new RoomEvent(type, audience, data, null, null);
    }

    /** The JSON-ready view for one recipient, or null if that recipient must not see this event. */
    public Map<String, Object> viewFor(String roomId, Role recipient) {
        if (audience != null && audience != recipient) return null;
        Map<String, Object> view = new LinkedHashMap<>(data);
        view.put("type", type);
        if (audioKey != null && audioFor == recipient) {
            view.put("audioUrl", "/api/rooms/" + roomId + "/audio/" + audioKey);
        }
        return view;
    }
}
