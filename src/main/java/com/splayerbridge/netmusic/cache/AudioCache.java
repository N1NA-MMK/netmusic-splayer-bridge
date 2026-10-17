package com.splayerbridge.netmusic.cache;

import com.splayerbridge.netmusic.Async;
import com.splayerbridge.netmusic.BridgeConfig;
import com.splayerbridge.netmusic.SPlayerBridge;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 服务端音频缓存 + 下载中转。
 * <p>
 * 文件命名：<code>&lt;歌曲ID&gt;.&lt;扩展名&gt;</code>。
 * 下载过程中会同时存在一个空的 <code>&lt;ID&gt;.&lt;ext&gt;.part</code> 标记文件，
 * 下载完成才删除标记；下次启动时发现标记还在一律视为没下完，直接清掉。
 * <p>
 * 之所以不先写 .part 再改名：客户端是在下载过程中就通过内置 HTTP 服务读取这个文件的，
 * Windows 上重命名被打开的文件会失败。所以文件从头到尾都叫最终名字，用标记文件表示是否完整。
 */
public final class AudioCache {
    private static final Pattern CACHE_FILE = Pattern.compile("^(\\d+)\\.([a-z0-9]{2,5})$");
    private static final Pattern PART_FILE = Pattern.compile("^(\\d+)\\.([a-z0-9]{2,5})\\.part$");
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;
    /** 下载多久没有新数据就判定为卡死。 */
    private static final long DOWNLOAD_STALL_MILLIS = 30_000L;
    private static final Set<String> AUDIO_EXTENSIONS =
            Set.of("mp3", "flac", "m4a", "aac", "wav", "ogg", "ape", "wma");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 已完整下载的歌曲。 */
    private static final Map<Long, Path> INDEX = new ConcurrentHashMap<>();
    /** 正在下载的歌曲。 */
    private static final Map<Long, Download> ACTIVE = new ConcurrentHashMap<>();

    private static volatile Path dir;
    private static volatile Semaphore permits = new Semaphore(2);

    private AudioCache() {
    }

    /**
     * 一次进行中的下载。HTTP 服务会读取这个对象来「等数据」。
     */
    public static final class Download {
        private final long songId;
        private final Path file;
        private final Path marker;

        private volatile long written;
        private volatile long lastWriteMillis = System.currentTimeMillis();
        private volatile boolean complete;
        private volatile boolean failed;
        private volatile String error = "";

        private Download(long songId, Path file, Path marker) {
            this.songId = songId;
            this.file = file;
            this.marker = marker;
        }

        public long songId() {
            return songId;
        }

        public Path file() {
            return file;
        }

        public long written() {
            return written;
        }

        public boolean isComplete() {
            return complete;
        }

        public boolean isFailed() {
            return failed;
        }

        public String error() {
            return error;
        }

