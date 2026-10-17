# Modrinth 简短介绍（直接粘贴版）

> 完整版见 `docs/modrinth.md`。这里只保留上架时必填的两块：**Summary** + **Description 正文**。

---

## Summary（≤256 字符，填在 Modrinth 的 Summary 栏）

英文（推荐，Modrinth 默认英文）：

```
Plays VIP NetEase songs through your server's SPlayer: the server fetches, relays and caches the audio, so clients never touch NetEase or need an account.
```

中文：

```
让网易云 VIP 歌曲改由服务端 SPlayer 取流：服务端中转音频、边下边播并自动缓存，客户端不直连网易云。
```

---

## Description 正文（英文，可直接粘贴）

```markdown
## What it does

An addon for **Net Music**. Vanilla Net Music needs a NetEase account to play VIP songs — this mod instead has the **server** ask a locally running **SPlayer** for the audio and relay it to clients.

- Clients never contact NetEase and need no account
- First play: the server downloads while streaming, then keeps the file
- Next plays: straight from disk, instant

## Requirements

- Minecraft **26.1.2** + Fabric
- **Net Music 1.5.x**
- **SPlayer** running on the server machine (default `http://127.0.0.1:25884`)

## Notes

- Only NetEase songs are affected; other sources are untouched
- If SPlayer is unavailable, it silently falls back to vanilla Net Music behaviour
- Behind NAT / LAN play: set `publicHost` to the server's LAN IP and allow port `25885`
- Config lives in `config/netmusic-splayer-bridge.json`; `/splayerbridge status|reload|cache clear` for control

This mod bundles no audio. Copyright belongs to NetEase Music and the rights holders; cached files are for local playback only.
```

---

## Description 正文（中文版，可选第二段）

```markdown
## 这个模组干什么

给 **Net Music（网络音乐机）** 用的附属。原版播网易云 VIP 歌曲需要登录，这个模组换了个思路：让**服务端**去问本机运行的 **SPlayer** 要音频，再把音频转发给客户端。

- 客户端不需要能访问网易云，也不需要账号
- 首次播放服务端边下边转发，播完这首就缓存好了
- 之后同一首歌直接读服务端磁盘，秒开

## 需要什么

- Minecraft **26.1.2** + Fabric
- **Net Music 1.5.x**
- **SPlayer** 运行在服务端那台机器上（默认 `http://127.0.0.1:25884`）

## 注意

- 只处理网易云歌曲，B 站等其它来源不受影响
- SPlayer 不可用时不报错，回退到 Net Music 原本行为
- 联机时客户端连不上 `127.0.0.1`，把 `publicHost` 改成服务端局域网 IP 并放行 `25885` 端口
- 配置在 `config/netmusic-splayer-bridge.json`，命令 `/splayerbridge status|reload|cache clear`

本模组不包含任何音频内容。版权归网易云音乐及版权方所有，缓存文件仅供本机播放。
```

---

## 其余字段

| 字段 | 值 |
| --- | --- |
| Project name | `Net Music: SPlayer Bridge` |
| Slug | `netmusic-splayer-bridge` |
| Project type | Mod |
| Loader | Fabric |
| Game version | 26.1.2 |
| Environment | Client and server |
| License | MIT |
| Categories | `utility` |
| Dependencies | Net Music（required）、Fabric API（required） |
| Source / Issues | `https://github.com/N1NA-MMK/netmusic-splayer-bridge` |
