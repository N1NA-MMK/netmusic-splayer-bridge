# Net Music: SPlayer Bridge

给 [NetMusic（网络音乐机）](https://github.com/TartaricAcid/NetMusic) 写的 Fabric 附属模组。

播放网易云 **VIP 歌曲**时，不再走 NetMusic 自带的（需要登录的）取流逻辑，而是由**服务端**问本机运行的
[SPlayer](https://github.com/imsyy/SPlayer) 要解锁后的直链，然后把客户端自己能访问的地址交给它。

音频由客户端自己从 CDN 取：**服务端不转发音频、不占额外端口**，所以 frp 只映射游戏端口也能用。

- Minecraft **26.1.2** / Fabric
- 需要 NetMusic **1.5.x（fabric+mc26.1）**
- 需要 SPlayer 运行在服务端那台机器上（默认 `http://127.0.0.1:25884`）
- **客户端必须安装本模组**（解码、FLAC、歌词同步都在客户端做，详见下文）

## 它是怎么工作的

```
客户端放置 CD → 服务端解析歌曲信息（本模组在此接管）
                    └─ 请求 SPlayer /api/unblock/netease?id=<id>
                         └─ 拿到 CDN 直链，包成标记地址后交给客户端

客户端收到标记地址 → 本模组客户端处理器接管
                    ├─ 非 mp3（FLAC 等）→ 先下载到本地，再用「文件」方式打开
                    ├─ mp3              → 直接流式播放
                    └─ 歌词门控         → 音频没真正开始前，冻结 tick，歌词停住
```

## 两个关键设计

### 1. 为什么 FLAC 要先下载再播放

NetMusic 是用 `AudioSystem.getAudioInputStream(InputStream)` **流式**打开音频的，而 mp3spi 在探测时会把整条流扫空再找同步字，
遇到 FLAC 必抛 `IOException: Resetting to invalid mark`——这不是缓冲大小的问题（实测 8192 / 1MB / 32MB 都失败），
只有按扩展名选 SPI（走 File）才能打开。

所以客户端遇到 FLAC 会先下到本地，再用 `getAudioInputStream(File)` 播放。

### 2. 为什么歌词能对齐音频

MC 的 `SoundEngine.play()` 对流式声音是这么干的：**先把声音实例加进 tick 表（歌词开始计时），同时异步去拉音频流**。
所以音频还没出声，歌词已经走了——这是 NetMusic + MC 的既有行为，所有歌曲都有，不是本模组引入的。

本模组用客户端 mixin 在音频真正就绪前冻结 tick：客户端解码器消费到约 200KB 数据（音频已经开播）时才解除冻结。
每次播放的会话号都不同，所以多台留声机同时播放也不会互相干扰。

## 安装

1. 把 `netmusic-splayer-bridge-x.y.z.jar` 放进**服务端**的 `mods/`
2. 把同一个 jar 放进**客户端**的 `mods/`（必需：解码、FLAC、歌词同步都在客户端做）
3. 确保 NetMusic 1.5.x（Fabric 26.1.2 版）两端都在
4. 在服务端那台机器上启动 SPlayer 并保持运行，确认 `http://127.0.0.1:25884/api` 能返回 JSON
5. 启动，配置自动生成在 `config/netmusic-splayer-bridge.json`

## 配置

改完执行 `/splayerbridge reload` 或重启。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关 |
| `preferForAllNeteaseSongs` | `false` | 为 true 时连非 VIP 的网易云歌曲也走 SPlayer |
| `splayerBaseUrl` | `http://127.0.0.1:25884` | SPlayer 服务地址 |
| `unlockEndpoints` | `/api/unblock/netease?id={id}` 等 | 解锁接口候选，按顺序尝试 |
| `keywordFallback` | `true` | 网易云失败时用「歌名-歌手」去酷我/波点兜底 |
| `requestTimeoutSeconds` | `6` | 单次请求超时 |
| `totalResolveTimeoutSeconds` | `15` | 单首歌解析总超时 |
| `logUnlocks` | `true` | 是否在日志打印解锁结果 |
| `downloadBeforePlay` | `true` | 客户端：非 mp3 格式先下载到本地再播放（关闭的话 FLAC 基本播不了） |
| `lyricOffsetTicks` | `0` | 歌词再延后多少 tick（20 tick = 1 秒）。正常情况下门控已经对齐了，这只用于微调 |

## 命令

需要 OP（权限等级 2）：

```
/splayerbridge status   查看开关、SPlayer 在线状态
/splayerbridge reload   重新读取配置
```

## 已知限制

- 只处理网易云歌曲（CD 地址里能解析出 `id=` 的），B 站、电台等其它来源不受影响
- 广播喇叭（Big Megaphone）走另一套逻辑，未经本模组
- 客户端必须能访问网易云 CDN——frp 只映射游戏端口正好满足这个前提
- FLAC 要先下完整首才开播（受解码器限制），歌词会被门控等它，不会超前
- SPlayer 不可用时回退 NetMusic 原本行为（VIP 歌曲播不出来）

## 免责声明

本模组只是把 SPlayer 已经提供的解锁结果接到 NetMusic 的播放链路上，不包含、也不提供任何音频内容。
音频版权归网易云音乐及版权方所有，请勿将音频用于传播或商业用途。

## 许可

MIT
