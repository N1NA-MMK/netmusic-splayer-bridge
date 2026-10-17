# Net Music: SPlayer Bridge

给 [NetMusic（网络音乐机）](https://github.com/TartaricAcid/NetMusic) 写的 Fabric 附属模组。

播放网易云 **VIP 歌曲**时，不再走 NetMusic 自带的（需要登录的）取流逻辑，而是让**服务端**去问本机运行的 SPlayer 要音频，
然后由服务端把音频**转发**给客户端：客户端只连服务端，不直连网易云，也不需要自己能访问外网。

- Minecraft **26.1.2** / Fabric
- 需要 NetMusic **1.5.x（fabric+mc26.1）**
- 需要 SPlayer 运行在服务端那台机器上（默认 `http://127.0.0.1:25884`）

## 它是怎么工作的

```
客户端放置 CD → 服务端 TileEntityMusicPlayer#setPlayToClient
                    │
                    ├─ MusicPlayResolverManager.resolve(SongInfo)   ← 本模组在这里接管
                    │      ├─ 已缓存    → 返回 http://<host>:25885/audio/<id>.<ext>（秒开）
                    │      └─ 未缓存    → GET /api/unblock/netease?id=<id> 拿直链
                    │                      └─ 服务端开始下载，并把「自己的地址」交给客户端
                    │
                    └─ MusicToClientMessage 把 URL 发给附近客户端
                              │
                              └─ 客户端 GET http://<host>:25885/audio/<id>.<ext>
                                     ├─ 服务端还在下 → chunked 边下边发，客户端读不到就等
                                     └─ 下完了       → 普通文件服务，支持 Range
```

（上面画的是 `transport=http` 的情况；`transport=packet` 时最后一步换成：客户端通过游戏连接请求，服务端按 32KB 分包推回来。）

几个关键点：

- NetMusic 的音频是**客户端**解码播放的，所以服务端下载好的音频要下发给客户端。有两种方式，用配置里的 `transport` 选：
  - `http`（默认）：服务端开一个只读 HTTP 服务（默认 25885），客户端直接连。**客户端不需要装本模组**，但要求客户端能访问这个端口。
  - `packet`：**通过游戏端口分包传输**（32KB 一块，带 ack 流量控制）。客户端**必须也装本模组**。适合 frp 反代、只映射了游戏端口、或者端口不通的服务器。
- **客户端全程不直连网易云**：首次播放时客户端拿到的也是服务端地址，连上后服务端下到哪发到哪（首字节通常 1 秒内），播完这首文件也就缓存好了，下次直接读磁盘。
- 缓存文件在下载过程中带一个 `<id>.<ext>.part` 标记，下完才删；中途退出游戏不会留下半首歌被当成完整缓存。

## 安装

1. 把 `netmusic-splayer-bridge-1.1.0.jar` 丢进 `mods/`
2. 确保 NetMusic 1.5.x（Fabric 26.1.2 版）也在 `mods/` 里
3. 启动一次 SPlayer（保持运行），确认 `http://127.0.0.1:25884/api` 能返回 JSON
4. 启动游戏 / 服务端，配置会自动生成在 `config/netmusic-splayer-bridge.json`

## 配置

`config/netmusic-splayer-bridge.json`，改完执行 `/splayerbridge reload` 或重启。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关 |
| `preferForAllNeteaseSongs` | `false` | 为 `true` 时连非 VIP 的网易云歌曲也走 SPlayer |
| `splayerBaseUrl` | `http://127.0.0.1:25884` | SPlayer 服务地址 |
| `transport` | `http` | 下发方式：`http`（客户端不用装模组）或 `packet`（走游戏端口，客户端必须装） |
| `unlockEndpoints` | `/api/unblock/netease?id={id}` 等 | 解锁接口候选，按顺序尝试；`{id}` 会被替换 |
| `keywordFallback` | `true` | 网易云解锁失败时用「歌名-歌手」去酷我/波点兜底 |
| `keywordEndpoints` | `/api/unblock/kuwo?...` 等 | 关键词兜底接口 |
| `requestTimeoutSeconds` | `6` | 单次请求超时 |
| `totalResolveTimeoutSeconds` | `15` | 单首歌解析总超时，超时则回退原地址 |
| `cacheEnabled` | `true` | 是否启用磁盘缓存 |
| `cacheDir` | `netmusic_splayer_cache` | 缓存目录（相对游戏/服务端根目录） |
| `cacheHttpPort` | `25885` | 内置文件服务端口，被占用时会自动往后找 |
| `cacheBindAddress` | `0.0.0.0` | 文件服务监听地址 |
| `publicHost` | `auto` | 下发地址里的主机名；单人游戏用 `127.0.0.1`，专用服务端自动取局域网 IP |
| `maxConcurrentDownloads` | `2` | 同时下载缓存的数量 |
| `packetWindowKb` | `1024` | packet 模式预读窗口。客户端按播放速度消费，窗口决定能提前缓冲多少；调大（如 `32768`）可一次把整首拉过去，但更吃带宽和内存 |
| `packetChunksPerTick` | `4` | packet 模式每 tick 最多发几块（16KB/块）。4 ≈ 1.25MB/s，网络差就调小 |
| `logCacheHits` | `true` | 是否记录缓存命中日志 |

> **联机提示**：单人游戏开「对局域网开放」、或专用服务端在 NAT 后面时，客户端不一定能访问到 `127.0.0.1`。
> 这种情况请把 `publicHost` 手动改成服务端的局域网 IP（例如 `192.168.1.10`），并确保 25885 端口放行。
>
> **端口不通（frp / 只映射了 25565 / 云服务器安全组）**：把 `transport` 改成 `packet`，音频就会走游戏端口传，
> 此时**客户端也要装本模组**（服务端日志会提示「客户端没有安装本模组」）。

## 命令

需要 OP（权限等级 2）：

```
/splayerbridge status        查看开关、SPlayer 在线状态、缓存数量、缓存服务地址
/splayerbridge reload        重新读取配置（会重启缓存服务）
/splayerbridge cache clear   清空缓存
```

## 说明 / 已知限制

- 只处理**网易云歌曲**（CD 地址里能解析出 `id=` 的那种）；B 站、电台等其它来源不受影响。
- 广播喇叭（Big Megaphone）走的是另一套逻辑，目前不经过解析器，VIP 歌曲仍按原版行为处理。
- 首次播放要等服务端拿到音频：SPlayer 解锁约 1 秒 + 服务端下载首字节约 1 秒，之后客户端边收边播；已缓存的歌秒开。
- 缓存被关闭（`cacheEnabled=false`）时才会退回让客户端直连网易云，日志里会有警告。
- 如果同时装了其它接管 VIP 的附属（如 LoginNeed），本模组优先级更高（`getPriority() = 1000`），会优先走 SPlayer。
- SPlayer 不可用时不会报错打断播放，只是回退到 NetMusic 原本的行为（VIP 歌曲会播不出来）。

## 构建

需要 **JDK 25**（编译目标 `release 25`）和 **Gradle 9.6.1+**。

```bash
gradle build
# 产物: build/libs/netmusic-splayer-bridge-1.1.0.jar
```

想用 `./gradlew` 的话先生成一次 wrapper（`gradle-wrapper.jar` 是二进制，没有入库）：

```bash
gradle wrapper --gradle-version 9.6.1
./gradlew build
```

编译期依赖 NetMusic 本体，从 Modrinth maven 拉取（仅 `compileOnly`，不会打进产物、也不分发它）。

国内网络下载不动的话：

```bash
# 1. Gradle 发行包走腾讯云镜像
curl -L https://mirrors.cloud.tencent.com/gradle/gradle-9.6.1-bin.zip -o gradle-9.6.1-bin.zip
# 2. Maven 依赖已在 build.gradle / settings.gradle 里优先使用阿里云镜像
```

发布：Actions → **release** → Run workflow，填版本号，会自动建 `v<版本>` tag 并把 jar 挂到 Release 上。

## 免责声明

- 本项目只是把「SPlayer 已经提供的解锁结果」接到 NetMusic 的播放链路上，不包含、也不提供任何音频内容。
- 音频版权归网易云音乐及版权方所有。缓存文件仅供本机播放使用，请勿传播或用于商业用途。
- 使用第三方接口存在被封禁/失效的可能，请自行评估风险并遵守当地法律与服务条款。

## 许可

MIT
