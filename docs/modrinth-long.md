# Modrinth 长描述（直接粘贴版）

> 短版见 `docs/modrinth-short.md`，完整素材见 `docs/modrinth.md`。
> Modrinth 的 Description 只接受一个版本，建议贴**英文长版**，中文版可放在页面最下方的「中文说明」小节或另建一个本地化条目。

---

## 英文长版

```markdown
# Net Music: SPlayer Bridge

**Play VIP NetEase songs on a server that has no NetEase access — let the server ask SPlayer for the audio, then relay it to everyone nearby.**

Net Music normally resolves VIP songs by logging into a NetEase account on the client. That breaks down in the two situations people actually run into: a dedicated server with no account, and clients that can't reach NetEase's CDN. This addon replaces that resolution step with a bridge to [SPlayer](https://github.com/imsyy/SPlayer) running on the **server** machine, and serves the resulting audio from the server's own HTTP endpoint.

The client never contacts NetEase. It only knows one address: your server.

## How it works

```
Client places a CD  →  server resolves the song
                        │
                        ├─ cached      → hand the client the server's own URL (instant)
                        └─ not cached  → ask SPlayer's unlock API for a direct link
                                         └─ server starts downloading, hands out its own URL
                        │
                        └─ MusicToClientMessage → nearby clients
Client GET http://<server>:25885/audio/<id>.<ext>
  ├─ still downloading → chunked stream, client waits for bytes
  └─ finished          → plain file server with Range support
```

Two details matter here:

- Net Music decodes audio **on the client**, so the server has to expose what it downloaded over HTTP. The built-in read-only file server (default port `25885`) supports `Range`, which is exactly what Net Music's `DirectHttpHandler` needs.
- Because the URL handed to the client is the server's, first play streams while the server downloads. First byte typically arrives in under a second; once the song ends, the file is complete and the next play reads straight from disk.

## Features

- **No client-side NetEase access** — clients only talk to your server, so it works behind a proxy, on a LAN, or on machines with no route to NetEase
- **Streaming + caching** — download-while-serving on first play, instant playback afterwards
- **Crash-safe cache** — an in-progress download is marked with an `<id>.<ext>.part` sidecar file, deleted only when complete; quitting mid-song never leaves a half file that gets served as if it were whole
- **Keyword fallback** — if the NetEase unlock fails, it can retry with "title - artist" against alternate providers (Kuwo / Bodian). Disable it if you care about exact quality matching
- **Silent fallback** — if SPlayer is down, nothing errors out; playback just reverts to vanilla Net Music behaviour
- **Coexists with other addons** — registers at priority `1000`, so it wins over other VIP-handling addons when both are installed
- **Live status** — `/splayerbridge status` reports switch state, SPlayer reachability, cache count and the serving URL

## Requirements

| | |
| --- | --- |
| Minecraft | **26.1.2** |
| Mod loader | **Fabric** (Loader ≥ 0.19.2) |
| Java | **25** |
| Required mods | **Net Music 1.5.x**, **Fabric API** |
| External | **SPlayer** running on the server machine, default `http://127.0.0.1:25884` |

## Installation

1. Drop `netmusic-splayer-bridge-<version>.jar` and Net Music into `mods/`
2. Start SPlayer on the machine that hosts the world, and leave it running
3. Verify SPlayer responds: open `http://127.0.0.1:25884/api` — it should return JSON
4. Launch the game or server; `config/netmusic-splayer-bridge.json` is generated on first run

Single-player counts as "the server" — install it client-side. On a dedicated server, clients don't need the mod at all.

## Configuration

`config/netmusic-splayer-bridge.json` — edit, then run `/splayerbridge reload` (or restart).

