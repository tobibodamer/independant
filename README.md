<img src="assets/icon.svg" width="64" height="64" alt="">

# Pendant (repo: *independant*)

A 24/7 speech-capture app for Android. It listens like a wearable pendant, records **only speech**, and stores every utterance as a small file — all locally, nothing leaves the phone.

> **Private repo:** <https://github.com/tobibodamer/independant>
> The app itself is called *Pendant*; the repository carries the earlier project name *independant*.

```
🎙 24/7 mic → VAD gate → Opus (Ogg) encoder → one file per utterance
```

## What it does

- **Speech-only recording.** A hand-rolled adaptive energy VAD (high-pass → frame RMS → noise-floor tracking with hysteresis and onset confirmation) decides frame by frame (20 ms) whether you are speaking. Silence costs almost nothing: no encoder runs, no disk writes.
- **Configurable after-speech hold (1–30 s, default 5 s).** One thinking pause does not explode into ten files; pre-roll (0.3–2 s) makes sure the file starts *before* your first word. Blips shorter than a threshold are discarded without ever creating a file. Long continuous speech is rotated into segments (default 15 min).
- **Battery-friendly.** One dedicated audio-priority thread, integer-only DSP, no neural nets, optional CPU wake lock, batches ~400 ms of Opus data per filesystem write. The mic still runs continuously — that is the honest cost of 24/7 capture — but everything else is as cheap as practical.
- **Opus-in-Ogg output** (`.opus`, RFC 7845), encoded by the platform MediaCodec at 16/24/32/48 kbps (default 24). Streams pages while recording, so even hour-long sessions are crash-safe. Falls back to AAC/M4A on the rare device without an Opus encoder. Unit-tested muxer, CRC-validated against real libopus streams.
- **Microphone sources.**
  - Phone mics: bottom / top / back / auto — or **combine two built-in mics and mix them**.
  - Bluetooth earbuds/headsets via the HFP (SCO) link, with automatic switching: pull the earbuds out of the case mid-sentence and the capture re-routes without stopping. Routing modes: automatic, Bluetooth-preferred, Bluetooth-only, phone-only.
  - Note: Bluetooth HFP audio is 16 kHz mono (codecs limitation), and while the SCO link is open, music playback pauses.
- **Quick pause.** Big in-app button, a Quick-Settings tile ("Pendant pause"), and buttons on the ongoing notification. Pausing frees the microphone immediately.
- **Optional status-bar indicator.** Either a visible recording icon or a "quiet" notification with no icon (activity stays visible in the shade either way — Android requires an ongoing notification for background capture).
- **Storage, location & care.** Clips land by default in your phone's public **Music/Pendant** folder (via MediaStore — no permissions needed, and visible in any music or file app so you can play or export clips anywhere). Settings → *Storage location* offers two alternatives: an **app-private folder** (invisible to other apps) and a **custom folder** picked with the system folder picker (the app keeps durable access and creates a `Pendant` subfolder inside it). MediaStore-backed rows cannot be renamed, so those clips carry no `_NNs` duration suffix — their duration comes from the media index; app-private and custom-folder clips keep the classic `…_42s.opus` filenames. Switching the location applies to **new clips** (the recording in progress keeps its folder); the Recordings tab lists, plays, shares and deletes clips from every location in one place. Optional auto-delete after N days and a size cap (oldest first) run across all locations.

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
| `app/src/main/java/app/pendant/ui/` | Compose UI |
| `app/src/test/` | JVM tests (Ogg muxer validated against ffmpeg output) |
