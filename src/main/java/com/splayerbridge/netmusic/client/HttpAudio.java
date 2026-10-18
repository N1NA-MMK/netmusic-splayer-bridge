package com.splayerbridge.netmusic.client;

import com.github.tartaricacid.netmusic.api.NetEaseMusic;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 客户端下载音频的小工具。 */
final class HttpAudio {
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private HttpAudio() {
    }

    static InputStream open(URL url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .header(HttpHeaders.USER_AGENT, NetEaseMusic.getUserAgent())
                .GET()
                .build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
    }

    /**
     * 最多读 limit 字节；到 EOF 或已满就停，不做「读满才返回」的阻塞。
     */
    static byte[] readUpTo(InputStream in, int limit) throws IOException {
        byte[] buffer = new byte[limit];
        int total = 0;
        while (total < limit) {
            int read = in.read(buffer, total, limit - total);
            if (read < 0) {
                break;
            }
            total += read;
        }
        if (total == limit) {
            return buffer;
        }
        byte[] trimmed = new byte[total];
        System.arraycopy(buffer, 0, trimmed, 0, total);
        return trimmed;
    }
}
