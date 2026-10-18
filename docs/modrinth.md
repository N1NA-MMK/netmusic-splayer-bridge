# Modrinth 上架素材

> 上架时：**Summary** 用下面的英文短句（≤256 字符），**Description** 贴 `modrinth-description-en.md` 全文。
> 中文说明可另建一个本地化条目，或附在英文版末尾。

---

## Summary（摘要栏，二选一）

```
Plays VIP NetEase songs via your server's SPlayer. No account, no extra ports — the client streams straight from the CDN.
```

```
网易云 VIP 歌曲改由服务端 SPlayer 解锁，客户端直连播放：无需账号、不占额外端口，frp 只映射游戏端口也能用。
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
| Environment | Client and server（两端都要装） |
| License | MIT |
| Categories | `utility` |
| Dependencies | Net Music（required）、Fabric API（required） |
| Source / Issues | `https://github.com/N1NA-MMK/netmusic-splayer-bridge` |

---

## 发布流程

改 `gradle.properties` 的 `mod_version` → 提交 → `git tag v<版本> && git push origin v<版本>`，
GitHub Actions 会自动构建并把 jar 挂到 Release 上。

---

## 中文长描述（如需）

以下内容与英文版等价，可作为本地化条目使用：

```markdown
# Net Music: SPlayer Bridge

用服务端的 SPlayer 播放网易云 VIP 歌曲——不需要账号、不占额外端口、不用做端口映射。

[Net Music](https://modrinth.com/mod/net-music) 的 Fabric 附属。原版播 VIP 歌曲需要客户端登录网易云账号，
而实际情况往往是：专用服务端没有账号，或者客户端走 frp 这类只映射了游戏端口的隧道。
本模组把这一步换掉：**服务端**向本机运行的 [SPlayer](https://github.com/imsyy/SPlayer) 请求解锁，
再把客户端能访问的地址交给它，音频由客户端自己从 CDN 播放。

**服务端和客户端都要装。** 服务端负责解锁，客户端负责解码。

## 为什么需要它

- 全程不需要网易云账号
- 不占用额外端口——音频不经服务端转发，frp 只映射 25565 也能直接用
- VIP 歌曲真的能播，包括原版根本播不了的 FLAC（见下）

## 顺手修掉的两个毛病

### FLAC 播放

Net Music 是**流式**打开音频的（`AudioSystem.getAudioInputStream(InputStream)`），
mp3spi 探测时会把整条流扫空再找同步字，于是每首 FLAC 都以
`IOException: Resetting to invalid mark` 收场。这跟缓冲大小无关（实测 8KB / 1MB / 32MB 都失败），
只有按扩展名走 File 才能打开。

本模组在客户端把非 mp3 音频先下载到本地，再用文件方式打开，FLAC 就能正常播。
下载按歌曲 ID 缓存（`splayer_bridge_audio/<歌曲ID>.<扩展名>`，上限 2GB、按最久未用淘汰），同一首歌只下一次。

### 歌词超前

MC 的 `SoundEngine.play()` 在把流式声音加进 tick 表的**同一时刻**才开始异步拉音频流。
歌词由这个 tick 驱动，所以总是比音频早走「加载耗时」——这是 Net Music 所有歌曲都有的老问题。

装上本模组后，客户端 mixin 会把 tick 冻结到「解码器消费掉约 4 秒音频」为止——
这个量正好对应 `NetMusicAudioStream` 开播前的预读，所以误差在几十毫秒级。
阈值按 `AudioFormat` 算（192kbps mp3 4 秒约 100KB，44.1kHz 立体声 PCM 约 705KB，写死字节数必然错）。
每次播放会话号独立，多台留声机互不干扰，解除时还会打印耗时便于排查。

## 需要什么

- Minecraft **26.1.2** + Fabric
- 两端都装 **Net Music 1.5.x**（fabric + mc26.1）
- 服务端那台机器运行 **SPlayer**（默认 `http://127.0.0.1:25884`）
- 客户端能访问网易云 CDN（frp 只转发游戏端口时正好满足）

## 免责声明

本模组只是把 SPlayer 已提供的解锁结果接到 Net Music 的播放链路上，不含也不分发任何音频内容。
音频版权归网易云音乐及版权方所有，请勿传播或商用。
```
