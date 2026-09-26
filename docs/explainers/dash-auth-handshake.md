# Explainer: The Dash Auth Handshake (RSA → AES session key)

*An "understand-to-participate" explainer. Background first, intuition before code, then a
literate walk through the actual change. Quiz at the bottom — don't claim you understand this
until you can pass it.*

Covers: `DashAuth.kt`, the auth path in `DashSession.kt`, and the `q3c.e`/`q3c.d`
commands in `DashCommands.kt`.

---

## 0. Background — what existed before this code

The Tripper Dash is a motorcycle instrument cluster that talks over WiFi UDP. Before any
useful data flows, the phone and the dash have to agree on a **shared secret** so that:

1. The dash trusts that *this* phone is allowed to drive it (auth), and
2. Later traffic (the `0F` telemetry packets — trip/odo/fuel/speed) can be **encrypted** so it
   isn't readable by anyone else sniffing the WiFi.

There is no username/password. Instead the dash owns an **RSA key pair** and hands out its
**public** half. The phone's job is to invent a fresh **AES-256** key and hand it to the dash
*secretly*, using that public key. After that, both sides share the AES key and use it for the
rest of the session. This is the same shape as a TLS handshake: **slow asymmetric crypto is used
once, only to deliver a fast symmetric key.**

Two protocol facts that shape the code:
- The dash sends its public key as **two separate TLVs** — the **modulus** (`07 00`) and the
  **exponent** (`07 03`) — and they can arrive in **different packets**.
- The dash replies `07 01 01` to accept the key, or `07 01 <not 01>` to reject it.

("TLV" = Type-Length-Value: every message on this wire is a type byte, a sub-type, a length, and
a value. `07` is the auth type.)

---

## 1. Intuition — the whole handshake in six steps

```
phone                                   dash
  │  ── initial burst (incl. q3c.e) ──▶   │   "here's who I am; send me your key"
  │                                       │
  │  ◀── 07 00  (RSA modulus) ───────────  │   pubkey, half 1
  │  ◀── 07 03  (RSA exponent) ──────────  │   pubkey, half 2  (maybe a different packet)
  │                                       │
  │  ── q3c.d: RSA_encrypt(ssid‖AESkey) ▶  │   "here's a secret only your private key can open"
  │                                       │
  │  ◀── 07 01 01  (confirmed) ──────────  │   "accepted — we now share the AES key"
  │                                       │
  │  ◀── 0F …  (AES-encrypted telemetry) ─ │   decrypt with the shared AES key
```

The tricky part isn't the crypto — Java's `Cipher` does that. The tricky part is that the two
pubkey halves **arrive across separate packets and possibly out of order**, so the code has to
*accumulate state across calls* and fire the response **exactly once**. That's why the design is
a **state machine**, not a straight-line function.

---

## 2. The three layers (who does what)

| Layer | File | Responsibility |
|---|---|---|
| **Sequencing** | `DashSession.runSession` / `dispatchIncoming` | Opens sockets, sends the burst, feeds each incoming `07` TLV to the state machine, waits (with a 15s deadline) for confirmation, handles retries. |
| **State machine** | `DashAuth` | Pure logic: accumulate modulus+exponent, build the key packet once, expose the AES `sessionKey`, re-arm on rejection. No I/O. |
| **Wire format** | `DashCommands` | The literal bytes: `authRequest()` (`q3c.e`) and `authSendKey()` (`q3c.d`, which asserts the RSA ciphertext is exactly 128 B). |

The clean separation is the point: `DashAuth` has **no sockets and no coroutines**, so it's
trivially testable and can't accidentally send twice. All the timing/retry lives one layer up.

---

## 3. Literate walk — `DashAuth.ingest()`

This is the heart. One incoming TLV goes in; an `AuthEvent` comes out telling the session what to
do. Read it in this order:

```kotlin
fun ingest(tlv: Tlv): AuthEvent {
    if (tlv.type != 0x07) return AuthEvent.None      // (a) not auth traffic — ignore
    when (tlv.sub) {
        0x00 -> modulus  = BigInteger(1, tlv.value)  // (b) accumulate half 1
        0x03 -> exponent = BigInteger(1, tlv.value)  // (b) accumulate half 2
        0x01 -> return if (tlv.value.firstOrNull() == 0x01.toByte())
            AuthEvent.Confirmed else AuthEvent.Rejected   // (c) dash's verdict
        else -> return AuthEvent.None
    }

    val m = modulus; val e = exponent
    if (!keySent && m != null && e != null) {        // (d) both halves in, not yet sent
        keySent = true                               //     latch so we send ONCE
        return AuthEvent.SendKey(buildKeyPacket(m, e))
    }
    return AuthEvent.None                            // (e) have one half, still waiting
}
```

- **(a)** Anything that isn't type `0x07` isn't this machine's business.
- **(b)** `BigInteger(1, ...)` — the leading `1` forces a **positive** number. RSA moduli are
  big positive integers; without the sign flag a high bit would be read as negative and the key
  would be wrong. (This is a classic, easy-to-miss bug.)
- **(c)** The verdict branch. `07 01 01` = confirmed; anything else = rejected.
- **(d)** The gate: **both** halves present **and** we haven't already sent. `keySent` is the
  latch that guarantees we emit the key packet exactly once even though `ingest` runs on every
  packet.
- **(e)** If only one half has arrived, we return `None` and wait for the next packet — this is
  the "accumulate across calls" behavior.

