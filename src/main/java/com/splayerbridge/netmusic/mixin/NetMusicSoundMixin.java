package com.splayerbridge.netmusic.mixin;

import com.github.tartaricacid.netmusic.api.lyric.LyricRecord;
import com.splayerbridge.netmusic.SPlayerBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让歌词的时钟晚一点走。
 * <p>
 * NetMusic 的歌词是靠 {@code NetMusicSound.tick()} 里的 tick 驱动的，而音频是异步开始播放的：
 * 客户端收到地址那一刻就开始计 tick，等音频真的出声还要几百毫秒到几秒，歌词就整体超前。
 * <p>
 * 这里把传给 {@code LyricRecord.updateCurrentLine} 的 tick 减去一个偏移，
 * 相当于把歌词整体往后推 {@code lyricOffsetTicks} 个 tick（20 tick = 1 秒）。
 */
@Mixin(targets = "com.github.tartaricacid.netmusic.client.audio.NetMusicSound", remap = false)
public abstract class NetMusicSoundMixin {
    @Redirect(
            method = "tick()V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/github/tartaricacid/netmusic/api/lyric/LyricRecord;updateCurrentLine(I)V"
            )
    )
    private void splayerBridge$delayLyric(LyricRecord record, int tick) {
        int offset = SPlayerBridge.config().lyricOffsetTicks;
        record.updateCurrentLine(offset <= 0 ? tick : Math.max(0, tick - offset));
    }
}
