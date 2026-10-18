package com.splayerbridge.netmusic.client;

import com.github.tartaricacid.netmusic.client.api.IAudioStreamHandler;
import com.splayerbridge.netmusic.SPlayerBridge;
import com.splayerbridge.netmusic.net.Links;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 处理本模组下发的标记地址。
 * <p>
 * 客户端只连服务端给的直链（默认直连网易云 CDN，不占用服务端任何端口），本类负责：
 * <ol>
 *     <li>解开真实地址</li>
 *     <li>非 mp3 格式（FLAC 等）先下到本地再用文件打开——NetMusic 流式打开会被
 *         mp3spi 扫空整个流，直接抛 {@code Resetting to invalid mark}</li>
 *     <li>登记 / 解除歌词门控，让歌词等音频真的开始才走</li>
 * </ol>
 */
public final class RelayAudioHandler implements IAudioStreamHandler {
    /** 这些格式必须落盘，否则 mp3spi 会把流扫空。 */
    private static final Set<String> NEED_FILE = Set.of("flac", "m4a", "aac", "ogg", "wav", "ape", "wma");
    /** 先预读这么多再交给 AudioSystem，让解码器立刻有数据可用。 */
    private static final int PREFILL_BYTES = 256 * 1024;
    /** 解码器消费到这个量就认为「音频已经在放了」，可以解除歌词门控。 */
    private static final int READY_BYTES = 200 * 1024;

    @Override
    public boolean canHandle(URL url) {
        return Links.unwrap(url.toString()) != null;
    }

    @Override
    public AudioInputStream handle(URL url) throws UnsupportedAudioFileException, IOException {
        String marker = url.toString();
        String target = Links.unwrap(marker);
        if (target == null) {
            throw new IOException("无法解析音频地址: " + url);
        }
        String session = marker.substring(marker.lastIndexOf('/') + 1, marker.indexOf('?'));
        String extension = extensionOf(target);
        LyricGate.begin(session);
        SPlayerBridge.LOGGER.info("[SPlayerBridge] 开始播 {} 音频（会话 {}）", extension, session);

        try {
            if (NEED_FILE.contains(extension)) {
                // 非 mp3：先下到本地再按文件打开，绕开 mp3spi 的流式探测 bug
                Path file = LocalFileAudio.write(HttpAudio.open(new URL(target)),
                        System.currentTimeMillis() + "-" + session + "." + extension);                // 本地文件读起来是毫秒级，等于立刻就绪
                LyricGate.ready(session);
                return AudioSystem.getAudioInputStream(file.toFile());
            }

            InputStream source = HttpAudio.open(new URL(target));
            byte[] prefill = HttpAudio.readUpTo(source, PREFILL_BYTES);
            InputStream combined = new SequenceInputStream(
                    new ByteArrayInputStream(prefill), new CountingStream(source, session));
            BufferedInputStream buffered = new BufferedInputStream(combined, 64 * 1024);
            return AudioSystem.getAudioInputStream(buffered);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 失败了也要解除门控，不然歌词会一直冻着
            LyricGate.ready(session);
            throw new IOException("获取音频被中断", e);
        } catch (Exception e) {
            // 失败了也要解除门控，不然歌词会一直冻着
            LyricGate.ready(session);
            throw e;
        }
    }

    @Override
    public int getPriority() {
        return 1000;
    }

    /**
     * 包装真实流，观察解码器真正消费了多少字节。
     */
    private static final class CountingStream extends InputStream {
        private final InputStream delegate;
        private final String session;
        private long delivered;

        private CountingStream(InputStream delegate, String session) {
            this.delegate = delegate;
            this.session = session;
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
            if (delivered >= READY_BYTES) {
                LyricGate.ready(session);
            }
        }
    }

    private static String extensionOf(String url) {
        try {
            String path = new URI(url).getPath();
            if (path == null) {
                return "";
            }
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) {
                return "";
            }
            return path.substring(dot + 1).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "";
        }
    }
}
