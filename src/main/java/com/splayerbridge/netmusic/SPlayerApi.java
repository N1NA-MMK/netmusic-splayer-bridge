package com.splayerbridge.netmusic;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SPlayer 本地 HTTP API 客户端。
 * <p>
 * 只用到解锁接口，例如：
 * <pre>GET http://127.0.0.1:25884/api/unblock/netease?id=4932606</pre>
 * 返回：<code>{"code":200,"url":"https://...flac"}</code>
 */
public final class SPlayerApi {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 同一首歌的解锁请求合并，避免循环播放时重复请求。 */
    private static final Map<Long, CompletableFuture<Optional<String>>> IN_FLIGHT = new ConcurrentHashMap<>();

    /** 失败后的短暂退避，避免同一首歌在循环播放时反复请求 SPlayer。 */
    private static final Map<Long, Long> FAILED_AT = new ConcurrentHashMap<>();
    private static final long FAIL_BACKOFF_MILLIS = 20_000L;

    private static volatile boolean alive = false;
    private static volatile long lastProbeMillis = 0L;
    private static volatile String lastError = "";

    private SPlayerApi() {
    }

    public static boolean isAlive() {
        return alive;
    }

    public static long lastProbeMillis() {
        return lastProbeMillis;
    }

    public static String lastError() {
        return lastError;
    }

    /**
     * 请求解锁后的音频直链。
     *
     * @param songId   网易云歌曲 ID
     * @param songName 歌名（关键词兜底时使用，可为空）
     * @param artist   第一位歌手（关键词兜底时使用，可为空）
     */
    public static CompletableFuture<Optional<String>> unlock(long songId, String songName, String artist) {
        CompletableFuture<Optional<String>> running = IN_FLIGHT.get(songId);
        if (running != null) {
            return running;
        }
        CompletableFuture<Optional<String>> future =
                CompletableFuture.supplyAsync(() -> doUnlock(songId, songName, artist), Async.api());
        CompletableFuture<Optional<String>> previous = IN_FLIGHT.putIfAbsent(songId, future);
        if (previous != null) {
            return previous;
        }
        future.whenComplete((result, throwable) -> IN_FLIGHT.remove(songId, future));
        return future;
    }

    /**
     * 探测 SPlayer 是否在线，返回是否可达。会阻塞当前线程，仅用于后台定时任务。
     */
    public static boolean probe() {
        lastProbeMillis = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(URI.create(SPlayerBridge.config().splayerBaseUrl + "/api"))
                .timeout(Duration.ofMillis(1200))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            alive = true;
            lastError = "HTTP " + response.statusCode();
            return true;
        } catch (Exception e) {
            alive = false;
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            return false;
        }
    }

    private static Optional<String> doUnlock(long songId, String songName, String artist) {
        Long failedAt = FAILED_AT.get(songId);
        if (failedAt != null) {
            if (System.currentTimeMillis() - failedAt < FAIL_BACKOFF_MILLIS) {
                return Optional.empty();
            }
            FAILED_AT.remove(songId);
        }

        BridgeConfig config = SPlayerBridge.config();
        long deadline = System.nanoTime() + config.totalResolveTimeoutSeconds * 1_000_000_000L;

        for (String endpoint : config.unlockEndpoints) {
            String url = config.splayerBaseUrl + expand(endpoint, songId, songName, artist);
            Optional<String> result = requestUrl(url, deadline);
            if (result.isPresent()) {
                FAILED_AT.remove(songId);
                return result;
            }
        }

        if (config.keywordFallback && songName != null && !songName.isBlank()) {
            String artistName = artist == null ? "" : artist;
            String keyword = artistName.isBlank() ? songName : songName + "-" + artistName;
            for (String endpoint : config.keywordEndpoints) {
                String path = endpoint
                        .replace("{keyword}", encode(keyword))
                        .replace("{name}", encode(songName))
                        .replace("{artist}", encode(artistName));
                Optional<String> result = requestUrl(config.splayerBaseUrl + path, deadline);
                if (result.isPresent()) {
                    FAILED_AT.remove(songId);
                    return result;
                }
            }
        }

        FAILED_AT.put(songId, System.currentTimeMillis());
        return Optional.empty();
    }

    private static String expand(String endpoint, long songId, String songName, String artist) {
        String path = endpoint.replace("{id}", Long.toString(songId));
        if (songName != null) {
            path = path.replace("{name}", encode(songName));
        }
        if (artist != null) {
            path = path.replace("{artist}", encode(artist));
        }
        return path;
    }

    private static Optional<String> requestUrl(String url, long deadlineNanos) {
        BridgeConfig config = SPlayerBridge.config();
        long remainingMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        if (remainingMillis < 200) {
            return Optional.empty();
        }
        long timeoutMillis = Math.min(remainingMillis, config.requestTimeoutSeconds * 1000L);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                return Optional.empty();
            }
            Optional<String> audioUrl = parseAudioUrl(response.body());
            if (audioUrl.isPresent()) {
                alive = true;
                return audioUrl;
            }
            return Optional.empty();
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (config.logCacheHits) {
                SPlayerBridge.LOGGER.debug("[SPlayerBridge] 解锁请求失败 {} -> {}", url, lastError);
            }
            return Optional.empty();
        }
    }

    /**
     * 兼容多种返回结构：{url:...}、{data:{url:...}}、{data:[{url:...}]}。
     */
    private static Optional<String> parseAudioUrl(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonElement root = JsonParser.parseString(body);
            if (!root.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject object = root.getAsJsonObject();
            String direct = readUrl(object);
            if (direct != null) {
                return Optional.of(direct);
            }
            JsonElement data = object.get("data");
            if (data != null) {
                if (data.isJsonObject()) {
                    String nested = readUrl(data.getAsJsonObject());
                    if (nested != null) {
                        return Optional.of(nested);
                    }
                } else if (data.isJsonArray()) {
                    JsonArray array = data.getAsJsonArray();
                    for (JsonElement element : array) {
                        if (element.isJsonObject()) {
                            String nested = readUrl(element.getAsJsonObject());
                            if (nested != null) {
                                return Optional.of(nested);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            lastError = "JSON 解析失败: " + e.getMessage();
        }
        return Optional.empty();
    }

    private static String readUrl(JsonObject object) {
        JsonElement url = object.get("url");
        if (url == null || url.isJsonNull() || !url.isJsonPrimitive()) {
            return null;
        }
        String value = url.getAsString();
        if (value == null) {
            return null;
        }
        value = value.trim();
        if (value.isEmpty() || "null".equals(value.toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return null;
        }
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
