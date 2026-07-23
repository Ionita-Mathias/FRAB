# Phase 3 — Detailed Technical Architecture (WebRTC P2P-first)

Architecture locked (post Phase 1/2 approval): **Peer-to-Peer-first via WebRTC
DataChannels**, Cloudflare Workers + Durable Objects as **ephemeral signaling only**,
smart fallback to **TURN** or **edge ciphertext fan-out**, and **end-to-end encryption**
that holds on every transport (app-layer AES-256-GCM keyed by a secret that never reaches
the edge).

| # | Deliverable | Document |
|---|---|---|
| 3.1 | Protocol & payload schemas (Protobuf vs CBOR decision; TS/Swift/Kotlin structures) | [01-protocol-and-payload-schemas.md](./01-protocol-and-payload-schemas.md) |
| 3.2 | Mermaid sequence, architecture & C4 container diagrams | [02-architecture-and-diagrams.md](./02-architecture-and-diagrams.md) |
| 3.3 | GPX route-snap & dynamic grade-adjusted ETA algorithm | [03-gpx-snap-and-eta-algorithm.md](./03-gpx-snap-and-eta-algorithm.md) |
| 3.4 | Monorepo structure, security model & phased roadmap | [04-project-structure-and-roadmap.md](./04-project-structure-and-roadmap.md) |

### Canonical, single-sourced artifacts (referenced by the docs)
- Wire schema: [`packages/protocol/telemetry.proto`](../../packages/protocol/telemetry.proto)
- Domain model: [`packages/protocol/src/types.ts`](../../packages/protocol/src/types.ts)
- Snap + ETA reference impl: [`packages/geo/src/routeSnap.ts`](../../packages/geo/src/routeSnap.ts)

### Design invariants (carried from Phase 1/2)
1. **Live position is phone-sourced**; the watch contributes at most Broadcast-HR. No proprietary BLE relay.
2. **The FIT (post-sync, Cloud API) is ground truth**; the live stream is a best-effort preview reconciled after `WORKOUT_CREATED`.
3. **The edge never sees telemetry plaintext** and stores no telemetry history — even on the fan-out fallback.
