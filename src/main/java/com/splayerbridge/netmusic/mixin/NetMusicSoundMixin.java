package com.splayerbridge.netmusic.mixin;

import com.github.tartaricacid.netmusic.client.audio.NetMusicSound;
import com.splayerbridge.netmusic.client.LyricGate;
import com.splayerbridge.netmusic.net.Links;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URL;

/**
 * 音频还没真正开始放之前，冻结 NetMusicSound 的 tick。
 * <p>
 * MC 的 SoundEngine 对流式声音是「先加进 tickingSounds（tick 开始计时，歌词跟着 tick 走），
 * 同时异步拉音频流」，所以音频没出声歌词已经走了。
 * <p>
 * 这里在音频就绪前直接取消整次 tick：歌词停住、停止判定也停住，等音频真的开始才一起走。
 * 客户端 {@code RelayAudioHandler} 负责在音频就绪时解除门控。
 */
@Mixin(value = NetMusicSound.class, remap = false)
public abstract class NetMusicSoundMixin {
    @Shadow
    @Nullable
    private URL songUrl;

    @Inject(method = MixinNames.SOUND_TICK + "()V", at = @At("HEAD"), cancellable = true)
    private void splayerbridge$awaitAudio(CallbackInfo ci) {
        URL url = this.songUrl;
        if (url == null) {
            return;
        }
        String session = Links.sessionOf(url.toString());
        if (session != null && LyricGate.isPending(session)) {
            // 音频还没开始：整次 tick 都跳过，歌词随之停住
            ci.cancel();
        }
    }
}
