package net.alpas.crystalplanner.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class StateStore {
    private static final String PREFS = "crystal_planner_state";
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
