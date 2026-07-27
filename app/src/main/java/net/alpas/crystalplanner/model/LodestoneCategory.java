package net.alpas.crystalplanner.model;

public final class LodestoneCategory {
    public final String key;
    public final String label;
    public final String url;
    public final String channelId;
    public final int color;
    public final String emoji;

    public LodestoneCategory(
            String key,
            String label,
            String url,
            String channelId,
            int color,
            String emoji
    ) {
        this.key = key;
        this.label = label;
        this.url = url;
        this.channelId = channelId == null ? "" : channelId.trim();
        this.color = color;
        this.emoji = emoji;
    }
}
