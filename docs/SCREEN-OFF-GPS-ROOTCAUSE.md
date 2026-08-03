# Screen-off GPS freeze — root cause (RESOLVED 2026-08-02)

## Symptom
On rides, the map/turn-by-turn froze while the phone screen was locked (in pocket) and
resumed on unlock. Ride logs showed GPS fixes dropping from ~1/sec to **~20-second gaps**,
often network-only, during screen-off windows — nav effectively dead when locked.

## Root cause
**Android/Samsung One UI Battery Saver.** It was left ON (since a trip) and throttles
screen-off location *delivery* to the app to ~20s network-only batches. The GPS chip keeps
producing fixes (verified: OS `dumpsys location` fix timestamp advanced continuously) — the OS
just withholds them from the app when the screen is off under Battery Saver.

Critically: **per-app "unrestricted battery" + "Allow all the time" location do NOT override
system Battery Saver.** All those were correctly set and it still throttled.

## Why it took so long (honest debugging notes)
- It was NOT a code regression. Every diff of the location code across the "working" era vs.
  now was functionally identical — because the cause was an OS power-mode setting, not code.
  Chasing commits (HandlerThread, keep-alive, reroute, Google Routes) were all dead ends.
- A flawed A/B test misled us: toggling Battery Saver OFF **mid-session** did NOT fix it,
  because Android had already applied the throttle to the running app process and doesn't
  re-evaluate live. That produced a false "not Battery Saver" conclusion.
- The fix was proven only after **force-stopping the app** (fresh process) WITH Battery Saver
  off: screen-off fixes went to steady ~1s, GPS-dominant (241 gps vs 17 network,
  median 999ms / p90 1007ms — vs p90 20,109ms while throttled).

## What actually resolves it
1. **Turn OFF system Battery Saver** (Settings > Battery), AND
2. **Restart the app** (force-stop / fresh launch) so the OS re-evaluates it without the
   already-applied throttle.

**Verified on the road 2026-08-03.** Two recon-dash nav rides with Battery Saver off +
fresh launch: screen-off GPS ran at a steady ~1 Hz (553 GPS fixes, median 1000 ms /
p90 1006 ms) — indistinguishable from screen-on. Contrast 2026-08-02 with Battery Saver
on: 0 screen-off GPS fixes, p90 ~224,000 ms. Fix confirmed end-to-end.

## What we shipped
- `NavDisplayState.batterySaverOn` + `isBatterySaverOn()` (PowerManager.isPowerSaveMode),
  checked each location update.
- A prominent in-nav **warning banner**: "Battery Saver is ON — turn it OFF for reliable GPS
  with the screen locked." Logged as `NAVEVT battery_saver on=true`.
- The screen-state diagnostic (`NAVEVT screen_off/on`) that made the correlation provable.

## Diagnostics that cracked it (reusable)
- `adb shell dumpsys location` — the `last location=Location[gps ... et=+..]` timestamp: if it
  advances, the chip is alive; the provider `ProviderRequest`/listener `(inactive)` shows OS
  throttle state.
- `adb shell settings get global low_power` — 1=Battery Saver ON.
- `adb shell am get-standby-bucket <pkg>` — standby throttle (5/10=active).
- `adb shell cmd appops get <pkg>` — effective FINE/COARSE/BACKGROUND location state.
- Per-fix log line `NAVEVT fix_src prov=<gps|network> gapMs=<n>` — the gap distribution is the
  health metric (healthy ≈ 1000ms; throttled ≈ 20000ms).

## Still open / optional
- **FusedLocationProviderClient** would be a robustness upgrade (Play Services delivery survives
  some OEM throttling that raw LocationManager doesn't) — NOT required now that the cause is known,
  but worth considering if we want the app to tolerate Battery Saver rather than just warn.
