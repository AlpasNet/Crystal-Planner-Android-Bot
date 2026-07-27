package net.alpas.crystalplanner.sync;

import android.content.Context;

import net.alpas.crystalplanner.R;
import net.alpas.crystalplanner.discord.DiscordApi;
import net.alpas.crystalplanner.model.LodestoneCategory;
import net.alpas.crystalplanner.storage.StateStore;
import net.alpas.crystalplanner.util.HttpClient;
import net.alpas.crystalplanner.util.PayloadNormalizer;
import net.alpas.crystalplanner.util.SyncLog;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class LodestoneSync {
    private static final Pattern MONTH = Pattern.compile(
            "\\b(?:Jan\\.?|January|Feb\\.?|February|Mar\\.?|March|Apr\\.?|April|May|Jun\\.?|June|Jul\\.?|July|Aug\\.?|August|Sep\\.?|Sept\\.?|September|Oct\\.?|October|Nov\\.?|November|Dec\\.?|December)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YEAR = Pattern.compile("\\b20\\d{2}\\b");

    private final Context context;
    private final HttpClient http;
    private final DiscordApi discord;
    private final StateStore state;
    private final SyncLog log;

    public LodestoneSync(Context context, HttpClient http, DiscordApi discord, StateStore state, SyncLog log) {
        this.context = context.getApplicationContext();
        this.http = http;
        this.discord = discord;
        this.state = state;
        this.log = log;
    }

    public int syncCategory(LodestoneCategory category) throws Exception {
        if (category.channelId.trim().isEmpty()) {
            log.info(context.getString(R.string.log_lodestone_skipped, category.label));
            return 0;
        }

        List<NewsItem> current = fetchCategoryNews(category);
        List<String> seen = state.getSeen(category.key);
        List<NewsItem> fresh = new ArrayList<>();
        for (NewsItem item : current) {
            if (!seen.contains(item.id)) fresh.add(item);
        }

        int sent = 0;
        for (int i = fresh.size() - 1; i >= 0; i--) {
            NewsItem item = fresh.get(i);
            JSONObject payload = buildPayload(item, fetchArticleDetails(item));
            discord.sendMessage(category.channelId, payload);
            seen.add(item.id);
            state.saveSeen(category.key, seen);
            sent++;
            Thread.sleep(800L);
        }

        log.info(context.getString(
                R.string.log_lodestone_result,
                category.label,
                current.size(),
                sent
        ));
        return sent;
    }

    private List<NewsItem> fetchCategoryNews(LodestoneCategory category) throws Exception {
        HttpClient.Response response = http.get(category.url, null);
        requireSuccess(response, "Lodestone category " + category.label);
        Document document = Jsoup.parse(response.body, category.url);
        Map<String, NewsItem> unique = new LinkedHashMap<>();

        Elements links = document.select(
                "a[href*='/lodestone/topics/detail/'], a[href*='/lodestone/news/detail/']"
        );
        for (Element link : links) {
            String href = link.absUrl("href");
            if (href.trim().isEmpty()) href = absoluteUrl(link.attr("href"), category.url);
            if (href.trim().isEmpty()) continue;
            if (!href.contains("/lodestone/topics/detail/")
                    && !href.contains("/lodestone/news/detail/")) continue;

            Element container = link.closest("li, article, .news__list, .news__list--wrapper, .topics__list");
            String title = firstNonBlank(
                    text(link.selectFirst(".news__list--title")),
                    text(link.selectFirst(".entry__title")),
                    text(container == null ? null : container.selectFirst(".news__list--title")),
                    text(container == null ? null : container.selectFirst(".entry__title")),
                    link.attr("title"),
                    link.text()
            );
            title = cleanTitle(title);
            if (title.length() < 4) continue;

            Element imageElement = link.selectFirst("img");
            if (imageElement == null && container != null) imageElement = container.selectFirst("img");
            String image = imageElement == null ? "" : imageElement.absUrl("src");
            if (image.trim().isEmpty() && imageElement != null) {
                image = absoluteUrl(imageElement.attr("src"), category.url);
            }

            unique.putIfAbsent(href, new NewsItem(
                    href,
                    title,
                    href,
                    image,
                    category
            ));
            if (unique.size() >= 10) break;
        }
        return new ArrayList<>(unique.values());
    }

    private ArticleDetails fetchArticleDetails(NewsItem item) {
        try {
            HttpClient.Response response = http.get(item.url, null);
            requireSuccess(response, "Lodestone article");
            Document document = Jsoup.parse(response.body, item.url);
            document.select("script,style,noscript").remove();

            String title = firstNonBlank(
                    meta(document, "meta[property=og:title]"),
                    meta(document, "meta[name=twitter:title]"),
                    document.title()
            );
            String metaDescription = firstNonBlank(
                    meta(document, "meta[property=og:description]"),
                    meta(document, "meta[name=description]"),
                    meta(document, "meta[name=twitter:description]")
            );
            String image = firstNonBlank(
                    meta(document, "meta[property=og:image]"),
                    meta(document, "meta[name=twitter:image]")
            );
            image = absoluteUrl(image, item.url);

            Element article = firstElement(
                    document.selectFirst(".news__detail__wrapper"),
                    document.selectFirst(".news__detail"),
                    document.selectFirst(".topics__detail"),
                    document.selectFirst("article"),
                    document.selectFirst("main")
            );

            String description = "";
            String dateTime = "";
            if ("topics".equals(item.category.key) || "notices".equals(item.category.key)) {
                description = extractFirstParagraph(article);
                if (description.trim().isEmpty()) description = limit(metaDescription, 900);
            } else {
                dateTime = extractDateTimeSection(article == null ? "" : article.wholeText());
                description = limit(metaDescription, 500);
                if (description.trim().isEmpty()) description = "New Lodestone publication available.";
            }

            return new ArticleDetails(cleanTitle(title), description, image, dateTime);
        } catch (Exception error) {
            log.warn(context.getString(
                    R.string.log_lodestone_article_error,
                    item.url,
                    error.getMessage()
            ));
            return new ArticleDetails("", "", "", "");
        }
    }

    private JSONObject buildPayload(NewsItem item, ArticleDetails details) throws Exception {
        String finalTitle = firstNonBlank(details.title, item.title);
        String finalDescription = firstNonBlank(
                details.description,
                "New Lodestone publication available."
        );
        String finalImage = firstNonBlank(details.image, item.image);

        JSONObject embed = new JSONObject();
        JSONObject author = new JSONObject();
        author.put("name", "The Lodestone - " + item.category.label);
        embed.put("author", author);
        embed.put("title", item.category.emoji + " " + cleanTitle(finalTitle));
        embed.put("url", item.url);
        embed.put("description", limit(finalDescription, 4096));
        embed.put("color", item.category.color);
        embed.put("timestamp", Instant.now().toString());

        JSONObject footer = new JSONObject();
        footer.put("text", "FINAL FANTASY XIV - The Lodestone");
        embed.put("footer", footer);

        JSONArray fields = new JSONArray();
        JSONObject link = new JSONObject();
        link.put("name", "Link");
        link.put("value", "[Open the article on The Lodestone](" + item.url + ")");
        link.put("inline", false);
        fields.put(link);

        if (!details.dateTime.trim().isEmpty()) {
            JSONObject date = new JSONObject();
            date.put("name", "[Date & Time]");
            date.put("value", limit(details.dateTime, 1000));
            date.put("inline", false);
            fields.put(date);
        }
        embed.put("fields", fields);

        if (!finalImage.trim().isEmpty()) {
            JSONObject image = new JSONObject();
            image.put("url", finalImage);
            embed.put("image", image);
        }

        JSONObject payload = new JSONObject();
        payload.put("embeds", new JSONArray().put(embed));
        return PayloadNormalizer.normalizeMessage(payload);
    }

    private static String extractFirstParagraph(Element article) {
        if (article == null) return "";
        List<String> blocked = Arrays.asList(
                "News", "Topics", "Notices", "Maintenance", "Updates", "Status",
                "Patch Notes", "Special Sites", "The Lodestone", "FINAL FANTASY XIV"
        );
        for (Element paragraph : article.select("p")) {
            String text = paragraph.text().trim();
            if (text.length() < 40) continue;
            boolean skip = false;
            for (String start : blocked) {
                if (text.startsWith(start)) {
                    skip = true;
                    break;
                }
            }
            if (!skip && !text.contains("JavaScript") && !text.contains("window.")) {
                return limit(text, 900);
            }
        }
        return "";
    }

    private static String extractDateTimeSection(String source) {
        if (source == null || source.trim().isEmpty()) return "";
        String cleaned = source.replace("\r", "").trim();
        int marker = cleaned.indexOf("[Date & Time]");
        if (marker < 0) return "";
        String tail = cleaned.substring(marker + "[Date & Time]".length()).trim();
        String[] stops = {
                "[Affected Service]", "[Affected Worlds]", "[Details]", "[Update Details]",
                "[Maintenance Details]", "[Recovery Details]", "[Issue Details]", "[Cause]",
                "[Countermeasures]", "[In-game Content]", "[Companion App]", "[Known Issues]"
        };
        int stop = -1;
        for (String value : stops) {
            int index = tail.indexOf(value);
            if (index >= 0 && (stop < 0 || index < stop)) stop = index;
        }
        if (stop >= 0) tail = tail.substring(0, stop).trim();

        List<String> lines = new ArrayList<>();
        for (String line : tail.split("\\n")) {
            String value = line.trim();
            if (!value.isEmpty() && MONTH.matcher(value).find() && YEAR.matcher(value).find()) {
                lines.add(value);
            }
        }
        return limit(String.join("\n", lines), 1000);
    }

    private static String meta(Document document, String selector) {
        Element element = document.selectFirst(selector);
        return element == null ? "" : element.attr("content").trim();
    }

    private static String absoluteUrl(String value, String base) {
        if (value == null || value.trim().isEmpty()) return "";
        try {
            return new URL(new URL(base), value).toString();
        } catch (Exception ignored) {
            return value.startsWith("https://") ? value : "";
        }
    }

    private static String text(Element element) {
        return element == null ? "" : element.text();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value.trim();
        }
        return "";
    }

    private static Element firstElement(Element... values) {
        for (Element value : values) if (value != null) return value;
        return null;
    }

    private static String cleanTitle(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String limit(String text, int max) {
        return PayloadNormalizer.truncate(text, max);
    }

    private static void requireSuccess(HttpClient.Response response, String operation) {
        if (response.isSuccessful()) return;
        throw new IllegalStateException(operation + " failed: HTTP " + response.status);
    }

    private static final class NewsItem {
        final String id;
        final String title;
        final String url;
        final String image;
        final LodestoneCategory category;

        NewsItem(String id, String title, String url, String image, LodestoneCategory category) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.image = image == null ? "" : image;
            this.category = category;
        }
    }

    private static final class ArticleDetails {
        final String title;
        final String description;
        final String image;
        final String dateTime;

        ArticleDetails(String title, String description, String image, String dateTime) {
            this.title = title == null ? "" : title;
            this.description = description == null ? "" : description;
            this.image = image == null ? "" : image;
            this.dateTime = dateTime == null ? "" : dateTime;
        }
    }
}
