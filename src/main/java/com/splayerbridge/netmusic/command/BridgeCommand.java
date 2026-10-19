package com.splayerbridge.netmusic.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.splayerbridge.netmusic.BridgeConfig;
import com.splayerbridge.netmusic.SPlayerApi;
import com.splayerbridge.netmusic.SPlayerBridge;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * <pre>
 * /splayerbridge status   查看状态
 * /splayerbridge reload   重新读取配置
 * </pre>
 */
public final class BridgeCommand {
    private static final String ROOT = "splayerbridge";

    private BridgeCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal(ROOT)
                // 不用 Commands.hasPermission(int)：它在 1.20.1 是两个参数，
                // CommandSourceStack.hasPermission(int) 各版本签名一致
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("status").executes(BridgeCommand::status))
                .then(Commands.literal("reload").executes(BridgeCommand::reload));
    }

    private static int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        BridgeConfig config = SPlayerBridge.config();
        long probeAge = SPlayerApi.lastProbeMillis() == 0
                ? -1
                : (System.currentTimeMillis() - SPlayerApi.lastProbeMillis()) / 1000;
        String text = """
                §8[§bSPlayerBridge§8] §7状态
                §7 开关: §f%s§7 | 全部歌曲: §f%s
                §7 SPlayer: §f%s §7(§f%s§7, %s)
                §7 下发方式: §f客户端直连（服务端不转发音频）"""
                .formatted(
                        config.enabled ? "开启" : "关闭",
                        config.preferForAllNeteaseSongs ? "是" : "否（仅 VIP）",
                        config.splayerBaseUrl,
                        SPlayerApi.isAlive() ? "在线" : "离线",
                        probeAge < 0 ? "尚未探测" : probeAge + " 秒前探测");
        context.getSource().sendSuccess(() -> Component.literal(text), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        SPlayerBridge.reload();
        context.getSource().sendSuccess(() -> Component.literal("§8[§bSPlayerBridge§8] §a配置已重新加载"), false);
        return Command.SINGLE_SUCCESS;
    }
}
