# 网络音乐机：SPlayer 桥接（Net Music: SPlayer Bridge）

> 本文是给 MC百科编辑页准备的正文，可直接粘贴。
>
> **粘贴须知**：百科正文不解析 Markdown，标题必须用辅助标签且**单独占一行**、不要加粗或多余样式：
>
> - 二级标题 `[h2=标题]`、三级标题 `[h3=标题]`
> - 调整目录位置 `[mark:title_menu]`（默认显示在正文顶部）
> - 不需要目录时在任意位置放 `[ban:title_menu]`
> - 插入图标 `[icon:图标ID]`
>
> 提交前先点「正文预览」确认目录和标题层级正常。

---

## 简介（词条摘要，填在「Mod介绍」框）

给[网络音乐机（Net Music）](https://www.mcmod.cn/class/4935.html)的附属模组：播放网易云 VIP 歌曲时，由**服务端**向本机运行的 SPlayer 请求解锁，再把音频交给**客户端**自己从 CDN 播放。

不需要网易云账号，不占用额外端口（frp 只映射游戏端口即可），同时修掉了原版两个毛病：FLAC 格式播放失败、歌词比音频提前开唱。

---

## 正文（从下面的 [h2=概述] 开始复制）

[h2=概述]

[网络音乐机](https://www.mcmod.cn/class/4935.html)播放网易云 VIP 歌曲，需要客户端登录网易云账号。实际场景里这往往走不通：专用服务端没有账号，或者客户端走 frp 之类只映射了游戏端口的隧道。

本模组把这一步换掉：**服务端**向本机运行的 [SPlayer](https://github.com/imsyy/SPlayer) 请求解锁，拿到直链后交给客户端，音频由客户端自己从 CDN 取。

- 全程不需要网易云账号
- 服务端不转发音频、不占额外端口，frp 只映射 `25565` 即可直接使用
- VIP 歌曲真的能播，包括原版根本播不了的 FLAC

[h3=谁需要装]

**服务端和客户端都要装，缺一不可。**

服务端负责解锁，客户端负责解码、FLAC 落盘和歌词同步。只装一边是不行的。

[h3=两个修掉的老毛病]

[h3=FLAC 播放失败]

原版是**流式**打开音频的（`AudioSystem.getAudioInputStream(InputStream)`），mp3spi 探测时会把整条流扫空再找同步字，于是每首 FLAC 都以 `IOException: Resetting to invalid mark` 收场。这跟缓冲大小无关（实测 8KB / 1MB / 32MB 均失败），只有按扩展名走 File 才能打开。

本模组在客户端把非 mp3 音频先下到本地，再用文件方式打开，FLAC 即可正常播放，并按歌曲 ID 缓存，同一首歌只下一次。

[h3=歌词比音频提前开唱]

MC 的 `SoundEngine.play()` 在把流式声音加进 tick 表的**同一时刻**才开始异步拉音频流，歌词由这个 tick 驱动，所以总是比音频早走「加载耗时」——这是**所有歌曲都有**的原版行为，不是本模组引入的。

本模组用客户端 Mixin 在音频真正开播前冻结 tick。放行时机是「解码器消费掉约 4 秒音频」，正好对应原版开播前的预读量，歌词与音频的误差在几十毫秒级。每次播放会话独立，多台唱片机互不干扰。

[h2=工作原理]

```
客户端放入 CD → 服务端解析歌曲信息（本模组在此接管）
                 └─ 请求 SPlayer /api/unblock/netease?id=<歌曲ID>
                      └─ 拿到 CDN 直链，包成标记地址后交给客户端

客户端收到标记地址 → 本模组客户端处理器接管
                 ├─ 非 mp3（FLAC 等）→ 先下到本地并缓存，再用「文件」方式打开
                 ├─ mp3              → 直接流式播放
                 └─ 歌词门控         → 音频没真正开播前冻结 tick，歌词停住
```

客户端在日志里会打印这两行，排查时很有用：

```
[SPlayerBridge] 开始播 flac 音频（会话 7，歌曲 2051267438），先下载
[SPlayerBridge] 歌词门控解除（会话 7，等待 1830 ms，已消费 512 KB）
```

[h2=安装]

[h3=支持哪些版本]

| MC 版本 | Java | Fabric Loader | NetMusic |
| --- | --- | --- | --- |
| **26.1.2** | ≥ 25 | ≥ 0.19.2 | 1.5.x（fabric + mc26.1） |
| **1.21.1** | ≥ 21 | ≥ 0.16.9 | 1.5.x（fabric + mc1.21.1） |
| **1.20.1** | ≥ 17 | ≥ 0.16.5 | 1.5.x（fabric + mc1.20.1） |

其它条件：服务端运行 [SPlayer](https://github.com/imsyy/SPlayer)（默认监听 `http://127.0.0.1:25884`）；
客户端能访问网易云 CDN（frp 只转发游戏端口时正好满足）。

[h3=步骤]

1. 下载对应 MC 版本的 jar，文件名里带版本，例如 `netmusic-splayer-bridge-mc1.21.1-x.y.z.jar`
2. 放进**服务端**的 `mods/` 目录
3. 把**同一个** jar 放进**客户端**的 `mods/` 目录
4. 确认两端都装了对应版本的 NetMusic 1.5.x（Fabric）
5. 在服务端那台机器上启动 SPlayer 并保持运行，浏览器打开 `http://127.0.0.1:25884/api` 应返回 JSON
6. 启动游戏，配置自动生成在 `config/netmusic-splayer-bridge.json`

[h2=配置]

配置文件位于 `config/netmusic-splayer-bridge.json`，修改后执行 `/splayerbridge reload` 或重启生效。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关 |
| `preferForAllNeteaseSongs` | `false` | 为 `true` 时连非 VIP 的网易云歌曲也走 SPlayer |
| `splayerBaseUrl` | `http://127.0.0.1:25884` | SPlayer 服务地址 |
| `unlockEndpoints` | `/api/unblock/netease?id={id}` 等四条 | 解锁接口候选，按顺序尝试，`{id}` 会被替换为歌曲 ID |
| `keywordFallback` | `true` | 网易云解锁失败时，用「歌名-歌手」去酷我、波点兜底（音质和时长可能不同） |
| `keywordEndpoints` | 酷我、波点两条 | 兜底接口，`{keyword}`、`{name}`、`{artist}` 会被替换 |
| `requestTimeoutSeconds` | `6` | 单次 HTTP 请求超时（秒） |
| `totalResolveTimeoutSeconds` | `15` | 一首歌解析的总超时，超过后放弃解锁、回退原地址 |
| `logUnlocks` | `true` | 是否在日志打印解锁结果 |
| `downloadBeforePlay` | `true` | 客户端：非 mp3 格式先下载到本地再播放。关闭后 FLAC 基本播不了，只建议排查问题时关 |
| `lyricOffsetTicks` | `0` | 歌词再延后多少 tick（20 tick = 1 秒）。门控已经对齐了，这只用于微调 |

[h2=命令]

需要权限等级 2（OP）：

[h3=/splayerbridge status]

查看开关状态、是否处理全部歌曲、SPlayer 地址与在线状态、最近一次探测时间。

[h3=/splayerbridge reload]

重新读取配置文件。

[h2=客户端磁盘缓存]

非 mp3 格式（FLAC 等）会下载到 `<游戏目录>/splayer_bridge_audio/<歌曲ID>.<扩展名>`：

- 同一首歌只下载一次，之后直接读本地，秒开
- 总大小上限 **2GB**，超出后按「最久未使用」自动淘汰，日志会说明淘汰了哪个文件
- 想手动清空，直接删掉这个目录即可
- 首次启动会清掉旧版本遗留的「时间戳-会话」命名文件

[h2=常见问题]

[h3=VIP 歌曲播不出来/提示播放失败]

按顺序检查：

1. 服务端日志有没有 `已解锁并交由客户端直连`？没有说明 SPlayer 没返回结果，看服务端是不是开着 SPlayer、`splayerBaseUrl` 对不对
2. 客户端日志有没有 `开始播 xxx 音频`？没有说明客户端没拿到本模组的地址，检查客户端 jar 装没装、版本是否一致
3. 有 `开始播` 但报错，多半是客户端连不上网易云 CDN，或者下载中断（看日志里的异常）
4. SPlayer 不可用时本模组会静默回退原版行为，VIP 歌曲自然播不出来——这是预期行为

[h3=歌词和音频对不上]

看客户端日志里 `歌词门控解除（会话 N，等待 X ms，已消费 Y KB）` 这一行：

- **没有这一行**，说明门控没工作，检查客户端 jar 版本（1.3.0 及更早的门控判定不准，建议升级到 1.3.1 以上）
- **有这一行、歌词仍偏快**，把 `等待 X ms` 和实际偏差一起反馈，可按 `lyricOffsetTicks` 补：偏差 0.5 秒就设 `10`（20 tick = 1 秒）
- 如果用过 **1.3.0 或更早版本**，门控的判定依据不准（FLAC 路径只等下载、不等解码，且阈值是写死的字节数），表现为「第一首碰巧对齐、后面随机偏」。请升到 1.3.1 以上再看

[h3=提示 IOException: Resetting to invalid mark]

说明音频走了流式打开且格式是 FLAC。确认 `downloadBeforePlay` 是 `true`，并且客户端装的是本模组（这个修复只在客户端生效）。

[h3=为什么占用磁盘]

非 mp3 格式必须整首下载到本地才能播（解码器限制），所以每首歌会占 20～40MB。缓存有 2GB 上限，超出自动淘汰最旧的。

[h3=只想让服务端用，客户端能省吗]

不能。解码、FLAC 落盘、歌词门控都在客户端，只装服务端等于什么都没发生。

[h2=已知限制]

- 只处理网易云歌曲（CD 地址里能解析出 `id=` 的），B 站、电台等其它来源不受影响
- 广播喇叭（Big Megaphone）走另一套播放逻辑，不经本模组
- 客户端必须能访问网易云 CDN
- FLAC 要先下完整首才开播（解码器限制），歌词门控会等它，不会提前唱
- 关键词兜底（酷我/波点）可能返回音质或时长不同的版本
- SPlayer 不可用时静默回退原版行为，VIP 歌曲将无法播放

[h2=卸载与清理]

1. 删除服务端和客户端 `mods/` 里的 jar
2. 删除 `config/netmusic-splayer-bridge.json`
3. 如需释放磁盘，删除客户端 `<游戏目录>/splayer_bridge_audio/` 整个目录

[h2=免责声明]

本模组仅将 SPlayer 已提供的解锁结果接入 NetMusic 的播放链路，不包含也不分发任何音频内容。
音频版权归网易云音乐及其版权方所有，请勿将下载的音频用于传播或商业用途。

---

================ 以上全部复制到百科正文 ================

---

## 词条其它字段

| 字段 | 填写内容 |
| --- | --- |
| 模组名 | 网络音乐机：SPlayer 桥接 |
| 英文名 | Net Music: SPlayer Bridge |
| 支持平台 | JAVA 版 |
| 运作方式 | Fabric |
| 运行环境 | 客户端需装、服务端需装 |
| MC 版本 | 26.1.2 / 1.21.1 / 1.20.1 |
| 授权协议 | MIT |
| 标签 | 网络音乐机、音乐、播放、VIP、SPlayer |
| 前置 Mod | 网络音乐机（Net Music）、Fabric API |
| 相关链接 | Modrinth、GitHub（`https://github.com/N1NA-MMK/netmusic-splayer-bridge`） |
| 资料类别 | Mod |

## 外链准备

MC百科的「相关链接」需要真实可访问地址，目前可填：

- GitHub 源码与 Issue：`https://github.com/N1NA-MMK/netmusic-splayer-bridge`
- Modrinth：待上架后补充（文案已备好在 `docs/modrinth-description-en.md`）
