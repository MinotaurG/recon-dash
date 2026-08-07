# Plan: Navigation tile + Saved-Places overhaul

Two related UI features. Pure app/UI work — no protocol/hardware. Build on a fresh branch
`feature/nav-tile-places`. Sequenced: **Places first (smaller, well-scoped), then Nav tile.**

---

## Feature A — Navigation tile (full map + search bar)

### Goal
A home tile (nav icon) → opens a full-screen **interactive** map (pan/zoom, offline pmtiles)
with a **floating search bar at the top**. Tapping the search bar opens search; picking a
result → existing route-preview → active nav. CarPlay single-screen model.

### What already exists (reuse, don't rebuild)
- `MapViewComposable` — MapLibre + offline pmtiles map. Today it FOLLOWS the rider; we add a
  free-pan mode (no auto-follow, gestures enabled).
- `SearchScreen` + Photon/Places autocomplete — the search logic exists.
- Route flow: `Routes.ROUTE_PREVIEW` → `Routes.ACTIVE_NAV` — unchanged.

### To build
1. New `Routes.NAV_MAP` screen: `MapViewComposable` in free-pan mode + a floating search bar
   overlaid at top (rounded, dark, "Where to?").
2. Search bar tap → navigate to existing `SEARCH` route (or overlay search results on the map —
   decide during build; overlay is nicer but a bigger change, start with navigate).
3. Result selected → `ROUTE_PREVIEW` (existing) → nav. No change to preview/active-nav.
4. New home tile `id="nav"`, nav icon (Icons.Rounded.Navigation / Map), **placed 4th, right
   after Dash**. New order: home, office, dash, **nav**, music, rides, garage, places, settings.

### Open UX decision (confirm during build)
- Search-on-map overlay vs. navigate to SearchScreen. Start simple (navigate), revisit.
- Free-pan map: show a "recenter on me" button; long-press to drop a destination pin? (later)

### Caveats
- The interactive map is a new *mode* of MapViewComposable (follow=false, gestures on). Small.
- Offline: search still needs Photon (online). Map tiles are offline. Note the split.

---

## Feature B — Saved-Places overhaul (presets + custom name + icons)

### Current limitation (the gap you hit)
`FavoritePlace` has a fixed slot enum (HOME, OFFICE, CUSTOM_1..4) and **no icon field**.
Custom slots show "Place 1" with an auto/fixed icon; you can't rename to "Gym" or pick an icon.

### Target model
- **Home & Office stay pinned** on the home screen as-is (always present — user confirmed).
- **Presets** for saved places: **Gym, Friend 1, Friend 2, Fuel, Food, Custom** — each with a
  fitting default icon AND an editable name (e.g. "Friend 1" → "Rahul"), plus a fully-custom
  option (free name + pick any icon).
- **Icon picker**: a grid of ~24–40 Material icons (home, work, gym/fitness, person/friend,
  fuel, restaurant, cafe, shopping, hospital, school, parking, hotel, beach, mountain, star,
  heart, etc.) to choose from.

### Schema change (Room migration)
`FavoritePlace` gains an `icon: String` field (icon key, e.g. "gym") and we move away from the
fixed CUSTOM_1..4 enum toward a flexible list:
- Option 1 (smaller): keep the slot enum but add `icon` + rely on the existing `name`/`label`
  for the custom text. Rename works via `name`; icon via new field.
- Option 2 (cleaner, more work): replace slot-keyed rows with an id-keyed list of arbitrary
  saved places (add/remove any number), each with name + icon + preset category.
- **Decision: Option 1 first** (add `icon`, use `name` for rename, keep 4 custom slots) — least
  risk, ships the user-visible win (rename + icon + presets) fast. Room migration adds one column.
  Revisit Option 2 (unlimited places) later if 4 slots isn't enough.

### To build
1. Add `icon: String` to `FavoritePlace` + Room migration (v+1, `ALTER TABLE ADD COLUMN icon`).
2. `PlaceIcon` catalog: a map of icon-key → ImageVector, with preset→default-icon mapping.
3. Preset picker + name field + icon-grid picker in the place-edit UI (ManagePlacesScreen /
   SavedPlacesScreen).
4. Home tiles + saved-places list render the chosen icon and custom name.

### Caveats
- Room migration must be tested (don't wipe existing saved Home/Office). Add a migration, not
  destructive fallback.
- Icon set uses `androidx.compose.material.icons` (already a dep) — no new assets.

---

## Build order
1. **B (Places)** — schema + migration, icon catalog, edit UI, render. Ships the clear win.
2. **A (Nav tile)** — nav-map screen + free-pan mode + search bar + tile at position 4.
3. Verify on device; iterate on the search-on-map UX.

## Out of scope (for now)
- Unlimited saved places (Option 2) — revisit after Option 1.
- Search-results overlaid directly on the nav map — start with navigate-to-search.
- Long-press-to-drop-pin destination — later.
