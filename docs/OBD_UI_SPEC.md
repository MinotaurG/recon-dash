# Recon Dash — OBD / Engine Live Display Spec

Design blueprint for the OBD-driven engine display, across phone and dash, with a **swappable
theme system**. Cyberpunk is the default theme; F&F Sport, Tron, HUD Rings, KITT are future themes.

This spec is the source of truth for the visual language and the data→element mapping. It does
NOT cover the OBD transport (see the `obd/` module plan) — only what we render.

---

## 1. Canvases & constraints

| | Phone | Dash (projected) | Raspberry Pi (future) |
|---|---|---|---|
| Frame rate | 60 fps | **4 fps hard cap** | unlimited |
| Shape | rectangular | round 526×300, ~15px bezel clip | rectangular |
| Motion | smooth (bars, sweeps, glitch) | **numeric + discrete/stepped only** | smooth |
| Role | show-off / sport | glanceable while riding | full cluster |

**Dash canvas & view model (corrected from rider):**
The **entire region above the golden bar is OUR canvas** — we replicate RE's native views there,
and all of it is modifiable (turn-by-turn arrow, the RPM arc that turns off on nav, the map
backdrop). The golden bar itself is RE-native (street/clock/temp/ODO/gear/speed/dest-dist/fuel).

Two dash screens we drive:
1. **Home / idle screen** — the only always-available screen. Today: wallpaper (or nothing).
   THIS is where our idle view lives — rider chooses **wallpaper OR the Cyberpunk OBD cluster**.
2. **Navigation menu** (joystick-accessed) — the **full H.264 map projection**.

**Turn-by-turn is a PERSISTENT OVERLAY, not tied to one screen.** The rule:
> Once navigation is started from the phone, the turn-by-turn (maneuver arrow + distance +
> roundabout exit) must render on WHATEVER dash screen is showing — the idle/OBD cluster OR the
> full-map view. It overlays on top, like RE's own TBT+golden-bar persist across their screens.
> (Our current TBT is broken — fixing it is the nav priority.)

So the OBD cluster and navigation are NOT mutually exclusive: the rider can be on the engine
cluster and still get turn prompts overlaid.

**4 fps rule:** anything that must animate smoothly (fill bars, sweeping needles, throttle blips)
is PHONE/Pi only. The dash uses big numbers, discrete segment ticks, stepped shift lights, and
state changes — all of which read fine at 4 fps.

---

## 2. Data → element mapping (all real PIDs)

| Element | PID(s) | Formula / notes |
|---|---|---|
| RPM | `010C` | ((A*256)+B)/4 |
| Speed | `010D` | A km/h |
| Throttle % | `0111` | A*100/255 |
| Engine load % | `0104` | A*100/255 |
| Coolant °C | `0105` | A-40 |
| Intake air °C | `010F` | A-40 |
| Timing advance | `010E` | A/2-64 |
| MAF | `0110` | ((A*256)+B)/100 → also feeds power/fuel estimate |
| MAP | `010B` | A kPa |
| Baro | `0133` | A kPa |
| Battery / module V | `0142` | ((A*256)+B)/1000 |
| Short/long fuel trim | `0106`/`0107` | (A-128)*100/128 |
| O2 / wide-range lambda | `0114`/`0124`+ | AFR / lambda |
| **Gear** (derived) | RPM ÷ speed | ratio bucketed into gears; not a PID |
| **Power** (derived) | MAF + RPM | rough HP estimate for dyno/curve |

---

## 3. Theme system (architecture)

A theme is a pure descriptor consumed by both renderers; adding one never touches the OBD data
pipe or the `ObdSnapshot`.

```
interface EngineTheme {
    val palette: Palette           // bg, primary, secondary, accent, warn, danger, text
    val typography: Typeface specs // display/number/label fonts
    val gauge: GaugeStyle          // ring vs bar vs arc; segment vs smooth
    val effects: EffectRules       // glitch/scanline/bloom on/off + triggers
    val boot: BootSequence?        // connect animation
    fun tileFrame(): FrameStyle    // card/bracket/chamfer shape
}
```
Renderers (phone Compose, dash MapRenderer-style canvas) read the theme; the data (`ObdSnapshot`
+ `NavProgress`) is theme-agnostic. Theme switch = swap the descriptor, re-render.

Themes planned: **Cyberpunk (default)**, F&F Sport, Tron, HUD Rings, Analog Reborn, KITT.

---

## 4. THEME #1 — Cyberpunk (default)

### Palette
- bg `#0A0A0F` (near-black, faint dark-teal wash)
- primary `#00E5FF` (cyan) · secondary `#FF2A6D` (hot magenta)
- warn `#F9F002` (electric yellow) · danger `#FF2A6D` intensified
- accent `#D4A853` (Recon gold, tertiary — keeps brand thread)
- text `#EAF6FF`

