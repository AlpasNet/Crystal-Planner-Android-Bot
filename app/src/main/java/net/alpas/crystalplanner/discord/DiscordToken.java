package net.alpas.crystalplanner.discord;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes values copied from Discord, a .env file or a shell export.
 * The token itself must never be logged.
 */
public final class DiscordToken {
    private static final Pattern ENV_LINE = Pattern.compile(
            "^(?:export\\s+)?(?:DISCORD_TOKEN|DISCORD_BOT_TOKEN|BOT_TOKEN|TOKEN)\\s*=\\s*(.*)$",
            Pattern.CASE_INSENSITIVE
    );

    private DiscordToken() {
    }

    public static String normalize(String raw) {
        String value = raw == null ? "" : raw;
        value = value
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace('\u00A0', ' ')
                .trim();

        if (value.startsWith("```") && value.endsWith("```") && value.length() >= 6) {
            value = value.substring(3, value.length() - 3).trim();
            int firstLineBreak = value.indexOf('\n');
            if (firstLineBreak >= 0) {
                String firstLine = value.substring(0, firstLineBreak).trim().toLowerCase(Locale.ROOT);
                if (firstLine.equals("env") || firstLine.equals("dotenv") || firstLine.equals("text")) {
                    value = value.substring(firstLineBreak + 1).trim();
                }
            }
        }

        String envValue = findEnvironmentValue(value);
        if (envValue != null) {
            value = envValue;
        }

        value = stripMatchingWrapper(value.trim());
        if (value.regionMatches(true, 0, "Bot", 0, 3)
                && value.length() > 3
                && Character.isWhitespace(value.charAt(3))) {
            value = value.substring(4).trim();
        }
        value = stripMatchingWrapper(value.trim());
        return value.trim();
    }

    public static void requirePlausible(String raw) {
        requirePlausible(
                raw,
                "Discord token is empty.",
                "The entered value is too short to be a Discord bot token.",
                "The token contains an unexpected space or line break."
        );
    }

    public static void requirePlausible(
            String raw,
            String emptyMessage,
            String tooShortMessage,
            String whitespaceMessage
    ) {
        String token = normalize(raw);
        if (token.isEmpty()) {
            throw new IllegalArgumentException(emptyMessage);
        }
        if (token.length() < 30) {
            throw new IllegalArgumentException(tooShortMessage);
        }
        for (int i = 0; i < token.length(); i++) {
            if (Character.isWhitespace(token.charAt(i))) {
                throw new IllegalArgumentException(whitespaceMessage);
            }
        }
    }

    private static String findEnvironmentValue(String value) {
        String[] lines = value.split("\\r?\\n");
        for (String line : lines) {
            Matcher matcher = ENV_LINE.matcher(line.trim());
            if (matcher.matches()) {
                return matcher.group(1).trim();
            }
        }
        Matcher direct = ENV_LINE.matcher(value.trim());
        return direct.matches() ? direct.group(1).trim() : null;
    }

    private static String stripMatchingWrapper(String value) {
        if (value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '`' && last == '`')) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
}
