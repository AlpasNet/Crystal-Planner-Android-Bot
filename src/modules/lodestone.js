import { getText } from "../http.js";
import { normalizeMessage } from "../discord/payload.js";
import { sha256, sleep, truncate } from "../utils.js";

export const LODESTONE_INITIAL_ITEMS = 10;

export const LODESTONE_FEEDS = [
  {
    key: "news",
    label: "News",
    url: "https://eu.finalfantasyxiv.com/lodestone/news/news.xml",
    channelConfigKey: "newsChannelId",
    color: 0x3498DB,
    emoji: "📰"
  },
  {
    key: "topics",
    label: "Topics",
    url: "https://eu.finalfantasyxiv.com/lodestone/news/topics.xml",
    channelConfigKey: "topicsChannelId",
    color: 0xF1C40F,
    emoji: "⭐"
  }
];

export async function syncLodestone({ config, discord, store, logger }) {
  if (!config.lodestone.enabled) {
    logger.info("Lodestone RSS: disabled.");
    return { sent: 0, errors: 0 };
  }

  let sent = 0;
  let errors = 0;
  for (const feed of LODESTONE_FEEDS) {
    const channelId = String(config.lodestone[feed.channelConfigKey] ?? "").trim();
    if (!channelId) {
      logger.info(`Lodestone ${feed.label} RSS: skipped, no channel configured.`);
      continue;
    }

    try {
      const xml = await getText(feed.url, {
        headers: {
          "Accept": "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.1"
        }
      });
      const items = parseRssFeed(xml, feed);
      if (!items.length) {
        throw new Error(`The RSS feed returned no usable item: ${feed.url}`);
      }
      const result = await syncRssFeed({ feed, channelId, items, discord, store, logger });
      sent += result.created;
    } catch (error) {
      errors++;
      logger.error(`Lodestone ${feed.label} RSS: ${error.message}`);
    }
  }

  return { sent, errors };
}

export async function syncRssFeed({ feed, channelId, items, discord, store, logger }) {
  const state = store.getLodestoneFeedState(feed.key);
  let created = 0;

  // Changing the destination channel never deletes or republishes old Lodestone
  // messages. Only future RSS entries are sent to the newly configured channel.
  state.channelId = channelId;

  const sorted = sortNewestFirst(items);
  const seen = new Set(Array.isArray(state.seenIds) ? state.seenIds.map(String) : []);

  if (!state.initialized) {
    // First activation: seed the Discord channel with the latest 10 entries only.
    // All entries currently visible in the RSS feed are then marked as known so
    // older feed history cannot be published on the next synchronization.
    const initial = sorted.slice(0, LODESTONE_INITIAL_ITEMS);

    for (let index = initial.length - 1; index >= 0; index--) {
      const item = initial[index];
      if (state.messages[item.id]?.messageId) continue;

      const message = await discord.sendMessage(channelId, buildRssPayload(item, feed));
      if (!message?.id) throw new Error(`Discord did not return a message ID for Lodestone ${feed.label}.`);
      state.messages[item.id] = {
        messageId: String(message.id),
        publishedAt: item.publishedAt || ""
      };
      store.saveLodestoneState();
      created++;
      await sleep(250);
    }

    for (const item of sorted) seen.add(item.id);
    state.seenIds = [...seen];
    state.initialized = true;
    state.baselinePending = false;
    store.saveLodestoneState();

    logger.info(`Lodestone ${feed.label} RSS initialized: ${created} of the latest ${Math.min(LODESTONE_INITIAL_ITEMS, sorted.length)} item(s) published. Older Discord messages will never be removed automatically.`);
    return { created, edited: 0, deleted: 0 };
  }

  // Compatibility with state files created by 1.1.0. The old release tracked
  // only the latest 10 messages. Establish the current RSS as the baseline once,
  // but still publish entries that are clearly newer than the newest tracked one.
  if (state.baselinePending) {
    const trackedDates = Object.values(state.messages || {})
      .map(entry => Date.parse(entry?.publishedAt || ""))
      .filter(Number.isFinite);
    const newestTracked = trackedDates.length ? Math.max(...trackedDates) : Number.NaN;

    const definitelyNew = sorted.filter(item => {
      if (seen.has(item.id)) return false;
      const published = Date.parse(item.publishedAt || item.publishedRaw || "");
      return Number.isFinite(newestTracked) && Number.isFinite(published) && published > newestTracked;
    });

    for (let index = definitelyNew.length - 1; index >= 0; index--) {
      const item = definitelyNew[index];
      const message = await discord.sendMessage(channelId, buildRssPayload(item, feed));
      if (!message?.id) throw new Error(`Discord did not return a message ID for Lodestone ${feed.label}.`);
      state.messages[item.id] = { messageId: String(message.id), publishedAt: item.publishedAt || "" };
      seen.add(item.id);
      state.seenIds = [...seen];
      store.saveLodestoneState();
      created++;
      await sleep(250);
    }

    for (const item of sorted) seen.add(item.id);
    state.seenIds = [...seen];
    state.baselinePending = false;
    store.saveLodestoneState();
    logger.info(`Lodestone ${feed.label} RSS migration baseline established: ${created} new item(s) published; no old Discord message deleted.`);
    return { created, edited: 0, deleted: 0 };
  }

  // Normal operation: append-only. Known entries are left untouched even if
  // their RSS representation changes or the Discord message was deleted by hand.
  // Only item IDs never seen before are published.
  const newItems = sorted.filter(item => !seen.has(item.id));
  for (let index = newItems.length - 1; index >= 0; index--) {
    const item = newItems[index];
    const message = await discord.sendMessage(channelId, buildRssPayload(item, feed));
    if (!message?.id) throw new Error(`Discord did not return a message ID for Lodestone ${feed.label}.`);

    state.messages[item.id] = {
      messageId: String(message.id),
      publishedAt: item.publishedAt || ""
    };
    seen.add(item.id);
    state.seenIds = [...seen];
    store.saveLodestoneState();
    created++;
    await sleep(250);
  }

  logger.info(`Lodestone ${feed.label} RSS: ${created} new item(s) published; existing Discord messages kept unchanged.`);
  return { created, edited: 0, deleted: 0 };
}