### Typography
Condensed near-mono display (Rajdhani / Blender-Pro family). Big glowing numerals with a subtle
chromatic-aberration edge (cyan/magenta 1px fringe). Labels UPPERCASE, wide letter-spacing.

### Signature devices
- **Angular bracket frames** — `⌜ ⌟` corner-cut boxes, 45° chamfers, thin neon stroke + inner glow. No rounded cards.
- **Glitch on state change** — redline cross / warning fire → ~150ms RGB-split + scanline tear + flicker, then snap clean. (Phone: on any alert. Dash: ONLY on connect + alerts — never continuous while riding, for safety.)
- **Scanlines + bloom** — faint horizontal scanline overlay; neon bloom on bright elements.
- **Decrypt spin-up** — values scramble-in like a terminal decrypting, then resolve.
- **Diegetic chrome** — corner frame ticks, `RECON//SYS` label, pulsing `◈ OBD LINK` dot.

### Boot sequence (on OBD connect, ~2s)
Breach-protocol style: scanlines sweep top→bottom; text scrambles in
`ESTABLISHING LINK… ECU 7E8… DECRYPTING…`; gauges power up with a glitch; settle to
`SYSTEMS ONLINE`. Phone full; dash a shorter stepped version.

### Phone layout (60fps, full)
```
⌜═════════════ RECON//SYS ═══════════════ ◈ OBD LINK ⌝
  ⌜RPM⌟                                  ⌜THR⌟
  ▓▓▓▓        ╔══════════════╗            ░░
  ▓▓▓▓        ║   4 2 5 0    ║            ▓▓      cyan RPM bar (fills up),
  ▓▓▓▓        ║   ⟨ R P M ⟩  ║            ▓▓      magenta redline zone glitches,
  ▓▓▓▓        ╚══════════════╝            ▓▓      throttle bar mirrors (magenta)
  ▓▒░░ redline                            ██
  ⌜ 92 ⌟km/h   ⌜ 87° ⌟C   ⌜ 14.2 ⌟V   ⌜ GEAR 3 ⌟
  ── shift light: green→amber→red sequential across top ──
                (scanline overlay whole screen)
```

### Dash layout (4fps, round, above golden bar — RPM arc space is ours now)

**Do NOT duplicate what the golden bar already shows** (RE's native widget renders: turn arrow,
current street, dist-to-turn, dest dist, clock, ambient temp, **gear**, **speed**, fuel range).
Our zone shows what the RE cluster does NOT: the engine-health data.

```
        ╭───────────────────────────╮
      ╱   ◜ R P M   S E G M E N T S ◝  ╲     discrete neon blocks light up
     │  ▮▮▮▮▮▮▮▮▮▮▮▮▮▮▮░░░░░  redline ▮  │    with RPM (stepped, 4fps-safe)
     │                                  │
     │        ⟨ 4 2 5 0 ⟩               │    huge glowing RPM number (hero)
     │                                  │
     │  COOL 87°   BATT 14.2V   ⚠︎       │    engine data the bar lacks;
     │  LOAD 34%   THR 60%             │    ⚠ glitches on thermal/batt alert
        ╰═══════ golden bar (RE) ══════╯     (gear + speed already in the bar)
```
RPM is the hero (the bar doesn't show it well). Coolant/battery/load/throttle are the value-add.
Gear + speed intentionally omitted — already in the golden bar. No smooth bars (4fps); RPM as
stepped segment blocks + big number. Glitch only on connect/alert.

### Cruise vs Sport sub-modes (phone)
- **Cruise:** calm — big speed, coolant/battery/fuel tiles, nav ETA. Minimal neon, no bars.
- **Sport:** the full layout above — bars, shift light, glitch. Toggle.

---

## 5. Nav enhancement (related, from dash photo)
Show the **current street name** during navigation on the **PHONE nav card** (not just
destination). Source: Valhalla maneuver `street_names` / `begin_street_names` (currently discarded
in `Router.parseTrip`). NOTE: the dash golden bar already shows the current street via RE's native
widget — so this is a phone-side enhancement; don't duplicate it in the dash projected zone.

---

## 6. Build order (when we start)
1. OBD data pipe (`obd/` module) → `ObdSnapshot` StateFlow. (Separate plan.)
2. `EngineTheme` interface + Cyberpunk theme descriptor.
3. Phone Sport screen (Compose) — the F&F-style bars in cyberpunk skin. Proves the pipe.
4. Dash cyberpunk numeric layout in the MapRenderer path (reuses the projection pipeline).
5. Boot sequence + glitch-on-alert.
6. Future themes (F&F Sport, Tron…) as additional `EngineTheme` descriptors.
