# Dash Protocol Capture (Clean-Room)

Goal: capture the Royal Enfield Tripper dash protocol **on the wire**, from the RE
official app, so the `dash/` layer can be re-specified and reimplemented from our own
observations rather than from OpenDash's source.

> **Why this matters (see `docs/LEGAL_NOTES.md`):** the current `dash/` code is copied
> from OpenDash (Apache-2.0). A capture-derived clean-room rebuild is the only way the
> protocol code becomes genuinely ours. Capture using the **RE app** as the talker so no
> OpenDash-derived code is in the loop.

## Hardware reality (as of this project)

- **M1 Pro Mac (Apple Silicon):** built-in WiFi CAN do monitor mode via Wireless Diagnostics.
  FREE, no purchase. This is the capture rig. Cannot run x86 Kali/Ubuntu live USB, and USB
  monitor-mode adapters (AR9271/MT7612U) have NO Apple Silicon macOS driver — so the
  "adapter + Linux" combo does not apply here. Use the built-in sniffer instead.
- **Galaxy Tab S9 SE:** bootloader locked (OEM unlock removed in One UI 8, on bit D — no
  downgrade). Cannot root, cannot live-boot. proot Ubuntu in Termux does NOT help: monitor
  mode is a kernel/driver/hardware capability, proot is userspace-only above the Android
  kernel, so it can't touch the radio. Tablet is not a capture device (can only ANALYZE a
  .pcap after the fact).
- **USB monitor-mode adapter (AR9271 / MT7612U):** useless on the Apple Silicon Mac (no
  driver) and on the tablet (no kernel support). Would only help with a separate x86 Linux
  box. Don't buy one — the Mac's built-in sniffer covers this.

**Band:** the RE dash broadcasts on **2.4 GHz**, so only 2.4 GHz capture is needed.

**Current path: the M1 Pro Mac's built-in Wireless Diagnostics Sniffer.** Free, works today.

## Secondary goals for the same capture (added 2026-08-02)

One RE-app capture can answer three things at once. Prioritised:

### G1 — Turn glyph maneuver codes (high confidence win)
In **analogue / turn-by-turn mode** the RE app *sends* maneuver packets phone→dash
and the dash renders its native glyph. We currently hardcode the maneuver glyph to
`CONTINUE` (0x0B) because we never decoded the real codes. Capture the RE app
navigating a route with varied turns (left, right, slight, sharp, roundabout exits,
U-turn, arrive) and record **which byte code precedes each glyph change** on the
dash. This is phone→dash, so no decryption barrier beyond the WPA layer (PSK known).

Cross-check with an **active probe** — no RE app needed for this half: from our own
app, send maneuver codes `0x00..0x2F` one at a time and photograph which glyph the
TFT shows. The sniff is the reference; the probe is the confirmation. Do both.

Fill in:

| Sent code (hex) | Glyph shown on dash | Maneuver meaning |
|-----------------|---------------------|------------------|
| 0x0B            | (baseline)          | continue / straight |
|                 |                     |                  |

### G2 — `0F` telemetry subscribe command (the engine-data question)
Per `docs/OBD_TELEMETRY_FINDINGS.md`, our own sessions receive almost no `0F`
(encrypted instrument-cluster) payload — only 2 packets in a full ride. Hypothesis:
the RE app sends a **subscribe/request** command the dash needs before it streams
telemetry, which we never send.

What to capture / check:
- Does the RE app receive a **steady stream of `0F` packets** during a ride (vs our
  ~2)? Count them. If yes → there IS a trigger we're missing.
- Find the phone→dash command that precedes the `0F` stream starting. That direction
  is readable. Record its port + bytes.
- We CANNOT decrypt the RE app's `0F` payloads (its AES key is client-generated and
  RSA-sealed to the dash — different key than ours). We are mining the **request**
  and the **packet volume**, not their plaintext. Once we know the trigger, we replay
  it from our app and decrypt the resulting `0F` with **our** key.
- If the RE app *also* gets little/no `0F` engine data → confirms engine telemetry is
  CAN/OBD-only and the dash never puts it on WiFi. That closes the question for good.

### G3 — Auth handshake bytes (original clean-room goal, below)

## What we're hunting (from the OpenDash-derived DashSession comments)

The connection sequence, on the dash's local AP (dash IP typically `192.168.x.1`):

- **:2000** — initial burst incl. `q3c.e` (request-auth); route-card packets; `z2` transition
- **:2002** — RX control channel: `07 00` / `07 03` -> app sends `q3c.d` -> waits `07 01 01`
- **:5000** — H.264 / RTP projection video (RTP header starts `80 e0 ...`, payload type 96)
- Auth is an **RSA handshake** (DashAuth) — the bytes we most need and don't yet have.

