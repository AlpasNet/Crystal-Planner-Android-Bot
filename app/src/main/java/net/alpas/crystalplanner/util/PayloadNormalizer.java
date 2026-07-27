package net.alpas.crystalplanner.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

public final class PayloadNormalizer {
    private PayloadNormalizer() {
    }

    public static JSONObject normalizeMessage(JSONObject raw) {
        try {
            return normalizeMessageChecked(raw);
        } catch (JSONException error) {
            throw new IllegalStateException("Unable to normalize Discord payload", error);
        }
    }

    private static JSONObject normalizeMessageChecked(JSONObject raw) throws JSONException {
        JSONObject message = new JSONObject();
        putIfNotBlank(message, "content", truncate(raw.optString("content", ""), 2000));

        JSONArray sourceEmbeds = raw.optJSONArray("embeds");
        JSONArray embeds = new JSONArray();
        if (sourceEmbeds != null) {
            for (int i = 0; i < Math.min(10, sourceEmbeds.length()); i++) {
                JSONObject embed = sourceEmbeds.optJSONObject(i);
                if (embed != null) embeds.put(normalizeEmbed(embed));
            }
        }
        if (embeds.length() > 0) message.put("embeds", embeds);

        JSONObject allowedMentions = new JSONObject();
        allowedMentions.put("parse", new JSONArray());
        message.put("allowed_mentions", allowedMentions);
        return message;
    }

    private static JSONObject normalizeEmbed(JSONObject raw) throws JSONException {
        JSONObject embed = new JSONObject();
        putIfNotBlank(embed, "title", truncate(raw.optString("title", ""), 256));
        putIfNotBlank(embed, "description", truncate(raw.optString("description", ""), 4096));
        putIfNotBlank(embed, "url", raw.optString("url", ""));
        if (raw.has("color")) embed.put("color", raw.optInt("color", 0));
        if (raw.has("timestamp")) putIfNotBlank(embed, "timestamp", raw.optString("timestamp", ""));

        copyNamedObject(raw, embed, "author", 256, true);
        copyUrlObject(raw, embed, "image");
        copyUrlObject(raw, embed, "thumbnail");
        copyNamedObject(raw, embed, "footer", 2048, false);

        JSONArray sourceFields = raw.optJSONArray("fields");
        if (sourceFields != null) {
            JSONArray fields = new JSONArray();
            for (int i = 0; i < Math.min(25, sourceFields.length()); i++) {
                JSONObject source = sourceFields.optJSONObject(i);
                if (source == null) continue;
                JSONObject field = new JSONObject();
                field.put("name", fallback(truncate(source.optString("name", ""), 256), "Field"));
                field.put("value", fallback(truncate(source.optString("value", ""), 1024), "-"));
                field.put("inline", source.optBoolean("inline", false));
                fields.put(field);
            }
            if (fields.length() > 0) embed.put("fields", fields);
        }
        return embed;
    }

    private static void copyNamedObject(
            JSONObject source,
            JSONObject target,
            String key,
            int maxNameLength,
            boolean includeUrl
    ) throws JSONException {
        JSONObject raw = source.optJSONObject(key);
        if (raw == null) return;
        String nameKey = "footer".equals(key) ? "text" : "name";
        String name = truncate(raw.optString(nameKey, ""), maxNameLength);
        if (name.trim().isEmpty()) return;
        JSONObject value = new JSONObject();
        value.put(nameKey, name);
        if (includeUrl) putIfNotBlank(value, "url", raw.optString("url", ""));
        putIfNotBlank(value, "icon_url", raw.optString("icon_url", ""));
        target.put(key, value);
    }

    private static void copyUrlObject(JSONObject source, JSONObject target, String key) throws JSONException {
        JSONObject raw = source.optJSONObject(key);
        if (raw == null) return;
        String url = raw.optString("url", "").trim();
        if (url.isEmpty()) return;
        JSONObject value = new JSONObject();
        value.put("url", url);
        target.put(key, value);
    }

    private static void putIfNotBlank(JSONObject target, String key, String value) throws JSONException {
        if (value != null && !value.trim().isEmpty()) target.put(key, value);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    public static String truncate(String value, int maxLength) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= maxLength) return text;
        if (maxLength <= 3) return text.substring(0, maxLength);
        return text.substring(0, maxLength - 3) + "...";
    }
}
