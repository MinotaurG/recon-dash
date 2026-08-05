# Legal Notes (working understanding — NOT legal advice)

> **Disclaimer:** I am not a lawyer and this is not legal advice. These are the project's
> own working notes on risk, written to guide engineering decisions. If this project ever
> moves toward distribution, get real legal advice at that point.

## Posture in one line

**Personal, undistributed use of a tool for your own bike.** That is a fundamentally
different (and much lower) risk surface than publishing an app. The single strongest
protection right now is: **keep it private, don't distribute, no RE branding.**

## The three independent risk axes

Clean-room only defends ONE of these (copyright). Do not conflate them.

### 1. Copyright (the `dash/` code)
- `dash/` is currently **copied from OpenDash (Apache-2.0)**. Apache is permissive, but the
  point of concern isn't the license — it's that we want the protocol layer to be genuinely
  **ours**, derived from our own observation, not a relabel of someone else's source.
- **Clean-room fix:** capture the protocol on the wire from a connection we're a party to
  (our own app, our own bike), write a **facts-only spec** (bytes/offsets/sequence — these
  are not copyrightable), then **reimplement `dash/` from that spec**.
- **What actually makes the code clean:** the from-spec *rewrite*, not the capture. Capturing
  packets does not retroactively launder code you already have. The capture is what *enables*
  the rewrite; the rewrite is the defensible act.
- Solo-dev reality: the textbook two-team wall (one reads, one writes) is impractical. The
  principle we CAN honor: build the implementation from our own observation record, document
  the derivation trail, and don't transcribe OpenDash. Keep a dated observation record
  (`/captures/<date>/SPEC.md`) as the backing artifact.

### 2. Anti-circumvention (the RSA auth handshake) — the SHARPER risk
- The dash uses an RSA auth handshake (DashAuth). If a court viewed that as a technological
  access control, **bypassing** it is a *different legal theory than copyright* — clean-room
  does nothing for it.
- **Our mitigating position:** it is our own bike and we hold the credentials (WiFi password
  `12345678`). We **authenticate** with legitimate access; we do not crack or circumvent.
  Reimplementing an auth mechanism you can legitimately complete is materially safer than
  defeating one you cannot. Be able to say truthfully: "we never circumvented — we
  authenticated to our own device."

### 3. Trademark / contract
- **Trademark:** never ship "Royal Enfield", "Tripper", "K1G", or imply endorsement.
  Cheap to avoid, so avoid it entirely. (Internal code names in a private repo are lower
  risk, but public-facing strings must be clean.)
- **Contract / ToS:** the RE app's terms are a separate axis from copyright. Not analyzed
  here; another reason distribution is the line to be careful about.

## On "why did OpenDash get taken down?"
Unknown to this project — do not guess or rely on assumptions. General principle only:
"used their protocol" alone is rarely the killable thing; **distributing** code + branding
is what typically draws a complaint. Our private/personal posture sidesteps the usual trigger.

## The clean-room capture, specifically
- The RE app negotiates **WPA3/SAE**, which is undecryptable via password (see
  `PROTOCOL_CAPTURE.md` blocker). So we **capture our OWN app** instead — it pins WPA2-PSK,
  is fully decryptable, and is entirely a connection we own and control. This is arguably a
  *cleaner* observation source than sniffing the vendor app: our own traffic, our own device.
- Keep raw captures + derived specs as **dated, self-contained observation records** under
  `/captures/` (gitignored — never in the public repo). Timestamp them, note provenance
  ("captured from own device/app, own credentials"). This is the artifact that backs the
  clean-room story if it ever matters.

## Practical rules of thumb
1. Repo is **public** — keep personal data (VIN, telemetry, captures, credentials) OUT of it
   (already gitignored: `raw-dumps/`, `/captures`, `.so` libs, APKs).
2. No RE trademarks in any user-facing / distributed string.
3. Don't distribute builds. Personal use only, for now.
4. Build `dash/` from the observation spec; document the derivation; don't transcribe OpenDash.
5. Revisit ALL of the above (with real counsel) before any public release.
