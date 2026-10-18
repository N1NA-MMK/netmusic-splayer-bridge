package com.splayerbridge.netmusic.client;

import com.splayerbridge.netmusic.SPlayerBridge;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 客户端本地音频缓存。
 * <p>
 * 按歌曲 ID + 扩展名缓存：同一首歌第二次播放直接读本地文件，不再重新下载，
 * 也不再往磁盘里堆文件。总大小有上限，超了按「最久未使用」淘汰。
 */
final class ClientAudioCache {
    /** 缓存目录上限（MB）。 */
    private static final long MAX_TOTAL_MB = 2048;
    /** 单个文件上限，防止下到奇怪的东西。 */
    private static final long MAX_FILE_BYTES = 200L * 1024 * 1024;

    private static final Path DIR = FabricLoader.getInstance().getGameDir().resolve("splayer_bridge_audio");

    private ClientAudioCache() {
    }

    /**
     * 取已缓存的音频文件；没有返回 null。
     */
    static Path lookup(long songId, String extension) {
        Path file = DIR.resolve(songId + "." + extension);
        try {
            if (Files.isRegularFile(file) && Files.size(file) > 4096) {
                return file;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 下载并写入缓存，返回本地文件路径。
     */
    static Path download(long songId, String extension, InputStream source) throws IOException {
        Files.createDirectories(DIR);
        Path target = DIR.resolve(songId + "." + extension);
        Path temp = DIR.resolve(songId + "." + extension + ".part");
        long total = 0;
        try (InputStream in = source;
             var out = Files.newOutputStream(temp,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                total += read;
                if (total > MAX_FILE_BYTES) {
                    throw new IOException("文件超过 200MB");
                }
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        evictIfNeeded();
        return target;
    }

    /**
     * 清理历史遗留的临时文件，并按体积上限淘汰最旧的文件。
     */
    static void cleanup() {
        if (!Files.isDirectory(DIR)) {
            return;
        }
        try (Stream<Path> files = Files.list(DIR)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".part") || !name.matches("\\d+\\.[a-z0-9]{2,5}")) {
                    // 旧版本用「时间戳-会话.扩展名」命名，直接清掉
                    try {
                        Files.deleteIfExists(file);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        evictIfNeeded();
    }

    private static void evictIfNeeded() {
        try (Stream<Path> files = Files.list(DIR)) {
            List<Path> cached = files.sorted(Comparator.comparingLong(ClientAudioCache::lastModified)).toList();
            long total = 0;
            for (Path file : cached) {
                total += sizeOf(file);
            }
            long limit = MAX_TOTAL_MB * 1024 * 1024;
            for (Path file : cached) {
                if (total <= limit) {
                    break;
                }
                long size = sizeOf(file);
                if (Files.deleteIfExists(file)) {
                    total -= size;
                    SPlayerBridge.LOGGER.info("[SPlayerBridge] 缓存上限 {}MB，已淘汰旧文件 {}", MAX_TOTAL_MB,
                            file.getFileName());
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (Exception e) {
            return 0;
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            return 0;
        }
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
