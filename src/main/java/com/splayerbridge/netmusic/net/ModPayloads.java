package com.splayerbridge.netmusic.net;

import com.splayerbridge.netmusic.SPlayerBridge;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 走游戏端口传音频用的数据包定义。
 * <p>
 * 客户端播放时拿到的地址是 <code>http://netmusic-splayer-bridge.invalid/audio/&lt;id&gt;.&lt;ext&gt;</code>，
 * 这个域名永远不会被解析，客户端那边的 {@code RelayAudioHandler} 会拦下来，
 * 改成通过这几个包向服务端要数据。
 */
public final class ModPayloads {
    /** 单个分包大小。太大容易把连接堵住、把 keep-alive 挤掉。 */
    public static final int CHUNK_SIZE = 16 * 1024;

    /** 不会真正被解析的保留域名（RFC 2606）。 */
    public static final String FAKE_HOST = "netmusic-splayer-bridge.invalid";

    private ModPayloads() {
    }

    /** 客户端 → 服务端：我要这首（会开一个新会话）。 */
    public record AudioRequest(int session, long songId) implements CustomPacketPayload {
        public static final Type<AudioRequest> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(SPlayerBridge.MOD_ID, "audio_request"));

        public static final StreamCodec<ByteBuf, AudioRequest> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AudioRequest::session,
                ByteBufCodecs.VAR_LONG, AudioRequest::songId,
                AudioRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：一段音频数据。last 表示这是最后一段。 */
    public record AudioChunk(int session, byte[] data, boolean last) implements CustomPacketPayload {
        public static final Type<AudioChunk> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(SPlayerBridge.MOD_ID, "audio_chunk"));

        public static final StreamCodec<ByteBuf, AudioChunk> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AudioChunk::session,
                ByteBufCodecs.BYTE_ARRAY, AudioChunk::data,
                ByteBufCodecs.BOOL, AudioChunk::last,
                AudioChunk::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：我消费掉了多少字节，用来做流量控制。 */
    public record AudioAck(int session, int consumed) implements CustomPacketPayload {
        public static final Type<AudioAck> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(SPlayerBridge.MOD_ID, "audio_ack"));

        public static final StreamCodec<ByteBuf, AudioAck> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AudioAck::session,
                ByteBufCodecs.VAR_INT, AudioAck::consumed,
                AudioAck::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** 服务端 → 客户端：这首拿不到。 */
    public record AudioError(int session, String message) implements CustomPacketPayload {
        public static final Type<AudioError> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(SPlayerBridge.MOD_ID, "audio_error"));

        public static final StreamCodec<ByteBuf, AudioError> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, AudioError::session,
                ByteBufCodecs.STRING_UTF8, AudioError::message,
                AudioError::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 两侧都要注册（在 ModInitializer 里调用）。
     */
    public static void register() {
        PayloadTypeRegistry.serverboundPlay().register(AudioRequest.TYPE, AudioRequest.STREAM_CODEC);
        PayloadTypeRegistry.serverboundPlay().register(AudioAck.TYPE, AudioAck.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AudioChunk.TYPE, AudioChunk.STREAM_CODEC);
        PayloadTypeRegistry.clientboundPlay().register(AudioError.TYPE, AudioError.STREAM_CODEC);
    }
}
