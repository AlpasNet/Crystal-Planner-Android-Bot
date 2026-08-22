#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";

function usage() {
  console.log("Usage: node scripts/migrate-android-backup.mjs BACKUP.json [--config FILE] [--state FILE]");
}

const args = process.argv.slice(2);
if (!args.length || args.includes("--help") || args.includes("-h")) {
  usage();
  process.exit(args.length ? 0 : 1);
}
const source = path.resolve(args.shift());
let configPath = path.resolve("config/config.json");
let statePath = path.resolve("data/state.json");
while (args.length) {
  const key = args.shift();
  if (key === "--config") configPath = path.resolve(args.shift());
  else if (key === "--state") statePath = path.resolve(args.shift());
  else throw new Error(`Unknown argument: ${key}`);
}

const backup = JSON.parse(fs.readFileSync(source, "utf8"));
if (backup.format !== "crystal-planner-settings" || !backup.settings) {
  throw new Error("This is not a Crystal Planner Android settings backup.");
}
const s = backup.settings;
const legacyNewsChannels = [s.noticesChannel, s.maintenanceChannel, s.updatesChannel]
  .map(value => String(value || "").trim())
  .filter(Boolean);
const uniqueLegacyNewsChannels = [...new Set(legacyNewsChannels)];
const newsChannelId = uniqueLegacyNewsChannels[0] || "";

const config = {
  syncIntervalMinutes: Math.max(1, Number(s.intervalMinutes || 15)),
  webFolderUrl: String(s.webFolderUrl || "").replace(/\/+$/, ""),
  jsonReadDelaySeconds: Math.max(0, Number(s.jsonReadDelaySeconds || 3)),
  events: {
    enabled: Boolean(s.linkshellEnabled),
    channelId: String(s.linkshellChannel || ""),
    crosspostAnnouncements: true
  },
  rules: { enabled: Boolean(s.rulesEnabled), channelId: String(s.rulesChannel || "") },
  guides: { enabled: Boolean(s.guidesEnabled), channelId: String(s.guidesChannel || "") },
  macros: { enabled: Boolean(s.macrosEnabled), channelId: String(s.macrosChannel || "") },
  lodestone: {
    enabled: true,
    newsChannelId,
    topicsChannelId: String(s.topicsChannel || "")
  }
};

const h = backup.history || {};
const state = {
  version: 3,
  seen: h.seen && typeof h.seen === "object" ? h.seen : {},
  boardHashes: h.boardHashes && typeof h.boardHashes === "object" ? h.boardHashes : {},
  events: {
    channelId: String(h.eventBoardChannel || ""),
    initialized: Boolean(h.eventBoardInitialized),
    messages: h.eventMessages && typeof h.eventMessages === "object" ? h.eventMessages : {}
  },
  lodestone: {
    feeds: {
      news: { channelId: "", initialized: false, baselinePending: false, seenIds: [], messages: {} },
      topics: { channelId: "", initialized: false, baselinePending: false, seenIds: [], messages: {} }
    }
  },
  lastRun: {}
};

fs.mkdirSync(path.dirname(configPath), { recursive: true });
fs.mkdirSync(path.dirname(statePath), { recursive: true });
fs.writeFileSync(configPath, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
fs.writeFileSync(statePath, `${JSON.stringify(state, null, 2)}\n`, { mode: 0o600 });
console.log(`Configuration written to ${configPath}`);
console.log(`History/state written to ${statePath}`);
if (uniqueLegacyNewsChannels.length > 1) {
  console.warn(`Android used ${uniqueLegacyNewsChannels.length} different Lodestone News/Maintenance/Updates channels. RSS mode now uses one News channel; ${newsChannelId} was selected. Change lodestone.newsChannelId if necessary.`);
}
console.log("Lodestone now uses the official news.xml and topics.xml RSS feeds. The first sync publishes the latest 10 items; later syncs append only newly-seen RSS entries and keep old Discord messages.");
console.log("The Discord token is intentionally NOT migrated. Put it in the server environment file.");
