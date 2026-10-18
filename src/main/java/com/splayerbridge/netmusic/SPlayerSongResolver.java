package com.splayerbridge.netmusic;

import com.github.tartaricacid.netmusic.api.resolver.IAsyncSongUrlResolver;
import com.github.tartaricacid.netmusic.item.ItemMusicCD;
import com.splayerbridge.netmusic.net.Links;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 把网易云 VIP 歌曲交给 SPlayer 解锁的解析器。
 * <p>
 * 流程：
 * <ol>
 *     <li>请求 SPlayer 解锁，拿到 CDN 直链</li>
 *     <li>把直链包成标记地址交给客户端（客户端必装本模组，才能正确处理歌词和 FLAC）</li>
 *     <li>SPlayer 不可用 → 原样返回，交给 NetMusic 原本的逻辑处理</li>
 * </ol>
 * 服务端不转发音频、不占额外端口，音频由客户端自己从 CDN 取。
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

        List<String> artists = songInfo.artists;
        String artist = (artists == null || artists.isEmpty()) ? "" : artists.get(0);

        return SPlayerApi.unlock(songId, songInfo.songName, artist).thenApply(result -> {
            if (result.isPresent()) {
                // 包一层标记地址：客户端才知道这是本模组给的链接，
                // 才会做「非 mp3 先下载再播放」和「等音频就绪再走歌词」
                songInfo.songUrl = Links.wrap(result.get());
                SPlayerBridge.LOGGER.info("[SPlayerBridge] 已解锁并交由客户端直连: {} (id={})",
                        songInfo.songName, songId);
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
