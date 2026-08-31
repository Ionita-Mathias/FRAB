# Phase 3.1 — Protocol & Payload Schemas

## 1. Transport decision: Protocol Buffers (primary) + CBOR (schemaless fallback)

The DataChannel telemetry frame carries ~15 numeric fields at ~1 Hz, must round-trip
identically across **TypeScript (browser), Swift (iOS), Kotlin (Android)**, and must
evolve safely for years. Decision matrix:

| Criterion | JSON | **Protobuf (proto3)** | CBOR | MessagePack |
|---|---|---|---|---|
| Wire size (typical frame) | 394 B † | **83 B †** | ~110–140 B (int keys) | ~115–145 B |
| Cross-language codegen | n/a | **ts-proto / swift-protobuf / wire** | hand-mapped | hand-mapped |
| Schema evolution | ad-hoc | **field numbers (robust)** | manual | manual |
| Self-describing | ✓ | ✗ (needs schema) | ✓ | ✓ |
| Browser decoder weight | 0 | ~13 KB (ts-proto runtime) | ~4 KB | ~5 KB |
| Deterministic bytes | ✗ | ✓ | ✓ | ✓ |
| Debuggability | ★★★ | ★ (binary) | ★★ | ★★ |

† Measured, not estimated — `packages/protocol/test/codec.test.ts` asserts these as regression
guards. Actual encoded sizes for the reference fixtures:

| Frame | Protobuf | Sealed (+29 B framing) | At 1 Hz |
|---|---|---|---|
| Minimal (position + HR) | **48 B** | 77 B | ~271 KB/h |
| Typical (+ cadence, power, pace) | **83 B** | 112 B | ~394 KB/h |
| Full (+ route progress) | **~129 B** | ~158 B | ~555 KB/h |

The same typical payload as JSON is 394 B, so protobuf is **~4.7× smaller**. CBOR/MessagePack
figures are estimates (they carry the same scaled integers but pay per-field key overhead).

**Verdict: Protocol Buffers.** The single `.proto` guaranteeing byte-for-byte
consistency across three languages is decisive for a small team, and it produces the
smallest frames (varint + scaled integers + delta encoding). **CBOR is retained as a
schemaless fallback** for the SuuntoPlus/tooling side and for debug builds — it needs
no codegen and is trivially decodable everywhere. **Signaling messages (SDP/ICE) stay
JSON**, matching every WebRTC/Workers convention and keeping the DO simple.

Canonical schema: [`packages/protocol/telemetry.proto`](../../packages/protocol/telemetry.proto).
Domain model: [`packages/protocol/src/types.ts`](../../packages/protocol/src/types.ts).

### 1.1 Encoding rules (wire ergonomics)
- **Position** as `sint32` degrees × 1e7 (zigzag) → ~1.1 cm resolution, fits int32 (180 × 1e7 = 1.8e9 < 2.147e9).
- **Timestamps** as `t_ms` = ms since `SessionHello.t0_epoch_ms` (small varint, not a 13-digit epoch).
- **Optional physiology**: absent HR/cadence/power are simply omitted (proto3 zero-value = "unknown").
- **Route polyline** in `RouteMeta` is delta-encoded and `packed` → a 3,000-point route ≈ a few KB, sent once.
- **Sequence number** `seq` is monotonic; a gap tells the viewer packets were lost (UDP DataChannel is loss-tolerant).

