package com.splayerbridge.netmusic.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/**
 * 客户端把音频写到本地临时文件的小工具。
 * <p>
 * NetMusic 流式打开音频时会被 mp3spi 把整条流扫空（FLAC 直接抛
 * {@code IOException: Resetting to invalid mark}），改成「先落盘、再用文件打开」就绕过去了。
 */
final class LocalFileAudio {
    private static final Path DIR = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
            .resolve("splayer_bridge_audio");
    private static final long MAX_BYTES = 200L * 1024 * 1024;

    private LocalFileAudio() {
    }

    /**
     * 把流完整写到本地文件后返回路径。
     */
    static Path write(InputStream in, String name) throws IOException {
        Files.createDirectories(DIR);
        Path target = DIR.resolve(name);
        long total = 0;
        try (InputStream input = in;
             var out = Files.newOutputStream(target,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
                if (total > MAX_BYTES) {
                    throw new IOException("文件超过 200MB");
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
