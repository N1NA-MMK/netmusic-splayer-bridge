package com.splayerbridge.netmusic.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 歌词门控：音频没真正就绪之前，不让歌词往前走。
 * <p>
 * 背景：MC 的 {@code SoundEngine.play()} 对流式声音是这么干的——先把声音实例塞进
 * {@code tickingSounds}（tick 开始计时，歌词就是靠 tick 驱动的），**同时**异步去拉音频流。
 * 所以音频还没出声，歌词已经走了，整体超前「加载音频那段耗时」。
 * <p>
 * 做法：客户端 handler 拿到地址时登记一个待就绪会话，等解码器真的读到音频后再标记就绪。
 * 配合 {@code NetMusicSoundMixin} 冻结 tick，就能做到「音频开始播放才显示歌词」。
 * <p>
 * 每次播放的会话号都不同（见服务端 Links.wrap），所以同时放好几台留声机不会互相干扰。
 */
public final class LyricGate {
    /** 待就绪的会话：会话号 -> 登记时间。 */
    private static final Map<String, Long> PENDING = new ConcurrentHashMap<>();
    /** 兜底：超过这么久还没就绪也放行，避免数据永远不来把留声机卡死。 */
    private static final long MAX_WAIT_MILLIS = 8_000L;

    private LyricGate() {
    }

    public static void begin(String session) {
        PENDING.put(session, System.currentTimeMillis());
    }

    public static void ready(String session) {
        PENDING.remove(session);
    }

    /**
     * 这个会话的音频还没就绪吗？
     */
    public static boolean isPending(String session) {
        if (session == null) {
            return false;
        }
        Long start = PENDING.get(session);
        if (start == null) {
            return false;
        }
        if (System.currentTimeMillis() - start > MAX_WAIT_MILLIS) {
            PENDING.remove(session);
            return false;
        }
        return true;
    }
}
