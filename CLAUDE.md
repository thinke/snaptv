# SnapTV

Snapcast client apps: **SnapTV** for Android TV (also phones, tablets, Android boxes) and
**SnapTV Desktop** for Linux. Both are synchronised Snapcast rooms with a visualizer.

## Layout

| Module | What | Platform |
|---|---|---|
| `core/` | Protocol, clock sync, sync buffer, FLAC/PCM/Ogg/Opus headers, transports (tcp/ws/wss), auth, JSON-RPC control client, visual maths (FFT, `VisualBuffer`), click-track source, and `session/`: `SnapSession` (the room: server choice, reconnects, volume, source, name, audio delay on the server, stats) and the settings model (`AppSettings`, `Prefs` over a `KeyValueStore`) | Plain Kotlin/JVM, no Android. Unit tested. |
| `shared/visuals/` | `Visualizer.kt`: the Compose visualizer, compiled into both apps | Compose (Android and Desktop) |
| `app/` | Android app: `Player` (thin, over `SnapSession`), AudioTrack output, MediaCodec/FFmpeg decoders, Compose for TV UI, services, updater | Android |
| `desktop/` | Linux app over the same `SnapSession`: PulseAudio/PipeWire output (JNA), mDNS (JmDNS), settings in java.util.prefs, Compose Desktop window and Settings panel | JVM desktop |
| `cli/` | Desktop test client (stats, `--wav`, `--play`) | JVM |

## Feature parity

**The Android and desktop apps stay at feature parity.** When a feature is added or changed in
one, add it to the other in the same change, or record here why it doesn't apply. Put the logic
in `core/` (or `shared/` for UI) so both use the same code; keep platform code thin. Room behaviour
belongs in `SnapSession`; a setting belongs in `AppSettings` and gets a control in both Settings UIs. Update the
table below with every such change.

| Feature | Android | Desktop | Notes |
|---|---|---|---|
| Synced playback (clock sync, sync buffer, drift correction) | ✅ | ✅ | `core` |
| Output timing from the audio device | ✅ AudioTrack timestamps | ✅ `pa_simple_get_latency` | |
| Codecs: FLAC, PCM | ✅ | ✅ | `core` decoders |
| Codecs: Opus, Ogg Vorbis | ✅ MediaCodec | ❌ | needs native libopus/libvorbis or FFmpeg on desktop |
| Selectable decoders + on-device decoder self-test | ✅ | ❌ | desktop has only `core` decoders so far |
| Transports: tcp, ws, wss; auth; accept-any-certificate | ✅ | ✅ | `core`; both Settings UIs |
| Server discovery (mDNS) | ✅ NSD | ✅ JmDNS | |
| Visualizers: Spectrum, Halo, Oscilloscope | ✅ | ✅ | `shared/visuals` |
| Room/track names, source switching, rename (JSON-RPC) | ✅ | ✅ | `SnapSession` |
| Audio delay stored as server latency, adjustable (−50/−10/+10/+50, reset) | ✅ | ✅ | `SnapSession` |
| Picture sync test / microphone measurement | ✅ | ❌ | |
| Volume from server + local control reported to server | ✅ | ✅ | |
| Stats overlay (toggle in Settings) | ✅ | ✅ | |
| Visualizer choice, volume in Settings | ✅ | ✅ | |
| Screensaver (DreamService) | ✅ | n/a | Android only |
| Update check from GitHub releases (on open + daily, SHA-256 checked, skip/pre-releases) | ✅ PackageInstaller | ✅ replaces its AppImage and restarts | `core/update/Releases`; the AppImage also carries zsync update info for AppImageUpdate/Gear Lever |
| Remote / touch / mouse / keyboard input | ✅ D-pad, touch, mouse | ✅ keyboard, mouse | |
| Multi-monitor full screen | n/a | ✅ | desktop only |
| Background playback | ✅ foreground service | ✅ system tray (KDE StatusNotifierItem, symbolic icon; XEmbed fallback) | closing only hides the window; the tray icon's menu (left or right click) shows it or quits |
| Start at boot / login | ✅ (not on Android 15+) | ❌ | `--tray` is ready for an autostart entry (the dev laptop uses `~/.config/autostart`) |
| Send mode: be the source (PipeWire output → snapserver tcp input, paced by the monotonic clock) | n/a | ✅ | Linux only by design; `SourceMode.kt`, one mode at a time (`desktopMode`) |
| Hidden: sync test through snapcast | ⏸ | ⏸ | parked; `SHOW_SNAPCAST_SYNC_TEST` |

## Building

System Java on the dev machine is a JRE only: use `JAVA_HOME=~/tools/jdk-21`.

```sh
./gradlew :core:test                         # unit tests
./gradlew :app:assembleDebug                 # Android debug APK
ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest   # on one device only
./gradlew :desktop:run --args="--server HOST --windowed"   # also --mode source --source-port N --sink NAME (not saved)
desktop/packaging/make-appimage.sh           # SnapTV-Desktop-x86_64.AppImage (needs appimagetool)
```

Release: push to `main`, wait for CI to pass, then tag `vX.Y.Z`; the Release workflow builds,
signs and publishes. Signing keys come from repository secrets (local copy in `~/tools/keys`).
