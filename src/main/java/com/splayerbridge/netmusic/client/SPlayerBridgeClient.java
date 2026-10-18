package com.splayerbridge.netmusic.client;

import com.github.tartaricacid.netmusic.client.api.AudioStreamHandlerManager;
import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端入口：注册音频处理器。
 */
public class SPlayerBridgeClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 必须在 NetMusic 冻结处理器列表（CLIENT_STARTED）之前注册
        AudioStreamHandlerManager.registerHandler(new RelayAudioHandler());
    }
}
