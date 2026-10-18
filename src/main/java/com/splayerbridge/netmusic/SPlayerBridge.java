package com.splayerbridge.netmusic;

import com.github.tartaricacid.netmusic.api.resolver.MusicPlayResolverManager;
import com.splayerbridge.netmusic.command.BridgeCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * NetMusic x SPlayer 桥接模组。
 * <p>
 * 作用：当 NetMusic 播放网易云 VIP 歌曲时，由服务端向本机运行的 SPlayer 请求解锁后的
 * CDN 直链，并把客户端自己能访问的地址交给它。
 * <p>
 * 实现方式：注册一个优先级最高的 {@link com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver}，
 * 由服务端在把歌曲信息下发给客户端之前完成 URL 替换。
 * <p>
 * 音频不走服务端转发：客户端装上本模组后，会直接去 CDN 取音频，
 * 因此不占用服务端的额外端口，也不需要 frp 之类做端口映射。
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

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(BridgeCommand.build()));

        LOGGER.info("[SPlayerBridge] 已加载，SPlayer 地址: {}，全部歌曲: {}",
                config.splayerBaseUrl, config.preferForAllNeteaseSongs ? "是" : "否（仅 VIP）");
    }

    /**
     * 重新读取配置文件。
     */
    public static void reload() {
        config = BridgeConfig.load();
        LOGGER.info("[SPlayerBridge] 配置已重新加载");
    }
}
