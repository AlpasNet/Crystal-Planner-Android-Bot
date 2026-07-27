package net.alpas.crystalplanner.storage;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppSettings {
    private static final String PREFS = "crystal_planner_settings";

    public int intervalMinutes = 15;
    public String topicsChannel = "";
    public String noticesChannel = "";
    public String maintenanceChannel = "";
    public String updatesChannel = "";

    public boolean linkshellEnabled = false;
    public String linkshellChannel = "";
    public String generatorUrl = "";
    public String dataJsonUrl = "";
    public int jsonReadDelaySeconds = 3;

    public boolean rulesEnabled = false;
    public String rulesChannel = "";
    public String rulesJsonUrl = "";

    public boolean guidesEnabled = false;
    public String guidesChannel = "";
    public String guidesJsonUrl = "";

    public static AppSettings load(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AppSettings s = new AppSettings();
        s.intervalMinutes = Math.max(15, p.getInt("intervalMinutes", 15));
        s.topicsChannel = p.getString("topicsChannel", "");
        s.noticesChannel = p.getString("noticesChannel", "");
        s.maintenanceChannel = p.getString("maintenanceChannel", "");
        s.updatesChannel = p.getString("updatesChannel", "");

        s.linkshellEnabled = p.getBoolean("linkshellEnabled", false);
        s.linkshellChannel = p.getString("linkshellChannel", "");
        s.generatorUrl = p.getString("generatorUrl", "");
        s.dataJsonUrl = p.getString("dataJsonUrl", "");
        s.jsonReadDelaySeconds = Math.max(0, p.getInt("jsonReadDelaySeconds", 3));

        s.rulesEnabled = p.getBoolean("rulesEnabled", false);
        s.rulesChannel = p.getString("rulesChannel", "");
        s.rulesJsonUrl = p.getString("rulesJsonUrl", "");

        s.guidesEnabled = p.getBoolean("guidesEnabled", false);
        s.guidesChannel = p.getString("guidesChannel", "");
        s.guidesJsonUrl = p.getString("guidesJsonUrl", "");
        return s;
    }

    public void save(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt("intervalMinutes", Math.max(15, intervalMinutes))
                .putString("topicsChannel", clean(topicsChannel))
                .putString("noticesChannel", clean(noticesChannel))
                .putString("maintenanceChannel", clean(maintenanceChannel))
                .putString("updatesChannel", clean(updatesChannel))
                .putBoolean("linkshellEnabled", linkshellEnabled)
                .putString("linkshellChannel", clean(linkshellChannel))
                .putString("generatorUrl", clean(generatorUrl))
                .putString("dataJsonUrl", clean(dataJsonUrl))
                .putInt("jsonReadDelaySeconds", Math.max(0, jsonReadDelaySeconds))
                .putBoolean("rulesEnabled", rulesEnabled)
                .putString("rulesChannel", clean(rulesChannel))
                .putString("rulesJsonUrl", clean(rulesJsonUrl))
                .putBoolean("guidesEnabled", guidesEnabled)
                .putString("guidesChannel", clean(guidesChannel))
                .putString("guidesJsonUrl", clean(guidesJsonUrl))
                .apply();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
