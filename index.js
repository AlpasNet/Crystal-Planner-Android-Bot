require("dotenv").config();

const { Client, GatewayIntentBits, EmbedBuilder, ChannelType } = require("discord.js");
const cheerio = require("cheerio");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const he = require("he");

const fetch = (...args) => import("node-fetch").then(({ default: fetch }) => fetch(...args));

const DISCORD_TOKEN = process.env.DISCORD_TOKEN;
const CHECK_INTERVAL_MINUTES = Number(process.env.CHECK_INTERVAL_MINUTES || 10);

const SEEN_FILE = path.join(__dirname, "seen-news.json");
const LINKSHELL_CURRENT_FILE = path.join(__dirname, "discord-bot-datas-current.json");
const LINKSHELL_PREVIOUS_FILE = path.join(__dirname, "discord-bot-datas-previous.json");

const LINKSHELL_ENABLED =
  String(process.env.LINKSHELL_ENABLED || "false").trim().toLowerCase() === "true";

const LINKSHELL_CHANNEL_ID = process.env.LINKSHELL_CHANNEL_ID;

const GENERATE_DISCORD_DATAS_URL =
  process.env.GENERATE_DISCORD_DATAS_URL;

const LINKSHELL_JSON_READ_DELAY_SECONDS = Number(
  process.env.LINKSHELL_JSON_READ_DELAY_SECONDS || 3
);

const DISCORD_DATAS_JSON_URL =
  process.env.DISCORD_DATAS_JSON_URL;

const LINKSHELL_ALWAYS_REPUBLISH =
  String(process.env.LINKSHELL_ALWAYS_REPUBLISH || "false").trim().toLowerCase() === "true";

const CATEGORIES = [
  {
    key: "topics",
    label: "Topics",
    url: "https://eu.finalfantasyxiv.com/lodestone/topics/",
    channelId: process.env.CHANNEL_TOPICS || process.env.CHANNEL_ID,
    color: 0xf1c40f,
    emoji: "⭐"
  },
  {
    key: "notices",
    label: "Notices",
    url: "https://eu.finalfantasyxiv.com/lodestone/news/category/1",
    channelId: process.env.CHANNEL_NOTICES || process.env.CHANNEL_ID,
    color: 0x3498db,
    emoji: "ℹ"
  },
  {
    key: "maintenance",
    label: "Maintenance",
    url: "https://eu.finalfantasyxiv.com/lodestone/news/category/2",
    channelId: process.env.CHANNEL_MAINTENANCE || process.env.CHANNEL_ID,
    color: 0xe67e22,
    emoji: "🛠"
  },
  {
    key: "updates",
    label: "Updates",
    url: "https://eu.finalfantasyxiv.com/lodestone/news/category/3",
    channelId: process.env.CHANNEL_UPDATES || process.env.CHANNEL_ID,
    color: 0x2ecc71,
    emoji: "🔄"
  }
];

const client = new Client({
  intents: [GatewayIntentBits.Guilds, GatewayIntentBits.GuildMessages]
});

function checkEnv() {
  if (!DISCORD_TOKEN) {
    console.error("Error: DISCORD_TOKEN is missing in the .env file.");
    process.exit(1);
  }

  const missingChannels = CATEGORIES.filter(category => !category.channelId);
  if (missingChannels.length > 0) {
    console.warn("Warning: some Lodestone channels are not configured:");
    missingChannels.forEach(category => console.warn(`- ${category.label}`));
  }

  if (LINKSHELL_ENABLED && !LINKSHELL_CHANNEL_ID) {
    console.warn("Warning: LINKSHELL_ENABLED=true but LINKSHELL_CHANNEL_ID is missing.");
  }
}