| Field | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master switch |
| `preferForAllNeteaseSongs` | `false` | Also route non-VIP NetEase songs through SPlayer |
| `splayerBaseUrl` | `http://127.0.0.1:25884` | SPlayer address |
| `unlockEndpoints` | `/api/unblock/netease?id={id}` and alternates | Endpoints tried in order; `{id}` is substituted |
| `keywordFallback` | `true` | Retry with title/artist on other providers when the NetEase unlock fails |
| `keywordEndpoints` | Kuwo / Bodian templates | `{keyword}`, `{name}`, `{artist}` are substituted |
| `requestTimeoutSeconds` | `6` | Per-request timeout |
| `totalResolveTimeoutSeconds` | `15` | Total budget per song; on timeout it falls back to the original URL |
| `cacheEnabled` | `true` | Enable on-disk cache |
| `cacheDir` | `netmusic_splayer_cache` | Cache directory, relative to the game/server root |
| `cacheHttpPort` | `25885` | Serving port; if taken, the next free port is used |
| `cacheBindAddress` | `0.0.0.0` | Bind address for the serving endpoint |
| `publicHost` | `auto` | Host name put into the handed-out URL (`127.0.0.1` in single-player, LAN IP on a dedicated server) |
| `maxConcurrentDownloads` | `2` | Simultaneous cache downloads (1–8) |
| `logCacheHits` | `true` | Log cache hits |

## Commands

Permission level 2 (gamemasters) required:

```
/splayerbridge status         switch state, SPlayer reachability, cache count, serving URL
/splayerbridge reload         reload config (restarts the cache server)
/splayerbridge cache clear    delete all cached audio
```

## Multiplayer & networking

The client must be able to reach the serving port (`25885` by default) on the server.

- **Single-player / LAN-opened world** — `publicHost: auto` already resolves to `127.0.0.1`; fine for the host
- **Dedicated server on a LAN** — `auto` picks a LAN IP; make sure the port isn't firewalled
- **Dedicated server behind NAT / reverse proxy** — set `publicHost` to the address clients should use (LAN IP or domain) and forward `25885`

If clients get silence on first play while the server log shows a successful resolve, it's almost always `publicHost` pointing somewhere the client can't reach.

## Performance

- First play: roughly 1s to resolve through SPlayer + about 1s until the first byte reaches the client, then it streams
- Cached songs: served immediately with `Range` support
- A 115 MB FLAC was measured at ~830 ms to first byte, with the client already receiving data while the server had only fetched ~1.7 MB
- Downloads are capped at `maxConcurrentDownloads` so a crowded jukebox doesn't saturate your uplink

## Compatibility

- Only **NetEase** songs are touched — anything that yields an `id=` from the CD's address. Bilibili, radio and other sources are left alone
- **Big Megaphone** uses a separate playback path and is not intercepted; VIP songs there still behave like vanilla
- Other VIP-handling addons: this one registers at priority `1000` and takes precedence

## Known limitations

- If the cache/serving server can't start (port unavailable), it falls back to letting the client fetch from NetEase directly, with a warning in the log
- Keyword fallback may return a different master/quality than the NetEase original — turn it off if that matters
- SPlayer must run on the same machine as the server (or be reachable from it); it is not a mod and isn't bundled
- No GUI — configuration is file-based

## Troubleshooting

**Nothing plays, log says SPlayer offline** — check `splayerBaseUrl`, and that SPlayer is actually running and answering `/api`.

**Resolve works but the client stays silent** — `publicHost` / port `25885` reachability.

**Songs play but start slowly every time** — `cacheEnabled` is off, or the cache directory isn't writable.

**A VIP song plays the wrong version** — keyword fallback matched another provider; set `keywordFallback` to `false`.

## Disclaimer

This mod bundles no audio and provides no content of its own. It only wires the result SPlayer already produces into Net Music's playback chain. All audio is copyright NetEase Music and the respective rights holders; cached files are for local playback only and must not be redistributed or used commercially. Third-party endpoints may change or stop working — use at your own discretion and comply with local law and the services' terms.

## License

MIT. Source and issues: https://github.com/N1NA-MMK/netmusic-splayer-bridge
```

---

## 中文长版

```markdown
# Net Music: SPlayer Bridge

