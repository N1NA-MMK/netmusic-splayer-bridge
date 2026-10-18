package com.splayerbridge.netmusic.net;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务端下发给客户端的「标记地址」。
 * <p>
 * 直接用解锁出来的 CDN 直链也能播，但那样客户端分不清这个链是不是本模组给的，
 * 也就没法在客户端做「先下载再播放」和「歌词门控」。所以服务端把真实地址编码进来，
 * 客户端 {@code RelayAudioHandler} 解出来后自己处理。
 * <p>
 * 形如：
 * <pre>http://netmusic-splayer-bridge.invalid/a/&lt;会话&gt;?u=&lt;URL编码的真实地址&gt;</pre>
 */
public final class Links {
    /** 不会真正被解析的保留域名（RFC 2606）。 */
    public static final String FAKE_HOST = "netmusic-splayer-bridge.invalid";
    private static final String PATH = "/a/";

    private static final AtomicLong SESSIONS = new AtomicLong();

    private Links() {
    }

    /**
     * 生成本次播放专用的标记地址。每次调用都会换一个新会话号，这样客户端的歌词门控
     * 可以精确定位到「这一次播放」。
     */
    public static String wrap(String targetUrl) {
        return "http://" + FAKE_HOST + PATH + SESSIONS.incrementAndGet()
                + "?u=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8);
    }

    /**
     * 解出真实地址；不是本模组生成的地址返回 null。
     */
    public static String unwrap(String url) {
        if (url == null || !url.contains("://" + FAKE_HOST + PATH)) {
            return null;
        }
        int index = url.indexOf("?u=");
        if (index < 0) {
            return null;
        }
        return java.net.URLDecoder.decode(url.substring(index + 3), StandardCharsets.UTF_8);
    }

    /**
     * 从标记地址里取会话号；不是本模组生成的地址返回 null。
     */
    public static String sessionOf(String url) {
        if (url == null || !url.contains("://" + FAKE_HOST + PATH)) {
            return null;
        }
        int start = url.indexOf(PATH) + PATH.length();
        int end = url.indexOf('?', start);
        if (start < PATH.length() || end < 0 || end <= start) {
            return null;
        }
        return url.substring(start, end);
    }
}
