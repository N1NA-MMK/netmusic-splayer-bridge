# Net Music: SPlayer Bridge

Let the **server** fetch the audio for NetEase VIP tracks and hand it to players.

A Fabric addon for [Net Music](https://modrinth.com/mod/net-music). Vanilla resolution of VIP tracks requires a logged-in NetEase account on the client. This addon replaces that step: the server asks a locally running [SPlayer](https://github.com/imsyy/SPlayer) for the unlocked audio, then delivers it to clients in whichever way fits your setup.

Clients can stay mod-free, and they never have to reach NetEase themselves.

---

## The problems it solves

The usual reasons VIP songs refuse to play:

- a dedicated server with no NetEase account
- the server sits behind frp / port mapping and only the game port is reachable
- players are on a network that can't reach NetEase's CDN

All three are covered, and you can switch delivery modes at any time without reinstalling anything.

---

## Features

- **Takes over VIP resolution** — only NetEase songs are touched (CDs whose URL contains an `id=`). Bilibili, radio and every other source are left alone.
- **Three delivery modes** — server HTTP endpoint, packets over the game connection, or plain direct links. Pick whatever your network allows.
- **Server-side cache** — the first play starts the download; every later play is served straight from disk without touching the API again.
- **Optional transcoding** — ffmpeg converts FLAC to 192k MP3, roughly a third of the original size, which cuts multiplayer bandwidth accordingly.
- **Fails safely** — SPlayer being down, a stalled CDN or a client that walked away all time out cleanly instead of holding up the server.

---

## Delivery modes

| Mode | Client needs the mod | Extra port | Cache speed-up | Best for |
| --- | --- | --- | --- | --- |
| `http` | No | Yes (25885 by default) | Yes | LAN, or clients that can reach the server's port |
| `packet` | **Yes** | No (uses the game port) | Yes | frp / port forwarding with only 25565 exposed |
| `direct` | No | No | No | Clients with internet access, zero-config setups |

`http` and `packet` both mean "the server feeds the audio to the client" — the client never contacts NetEase. `direct` means the server only unlocks the track and hands the client a CDN link to fetch itself.

> Not sure which one? Try `direct` first. If it plays, the client's network is fine. If it doesn't, the client can't reach NetEase and you should use `packet`.

---

## How it works

```
CD placed → server resolves the song
              │
              ├─ cached     → hand the client the server's address (instant)
              │
              └─ not cached → ask SPlayer to unlock
                              ├─ got a link, server starts downloading
                              ├─ on completion, ffmpeg converts to mp3
                              └─ hand the client an address
                                        │
client fetches audio ───────────────────┘
  ├─ http   : client connects to the server's 25885, Range supported
  ├─ packet : client requests over the game connection, server streams chunks
  └─ direct : client goes to NetEase's CDN itself
```

---

## Installation

1. Drop `netmusic-splayer-bridge-x.y.z.jar` into `mods/`
2. Make sure **Net Music 1.5.x (fabric + mc26.1)** is installed too
3. Run SPlayer **on the server machine** and keep it running; check that `http://127.0.0.1:25884/api` returns JSON
4. Start the game or server — the config appears at `config/netmusic-splayer-bridge.json`

In `packet` mode the client needs the same jar.

**Requirements:** Minecraft 26.1.2, Fabric Loader, Fabric API, Net Music 1.5.x.
SPlayer is not a mod — it is a separate local service that must be running on the server.

---

## Configuration

Reload with `/splayerbridge reload` or restart the server.

| Option | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master switch |
| `transport` | `http` | Delivery mode: `http`, `packet` or `direct` |
| `splayerBaseUrl` | `http://127.0.0.1:25884` | SPlayer endpoint |
| `preferForAllNeteaseSongs` | `false` | Route non-VIP NetEase songs through SPlayer too |
| `unlockEndpoints` | `/api/unblock/netease?id={id}` etc. | Unlock endpoints, tried in order |
| `keywordFallback` | `true` | Fall back to Kuwo/Bodian by title when the NetEase unlock fails |
| `cacheEnabled` | `true` | Enable the on-disk cache |
| `cacheDir` | `netmusic_splayer_cache` | Cache directory, relative to the server root |
| `cacheHttpPort` | `25885` | Port used in `http` mode; falls forward if taken |
| `publicHost` | `auto` | Hostname used in `http` mode — set your LAN IP for multiplayer |
| `transcode` | `auto` | Convert to mp3: `auto` (if ffmpeg is present), `off`, `mp3` |
| `transcodeBitrateKbps` | `192` | Transcode bitrate; `128` saves more bandwidth |
| `ffmpegPath` | `ffmpeg` | ffmpeg binary, PATH by default |
| `packetWindowKb` | `1024` | `packet` mode read-ahead window; raise it to buffer a whole song |
| `packetChunksPerTick` | `4` | `packet` mode chunks per tick (16KB each); lower it on slow links |
| `maxConcurrentDownloads` | `2` | Parallel cache downloads |

---

## Commands

Requires permission level 2:

```
/splayerbridge status          switch, SPlayer state, delivery mode, transcoding, cache size
/splayerbridge reload          reload the config
/splayerbridge cache clear     wipe the cache
/splayerbridge cache transcode convert existing cache files to mp3 (needs ffmpeg)
```

---

## About transcoding

Once a download finishes, the server converts the file to 192k MP3 and keeps only the small version. A 485KB FLAC test file became 101KB.

- The first play still streams the original file while it downloads; transcoding happens in the background and doesn't affect that play.
- Already-cached files can be converted with `/splayerbridge cache transcode`.
- Set `transcode` to `off` to disable it. Without ffmpeg the conversion is skipped and a warning is logged.

---

## Troubleshooting

**No sound, or a "playback failed" message on the client**
Check the client log for `[SPlayerBridge]` lines. Nothing at all means the client never received the address (mod missing, or it never got the message). `Intercepted audio URL` followed by silence points at transport or decoding. The server log shows either "unlocked and relaying" or "unlock failed".

**Client disconnects with `Timed out`**
In `packet` mode a large burst can clog single-connection tunnels like frp. Lower `packetChunksPerTick` to 2 — that usually fixes it. The server stops sending after 30 seconds without an acknowledgement.

**The cache directory keeps growing**
Run `/splayerbridge cache clear`, or enable transcoding so files are smaller.

**Port already in use on startup**
`http` mode automatically picks the next free port and logs which one it used. Switching to `packet` or `direct` removes the extra port entirely.

**SPlayer reported offline**
Make sure it runs on the server machine and listens on 25884; adjust `splayerBaseUrl` if you changed the port.

---

## Known limitations

- Only NetEase songs are handled (CDs whose URL contains an `id=`); other sources are untouched
- The Big Megaphone uses a different playback path and is not covered yet
- `direct` mode has no cache speed-up — every play requests a fresh link from SPlayer
- The first play waits for the server: roughly 1 second to unlock plus 1 second for the first bytes, then it streams
- If SPlayer is unavailable, playback falls back to stock Net Music behaviour (VIP songs will not play)

---

## Disclaimer

This mod only wires the unlock result SPlayer already provides into Net Music's playback path. It contains and distributes no audio content.
All audio is copyright NetEase Cloud Music and its rights holders. Cached files are meant for local playback only — do not redistribute them or use them commercially.
Third-party endpoints can break at any time; evaluate the risk yourself and follow your local laws and the services' terms.
