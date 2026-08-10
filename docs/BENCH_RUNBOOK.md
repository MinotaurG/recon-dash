# Bench session runbook (Mac + bike)

One session to knock out all parked verification. Bike **powered on (dash lit), stationary**,
phone on the bike, Mac nearby. Do the in-app captures FIRST (Mac is free), then the WiFi sniff
(the sniffer takes the Mac's WiFi offline, so it's last).

Prereqs: phone has the latest debug build installed; USB-connect the phone to the Mac when pulling
artifacts. `ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb`.

---

## A. Glyph LABELER  ⭐ (fixes the wrong-glyph bug — top priority)
Ground-truth byte→glyph map, verified by your eyes (the current map was guessed from video and has
wrong left/right + shapes).

1. Dash tab → Connect → wait for **Streaming**.
2. Prop the phone so you can see BOTH the phone and the dash.
3. Tap **Start glyph labeler (tap what you see)**.
4. For each code: look at the DASH, tap the matching button on the phone (Turn L/R, Sharp L/R,
   Keep/Fork, U-turn, Roundabout, Lanes, Straight, Blank, Other). ~50 codes (0x00–0x32).
5. Skip if unclear. It stops itself at 0x32.
- **Artifact (pull after):**
  `$ADB exec-out run-as com.recon.dash.debug cat files/glyph-label/label-<ts>.csv > glyph-label.csv`
- Tell me when done; I rebuild `Maneuver.dashCode` from this and the wrong glyphs are fixed for good.

## B. Live OBD dongle  ⭐ (brand-new, never tested on hardware)
1. Plug the ELM327 into the bike's OBD port; pair it in the phone's Bluetooth settings first.
2. Open the **Telemetry** home tile.
3. Expect: "Connecting…" → the cyberpunk cluster with live RPM/speed/coolant. It tries OBD-named
   paired devices first, then any other paired device.
4. If it stays on a prompt (Turn on BT / Connect module / retry) or shows wrong numbers:
   - Note the prompt text.
   - Pull the driver log: `$ADB logcat -d -s Elm327Source > obd.log` (also grep `GLYPHMAP`-style
     lines aren't here — it's `Elm327Source` tag).
   - Tell me your dongle's exact Bluetooth name + what the log says; ELM327 clones vary and I'll
     tune the init/parse to yours.

## C. Nav-field (flash/color) probe
The turn arrow flashes red constantly; this hunts for the field that controls color/flash.
1. Dash tab → Streaming → **Start nav-field probe (flash/color)**.
2. Watch the dash arrow: does the flashing calm or change color at any step? Note the label shown
   on the button (e.g. "05 0C = 04") when it does.
- **Artifact:** `files/navfield-probe/navfield-<ts>.csv` (pull like A).

## D. Screen-focus probe
Find the command to auto-open the Nav/Phone/Media carousel screen.
1. Dash tab → Streaming → **Start screen probe (06 family sweep)**.
2. Watch the dash carousel: does it jump to a different screen at any step? Note the button label
   (e.g. "sub 06 0x12").
- **Artifact:** `files/screen-probe/screen-<ts>.csv`.

---

## E. Own-app WPA2 protocol capture (Mac WiFi sniffer — do LAST)
The clean-room observation record. Our app uses WPA2 (decryptable), unlike the RE app (WPA3).

1. **Before joining:** ⌥-click WiFi → Wireless Diagnostics → Scan (⌘4) → find the `RE_...` row →
   note channel (should be **2**) + BSSID.
2. Wireless Diagnostics → Sniffer (⌘6) → set **channel 2, width 20 MHz** → **Start**. (Mac drops
   off WiFi now — expected.)
3. On the phone: open the app, Dash tab → Connect → let it stream ~1 min. (If it's already paired
   and auto-reconnects, toggle phone WiFi off/on so the sniffer catches a fresh WPA2 4-way
   handshake — no handshake = can't decrypt.)
4. **Stop** the sniffer. The `.pcap` lands in `/var/tmp/`.
5. Copy it into `captures/2026-08-05-bench-ownapp/` (gitignored) and tell me — I decode the auth
   handshake + glyph packets on the wire (cross-checks the labeler) into `SPEC.md`.

---

## Priority if time is short
1. **A (glyph labeler)** — fixes a real nav bug, in-app, ~5 min.
2. **B (OBD dongle)** — verifies brand-new hardware code.
Everything else (C, D, E) is bonus / protocol research.

## After: pull everything in one go
```
$ADB exec-out run-as com.recon.dash.debug tar -c -C files glyph-label screen-probe navfield-probe \
  > /tmp/bench-artifacts.tar 2>/dev/null    # then extract on the Mac
$ADB logcat -d -s Elm327Source DashViewModel DashSession > /tmp/bench-logcat.txt
```
