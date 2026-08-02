# Troubleshooting Handout — Support Engineer Interview Stories

Four debugging problems from Recon Dash (an offline motorcycle-navigation Android app
that streams to a Royal Enfield instrument cluster). Each is grounded in the actual
git history and ride-log evidence. Chosen because together they demonstrate the core
support-engineering skills: **isolating variables, reading signals instead of guessing,
forming and testing hypotheses, and correcting a wrong hypothesis with evidence.**

Ordered strongest-first for interview use.

---

## 1. Screen-off GPS freeze on Samsung (a regression I caused, then bisected)

*The best story — it shows disciplined bisection AND intellectual honesty about being wrong.*

**1. SYMPTOM**
On a real ride, the map froze and turn-by-turn was useless: the rider's position would
not move while the phone screen was locked (phone in pocket), but jumped to the correct
place the instant the screen was unlocked. "Turn-by-turn is a complete miss."

**2. WHY IT WAS HARD**
- Intermittent and tied to device state (screen off) — not reproducible sitting at a desk.
- The app *looked* alive: the dash render loop kept running at 4 fps the whole time, so
  "the app is frozen" was clearly wrong.
- My first two hypotheses were both wrong, and each was plausible:
  a) "The keep-alive foreground service is dying" — I even shipped a fix for it.
  b) "It's Samsung Doze throttling raw LocationManager; we need FusedLocationProvider" —
     I was about to add a Google Play Services dependency.
- The user's key correction blew up my platform theory: *"screen-off GPS worked weeks ago,
  same phone, same pocket."* If it worked before, it's a **regression**, not a platform limit.

**3. INVESTIGATION**
- Added a **screen-state diagnostic** first (log SCREEN_ON/OFF/USER_PRESENT) instead of
  guessing — so the next ride would give a definitive yes/no.
- The ride log then showed a **perfect correlation**: GPS fixes stopped within ~1s of every
  `screen_off` and resumed instantly on `screen_on`. That located the *when*, not the *why*.
- Ruled out my own code as the immediate cause: only **3 "fix_drop" events all ride**, so the
  network-fix filter I'd added was NOT eating the fixes — they simply weren't being delivered.
- **Bisected against the known-good era.** The user pinned "it worked" to a specific week;
  I diffed the location code between that commit and HEAD. Exactly one relevant change: I had
  moved location delivery from the main `Looper` to a private `HandlerThread`.

**4. ROOT CAUSE**
My own earlier "improvement" (moving location callbacks to a background `HandlerThread`)
broke it. On Samsung, the OS suspends background **app** threads when the screen locks, so
fixes queued to that thread never fired. The **main Looper survives screen-off** because the
foreground service keeps it alive — which is why the original main-Looper version worked.

**5. FIX**
Reverted location delivery to `Looper.getMainLooper()` (the working-era config), removed the
HandlerThread. Kept the genuinely-useful pieces from the same work (network-fix filter, the
screen-state diagnostic). Confirmed via the diagnostic, not theory.

**6. WHAT I'D SAY IN AN INTERVIEW**
> "Users reported GPS froze when the screen was off but worked when unlocked. The app itself
> was clearly alive — the render loop kept ticking — so it wasn't a hang. I'd guessed twice
> wrong: first a service-lifecycle issue, then Samsung's battery throttling, where I nearly
> added a whole dependency. What saved me was one thing the user said: it *used* to work on the
> same phone. That reframed it from a platform limitation to a regression I'd introduced. So I
> added a screen-state log to correlate exactly, and the fixes stopped the instant the screen
> locked. Then I diffed my own history against the last-known-good version — I'd moved location
> onto a background thread, which Android suspends on screen-off, while the main thread survives
> because a foreground service holds it. Reverting that one change fixed it. The lesson I took:
> when something *used* to work, bisect what changed before blaming the platform — and add the
> instrument that proves it instead of shipping another guess."

---

## 2. ENETUNREACH send-storm when the WiFi link drops

*Shows reading a signal at scale and finding a self-inflicted feedback loop.*

**1. SYMPTOM**
Ride logs were enormous and the app felt sluggish late in rides. One 88-minute session
produced **72,000 log lines — 40,000 of them "ENETUNREACH (Network is unreachable)."**

**2. WHY IT WAS HARD**
- The error was a *symptom*, not the cause — ENETUNREACH just means "sent to an unreachable
  network." The question was *why we kept sending into a dead network 48 times a second.*
- It was easy to misread the volume as "the network is flaky, nothing we can do."
- It muddied every other investigation — the flood was a confound hiding the real GPS bug.

**3. INVESTIGATION**
- Bucketed the 40k errors by timestamp — they were **clustered from ~21:02 onward**, ~2,900/min,
  not spread evenly. So something *started* the storm.
- Read the 15 lines just before the first ENETUNREACH: `DashWifiManager: WiFi link lost —
  reconnecting in 8000ms`, immediately followed by the first failed send.
- Categorized the failing sends: **34,537 RTP + 5,708 control** — i.e. the 4 fps video encoder
  and the 4 Hz heartbeat, both firing on a schedule regardless of link state.

**4. ROOT CAUSE**
When the dash WiFi dropped, nothing told the sender to stop. The RTP encoder and heartbeat
timers kept calling `send()` into a dead socket; each call failed, logged a line, and burned a
syscall — ~48/sec. A classic **fire-into-the-void feedback loop**: the app hammered a link that
was gone.