### 1.2 The on-wire frame (post-encryption)
Frames are **application-layer encrypted** so the same bytes work over direct P2P *and*
the DO fan-out relay (see [04 §Security](./04-project-structure-and-roadmap.md#7-security-model)):

```
┌────────┬────────────────────────────┬──────────────────────────────────────┐
│ ver(1) │ nonce(12) = salt(4)‖ctr(8) │ AES-256-GCM( protobuf Envelope ) +tag(16) │
└────────┴────────────────────────────┴──────────────────────────────────────┘
```

**The nonce is deterministic, not random** — a 4-byte per-session random salt concatenated
with a 64-bit big-endian frame counter. This was chosen over a random 96-bit nonce because:

1. Nonce reuse becomes *structurally* impossible within a session (a random nonce carries a
   birthday risk across a multi-hour 1 Hz stream, and GCM fails catastrophically on reuse).
2. The receiver reads the counter straight off the wire and can reject replays and duplicates
   **before** spending any crypto work.

**AAD = `ver ‖ sessionId`** (16 raw UUID bytes), binding every frame to its protocol version
and session so ciphertext cannot be spliced from one session into another. Ordering/replay is
enforced by the counter via a 64-frame sliding window (`FrameOpener`), which tolerates the
out-of-order delivery that is normal on an unordered DataChannel.

Reference implementation: [`packages/protocol/src/crypto.ts`](../../packages/protocol/src/crypto.ts).

### 1.3 CBOR fallback map (integer keys)
When schemaless CBOR is used, `TelemetryFrame` maps to a CBOR map with integer keys
equal to the proto field numbers (`{1:seq, 2:t_ms, 3:lat_e7, …}`), so the two encodings
are trivially inter-convertible and the field-number contract stays single-sourced.

---

## 2. Live-metrics data structures

The wire types (generated from `.proto`) use scaled ints; the **domain model** below is
what UI code consumes. `codec.ts` maps between them. TS is the reference; Swift/Kotlin
mirror it field-for-field.

### 2.1 TypeScript (viewer + shared) — excerpt
Full file: [`packages/protocol/src/types.ts`](../../packages/protocol/src/types.ts).

```ts
export interface LiveMetrics {
  seq: number;
  timestamp: number;              // epoch ms (t0 + t_ms)
  position: GeoPoint;             // { lat, lon, altM, hAccM|null }
  speedMps: number | null;
  courseDeg: number | null;
  heartRateBpm: number | null;
  cadenceRpm: number | null;      // spm (run) | rpm (bike)
  powerW: number | null;
  paceSecPerKm: number | null;
  elevationGainM: number;
  elevationLossM: number;
  route: RouteProgress | null;    // null when no GPX loaded
  batteryPct: number | null;
  sources: TelemetrySources;      // provenance per metric
}

export interface RouteProgress {
  distanceDoneM: number;
  distanceRemainingM: number;
  ascentRemainingM: number;
  descentRemainingM: number;
  etaEpochMs: number | null;      // null off-route/unknown
  etaRemainingSec: number | null;
  fraction: number;               // 0..1
  segIndex: number;
  offRoute: boolean;
  crossTrackM: number;
}
```

### 2.2 Swift (iOS emitter) — domain mirror
```swift
public struct GeoPoint: Codable, Sendable {
    public let lat: Double
    public let lon: Double
    public let altM: Double
    public let hAccM: Double?          // nil = unknown
}

public struct RouteProgress: Codable, Sendable {
    public let distanceDoneM: Double
    public let distanceRemainingM: Double
    public let ascentRemainingM: Double
    public let descentRemainingM: Double
    public let etaEpochMs: Int64?
    public let etaRemainingSec: Int?
    public let fraction: Double
    public let segIndex: Int
    public let offRoute: Bool
    public let crossTrackM: Double
}

public enum Source: String, Codable, Sendable {
    case unknown, phoneGPS = "phone-gps", watchBroadcast = "watch-broadcast"
    case bleSensor = "ble-sensor", healthKit = "healthkit"
    case healthServices = "health-services", derived
}

public struct TelemetrySources: Codable, Sendable {
    public let position: Source
    public let heartRate: Source
    public let cadence: Source
    public let power: Source
}

public struct LiveMetrics: Codable, Sendable {
    public let seq: UInt32
    public let timestamp: Int64        // epoch ms
    public let position: GeoPoint
    public let speedMps: Double?
    public let courseDeg: Double?
    public let heartRateBpm: Int?
    public let cadenceRpm: Double?
    public let powerW: Int?
    public let paceSecPerKm: Double?
    public let elevationGainM: Double
    public let elevationLossM: Double
    public let route: RouteProgress?
    public let batteryPct: Int?
    public let sources: TelemetrySources
}
```

### 2.3 Kotlin (Android emitter / KMP shared) — domain mirror
```kotlin
enum class Source { UNKNOWN, PHONE_GPS, WATCH_BROADCAST, BLE_SENSOR, HEALTHKIT, HEALTH_SERVICES, DERIVED }

data class GeoPoint(
    val lat: Double,
    val lon: Double,
    val altM: Double,
    val hAccM: Double?,               // null = unknown
)

data class RouteProgress(
    val distanceDoneM: Double,
    val distanceRemainingM: Double,
    val ascentRemainingM: Double,
    val descentRemainingM: Double,
    val etaEpochMs: Long?,
    val etaRemainingSec: Int?,
    val fraction: Double,
    val segIndex: Int,
    val offRoute: Boolean,
    val crossTrackM: Double,
)

data class TelemetrySources(
    val position: Source,
    val heartRate: Source,
    val cadence: Source,
    val power: Source,
)

data class LiveMetrics(
    val seq: UInt,
    val timestamp: Long,              // epoch ms
    val position: GeoPoint,
    val speedMps: Double?,
    val courseDeg: Double?,
    val heartRateBpm: Int?,
    val cadenceRpm: Double?,
    val powerW: Int?,
    val paceSecPerKm: Double?,
    val elevationGainM: Double,
    val elevationLossM: Double,
    val route: RouteProgress?,
    val batteryPct: Int?,
    val sources: TelemetrySources,
)
```

### 2.4 Signaling messages (JSON, over the DO WebSocket)
```ts
type SignalRole = 'publisher' | 'subscriber';

type SignalMessage =
  | { t: 'join';  role: SignalRole; token: string }
  | { t: 'peer-joined'; peerId: string }
  | { t: 'peer-left';   peerId: string }
  | { t: 'offer';  peerId: string; sdp: string }
  | { t: 'answer'; peerId: string; sdp: string }
  | { t: 'ice';    peerId: string; candidate: RTCIceCandidateInit }
  | { t: 'state';  transport: TransportMode; viewerCount: number }
  | { t: 'fallback'; mode: 'turn' | 'edge-fanout' }
  | { t: 'error'; code: string; message: string };
```

---

## 3. Field-level provenance
Every frame carries `SourceFlags`, so the viewer can label each metric by where it came
from — critical given the Phase 1/2 finding that **live position is phone-sourced** and
**HR may be watch-broadcast or a BLE strap**. Example: a runner on a Broadcast-HR watch
with no cadence pod → `position: phone-gps`, `heartRate: watch-broadcast`,
`cadence: unknown`, `power: unknown`. The UI greys out unavailable metrics rather than
showing stale zeros.
