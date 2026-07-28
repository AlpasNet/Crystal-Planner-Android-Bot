package net.alpas.crystalplanner.storage;

import org.json.JSONException;
import org.json.JSONObject;

public final class SettingsBackup {
    public static final String FORMAT = "crystal-planner-settings";
    public static final int VERSION = 4;

    private SettingsBackup() {
    }

    public static String create(AppSettings settings, JSONObject history) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("discordTokenIncluded", false);
        root.put("settings", settings.toJson());
        boolean historyIncluded = history != null;
        root.put("historyIncluded", historyIncluded);
        if (historyIncluded) root.put("history", history);
        return root.toString(2);
    }

    public static String create(AppSettings settings) throws JSONException {
        return create(settings, null);
    }

    public static RestoreData parse(String content) throws JSONException {
        JSONObject root = new JSONObject(content);
        if (!FORMAT.equals(root.optString("format", ""))) {
            throw new JSONException("Unsupported backup format");
        }
        int version = root.optInt("version", 0);
        if (version < 1 || version > VERSION) {
            throw new JSONException("Unsupported backup version: " + version);
        }
        JSONObject settings = root.optJSONObject("settings");
        if (settings == null) {
            throw new JSONException("Missing settings object");
        }
        JSONObject history = root.optJSONObject("history");
        boolean historyIncluded = root.optBoolean("historyIncluded", history != null)
                && history != null;
        return new RestoreData(
                AppSettings.fromJson(settings),
                historyIncluded ? history : null
        );
    }

    public static final class RestoreData {
        public final AppSettings settings;
        public final JSONObject history;

        RestoreData(AppSettings settings, JSONObject history) {
            this.settings = settings;
            this.history = history;
        }

        public boolean hasHistory() {
            return history != null;
        }
    }
}
