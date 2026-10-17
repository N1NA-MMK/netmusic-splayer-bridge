package com.splayerbridge.netmusic.cache;

import com.splayerbridge.netmusic.BridgeConfig;
import com.splayerbridge.netmusic.SPlayerBridge;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * 内置的只读音频服务，把服务端缓存（或正在下载）的音频下发给客户端。
 * <p>
 * 两种模式：
 * <ul>
 *     <li>文件已下完：普通文件服务，支持 Range</li>
 *     <li>还在下载：chunked 边下边发，客户端读不到数据时会在这里等待</li>
 * </ul>
 * NetMusic 的 DirectHttpHandler 会带 <code>Range: bytes=N-</code>，两种模式都要支持。
 */
public final class CacheHttpServer {
    private static final Pattern FILE_NAME = Pattern.compile("^\\d+\\.[a-z0-9]{2,5}$");
    private static final String PREFIX = "/audio/";

    /** 下载开始后等待首字节的最长时间。 */
    private static final long FIRST_BYTE_TIMEOUT_MILLIS = 20_000L;
    /** 流式下发过程中，连续多久没有新数据就断开。 */
    private static final long STALL_TIMEOUT_MILLIS = 30_000L;

    private static HttpServer server;
    private static ExecutorService executor;
    private static String baseUrl;
    private static int port;
    private static Path root;

    private CacheHttpServer() {
    }