### `buildKeyPacket` — where the secret is created

```kotlin
private fun buildKeyPacket(modulus: BigInteger, exponent: BigInteger): ByteArray {
    val aes = ByteArray(32).also { SecureRandom().nextBytes(it) }   // (f) fresh AES-256 key
    sessionKey = aes                                                //     keep it for decryption later

    val payload = ssid.toByteArray(Charsets.UTF_8) + aes            // (g) ssid ‖ AES key
    val pubKey = KeyFactory.getInstance("RSA")
        .generatePublic(RSAPublicKeySpec(modulus, exponent))        // (h) rebuild dash's public key
    val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
    cipher.init(Cipher.ENCRYPT_MODE, pubKey)
    return DashCommands.authSendKey(cipher.doFinal(payload))        // (i) encrypt → wrap as q3c.d
}
```

- **(f)** 32 bytes = 256 bits, from `SecureRandom` (cryptographically strong, not `Random`).
- **(g)** We send the SSID alongside the key so the dash can bind the session to this network.
- **(h)** Reconstruct the dash's public key from the two halves we accumulated.
- **(i)** Encrypt with the **public** key → only the dash's **private** key can open it. Then wrap
  in the `q3c.d` frame, which requires the ciphertext to be exactly **128 B** (RSA-1024).

### `reset()` — why rejection needs re-arming

```kotlin
fun reset() { modulus = null; exponent = null; keySent = false }
```

On `Rejected`, the session calls `reset()` and re-sends `authRequest()`. Without clearing
`keySent`, the latch would stay `true` and we'd **never send a key again** — the retry would be
silent and auth would hang until the 15s timeout. `reset()` puts the machine back to "waiting for
pubkey."

---

## 4. How the session layer drives it (`DashSession.dispatchIncoming`)

```kotlin
if (tlv.type == 0x07) {
    when (val ev = auth?.ingest(tlv)) {
        is AuthEvent.SendKey -> sock.send(ev.packet)          // both halves arrived → send q3c.d
        AuthEvent.Confirmed  -> { authConfirmed = true }      // unblocks the waiter in runSession
        AuthEvent.Rejected   -> {
            authRetries++
            auth?.reset()
            if (authRetries <= 5) sock.send(DashCommands.authRequest())   // bounded retry
        }
        else -> {}
    }
    continue
}
```

And the waiter in `runSession`:

```kotlin
val deadline = System.currentTimeMillis() + AUTH_TIMEOUT   // 15_000 ms
while (!authConfirmed && System.currentTimeMillis() < deadline) delay(100)
if (!authConfirmed) { fail("Auth timed out — no 07 01 01 …"); return }
```

Two things worth noticing:
- **The RX loop must be running *before* the initial burst is sent** (see the comment in
  `runSession`: *"RX loop MUST be running before the burst (early pubkey + no ICMP)"*). If we sent
  the burst first, the dash's pubkey reply could arrive before we're listening and be dropped —
  and, worse, a closed port would make the OS emit ICMP "port unreachable," which can upset the
  dash. Order matters.
- **Retries are bounded to 5.** Unbounded retry on a rejecting dash would be a send storm.

---

## 5. The payoff — the session key is *used*, not just created

The whole reason to bother: once `sessionKey` exists, the `0F` telemetry packets get decrypted
in `DashSession`:

```kotlin
if (tlv.type == 0x0F) {
    val key = auth?.sessionKey
    val plain = key?.let { aesDecryptCbc(tlv.value, it) }   // IV = first 16 bytes, then AES-256-CBC
    ...
}
```

So the handshake isn't ceremony — the AES key it produces is what turns opaque ciphertext into
readable instrument data. **Asymmetric bootstrapped symmetric; symmetric carries the session.**
That single sentence is the whole design.

---

## 6. The map to interview language (why this matters for the SE assessment)

- "I implemented an **RSA→AES handshake** by hand — asymmetric crypto delivering a symmetric
  session key, which is exactly how TLS works."
- "The hard part wasn't the crypto, it was **state across packets**: the public key arrives in
  two halves in separate packets, so I built a **state machine** with a **send-once latch** and a
  **reset path** for rejection."
- "I separated **pure logic (`DashAuth`) from I/O (`DashSession`)** so the auth logic is testable
  and can't double-send."
- "Auth has a **15s deadline** and **bounded (≤5) retries** — no infinite hang, no send storm."

---

## QUIZ — pass this before you claim you understand it

1. Why is the AES key delivered using the dash's **public** key rather than just sent in the
   clear or with a password? What property of RSA makes this safe over open WiFi?
2. The public key arrives as `07 00` and `07 03` in *separate* packets. What in `ingest()`
   guarantees the key packet is sent **exactly once**, and what would break if that guard were
   missing?
3. What is the purpose of the leading `1` in `BigInteger(1, tlv.value)`? What bug appears if you
   drop it?
4. On a `Rejected` verdict, why must `reset()` clear `keySent` (not just the modulus/exponent)?
   Trace what happens on the retry if it didn't.
5. Why does the code insist the RX loop is running *before* the initial burst is sent? Name the
   two distinct failure modes that ordering prevents.
6. `sessionKey` is set inside `buildKeyPacket`. Where and how is it later *used*, and what would
   the symptom be if the handshake succeeded but the key were somehow wrong?
7. (Design) `DashAuth` has no sockets and no coroutines. Give two concrete benefits of keeping
   the auth logic pure and pushing timing/retry up into `DashSession`.
