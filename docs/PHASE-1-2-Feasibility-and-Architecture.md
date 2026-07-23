# LiveTrackSuunto — Phase 1 & 2: API Feasibility Audit + High‑Level Architecture

**Document status:** Phases 1 & 2 complete. **Stops here, awaiting validation before Phase 3 (Architecture detail) and Phase 4 (Implementation).**
**Compliance posture:** 100% official Suunto APIs/SDKs + official iOS/Android/Bluetooth‑SIG platform APIs. **Zero private APIs, zero reverse‑engineering, zero un‑sanctioned BLE hacks.**
**Author role:** Principal Engineer / Architect / Suunto Ecosystem Specialist.

---

## 0. Sourcing & Confidence — read this first (integrity note)

Every capability claim below was gathered by a multi‑agent research sweep across the official Suunto developer surface (`apizone.suunto.com`, `cloudapi.suunto.com`, `suunto.com`, the Azure API‑Management mirror), then **adversarially re‑verified** by independent "try‑to‑refute" agents, and finally reconciled by a completeness critic.

**Honest limitation:** during research, outbound fetches to *every* `*.suunto.com` host returned `403` at the network gateway, so the Suunto‑specific facts were extracted from **search‑engine summaries of the official pages, not verbatim page fetches**, and cross‑checked against two independent open‑source Suunto integrations plus reputable industry reporting (DC Rainmaker, the5krunner). Platform facts (Apple / Google / Bluetooth SIG) **were** fetched verbatim.

Consequently:

| Confidence | Meaning | Applies to |
|---|---|---|
| **High** | Verbatim‑fetched official docs, or corroborated across ≥2 independent sources | iOS/Android/BLE platform APIs; OAuth2 flow shape; post‑sync‑only model |
| **Medium** | Official page content via search summary, single‑sourced | Exact Suunto endpoint path spellings; webhook body fields; SuuntoPlus internals |
| **Low / `?`** | Forum/blog only, or genuine absence‑of‑evidence | The Athletica live‑relay mechanism; broadcast‑HR coexistence; GATT UUID mapping |

**Nothing Suunto‑specific here should be treated as final until re‑verified with live ApiZone developer credentials.** A concrete verification checklist is in §6.

---

# PHASE 1 — Audit & API Feasibility Matrix

## 1.1 Required‑feature matrix

Legend: ✓ Supported · ⚠ Limited · ✗ Impossible (officially) · ? Undocumented

| Feature | Support Status | Official API / SDK Reference | Official Alternative if Restricted |
|---|---|---|---|
| **Real‑time GPS Streaming from Watch** | ✗ Impossible *(officially, off‑watch)* | None. Watch has no WAN radio; Cloud API is post‑sync only; no companion BLE API; watch does **not** advertise the BLE Location & Navigation service (0x1819). | **Capture GPS on the companion phone** — iOS `CLLocationManager` live updates / Android `FusedLocationProviderClient.requestLocationUpdates()`. Phone is the live‑position source. |
| **Real‑time HR / Cadence / Power Streaming** | ⚠ Limited *(HR only, recent models)* | **"Broadcast heart rate"** (≈April 2026 firmware; Race / Race S / Race 2 / Vertical 2 — Vertical gen‑1 & Run unconfirmed) makes the watch a standard BLE **Heart‑Rate** peripheral. **Cadence & power are NOT broadcast.** | Phone subscribes directly to **standard BLE sensors**: HR `0x180D`, Running SC `0x1814`, Cycling SC `0x1816`, Cycling Power `0x1818`. On‑watch SuuntoPlus app can also read these and store to FIT (post‑sync). |
| **Active Route / GPX Extraction in Real‑time** | ✗ Real‑time impossible · ✓ Planned route yes | **Route API**: `GET /v2/route` (list), `GET /v2/route/{id}/export` with `Accept: application/gpx+xml`; `ROUTE_CREATED` webhook. This returns the **planned** route only. Watch computes distance‑left/ETA/off‑route **on‑device**, not exposed via API. | Pull the **planned GPX** via Route API (or user upload), then **recompute live progress** in our backend by snapping live phone‑GPS to the route polyline (see Phase 2 Q4). |
| **Bidirectional Watch‑Phone Communication** | ? Undocumented / ✗ for 3rd parties | The watch↔Suunto‑app BLE link is **proprietary & undocumented**. SuuntoPlus apps read **inbound** external BLE sensors; no documented **outbound** companion‑messaging API. "Broadcast HR" is one‑way (watch → any collector). | No official arbitrary‑data channel. Use **phone‑side sensors + standard BLE**; use the on‑watch SuuntoPlus app only for **display/controls**, not as a data uplink. |
| **Live Webhooks during workout** | ✗ Impossible | All webhooks are `*_CREATED`, fired **after sync**: `WORKOUT_CREATED`, `ROUTE_CREATED`, `SUUNTO_247_ACTIVITY/SLEEP/RECOVERY_CREATED`. No in‑progress/live‑position webhook exists. | None from Suunto. **Our own backend** ingests the live stream from our companion app (edge WebSocket/SSE). |
| **Post‑Workout Sync (FIT/JSON)** | ✓ Supported | **Workout API v2**: `GET /v2/workouts` (JSON summary), `export-user-workout-in-fit` (full FIT), workout image; delivered/announced via `WORKOUT_CREATED` webhook (HMAC‑signed, `X‑HMAC‑SHA256‑Signature`). OAuth2 (`cloudapi-oauth.suunto.com`) + `Ocp-Apim-Subscription-Key`. | This is the **authoritative ground‑truth** path; no alternative needed. |

