package net.alpas.crystalplanner.storage;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

public final class AppSettings {
    private static final String PREFS = "crystal_planner_settings";

    private static final String GENERATOR_FILE = "generate_discord_bot_datas.php";
    private static final String EVENTS_FILE = "discord-bot-datas.json";
    private static final String RULES_FILE = "discord-rules.json";
    private static final String GUIDES_FILE = "discord-guides.json";
    private static final String MACROS_FILE = "discord-macros.json";

    public int intervalMinutes = 15;
    public boolean keepScreenOn = false;

    public String topicsChannel = "";
    public String noticesChannel = "";
    public String maintenanceChannel = "";
    public String updatesChannel = "";

    /** Base Web folder containing all Crystal Planner PHP/JSON files. */
    public String webFolderUrl = "";

    public boolean linkshellEnabled = false;
    public String linkshellChannel = "";
    public int jsonReadDelaySeconds = 3;

    public boolean rulesEnabled = false;
    public String rulesChannel = "";

    public boolean guidesEnabled = false;
    public String guidesChannel = "";

    public boolean macrosEnabled = false;
    public String macrosChannel = "";

    public static AppSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AppSettings s = new AppSettings();
        s.intervalMinutes = Math.max(15, p.getInt("intervalMinutes", 15));
        s.keepScreenOn = p.getBoolean("keepScreenOn", false);
        s.topicsChannel = p.getString("topicsChannel", "");
        s.noticesChannel = p.getString("noticesChannel", "");
        s.maintenanceChannel = p.getString("maintenanceChannel", "");
        s.updatesChannel = p.getString("updatesChannel", "");

        String storedFolder = p.getString("webFolderUrl", "");
        if (clean(storedFolder).isEmpty()) {
            // Automatic migration from Crystal Planner Android 1.0.7 and earlier.
            storedFolder = inferFolderFromLegacyUrls(
                    p.getString("generatorUrl", ""),
                    p.getString("dataJsonUrl", ""),
                    p.getString("rulesJsonUrl", ""),
                    p.getString("guidesJsonUrl", ""),
                    p.getString("macrosJsonUrl", "")
            );
            if (!clean(storedFolder).isEmpty()) {
                p.edit().putString("webFolderUrl", normalizeFolder(storedFolder)).apply();
            }
        }
        s.webFolderUrl = normalizeFolder(storedFolder);

        s.linkshellEnabled = p.getBoolean("linkshellEnabled", false);
        s.linkshellChannel = p.getString("linkshellChannel", "");
        s.jsonReadDelaySeconds = Math.max(0, p.getInt("jsonReadDelaySeconds", 3));

        s.rulesEnabled = p.getBoolean("rulesEnabled", false);
        s.rulesChannel = p.getString("rulesChannel", "");

        s.guidesEnabled = p.getBoolean("guidesEnabled", false);
        s.guidesChannel = p.getString("guidesChannel", "");