A prior PCAPdroid capture only caught the outbound :5000 RTP — the app **binds sockets to
the dash network**, which bypasses a VPNService capture. That's why we sniff off-air instead.

## Procedure — macOS Wireless Diagnostics Sniffer (M4)

### Phase 1 — find the dash channel
1. Power the bike so the dash broadcasts its `RE_...` WiFi.
2. Hold **⌥ (Option)** + click the WiFi menu -> note the dash SSID.
3. Hold ⌥ + click WiFi -> **Open Wireless Diagnostics** -> ignore the wizard ->
   menu **Window -> Scan (⌘4)**. Find the `RE_...` row. Record:

   ```
   SSID:    RE_____________
   BSSID:   __:__:__:__:__:__
   Channel: ____        Width: ____ MHz   Band: 2.4 / 5 GHz
   ```

### Phase 2 — capture
4. Wireless Diagnostics -> **Window -> Sniffer (⌘6)**.
5. Set **Channel** = dash channel, **Width** = matching (usually 20 MHz on 2.4 GHz).
6. **Start.** The Mac drops off all WiFi and begins capturing (offline during capture — fine).
7. **Now** connect the phone to the dash **using the RE official app**:
   connect -> authenticate -> start navigation -> let it stream ~1 min.
   - **CRITICAL:** the WPA 4-way handshake happens when the phone JOINS. Sniffer must be
     running BEFORE the phone connects. If already connected, toggle the phone's WiFi
     off/on to force a fresh join while the sniffer runs — no handshake => can't decrypt.
8. **Stop.** Capture is written to `/var/tmp/` (`.pcap` / `.wcap`).

Budget 2-3 attempts; built-in monitor mode is finicky about channel width and catching the join.

### Phase 3 — decrypt & read
9. `brew install --cask wireshark`
10. Wireshark -> Settings -> Protocols -> **IEEE 802.11**:
    - Enable **"Enable decryption"**
    - Decryption keys -> add -> type **wpa-pwd**, value `12345678:RE_xxxx` (password:SSID)
11. Filter to the dash: `wlan.addr == <dash BSSID>` then `ip.addr == 192.168.x.1`.
12. Confirm the 4-way handshake is present: filter `eapol` (expect 4 packets). If absent, redo.
13. Read the plaintext exchange on **:2000** and **:2002**.

## Decoding template — fill this in from the capture

For each packet in the handshake, in order. Keep this factual (bytes + meaning), NOT code.

```
# Packet N
dir:       app->dash | dash->app
port:      2000 | 2002 | 5000
time:      +__ ms from connect
len:       ___ bytes
hex:       __ __ __ __ ...
meaning:   (e.g. "byte0 = packet type 0x07; byte1 = subtype 0x00; ...")
```

### Handshake sequence (target spec)

| Step | Dir | Port | Marker bytes | Meaning |
|------|-----|------|--------------|---------|
| 1 | app->dash  | 2000 | `q3c.e ...`   | request-auth (contains? nonce / app pubkey?) |
| 2 | dash->app  | 2002 | `07 00`       | ? |
| 3 | dash->app  | 2002 | `07 03`       | ? |
| 4 | app->dash  | ?    | `q3c.d ...`   | auth response (RSA-signed? with what?) |
| 5 | dash->app  | 2002 | `07 01 01`    | auth OK -> proceed |
| 6 | app->dash  | 2000 | route-card x4 | destination establish |
| 7 | app->dash  | ?    | `z2` (once)   | mode/projection switch |
| 8 | app->dash  | 5000 | RTP `80 e0`   | H.264 stream begins |

> The RSA specifics (key source, what's signed, padding) are the crux. Note exactly which
> bytes change between two separate captures (nonce/random) vs. stay constant (keys/format).

## Clean-room discipline (so the rebuild is defensible)

1. This spec = **facts only** (bytes, offsets, sequence, field meaning). No code.
2. Protocol facts are not copyrightable; the interoperability carve-outs protect this.
3. Reimplement `dash/` **from this spec**, ideally without OpenDash's files open. You have
   already seen their code, so document the derivation trail and keep the rewrite genuinely
   from-spec — a relabel is not a clean room.
4. This addresses the COPYRIGHT axis only. The RSA-handshake/TPM and RE-complaint axes are
   unchanged (see `docs/LEGAL_NOTES.md`). Personal, undistributed use remains the safe posture.

## Capture log

| Date | File | Channel | Handshake caught? | Notes |
|------|------|---------|-------------------|-------|
|      |      |         |                   |       |