function wait(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function stableStringify(value) {
  const ignoredCompareKeys = new Set([
    "timestamp",
    "generated_at",
    "generatedAt",
    "updated_at",
    "updatedAt",
    "created_at",
    "createdAt",
    "cache_buster",
    "cacheBuster"
  ]);

  if (Array.isArray(value)) {
    return `[${value.map(item => stableStringify(item)).join(",")}]`;
  }

  if (value && typeof value === "object") {
    const keys = Object.keys(value)
      .filter(key => !ignoredCompareKeys.has(key))
      .sort();

    return `{${keys.map(key => `${JSON.stringify(key)}:${stableStringify(value[key])}`).join(",")}}`;
  }

  return JSON.stringify(value);
}

function normalizeMessagesForCompareFromText(jsonText) {
  if (jsonText === null || jsonText === undefined) return null;

  try {
    const parsed = JSON.parse(jsonText);

    if (!parsed || !Array.isArray(parsed.messages)) {
      return null;
    }

    return stableStringify(parsed.messages);
  } catch {
    return null;
  }
}

function normalizeMessagesForCompareFromData(data) {
  if (!data || !Array.isArray(data.messages)) return null;
  return stableStringify(data.messages);
}

function loadSeenNews() {
  if (!fs.existsSync(SEEN_FILE)) {
    fs.writeFileSync(SEEN_FILE, JSON.stringify({}, null, 2));
  }

  try {
    return JSON.parse(fs.readFileSync(SEEN_FILE, "utf8"));
  } catch {
    return {};
  }
}

function saveSeenNews(seen) {
  fs.writeFileSync(SEEN_FILE, JSON.stringify(seen, null, 2));
}

function logFirstStringDifference(label, previousText, currentText) {
  if (previousText === null || previousText === undefined) {
    console.log(`${label}: previous text is missing.`);
    return;
  }

  if (currentText === null || currentText === undefined) {
    console.log(`${label}: current text is missing.`);
    return;
  }

  console.log(`${label}: previous length = ${previousText.length}`);
  console.log(`${label}: current length = ${currentText.length}`);

  const maxLength = Math.max(previousText.length, currentText.length);

  for (let i = 0; i < maxLength; i++) {
    if (previousText[i] !== currentText[i]) {
      const start = Math.max(0, i - 80);
      const end = i + 160;

      console.log(`${label}: first difference at index ${i}`);
      console.log(`${label}: previous preview = ${previousText.slice(start, end)}`);
      console.log(`${label}: current preview  = ${currentText.slice(start, end)}`);
      return;
    }
  }

  console.log(`${label}: no string difference found.`);
}

function readLocalFile(filePath) {
  if (!fs.existsSync(filePath)) return null;
  return fs.readFileSync(filePath, "utf8");
}

function getDiscordMessages(jsonData) {
  if (jsonData && Array.isArray(jsonData.messages)) {
    return jsonData.messages;
  }

  return null;
}

function writeLocalFile(filePath, content) {
  fs.writeFileSync(filePath, content, "utf8");
}

function normalizeJsonTextForCompare(jsonText) {
  if (jsonText === null || jsonText === undefined) return null;

  try {
    const parsed = JSON.parse(jsonText);
    return JSON.stringify(parsed);
  } catch {
    return String(jsonText || "").trim();
  }
}

function absoluteUrl(url, baseUrl) {
  if (!url) return null;
  if (url.startsWith("http")) return url;
  return new URL(url, baseUrl).href;
}

function cleanText(text) {
  if (!text) return "";

  return he
    .decode(String(text))
    .replace(/<script[\s\S]*?<\/script>/gi, "")
    .replace(/<style[\s\S]*?<\/style>/gi, "")
    .replace(/<noscript[\s\S]*?<\/noscript>/gi, "")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/p>/gi, "\n\n")
    .replace(/<\/div>/gi, "\n")
    .replace(/<\/li>/gi, "\n")
    .replace(/<[^>]*>/g, "")
    .replace(/\r/g, "")
    .replace(/\s+\n/g, "\n")
    .replace(/\n\s+/g, "\n")
    .replace(/[ \t]+/g, " ")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function cleanTitle(text) {
  return cleanText(text).replace(/\n+/g, " ").replace(/\s+/g, " ").trim();
}

function limitText(text, maxLength = 900) {
  const cleaned = cleanText(text);
  if (!cleaned) return null;
  if (cleaned.length <= maxLength) return cleaned;
  return cleaned.slice(0, maxLength - 3) + "...";
}

function truncateString(value, maxLength) {
  const text = String(value || "").trim();
  if (text.length <= maxLength) return text;
  return text.slice(0, maxLength - 3) + "...";
}

async function fetchHtml(url) {
  const response = await fetch(url, {
    headers: { "User-Agent": "Mozilla/5.0 FFXIV Lodestone Discord Bot" }
  });

  if (!response.ok) {
    throw new Error(`HTTP error ${response.status} on ${url}`);
  }

  return response.text();
}

async function openGeneratorPage(url) {
  const finalUrl = `${url}${url.includes("?") ? "&" : "?"}_=${Date.now()}`;

  console.log(`Linkshell board: opening PHP generator: ${finalUrl}`);

  const response = await fetch(finalUrl, {
    headers: {
      "User-Agent": "Mozilla/5.0 AlpasNet Discord Bot",
      Accept: "text/plain,text/html,*/*"
    }
  });

  const text = await response.text();

  console.log(`Linkshell board: PHP generator HTTP status: ${response.status}`);
  console.log("Linkshell board: PHP generator response preview: " + text.slice(0, 300));

  if (!response.ok) {
    throw new Error(`PHP generator HTTP error ${response.status}: ${text.slice(0, 300)}`);
  }

  return text;
}

async function fetchJsonText(url) {
  const finalUrl = `${url}${url.includes("?") ? "&" : "?"}_=${Date.now()}`;

  console.log(`Reading JSON URL: ${finalUrl}`);

  const response = await fetch(finalUrl, {
    headers: {
      "User-Agent": "Mozilla/5.0 AlpasNet Discord Bot",
      Accept: "application/json,text/plain,*/*"
    }
  });

  const text = await response.text();

  console.log(`JSON HTTP status: ${response.status}`);

  if (!response.ok) {
    throw new Error(`HTTP error ${response.status} on ${finalUrl}: ${text.slice(0, 300)}`);
  }

  return text;
}

async function fetchJson(url) {
  const text = await fetchJsonText(url);

  try {
    return JSON.parse(text);
  } catch (error) {
    throw new Error(`Invalid JSON from ${url}: ${text.slice(0, 300)}`);
  }
}

function extractFirstParagraph(text) {
  if (!text) return null;

  const blockedStarts = [
    "News",
    "Topics",
    "Notices",
    "Maintenance",
    "Updates",
    "Status",
    "Patch Notes",
    "Special Sites",
    "The Lodestone",
    "FINAL FANTASY XIV"
  ];

  const paragraphs = cleanText(text)
    .split(/\n{2,}|\n/)
    .map(p => p.trim())
    .filter(p => p.length >= 40)
    .filter(p => !blockedStarts.some(start => p.startsWith(start)))
    .filter(p => !p.includes("JavaScript"))
    .filter(p => !p.includes("window."))
    .filter(p => !p.includes("var "));

  if (paragraphs.length === 0) return null;
  return limitText(paragraphs[0], 900);
}

function extractDateTimeSection(text) {
  if (!text) return null;

  const cleaned = cleanText(text);
  const marker = "[Date & Time]";
  const startIndex = cleaned.indexOf(marker);

  if (startIndex === -1) return null;

  let afterMarker = cleaned.slice(startIndex + marker.length).trim();

  const stopMarkers = [
    "[Affected Service]",
    "[Affected Worlds]",
    "[Details]",
    "[Update Details]",
    "[Maintenance Details]",
    "[Recovery Details]",
    "[Issue Details]",
    "[Cause]",
    "[Countermeasures]",
    "[In-game Content]",
    "[Companion App]",
    "[Known Issues]"
  ];

  let stopIndex = -1;

  for (const stopMarker of stopMarkers) {
    const index = afterMarker.indexOf(stopMarker);
    if (index !== -1 && (stopIndex === -1 || index < stopIndex)) {
      stopIndex = index;
    }
  }

  if (stopIndex !== -1) {
    afterMarker = afterMarker.slice(0, stopIndex).trim();
  }

  const monthRegex =
    /\b(?:Jan\.?|January|Feb\.?|February|Mar\.?|March|Apr\.?|April|May|Jun\.?|June|Jul\.?|July|Aug\.?|August|Sep\.?|Sept\.?|September|Oct\.?|October|Nov\.?|November|Dec\.?|December)\b/i;
  const yearRegex = /\b20\d{2}\b/;

  const dateLines = afterMarker
    .split("\n")
    .map(line => line.trim())
    .filter(line => line.length > 0)
    .filter(line => monthRegex.test(line) && yearRegex.test(line));

  if (dateLines.length === 0) return null;
  return limitText(dateLines.join("\n"), 1000);
}

async function fetchArticleDetails(articleUrl, categoryKey) {
  try {
    const html = await fetchHtml(articleUrl);
    const $ = cheerio.load(html);

    $("script").remove();
    $("style").remove();
    $("noscript").remove();

    const metaTitle =
      $("meta[property='og:title']").attr("content") ||
      $("meta[name='twitter:title']").attr("content") ||
      $("title").text();

    const metaDescription =
      $("meta[property='og:description']").attr("content") ||
      $("meta[name='description']").attr("content") ||
      $("meta[name='twitter:description']").attr("content");

    const metaImage =
      $("meta[property='og:image']").attr("content") ||
      $("meta[name='twitter:image']").attr("content");

    const articleElement =
      $(".news__detail__wrapper").first().length ? $(".news__detail__wrapper").first() :
      $(".news__detail").first().length ? $(".news__detail").first() :
      $(".topics__detail").first().length ? $(".topics__detail").first() :
      $("article").first().length ? $("article").first() :
      $("main").first();

    const articleText = cleanText(articleElement.html() || articleElement.text());

    let description = null;
    let dateTimeSection = null;

    if (categoryKey === "topics" || categoryKey === "notices") {
      description = extractFirstParagraph(articleText) || limitText(metaDescription, 900);
    }

    if (categoryKey === "maintenance" || categoryKey === "updates") {
      dateTimeSection = extractDateTimeSection(articleText);
      description = limitText(metaDescription, 500) || "New Lodestone publication available.";
    }

    return {
      title: cleanTitle(metaTitle),
      description: description || limitText(metaDescription, 500),
      image: metaImage ? absoluteUrl(metaImage, articleUrl) : null,
      dateTimeSection
    };
  } catch (error) {
    console.warn(`Unable to fetch article details: ${articleUrl} - ${error.message}`);
    return {
      title: null,
      description: null,
      image: null,
      dateTimeSection: null
    };
  }
}

async function fetchCategoryNews(category) {
  const html = await fetchHtml(category.url);
  const $ = cheerio.load(html);
  const news = [];

  const pushNews = (href, title, image = null) => {
    const fullUrl = absoluteUrl(href, category.url);
    const cleanItemTitle = cleanTitle(title);

    if (!fullUrl) return;
    if (!cleanItemTitle || cleanItemTitle.length < 4) return;
    if (!fullUrl.includes("/lodestone/topics/detail/") && !fullUrl.includes("/lodestone/news/detail/")) return;

    news.push({
      id: fullUrl,
      title: cleanItemTitle,
      url: fullUrl,
      image: image ? absoluteUrl(image, category.url) : null,
      categoryKey: category.key,
      categoryLabel: category.label,
      color: category.color,
      emoji: category.emoji
    });
  };

  $("a[href*='/lodestone/topics/detail/'], a[href*='/lodestone/news/detail/']").each((_, element) => {
    const link = $(element);
    const href = link.attr("href");
    const container = link.closest("li, article, .news__list, .news__list--wrapper, .topics__list");

    const title =
      link.find(".news__list--title").text() ||
      link.find(".entry__title").text() ||
      container.find(".news__list--title").first().text() ||
      container.find(".entry__title").first().text() ||
      link.attr("title") ||
      link.text();

    const image =
      link.find("img").first().attr("src") ||
      container.find("img").first().attr("src") ||
      null;

    pushNews(href, title, image);
  });

  const uniqueNews = Array.from(new Map(news.map(item => [item.id, item])).values()).slice(0, 10);

  console.log(`Lodestone ${category.label}: ${uniqueNews.length} publication(s) found.`);

  return uniqueNews;
}

async function postNews(item, channelId) {
  const channel = await client.channels.fetch(channelId);

  if (!channel) {
    console.error(`Channel not found: ${channelId}`);
    return;
  }

  const details = await fetchArticleDetails(item.url, item.categoryKey);

  const finalTitle = details.title || item.title;
  const finalDescription = details.description || "New Lodestone publication available.";
  const finalImage = details.image || item.image;

  const embed = new EmbedBuilder()
    .setAuthor({ name: `The Lodestone - ${item.categoryLabel}` })
    .setTitle(`${item.emoji} ${cleanTitle(finalTitle)}`)
    .setURL(item.url)
    .setDescription(finalDescription)
    .addFields({
      name: "Link",
      value: `[Open the article on The Lodestone](${item.url})`
    })
    .setColor(item.color)
    .setFooter({ text: "FINAL FANTASY XIV - The Lodestone" })
    .setTimestamp(new Date());

  if (details.dateTimeSection) {
    embed.addFields({
      name: "[Date & Time]",
      value: details.dateTimeSection
    });
  }

  if (finalImage) {
    embed.setImage(finalImage);
  }

  await channel.send({ embeds: [embed] });
}

async function checkCategory(category, seen, firstRun = false) {
  if (!category.channelId) {
    console.log(`Category ignored, channel not configured: ${category.label}`);
    return;
  }

  console.log(`Checking Lodestone: ${category.label}`);

  const currentNews = await fetchCategoryNews(category);

  if (!seen[category.key]) {
    seen[category.key] = [];
  }

  const newItems = currentNews.filter(item => !seen[category.key].includes(item.id));

  if (newItems.length === 0) {
    console.log(`No new Lodestone publication for ${category.label}.`);
    return;
  }

  for (const item of newItems.reverse()) {
    await postNews(item, category.channelId);
    seen[category.key].push(item.id);
  }

  seen[category.key] = [...new Set(seen[category.key])].slice(-200);

  console.log(`${newItems.length} new publication(s) sent for ${category.label}.`);
}

async function checkAllLodestoneCategories(firstRun = false) {
  const seen = loadSeenNews();

  for (const category of CATEGORIES) {
    try {
      await checkCategory(category, seen, firstRun);
    } catch (error) {
      console.error(`Category error ${category.label}:`, error.message);
    }
  }

  saveSeenNews(seen);
}

function normalizeDiscordEmbed(rawEmbed) {
  const embed = {};

  if (rawEmbed.title) embed.title = truncateString(rawEmbed.title, 256);
  if (rawEmbed.description) embed.description = truncateString(rawEmbed.description, 4096);
  if (rawEmbed.url) embed.url = rawEmbed.url;
  if (typeof rawEmbed.color === "number") embed.color = rawEmbed.color;

  if (rawEmbed.author && rawEmbed.author.name) {
    embed.author = {
      name: truncateString(rawEmbed.author.name, 256)
    };
    if (rawEmbed.author.url) embed.author.url = rawEmbed.author.url;
    if (rawEmbed.author.icon_url) embed.author.icon_url = rawEmbed.author.icon_url;
  }

  if (rawEmbed.image && rawEmbed.image.url) {
    embed.image = { url: rawEmbed.image.url };
  }

  if (rawEmbed.thumbnail && rawEmbed.thumbnail.url) {
    embed.thumbnail = { url: rawEmbed.thumbnail.url };
  }

  if (rawEmbed.footer && rawEmbed.footer.text) {
    embed.footer = {
      text: truncateString(rawEmbed.footer.text, 2048)
    };
    if (rawEmbed.footer.icon_url) embed.footer.icon_url = rawEmbed.footer.icon_url;
  }

  if (Array.isArray(rawEmbed.fields)) {
    embed.fields = rawEmbed.fields.slice(0, 25).map(field => ({
      name: truncateString(field.name || "Field", 256) || "Field",
      value: truncateString(field.value || "-", 1024) || "-",
      inline: Boolean(field.inline)
    }));
  }

  return embed;
}

function normalizeDiscordMessage(rawMessage) {
  return {
    content: truncateString(rawMessage.content || "", 2000),
    embeds: Array.isArray(rawMessage.embeds)
      ? rawMessage.embeds.slice(0, 10).map(normalizeDiscordEmbed)
      : []
  };
}

async function getTextChannel(channelId) {
  const channel = await client.channels.fetch(channelId);

  if (!channel) {
    throw new Error(`Channel not found: ${channelId}`);
  }

  const isTextChannel =
    channel.type === ChannelType.GuildText ||
    channel.type === ChannelType.GuildAnnouncement ||
    channel.type === ChannelType.PublicThread ||
    channel.type === ChannelType.PrivateThread;

  if (!isTextChannel || !channel.messages || !channel.send) {
    throw new Error(`The configured channel is not text-compatible: ${channelId}`);
  }

  return channel;
}

async function clearChannel(channel) {
  console.log(`Clearing channel: ${channel.id}`);

  let deletedTotal = 0;

  while (true) {
    const messages = await channel.messages.fetch({ limit: 100 });

    if (messages.size === 0) break;

    const now = Date.now();
    const fourteenDays = 14 * 24 * 60 * 60 * 1000;
    const recentMessages = messages.filter(message => now - message.createdTimestamp < fourteenDays);
    const oldMessages = messages.filter(message => now - message.createdTimestamp >= fourteenDays);

    if (recentMessages.size >= 2) {
      const deleted = await channel.bulkDelete(recentMessages, true);
      deletedTotal += deleted.size;
      await wait(1000);
    } else {
      for (const message of recentMessages.values()) {
        try {
          await message.delete();
          deletedTotal += 1;
          await wait(350);
        } catch (error) {
          console.warn(`Unable to delete recent message ${message.id}: ${error.message}`);
        }
      }
    }

    for (const message of oldMessages.values()) {
      try {
        await message.delete();
        deletedTotal += 1;
        await wait(750);
      } catch (error) {
        console.warn(`Unable to delete old message ${message.id}: ${error.message}`);
      }
    }

    if (messages.size < 100) break;
  }

  console.log(`Channel cleared. Deleted messages: ${deletedTotal}`);
}

async function publishLinkshellMessages(channel, messages) {
  for (const rawMessage of messages) {
    const payload = normalizeDiscordMessage(rawMessage);

    if (!payload.content && payload.embeds.length === 0) {
      continue;
    }

    await channel.send(payload);
    await wait(800);
  }
}

async function syncLinkshellBoard() {
  if (!LINKSHELL_ENABLED) {
    console.log("Linkshell board: disabled by LINKSHELL_ENABLED=false.");
    return;
  }

  if (!LINKSHELL_CHANNEL_ID) {
    console.warn("Linkshell board ignored: LINKSHELL_CHANNEL_ID is missing.");
    return;
  }

  console.log("Linkshell board: starting PHP generation + JSON download workflow...");

  // 1. Ouvrir la page PHP pour générer le fichier JSON côté serveur.
  await openGeneratorPage(GENERATE_DISCORD_DATAS_URL);

  // 2. Petite pause pour laisser le serveur finir l'écriture du fichier.
  if (LINKSHELL_JSON_READ_DELAY_SECONDS > 0) {
    console.log("Linkshell board: waiting " + LINKSHELL_JSON_READ_DELAY_SECONDS + "s before downloading JSON...");
    await wait(LINKSHELL_JSON_READ_DELAY_SECONDS * 1000);
  }

  // 3. Télécharger le JSON généré.
  const currentJsonText = await fetchJsonText(DISCORD_DATAS_JSON_URL);

  // 4. Écrire le JSON téléchargé dans le fichier current.
  writeLocalFile(LINKSHELL_CURRENT_FILE, currentJsonText);

  // 5. Lire le previous s'il existe.
  const previousJsonText = readLocalFile(LINKSHELL_PREVIOUS_FILE);
  const previousExists = previousJsonText !== null;

  // 6. Comparer simplement le contenu des deux fichiers.
  const filesAreDifferent = !previousExists || currentJsonText !== previousJsonText;

  console.log("Linkshell board: previous file exists: " + (previousExists ? "yes" : "no"));
  console.log("Linkshell board: files different: " + (filesAreDifferent ? "yes" : "no"));

  if (!filesAreDifferent) {
    console.log("Linkshell board: current and previous files are identical. No clear, no publish, no previous update.");
    return;
  }

  // 7. Parser uniquement pour récupérer les messages à publier.
  let currentData;
  try {
    currentData = JSON.parse(currentJsonText);
  } catch (error) {
    throw new Error("Invalid current JSON: " + currentJsonText.slice(0, 300));
  }

  const messages = getDiscordMessages(currentData);
  if (!Array.isArray(messages)) {
    throw new Error("Invalid current JSON: missing messages array.");
  }

  const channel = await getTextChannel(LINKSHELL_CHANNEL_ID);

  // 8. Si différent : vider le salon puis publier.
  console.log("Linkshell board: clearing Events channel before publishing...");
  await clearChannel(channel);

  console.log("Linkshell board: publishing Events messages...");
  await publishLinkshellMessages(channel, messages);

  // 9. Sauvegarder current comme nouveau previous uniquement après publication réussie.
  writeLocalFile(LINKSHELL_PREVIOUS_FILE, currentJsonText);

  console.log("Linkshell board: previous file updated after publication.");
  console.log("Linkshell board: workflow complete. " + messages.length + " Discord message(s) handled.");
}

async function runGlobalSync(firstRun = false) {
  console.log("Global sync: checking Lodestone news first...");

  try {
    await checkAllLodestoneCategories(firstRun);
  } catch (error) {
    console.error("Global Lodestone check error:", error.message);
  }

  console.log("Global sync: checking Linkshell JSON after Lodestone...");

  try {
    await syncLinkshellBoard(false);
  } catch (error) {
    console.error("Linkshell board sync error:", error.message);
  }
}

client.once("ready", async () => {
  console.log(`Bot connected as ${client.user.tag}`);

  const seen = loadSeenNews();
  const isFirstRun = Object.keys(seen).length === 0;

  await runGlobalSync(isFirstRun);

  setInterval(() => {
    runGlobalSync(false).catch(error => {
      console.error("Global sync error:", error.message);
    });
  }, CHECK_INTERVAL_MINUTES * 60 * 1000);
});

checkEnv();
client.login(DISCORD_TOKEN);