**服务端没有网易云账号也能播 VIP 歌：让服务端去问 SPlayer 要音频，再转发给附近所有客户端。**

Net Music 原本靠客户端登录网易云来解析 VIP 歌曲，但实际会遇到两种情况：专用服务端没有账号，或者客户端根本连不上网易云 CDN。这个附属把「解析」这一步换掉——让**服务端**去问同机运行的 [SPlayer](https://github.com/imsyy/SPlayer)，再由服务端自己的 HTTP 服务把音频下发出去。

客户端全程只知道一个地址：你的服务器。

## 工作原理

```
客户端放置 CD  →  服务端解析歌曲
                   │
                   ├─ 已缓存   → 直接把服务端地址交给客户端（秒开）
                   └─ 未缓存   → 请求 SPlayer 解锁接口拿直链
                                └─ 服务端开始下载，同时把自己的地址交出去
                   │
                   └─ MusicToClientMessage → 附近客户端
客户端 GET http://<服务端>:25885/audio/<id>.<ext>
  ├─ 还在下 → chunked 边下边发，客户端读不到数据就等
  └─ 下完了 → 普通文件服务，支持 Range
```

两个关键点：

- Net Music 的音频是**客户端**解码的，所以服务端下载好的文件必须用内置的只读 HTTP 服务（默认 `25885`）暴露出来，并支持 `Range`——正好对上 Net Music 的 `DirectHttpHandler`。
- 交给客户端的地址就是服务端自己的地址，所以首次播放是「边下边发」。首字节通常 1 秒内到；这首播完文件也就下完了，下次直接读磁盘。

## 特性

- **客户端不碰网易云**：只连服务端，适合代理后面、纯内网、或者根本没外网出口的机器
- **边下边播 + 自动缓存**：首次播放即下载即播放，之后秒开
- **缓存不怕中断**：未完成的下载带一个 `<id>.<ext>.part` 标记文件，下完才删；中途退游戏不会留下半首歌被当成完整缓存
- **关键词兜底**：网易云解锁失败时可用「歌名-歌手」去酷我/波点再试一次（在意音质一致性可以关掉）
- **静默回退**：SPlayer 挂了不会报错打断播放，只是回到 Net Music 原本的行为
- **与其它附属共存**：注册优先级 `1000`，同时装了别的接管 VIP 的附属时本模组优先
- **状态可查**：`/splayerbridge status` 一行看清开关、SPlayer 在线状态、缓存数量和下发地址

## 前置要求

| | |
| --- | --- |
| Minecraft | **26.1.2** |
| 加载器 | **Fabric**（Loader ≥ 0.19.2） |
| Java | **25** |
| 必需模组 | **Net Music 1.5.x**、**Fabric API** |
| 外部软件 | **SPlayer** 运行在服务端那台机器上，默认 `http://127.0.0.1:25884` |

## 安装

1. 把 `netmusic-splayer-bridge-<版本>.jar` 和 Net Music 一起丢进 `mods/`
2. 在开服那台机器上启动 SPlayer，保持运行
3. 确认 SPlayer 有响应：打开 `http://127.0.0.1:25884/api`，应该返回 JSON
4. 启动游戏/服务端，首次运行会生成 `config/netmusic-splayer-bridge.json`

单人游戏本身就相当于「服务端」，所以必须装在客户端 mods 里；连别人的专用服务端时，客户端不需要装。

## 配置

`config/netmusic-splayer-bridge.json`，改完执行 `/splayerbridge reload` 或重启。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关 |
| `preferForAllNeteaseSongs` | `false` | 为 true 时非 VIP 的网易云歌曲也走 SPlayer |
| `splayerBaseUrl` | `http://127.0.0.1:25884` | SPlayer 服务地址 |
| `unlockEndpoints` | `/api/unblock/netease?id={id}` 等 | 解锁接口候选，按顺序尝试；`{id}` 会被替换 |
| `keywordFallback` | `true` | 解锁失败时用「歌名-歌手」去其它源兜底 |
| `keywordEndpoints` | 酷我 / 波点模板 | `{keyword}`、`{name}`、`{artist}` 会被替换 |
| `requestTimeoutSeconds` | `6` | 单次请求超时 |
| `totalResolveTimeoutSeconds` | `15` | 单首歌解析总超时，超时回退原地址 |
| `cacheEnabled` | `true` | 是否启用磁盘缓存 |
| `cacheDir` | `netmusic_splayer_cache` | 缓存目录（相对游戏/服务端根目录） |
| `cacheHttpPort` | `25885` | 下发端口，被占用时自动往后找 |
| `cacheBindAddress` | `0.0.0.0` | 下发服务的监听地址 |
| `publicHost` | `auto` | 下发地址里的主机名（单人用 `127.0.0.1`，专用服务端自动取局域网 IP） |
| `maxConcurrentDownloads` | `2` | 同时下载缓存的数量（1–8） |
| `logCacheHits` | `true` | 缓存命中时是否打日志 |

## 命令

需要 OP（权限等级 2）：

```
/splayerbridge status        查看开关、SPlayer 在线状态、缓存数量、下发地址
/splayerbridge reload        重新读取配置（会重启缓存服务）
/splayerbridge cache clear   清空缓存
```

## 联机与网络

客户端必须能访问服务端的下发端口（默认 `25885`）。

- **单人 / 对局域网开放**：`publicHost: auto` 已经是 `127.0.0.1`，房主自己没问题
- **局域网专用服务端**：`auto` 会取局域网 IP，注意别被防火墙挡住端口
- **NAT / 反代后面**：把 `publicHost` 手动改成客户端可达的地址（局域网 IP 或域名），并转发 `25885`

如果服务端日志显示解析成功、客户端却一直没声音，基本都是 `publicHost` 指到了客户端访问不到的地方。

## 性能

- 首次播放：SPlayer 解锁约 1 秒 + 首字节到客户端约 1 秒，之后边收边播
- 已缓存的歌：直接下发，支持 `Range`
- 实测 115 MB 的 FLAC，首字节 830 ms，此时服务端只下到约 1.7 MB
- 并发下载受 `maxConcurrentDownloads` 限制，避免点歌台一热闹就把上行带宽打满

## 兼容性

- 只处理**网易云歌曲**（CD 地址里能解析出 `id=` 的那种），B 站、电台等其它来源不受影响
- **广播喇叭（Big Megaphone）** 走另一套播放逻辑，暂未接管，VIP 歌曲仍按原版行为处理
- 同时装了其它接管 VIP 的附属（如 LoginNeed）时，本模组优先级 `1000`，会优先走 SPlayer

## 已知限制

- 缓存/下发服务起不来（端口被占且顺延失败）时，会退回让客户端直连网易云，日志里会有警告
- 关键词兜底可能拿到和网易云原版不同的版本/音质，在意的话关掉
- SPlayer 必须跑在服务端同机（或服务端可达），它不是模组，也不随本模组分发
- 没有图形界面，配置全靠改文件

## 常见问题

**没声音，日志说 SPlayer 离线** —— 检查 `splayerBaseUrl`，确认 SPlayer 真的在跑且 `/api` 有响应。

**解析成功但客户端一直静音** —— 检查 `publicHost` 和 `25885` 端口的连通性。

**每次播放都很慢** —— `cacheEnabled` 关了，或者缓存目录不可写。

**VIP 歌播出来是另一个版本** —— 关键词兜底匹配到了别的源，把 `keywordFallback` 设为 `false`。

## 免责声明

本模组不包含任何音频内容，也不自行提供内容，只是把 SPlayer 已经给出的结果接到 Net Music 的播放链路上。音频版权归网易云音乐及版权方所有，缓存文件仅供本机播放，请勿传播或用于商业用途。第三方接口存在变更或失效的可能，请自行评估风险并遵守当地法律与服务条款。

## 许可

MIT。源码与问题反馈：https://github.com/N1NA-MMK/netmusic-splayer-bridge
```
