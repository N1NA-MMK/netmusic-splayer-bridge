package com.splayerbridge.netmusic;

import com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.splayerbridge.netmusic.cache.AudioCache;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * 把网易云 VIP 歌曲交给 SPlayer 解锁的解析器。
 * <p>
 * 流程：
 * <ol>
 *     <li>命中本地缓存 → 直接把服务端缓存地址交给客户端（秒开）</li>
 *     <li>没有缓存 → 请求 SPlayer 解锁拿到直链，服务端开始下载，同时把
 *         <b>服务端自己的地址</b>交给客户端；客户端连上来后读不到数据会等待，
 *         服务端下到哪就发到哪（边下边播），全程客户端不直连网易云</li>
 *     <li>SPlayer 不可用 → 原样返回，交给 NetMusic 原本的逻辑处理</li>
 * </ol>
 */
public final class SPlayerSongResolver implements IAsyncSongUrlResolver {

    @Override
    public boolean canResolve(ItemMusicCD.SongInfo songInfo) {
        BridgeConfig config = SPlayerBridge.config();
        if (!config.enabled || songInfo == null) {
            return false;
        }
        if (!SongIds.isNeteaseUrl(songInfo.songUrl)) {
            return false;
        }
        return songInfo.vip || config.preferForAllNeteaseSongs;
    }

    @Override
    public CompletableFuture<ItemMusicCD.SongInfo> resolve(ItemMusicCD.SongInfo songInfo) {
        Long songId = SongIds.extractId(songInfo.songUrl);
        if (songId == null) {
            return CompletableFuture.completedFuture(songInfo);
        }

        BridgeConfig config = SPlayerBridge.config();
        Optional<String> cachedName = AudioCache.fileName(songId);
        if (cachedName.isPresent()) {
            Optional<String> url = AudioCache.urlFor(songId, cachedName.get());
            if (url.isPresent()) {
                songInfo.songUrl = url.get();
                if (config.logCacheHits) {
                    SPlayerBridge.LOGGER.info("[SPlayerBridge] 缓存命中: {} (id={})", songInfo.songName, songId);
                }
                return CompletableFuture.completedFuture(songInfo);
            }
        }

        List<String> artists = songInfo.artists;
        String artist = (artists == null || artists.isEmpty()) ? "" : artists.get(0);

        return SPlayerApi.unlock(songId, songInfo.songName, artist).thenApply(result -> {
            if (result.isPresent()) {
                String audioUrl = result.get();
                Optional<String> url = AudioCache.ensure(songId, audioUrl)
                        .flatMap(name -> AudioCache.urlFor(songId, name));
                if (url.isPresent()) {
                    // 交给客户端的是服务端地址：客户端来取数据时服务端边下边发
                    songInfo.songUrl = url.get();
                    SPlayerBridge.LOGGER.info("[SPlayerBridge] 已解锁并由服务端转发: {} (id={}, {})",
                            songInfo.songName, songId, AudioCache.isPacketTransport() ? "游戏端口" : "HTTP");
                } else {
                    // 缓存/下发不可用时退回直链，至少还能播
                    songInfo.songUrl = audioUrl;
                    SPlayerBridge.LOGGER.warn("[SPlayerBridge] 服务端下发不可用，客户端将直连: {} (id={})",
                            songInfo.songName, songId);
                }
            } else {
                SPlayerBridge.LOGGER.warn("[SPlayerBridge] 解锁失败，回退原始地址: {} (id={})",
                        songInfo.songName, songId);
            }
            return songInfo;
        });
    }

    @Override
    public int getPriority() {
        // 高于其它解析器（如登录类附属），保证 SPlayer 优先
        return 1000;
    }
}
