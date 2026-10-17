package com.splayerbridge.netmusic.cache;

import com.splayerbridge.netmusic.Async;
import com.splayerbridge.netmusic.BridgeConfig;
import com.splayerbridge.netmusic.SPlayerApi;
import com.splayerbridge.netmusic.SPlayerBridge;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 缓存服务与 SPlayer 健康检查的生命周期管理，跟随服务端启停。
 */
public final class AudioCacheService {
    private static volatile boolean started;
    private static volatile ScheduledFuture<?> probeTask;

    private AudioCacheService() {
    }

    public static synchronized void start() {
        if (started) {
            return;
        }
        BridgeConfig config = SPlayerBridge.config();
        if (!config.enabled) {
            SPlayerBridge.LOGGER.info("[SPlayerBridge] 配置中已关闭，跳过初始化");
            return;
        }
        AudioCache.init(config);
        if (config.cacheEnabled && !AudioCache.isPacketTransport()) {
            // packet 模式走游戏端口，不需要再占一个端口
            CacheHttpServer.start(AudioCache.directory(), config);
        } else if (AudioCache.isPacketTransport()) {
            SPlayerBridge.LOGGER.info("[SPlayerBridge] 下发方式为游戏端口分包，客户端需要安装本模组");
        }
        started = true;
        startProbe();
    }

    public static synchronized void stop() {
        if (probeTask != null) {
            probeTask.cancel(false);
            probeTask = null;
        }
        CacheHttpServer.stop();
        started = false;
    }

    public static synchronized void restart() {
        stop();
        start();
    }

    public static boolean isStarted() {
        return started;
    }

    private static void startProbe() {
        if (probeTask != null) {
            probeTask.cancel(false);
        }
        // 首次延迟 2 秒，之后每 15 秒探测一次
        probeTask = Async.tick().scheduleWithFixedDelay(() -> {
            try {
                SPlayerApi.probe();
            } catch (Exception ignored) {
            }
        }, 2, 15, TimeUnit.SECONDS);
    }
}
