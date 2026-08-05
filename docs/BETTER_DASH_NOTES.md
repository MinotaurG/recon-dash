# better-dash — reference notes (not for copying)

[OpenMotoDash/better-dash](https://github.com/OpenMotoDash/better-dash) is the successor to
OpenDash, same community, **Apache-2.0**. It's a **Python app for a Raspberry Pi Zero** (the
Pi replaces the phone), Qt/pygame → raw RGB24 → ffmpeg/libx264. Very different architecture
from our Android/Kotlin app, so nothing is portable line-for-line.

**Legal posture:** same lineage as the OpenDash RE takedown. Use ONLY as an *independent
confirmation source* for protocol facts (facts aren't copyrightable) and for feature ideas.
Do NOT port code — our `dash/` is already OpenDash-derived and the goal is to clean-room away
from that, not deepen it. See `docs/PROTOCOL_CAPTURE.md` and `docs/LEGAL_NOTES.md`.

## Useful lead: framerate beyond 4 fps

better-dash reports running the dash at **8-12 fps** (stock RE app uses 4). Their own note:
*"8-12 is more responsive but pushes the dash decoder. Drop back to 4 if the dash blinks."*
So it is NOT free — it trades against decoder stability — but it is apparently achievable.

Their H.264 encoder settings when raising fps (facts, for our own experiment — we'd implement
this in our MediaCodec encoder, not copy ffmpeg flags):

- Codec: H.264 **baseline 4.1**, 526×300, one slice per picture.
- Stock: **4 fps, 204800 bps, GOP = 4** (IDR every 1 s).
- Raised: **12 fps, bitrate 300-450 kbps** ("300-450 is reliable at 12 fps"). Bitrate must
  scale up with fps or the stream degrades.
- GOP kept at `fps × gop_sec` (fixed 1 s cadence); `scenecut=0` (fixed GOP), `repeat-headers=1`
  (SPS+PPS in-band on every IDR), lookahead disabled for low latency.

**For us:** if dash video smoothness ever becomes a priority, try raising our MediaCodec
encoder to 8 fps first (bitrate ~300 kbps, keep IDR at 1 s), watch for the dash "blinking"
(decoder overrun) and back off to 4 if it does. Our hard constraint currently assumes 4 fps is
the decoder ceiling — better-dash suggests there's headroom, but verify on hardware before
trusting it. Independent, hardware-gated experiment; not a given.

## Other observations (no action needed)

- **Protocol sequence — independent confirmation.** Their `bike_link.py` documents the same K1G
  flow we use: latch projection (q3c.g + q3c.w) → q3c.z2 "start navigation" → 4 Hz q3c.g +
  1 Hz route-card + 1 Hz nav-info heartbeat; buttons arrive as `09 00 0001 XX` on UDP/2002.
  Two independent projects documenting the same bytes = evidence it's factual protocol.
- **Glyphs — same dead-end.** Their `BikeLinkConfig` defaults `nav_maneuver =
  NAV_MANEUVER_CONTINUE` (0x0B), same as us and OpenDash. Nobody has mapped the other glyph
  codes; our active probe remains the only known path.
- **GPX pre-loading** (`gpx.py`): they load a route from a GPX file before the ride (a Pi has no
  router). We already do live on-device Valhalla routing, which is more capable — low priority,
  but a possible "import a planned route" feature someday.
