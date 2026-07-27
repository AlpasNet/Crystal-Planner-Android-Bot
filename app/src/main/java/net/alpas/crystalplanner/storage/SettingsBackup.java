package net.alpas.crystalplanner.storage;

import org.json.JSONException;
import org.json.JSONObject;

public final class SettingsBackup {
    public static final String FORMAT = "crystal-planner-settings";
    public static final int VERSION = 1;

    private SettingsBackup() {
    }

    public static String create(AppSettings settings) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("version", VERSION);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("discordTokenIncluded", false);
        root.put("settings", settings.toJson());
        return root.toString(2);
    }

    public static AppSettings parse(String content) throws JSONException {
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
        return AppSettings.fromJson(settings);
    }
}