        s.macrosEnabled = p.getBoolean("macrosEnabled", false);
        s.macrosChannel = p.getString("macrosChannel", "");
        return s;
    }

    public void save(Context context) {
        webFolderUrl = normalizeFolder(webFolderUrl);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt("intervalMinutes", Math.max(15, intervalMinutes))
                .putBoolean("keepScreenOn", keepScreenOn)
                .putString("topicsChannel", clean(topicsChannel))
                .putString("noticesChannel", clean(noticesChannel))
                .putString("maintenanceChannel", clean(maintenanceChannel))
                .putString("updatesChannel", clean(updatesChannel))
                .putString("webFolderUrl", webFolderUrl)
                .putBoolean("linkshellEnabled", linkshellEnabled)
                .putString("linkshellChannel", clean(linkshellChannel))
                .putInt("jsonReadDelaySeconds", Math.max(0, jsonReadDelaySeconds))
                .putBoolean("rulesEnabled", rulesEnabled)
                .putString("rulesChannel", clean(rulesChannel))
                .putBoolean("guidesEnabled", guidesEnabled)
                .putString("guidesChannel", clean(guidesChannel))
                .putBoolean("macrosEnabled", macrosEnabled)
                .putString("macrosChannel", clean(macrosChannel))
                .remove("gatewayPresenceEnabled")
                .remove("presenceStatus")
                .remove("presenceActivityType")
                .remove("presenceMessage")
                .remove("generatorUrl")
                .remove("dataJsonUrl")
                .remove("rulesJsonUrl")
                .remove("guidesJsonUrl")
                .remove("macrosJsonUrl")
                .apply();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("intervalMinutes", Math.max(15, intervalMinutes));
        json.put("keepScreenOn", keepScreenOn);
        json.put("topicsChannel", clean(topicsChannel));
        json.put("noticesChannel", clean(noticesChannel));
        json.put("maintenanceChannel", clean(maintenanceChannel));
        json.put("updatesChannel", clean(updatesChannel));
        json.put("webFolderUrl", normalizeFolder(webFolderUrl));

        json.put("linkshellEnabled", linkshellEnabled);
        json.put("linkshellChannel", clean(linkshellChannel));
        json.put("jsonReadDelaySeconds", Math.max(0, jsonReadDelaySeconds));

        json.put("rulesEnabled", rulesEnabled);
        json.put("rulesChannel", clean(rulesChannel));

        json.put("guidesEnabled", guidesEnabled);
        json.put("guidesChannel", clean(guidesChannel));

        json.put("macrosEnabled", macrosEnabled);
        json.put("macrosChannel", clean(macrosChannel));
        return json;
    }

    public static AppSettings fromJson(JSONObject json) {
        AppSettings s = new AppSettings();
        s.intervalMinutes = Math.max(15, json.optInt("intervalMinutes", 15));
        s.keepScreenOn = json.optBoolean("keepScreenOn", false);
        s.topicsChannel = clean(json.optString("topicsChannel", ""));
        s.noticesChannel = clean(json.optString("noticesChannel", ""));
        s.maintenanceChannel = clean(json.optString("maintenanceChannel", ""));
        s.updatesChannel = clean(json.optString("updatesChannel", ""));

        String folder = clean(json.optString("webFolderUrl", ""));
        if (folder.isEmpty()) {
            // Backward compatibility with backups exported by version 1.0.7 and earlier.
            folder = inferFolderFromLegacyUrls(
                    json.optString("generatorUrl", ""),
                    json.optString("dataJsonUrl", ""),
                    json.optString("rulesJsonUrl", ""),
                    json.optString("guidesJsonUrl", ""),
                    json.optString("macrosJsonUrl", "")
            );
        }
        s.webFolderUrl = normalizeFolder(folder);

        s.linkshellEnabled = json.optBoolean("linkshellEnabled", false);
        s.linkshellChannel = clean(json.optString("linkshellChannel", ""));
        s.jsonReadDelaySeconds = Math.max(0, json.optInt("jsonReadDelaySeconds", 3));

        s.rulesEnabled = json.optBoolean("rulesEnabled", false);
        s.rulesChannel = clean(json.optString("rulesChannel", ""));

        s.guidesEnabled = json.optBoolean("guidesEnabled", false);
        s.guidesChannel = clean(json.optString("guidesChannel", ""));

        s.macrosEnabled = json.optBoolean("macrosEnabled", false);
        s.macrosChannel = clean(json.optString("macrosChannel", ""));
        return s;
    }

    public String generatorUrl() {
        return joinWebFile(GENERATOR_FILE);
    }

    public String eventsJsonUrl() {
        return joinWebFile(EVENTS_FILE);
    }

    public String rulesJsonUrl() {
        return joinWebFile(RULES_FILE);
    }

    public String guidesJsonUrl() {
        return joinWebFile(GUIDES_FILE);
    }

    public String macrosJsonUrl() {
        return joinWebFile(MACROS_FILE);
    }

    private String joinWebFile(String filename) {
        String folder = normalizeFolder(webFolderUrl);
        return folder.isEmpty() ? "" : folder + "/" + filename;
    }

    private static String inferFolderFromLegacyUrls(String... urls) {
        if (urls == null) return "";
        for (String url : urls) {
            String cleanUrl = clean(url);
            if (cleanUrl.isEmpty()) continue;
            int query = cleanUrl.indexOf('?');
            if (query >= 0) cleanUrl = cleanUrl.substring(0, query);
            int fragment = cleanUrl.indexOf('#');
            if (fragment >= 0) cleanUrl = cleanUrl.substring(0, fragment);
            int slash = cleanUrl.lastIndexOf('/');
            if (slash > "https://".length()) {
                return cleanUrl.substring(0, slash);
            }
        }
        return "";
    }

    private static String normalizeFolder(String value) {
        String normalized = clean(value);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
