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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        // Events are managed message-by-message. This keeps one Discord message
        // per event, lets participant/job/position changes edit that message in
        // place, and deletes only the message for an event that disappears.
        if ("events".equals(boardKey)) {
            return syncEvents(boardLabel, channelId.trim(), messages);
        }

        // Include the local rendering revision in the hash so a display-only
        // change is republished once even when discord-guides.json itself did
        // not change.
        String renderingRevision = "guides".equals(boardKey) ? "guides-embed-footer-v1|" : "";
        String hash = sha256(renderingRevision + stableStringify(messages));
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

            // Guides: keep the public URL inside the SAME Discord embed and
            // display it below the image using the embed footer. The original
            // embed URL is kept as well, so the title remains clickable.
            if ("guides".equals(boardKey)) {
                putGuideLinkBelowImage(payload, extractGuideLink(raw));
            }

            if (!hasDiscordPayload(payload)) continue;
            discord.sendMessage(channelId, payload);
            published++;
            Thread.sleep(800L);
        }

        state.setBoardHash(boardKey, hash);
        log.info(context.getString(R.string.log_board_published, boardLabel, published));
        return published;
    }

    private int syncEvents(String boardLabel, String channelId, JSONArray messages) throws Exception {
        Map<String, EventEntry> currentEvents = buildEventEntries(messages);
        JSONObject tracked = state.getEventMessageState();
        String trackedChannel = state.getEventBoardChannel();

        // One-time migration from the old "clear everything and republish"
        // system, and also when the configured Events channel changes.
        if (!state.isEventBoardInitialized() || !channelId.equals(trackedChannel)) {
            log.info("Events: initializing individual Discord message tracking.");
            discord.clearChannel(channelId);
            tracked = new JSONObject();
            state.clearEventMessageState();
            state.setEventBoardTracking(channelId, true);
        }

        int changed = 0;
        Set<String> currentKeys = new LinkedHashSet<>();

        for (Map.Entry<String, EventEntry> item : currentEvents.entrySet()) {
            String eventKey = item.getKey();
            EventEntry event = item.getValue();
            currentKeys.add(eventKey);

            JSONObject record = tracked.optJSONObject(eventKey);
            if (record == null) record = new JSONObject();

            String messageId = record.optString("message_id", "").trim();
            String previousHash = record.optString("hash", "").trim();
            boolean crossposted = record.optBoolean("crossposted", false);
            boolean crosspostAttemptedThisRun = false;

            if (messageId.isEmpty()) {
                JSONObject created = discord.sendMessageAndReturn(channelId, event.payload);
                messageId = created.optString("id", "").trim();
                if (messageId.isEmpty()) {
                    throw new IllegalStateException("Discord did not return the created Event message ID.");
                }
                // Persist the Discord ID before crossposting. If Android is
                // stopped between the two network calls, the next sync edits/
                // crossposts this exact message instead of creating a duplicate.
                tracked.put(eventKey, eventRecord(messageId, event.hash, false, channelId));
                state.setEventMessageState(tracked);

                crossposted = tryCrosspostEvent(channelId, messageId);
                crosspostAttemptedThisRun = true;
                changed++;
                log.info("Events: created Discord message " + messageId + ".");
            } else if (!event.hash.equals(previousHash)) {
                boolean edited = discord.editMessageIfExists(channelId, messageId, event.payload);
                if (!edited) {
                    JSONObject created = discord.sendMessageAndReturn(channelId, event.payload);
                    messageId = created.optString("id", "").trim();
                    if (messageId.isEmpty()) {
                        throw new IllegalStateException("Discord did not return the recreated Event message ID.");
                    }
                    crossposted = false;
                    tracked.put(eventKey, eventRecord(messageId, event.hash, false, channelId));
                    state.setEventMessageState(tracked);

                    crossposted = tryCrosspostEvent(channelId, messageId);
                    crosspostAttemptedThisRun = true;
                    log.info("Events: recreated missing Discord message " + messageId + ".");
                } else {
                    log.info("Events: updated Discord message " + messageId + ".");
                }
                changed++;
            }

            // Crosspost only needs to succeed once. If it failed because of a
            // transient Discord/rate-limit/permission issue, retry on a later
            // synchronization without recreating the Event message.
            if (!crossposted && !crosspostAttemptedThisRun) {
                crossposted = tryCrosspostEvent(channelId, messageId);
                if (crossposted) changed++;
            }

            tracked.put(eventKey, eventRecord(messageId, event.hash, crossposted, channelId));
            state.setEventMessageState(tracked);
        }

        // Anything still tracked but no longer present in the JSON is an Event
        // that disappeared/ended/deleted. Delete only its own Discord message.
        List<String> removedKeys = new ArrayList<>();
        Iterator<String> trackedKeys = tracked.keys();
        while (trackedKeys.hasNext()) {
            String key = trackedKeys.next();
            if (!currentKeys.contains(key)) removedKeys.add(key);
        }

        for (String removedKey : removedKeys) {
            JSONObject record = tracked.optJSONObject(removedKey);
            String messageId = record == null ? "" : record.optString("message_id", "").trim();
            String messageChannel = record == null ? channelId : record.optString("channel_id", channelId).trim();
            if (messageChannel.isEmpty()) messageChannel = channelId;

            if (!messageId.isEmpty()) {
                discord.deleteMessageIfExists(messageChannel, messageId);
                log.info("Events: deleted obsolete Discord message " + messageId + ".");
                changed++;
            }
            tracked.remove(removedKey);
            state.setEventMessageState(tracked);
        }

        String overallHash = sha256("events-individual-v1|" + stableStringify(messages));
        state.setBoardHash("events", overallHash);
        state.setEventBoardTracking(channelId, true);

        if (changed == 0) {
            log.info(context.getString(R.string.log_board_unchanged, boardLabel));
        } else {
            log.info(context.getString(R.string.log_board_published, boardLabel, changed));
        }
        return changed;
    }

    private static JSONObject eventRecord(
            String messageId,
            String hash,
            boolean crossposted,
            String channelId
    ) throws Exception {
        JSONObject record = new JSONObject();
        record.put("message_id", messageId);
        record.put("hash", hash);
        record.put("crossposted", crossposted);
        record.put("channel_id", channelId);
        return record;
    }

    private boolean tryCrosspostEvent(String channelId, String messageId) {
        try {
            discord.crosspostMessage(channelId, messageId);
            log.info("Events: announcement published for Discord message " + messageId + ".");
            return true;
        } catch (Exception error) {
            // If the app was interrupted just after a successful crosspost,
            // local state may still say false. Recover by checking Discord's
            // CROSSPOSTED message flag before scheduling another retry.
            try {
                if (discord.isMessageCrossposted(channelId, messageId)) {
                    log.info("Events: announcement was already published for Discord message " + messageId + ".");
                    return true;
                }
            } catch (Exception ignored) {
                // Keep the original crosspost error in the log below.
            }
            log.warn("Events: automatic announcement publication failed for message "
                    + messageId + ": " + error.getMessage());
            return false;
        }
    }

    private static Map<String, EventEntry> buildEventEntries(JSONArray messages) throws Exception {
        Map<String, EventEntry> result = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();

        for (int i = 0; i < messages.length(); i++) {
            JSONObject raw = messages.optJSONObject(i);
            if (raw == null) continue;

            JSONObject payload = PayloadNormalizer.normalizeMessage(raw);
            if (!hasDiscordPayload(payload)) continue;

            String baseKey = eventIdentity(raw, payload, i);
            int occurrence = occurrences.containsKey(baseKey) ? occurrences.get(baseKey) + 1 : 1;
            occurrences.put(baseKey, occurrence);
            String key = occurrence == 1 ? baseKey : baseKey + "-" + occurrence;
            String hash = sha256("event-payload-v1|" + stableStringify(payload));
            result.put(key, new EventEntry(payload, hash));
        }
        return result;
    }

    private static String eventIdentity(JSONObject raw, JSONObject payload, int fallbackIndex) throws Exception {
        String explicitId = firstNonBlank(
                raw.optString("event_id", ""),
                raw.optString("eventId", ""),
                raw.optString("id", "")
        );
        if (!explicitId.isEmpty()) {
            return "id-" + safeKeyPart(explicitId);
        }

        JSONArray rawEmbeds = raw.optJSONArray("embeds");
        JSONObject rawEmbed = rawEmbeds == null ? null : rawEmbeds.optJSONObject(0);
        if (rawEmbed != null) {
            explicitId = firstNonBlank(
                    rawEmbed.optString("event_id", ""),
                    rawEmbed.optString("eventId", ""),
                    rawEmbed.optString("id", "")
            );
            if (!explicitId.isEmpty()) {
                return "id-" + safeKeyPart(explicitId);
            }
        }

        // The current Web JSON exposes one embed per Event but does not expose
        // its database ID. Prefer the Event image URL as a durable identity: it
        // stays unchanged when title, description, dates, jobs or positions are
        // edited. If the Web JSON later exposes event_id, it is preferred above.
        JSONArray embeds = payload.optJSONArray("embeds");
        JSONObject embed = embeds == null ? null : embeds.optJSONObject(0);
        if (embed != null) {
            JSONObject image = embed.optJSONObject("image");
            String imageUrl = image == null ? "" : image.optString("url", "").trim();
            if (!imageUrl.isEmpty()) {
                return "image-" + sha256(imageUrl).substring(0, 32);
            }
        }

        // Fallback for an Event without an image: title + Start timestamp is
        // stable for normal participant/job/position updates.
        String title = embed == null ? "" : embed.optString("title", "").trim();
        String startValue = "";
        if (embed != null) {
            JSONArray fields = embed.optJSONArray("fields");
            if (fields != null && fields.length() > 0) {
                JSONObject startField = fields.optJSONObject(0);
                if (startField != null) startValue = startField.optString("value", "").trim();
            }
        }

        String basis = title + "|" + startValue;
        if (basis.replace("|", "").trim().isEmpty()) {
            basis = "fallback-index-" + fallbackIndex + "|" + stableStringify(payload);
        }
        return "derived-" + sha256(basis).substring(0, 32);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static String safeKeyPart(String value) {
        String cleaned = value == null ? "" : value.trim().replaceAll("[^A-Za-z0-9_-]", "_");
        if (cleaned.length() > 80) cleaned = cleaned.substring(0, 80);
        return cleaned.isEmpty() ? "unknown" : cleaned;
    }

    private static boolean hasDiscordPayload(JSONObject payload) {
        if (payload == null) return false;
        boolean hasContent = !payload.optString("content", "").trim().isEmpty();
        JSONArray embeds = payload.optJSONArray("embeds");
        boolean hasEmbeds = embeds != null && embeds.length() > 0;
        return hasContent || hasEmbeds;
    }

    private static final class EventEntry {
        final JSONObject payload;
        final String hash;

        EventEntry(JSONObject payload, String hash) {
            this.payload = payload;
            this.hash = hash;
        }
    }

    private static void putGuideLinkBelowImage(JSONObject payload, String guideLink) throws Exception {
        if (payload == null || guideLink == null || guideLink.trim().isEmpty()) return;

        JSONArray embeds = payload.optJSONArray("embeds");
        if (embeds == null || embeds.length() == 0) return;

        JSONObject embed = embeds.optJSONObject(0);
        if (embed == null) return;

        JSONObject footer = new JSONObject();
        footer.put("text", guideLink.trim());
        embed.put("footer", footer);
    }

    private static String extractGuideLink(JSONObject raw) {
        if (raw == null) return "";

        JSONArray embeds = raw.optJSONArray("embeds");
        if (embeds == null) return "";

        for (int i = 0; i < embeds.length(); i++) {
            JSONObject embed = embeds.optJSONObject(i);
            if (embed == null) continue;
            String url = embed.optString("url", "").trim();
            if (url.startsWith("https://") || url.startsWith("http://")) {
                return url;
            }
        }
        return "";
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
