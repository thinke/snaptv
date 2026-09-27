# SnapTV

An Android TV client for [Snapcast](https://github.com/badaix/snapcast) multiroom audio.
Your TV becomes one more synchronised room speaker, and the screen shows a visualizer of
what is playing instead of a blank menu.

- **Built for the remote.** Everything works with the D-pad: ◀ ▶ changes the visualizer,
  ▲ ▼ changes volume, OK opens settings.
- **Plays in the background.** Audio runs in a foreground service, keeps going while the TV
  shows other apps, and can start at boot.
- **In sync, including the TV's own delay.** HDMI, soundbars and TV sound processing add
  delay that changes from one setup to the next. Settings → *Audio delay* compensates for it,
  and applies live while you listen.
- **Visuals that match the sound.** Spectrum, halo and oscilloscope styles are drawn from the
  exact samples being played, timed to when they are *heard*, not when they are decoded.
- **Finds your server.** Discovers snapserver over mDNS (`_snapcast._tcp`), or you can enter
  an address.

## How it works

SnapTV is a small, pure Kotlin reimplementation of the snapclient side of the protocol. It
does not wrap the native snapclient binary.

| Piece | Where | Notes |
|---|---|---|
| Binary protocol | `core/…/protocol` | Hello, Time, ServerSettings, CodecHeader, WireChunk, ClientInfo |
| Clock sync | `core/…/sync/TimeSync.kt` | Median of `(c2s − s2c) / 2` over recent round trips |
| Decoders | `core/…/codec`, `app/…/MediaCodecDecoders.kt` | FLAC (own decoder, checked against libFLAC's MD5) and PCM in core; Opus and Ogg/Vorbis through Android's MediaCodec, with the Ogg demuxing and codec headers done in core |
| Sync buffer | `core/…/sync/SyncBuffer.kt` | Sample-exact start, hard resync on jumps, ±500 ppm drift correction by dropping or duplicating single frames |
| Audio output | `app/…/AudioOutput.kt` | AudioTrack. The DAC time of each block is extrapolated from `AudioTrack.getTimestamp`. |
| Visualizer | `core/…/visual`, `app/…/ui/Visualizer.kt` | FFT spectrum; samples pulled at the time they are heard |

The `core` module has no Android dependencies, so it is unit tested on the JVM. The `cli`
module is a desktop test client that exercises it against a real server.

Codecs: `flac`, `pcm`, `opus` and `ogg` (Vorbis) in the app. The desktop `cli` has no
MediaCodec, so there `opus` and `ogg` are reported as unsupported.

## Building

Requires JDK 17+ and the Android SDK (compileSdk 37).

```sh
./gradlew :core:test            # protocol, codec and sync tests
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Desktop test client (connects, keeps sync, prints statistics, and can dump what it would
play to a WAV file):

```sh
./gradlew :cli:installDist
cli/build/install/cli/bin/cli <server> [--seconds 10] [--wav out.wav] [--play]
```

## Known limitations

- Android 15+ does not allow media playback services to start from `BOOT_COMPLETED`, so
  there *Start when the TV boots* has no effect and playback starts when the app is opened.
- Output is 16-bit. 24/32-bit streams are down-converted.
- Opus and Vorbis depend on the TV's MediaCodec decoders. If a decoder falls over mid-stream
  it is restarted at the next chunk, which costs a short gap. Opus is decoded without
  pre-skip, exactly as snapclient does, so it stays in step with snapclient rooms.
- Stream and group names from the JSON-RPC control API are not shown yet.

## License

GPL-3.0-or-later, the same as Snapcast.
