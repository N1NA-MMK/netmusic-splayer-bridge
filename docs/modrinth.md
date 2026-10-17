# Modrinth 发布素材

## 基本信息

| 字段 | 建议值 |
| --- | --- |
| Project name | `Net Music: SPlayer Bridge` |
| Slug | `netmusic-splayer-bridge` |
| Summary | 见下方（≤256 字符） |
| Project type | Mod |
| Loader | Fabric |
| Game version | 26.1.2 |
| Environment | Client and server（见下方说明） |
| License | MIT |
| Source / Issues | `https://github.com/N1NA-MMK/netmusic-splayer-bridge` |

**Environment 说明**：逻辑跑在「服务端」侧（集成服务端也算），所以单人游戏时必须装在客户端 mods 里；
连别人的专用服务端时客户端不需要装。选 **Client and server**。

**Dependencies**：`Net Music`（required）、`Fabric API`（required）。
SPlayer 不是模组，属于外部软件，写在描述里即可。

**Categories**：`utility`（可再加 `decoration` / `social`）

---

## Summary（短描述，挑一条）

中文（97 字符）：

```
让网易云 VIP 歌曲改由服务端 SPlayer 取流：服务端中转音频、边下边播，首次播放自动缓存，客户端不直连网易云。
```

English（129 chars）：

```
Streams VIP NetEase songs from your server's SPlayer. The server fetches, relays and caches the audio — clients never touch NetEase.
```

更短的版本（如果嫌长）：

```
VIP 网易云歌曲改走服务端 SPlayer，服务端中转 + 边下边播 + 自动缓存。
```

---

## Description（正文，可直接粘贴）

```markdown
## 这个模组干什么

给 [Net Music（网络音乐机）](https://modrinth.com/mod/net-music) 用的附属。

原版 Net Music 播放网易云 VIP 歌曲需要登录账号；这个模组换了个思路：**让服务端去问本机运行的 [SPlayer](https://github.com/imsyy/SPlayer) 要音频**，再把音频转发给客户端。

- 客户端**不需要**能访问网易云，也不需要登录
- 首次播放由服务端边下边转发，播完这首就缓存好了
- 之后同一首歌直接读服务端磁盘，秒开、不再请求接口

## 它是怎么工作的

```
客户端放 CD → 服务端解析歌曲信息
                 ├─ 已缓存 → 直接把服务端地址交给客户端
                 └─ 未缓存 → 请求 SPlayer 的解锁接口拿到直链
                              └─ 服务端开始下载，同时把「自己的地址」交给客户端
客户端 GET 服务端地址
  ├─ 还在下 → chunked 边下边发，客户端读不到数据就等
  └─ 下完了 → 普通文件服务，支持 Range
```

客户端全程只连服务端（默认 25885 端口），不碰网易云的 CDN。

## 需要什么

- Minecraft **26.1.2** + Fabric
- **Net Music 1.5.x**（fabric+mc26.1）
- **SPlayer** 运行在服务端那台机器上，默认地址 `http://127.0.0.1:25884`

## 配置

首次启动生成 `config/netmusic-splayer-bridge.json`：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `splayerBaseUrl` | `http://127.0.0.1:25884` | SPlayer 地址 |
| `preferForAllNeteaseSongs` | `false` | 为 true 时非 VIP 歌曲也走 SPlayer |
| `cacheEnabled` | `true` | 是否缓存到磁盘 |
| `cacheDir` | `netmusic_splayer_cache` | 缓存目录 |
| `cacheHttpPort` | `25885` | 下发端口，被占用会自动顺延 |
| `publicHost` | `auto` | 下发地址里的主机名 |
| `keywordFallback` | `true` | 网易云失败时用歌名去酷我/波点兜底 |

> 联机时如果客户端连不上 `127.0.0.1`，把 `publicHost` 改成服务端的局域网 IP。

## 命令

```
/splayerbridge status        查看状态
/splayerbridge reload        重载配置
/splayerbridge cache clear   清空缓存
```

## 已知限制

- 只处理网易云歌曲（CD 里能解析出 `id=` 的），B 站等其它来源不受影响
- 广播喇叭（Big Megaphone）走另一套逻辑，暂未接管
- 首次播放要等服务端拿到首字节（约 1~2 秒），之后边收边播
- SPlayer 不可用时会回退到 Net Music 原本行为（VIP 歌曲播不出来）

## 免责声明

本模组只是把 SPlayer 已经提供的解锁结果接到 Net Music 的播放链路上，不包含也不提供任何音频内容。
音频版权归网易云音乐及版权方所有，缓存文件仅供本机播放，请勿传播或用于商业用途。
```

---

## 英文正文（可选，作为第二段或单独版本）

```markdown
A bridge addon for **Net Music**: when a VIP NetEase song is played, the server asks a locally running **SPlayer** for the unlocked audio and relays it to clients.

- Clients never talk to NetEase and don't need an account
- The server downloads while streaming, then keeps the file cached
- Later plays are served straight from disk

Requires Minecraft 26.1.2, Fabric, Net Music 1.5.x and SPlayer running on the server at `http://127.0.0.1:25884`.

This mod contains no audio content; it only wires SPlayer's existing unlock result into Net Music's playback chain.
```
