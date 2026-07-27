package net.alpas.crystalplanner.discord;

import android.content.Context;

import net.alpas.crystalplanner.R;
import net.alpas.crystalplanner.util.HttpClient;
import net.alpas.crystalplanner.util.SyncLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class DiscordApi {
    private static final String API = "https://discord.com/api/v10";
    private static final Duration BULK_DELETE_LIMIT = Duration.ofDays(14).minusMinutes(5);

    private final Context context;
    private final HttpClient http;
    private final SyncLog log;
    private final String authorization;

    public DiscordApi(Context context, HttpClient http, SyncLog log, String botToken) {
        this.context = context.getApplicationContext();
        this.http = http;
        this.log = log;
        String normalized = DiscordToken.normalize(botToken);
        DiscordToken.requirePlausible(
                normalized,
                this.context.getString(R.string.discord_token_empty),
                this.context.getString(R.string.discord_token_too_short),
                this.context.getString(R.string.discord_token_whitespace)
        );
        this.authorization = "Bot " + normalized;
    }

    public String verifyBot() throws Exception {
        HttpClient.Response response = http.get(API + "/users/@me", authorization);
        requireSuccess(response, context.getString(R.string.discord_op_verify_token));
        JSONObject user = new JSONObject(response.body);
        return user.optString("username", "unknown") + "#" + user.optString("discriminator", "0");
    }

    public void sendMessage(String channelId, JSONObject payload) throws Exception {
        HttpClient.Response response = http.postJson(
                API + "/channels/" + requireSnowflake(channelId) + "/messages",
                authorization,
                payload
        );
        requireSuccess(response, context.getString(R.string.discord_op_send_message));
    }

    public int clearChannel(String channelId) throws Exception {
        String safeChannel = requireSnowflake(channelId);
        int totalDeleted = 0;

        for (int page = 0; page < 50; page++) {
            HttpClient.Response response = http.get(
                    API + "/channels/" + safeChannel + "/messages?limit=100",
                    authorization
            );
            requireSuccess(response, context.getString(R.string.discord_op_read_messages));
            JSONArray messages = new JSONArray(response.body);
            if (messages.length() == 0) break;

            List<String> recent = new ArrayList<>();
            List<String> old = new ArrayList<>();
            Instant recentThreshold = Instant.now().minus(BULK_DELETE_LIMIT);

            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.optJSONObject(i);
                if (message == null) continue;
                String id = message.optString("id", "");
                String timestamp = message.optString("timestamp", "");
                if (id.trim().isEmpty()) continue;
                try {
                    if (Instant.parse(timestamp).isAfter(recentThreshold)) recent.add(id);
                    else old.add(id);
                } catch (Exception ignored) {
                    old.add(id);
                }
            }

            int deletedThisRound = 0;
            if (recent.size() >= 2) {
                try {
                    bulkDelete(safeChannel, recent);
                    deletedThisRound += recent.size();
                } catch (Exception error) {
                    log.warn(context.getString(R.string.log_bulk_delete_fallback, error.getMessage()));
                    deletedThisRound += deleteIndividually(safeChannel, recent, 250L);
                }
            } else if (recent.size() == 1) {
                deletedThisRound += deleteIndividually(safeChannel, recent, 250L);
            }
            deletedThisRound += deleteIndividually(safeChannel, old, 550L);
            totalDeleted += deletedThisRound;

            if (deletedThisRound == 0 || messages.length() < 100) break;
            Thread.sleep(500L);
        }

        log.info(context.getString(R.string.log_channel_cleared, safeChannel, totalDeleted));
        return totalDeleted;
    }

    private void bulkDelete(String channelId, List<String> messageIds) throws Exception {
        JSONObject body = new JSONObject();
        body.put("messages", new JSONArray(messageIds));
        HttpClient.Response response = http.postJson(
                API + "/channels/" + channelId + "/messages/bulk-delete",
                authorization,
                body
        );
        requireSuccess(response, context.getString(R.string.discord_op_bulk_delete));
    }

    private int deleteIndividually(String channelId, List<String> messageIds, long delayMs) {
        int count = 0;
        for (String messageId : messageIds) {
            try {
                HttpClient.Response response = http.delete(
                        API + "/channels/" + channelId + "/messages/" + requireSnowflake(messageId),
                        authorization
                );
                if (response.isSuccessful()) {
                    count++;
                } else {
                    log.warn(context.getString(R.string.log_message_not_deleted_http, messageId, response.status));
                }
                Thread.sleep(delayMs);
            } catch (Exception error) {
                log.warn(context.getString(R.string.log_message_not_deleted, messageId, error.getMessage()));
            }
        }
        return count;
    }

    private String requireSnowflake(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (!cleaned.matches("[0-9]{15,22}")) {
            throw new IllegalArgumentException(context.getString(R.string.invalid_discord_id, cleaned));
        }
        return cleaned;
    }

    private void requireSuccess(HttpClient.Response response, String operation) {
        if (response.isSuccessful()) return;

        String discordMessage = "";
        String discordCode = "";
        try {
            JSONObject error = new JSONObject(response.body == null ? "{}" : response.body);
            discordMessage = error.optString("message", "").trim();
            if (error.has("code")) discordCode = String.valueOf(error.opt("code"));
        } catch (Exception ignored) {
        }

        if (response.status == 401) {
            throw new IllegalStateException(context.getString(R.string.discord_unauthorized));
        }
        if (response.status == 403) {
            throw new IllegalStateException(context.getString(R.string.discord_forbidden));
        }
        if (response.status == 429) {
            throw new IllegalStateException(context.getString(R.string.discord_rate_limited));
        }

        String details = discordMessage;
        if (!discordCode.isEmpty()) {
            details = details.isEmpty() ? "code " + discordCode : details + " (code " + discordCode + ")";
        }
        if (details.isEmpty()) {
            details = context.getString(R.string.discord_empty_response);
        }
        throw new IllegalStateException(context.getString(
                R.string.discord_operation_failed,
                operation,
                response.status,
                details
        ));
    }
}
