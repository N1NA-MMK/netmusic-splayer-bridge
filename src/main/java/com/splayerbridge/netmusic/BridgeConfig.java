package com.splayerbridge.netmusic;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置文件，位于 <code>config/netmusic-splayer-bridge.json</code>。
 * <p>
 * 缺失字段会使用这里的默认值，因此旧配置文件在升级后仍然可用。
 */
public class BridgeConfig {
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();
    private static final String FILE_NAME = "netmusic-splayer-bridge.json";

    /** 总开关。关闭后本模组不接管任何歌曲。 */
    public boolean enabled = true;

    /** 是否连非 VIP 的网易云歌曲也走 SPlayer（默认只处理 VIP 歌曲）。 */
    public boolean preferForAllNeteaseSongs = false;

    /** SPlayer 服务地址。 */
    public String splayerBaseUrl = "http://127.0.0.1:25884";

    /**
     * 音频怎么下发给客户端：
     * <ul>
     *     <li><code>http</code>：服务端开一个本地 HTTP 服务（默认 25885），客户端直接连这个端口。客户端不需要装本模组。</li>
     *     <li><code>packet</code>：通过游戏端口分包传输。客户端也要装本模组，适合 frp 反代 / 只映射了游戏端口的服务器。</li>
     * </ul>
     */
    public String transport = "http";

    /**
     * 解锁接口候选列表，按顺序尝试，{id} 会被替换为网易云歌曲 ID。
     * <p>
     * 新版 SPlayer 的接口是 <code>/api/unblock/netease</code>；
     * 这里同时保留了 <code>/api/unlock</code> 以兼容其他分支/旧版本。
     */
    public List<String> unlockEndpoints = new ArrayList<>(List.of(
            "/api/unblock/netease?id={id}",
            "/api/unlock?id={id}",
            "/api/unlock/netease?id={id}",
            "/api/unblock?id={id}"
    ));

    /** 网易云解锁失败时，是否再用关键词去酷我/波点兜底（音质和时长可能不同）。 */
    public boolean keywordFallback = true;

    /** 关键词兜底接口，{keyword} = 歌名-歌手，{name} = 歌名，{artist} = 第一位歌手。 */
    public List<String> keywordEndpoints = new ArrayList<>(List.of(
            "/api/unblock/kuwo?keyword={keyword}&songName={name}&artist={artist}",
            "/api/unblock/bodian?keyword={keyword}&songName={name}&artist={artist}"
    ));

    /** 单次 HTTP 请求超时（秒）。 */
    public int requestTimeoutSeconds = 6;

    /** 一首歌解析的总超时（秒），超过后放弃解锁、回退原地址。 */
    public int totalResolveTimeoutSeconds = 15;

    /** 是否启用磁盘缓存。 */
    public boolean cacheEnabled = true;

    /** 缓存目录，相对路径基于游戏/服务端根目录。 */
    public String cacheDir = "netmusic_splayer_cache";

    /** 内置文件服务的端口，客户端会从这里拉取缓存音频。 */
    public int cacheHttpPort = 25885;

    /** 内置文件服务监听地址，默认监听全部网卡。 */
    public String cacheBindAddress = "0.0.0.0";

    /**
     * 下发给客户端的缓存地址中的主机名。
     * <ul>
     *     <li>auto：单人游戏用 127.0.0.1，专用服务端自动选择局域网 IP</li>
     *     <li>也可手动填写，例如 192.168.1.10 或 example.com</li>
     * </ul>
     */
    public String publicHost = "auto";

    /** 同时下载缓存的最大数量。 */
    public int maxConcurrentDownloads = 2;

    /**
     * 是否把音频转成 mp3 再下发给客户端（能显著减少 frp 流量）。
     * <ul>
     *     <li><code>auto</code>：找得到 ffmpeg 就转（默认）</li>
     *     <li><code>off</code>：不转，原样下发</li>
     *     <li><code>mp3</code>：强制转（找不到 ffmpeg 时回退到原始文件）</li>
     * </ul>
     */
    public String transcode = "auto";

    /** 转码码率（kbps）。192 对游戏内播放足够，想更省流量可以调 128。 */
    public int transcodeBitrateKbps = 192;

