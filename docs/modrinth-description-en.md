# Net Music: SPlayer Bridge

Play VIP NetEase songs through your server's SPlayer — no account, no extra ports, no port mapping.

A Fabric addon for [Net Music](https://modrinth.com/mod/net-music). Vanilla Net Music resolves VIP tracks by logging into a NetEase account on the client, which falls apart in exactly the setups people run: a dedicated server with no account, and clients behind frp or similar tunnels that only expose the game port.

This addon replaces that step. The **server** asks a locally running [SPlayer](https://github.com/imsyy/SPlayer) for the unlocked track and hands the client an address it can actually reach. The client then plays the audio itself, straight from the CDN.

Install it on **both** sides. The server does the unlocking; the client does the decoding.

---

## Why it exists

- **No NetEase account needed anywhere** — the server asks SPlayer, nothing logs in
- **No extra ports** — audio is never relayed through the server, so a server behind frp with only port 25565 mapped works out of the box
- **VIP songs actually play** — including the FLAC ones, which stock Net Music cannot handle (see below)

---

## Two things it fixes for you

### FLAC playback

Stock Net Music opens audio as a **stream** (`AudioSystem.getAudioInputStream(InputStream)`). The mp3spi probe scans the whole stream looking for a frame sync before giving up, so every FLAC track dies with `IOException: Resetting to invalid mark`. This is not a buffer-size problem — 8KB, 1MB and 32MB buffers all fail; only opening by file extension works.

This mod therefore downloads non-mp3 tracks to disk on the client and opens them as a file. FLAC and friends play normally. Downloads are cached per song id, so a song is fetched once and then played straight from disk.

### Lyrics starting too early

`SoundEngine.play()` adds a streaming sound to the tick list **at the same moment** it kicks off the async audio load. Lyrics are driven by that tick counter, so they always run ahead of the audio by however long loading takes — a stock Net Music quirk, visible on every song.

With this mod installed, a client-side mixin freezes the tick until the decoder has consumed about four seconds of audio — exactly the amount `NetMusicAudioStream` primes before it starts. The threshold is derived from the `AudioFormat` rather than a fixed byte count (192kbps MP3 needs ~100KB for four seconds, 44.1kHz stereo PCM needs ~705KB — a factor of seven, so any constant is wrong). Each play has its own session id, so multiple jukeboxes don't interfere, and the release is logged with the elapsed time if you ever need to tune it.

---

## How it works

```
CD placed → server resolves the song (this addon takes over)
              └─ asks SPlayer /api/unblock/netease?id=<id>
                   └─ CDN link, wrapped into a marker address, sent to the client

client receives the marker address → this addon's handler takes over
              ├─ non-mp3 (FLAC etc.) → download locally, then open as a file
              ├─ mp3                 → stream directly
              └─ lyric gate          → freeze the tick until audio really starts
```

---

## Requirements

- Minecraft **26.1.2**, **1.21.1** or **1.20.1** + Fabric
- **Net Music 1.5.x** (Fabric build matching your MC version) on both sides
- **SPlayer** running on the server machine (default `http://127.0.0.1:25884`)
- Clients must be able to reach the NetEase CDN — with frp forwarding only the game port, that's exactly the case

Install the same jar on the server **and** the client.

---

## Configuration

`config/netmusic-splayer-bridge.json`; reload with `/splayerbridge reload`.

| Option | Default | Description |
| --- | --- | --- |
| `enabled` | `true` | Master switch |
| `preferForAllNeteaseSongs` | `false` | Route non-VIP NetEase songs through SPlayer too |
| `splayerBaseUrl` | `http://127.0.0.1:25884` | SPlayer endpoint |
| `unlockEndpoints` | `/api/unblock/netease?id={id}` etc. | Unlock endpoints, tried in order |
| `keywordFallback` | `true` | Fall back to Kuwo/Bodian by title when the NetEase unlock fails |
| `requestTimeoutSeconds` | `6` | Per-request timeout |
| `totalResolveTimeoutSeconds` | `15` | Overall resolve timeout before falling back |
| `logUnlocks` | `true` | Log unlock results |
| `downloadBeforePlay` | `true` | Client: download non-mp3 formats to disk before playing |
| `lyricOffsetTicks` | `0` | Extra lyric delay in ticks (20 = 1s). Only for fine-tuning — the gate already aligns them |

---

## Commands

Requires permission level 2:

```
/splayerbridge status   switch, SPlayer state
/splayerbridge reload   reload the config
```

---

## Known limitations

- Only NetEase songs are handled (CDs whose URL contains an `id=`); other sources are untouched
- The Big Megaphone uses a different playback path and is not covered
- Clients need access to the NetEase CDN
- FLAC must be downloaded fully before playback starts (decoder limitation); the lyric gate waits for it rather than running ahead
- If SPlayer is unavailable, playback falls back to stock Net Music behaviour (VIP songs will not play)

---

## Disclaimer

This mod only wires the unlock result SPlayer already provides into Net Music's playback path. It contains and distributes no audio content.
All audio is copyright NetEase Cloud Music and its rights holders. Do not redistribute downloaded audio or use it commercially.
