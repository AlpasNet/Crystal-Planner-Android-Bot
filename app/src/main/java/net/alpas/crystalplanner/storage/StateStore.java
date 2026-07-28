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
    public static final String KEY_LAST_RUN = "last_run";
    public static final String KEY_GATEWAY_STATE = "gateway_state";
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


    public void setGatewayState(String state, String detail) {
        JSONObject object = new JSONObject();
        try {
            object.put("timestamp", System.currentTimeMillis());
            object.put("state", state == null ? "stopped" : state);
            object.put("detail", detail == null ? "" : detail);
        } catch (JSONException ignored) {
        }
        prefs.edit().putString(KEY_GATEWAY_STATE, object.toString()).apply();
    }

    public JSONObject getGatewayState() {
        try {
            return new JSONObject(prefs.getString(KEY_GATEWAY_STATE, "{}"));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    /**
     * Exports only duplicate-prevention history: Lodestone seen IDs and board hashes.
     * Logs, scheduling state and execution status are intentionally excluded.
     */
    public JSONObject exportHistory() throws JSONException {
        JSONObject history = new JSONObject();
        history.put("version", 1);
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
        return history;
    }

    /**
     * Replaces duplicate-prevention history with values from a trusted backup.
     */
    public void importHistory(JSONObject history) throws JSONException {
        if (history == null) return;
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith("seen_") || key.startsWith("board_hash_")) {
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
        editor.apply();
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