## 1.2 Crucial requirement — does a native watch SDK allow real‑time background streaming to a companion app?

**Verdict: No officially‑documented capability exists.** *(Confidence: medium‑high on "no documented API"; the one shipping counter‑example is undocumented and its official status is `?`.)*

- **SuuntoPlus Sports Apps** (JavaScript `main.js` + HTML/CSS UI + `manifest.json`; driven by `onLoad()` / `evaluate()` callbacks) run **on the watch during the activity**. They can read live sources — GPS, HR, altitude, ascent/descent, vertical speed, speed/pace, distance, duration, cadence, power, temperature, swim strokes, energy, plus navigation/environment — including from **external BLE sensors the watch connects to (inbound)**.
- Their outputs are written to the workout **FIT file as 32‑bit‑float "developer fields"** (session = Summary Outputs, record = per‑sample Channels) and are retrievable **only after sync**, via the Cloud API.
- **No documented networking** exists in the sandbox — no HTTP/fetch, no socket, no companion‑phone messaging API. A Suunto representative on the official forum described an indirect "app fetches via the Suunto app over Bluetooth" idea as a *future* possibility with unresolved security/sandbox issues — **explicitly not available today**.
- The only shipping "live" example (the third‑party **Athletica "Live.τ"** app) pairs an on‑watch app with a **separate companion phone app** that relays the watch's GPS to a server over BLE. This path is **undocumented**, **competes for the watch's single live BLE link** (reported 60–90 s disconnect/reconnect cycles when the Suunto app is also connected), and its "official vs workaround" status could not be confirmed. **We will not build on it.**

**Therefore the mandated hybrid architecture is:** the **companion mobile app is the Live Relay** — it captures telemetry from the **phone's own GPS** (authoritative live position) and **heart rate from Broadcast‑HR or a standard BLE strap**, and streams it to our backend. The watch is a **display / navigation / optional HR‑source** device, and the **Suunto Cloud FIT is the post‑hoc source of truth** for reconciliation. This keeps the product **100% within official APIs**.

## 1.3 Supporting reference tables

**A. Suunto Cloud / Partner API (post‑workout).** *(confidence: high on shape, medium on exact path strings)*

