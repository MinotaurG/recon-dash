# Valhalla vs Google Routes — divergence findings

Source: `route_divergences` Room table, populated by `DivergenceCapture` (debug-only; queries the
Google Routes API on plan/reroute/periodic and compares to the live Valhalla route). 47 rows across
rides through 2026-08-05. `periodic` rows compare a SHRINKING Google route (from current position) to
the FIXED full Valhalla route, so they're expected to diverge — only **`plan` and `reroute` rows are
fair same-origin/dest comparisons**.

## Finding 1 — ETA is optimistic because Valhalla has no live traffic (EXPECTED, not a bug)
On every fair row, Valhalla time ≈ 55–60% of Google time:

| id | ctx | V dist | G dist | V sec | G sec |
|----|-----|--------|--------|-------|-------|
| 40 | plan | 12687 | 13583 | 909 | 1650 |
| 33 | plan | 12702 | 11642 | 979 | 1627 |
| 17 | reroute | 13631 | 14648 | 984 | 1669 |
| 22 | reroute | 8056 | 8166 | 642 | 1197 |

Google's seconds include **live traffic**; Valhalla uses **free-flow** road speeds. So the gap is
traffic, not a costing error — and it's **unfixable offline** (no traffic feed on-device). Options:
apply a blanket congestion factor to ETA (e.g. ×1.5 in-city) for a less-optimistic display, or label
the ETA as "no traffic". Distances are close, so routing itself is sound.

## Finding 2 — ~63–67% geometry overlap on the ORR corridor (road-choice difference)
Rows 1–6, 15–16, 38–41 (all the same ~12.7km trip through the Outer Ring Road / HITEC City area)
sit at 63–67% overlap: Valhalla and Google pick materially different roads there. This is the same
corridor where the maneuver glyphs got confusing on the 2026-08-05 ride ("keep left / bear left onto
flyover" stretch). Worth eyeballing both polylines (stored in the table) to see if Valhalla takes a
worse line, or just a different-but-valid one.

## Finding 3 — the glyph problem is the maneuver→glyph MAPPING, not the engine
The 2026-08-05 ride log (`session-20260805-210815.log`) shows the nav engine tracking correctly
(dman counts down to each turn, snap median 3m, 3 off-route blips all ride). But the route was full
of **"keep left / bear left / take the ramp"** maneuvers, which Valhalla emits as kStayLeft/kRampLeft
and our `mapValhallaType` collapses to `SLIGHT_LEFT → glyph 0x16`. The dash has DISTINCT fork/keep
glyphs (SPEC.md 0x03–0x08, 0x1D–0x24) that we're not using — so "keep left" showed a slight-left
turn arrow instead of a fork/keep glyph. Matches the rider report: right at the start (plain
turns + roundabout), wrong through the keep/fork/ramp stretch.

**Next step (in progress):** NAVFIX now logs `mtype=`, `glyph=`, `exit=` (the exact byte sent), so
one more ride gives ground-truth per-maneuver codes to correct the fork/keep/ramp mappings from data
instead of inference. Then extend `mapValhallaType`/`Maneuver.dashCode` to use the fork/keep glyphs.
