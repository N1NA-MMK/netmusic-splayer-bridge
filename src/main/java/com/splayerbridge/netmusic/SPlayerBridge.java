package com.splayerbridge.netmusic;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.splayerbridge.netmusic.cache.AudioCacheService;
import com.splayerbridge.netmusic.command.BridgeCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NetMusic x SPlayer 桥接模组。
 * <p>
 * 作用：当 NetMusic 播放网易云 VIP 歌曲时，优先向本机（服务端）运行的 SPlayer
 * 的解锁接口请求音频直链；首次播放会把音频缓存到服务端磁盘，之后同一首歌直接走缓存。
 * <p>
 * 实现方式：注册一个优先级最高的 {@link com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver}，
 * 由服务端在把歌曲信息下发给客户端之前完成 URL 替换。
 */
public class SPlayerBridge implements ModInitializer {
    public static final String MOD_ID = "netmusic_splayer_bridge";
    public static final Logger LOGGER = LogManager.getLogger("NetMusic-SPlayerBridge");

    private static volatile BridgeConfig config = new BridgeConfig();

    /**
     * 全局配置，reload 时会被整体替换，使用处请每次读取 {@link #config()}。
     */
    public static BridgeConfig config() {
        return config;
    }

    @Override
    public void onInitialize() {
        config = BridgeConfig.load();

        // 必须在 NetMusic 冻结解析器列表（AllModLoadedEvent）之前注册
        MusicPlayResolverManager.registerResolver(new SPlayerSongResolver());

        ServerLifecycleEvents.SERVER_STARTED.register(server -> AudioCacheService.start());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> AudioCacheService.stop());

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(BridgeCommand.build()));

        LOGGER.info("[SPlayerBridge] 已加载，SPlayer 地址: {}，缓存: {}",
                config.splayerBaseUrl, config.cacheEnabled ? config.cacheDir : "已关闭");
    }

    /**
     * 重新读取配置文件。
     */
    public static void reload() {
        config = BridgeConfig.load();
        AudioCacheService.restart();
        LOGGER.info("[SPlayerBridge] 配置已重新加载");
    }
}
