# Changelog

## 1.1.7

- Event announcements configuration loading is more robust and logs the effective configuration.
- The real config/state file paths are logged at startup.
- Regular Events/Polls messages are no longer crossposted.
- `eventAnnouncements.channelId` is now the only channel used for Discord Announcement publishing.
- The summary message is crossposted once after creation/recreation and its crosspost state is persisted/retried.
- Added `eventAnnouncements.publishAnnouncements` (default `true`).
- Accepts `eventAnnouncements`, `eventAnnouncement`, `announcements`, or `events.announcements` configuration blocks for compatibility.


## 1.1.6

- Added an optional persistent **Available Events** summary message for a dedicated Discord announcements channel.
- Fixed bilingual title: `Evénements disponibles / Available Events`.
- Lists Events only (Polls are excluded), sorted by start time, with start/end Discord timestamps.
- Adds a configurable Discord link and a configurable illustration image at the end of the embed.
- The summary is created once and then edited in place when the available Event list or configured link/image changes.
- If the tracked summary message is manually deleted, Crystal Planner recreates it without clearing the channel.
- JSON state schema bumped to version 4 to persist the summary message ID/hash.

## 1.1.5

- Fixed Lodestone article links so Discord titles and the explicit Link field always target the official Lodestone article page, never an enclosure/image URL.
- Atom-style `rel="alternate"` article links are preferred while `rel="enclosure"` links are excluded from article navigation.
- Added validation for official `/lodestone/news/detail/...` and `/lodestone/topics/detail/...` URLs with an image-URL rejection fallback.
- Lodestone RSS artwork now supports both RSS 2.0 `enclosure url="..."` and RSS 1.0/RDF `enclosure rdf:resource="..."` forms, plus `resource`, `href`, `src`, namespaced enclosure elements, and existing media/HTML fallbacks.
- News still intentionally omits description text but now keeps the enclosure artwork as the final embed image.
- Added a regression test reproducing a feed item where an enclosure image link appears before the real article link.

## 1.1.4

- Added a minimal Discord Gateway connection used only for bot presence.
- Crystal Planner now appears Online (green dot) while the service is running and connected.
- No activity text is configured: no Playing, Watching, Listening or custom status.
- Existing Discord message synchronization remains REST-based.
- Gateway shutdown is handled cleanly when systemd sends SIGTERM/SIGINT; Discord then marks the bot Offline.
- The Debian installer now installs the lightweight `ws` WebSocket dependency automatically; Node.js 20+ support is preserved.
- Added `scripts/update-1.1.4.sh` for a safe in-place upgrade from 1.1.3 without replacing configuration, token or state files.

## 1.1.3

- Lodestone: `<enclosure>` is now the authoritative/priority source for RSS artwork.
- Lodestone: enclosure URLs are accepted from quoted/unquoted `url`, `href` or `src` attributes, with a body-URL fallback.
- Lodestone News (`news.xml`): the RSS description text is no longer displayed in Discord; title, link, timestamp and image remain.
- Lodestone Topics (`topics.xml`): cleaned description text and image continue to be displayed.

## 1.1.2

- Fixed Lodestone RSS descriptions containing XML-escaped HTML: HTML entities are decoded before tags are removed, so Discord receives clean text instead of `<p>`, `<br>`, `<a>`, etc.
- Fixed Lodestone RSS image extraction when `<img>` markup is XML-escaped in `description` or `content:encoded`.
- Image extraction still supports RSS `enclosure`, `media:content`, and `media:thumbnail`.
- Lodestone synchronization remains append-only; no existing Discord message is automatically deleted or rewritten.

## 1.1.1

- Lodestone RSS synchronization is now append-only.
- First synchronization publishes the latest 10 News and latest 10 Topics as the initial baseline.
- Later synchronizations publish only RSS entries never seen before.
- Existing Lodestone Discord messages are never automatically deleted when they leave the RSS feed.
- Existing Lodestone Discord messages are never automatically edited or recreated.
- Changing the configured Lodestone channel does not delete or republish old RSS messages; only future entries go to the new channel.
- State schema upgraded to version 3 with per-feed `initialized`, `seenIds` and migration baseline tracking.
- Version 1.1.0 state files are upgraded without deleting their existing Discord messages.

## 1.1.0

- Lodestone synchronization now uses only the official Square Enix RSS feeds:
  - `https://eu.finalfantasyxiv.com/lodestone/news/news.xml`
  - `https://eu.finalfantasyxiv.com/lodestone/news/topics.xml`
- The bot maintains the latest 10 RSS items per feed at all times.
- When a new item arrives, only the item leaving the Top 10 is removed.
- Existing RSS messages are edited when the official RSS payload changes and recreated if manually deleted.
- RSS data (title, link, publication date, description and image when present) is used directly; article/category HTML pages are no longer scraped.
- Lodestone configuration is simplified to `newsChannelId` and `topicsChannelId`.
- State schema upgraded to version 2 with per-feed Discord message IDs and hashes.
- Existing v1.0 state files are upgraded automatically.
- Android migration maps the legacy Notices/Maintenance/Updates channels to the new single News RSS channel.

## 1.0.0

- Initial Debian/Node.js/systemd server release.
- REST-only Discord synchronization.
- Events/Polls individual create/edit/delete and announcement crosspost.
- Rules, Guides and Macros boards.
- Lodestone synchronization.
- Silent Discord publications using `SUPPRESS_NOTIFICATIONS`.
- JSON state with atomic writes.