    /** ffmpeg 可执行文件路径，默认走 PATH。 */
    public String ffmpegPath = "ffmpeg";

    /**
     * packet 模式下客户端的预读窗口（KB）。
     * <p>
     * 客户端是按播放速度消费的，所以这个窗口决定「能提前缓冲多少」：
     * 1MB 只够撑十几秒，传输会跟着播放进度慢慢走；调大（例如 16384）可以一次把整首拉过去，
     * 但需要带宽足够，否则会把游戏连接堵住。
     */
    public int packetWindowKb = 1024;

    /** packet 模式每 tick 最多发几块（16KB/块）。4 块 ≈ 1.25MB/s。 */
    public int packetChunksPerTick = 4;

    /** 缓存命中时是否在日志里输出。 */
    public boolean logCacheHits = true;

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /**
     * 读取配置，文件不存在时写入一份默认配置。
     */
    public static BridgeConfig load() {
        Path path = configPath();
        BridgeConfig loaded = new BridgeConfig();
        try {
            if (Files.exists(path)) {
                String json = Files.readString(path, StandardCharsets.UTF_8);
                BridgeConfig parsed = GSON.fromJson(json, BridgeConfig.class);
                if (parsed != null) {
                    loaded = parsed;
                }
            } else {
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(loaded), StandardCharsets.UTF_8);
                SPlayerBridge.LOGGER.info("[SPlayerBridge] 已生成默认配置: {}", path);
            }
        } catch (Exception e) {
            SPlayerBridge.LOGGER.error("[SPlayerBridge] 读取配置失败，使用默认值", e);
            return new BridgeConfig();
        }
        return loaded.sanitize();
    }

    /**
     * 兜底修正非法值，避免手改配置后出现奇怪的问题。
     */
    private BridgeConfig sanitize() {
        if (splayerBaseUrl == null || splayerBaseUrl.isBlank()) {
            splayerBaseUrl = "http://127.0.0.1:25884";
        }
        while (splayerBaseUrl.endsWith("/")) {
            splayerBaseUrl = splayerBaseUrl.substring(0, splayerBaseUrl.length() - 1);
        }
        if (transport == null || transport.isBlank()) {
            transport = "http";
        }
        transport = transport.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"packet".equals(transport) && !"http".equals(transport)) {
            transport = "http";
        }
        if (unlockEndpoints == null || unlockEndpoints.isEmpty()) {
            unlockEndpoints = new ArrayList<>(List.of("/api/unblock/netease?id={id}"));
        }
        if (keywordEndpoints == null) {
            keywordEndpoints = new ArrayList<>();
        }
        if (cacheDir == null || cacheDir.isBlank()) {
            cacheDir = "netmusic_splayer_cache";
        }
        if (cacheHttpPort < 1024 || cacheHttpPort > 65535) {
            cacheHttpPort = 25885;
        }
        if (cacheBindAddress == null || cacheBindAddress.isBlank()) {
            cacheBindAddress = "0.0.0.0";
        }
        if (publicHost == null || publicHost.isBlank()) {
            publicHost = "auto";
        }
        requestTimeoutSeconds = Math.max(1, Math.min(requestTimeoutSeconds, 60));
        totalResolveTimeoutSeconds = Math.max(2, Math.min(totalResolveTimeoutSeconds, 120));
        maxConcurrentDownloads = Math.max(1, Math.min(maxConcurrentDownloads, 8));
        packetWindowKb = Math.max(256, Math.min(packetWindowKb, 65536));
        packetChunksPerTick = Math.max(1, Math.min(packetChunksPerTick, 32));
        if (transcode == null || transcode.isBlank()) {
            transcode = "auto";
        }
        transcode = transcode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!"auto".equals(transcode) && !"off".equals(transcode) && !"mp3".equals(transcode)) {
            transcode = "auto";
        }
        transcodeBitrateKbps = Math.max(64, Math.min(transcodeBitrateKbps, 320));
        if (ffmpegPath == null || ffmpegPath.isBlank()) {
            ffmpegPath = "ffmpeg";
        }
        return this;
    }

    /**
     * 把配置写回磁盘（目前只在 reload 时由外部显式调用）。
     */
    public void save() throws IOException {
        Path path = configPath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
    }
}
