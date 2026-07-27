package net.alpas.crystalplanner.sync;

import android.content.Context;

import net.alpas.crystalplanner.R;
import net.alpas.crystalplanner.discord.DiscordApi;
import net.alpas.crystalplanner.storage.StateStore;
import net.alpas.crystalplanner.util.HttpClient;
import net.alpas.crystalplanner.util.PayloadNormalizer;
import net.alpas.crystalplanner.util.SyncLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BoardSync {
    private final Context context;
    private final HttpClient http;
    private final DiscordApi discord;
    private final StateStore state;
    private final SyncLog log;

    public BoardSync(Context context, HttpClient http, DiscordApi discord, StateStore state, SyncLog log) {
        this.context = context.getApplicationContext();
        this.http = http;
        this.discord = discord;
        this.state = state;
        this.log = log;
    }

    public int sync(
            String boardKey,
            String boardLabel,
            boolean enabled,
            String channelId,
            String jsonUrl,
            String generatorUrl,
            int generatorDelaySeconds
    ) throws Exception {
        if (!enabled) {
            log.info(context.getString(R.string.log_board_disabled, boardLabel));
            return 0;
        }
        if (channelId == null || channelId.trim().isEmpty()) {
            throw new IllegalArgumentException(context.getString(R.string.error_board_channel_missing, boardLabel));
        }
        if (jsonUrl == null || jsonUrl.trim().isEmpty()) {
            throw new IllegalArgumentException(context.getString(R.string.error_board_json_url_missing, boardLabel));
        }

        if (generatorUrl != null && !generatorUrl.trim().isEmpty()) {
            String finalUrl = cacheBusted(generatorUrl);
            HttpClient.Response generator = http.get(finalUrl, null);
            if (!generator.isSuccessful()) {
                throw new IllegalStateException(context.getString(
                        R.string.error_board_generator_failed,
                        boardLabel,
                        generator.status
                ));
            }
            if (generatorDelaySeconds > 0) {
                Thread.sleep(Math.min(generatorDelaySeconds, 30) * 1000L);
            }
        }

        HttpClient.Response response = http.get(cacheBusted(jsonUrl), null);
        if (!response.isSuccessful()) {
            throw new IllegalStateException(context.getString(
                    R.string.error_board_json_failed,
                    boardLabel,
                    response.status
            ));
        }

        JSONObject root = new JSONObject(response.body);
        JSONArray messages = root.optJSONArray("messages");
        if (messages == null) {
            throw new IllegalArgumentException(context.getString(R.string.error_board_no_messages, boardLabel));
        }

        String hash = sha256(stableStringify(messages));
        String previousHash = state.getBoardHash(boardKey);
        if (!previousHash.trim().isEmpty() && previousHash.equals(hash)) {
            log.info(context.getString(R.string.log_board_unchanged, boardLabel));
            return 0;
        }

        discord.clearChannel(channelId);
        int published = 0;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject raw = messages.optJSONObject(i);
            if (raw == null) continue;
            JSONObject payload = PayloadNormalizer.normalizeMessage(raw);
            boolean hasContent = !payload.optString("content", "").trim().isEmpty();
            JSONArray embeds = payload.optJSONArray("embeds");
            boolean hasEmbeds = embeds != null && embeds.length() > 0;
            if (!hasContent && !hasEmbeds) continue;
            discord.sendMessage(channelId, payload);
            published++;
            Thread.sleep(800L);
        }

        state.setBoardHash(boardKey, hash);
        log.info(context.getString(R.string.log_board_published, boardLabel, published));
        return published;
    }

    private static String cacheBusted(String url) {
        return url + (url.contains("?") ? "&" : "?") + "_=" + System.currentTimeMillis();
    }

    private static String sha256(String value) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(hash.length * 2);
        for (byte b : hash) result.append(String.format("%02x", b));
        return result.toString();
    }

    private static String stableStringify(Object value) {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                parts.add(stableStringify(array.opt(i)));
            }
            return "[" + String.join(",", parts) + "]";
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            List<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            List<String> parts = new ArrayList<>();
            for (String key : keys) {
                if (isIgnoredCompareKey(key)) continue;
                parts.add(JSONObject.quote(key) + ":" + stableStringify(object.opt(key)));
            }
            return "{" + String.join(",", parts) + "}";
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        return String.valueOf(value);
    }

    private static boolean isIgnoredCompareKey(String key) {
        return "timestamp".equals(key)
                || "generated_at".equals(key)
                || "generatedAt".equals(key)
                || "updated_at".equals(key)
                || "updatedAt".equals(key)
                || "created_at".equals(key)
                || "createdAt".equals(key)
                || "cache_buster".equals(key)
                || "cacheBuster".equals(key);
    }
}
