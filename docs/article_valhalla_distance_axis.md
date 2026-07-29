# The Valhalla turn-by-turn distance bug that isn't in any tutorial

I build an offline motorcycle navigation app. It routes on-device with
[Valhalla](https://github.com/valhalla/valhalla), snaps the rider's GPS onto the route, and shows
the usual "in 300 m, turn left." For weeks the turn distances were subtly, maddeningly wrong: the
"200 m" callout would fire when I was clearly 350 m from the junction, or a turn would announce
late. The instructions themselves were correct. The distances were not.

The root cause turned out to be a units/axis mismatch that is easy to introduce, produces
plausible-looking-but-wrong output, and that I have not seen written down anywhere. If you are
doing client-side navigation on top of Valhalla (or OSRM, or any routing engine), this will save
you the debugging session I had.

## Two different rulers

A routed trip gives you two things that both measure "distance along the route," but on **different
rulers**:

1. **Per-maneuver `length`**. Valhalla tells you each maneuver's road length in kilometers. Sum
   these and you get the road-network distance: the true driving distance along the actual roads.

2. **The geometry polyline**, an encoded polyline of shape points. The distance you get by walking
   those points with the haversine formula is the *straight-line-between-vertices* distance. On a
   sparse polyline through curves, this is **shorter** than the true road length, because the
   polyline chords cut the corners the real road takes.

These two numbers are not equal. For a curvy route they can differ by several percent. And here is
the trap: **your live map-matcher snaps onto the polyline.**

## Why the mismatch produces wrong callouts

Live navigation works like this: each GPS fix, you find the nearest point on the route polyline and
compute how far along the polyline you are. Call that `traveled`. Then distance-to-next-turn is:

```
distanceToTurn = maneuver.cumulativeMeters - traveled
```

`traveled` is measured on the **haversine polyline ruler** because that is the geometry you snapped
onto. So `maneuver.cumulativeMeters` MUST be on the same ruler. If you built it by summing
Valhalla's per-maneuver `length` (the road-network ruler), you are subtracting two numbers measured
with different rulers. The result is garbage that happens to look like a distance.

Concretely: say a maneuver is 1000 m along the road but the polyline chord distance to it is 940 m.
If `cumulativeMeters = 1000` (road ruler) and your snap says `traveled = 200` (polyline ruler), you
compute 800 m to the turn. But the rider is actually 740 m away on the polyline you are tracking.
The error grows with route curviness and compounds over the trip.

## The fix: pick one ruler and use it everywhere

The map-matcher's ruler is not negotiable, so everything else must move onto it. Place each maneuver
on the **haversine polyline axis** using its shape index, not its road length.

Valhalla gives you `begin_shape_index` on each maneuver: the index into that leg's shape where the
maneuver begins. So:

1. Decode the full geometry (remember Valhalla shape is **precision 6**, not the usual 5).
2. Build a cumulative haversine distance array over the shape vertices, once.
3. For each maneuver, look up `cumulative[begin_shape_index]`. That is its distance-to-here on the
   same ruler the matcher uses.

```kotlin
// One distance axis: haversine cumulative distance at each shape vertex.
val cumulative = DoubleArray(geometry.size)
for (i in 1 until geometry.size) {
    cumulative[i] = cumulative[i - 1] + haversineMeters(geometry[i - 1], geometry[i])
}

// Place each maneuver on THAT axis via its shape index, not via Valhalla's road-length `length`.
val maneuvers = rawManeuvers.map { m ->
    Maneuver(
        instruction = m.instruction,
        cumulativeMeters = cumulative[m.beginShapeIndex.coerceIn(0, cumulative.lastIndex)],
    )
}

// Total distance for remaining/ETA math: also the polyline total, NOT summary.length.
val totalMeters = cumulative.last()
```

Now `maneuver.cumulativeMeters - traveled` is a subtraction of two numbers on the same ruler, and
distance-to-turn is correct. Arrival and remaining-distance math get consistent for free, because
they are all on the polyline axis too. (Keep Valhalla's `length` around if you like, for display or
diagnostics; just do not mix it into the matcher math.)

## Two gotchas worth calling out

- **`begin_shape_index` is per-leg and inclusive.** If you concatenate multiple legs into one
  geometry array, offset each leg's indices by where that leg started in the combined array.
- **Precision 6.** Decoding Valhalla's polyline with the default precision-5 factor silently puts
  every point in the wrong place. Ten-times-off coordinates. Use precision 6 for Valhalla.

## Why "it looked roughly right" hid the bug

This class of bug is nasty precisely because both rulers produce *distances*: positive numbers in
the right ballpark. On a straight highway the two rulers nearly agree, so it works. It is only on
curves, roundabouts, and dense urban turns that the gap opens up, which is exactly where accurate
turn distances matter most. If your nav "mostly works but the turn callouts feel off," check
whether your maneuver distances and your snapped-progress distance are measured on the same ruler.

Pin it down with a unit test: feed a canned Valhalla response through your parser and assert that a
maneuver's `cumulativeMeters` equals the hand-computed haversine cumulative at its shape index. If
those two ever diverge, you have a two-ruler bug.

```kotlin
@Test
fun `maneuver distance sits on the haversine axis, not Valhalla road-length`() {
    val route = parseTrip(cannedValhallaResponse)   // your parser
    val turn = route.maneuvers.first { it.type == TURN }
    // Expected: haversine cumulative at the maneuver's begin_shape_index.
    val expected = route.cumulative[turn.beginShapeIndex]
    assertEquals(expected, turn.cumulativeMeters, 0.5)
    // And the trip total is the polyline total, not summary.length:
    assertEquals(route.cumulative.last(), route.totalMeters, 0.001)
}
```

---

*Written while building an offline nav app on Valhalla. The fix is one idea: the live map-matcher
defines the distance axis, so every other distance must live on it.*

*If you do offline maps on the JVM/Android, I also wrote a small dependency-free PMTiles reader:
[pmtiles-kotlin](https://github.com/MinotaurG/pmtiles-kotlin).*