    public static synchronized void start(Path cacheDir, BridgeConfig config) {
        if (server != null) {
            return;
        }
        root = cacheDir;
        int startPort = config.cacheHttpPort;
        for (int attempt = 0; attempt < 20; attempt++) {
            int candidate = startPort + attempt;
            try {
                HttpServer httpServer = HttpServer.create(
                        new InetSocketAddress(InetAddress.getByName(config.cacheBindAddress), candidate), 32);
                executor = Executors.newFixedThreadPool(8, runnable -> {
                    Thread thread = new Thread(runnable, "SPlayerBridge-Http");
                    thread.setDaemon(true);
                    return thread;
                });
                httpServer.setExecutor(executor);
                httpServer.createContext("/", CacheHttpServer::handle);
                httpServer.start();
                server = httpServer;
                port = candidate;
                baseUrl = "http://" + resolveHost(config) + ":" + candidate;
                if (candidate != startPort) {
                    SPlayerBridge.LOGGER.warn("[SPlayerBridge] 端口 {} 被占用，缓存服务改用 {}",
                            startPort, candidate);
                }
                SPlayerBridge.LOGGER.info("[SPlayerBridge] 音频下发服务已启动: {}", baseUrl);
                return;
            } catch (Exception e) {
                if (attempt == 19) {
                    SPlayerBridge.LOGGER.error("[SPlayerBridge] 音频下发服务启动失败，本次运行将不使用缓存", e);
                    baseUrl = null;
                }
            }
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        baseUrl = null;
    }

    public static String baseUrl() {
        return baseUrl;
    }

    public static int port() {
        return port;
    }

    /**
     * 缓存地址里的主机名。
     */
    private static String resolveHost(BridgeConfig config) {
        if (!"auto".equalsIgnoreCase(config.publicHost)) {
            return config.publicHost;
        }
        // 单人游戏（集成服务端）直接用回环地址
        if (net.fabricmc.loader.api.FabricLoader.getInstance().getEnvironmentType()
                == net.fabricmc.api.EnvType.CLIENT) {
            return "127.0.0.1";
        }
        String lan = findLanAddress();
        return lan != null ? lan : "127.0.0.1";
    }

    private static String findLanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address.getAddress().length == 4 && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // 忽略，回退到回环地址
        }
        return null;
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path == null || !path.startsWith(PREFIX)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String name = path.substring(PREFIX.length());
            if (!FILE_NAME.matcher(name).matches()) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            long songId;
            try {
                songId = Long.parseLong(name.substring(0, name.indexOf('.')));
            } catch (NumberFormatException e) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }

            Optional<Path> complete = AudioCache.findComplete(songId);
            if (complete.isPresent() && complete.get().getFileName().toString().equals(name)) {
                serveFile(exchange, complete.get(), method);
                return;
            }

            Optional<AudioCache.Download> active = AudioCache.activeDownload(songId);
            if (active.isPresent() && active.get().file().getFileName().toString().equals(name)) {
                serveGrowing(exchange, active.get(), name, method);
                return;
            }

            exchange.sendResponseHeaders(404, -1);
        } catch (Exception e) {
            SPlayerBridge.LOGGER.debug("[SPlayerBridge] 音频请求处理失败: {}", e.getMessage());
        } finally {
            exchange.close();
        }
    }

    /**
     * 已完整下载的文件：支持 Range。
     */
    private static void serveFile(HttpExchange exchange, Path file, String method) throws IOException {
        long length = Files.size(file);
        if (length <= 0) {
            exchange.sendResponseHeaders(404, -1);
            return;
        }
        String contentType = contentType(file.getFileName().toString());

        long start = 0;
        long end = length - 1;
        boolean partial = false;

        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length()).trim();
            int comma = spec.indexOf(',');
            if (comma > 0) {
                spec = spec.substring(0, comma);
            }
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String startText = spec.substring(0, dash).trim();
                String endText = spec.substring(dash + 1).trim();
                try {
                    if (!startText.isEmpty()) {
                        start = Long.parseLong(startText);
                        if (!endText.isEmpty()) {
                            end = Math.min(Long.parseLong(endText), length - 1);
                        }
                    } else if (!endText.isEmpty()) {
                        start = Math.max(0, length - Long.parseLong(endText));
                    }
                    partial = true;
                } catch (NumberFormatException e) {
                    start = 0;
                    end = length - 1;
                    partial = false;
                }
            }
        }
        if (start > end || start >= length) {
            exchange.getResponseHeaders().set("Content-Range", "bytes */" + length);
            exchange.sendResponseHeaders(416, -1);
            return;
        }

        long contentLength = end - start + 1;
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        headers.set("Accept-Ranges", "bytes");
        headers.set("Cache-Control", "public, max-age=3600");
        if (partial) {
            headers.set("Content-Range", "bytes " + start + "-" + end + "/" + length);
        }

        if ("HEAD".equals(method)) {
            headers.set("Content-Length", Long.toString(contentLength));
            exchange.sendResponseHeaders(partial ? 206 : 200, -1);
            return;
        }

        exchange.sendResponseHeaders(partial ? 206 : 200, contentLength);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r");
             OutputStream out = exchange.getResponseBody()) {
            raf.seek(start);
            byte[] buffer = new byte[64 * 1024];
            long remaining = contentLength;
            while (remaining > 0) {
                int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
    }

    /**
     * 正在下载的文件：边下边发。总长度未知，用 chunked，客户端读不到数据就等。
     */
    private static void serveGrowing(HttpExchange exchange, AudioCache.Download download, String name, String method)
            throws IOException {
        long start = 0;
        long endLimit = Long.MAX_VALUE;
        String range = exchange.getRequestHeaders().getFirst("Range");
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length()).trim();
            int comma = spec.indexOf(',');
            if (comma > 0) {
                spec = spec.substring(0, comma);
            }
            int dash = spec.indexOf('-');
            if (dash >= 0) {
                String startText = spec.substring(0, dash).trim();
                String endText = spec.substring(dash + 1).trim();
                try {
                    if (!startText.isEmpty()) {
                        start = Long.parseLong(startText);
                    }
                    if (!endText.isEmpty()) {
                        endLimit = Long.parseLong(endText);
                    }
                } catch (NumberFormatException ignored) {
                    start = 0;
                    endLimit = Long.MAX_VALUE;
                }
            }
        }

        // 等到首字节再回响应头；拿不到就告诉客户端失败
        if (!download.awaitBytes(start, FIRST_BYTE_TIMEOUT_MILLIS)) {
            SPlayerBridge.LOGGER.warn("[SPlayerBridge] 音频未就绪（歌曲 {}）: {}", download.songId(),
                    download.isFailed() ? download.error() : "等待超时");
            exchange.sendResponseHeaders(503, -1);
            return;
        }

        if ("HEAD".equals(method)) {
            exchange.getResponseHeaders().set("Content-Type", contentType(name));
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(200, -1);
            return;
        }

        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType(name));
        headers.set("Accept-Ranges", "bytes");
        headers.set("Cache-Control", "no-store");
        // 长度未知 -> 0 表示使用 chunked
        exchange.sendResponseHeaders(200, 0);

        try (RandomAccessFile raf = new RandomAccessFile(download.file().toFile(), "r");
             OutputStream out = exchange.getResponseBody()) {
            raf.seek(start);
            byte[] buffer = new byte[32 * 1024];
            long position = start;
            while (position <= endLimit) {
                long available = download.written() - position;
                if (available <= 0) {
                    if (download.isComplete() || download.isFailed()) {
                        break;
                    }
                    if (!download.awaitBytes(position, STALL_TIMEOUT_MILLIS)) {
                        break;
                    }
                    continue;
                }
                // endLimit 可能是 Long.MAX_VALUE，不能直接参与 +1 运算（会溢出成负数）
                long limit = endLimit == Long.MAX_VALUE
                        ? available
                        : Math.min(available, endLimit - position + 1);
                int toRead = (int) Math.min(buffer.length, limit);
                if (toRead <= 0) {
                    break;
                }
                int read = raf.read(buffer, 0, toRead);
                if (read <= 0) {
                    break;
                }
                out.write(buffer, 0, read);
                out.flush();
                position += read;
            }
        }
    }

    private static String contentType(String fileName) {
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            case "m4a", "mp4" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            default -> "application/octet-stream";
        };
    }
}