| Concern | Detail |
|---|---|
| Portal / hosts | Portal `apizone.suunto.com`; data `https://cloudapi.suunto.com`; OAuth `https://cloudapi-oauth.suunto.com` (Azure API Management) |
| Auth | OAuth2 **authorization‑code**; `GET /oauth/authorize`, `POST /oauth/token` (HTTP Basic `client_id:client_secret`); scope **`workout`**; JWT access token (`expires_in` 86400 s) carrying a custom **`user`** claim; long‑lived refresh token |
| Required headers | `Authorization: Bearer <jwt>` **and** `Ocp-Apim-Subscription-Key: <apim key>` on every `cloudapi.suunto.com` call |
| Workouts | `GET /v2/workouts` → JSON summary (`startTime`/`stopTime` epoch‑ms, `timeOffsetInMinutes`, `workoutKey`); full data via FIT export (`export-user-workout-in-fit`, keyed by `workoutKey`) |
| FIT contents | GPS track (≤1 pt/s), HR (1 s or 10 s), R‑R, power, cadence, altitude, temperature, laps, sessions, SuuntoPlus developer fields. **FIT is the only workout data format** (no JSON samples, no workout‑GPX/TCX from the API) |
| Routes | `GET /v2/route`; `GET /v2/route/{id}/export` (`Accept: application/gpx+xml`); GPX import with `activities` query param; **polling prohibited — use `ROUTE_CREATED` webhook** |
| Guides | SuuntoPlus **Guide** (data, not code): `guide.zip` = Guide JSON + Manifest JSON + PNG, uploaded to `POST /v2/guides/files`; structured‑workout/targets, not map routes |
| Webhooks | POST to a URL configured in the ApiZone app settings; JSON body `{type, username, workout:{workoutKey,…}}`; verified via `X‑HMAC‑SHA256‑Signature` (HMAC‑SHA256 of raw body w/ notification secret); ack **2XX within ~2 s**; ret/backoff + circuit breaker |
| Rate limits | Dev tier stricter than prod; **exact numbers unpublished** (expect `429`) |
| Access gate | Commercial‑partner oriented; register app + subscribe to a product for the APIM key |

**B. Real‑time capture — official platform APIs (companion phone).** *(confidence: high, verbatim‑fetched)*

| Platform | Live GPS | Live BLE sensors | Live workout/HR |
|---|---|---|---|
| **iOS** | `CLLocationManager.startUpdatingLocation()` / `CLLocationUpdate` (iOS 2.0+) | `CBCentralManager` + `CBPeripheral.setNotifyValue(_:for:)` → `didUpdateValueFor` (iOS 5.0+) | `HKWorkoutSession` + `HKLiveWorkoutBuilder` — **now on iPhone as of iOS 26.0** (was watchOS‑only) |
| **Android** | `FusedLocationProviderClient.requestLocationUpdates(LocationRequest, LocationCallback)` | `connectGatt()` → `discoverServices()` → `setCharacteristicNotification()` + CCCD → `onCharacteristicChanged()` | `ExerciseClient` (~1 Hz) is **Wear OS**, not phone; Health Connect is a **datastore, not a live stream** |

**C. Standard Bluetooth‑SIG GATT services a phone may collect.** *(confidence: high)*
Heart Rate `0x180D` (measurement `0x2A37`) · Running Speed & Cadence `0x1814` · Cycling Speed & Cadence `0x1816` · Cycling Power `0x1818`. These are what the **phone reads from straps/pods** (and, for HR only, from a Broadcast‑HR watch).

---

# PHASE 2 — Feasibility & Technical Answers

### Q1 — Can a SuuntoPlus Sports App stream real‑time data to a mobile app during an active session via official APIs?
**Verdict: NO — not via any documented official API.** *(status: no documented capability; a single undocumented workaround exists → treat as `?` and do not rely on it)*
SuuntoPlus apps run on‑watch, read live sensors, render on the watch, and persist outputs to **FIT developer fields retrievable only post‑sync** through the Cloud API. The runtime exposes **no HTTP/socket/companion‑messaging** API. The only live off‑watch example (Athletica Live.τ) uses an **undocumented** watch→phone BLE path that fights for the single BLE link and whose sanctioned status is unconfirmed — **excluded on both compliance and reliability grounds**.

### Q2 — Does an official companion BLE data‑streaming API exist for 3rd‑party developers?
**Verdict: NO (confirmed).** *(confidence: medium — every Suunto page was gateway‑blocked, corroborated via search + industry sources)*
Suunto's developer surface is exactly two things: the **post‑sync Cloud API** and the **on‑watch SuuntoPlus SDK**. Neither is a phone‑side BLE streaming API, and the watch↔phone link is proprietary. **Narrow exception:** the 2026 **"Broadcast heart rate"** feature exposes **HR only** over the *standard* Bluetooth HR profile — a consumer interoperability feature, **not a developer companion SDK**, and it does **not** cover GPS, cadence, power, or navigation.

### Q3 — What exact metrics can be read in real time (mobile app) vs post‑workout (Cloud API)?

