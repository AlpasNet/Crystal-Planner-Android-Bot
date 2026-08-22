# Changelog

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