export function parseRssFeed(xml, feed = {}) {
  const source = String(xml ?? "");
  const blocks = [...source.matchAll(/<item\b[^>]*>([\s\S]*?)<\/item>/gi)].map(match => match[1]);
  if (!blocks.length) {
    return parseAtomFeed(source, feed);
  }

  const seen = new Set();
  const items = [];
  for (const block of blocks) {
    const title = cleanText(readTag(block, "title"));
    const link = cleanUrl(readTag(block, "link") || readLinkHref(block));
    const guid = cleanText(readTag(block, "guid"));
    const publishedRaw = cleanText(readTag(block, "pubDate") || readTag(block, "dc:date") || readTag(block, "date"));
    const descriptionHtml = readTag(block, "description") || readTag(block, "content:encoded");
    const description = truncate(htmlToText(descriptionHtml), 3500);
    const image = extractImage(block, descriptionHtml, link || feed.url || "");
    const category = cleanText(readTag(block, "category"));
    const publishedAt = normalizeDate(publishedRaw);
    const id = cleanText(guid) || link || sha256(`${title}\n${publishedRaw}\n${description}`).slice(0, 32);

    if (!id || !title || seen.has(id)) continue;
    seen.add(id);
    items.push({ id, title, url: link, description, image, category, publishedAt, publishedRaw });
  }

  return sortNewestFirst(items);
}

function parseAtomFeed(xml, feed) {
  const blocks = [...String(xml ?? "").matchAll(/<entry\b[^>]*>([\s\S]*?)<\/entry>/gi)].map(match => match[1]);
  const seen = new Set();
  const items = [];
  for (const block of blocks) {
    const title = cleanText(readTag(block, "title"));
    const link = cleanUrl(readLinkHref(block));
    const guid = cleanText(readTag(block, "id"));
    const publishedRaw = cleanText(readTag(block, "published") || readTag(block, "updated"));
    const descriptionHtml = readTag(block, "summary") || readTag(block, "content");
    const description = truncate(htmlToText(descriptionHtml), 3500);
    const image = extractImage(block, descriptionHtml, link || feed.url || "");
    const publishedAt = normalizeDate(publishedRaw);
    const id = guid || link || sha256(`${title}\n${publishedRaw}\n${description}`).slice(0, 32);
    if (!id || !title || seen.has(id)) continue;
    seen.add(id);
    items.push({ id, title, url: link, description, image, category: "", publishedAt, publishedRaw });
  }
  return sortNewestFirst(items);
}

function buildRssPayload(item, feed) {
  const embed = {
    author: { name: `The Lodestone - ${feed.label}` },
    title: `${feed.emoji} ${truncate(item.title, 250)}`,
    color: feed.color,
    footer: { text: "FINAL FANTASY XIV - The Lodestone · Official RSS" }
  };
  if (item.url) {
    embed.url = item.url;
    embed.fields = [{ name: "Link", value: `[Open the article on The Lodestone](${item.url})`, inline: false }];
  }
  if (item.description) embed.description = truncate(item.description, 4096);
  if (item.publishedAt) embed.timestamp = item.publishedAt;
  if (item.image) embed.image = { url: item.image };
  return normalizeMessage({ embeds: [embed] });
}

