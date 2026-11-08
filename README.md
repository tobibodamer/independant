<img src="assets/icon.svg" width="64" height="64" alt="">

# inde**pendant**

A 24/7 speech-capture app for Android. It listens like a wearable pendant, records **only speech**, and stores every utterance as a small file — all locally, nothing leaves the phone unless you switch on the optional [capture service](#capture-service) link.

> **Private repo:** <https://github.com/tobibodamer/independant>
> The name is the product: *independant* — an independent pendant that listens all day. The launcher label and clip folders keep the short form (`Pendant`, `Documents/Pendant`); the application ID is `io.github.tobibodamer.independant` (`.debug` suffix for debug builds).

```
🎙 24/7 mic → VAD gate → Opus (Ogg) encoder → one file per utterance
```

<p align="center">
  <img src="assets/listen.png" width="340" alt="Listen tab: live level meter while speaking, daily clip/speech/storage stats, battery-optimization card">
</p>

## What it does

- **Speech-only recording.** A hand-rolled adaptive energy VAD (high-pass → frame RMS → noise-floor tracking with hysteresis and onset confirmation) decides frame by frame (20 ms) whether you are speaking. Silence costs almost nothing: no encoder runs, no disk writes.
- **Configurable after-speech hold (1–30 s, default 5 s).** One thinking pause does not explode into ten files; pre-roll (0.3–2 s) makes sure the file starts *before* your first word. Blips shorter than a threshold are discarded without ever creating a file. Long continuous speech is rotated into segments (default 15 min).
- **Battery-friendly.** One capture thread that reads the mic in ~0.5 s blocks (a couple of wakeups per second instead of fifty), cheap DSP, the live level meter only computed while it is on screen, batches ~400 ms of Opus data per filesystem write, CPU wake lock off by default (Android's audio service keeps capture alive on its own). The mic still runs continuously — that is the honest cost of 24/7 capture — but everything else is as cheap as practical.
- **Opus-in-Ogg output** (`.opus`, RFC 7845), encoded by the platform MediaCodec at 16/24/32/48 kbps (default 24). Streams pages while recording, so even hour-long sessions are crash-safe. Falls back to AAC/M4A on the rare device without an Opus encoder. Unit-tested muxer, CRC-validated against real libopus streams.
- **Microphone sources.**
  - Phone mics: bottom / top / back / auto — or **combine two built-in mics and mix them**.
  - Bluetooth earbuds/headsets via the HFP (SCO) link, with automatic switching: pull the earbuds out of the case mid-sentence and the capture re-routes without stopping. Routing modes: automatic, Bluetooth-preferred, Bluetooth-only, phone-only.
  - Note: Bluetooth HFP audio is 16 kHz mono (codecs limitation), and while the SCO link is open, music playback pauses.
- **Quick pause.** Big in-app button, a Quick-Settings tile ("Pendant pause"), and buttons on the ongoing notification. Pausing frees the microphone immediately.
- **Optional status-bar indicator.** Either a visible recording icon or a "quiet" notification with no icon (activity stays visible in the shade either way — Android requires an ongoing notification for background capture).
- **Storage, location & care.** Clips land by default in your phone's public **Documents/Pendant** folder (no permissions needed, visible in any file app so you can play or export clips anywhere). Segments are finished in an app-private staging area first and only *complete* clips — already carrying their `…_42s.opus` duration suffix — appear in the folder, which makes it safe to watch with a sync tool (Syncthing et al.): sync never sees partial or renamed audio. The daily capture log lives right beside the clips in `Documents/Pendant/events`, so clips and their metadata form **one tree** you can sync with a single folder pair. Settings → *Storage location* offers three alternatives: a public **Music/Pendant** folder (listed by music-player apps; the capture log cannot live there — Android only admits audio under Music — and publishes to `Documents/Pendant/events` one tree over), an **app-private folder** (invisible to other apps), and a **custom folder** picked with the system folder picker (the app keeps durable access and creates a `Pendant` subfolder inside it). If publishing a finished clip fails (full disk, provider error), it is rescued to the app-private listing instead of dropped. Switching the location applies to **new clips** (the recording in progress keeps its folder); the Recordings tab lists, plays, shares and deletes clips from every location in one place. Optional auto-delete after N days and a size cap (oldest first) run across all locations.
- **Capture log.** A gap between clips can mean silence, a pause, another app holding the mic, or a dead service. A daily event log tells them apart (see [Capture log](#capture-log)), and every clip carries its own metadata tags.

## Capture service

Optional, off by default (Settings → *Capture service*: URL and this phone's token). The phone then acts as the `phone` agent of a capture service: one input, `builtin` (kind `microphone`), reported by a long-polling heartbeat (`POST /v1/capture/heartbeat?wait=25`) from the recording service.

- **Follows the assignment.** On `off` it finishes the open clip, frees the microphone and stays idle; on a recording mode it records. The user's own pause stays separate, and while paused (or the mic is missing or held by a call) the input reports `unavailable`, so it never holds the slot.
- **Lease on `elapsedRealtime`.** If no good answer arrives within `valid_for` seconds (offline, timeout, any non-200), the phone records anyway: duplicates, never a gap.
- **Heartbeat at once** on a state change or a network change; a goodbye (`"inputs": []`) when recording stops or the link is switched off.
- **Status lines** (`{"at": …, "state": "recording|paused|unavailable"}`) go to `status/<day>.jsonl`, mirrored beside the clips like the capture log (`Documents/Pendant/status`).
- The service URL may be plain `http` (e.g. over Tailscale), so cleartext traffic is allowed. The token is stored unencrypted in the app's private settings.

## Capture log

One file per local day, one JSON object per line: `Documents/Pendant/events/2026-10-30.jsonl` (both Music and Documents storage — beside the clips in Documents mode, one tree over in Music mode), `<custom folder>/Pendant/events/` (custom folder) or `Android/data/io.github.tobibodamer.independant/files/events/` (app-private, always the source of truth). Mirrors get state changes right away and heartbeats every 5 minutes, as appends.

Every line has `t` (UTC epoch ms), `tz` (UTC offset at that instant), `up` (`elapsedRealtime` ms, exposes wall-clock jumps) and `e`:

| `e` | Meaning | Extra fields |
| --- | --- | --- |
| `start` / `stop` | service started / stopped | `detector`, `rate`, `app` (start) |
| `pause` / `resume` | user paused / resumed | |
| `mic` | route opened or changed | `mic` (label), `open` (false: nothing to record from, e.g. waiting for Bluetooth) |
| `silenced` / `unsilenced` | Android gives the mic to a call or voice-chat app and hands Pendant silence | |
| `alive` | heartbeat, every minute while listening | `audio_s` (analyzed audio), `level_db`, `floor_db`, `speech` (share 0–1), `silenced` |
| `gap` | audio lost to a buffer overrun | `ms` |
| `clip` | clip stored | `file`, `start` (epoch ms, pre-roll included), `dur_ms`, `speech_ms` |
| `config` | detector or sample rate changed | `detector`, `rate` |
| `error` | capture error (throttled) | `msg` |

Pendant was **listening** from `resume`, `unsilenced` or `mic` with `open: true` until the next `pause`, `stop`, `mic` with `open: false`, `silenced`, or, if none follows (killed, crashed, battery died), one minute after the last `alive`.

Clips (Opus) carry comment tags: `DATE` (ISO 8601 with offset), `PENDANT_START_MS`, `PENDANT_MIC`, `PENDANT_DETECTOR`. The AAC fallback has no tags.

## Building

```bash
./gradlew assembleDebug   # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew test            # muxer + VAD + segmenter unit tests
```

Requires JDK 17 and an Android SDK (platform 35).

## Notes on Android's rules

- Since Android 14 the microphone foreground service cannot be started *from the background*: boot-start, tiles or notification actions may get refused on newer devices — Pendant asks you to open the app for the first start after boot.
- Bluetooth mic capture engages the telephony audio path; music from other apps stops while a Bluetooth mic is active. If you record with earbuds all day, expect their battery to drain like on a call.

## Layout

| Path | What |
| --- | --- |
| `app/src/main/java/app/pendant/recording/` | foreground service + capture engine |
| `app/src/main/java/app/pendant/audio/` | route planning, mic selection, Bluetooth SCO |
| `app/src/main/java/app/pendant/vad/` | energy VAD |
| `app/src/main/java/app/pendant/segment/` | pre-roll / hangover segmentation |
| `app/src/main/java/app/pendant/codec/` | Opus encoder, Ogg muxer, AAC fallback |
| `app/src/main/java/app/pendant/events/` | capture log and its mirror next to the clips |
| `app/src/main/java/app/pendant/ui/` | Compose UI |
| `app/src/test/` | JVM tests (Ogg muxer validated against ffmpeg output) |
