package com.splayerbridge.netmusic.client;

import com.github.tartaricacid.netmusic.api.NetEaseMusic;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 把音频先落到本地临时文件，再用 {@code AudioSystem.getAudioInputStream(File)} 打开的辅助类。
 * <p>
 * NetMusic 官方是流式打开音频的（{@code getAudioInputStream(InputStream)}），这条路遇到 FLAC 必挂：
 * mp3spi 会先试探一遍，找不到同步字就把整个流读空，之后 {@code reset()} 超出 mark 缓冲，
 * 抛出 {@code IOException: Resetting to invalid mark}。走 File 则按扩展名选 SPI，完全没这个问题。
 */
final class LocalFileAudio {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final Path DIR = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
            .resolve("splayer_bridge_audio");

    private LocalFileAudio() {
    }

    /**
     * 下载直链到临时文件。
     */
    static Path downloadFromUrl(String url, String name) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header(HttpHeaders.USER_AGENT, NetEaseMusic.getUserAgent())
                .GET()
                .build();
        try (InputStream in = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream()).body()) {
            return write(in, name);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("下载音频失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把已经拿到的流（例如走游戏端口收到的）写到临时文件。
     */
    static Path write(InputStream in, String name) throws IOException {
        Files.createDirectories(DIR);
        Path target = DIR.resolve(name);
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        try (InputStream input = in) {
            try (var out = Files.newOutputStream(target,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                int read;
                while ((read = input.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    total += read;
                    if (total > 200L * 1024 * 1024) {
                        throw new IOException("文件超过 200MB");
                    }
                }
            }
        } finally {
            target.toFile().deleteOnExit();
        }
        if (total < 4096) {
            Files.deleteIfExists(target);
            throw new IOException("下载到的内容过小（" + total + " 字节）");
        }
        return target;
    }

    /**
     * 取扩展名，不含点；没有扩展名时返回空串。
     */
    static String extension(String path) {
        if (path == null) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "";
        }
        return path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