        /**
         * 等待 position 之后至少有一个字节可读。
         *
         * @return true 表示可以继续读；false 表示下载失败、已到末尾或等待超时
         */
        public boolean awaitBytes(long position, long timeoutMillis) {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (true) {
                if (written > position) {
                    return true;
                }
                if (complete || failed) {
                    return false;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    Thread.sleep(Math.min(50L, remaining));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
    }

    public static synchronized void init(BridgeConfig config) {
        Path cacheDir = Path.of(config.cacheDir);
        if (!cacheDir.isAbsolute()) {
            cacheDir = FabricLoader.getInstance().getGameDir().resolve(config.cacheDir);
        }
        dir = cacheDir;
        permits = new Semaphore(config.maxConcurrentDownloads);
        INDEX.clear();
        try {
            Files.createDirectories(cacheDir);
        } catch (Exception e) {
            SPlayerBridge.LOGGER.error("[SPlayerBridge] 无法创建缓存目录 {}", cacheDir, e);
            return;
        }
        try (Stream<Path> files = Files.list(cacheDir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                Matcher part = PART_FILE.matcher(name);
                if (part.matches()) {
                    // 上次没下完，连同数据文件一起清掉
                    try {
                        Files.deleteIfExists(cacheDir.resolve(part.group(1) + "." + part.group(2)));
                        Files.deleteIfExists(file);
                    } catch (Exception ignored) {
                    }
                    continue;
                }
                Matcher matcher = CACHE_FILE.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }
                try {
                    if (Files.isRegularFile(file) && Files.size(file) > 4096) {
                        INDEX.put(Long.parseLong(matcher.group(1)), file);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            SPlayerBridge.LOGGER.warn("[SPlayerBridge] 扫描缓存目录失败: {}", e.getMessage());
        }
        SPlayerBridge.LOGGER.info("[SPlayerBridge] 缓存目录 {}，已有 {} 首歌曲", cacheDir, INDEX.size());
    }

    public static Path directory() {
        return dir;
    }

    public static int count() {
        return INDEX.size();
    }

    public static Optional<Path> findComplete(long songId) {
        Path file = INDEX.get(songId);
        return file != null && Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    public static Optional<Download> activeDownload(long songId) {
        return Optional.ofNullable(ACTIVE.get(songId));
    }

    /**
     * 已完整缓存的歌曲文件名（例如 <code>2051267438.flac</code>）。
     */
    public static Optional<String> fileName(long songId) {
        if (!SPlayerBridge.config().cacheEnabled) {
            return Optional.empty();
        }
        return findComplete(songId).map(file -> file.getFileName().toString());
    }

    /**
     * 确保这首歌有数据：已缓存直接返回文件名，否则开始下载并返回即将生成的文件名。
     * <p>
     * 返回后调用方就能把地址交给客户端了——客户端来取数据时会等，服务端下到哪发到哪。
     *
     * @return 文件名；缓存被关闭或目录不可用时返回 empty，调用方应回退到远端直链
     */
    public static Optional<String> ensure(long songId, String remoteUrl) {
        BridgeConfig config = SPlayerBridge.config();
        if (!config.cacheEnabled || dir == null) {
            return Optional.empty();
        }
        Optional<Path> done = findComplete(songId);
        if (done.isPresent()) {
            return Optional.of(done.get().getFileName().toString());
        }
        Download download = ACTIVE.get(songId);
        if (download == null) {
            download = start(songId, remoteUrl);
        }
        return Optional.of(download.file().getFileName().toString());
    }

    /**
     * 根据配置生成交给客户端的地址。
     * <ul>
     *     <li>http：内置 HTTP 服务（默认，客户端不用装本模组）</li>
     *     <li>packet：走游戏端口分包（客户端也要装本模组，适合 frp/端口受限的服务器）</li>
     * </ul>
     */
    public static Optional<String> urlFor(long songId, String fileName) {
        if (isPacketTransport()) {
            return Optional.of("http://" + com.splayerbridge.netmusic.net.ModPayloads.FAKE_HOST
                    + "/audio/" + fileName);
        }
        String base = CacheHttpServer.baseUrl();
        return base == null ? Optional.empty() : Optional.of(base + "/audio/" + fileName);
    }

    public static boolean isPacketTransport() {
        return "packet".equalsIgnoreCase(SPlayerBridge.config().transport);
    }

    private static Download start(long songId, String remoteUrl) {
        String extension = extensionFromUrl(remoteUrl);
        Path file = dir.resolve(songId + "." + extension);
        Path marker = dir.resolve(songId + "." + extension + ".part");
        Download download = new Download(songId, file, marker);
        Download previous = ACTIVE.putIfAbsent(songId, download);
        if (previous != null) {
            return previous;
        }
        CompletableFuture<Void> task = CompletableFuture.runAsync(() -> run(download, remoteUrl), Async.download());
        task.whenComplete((unused, throwable) -> ACTIVE.remove(songId, download));
        return download;
    }

    private static void run(Download download, String remoteUrl) {
        Semaphore slot = permits;
        boolean acquired = false;
        try {
            slot.acquire();
            acquired = true;
            Files.createFile(download.marker);

            HttpRequest request = HttpRequest.newBuilder(URI.create(remoteUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "NetMusic-SPlayerBridge/1.0")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                fail(download, "HTTP " + response.statusCode());
                return;
            }

            byte[] head = new byte[12];
            int headLength = 0;
            long total = 0;
            InputStream body = response.body();
            // 网易云 CDN 偶尔会在传输中途卡死，read() 会一直阻塞，这里放个看门狗
            ScheduledFuture<?> watchdog = Async.tick().scheduleAtFixedRate(() -> {
                if (download.complete || download.failed) {
                    return;
                }
                if (System.currentTimeMillis() - download.lastWriteMillis > DOWNLOAD_STALL_MILLIS) {
                    SPlayerBridge.LOGGER.warn("[SPlayerBridge] 下载 {} 秒没有新数据，放弃: 歌曲 {}",
                            DOWNLOAD_STALL_MILLIS / 1000, download.songId);
                    try {
                        body.close();
                    } catch (Exception ignored) {
                    }
                }
            }, 5, 5, TimeUnit.SECONDS);

            try (InputStream in = body;
                 OutputStream out = Files.newOutputStream(download.file,
                         StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (headLength < head.length) {
                        int copy = Math.min(read, head.length - headLength);
                        System.arraycopy(buffer, 0, head, headLength, copy);
                        headLength += copy;
                    }
                    out.write(buffer, 0, read);
                    out.flush();
                    total += read;
                    download.written = total;
                    download.lastWriteMillis = System.currentTimeMillis();
                    if (total > MAX_FILE_BYTES) {
                        fail(download, "文件超过 200MB");
                        return;
                    }
                }
            } finally {
                watchdog.cancel(false);
            }

            if (total < 4096) {
                fail(download, "内容过小（" + total + " 字节）");
                return;
            }
            if (!looksLikeAudio(head, headLength)) {
                fail(download, "内容不像音频文件");
                return;
            }

            download.complete = true;
            Files.deleteIfExists(download.marker);
            INDEX.put(download.songId, download.file);
            SPlayerBridge.LOGGER.info("[SPlayerBridge] 已缓存歌曲 {}（{} KB）",
                    download.songId, total / 1024);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(download, "线程中断");
        } catch (Exception e) {
            fail(download, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (!download.complete) {
                // 客户端可能还占着这个文件，删不掉也无所谓，下次启动会按 .part 标记清掉
                try {
                    Files.deleteIfExists(download.file);
                } catch (Exception ignored) {
                }
            }
            if (acquired) {
                slot.release();
            }
        }
    }

    private static void fail(Download download, String reason) {
        download.failed = true;
        download.error = reason;
        SPlayerBridge.LOGGER.warn("[SPlayerBridge] 缓存歌曲 {} 失败: {}", download.songId, reason);
    }

    /**
     * 清空缓存，返回删除的文件数量。
     */
    public static synchronized int clear() {
        if (dir == null || !Files.isDirectory(dir)) {
            return 0;
        }
        int removed = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (CACHE_FILE.matcher(name).matches() || PART_FILE.matcher(name).matches()) {
                    try {
                        if (Files.deleteIfExists(file)) {
                            removed++;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception e) {
            SPlayerBridge.LOGGER.warn("[SPlayerBridge] 清空缓存失败: {}", e.getMessage());
        }
        INDEX.clear();
        return removed;
    }

    /**
     * 从 URL 推断扩展名，判断不出来时按 mp3 处理。
     */
    private static String extensionFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path != null) {
                int dot = path.lastIndexOf('.');
                if (dot >= 0 && dot < path.length() - 1) {
                    String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                    if (AUDIO_EXTENSIONS.contains(ext)) {
                        return ext;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "mp3";
    }

    /**
     * 粗略校验文件头，避免把 JSON/HTML 错误页当成音频缓存下来。
     */
    private static boolean looksLikeAudio(byte[] head, int length) {
        if (length < 4) {
            return false;
        }
        String magic = new String(head, 0, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
        if ("fLaC".equals(magic) || "OggS".equals(magic) || "RIFF".equals(magic) || "MAC ".equals(magic)) {
            return true;
        }
        if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            return true;
        }
        if (length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return true;
        }
        int b0 = head[0] & 0xFF;
        int b1 = head[1] & 0xFF;
        // MP3/AAC 帧同步字
        return b0 == 0xFF && (b1 & 0xE0) == 0xE0;
    }
}
