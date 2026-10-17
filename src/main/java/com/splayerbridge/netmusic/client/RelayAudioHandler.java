package com.splayerbridge.netmusic.client;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.audio.MusicBufferedInputStream;
import com.github.tartaricacid.netmusic.util.Mp3Util;
import com.splayerbridge.netmusic.SPlayerBridge;
import com.splayerbridge.netmusic.net.ModPayloads;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.net.URL;

/**
 * 拦下服务端下发的「假地址」，改成通过游戏连接向服务端要音频。
 * <p>
 * 服务端在 packet 模式下会给出
 * <code>http://netmusic-splayer-bridge.invalid/audio/&lt;id&gt;.&lt;ext&gt;</code>，
 * 这个域名不会真的被解析，由这里接管。
 */
public final class RelayAudioHandler implements IAudioStreamHandler {
    private static final String PREFIX = "/audio/";

    @Override
    public boolean canHandle(URL url) {
        return ModPayloads.FAKE_HOST.equalsIgnoreCase(url.getHost())
                && url.getPath() != null
                && url.getPath().startsWith(PREFIX);
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        long songId = parseSongId(url.getPath());
        if (songId < 0) {
            throw new IOException("无法解析音频地址: " + url);
        }
        BufferedInputStream buffered = new MusicBufferedInputStream(ClientTransport.open(songId));
        Mp3Util.skipID3(buffered);
        return AudioSystem.getAudioInputStream(buffered);
    }

    @Override
    public int getPriority() {
        // 比内置处理器都高，确保这个假地址由我们处理
        return 1000;
    }

    private static long parseSongId(String path) {
        try {
            String name = path.substring(PREFIX.length());
            int dot = name.indexOf('.');
            if (dot > 0) {
                name = name.substring(0, dot);
            }
            return Long.parseLong(name);
        } catch (Exception e) {
            SPlayerBridge.LOGGER.warn("[SPlayerBridge] 无法解析音频地址: {}", path);
            return -1L;
        }
    }
}
