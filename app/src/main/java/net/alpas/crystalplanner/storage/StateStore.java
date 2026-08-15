package net.alpas.crystalplanner.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StateStore {
    private static final String PREFS = "crystal_planner_state";
    private static final String KEY_EVENT_MESSAGES = "event_board_messages";
    private static final String KEY_EVENT_CHANNEL = "event_board_channel";
    private static final String KEY_EVENT_INITIALIZED = "event_board_initialized";
    public static final String KEY_LAST_RUN = "last_run";
    private final SharedPreferences prefs;

    public StateStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<String> getSeen(String category) {
        String raw = prefs.getString("seen_" + category, "[]");
        List<String> result = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                String value = array.optString(i, "").trim();
                if (!value.isEmpty()) result.add(value);
            }
        } catch (JSONException ignored) {
            // A corrupted local cache is treated as empty.
        }
        return result;
    }

    public void saveSeen(String category, List<String> values) {
        Set<String> unique = new LinkedHashSet<>(values);
        List<String> compact = new ArrayList<>(unique);
        if (compact.size() > 200) {
            compact = compact.subList(compact.size() - 200, compact.size());
        }
        prefs.edit().putString("seen_" + category, new JSONArray(compact).toString()).apply();
    }

    public String getBoardHash(String boardKey) {
        return prefs.getString("board_hash_" + boardKey, "");
    }

    public void setBoardHash(String boardKey, String hash) {
        prefs.edit().putString("board_hash_" + boardKey, hash).apply();
    }

    public JSONObject getEventMessageState() {
        try {
            return new JSONObject(prefs.getString(KEY_EVENT_MESSAGES, "{}"));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    public void setEventMessageState(JSONObject state) {
        prefs.edit().putString(
                KEY_EVENT_MESSAGES,
                state == null ? "{}" : state.toString()
        ).apply();
    }

    public void clearEventMessageState() {
        prefs.edit().remove(KEY_EVENT_MESSAGES).apply();
    }

    public String getEventBoardChannel() {
        return prefs.getString(KEY_EVENT_CHANNEL, "");
    }

    public boolean isEventBoardInitialized() {
        return prefs.getBoolean(KEY_EVENT_INITIALIZED, false);
    }

    public void setEventBoardTracking(String channelId, boolean initialized) {
        prefs.edit()
                .putString(KEY_EVENT_CHANNEL, channelId == null ? "" : channelId.trim())
                .putBoolean(KEY_EVENT_INITIALIZED, initialized)
                .apply();
    }

    public void setLastRun(boolean success, String summary) {
        JSONObject object = new JSONObject();
        try {
            object.put("timestamp", System.currentTimeMillis());
            object.put("success", success);
            object.put("summary", summary == null ? "" : summary);
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_LAST_RUN, object.toString()).apply();
    }

    public JSONObject getLastRun() {
        try {
            return new JSONObject(prefs.getString(KEY_LAST_RUN, "{}"));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    /**
     * Exports duplicate-prevention history plus the per-Event Discord message
     * mapping required to resume targeted Event edits after a restore.
     * Logs, scheduling state and execution status are intentionally excluded.
     */
    public JSONObject exportHistory() throws JSONException {
        JSONObject history = new JSONObject();
        history.put("version", 2);
        JSONObject seen = new JSONObject();
        JSONObject boardHashes = new JSONObject();

        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (!(value instanceof String)) continue;
            if (key.startsWith("seen_")) {
                String category = key.substring("seen_".length());
                try {
                    seen.put(category, new JSONArray((String) value));
                } catch (JSONException ignored) {
                    seen.put(category, new JSONArray());
                }
            } else if (key.startsWith("board_hash_")) {
                String board = key.substring("board_hash_".length());
                boardHashes.put(board, String.valueOf(value));
            }
        }

        history.put("seen", seen);
        history.put("boardHashes", boardHashes);
        history.put("eventBoardChannel", getEventBoardChannel());
        history.put("eventBoardInitialized", isEventBoardInitialized());
        history.put("eventMessages", sanitizeEventMessageState(getEventMessageState()));
        return history;
    }

    /**
     * Replaces duplicate-prevention history with values from a trusted backup.
     * Older backups without Event message tracking remain supported.
     */
    public void importHistory(JSONObject history) throws JSONException {
        if (history == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("seen_")
                    || key.startsWith("board_hash_")
                    || KEY_EVENT_MESSAGES.equals(key)
                    || KEY_EVENT_CHANNEL.equals(key)
                    || KEY_EVENT_INITIALIZED.equals(key)) {
                editor.remove(key);
            }
        }

        JSONObject seen = history.optJSONObject("seen");
        if (seen != null) {
            Iterator<String> keys = seen.keys();
            while (keys.hasNext()) {
                String category = keys.next();
                if (!isSafeHistoryKey(category)) continue;
                JSONArray source = seen.optJSONArray(category);
                if (source == null) continue;
                LinkedHashSet<String> unique = new LinkedHashSet<>();
                for (int i = 0; i < source.length(); i++) {
                    String value = source.optString(i, "").trim();
                    if (!value.isEmpty()) unique.add(value);
                }
                List<String> compact = new ArrayList<>(unique);
                if (compact.size() > 200) {
                    compact = compact.subList(compact.size() - 200, compact.size());
                }
                editor.putString("seen_" + category, new JSONArray(compact).toString());
            }
        }

        JSONObject hashes = history.optJSONObject("boardHashes");
        if (hashes != null) {
            Iterator<String> keys = hashes.keys();
            while (keys.hasNext()) {
                String board = keys.next();
                if (!isSafeHistoryKey(board)) continue;
                String hash = hashes.optString(board, "").trim();
                if (!hash.isEmpty() && hash.length() <= 256) {
                    editor.putString("board_hash_" + board, hash);
                }
            }
        }

        String eventChannel = history.optString("eventBoardChannel", "").trim();
        JSONObject eventMessages = history.optJSONObject("eventMessages");
        boolean eventInitialized = history.optBoolean(
                "eventBoardInitialized",
                eventMessages != null && eventMessages.length() > 0 && !eventChannel.isEmpty()
        );
        if (eventChannel.matches("[0-9]{15,22}")) {
            editor.putString(KEY_EVENT_CHANNEL, eventChannel);
        }
        if (eventMessages != null) {
            editor.putString(KEY_EVENT_MESSAGES, sanitizeEventMessageState(eventMessages).toString());
        }
        editor.putBoolean(KEY_EVENT_INITIALIZED, eventInitialized && !eventChannel.isEmpty());
        editor.apply();
    }

    private static JSONObject sanitizeEventMessageState(JSONObject source) throws JSONException {
        JSONObject result = new JSONObject();
        if (source == null) return result;

        Iterator<String> keys = source.keys();
        int count = 0;
        while (keys.hasNext() && count < 200) {
            String key = keys.next();
            if (key == null || key.length() > 160 || !key.matches("[A-Za-z0-9_-]+")) continue;
            JSONObject record = source.optJSONObject(key);
            if (record == null) continue;

            String messageId = record.optString("message_id", "").trim();
            String hash = record.optString("hash", "").trim();
            String channelId = record.optString("channel_id", "").trim();
            if (!messageId.matches("[0-9]{15,22}")) continue;
            if (hash.length() > 256) hash = "";
            if (!channelId.isEmpty() && !channelId.matches("[0-9]{15,22}")) channelId = "";

            JSONObject clean = new JSONObject();
            clean.put("message_id", messageId);
            clean.put("hash", hash);
            clean.put("crossposted", record.optBoolean("crossposted", false));
            if (!channelId.isEmpty()) clean.put("channel_id", channelId);
            result.put(key, clean);
            count++;
        }
        return result;
    }

    private static boolean isSafeHistoryKey(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener);
    }

    public boolean isScheduled() {
        return prefs.getBoolean("scheduled", false);
    }

    public void setScheduled(boolean scheduled) {
        prefs.edit().putBoolean("scheduled", scheduled).apply();
    }
}
