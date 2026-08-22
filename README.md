# Crystal Planner Server

Crystal Planner Server is the Debian/Linux edition of Crystal Planner. It replaces the Android/Java runtime with a Node.js service managed by `systemd`, while keeping the Discord synchronization behavior and JSON-based storage.

## Included features

- **Events / Polls**: calls `generate_discord_bot_datas.php`, reads `discord-bot-datas.json`, creates one persistent Discord message per entry, edits only the entry that changed, and deletes only an entry that disappeared.
- **Announcement channels**: a newly-created Event/Poll is crossposted once. If Discord rejects the crosspost temporarily (including rate limiting), the original message is preserved and crossposting is retried during a later synchronization.
- **Rules**: `discord-rules.json`.
- **Guides**: `discord-guides.json`; the guide URL remains on the embed and is also shown in the footer of the same embed, below the image.
- **Macros**: `discord-macros.json`.
- **Lodestone RSS**: News and Topics from the two official Square Enix RSS feeds, with append-only anti-duplicate history.
- **Silent publications**: every newly-created message uses Discord `SUPPRESS_NOTIFICATIONS` (`4096`) and `allowed_mentions.parse = []`.
- **JSON-only local state**: no SQL database.
- **Atomic state writes**: state is written to a temporary JSON file, validated, then renamed into place. If the live state is ever unreadable, the service backs it up and refuses to perform Discord changes rather than starting destructively from an empty history.
- **Discord REST API only**: no Gateway presence is needed, so the bot may appear Offline in Discord even while synchronization is working normally.

## Requirements

- Debian 12/13 or another recent Linux distribution.
- Node.js **20 or newer**. No npm package is required at runtime.
- A Discord bot token with the permissions required for the configured channels.
- Dedicated Discord channels are strongly recommended because Rules/Guides/Macros synchronization clears the configured channel when their source JSON changes.

## Quick installation on Debian

Extract the archive, enter the project directory, then:

```bash
sudo ./scripts/install-debian.sh
```

The installer creates:

```text
/opt/crystal-planner/                 application
/etc/crystal-planner/config.json      configuration
/etc/crystal-planner/crystal-planner.env  Discord token
/var/lib/crystal-planner/state.json   persistent JSON state
/etc/systemd/system/crystal-planner.service
```

Then edit the token:

```bash
sudo nano /etc/crystal-planner/crystal-planner.env
```

Set:

```text
DISCORD_TOKEN=your_real_bot_token
```

Edit the channels if they were not migrated:

```bash
sudo nano /etc/crystal-planner/config.json
```

Validate before starting:

```bash
sudo -u crystalplanner env $(grep -v '^#' /etc/crystal-planner/crystal-planner.env | xargs) \
  CRYSTAL_PLANNER_CONFIG=/etc/crystal-planner/config.json \
  CRYSTAL_PLANNER_STATE=/var/lib/crystal-planner/state.json \
  node /opt/crystal-planner/src/index.js check
```

Start and enable at boot:

```bash
sudo systemctl start crystal-planner
sudo systemctl enable crystal-planner
```

Logs:

```bash
journalctl -u crystal-planner -f
```

Status:

```bash
systemctl status crystal-planner
```

Restart after changing configuration:

```bash
sudo systemctl restart crystal-planner
```

## Configuration

Example:

```json
{
  "syncIntervalMinutes": 15,
  "webFolderUrl": "https://alpasnet.eu/cozy_events",
  "jsonReadDelaySeconds": 3,
  "events": {
    "enabled": true,
    "channelId": "123456789012345678",
    "crosspostAnnouncements": true
  },
  "rules": {
    "enabled": true,
    "channelId": "123456789012345678"
  },
  "guides": {
    "enabled": true,
    "channelId": "123456789012345678"
  },
  "macros": {
    "enabled": true,
    "channelId": "123456789012345678"
  },
  "lodestone": {
    "enabled": true,
    "newsChannelId": "123456789012345678",
    "topicsChannelId": "123456789012345679"
  }
}
```

The Web folder is expected to contain fixed filenames:

```text
generate_discord_bot_datas.php
discord-bot-datas.json
discord-rules.json
discord-guides.json
discord-macros.json
```

Lodestone uses the official RSS feeds directly. On first start it publishes the latest 10 items from each configured feed. After that, it only appends RSS entries that have never been seen before. Existing Discord messages are never removed, edited or recreated automatically.

## Events / Polls synchronization

The Events/Polls board no longer clears the whole channel after every change.

