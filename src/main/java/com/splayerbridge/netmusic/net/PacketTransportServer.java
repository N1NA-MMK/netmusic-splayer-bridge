package com.splayerbridge.netmusic.net;

import com.splayerbridge.netmusic.SPlayerBridge;
import com.splayerbridge.netmusic.cache.AudioCache;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端侧：把缓存的音频切块，通过游戏连接发给客户端。
 * <p>
 * 适用于客户端只能访问到服务器游戏端口的情况（frp 反代、只映射 25565 等），
 * 这种拓扑下客户端连不到 25885，只能借游戏端口传输。
 * <p>
 * 流量控制：客户端每消费 512KB 回一个 ack，服务端保持在途数据不超过 {@link #WINDOW_BYTES}，
 * 避免把 Netty 的写缓冲撑爆。
 */
public final class PacketTransportServer {
    private static final long WINDOW_BYTES = 4L * 1024 * 1024;
    private static final int MAX_CHUNKS_PER_TICK = 8;

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();

    private PacketTransportServer() {
    }

    public static void init() {
        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.AudioRequest.TYPE, (payload, context) ->
                context.server().execute(() -> onRequest(context.player(), payload)));

        ServerPlayNetworking.registerGlobalReceiver(ModPayloads.AudioAck.TYPE, (payload, context) ->
                context.server().execute(() -> onAck(context.player(), payload)));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (Session session : SESSIONS.values()) {
                try {
                    session.pump();
                } catch (Exception e) {
                    session.fail(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Session session = SESSIONS.remove(handler.player.getUUID());
            if (session != null) {
                session.close();
            }
        });
    }

    private static void onRequest(ServerPlayer player, ModPayloads.AudioRequest payload) {
        if (!ServerPlayNetworking.canSend(player, ModPayloads.AudioChunk.TYPE)) {
            SPlayerBridge.LOGGER.error("[SPlayerBridge] {} 的客户端没有安装本模组（或版本不匹配），"
                    + "无法通过游戏端口下发音频。请让客户端也装上本模组，或把 transport 改回 http。",
                    player.getName().getString());
            return;
        }
        Session previous = SESSIONS.remove(player.getUUID());
        if (previous != null) {
            previous.close();
        }
        SESSIONS.put(player.getUUID(), new Session(payload.session(), player, payload.songId()));
        SPlayerBridge.LOGGER.info("[SPlayerBridge] {} 请求音频 id={}（走游戏端口）",
                player.getName().getString(), payload.songId());
    }

    private static void onAck(ServerPlayer player, ModPayloads.AudioAck payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || session.id != payload.session()) {
            return;
        }
        if (payload.consumed() < 0) {
            // 客户端切歌 / 提前结束
            session.close();
            return;
        }
        if (payload.consumed() > 0) {
            session.acked += payload.consumed();
        }
    }

    private static final class Session {
        private final int id;
        private final ServerPlayer player;
        private final long songId;

        private RandomAccessFile file;
        private AudioCache.Download download;
        private boolean complete;
        private long position;
        private long sent;
        private long acked;
        private boolean closed;

        private Session(int id, ServerPlayer player, long songId) {
            this.id = id;
            this.player = player;
            this.songId = songId;
        }

        private void pump() throws Exception {
            if (closed) {
                return;
            }
            if (player.hasDisconnected()) {
                close();
                return;
            }
            if (file == null) {
                Optional<Path> cached = AudioCache.findComplete(songId);
                if (cached.isPresent()) {
                    file = new RandomAccessFile(cached.get().toFile(), "r");
                    complete = true;
                } else {
                    Optional<AudioCache.Download> active = AudioCache.activeDownload(songId);
                    if (active.isEmpty()) {
                        fail("服务端没有这首歌的缓存，也没有正在进行的下载");
                        return;
                    }
                    download = active.get();
                    if (download.isFailed()) {
                        fail(download.error());
                        return;
                    }
                    if (!Files.exists(download.file())) {
                        // 还没写出第一个字节，下一 tick 再看
                        return;
                    }
                    file = new RandomAccessFile(download.file().toFile(), "r");
                }
            }

            long available = complete ? file.length() : download.written();
            int chunks = 0;
            while (chunks < MAX_CHUNKS_PER_TICK && sent - acked < WINDOW_BYTES && position < available) {
                int length = (int) Math.min(ModPayloads.CHUNK_SIZE, available - position);
                byte[] data = new byte[length];
                file.seek(position);
                file.readFully(data);
                ServerPlayNetworking.send(player, new ModPayloads.AudioChunk(id, data, false));
                position += length;
                sent += length;
                chunks++;
            }

            if (!complete && download != null) {
                if (download.isComplete()) {
                    complete = true;
                } else if (download.isFailed()) {
                    fail(download.error());
                    return;
                }
            }

            if (complete && position >= file.length()) {
                ServerPlayNetworking.send(player, new ModPayloads.AudioChunk(id, new byte[0], true));
                SPlayerBridge.LOGGER.info("[SPlayerBridge] 音频发送完成 id={}（{} KB）", songId, sent / 1024);
                close();
            }
        }

        private void fail(String message) {
            if (closed) {
                return;
            }
            SPlayerBridge.LOGGER.warn("[SPlayerBridge] 发送音频失败 id={}: {}", songId, message);
            ServerPlayNetworking.send(player, new ModPayloads.AudioError(id, message));
            close();
        }

        private void close() {
            closed = true;
            SESSIONS.remove(player.getUUID(), this);
            if (file != null) {
                try {
                    file.close();
                } catch (Exception ignored) {
                }
                file = null;
            }
        }
    }
}
