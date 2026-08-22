import test from "node:test";
import assert from "node:assert/strict";
import { LODESTONE_INITIAL_ITEMS, parseRssFeed, syncRssFeed } from "../src/modules/lodestone.js";

const FEED = { key: "news", label: "News", emoji: "📰", color: 123, url: "https://eu.finalfantasyxiv.com/lodestone/news/news.xml" };

function rss(count = 12, offset = 0) {
  const items = [];
  for (let i = 0; i < count; i++) {
    const n = i + offset;
    const date = new Date(Date.UTC(2026, 7, 20, 12, 0, 0) - n * 86400000).toUTCString();
    items.push(`<item>
<title><![CDATA[Official news ${n}]]></title>
<link>https://eu.finalfantasyxiv.com/lodestone/news/detail/id-${n}</link>
<guid>id-${n}</guid>
<pubDate>${date}</pubDate>
<description><![CDATA[<p>Description ${n}</p><img src="https://img.finalfantasyxiv.com/${n}.jpg">]]></description>
</item>`);
  }
  return `<?xml version="1.0"?><rss version="2.0"><channel>${items.join("")}</channel></rss>`;
}

class MemoryStore {
  constructor() {
    this.state = { lodestone: { feeds: { news: { channelId: "", initialized: false, baselinePending: false, seenIds: [], messages: {} } } } };
  }
  getLodestoneFeedState(key) { return this.state.lodestone.feeds[key]; }
  saveLodestoneState() {}
}

class MockDiscord {
  constructor() { this.next = 100000000000000; this.messages = new Map(); this.created=[]; this.edited=[]; this.deleted=[]; }
  async sendMessage(channel, payload) { const id=String(this.next++); this.messages.set(id,payload); this.created.push({channel,id,payload}); return {id}; }
  async editMessageIfExists(channel,id,payload) { if(!this.messages.has(id)) return false; this.messages.set(id,payload); this.edited.push({channel,id,payload}); return true; }
  async deleteMessageIfExists(channel,id) { this.deleted.push({channel,id}); return this.messages.delete(id); }
  async getMessage(channel,id) { return this.messages.has(id) ? {id} : null; }
}
const logger = { info() {}, warn() {}, error() {} };

test("RSS parser returns official items newest-first with RSS description and image", () => {
  const items = parseRssFeed(rss(12), FEED);
  assert.equal(items.length, 12);
  assert.equal(items[0].id, "id-0");
  assert.equal(items[0].title, "Official news 0");
  assert.equal(items[0].description, "Description 0");
  assert.equal(items[0].image, "https://img.finalfantasyxiv.com/0.jpg");
});

test("first RSS sync publishes only the latest 10 and establishes a baseline for the whole current feed", async () => {
  const store = new MemoryStore();
  const discord = new MockDiscord();
  const items = parseRssFeed(rss(15), FEED);
  await syncRssFeed({ feed: FEED, channelId: "123456789012345", items, discord, store, logger });

  assert.equal(discord.created.length, LODESTONE_INITIAL_ITEMS);
  assert.equal(discord.deleted.length, 0);
  assert.equal(discord.edited.length, 0);
  assert.equal(store.state.lodestone.feeds.news.initialized, true);
  assert.equal(store.state.lodestone.feeds.news.seenIds.length, 15);
  assert.equal(Object.keys(store.state.lodestone.feeds.news.messages).length, 10);
});

test("after initialization only unseen RSS entries are appended and old Discord messages are kept", async () => {
  const store = new MemoryStore();
  const discord = new MockDiscord();
  const channelId = "123456789012345";
  const initial = parseRssFeed(rss(12), FEED);
  await syncRssFeed({ feed: FEED, channelId, items: initial, discord, store, logger });
  const firstMessageIds = [...discord.messages.keys()];

  const newer1 = {
    id: "brand-new-1", title: "Brand new 1", url: "https://eu.finalfantasyxiv.com/lodestone/news/detail/new-1",
    description: "New 1", image: "", category: "", publishedAt: "2026-08-21T12:00:00.000Z", publishedRaw: ""
  };
  const newer2 = {
    id: "brand-new-2", title: "Brand new 2", url: "https://eu.finalfantasyxiv.com/lodestone/news/detail/new-2",
    description: "New 2", image: "", category: "", publishedAt: "2026-08-22T12:00:00.000Z", publishedRaw: ""
  };
  const next = [newer2, newer1, ...initial.slice(0, 8)];
  await syncRssFeed({ feed: FEED, channelId, items: next, discord, store, logger });

  assert.equal(discord.created.length, 12);
  assert.equal(discord.deleted.length, 0);
  assert.equal(discord.edited.length, 0);
  for (const id of firstMessageIds) assert.ok(discord.messages.has(id));
  assert.ok(store.state.lodestone.feeds.news.messages["brand-new-1"]);
  assert.ok(store.state.lodestone.feeds.news.messages["brand-new-2"]);
});

test("known RSS items are never edited or recreated automatically", async () => {
  const store = new MemoryStore();
  const discord = new MockDiscord();
  const channelId = "123456789012345";
  const items = parseRssFeed(rss(10), FEED);
  await syncRssFeed({ feed: FEED, channelId, items, discord, store, logger });

  const tracked = store.state.lodestone.feeds.news.messages[items[0].id];
  discord.messages.delete(tracked.messageId);
  const before = discord.created.length;

  const changed = items.map(item => item.id === items[0].id ? { ...item, description: "Changed official description" } : item);
  await syncRssFeed({ feed: FEED, channelId, items: changed, discord, store, logger });

  assert.equal(discord.created.length, before);
  assert.equal(discord.edited.length, 0);
  assert.equal(discord.deleted.length, 0);
});

test("changing the configured channel never deletes or republishes old Lodestone entries", async () => {
  const store = new MemoryStore();
  const discord = new MockDiscord();
  const items = parseRssFeed(rss(10), FEED);
  await syncRssFeed({ feed: FEED, channelId: "123456789012345", items, discord, store, logger });
  const createdBefore = discord.created.length;

  await syncRssFeed({ feed: FEED, channelId: "223456789012345", items, discord, store, logger });
  assert.equal(discord.created.length, createdBefore);
  assert.equal(discord.deleted.length, 0);
  assert.equal(store.state.lodestone.feeds.news.channelId, "223456789012345");
});