```text
New entry      -> POST message -> save message ID -> crosspost
Changed entry  -> PATCH existing message
Missing entry  -> DELETE only that message
Unchanged      -> nothing
```

The mapping is stored in `state.json`:

```json
{
  "events": {
    "channelId": "123456789012345678",
    "initialized": true,
    "messages": {
      "id-42": {
        "message_id": "1478923456789012345",
        "hash": "...",
        "crossposted": true,
        "channel_id": "123456789012345678"
      }
    }
  }
}
```

If the Web JSON exposes `event_id`, `eventId` or `id`, it is used as the durable identity. For compatibility with the current generator, the server can fall back to the event image URL, then to title + first field.

### First synchronization without imported Event history

The configured Events/Polls channel is cleared **once** to migrate from the old clear-and-republish model. After initialization, only targeted create/edit/delete operations are used.

## Discord rate limits

Normal Discord REST operations retry HTTP `429` responses up to five attempts and honor Discord's `retry_after` value (with an individual wait capped at 120 seconds).

Announcement crossposts are intentionally attempted only once per synchronization cycle. If a crosspost fails, its state remains `crossposted: false`; the next cycle retries it without recreating the Event/Poll message.

## Import settings/history from the Android app

Export Crystal Planner settings from Android **with internal history included**, copy the JSON backup to the VPS, and run before starting the service:

```bash
node scripts/migrate-android-backup.mjs /path/to/android-backup.json \
  --config /tmp/config.json \
  --state /tmp/state.json
```

Then install/copy the generated files:

```bash
sudo install -m 0640 -o root -g crystalplanner /tmp/config.json /etc/crystal-planner/config.json
sudo install -m 0600 -o crystalplanner -g crystalplanner /tmp/state.json /var/lib/crystal-planner/state.json
```

The Discord token is deliberately not part of Android backups and is never migrated. Put it in `/etc/crystal-planner/crystal-planner.env`.

Importing the Event history preserves existing Discord message IDs, so the Linux service can continue editing those messages instead of rebuilding the channel.

## Manual commands

One synchronization:

```bash
node src/index.js sync --config config/config.json --state data/state.json
```

Validate token/configuration:

```bash
node src/index.js check --config config/config.json --state data/state.json
```

Clear all configured Lodestone channels:

```bash
node src/index.js clear-lodestone --config config/config.json --state data/state.json
```

Run continuously without systemd:

```bash
node src/index.js run --config config/config.json --state data/state.json
```

## Permissions expected on Discord

At minimum, the bot needs access to the configured channels and permission to send messages. Features that delete/rebuild dedicated board channels require message-management permissions. Event/Poll announcement crossposting requires the appropriate permissions in the Announcement channel.

## Security notes

- Never place the real Discord token in `config.json`.
- `/etc/crystal-planner/crystal-planner.env` should not be world-readable.
- The included systemd unit runs as the unprivileged `crystalplanner` user and only grants write access to `/var/lib/crystal-planner`.
- All Web source URLs are required to use HTTPS.


## Lodestone RSS (1.1.3)

For RSS artwork, Crystal Planner prioritizes the official `<enclosure>` URL. **News** embeds intentionally omit the RSS description text and show the title/link/date/image only. **Topics** keep the cleaned description text and image.

Lodestone publications are read exclusively from the official Square Enix RSS feeds:

- News: `https://eu.finalfantasyxiv.com/lodestone/news/news.xml`
- Topics: `https://eu.finalfantasyxiv.com/lodestone/news/topics.xml`

Configure one Discord channel for each feed:

```json
"lodestone": {
  "enabled": true,
  "newsChannelId": "123456789012345678",
  "topicsChannelId": "123456789012345679"
}
```

On the **first synchronization only**, Crystal Planner publishes the 10 latest items from each feed to seed the configured Discord channel. From then on, synchronization is **append-only**: only RSS entries with an ID never seen before are published. Existing Discord messages are never deleted when they leave the RSS feed, never edited when an RSS item changes, and never recreated if someone deletes them manually. All new Discord messages remain silent (`SUPPRESS_NOTIFICATIONS`).

For **Topics**, RSS descriptions are decoded from XML/HTML entities and converted to clean Discord text. For **News**, the description is parsed only as a fallback source for an image and is not displayed. HTML tags are never intentionally sent to Discord. The parser prioritizes the official RSS `enclosure` image, then falls back to `media:content`, `media:thumbnail`, or an `<img src>` contained in RSS description/content.

The old HTML category scraping (`Notices`, `Maintenance`, `Updates`) is no longer used.
