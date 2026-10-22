# Serve mode (planned)

SnapTV Desktop as the snapserver itself, written in Kotlin in `core/`, instead of bundling the
C++ snapserver into the AppImage. Not started.

## Why not bundle snapserver

Nothing technical stops it, but it is a native C++ build in CI (CMake, Boost headers) plus its
libraries (libFLAC, libopus, libvorbis/ogg, libsoxr, avahi-client, openssl, expat) built against
an old glibc, and Snapweb on top. Most of what a server needs is already in `core/` from the
client side, so writing it ourselves is the smaller job.

## Already there

- **Wire format**: `BaseHeader`, the timestamp encoding, `MessageWriter` in `protocol/Messages.kt`.
  The server writes what the client reads (CodecHeader, WireChunk, ServerSettings, Time) and
  reads the client's Hello and Time.
- **Audio in**: `SourceMode.kt` creates the PipeWire output, captures it and paces by the
  monotonic clock. As the server our clock is the timeline, so the drift handling (dropping or
  repeating frames) goes away.
- **Time sync**: the server only answers each Time message with its own clock.
- **Codec**: PCM needs no encoder, only the 44-byte RIFF header (built in `AuthTest.kt`).
  All clients, the WT3020 too, ran PCM before FLAC.
- **Transport**: `TcpTransport` wraps a `Socket`; the server adds a `ServerSocket`. WebSocket later.
- **mDNS**: JmDNS is in the desktop app; announce `_snapcast._tcp` as well as browse it.
- **Tests**: `SnapEngine` is a client, so `:core:test` can run server and client on localhost.

## To do

1. **JSON-RPC server (port 1705)**, the biggest part. Both apps and Snapweb use it for volume,
   latency, name and source: `Server.GetStatus` in the JSON shape Snapweb and snapclient expect,
   `Client.SetVolume/SetLatency/SetName`, `Group.SetStream`, and the change notifications.
   With one stream (this computer), groups and streams are trivial.
2. **Remember clients**: name, volume and latency per client id, kept across restarts
   (java.util.prefs on desktop).
3. **ServerSettings per client**: buffer, latency, volume, mute; on connect and on every change.
4. **Real snapclient compatibility**: the WT3020 runs snapclient, so field names and message
   order must match snapserver. Test against it, not only against `SnapEngine`.
5. **FLAC encoding**: optional. PCM 48000:16:2 is ~1.5 Mbit/s per client, fine on the LAN.

## Design

- A third mode next to Play and Send: **Serve**. Linux only, desktop only (n/a on Android);
  add the row to the parity table in `CLAUDE.md` when it lands.
- If the laptop is the server, every room stops when it sleeps or leaves. So steambox stays the
  normal server, and Serve only starts when no other server is found on the network. That also
  keeps two servers from competing for Automatic.
- Ports as snapserver: 1704 clients, 1705 JSON-RPC (1780 Snapweb not planned).

## First step

A minimal `core/server/`: PCM, tcp, time sync, Hello → CodecHeader + ServerSettings, the
JSON-RPC subset above. Tested against `SnapEngine` in `:core:test`, then a real snapclient.
