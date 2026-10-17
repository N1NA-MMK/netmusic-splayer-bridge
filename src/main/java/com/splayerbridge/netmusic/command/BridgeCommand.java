package com.splayerbridge.netmusic.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.splayerbridge.netmusic.BridgeConfig;
import com.splayerbridge.netmusic.SPlayerApi;
import com.splayerbridge.netmusic.SPlayerBridge;
import com.splayerbridge.netmusic.cache.AudioCache;
import com.splayerbridge.netmusic.cache.AudioCacheService;
import com.splayerbridge.netmusic.cache.CacheHttpServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * <pre>
 * /splayerbridge status   查看状态
 * /splayerbridge reload   重新读取配置
 * /splayerbridge cache clear  清空缓存
 * </pre>
 */
public final class BridgeCommand {
    private static final String ROOT = "splayerbridge";

    private BridgeCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal(ROOT)
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(BridgeCommand::status))
                .then(Commands.literal("reload").executes(BridgeCommand::reload))
                .then(Commands.literal("cache")
                        .then(Commands.literal("clear").executes(BridgeCommand::clearCache)));
    }

    private static int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        BridgeConfig config = SPlayerBridge.config();
        long probeAge = SPlayerApi.lastProbeMillis() == 0
                ? -1
                : (System.currentTimeMillis() - SPlayerApi.lastProbeMillis()) / 1000;
        String base = CacheHttpServer.baseUrl();
        String text = """
                §8[§bSPlayerBridge§8] §7状态
                §7 开关: §f%s§7 | 全部歌曲: §f%s
                §7 SPlayer: §f%s §7(§f%s§7, %s)
                §7 缓存: §f%s §7首 | 目录: §f%s
                §7 缓存服务: §f%s"""
                .formatted(
                        config.enabled ? "开启" : "关闭",
                        config.preferForAllNeteaseSongs ? "是" : "否（仅 VIP）",
                        config.splayerBaseUrl,
                        SPlayerApi.isAlive() ? "在线" : "离线",
                        probeAge < 0 ? "尚未探测" : probeAge + " 秒前探测",
                        AudioCache.count(),
                        AudioCache.directory() == null ? "-" : AudioCache.directory(),
                        base == null ? "未启动" : base);
        context.getSource().sendSuccess(() -> Component.literal(text), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        SPlayerBridge.reload();
        context.getSource().sendSuccess(() -> Component.literal("§8[§bSPlayerBridge§8] §a配置已重新加载"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int clearCache(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        if (!AudioCacheService.isStarted()) {
            context.getSource().sendFailure(Component.literal("§c缓存服务尚未启动"));
            return 0;
        }
        int removed = AudioCache.clear();
        context.getSource().sendSuccess(
                () -> Component.literal("§8[§bSPlayerBridge§8] §a已删除 " + removed + " 个缓存文件"), false);
        return Command.SINGLE_SUCCESS;
    }
}