**5. FIX**
Added a `linkUp` gate on the socket. The WiFi state collector sets it (CONNECTED → up, anything
else → down); while down, `send()`/`sendRtp()` are cheap no-ops (a suppressed counter), no
syscall, no log. A send that races the drop catches once and flips the gate. No change to the
packet format — only *whether* we send. Result next ride: **40,245 → 2.**

**6. WHAT I'D SAY IN AN INTERVIEW**
> "Ride logs were 40,000 network-unreachable errors in one session. The instinct is 'flaky
> network,' but ENETUNREACH is a symptom — the real question is why we sent into a dead socket
> 48 times a second. I bucketed the errors by time; they didn't start until mid-ride, so I read
> what happened right before the first one — the WiFi link dropped. Then I categorized the failing
> sends and they were exactly the video stream and the heartbeat, both on fixed timers. So it was
> a feedback loop: the link died and nobody told the sender to stop. I gated sends on link state —
> when the link's down, sending is a no-op. Errors went from 40,000 to 2. The takeaway: an error
> that appears 40,000 times is usually one root cause multiplied, not 40,000 problems."

---

## 3. The reroute storm — a feedback loop in the navigation logic

*Shows finding a self-reinforcing loop from a metric that "should" converge but didn't.*

**1. SYMPTOM**
On a real ride the app rerouted **20 times in 14 minutes**, and navigation **never ended** even
after reaching the destination — it just kept "navigating" past the arrival point.

**2. WHY IT WAS HARD**
- Two symptoms (reroute spam + no-arrival) looked separate but shared a cause.
- Rerouting itself is *correct* behavior when you leave the route — so "it reroutes a lot" isn't
  obviously a bug; you have to prove the reroutes were *false*.
- The naive fix (raise the off-route distance threshold) would break legitimate rerouting.

**3. INVESTIGATION**
- Read the ride log's progress metrics: remaining-distance and cumulative-distance **never
  converged** — they were being reset on every reroute, so "arrived" (remaining ≈ 0) never fired.
- Looked at *where* the false off-routes triggered: **roundabouts and curves**, where the sparse
  route polyline is 70–140 m from the actual GPS path even though the rider is on-route.
- Realized a flat distance threshold (40 m) can't tell "off-route" from "on a curve the polyline
  cuts" — you need a second signal.

**4. ROOT CAUSE**
Two coupled bugs:
- **False off-route detection**: distance-only threshold flagged normal roundabout/curve snap
  error as off-route → triggered a reroute → the fresh route reset the progress cursor → the next
  fix looked off-route again → **feedback loop.**
- **Arrival never consumed**: the code computed `arrived` but nothing acted on it, and the reroute
  loop kept resetting progress so `remaining` never reached zero anyway.

**5. FIX**
- **Heading gate**: a large snap distance only counts as off-route if the rider's *heading* also
  disagrees with the route direction (cheap map-matching). On a roundabout you're far from the
  chord but still heading along it → stays on-route.
- Speed-tiered thresholds (50/70/90 m) + consecutive-confirmation instead of a flat 40 m.
- **Post-reroute grace window** (~6 s) so the fresh route can acquire the rider before off-route
  can re-trigger — breaks the loop.
- Made arrival actually *do* something (end nav, save ride, show summary).
- 41 unit tests, including "heading-along-at-distance is NOT off-route" and "far + wrong-heading IS."

**6. WHAT I'D SAY IN AN INTERVIEW**
> "The app rerouted 20 times in 14 minutes and never registered arrival. Rerouting is normal when
> you leave the route, so I had to prove these were false. The log showed the progress metric kept
> resetting and never converged, and the false reroutes clustered at roundabouts — where the route
> line is a straight chord but the road curves, so you're legitimately 100 meters off the line while
> still on the road. Distance alone couldn't distinguish that, so I added heading: only count it as
> off-route if you're also *pointed* the wrong way. Plus a grace window after each reroute so the new
> route settles before it can re-trigger — that broke the feedback loop. The lesson: when a value that
> should converge keeps resetting, look for the loop feeding it, and add a second independent signal
> rather than just loosening a threshold."

---

## 4. Meta-lesson: instrument before you theorize

*Not a standalone bug — a thread running through all of the above. Strong closing point if asked
"how do you approach a problem you can't reproduce?"*

These bugs were all field-only (a moving motorcycle, screen off, phone in a pocket, no debugger
attached). The thing that actually moved each one forward was **building the right signal first**:

- A **persistent on-device log** that survives the rolling logcat buffer, pulled after the ride.
- **Structured, greppable log lines** (`NAVFIX`, `NAVROUTE`, `fix_src prov=… gap=…`) so a whole
  ride reconstructs and can be bucketed/correlated.
- Logging the **real** value, not an inferred one — e.g. a routing-source label that was a hardcoded
  string told us nothing; I changed it to log which engine actually answered.
- Adding a **targeted diagnostic** (screen on/off events) specifically to test one hypothesis, then
  reading the correlation — instead of shipping a fix and hoping.

> "Most of these were on a moving bike with no debugger, so I couldn't step through anything. My
> approach was to make the system tell me what it was doing: a persistent log that survives the ride,
> structured lines I can grep and bucket by time, and — crucially — logging real values instead of
> guesses. When I had a hypothesis I couldn't test at my desk, I'd add the one diagnostic that would
> confirm or kill it, then ride and read the correlation. Twice that discipline caught me shipping the
> wrong fix. The habit I'd bring to a support role: when you can't reproduce it, instrument it, and let
> the signal — not the theory — pick the fix."
