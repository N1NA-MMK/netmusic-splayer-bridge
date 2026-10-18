package com.splayerbridge.netmusic.client;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.splayerbridge.netmusic.SPlayerBridge;
import com.splayerbridge.netmusic.net.Links;

import javax.sound.sampled.AudioFormat;
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
 * 处理本模组下发的标记地址。
 * <p>
 * 做的事：
 * <ol>
 *     <li>解开真实地址和歌曲 ID</li>
 *     <li>按歌曲 ID 做本地缓存，同一首歌不重复下载</li>
 *     <li>非 mp3 格式（FLAC 等）必须先落盘再用「文件」方式打开——NetMusic 流式打开会被
 *         mp3spi 扫空整个流，直接抛 {@code Resetting to invalid mark}</li>
 *     <li>歌词门控：音频没真正开始播放前冻结 tick。放行时机是「解码器已经消费掉约 4 秒音频」，
 *         正好对应 NetMusicAudioStream 构造时 pumpBuffers(4) 的量，所以误差在几十毫秒级。</li>
 * </ol>
 */
public final class RelayAudioHandler implements IAudioStreamHandler {
    /** 这些格式必须落盘，否则 mp3spi 会把流扫空。 */
    private static final Set<String> NEED_FILE = Set.of("flac", "m4a", "aac", "ogg", "wav", "ape", "wma");
    /** 流式播放时先预读这么多，让解码器立刻有数据可用。 */
    private static final int PREFILL_BYTES = 256 * 1024;
    /** NetMusicAudioStream 构造时会预读 4 个「1 秒缓冲」，放行阈值就按 4 秒算。 */
    private static final double PRIME_SECONDS = 4;

    @Override
    public boolean canHandle(URL url) {
        return Links.unwrap(url.toString()) != null;
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        String marker = url.toString();
        String target = Links.unwrap(marker);
        String session = Links.sessionOf(marker);
        long songId = Links.idOf(marker);
        if (target == null) {
            throw new IOException("无法解析音频地址: " + url);
        }
        String extension = ClientAudioCache.extension(target);
        boolean needFile = NEED_FILE.contains(extension) && SPlayerBridge.config().downloadBeforePlay;

        LyricGate.begin(session);
        long startNanos = System.nanoTime();
        SPlayerBridge.LOGGER.info("[SPlayerBridge] 开始播 {} 音频（会话 {}，歌曲 {}）{}",
                extension, session, songId, needFile ? "，先下载" : "");

        AudioInputStream opened;
        Path cached = null;
        try {
            if (needFile) {
                cached = ClientAudioCache.lookup(songId, extension);
                if (cached == null) {
                    cached = ClientAudioCache.download(songId, extension, HttpAudio.open(new URL(target)));
                    SPlayerBridge.LOGGER.info("[SPlayerBridge] 歌曲 {} 已缓存到本地（{} KB）",
                            songId, cached.toFile().length() / 1024);
                }
                // 必须按文件打开：流式打开 FLAC 会被 mp3spi 扫空，必抛 Resetting to invalid mark
                opened = AudioSystem.getAudioInputStream(cached.toFile());
            } else {
                InputStream source = HttpAudio.open(new URL(target));
                byte[] prefill = HttpAudio.readUpTo(source, PREFILL_BYTES);
                InputStream combined = new SequenceInputStream(
                        new ByteArrayInputStream(prefill), source);
                opened = AudioSystem.getAudioInputStream(new BufferedInputStream(combined, 64 * 1024));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LyricGate.ready(session);
            throw new IOException("获取音频被中断", e);
        } catch (Exception e) {
            // 失败也要解除门控，否则歌词会一直被冻住
            LyricGate.ready(session);
            throw e;
        }

        return gate(session, opened, startNanos);
    }

    @Override
    public int getPriority() {
        return 1000;
    }

    /**
     * 包一层计数流：解码器消费掉约 4 秒音频后才解除歌词门控。
     * <p>
     * 为什么不按固定字节数：192kbps 的 mp3 4 秒约 100KB，而 44.1kHz 立体声 PCM 4 秒约 705KB，
     * 差 7 倍。按 AudioFormat 算才靠得住。
     */
    private static AudioInputStream gate(String session, AudioInputStream opened, long startNanos) {
        AudioFormat format = opened.getFormat();
        float frameRate = format.getFrameRate() > 0 ? format.getFrameRate() : 44100f;
        int frameSize = format.getFrameSize() > 0 ? format.getFrameSize() : 4;
        long threshold = Math.max(16 * 1024, (long) (PRIME_SECONDS * frameRate * frameSize));

        CountingStream counting = new CountingStream(opened, session, threshold, startNanos);
        return new AudioInputStream(counting, format, opened.getFrameLength());
    }

    /**
     * 观察解码器真正消费了多少音频。
     */
    private static final class CountingStream extends InputStream {
        private final InputStream delegate;
        private final String session;
        private final long threshold;
        private final long startNanos;
        private long delivered;
        private boolean announced;

        private CountingStream(InputStream delegate, String session, long threshold, long startNanos) {
            this.delegate = delegate;
            this.session = session;
            this.threshold = threshold;
            this.startNanos = startNanos;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                count(1);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) {
            delivered += bytes;
            if (delivered >= threshold) {
                LyricGate.ready(session);
                if (!announced) {
                    announced = true;
                    long millis = (System.nanoTime() - startNanos) / 1_000_000;
                    SPlayerBridge.LOGGER.info("[SPlayerBridge] 歌词门控解除（会话 {}，等待 {} ms，已消费 {} KB）",
                            session, millis, delivered / 1024);
                }
            }
        }
    }
}
