package com.splayerbridge.netmusic.client;

import com.splayerbridge.netmusic.SPlayerBridge;
import com.splayerbridge.netmusic.net.ModPayloads;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端侧：通过游戏连接向服务端要音频数据。
 * <p>
 * 服务端按块推过来，这里缓存成队列；播放线程读不到数据时会阻塞等待，
 * 所以「客户端等服务端拿到音频再播」是天然的。
 */
public final class ClientTransport {
    /** 每消费这么多字节回一个 ack，服务端据此控制发送窗口。 */
    private static final int ACK_THRESHOLD = 512 * 1024;
    /** 多久收不到数据就认为服务端那边出问题了。 */
    private static final long WAIT_TIMEOUT_MILLIS = 60_000L;

    private static final Map<Integer, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_SESSION = new AtomicInteger(1);

    private ClientTransport() {
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.AudioChunk.TYPE, (payload, context) -> {
            Session session = SESSIONS.get(payload.session());
            if (session != null) {
                session.push(payload.data(), payload.last());
            } else {
                SPlayerBridge.LOGGER.warn("[SPlayerBridge] 收到未知会话的数据块: {}", payload.session());
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(ModPayloads.AudioError.TYPE, (payload, context) -> {
            Session session = SESSIONS.get(payload.session());
            if (session != null) {
                session.fail(payload.message());
            }
        });
    }

    /**
     * 请求一首歌，返回一个阻塞式的输入流。
     */
    public static InputStream open(long songId) {
        int sessionId = NEXT_SESSION.getAndIncrement();
        Session session = new Session(sessionId);
        SESSIONS.put(sessionId, session);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> ClientPlayNetworking.send(new ModPayloads.AudioRequest(sessionId, songId)));
        SPlayerBridge.LOGGER.info("[SPlayerBridge] 向服务端请求音频 id={} session={}", songId, sessionId);
        return session.stream();
    }

    private static final class Session {
        private final int id;
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private final Object lock = new Object();

        private int offset;
        private boolean done;
        private String error;
        private int unacked;
        private long received;
        private boolean loggedFirstChunk;
        private boolean loggedFirstRead;

        private Session(int id) {
            this.id = id;
        }

        private void push(byte[] data, boolean last) {
            synchronized (lock) {
                if (data != null && data.length > 0) {
                    queue.add(data);
                    received += data.length;
                    if (!loggedFirstChunk) {
                        loggedFirstChunk = true;
                        SPlayerBridge.LOGGER.info("[SPlayerBridge] 收到首块数据 session={} ({} 字节)",
                                id, data.length);
                    }
                }
                if (last) {
                    done = true;
                    SPlayerBridge.LOGGER.info("[SPlayerBridge] 音频接收完成 session={}，共 {} KB",
                            id, received / 1024);
                }
                lock.notifyAll();
            }
        }

        private void fail(String message) {
            synchronized (lock) {
                error = message;
                done = true;
                SPlayerBridge.LOGGER.warn("[SPlayerBridge] 服务端返回错误 session={}: {}", id, message);
                lock.notifyAll();
            }
        }

        private InputStream stream() {
            return new InputStream() {
                private boolean closed;

                @Override
                public int read() throws IOException {
                    byte[] single = new byte[1];
                    int read = read(single, 0, 1);
                    return read < 0 ? -1 : (single[0] & 0xFF);
                }

                @Override
                public int read(byte[] target, int off, int len) throws IOException {
                    if (len == 0) {
                        return 0;
                    }
                    synchronized (lock) {
                        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
                        while (queue.isEmpty()) {
                            if (error != null) {
                                throw new IOException("服务端没有返回音频: " + error);
                            }
                            if (done) {
                                SESSIONS.remove(id);
                                return -1;
                            }
                            long remaining = deadline - System.currentTimeMillis();
                            if (remaining <= 0) {
                                throw new IOException("等待服务端音频超时");
                            }
                            try {
                                lock.wait(Math.min(200L, remaining));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw new IOException("等待被中断", e);
                            }
                        }
                        byte[] head = queue.peek();
                        int count = Math.min(len, head.length - offset);
                        System.arraycopy(head, offset, target, off, count);
                        offset += count;
                        if (offset >= head.length) {
                            queue.poll();
                            offset = 0;
                        }
                        if (!loggedFirstRead) {
                            loggedFirstRead = true;
                            SPlayerBridge.LOGGER.info("[SPlayerBridge] 解码器开始读取 session={}，队列 {} 块",
                                    id, queue.size());
                        }
                        unacked += count;
                        if (unacked >= ACK_THRESHOLD) {
                            int delta = unacked;
                            unacked = 0;
                            Minecraft.getInstance().execute(() ->
                                    ClientPlayNetworking.send(new ModPayloads.AudioAck(id, delta)));
                        }
                        return count;
                    }
                }

                @Override
                public void close() {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    Session removed = SESSIONS.remove(id);
                    if (removed != null && !removed.done) {
                        // -1 表示取消，让服务端别再发了（切歌 / 提前结束）
                        Minecraft.getInstance().execute(() ->
                                ClientPlayNetworking.send(new ModPayloads.AudioAck(id, -1)));
                    }
                }
            };
        }
    }
}