function readTag(source, name) {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = String(source ?? "").match(new RegExp(`<${escaped}\\b[^>]*>([\\s\\S]*?)<\\/${escaped}>`, "i"));
  return match ? unwrapCdata(match[1]) : "";
}

function readLinkHref(source) {
  const match = String(source ?? "").match(/<link\b[^>]*\bhref\s*=\s*["']([^"']+)["'][^>]*\/?\s*>/i);
  return match ? decodeXml(match[1]) : "";
}

function extractImage(itemBlock, descriptionHtml, baseUrl) {
  // Square Enix RSS can expose HTML either literally inside CDATA or XML-escaped
  // (&lt;img ...&gt;). Decode entities before looking for an <img> tag.
  const decodedBlock = decodeXmlRepeated(String(itemBlock ?? ""));
  const decodedDescription = decodeXmlRepeated(String(descriptionHtml ?? ""));

  const candidates = [
    /<enclosure\b[^>]*\burl\s*=\s*["']([^"']+)["'][^>]*>/i,
    /<media:content\b[^>]*\burl\s*=\s*["']([^"']+)["'][^>]*>/i,
    /<media:thumbnail\b[^>]*\burl\s*=\s*["']([^"']+)["'][^>]*>/i
  ];
  for (const pattern of candidates) {
    const match = decodedBlock.match(pattern);
    if (match) return absoluteUrl(decodeXmlRepeated(match[1]), baseUrl);
  }

  const img = decodedDescription.match(/<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>/i);
  return img ? absoluteUrl(decodeXmlRepeated(img[1]), baseUrl) : "";
}

function unwrapCdata(value) {
  const text = String(value ?? "").trim();
  const match = text.match(/^<!\[CDATA\[([\s\S]*?)\]\]>$/i);
  return match ? match[1] : text;
}

function htmlToText(value) {
  // Decode first. If the feed contains &lt;p&gt; instead of a literal <p>,
  // stripping tags before entity decoding would leak HTML tags into Discord.
  const decoded = decodeXmlRepeated(String(value ?? ""));
  return cleanText(decoded
    .replace(/<script\b[\s\S]*?<\/script>/gi, " ")
    .replace(/<style\b[\s\S]*?<\/style>/gi, " ")
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<\/(?:p|div|li|section|article|h[1-6]|ul|ol|blockquote)>/gi, "\n")
    .replace(/<li\b[^>]*>/gi, "• ")
    .replace(/<[^>]+>/g, " "));
}

function cleanText(value) {
  return decodeXml(value)
    .replace(/\r/g, "")
    .split("\n")
    .map(line => line.replace(/[ \t]+/g, " ").trim())
    .filter(Boolean)
    .join("\n")
    .trim();
}

function decodeXml(value) {
  const named = {
    amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " ",
    ndash: "–", mdash: "—", hellip: "…", rsquo: "’", lsquo: "‘", rdquo: "”", ldquo: "“"
  };
  return String(value ?? "").replace(/&(#x[0-9a-f]+|#\d+|[a-z][a-z0-9]+);/gi, (whole, entity) => {
    if (entity[0] === "#") {
      const hex = entity[1]?.toLowerCase() === "x";
      const code = Number.parseInt(entity.slice(hex ? 2 : 1), hex ? 16 : 10);
      if (Number.isFinite(code)) {
        try { return String.fromCodePoint(code); } catch { return whole; }
      }
    }
    return named[entity.toLowerCase()] ?? whole;
  });
}

function decodeXmlRepeated(value, maxPasses = 3) {
  let current = String(value ?? "");
  for (let i = 0; i < maxPasses; i++) {
    const decoded = decodeXml(current);
    if (decoded === current) break;
    current = decoded;
  }
  return current;
}

function normalizeDate(value) {
  const ms = Date.parse(String(value ?? "").trim());
  return Number.isFinite(ms) ? new Date(ms).toISOString() : "";
}

function sortNewestFirst(items) {
  return [...items].sort((a, b) => {
    const left = Date.parse(a.publishedAt || a.publishedRaw || "");
    const right = Date.parse(b.publishedAt || b.publishedRaw || "");
    if (Number.isFinite(left) && Number.isFinite(right) && left !== right) return right - left;
    return 0;
  });
}

function cleanUrl(value) {
  const text = cleanText(value).trim();
  if (!text) return "";
  try {
    const url = new URL(text);
    return url.protocol === "https:" ? url.toString() : "";
  } catch {
    return "";
  }
}

function absoluteUrl(value, baseUrl) {
  const text = String(value ?? "").trim();
  if (!text) return "";
  try {
    const url = new URL(text, baseUrl || "https://eu.finalfantasyxiv.com/");
    return url.protocol === "https:" ? url.toString() : "";
  } catch {
    return "";
  }
}
