package net.alpas.crystalplanner.util;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

public final class HttpClient {
    public static final class Response {
        public final int status;
        public final String body;
        public final Map<String, List<String>> headers;

        Response(int status, String body, Map<String, List<String>> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }

        public boolean isSuccessful() {
            return status >= 200 && status < 300;
        }

        public String header(String name) {
            if (name == null || headers == null) return null;
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                    List<String> values = entry.getValue();
                    return values == null || values.isEmpty() ? null : values.get(0);
                }
            }
            return null;
        }
    }

    private static final String USER_AGENT = "DiscordBot (https://github.com/AlpasNet/Crystal-Planner, 1.0.17)";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient OK_HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(35, TimeUnit.SECONDS)
            .build();

    public Response get(String url, String authorization) throws Exception {
        return request("GET", url, authorization, null, 5);
    }

    public Response postJson(String url, String authorization, JSONObject body) throws Exception {
        return request("POST", url, authorization, body == null ? "{}" : body.toString(), 5);
    }

    public Response delete(String url, String authorization) throws Exception {
        return request("DELETE", url, authorization, null, 5);
    }

    public Response patchJson(String url, String authorization, JSONObject body) throws Exception {
        return patchJsonWithOkHttp(url, authorization, body == null ? "{}" : body.toString(), 5);
    }

    private Response patchJsonWithOkHttp(
            String url,
            String authorization,
            String jsonBody,
            int maxAttempts
    ) throws Exception {
        validateHttps(url);
        int attempt = 0;
        while (true) {
            attempt++;
            RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);
            Request.Builder builder = new Request.Builder()
                    .url(url)
                    .patch(body)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json,text/plain,*/*");
            if (authorization != null && !authorization.trim().isEmpty()) {
                builder.header("Authorization", authorization);
            }

            Response wrapped;
            try (okhttp3.Response response = OK_HTTP.newCall(builder.build()).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                wrapped = new Response(response.code(), responseBody, response.headers().toMultimap());
            }

            if (wrapped.status != 429 || attempt >= Math.max(1, maxAttempts)) {
                return wrapped;
            }

            long waitMs = parseRetryAfterMs(wrapped);
            Thread.sleep(Math.min(Math.max(waitMs, 250L), 120_000L));
        }
    }

    public Response request(
            String method,
            String url,
            String authorization,
            String jsonBody,
            int maxAttempts
    ) throws Exception {
        validateHttps(url);
        int attempt = 0;
        while (true) {
            attempt++;
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(35_000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json,text/html,text/plain,*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (authorization != null && !authorization.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", authorization);
            }

            if (jsonBody != null) {
                byte[] data = jsonBody.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(data);
                }
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readAll(stream);
            Map<String, List<String>> headers = connection.getHeaderFields();
            connection.disconnect();

            Response response = new Response(
                    status,
                    body,
                    headers == null ? Collections.emptyMap() : headers
            );

            if (status != 429 || attempt >= Math.max(1, maxAttempts)) {
                return response;
            }

            long waitMs = parseRetryAfterMs(response);
            Thread.sleep(Math.min(Math.max(waitMs, 250L), 120_000L));
        }
    }

    private static long parseRetryAfterMs(Response response) {
        try {
            String header = response.header("Retry-After");
            if (header != null) {
                double seconds = Double.parseDouble(header.trim());
                return (long) Math.ceil(seconds * 1000.0);
            }
        } catch (Exception ignored) {
        }
        try {
            JSONObject object = new JSONObject(response.body);
            return (long) Math.ceil(object.optDouble("retry_after", 1.0) * 1000.0);
        } catch (Exception ignored) {
            return 1000L;
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                result.append(buffer, 0, read);
            }
        }
        return result.toString();
    }

    private static void validateHttps(String rawUrl) {
        try {
            URI uri = URI.create(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IllegalArgumentException("Only valid HTTPS URLs are accepted: " + rawUrl);
            }
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid HTTPS URL: " + rawUrl, error);
        }
    }
}
