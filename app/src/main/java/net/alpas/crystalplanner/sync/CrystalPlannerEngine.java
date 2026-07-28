package net.alpas.crystalplanner.sync;

import android.content.Context;

import net.alpas.crystalplanner.R;
import net.alpas.crystalplanner.discord.DiscordApi;
import net.alpas.crystalplanner.model.LodestoneCategory;
import net.alpas.crystalplanner.storage.AppSettings;
import net.alpas.crystalplanner.storage.StateStore;
import net.alpas.crystalplanner.util.HttpClient;
import net.alpas.crystalplanner.util.SyncLog;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CrystalPlannerEngine {
    public static final class Summary {
        public int lodestoneMessages;
        public int boardMessages;
        public int errors;

        public String describe(Context context) {
            return context.getString(
                    R.string.summary_sync,
                    lodestoneMessages,
                    boardMessages,
                    errors
            );
        }
    }

    private final Context context;
    private final AppSettings settings;
    private final StateStore state;
    private final SyncLog log;
    private final HttpClient http;
    private final DiscordApi discord;

    public CrystalPlannerEngine(Context context, AppSettings settings, String token) {
        this.context = context.getApplicationContext();
        this.settings = settings;
        this.state = new StateStore(context);
        this.log = new SyncLog(context);
        this.http = new HttpClient();
        this.discord = new DiscordApi(this.context, http, log, token);
    }

    public Summary run() throws Exception {
        Summary summary = new Summary();
        log.info(context.getString(R.string.log_global_sync_started));
        logConfiguredWebPaths();
        String botName = discord.verifyBot();
        log.info(context.getString(R.string.log_discord_auth_success, botName));

        LodestoneSync lodestone = new LodestoneSync(context, http, discord, state, log);
        List<LodestoneCategory> categories = Arrays.asList(
                new LodestoneCategory(
                        "topics", "Topics",
                        "https://eu.finalfantasyxiv.com/lodestone/topics/",
                        settings.topicsChannel, 0xF1C40F, "⭐"
                ),
                new LodestoneCategory(
                        "notices", "Notices",
                        "https://eu.finalfantasyxiv.com/lodestone/news/category/1",
                        settings.noticesChannel, 0x3498DB, "ℹ"
                ),
                new LodestoneCategory(
                        "maintenance", "Maintenance",
                        "https://eu.finalfantasyxiv.com/lodestone/news/category/2",
                        settings.maintenanceChannel, 0xE67E22, "🛠"
                ),
                new LodestoneCategory(
                        "updates", "Updates",
                        "https://eu.finalfantasyxiv.com/lodestone/news/category/3",
                        settings.updatesChannel, 0x2ECC71, "🔄"
                )
        );

        for (LodestoneCategory category : categories) {
            try {
                summary.lodestoneMessages += lodestone.syncCategory(category);
            } catch (Exception error) {
                summary.errors++;
                log.error(context.getString(R.string.log_lodestone_error, category.label, error.getMessage()));
            }
        }

        BoardSync boards = new BoardSync(context, http, discord, state, log);
        summary.boardMessages += runBoard(summary, boards, "events",
                context.getString(R.string.board_events),
                settings.linkshellEnabled,
                settings.linkshellChannel,
                settings.eventsJsonUrl(),
                settings.generatorUrl(),
                settings.jsonReadDelaySeconds);
        summary.boardMessages += runBoard(summary, boards, "rules",
                context.getString(R.string.board_rules),
                settings.rulesEnabled,
                settings.rulesChannel,
                settings.rulesJsonUrl(),
                "",
                0);
        summary.boardMessages += runBoard(summary, boards, "guides",
                context.getString(R.string.board_guides),
                settings.guidesEnabled,
                settings.guidesChannel,
                settings.guidesJsonUrl(),
                "",
                0);
        summary.boardMessages += runBoard(summary, boards, "macros",
                context.getString(R.string.board_macros),
                settings.macrosEnabled,
                settings.macrosChannel,
                settings.macrosJsonUrl(),
                "",
                0);

        log.info(context.getString(
                R.string.log_global_sync_complete,
                summary.describe(context)
        ));
        return summary;
    }

    private void logConfiguredWebPaths() {
        if (settings.webFolderUrl == null || settings.webFolderUrl.trim().isEmpty()) {
            log.warn(context.getString(R.string.log_web_folder_missing));
            return;
        }
        log.info(context.getString(R.string.log_web_folder_path, settings.webFolderUrl));
        log.info(context.getString(R.string.log_events_generator_path, settings.generatorUrl()));
        log.info(context.getString(R.string.log_events_json_path, settings.eventsJsonUrl()));
        log.info(context.getString(R.string.log_rules_json_path, settings.rulesJsonUrl()));
        log.info(context.getString(R.string.log_guides_json_path, settings.guidesJsonUrl()));
        log.info(context.getString(R.string.log_macros_json_path, settings.macrosJsonUrl()));
    }

    public int clearLodestoneChannels() throws Exception {
        log.info(context.getString(R.string.log_lodestone_cleanup_started));
        String botName = discord.verifyBot();
        log.info(context.getString(R.string.log_discord_auth_success, botName));

        Map<String, String> configured = new LinkedHashMap<>();
        configured.put("Topics", settings.topicsChannel);
        configured.put("Notices", settings.noticesChannel);
        configured.put("Maintenance", settings.maintenanceChannel);
        configured.put("Updates", settings.updatesChannel);

        Set<String> alreadyCleared = new LinkedHashSet<>();
        int totalDeleted = 0;
        int configuredChannels = 0;

        for (Map.Entry<String, String> entry : configured.entrySet()) {
            String channelId = entry.getValue() == null ? "" : entry.getValue().trim();
            if (channelId.isEmpty()) {
                log.warn(context.getString(R.string.log_lodestone_no_channel, entry.getKey()));
                continue;
            }
            if (!alreadyCleared.add(channelId)) {
                log.warn(context.getString(R.string.log_lodestone_duplicate_channel, entry.getKey()));
                continue;
            }

            configuredChannels++;
            log.info(context.getString(R.string.log_clearing_lodestone_channel, entry.getKey(), channelId));
            int deleted = discord.clearChannel(channelId);
            totalDeleted += deleted;
            log.info(context.getString(R.string.log_lodestone_deleted, entry.getKey(), deleted));
        }

        if (configuredChannels == 0) {
            throw new IllegalStateException(context.getString(R.string.error_no_valid_lodestone_channel));
        }

        log.info(context.getString(R.string.log_lodestone_cleanup_complete, totalDeleted));
        return totalDeleted;
    }

    private int runBoard(
            Summary summary,
            BoardSync boards,
            String key,
            String label,
            boolean enabled,
            String channel,
            String json,
            String generator,
            int delay
    ) {
        try {
            return boards.sync(key, label, enabled, channel, json, generator, delay);
        } catch (Exception error) {
            summary.errors++;
            log.error(context.getString(R.string.log_board_error, label, error.getMessage()));
            return 0;
        }
    }
}
