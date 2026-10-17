package com.splayerbridge.netmusic;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网易云歌曲地址相关的工具方法。
 * <p>
 * NetMusic 的 CD 里保存的是语义地址，例如
 * <code>https://music.163.com/song/media/outer/url?id=4932606.mp3</code>，
 * 真正需要的只是其中的歌曲 ID。
 */
public final class SongIds {
    private static final Pattern ID_PATTERN = Pattern.compile("[?&#]id=(\\d+)");
    private static final Pattern HOST_SUFFIX = Pattern.compile("(^|\\.)163\\.com$");

    private SongIds() {
    }

    /**
     * 是否是网易云的歌曲地址。
     */
    public static boolean isNeteaseUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String host = hostOf(url);
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.ROOT);
        // music.163.com / interface.music.163.com / 各种镜像子域
        return HOST_SUFFIX.matcher(host).find() || host.contains("music.163");
    }

    /**
     * 从地址里提取歌曲 ID，取不到返回 null。
     */
    public static Long extractId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        Matcher matcher = ID_PATTERN.matcher(url);
        if (matcher.find()) {
            try {
                return Long.parseLong(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String hostOf(String url) {
        try {
            URI uri = URI.create(url.replace(" ", "%20"));
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