| Metric | Real‑time (companion app, official) | Post‑workout (Cloud API, FIT/JSON) |
|---|---|---|
| GPS position / speed / altitude | ✓ **phone GPS** (CoreLocation / Fused) | ✓ full GPS track (≤1 pt/s) |
| Pace / distance / elevation gain | ✓ derived on phone from GPS | ✓ from FIT |
| Heart rate | ⚠ Broadcast‑HR watch (recent models) **or** BLE strap `0x180D` | ✓ HR (1 s/10 s) + R‑R |
| Cadence | ⚠ **only** via external BLE `0x1814`/`0x1816` (not the watch) | ✓ from FIT |
| Power | ⚠ **only** via external BLE `0x1818` (not the watch) | ✓ from FIT |
| Route progress (dist‑left, ETA, off‑route) | ✗ not exposed → **we compute it** | ✗ not a field → derive from track + route |
| SuuntoPlus custom outputs | ✗ not live | ✓ FIT developer fields |

**One‑line rule:** *Live position comes from the phone; live HR can come from the watch (recent models) or a strap; cadence/power come only from external BLE sensors; everything authoritative arrives post‑sync in the FIT.*

### Q4 — How do we dynamically track a user along a pre‑loaded GPX route (ETA, remaining distance/elevation)?
Suunto exposes no live route‑progress, so we compute it ourselves:
1. **Get the planned route as GPX** — Suunto **Route API** `GET /v2/route/{id}/export` (`Accept: application/gpx+xml`) or user upload.
2. **Pre‑process once** — resample to a dense polyline; build a **cumulative‑distance array** and a **cumulative‑ascent/descent** profile from the route's elevation.
3. **Per live fix** — **snap** the phone GPS point to the nearest route segment (perpendicular projection) → `distanceDone`; `remainingDistance = totalLength − distanceDone`; `remainingAscent/Descent` from the profile between the snapped point and the end.
4. **Dynamic ETA** — `ETA = now + remainingDistance / v̄`, where `v̄` is a moving‑average speed (rolling window), optionally **grade‑adjusted** (e.g. a Naismith/GAP‑style correction using `remainingAscent`) for hilly routes.
5. **Off‑route** — flag when snap distance exceeds a threshold (e.g. > 30–50 m for N consecutive fixes); suppress ETA while off‑route (mirrors the watch's own behavior).
All of this runs in the edge/Durable Object (or client), against the phone‑GPS live track — **no watch data required**.

---

# PHASE 3 (preview) — High‑Level Architecture Recommendation

*Full protocol trade‑off, schemas, and detailed component design are deferred to Phase 3 proper. This is the topology that bridges the Suunto constraints above.*

## The core constraint that shapes everything
**Suunto exposes no official real‑time off‑watch telemetry, and the watch has no WAN radio.** So the only officially‑sanctioned live path sources **position from the phone** and **HR from Broadcast‑HR/strap**; the watch contributes at most HR. Live view = best‑effort preview; **FIT = ground truth.**

## Topology

```mermaid
flowchart LR
  subgraph Athlete
    W["Suunto watch<br/>(display/nav; optional Broadcast-HR)"]
    S["BLE strap / pod<br/>HR·RSC·CSC·Power (optional)"]
    P["Companion App = LIVE RELAY<br/>iOS SwiftUI / Android Compose (KMP)<br/>phone GPS + BLE + workout session"]
  end
  W -. "BLE HR only (0x180D)" .-> P
  S -. "standard BLE GATT" .-> P
  P -- "uplink: CBOR over WSS<br/>(UUID session + 256-bit token)" --> DO

  subgraph Cloudflare Edge — zero retention
    DO["Durable Object<br/>1 per live session<br/>ephemeral state · route-progress calc · fan-out"]
    WK["Workers<br/>token mint · rate limit · webhook sink"]
  end
  DO -- "downlink: JSON/CBOR over WSS or SSE" --> V["Viewers (Next.js PWA)<br/>MapLibre GL + PMTiles<br/>dual-trace map + live metrics card"]

  SC["Suunto Cloud API<br/>OAuth2 · WORKOUT_CREATED webhook · FIT export"] -- "post-workout (HMAC-verified)" --> WK
  WK -- "authoritative FIT → reconcile live track" --> DO
```

## Component responsibilities
- **Companion app (Live Relay).** Foreground/background workout session (`HKWorkoutSession`+`HKLiveWorkoutBuilder` on iOS 26+ / Android foreground service `location|health`). Captures phone GPS + BLE sensors, buffers, and streams. Handles reconnect/offline replay.
- **Cloudflare Workers.** Session bootstrap, **UUIDv4 session id + 256‑bit access token** mint w/ configurable expiry, **rate limiting**, and the **HMAC‑verified** (`X‑HMAC‑SHA256‑Signature`) Suunto webhook sink.
- **Durable Object (one per session).** Holds **ephemeral** live state only (zero‑retention), runs **route‑snap + ETA** (Q4), and **fans out** to viewers over WebSocket/SSE. Discarded at session end.
- **Web frontend (Next.js + MapLibre GL + PMTiles).** **Dual‑trace** map (planned GPX vs realized track, distinct styling) + **live metrics card** (speed, pace, HR, cadence, altitude, elev ±gain, remaining distance, dynamic ETA). PWA/offline‑tolerant.
- **Post‑workout reconciliation.** On `WORKOUT_CREATED`, fetch the FIT, time‑align, and replace the lossy live track with the authoritative one for the saved record.

## Protocol (high‑level lean; decided fully in Phase 3)
- **Uplink (phone → edge):** **CBOR** (or Protobuf) over WSS — compact binary, low battery/bandwidth for 1 Hz telemetry, delta‑encoded.
- **Downlink (edge → browser):** **JSON over WSS** by default (simplest, debuggable) with a CBOR option if viewer bandwidth matters.
- Phase 3 will produce the formal **JSON vs Protobuf vs CBOR vs MessagePack** matrix (payload size, encode/decode cost, battery, browser‑decoder weight) and lock the choice.

## Security & privacy (per requirements)
Session **UUIDv4**; **256‑bit** bearer tokens with configurable expiry; **rate limiting** at the Worker; **HMAC‑SHA256** webhook verification; **zero data retention** (DO state is ephemeral, dropped at session end; FIT persisted only if the user opts in); TLS everywhere; least‑privilege OAuth scope (`workout`).

## Compliance stance vs Live.τ
Live.τ appears to source **watch GPS via an undocumented BLE relay** (battery‑friendly but non‑compliant/fragile). Our design deliberately sources **live position from the phone** — the trade‑off is higher **phone** battery use, bought in exchange for **full official‑API compliance, Partner‑Program publishability, and firmware‑change resilience**. This is the "comparable‑or‑superior **and** compliant" position the brief demands.

---

## 6. Open items to verify with live ApiZone credentials (de‑risking checklist)
1. **Broadcast‑HR coexistence** — can a phone read Broadcast‑HR **while** the Suunto app holds its BLE link? (load‑bearing for the standards path) `?`
2. Exact **model list** for Broadcast‑HR (Vertical gen‑1? Run?) `?`
3. Whether the watch advertises **any** GATT beyond HR (CSC/RSC/LNS/power) — currently "no" by absence of evidence `?`
4. Broadcast‑HR **GATT UUID** mapping (assumed `0x180D`/`0x2A37`) `?`
5. FIT export **exact path/param** spelling (`export-user-workout-in-fit`) `?`
6. Webhook **latency**, retry counts, circuit‑breaker thresholds, and numeric **rate limits** `?`
7. Whether a SuuntoPlus app can read the watch's **navigation‑engine** values as an input resource `?`
8. **Partner Program** cost, review SLA, and whether a live‑tracking product qualifies for access `?`
9. Legal/ToS review of *not* using the undocumented watch→phone relay (confirming our compliant path).

## 7. Primary sources
Official (via search summary unless noted): `apizone.suunto.com/{how-to-start, how-to-workout-upload, fit-description, webhooks, faq, route-description, suuntoplus-sports-apps, suuntoplusEditor, suuntoplus-guide-description}`; `suunto.com` support & Suunto‑7 Wear‑OS pages; `suunto.com/partners/welcome-partners`. Platform (verbatim): Apple `developer.apple.com` (HealthKit / Core Location / Core Bluetooth), Google `developer.android.com` (Location / Bluetooth / Health Services / Health Connect), Bluetooth SIG `bluetooth.com/specifications`. Corroboration: open‑source `quantified-self`, `suunto-mcp`; DC Rainmaker; the5krunner. Community context (low confidence): `forum.suunto.com`; Athletica Live.τ pages.

---

**➡️ End of Phase 2. Awaiting your validation before proceeding to Phase 3 (detailed architecture, protocol selection, data schemas) and Phase 4 (implementation).**
