package com.splayerbridge.netmusic.client;

import com.github.tartaricacid.netmusic.api.NetEaseMusic;
import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.github.tartaricacid.netmusic.client.audio.MusicBufferedInputStream;
import com.github.tartaricacid.netmusic.util.Mp3Util;
import com.splayerbridge.netmusic.SPlayerBridge;
import com.splayerbridge.netmusic.net.ModPayloads;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Set;

/**
 * 处理本模组下发给客户端的地址，顺手修掉 NetMusic 播不了 FLAC 的问题。
 * <p>
 * 两类地址：
 * <ol>
 *     <li>服务端的假地址 <code>http://netmusic-splayer-bridge.invalid/audio/&lt;id&gt;.&lt;ext&gt;</code>，
 *         由服务端通过游戏端口下发数据</li>
 *     <li>其它非 mp3 直链：NetMusic 原版流式打开 {@code getAudioInputStream(InputStream)} 时，
 *         mp3spi 会先把整个流扫空，遇到 FLAC 就抛 {@code Resetting to invalid mark}。
 *         这里改成先落盘再用 {@code getAudioInputStream(File)}，按扩展名选 SPI，可以正常播放。</li>
 * </ol>
 * 网易云自己的域名（music.163.com）不接管，仍交给 NetMusic 的 NetEaseHttpHandler，免得丢掉请求头。
 */
public final class RelayAudioHandler implements IAudioStreamHandler {
    private static final String PREFIX = "/audio/";
    /** 这些格式流式播放没问题，直接像 NetMusic 那样喂给 AudioSystem。 */
    private static final Set<String> STREAMABLE = Set.of("mp3");
    /** 这些格式必须落盘，否则 mp3spi 会把流扫空。 */
    private static final Set<String> NEED_FILE = Set.of("flac", "m4a", "aac", "ogg", "wav", "ape", "wma");

    private static final int PREFILL_BYTES = 256 * 1024;

    @Override
    public boolean canHandle(URL url) {
        String host = url.getHost();
        if (ModPayloads.FAKE_HOST.equalsIgnoreCase(host)) {
            return true;
        }
        if (!SPlayerBridge.config().fixFlac) {
            return false;
        }
        if (!"http".equalsIgnoreCase(url.getProtocol()) && !"https".equalsIgnoreCase(url.getProtocol())) {
            return false;
        }
        if (host != null && host.contains(NetEaseMusic.getHost())) {
            // 网易云自己的域名交给 NetMusic，免得丢登录态
            return false;
        }
        return NEED_FILE.contains(LocalFileAudio.extension(url.getPath()));
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        String extension = LocalFileAudio.extension(url.getPath());

        if (ModPayloads.FAKE_HOST.equalsIgnoreCase(url.getHost())) {
            long songId = parseSongId(url.getPath());
            if (songId < 0) {
                throw new IOException("无法解析音频地址: " + url);
            }
            SPlayerBridge.LOGGER.info("[SPlayerBridge] 拦截到音频地址: {}（走游戏端口，{}）", url, extension);

            if (STREAMABLE.contains(extension) || extension.isEmpty()) {
                return openStream(ClientTransport.open(songId), url);
            }
            // 非 mp3：服务端已经把 FLAC 转过码了，但以防万一，落盘后按文件打开
            Path file = LocalFileAudio.write(ClientTransport.open(songId),
                    System.currentTimeMillis() + "-" + songId + "." + extension);
            return AudioSystem.getAudioInputStream(file.toFile());
        }

        SPlayerBridge.LOGGER.info("[SPlayerBridge] {} 格式无法流式播放，先下载到本地再播", extension);
        try (InputStream in = HttpAudio.open(url)) {
            Path file = LocalFileAudio.write(in, System.currentTimeMillis() + "." + extension);
            return AudioSystem.getAudioInputStream(file.toFile());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载音频被中断", e);
        }
    }

    /**
     * 打开流式音频，并且先预读一段。
     * <p>
     * 为什么预读：音频是异步开始播放的，而客户端从拿到地址那一刻就在按 tick 计时。
     * 如果不预读，音频真正出声前那几百毫秒会把歌词往前顶。预读 256KB（192kbps 下大约 10 秒）
     * 之后，解码器拿到流就能立刻有数据可用。
     */
    private static AudioInputStream openStream(InputStream source, URL url)
            throws UnsupportedAudioFileException, IOException {
        byte[] prefill = HttpAudio.readUpTo(source, PREFILL_BYTES);
        InputStream combined = new SequenceInputStream(
                new ByteArrayInputStream(prefill), source);
        BufferedInputStream buffered = new MusicBufferedInputStream(combined);
        Mp3Util.skipID3(buffered);
        return AudioSystem.getAudioInputStream(buffered);
    }

    @Override
    public int getPriority() {
        // 比内置处理器都高，确保这些地址由我们处理
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
