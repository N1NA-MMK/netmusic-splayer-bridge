# Net Music: SPlayer Bridge

让 **服务端** 代替客户端去取网易云 VIP 歌曲的音频，再按你选的方式发给客户端。

给 [Net Music（网络音乐机）](https://modrinth.com/mod/net-music) 用的 Fabric 附属。原版播放 VIP 歌曲需要在客户端登录网易云账号；这个模组把「解析地址」这一步换成向服务端本机运行的 [SPlayer](https://github.com/imsyy/SPlayer) 请求解锁结果，音频由服务端负责获取和转发。

客户端可以不装任何东西，也可以只连服务端，取决于你选哪种下发方式。

---

## 它解决什么问题

真实场景里卡住的通常是这几件事：

- 专用服务端没有网易云账号，VIP 歌曲直接播不出来
- 服务器用 frp / 端口映射，只对外开放了游戏端口，别的端口进不来
- 客户端所在网络访问不了网易云 CDN

这三种情况这个模组都能覆盖，而且**可以随时切换**，不用重装。

---

## 特性

- **接管 VIP 歌曲解析**：只处理网易云歌曲（CD 地址里能解析出 `id=` 的），B 站、电台等其它来源完全不受影响
- **三种下发方式**：HTTP 中转 / 走游戏端口分包 / 客户端直连，按服务器条件选
- **服务端缓存**：首次播放就开始缓存，之后同一首歌直接读磁盘，不再请求接口
- **自动转码**：用 ffmpeg 把 FLAC 转成 192k MP3，缓存体积约原来的 1/3，联机流量同步下降
- **断线不卡服**：SPlayer 不可用、CDN 卡死、客户端断开都有超时兜底，不会把服务端拖住

---

## 三种下发方式

| 模式 | 客户端要装本模组 | 需要额外端口 | 缓存加速 | 适用场景 |
| --- | --- | --- | --- | --- |
| `http` | 否 | 是（默认 25885） | 有 | 局域网、客户端能访问服务端端口 |
| `packet` | **是** | 否（走游戏端口） | 有 | frp 反代、只映射了 25565、端口不通 |
| `direct` | 否 | 否 | 无 | 客户端能上外网，想要零配置 |

`http` 和 `packet` 都是「服务端把音频喂给客户端」，客户端全程不碰网易云；`direct` 则是服务端只负责解锁，把直链交给客户端自己去 CDN 取。

> 不确定选哪个？先用 `direct` 试一次：能出声说明客户端网络没问题；不出声就说明客户端访问不了网易云，换 `packet`。

---

## 工作流程

```
客户端放置 CD → 服务端解析歌曲信息
                 │
                 ├─ 已缓存 → 直接把服务端地址交给客户端（秒开）
                 │
                 └─ 未缓存 → 向 SPlayer 请求解锁
                              ├─ 拿到直链，服务端开始下载
                              ├─ 下载完成后用 ffmpeg 转成 mp3
                              └─ 把地址交给客户端
                                        │
客户端取音频 ────────────────────────────┘
  ├─ http   ：客户端连服务端的 25885 端口，支持 Range
  ├─ packet ：客户端通过游戏连接按块请求，服务端边下边发
  └─ direct ：客户端自己连网易云 CDN
```

---

## 安装

1. 把 `netmusic-splayer-bridge-x.y.z.jar` 放进 `mods/`
2. 确保 **Net Music 1.5.x（fabric + mc26.1）** 也在 `mods/` 里
3. 在**服务端那台机器**上启动 SPlayer 并保持运行，确认 `http://127.0.0.1:25884/api` 能返回 JSON
4. 启动游戏 / 服务端，配置会自动生成在 `config/netmusic-splayer-bridge.json`

`packet` 模式下客户端也要装同一个 jar。

**依赖**：Minecraft 26.1.2、Fabric Loader、Fabric API、Net Music 1.5.x。
SPlayer 不是模组，是独立的本地服务，需要单独运行。

---

## 配置

改完执行 `/splayerbridge reload` 或重启服务端。

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关 |
| `transport` | `http` | 下发方式：`http` / `packet` / `direct` |
| `splayerBaseUrl` | `http://127.0.0.1:25884` | SPlayer 服务地址 |
| `preferForAllNeteaseSongs` | `false` | 为 true 时非 VIP 歌曲也走 SPlayer |
| `unlockEndpoints` | `/api/unblock/netease?id={id}` 等 | 解锁接口候选，按顺序尝试 |
| `keywordFallback` | `true` | 网易云失败时用「歌名-歌手」去酷我/波点兜底 |
| `cacheEnabled` | `true` | 是否启用磁盘缓存 |
| `cacheDir` | `netmusic_splayer_cache` | 缓存目录（相对服务端根目录） |
| `cacheHttpPort` | `25885` | `http` 模式的下发端口，被占用会自动顺延 |
| `publicHost` | `auto` | `http` 模式地址里的主机名，联机时填服务端局域网 IP |
| `transcode` | `auto` | 是否转 mp3：`auto`（有 ffmpeg 就转）/ `off` / `mp3` |
| `transcodeBitrateKbps` | `192` | 转码码率，想更省流量可以调 `128` |
| `ffmpegPath` | `ffmpeg` | ffmpeg 路径，默认走 PATH |
| `packetWindowKb` | `1024` | `packet` 模式预读窗口，调大可一次缓冲整首 |
| `packetChunksPerTick` | `4` | `packet` 模式每 tick 发几块（16KB/块），网络差就调小 |
| `maxConcurrentDownloads` | `2` | 同时下载缓存的数量 |

---

## 命令

需要 OP（权限等级 2）：

```
/splayerbridge status          查看开关、SPlayer 在线状态、下发方式、转码状态、缓存数量
/splayerbridge reload          重新读取配置
/splayerbridge cache clear     清空缓存
/splayerbridge cache transcode 把已有缓存批量转成 mp3（需要 ffmpeg）
```

---

## 关于转码

服务端下载完成后会用 ffmpeg 把音频转成 192k MP3 存进缓存，之后下发的都是这个小文件。实测一首 485KB 的 FLAC 转完约 101KB。

- 首次播放是边下边播原始文件，转码在后台完成，不影响这次播放
- 已经缓存的老文件用 `/splayerbridge cache transcode` 批量转换
- 不想转码就把 `transcode` 设成 `off`；没有 ffmpeg 时会自动跳过并打印提示

---

## 常见问题

**客户端没声音 / 提示播放失败**
先看客户端日志里有没有 `[SPlayerBridge]` 的行：没有说明客户端没装模组或没收到地址；有 `拦截到音频地址` 但卡住，说明是传输或解码环节的问题。服务端日志会打印「已解锁并由服务端转发」或「解锁失败」。

**客户端掉线，日志写 `Timed out`**
`packet` 模式一次推太多会把 frp 这类单连接隧道堵死，把 `packetChunksPerTick` 降到 2 通常就好。服务端 30 秒收不到确认会自动停止下发。

**缓存目录一直涨**
用 `/splayerbridge cache clear` 清空，或把 `transcode` 打开让文件变小。

**服务端启动时提示端口被占用**
`http` 模式会自动往后找可用端口，日志里会写明实际用了哪个。也可以直接改成 `packet` 或 `direct` 彻底不占端口。

**SPlayer 提示离线**
确认它跑在服务端那台机器上、端口是 25884；如果换了端口，改 `splayerBaseUrl`。

---

## 已知限制

- 只处理网易云歌曲（CD 里能解析出 `id=` 的），其它音源不受影响
- 广播喇叭（Big Megaphone）走的是另一套逻辑，目前不经过解析器
- `direct` 模式没有缓存加速，每次播放都要向 SPlayer 要一次链接
- 首次播放要等服务端拿到音频：解锁约 1 秒 + 首字节约 1 秒，之后边收边播
- SPlayer 不可用时会回退到 Net Music 原本的行为（VIP 歌曲播不出来）

---

## 免责声明

本模组只是把 SPlayer 已经提供的解锁结果接到 Net Music 的播放链路上，不包含、也不提供任何音频内容。
音频版权归网易云音乐及版权方所有，缓存文件仅供本机播放使用，请勿传播或用于商业用途。
使用第三方接口存在失效的可能，请自行评估风险并遵守当地法律与服务条款。
