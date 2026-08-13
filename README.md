# LiveTrackSuunto

Privacy-first live tracking for Suunto athletes. Telemetry streams **peer-to-peer
over WebRTC** and is **end-to-end encrypted**, so no third-party server — ours
included — can read an athlete's position.

Built strictly on **official Suunto APIs** (Cloud/Partner API, Webhooks,
SuuntoPlus SDK) plus official iOS/Android/Bluetooth-SIG platform APIs. No private
endpoints, no reverse-engineered protocols, no unsanctioned BLE workarounds.

## How it works

Suunto exposes no real-time off-watch telemetry and its watches have no WAN
radio, so live position **cannot** come from the watch through any official API
(see the [Phase 1 & 2 audit](docs/PHASE-1-2-Feasibility-and-Architecture.md)).
The compliant architecture that follows:

- **The phone is the Live Relay** — position from CoreLocation / FusedLocationProvider.
- **The watch contributes heart rate** via "Broadcast HR" (recent models), or a
  standard BLE strap does.
- **The synced FIT file is ground truth** — the live stream is a best-effort
  preview, reconciled against the authoritative workout after `WORKOUT_CREATED`.
- **The edge only brokers signaling** — and even on the fan-out fallback it
  relays ciphertext it cannot decrypt.

```
Watch ─BLE HR→ ┌─────────┐ ═══ WebRTC DataChannel (E2EE) ═══> ┌────────┐
Strap ─BLE───→ │ Phone   │                                     │ Viewer │
Phone GPS ───→ │ Emitter │ ←── WSS signaling ──> [ CF Worker + Durable Object ]
               └─────────┘                        (ephemeral · zero retention)
```

## Documentation

| Phase | Document |
|---|---|
| 1 & 2 — API feasibility audit & verdicts | [docs/PHASE-1-2-Feasibility-and-Architecture.md](docs/PHASE-1-2-Feasibility-and-Architecture.md) |
| 3 — Detailed architecture | [docs/phase3/](docs/phase3/README.md) |
| 3.1 — Protocol & payload schemas | [01](docs/phase3/01-protocol-and-payload-schemas.md) |
| 3.2 — Sequence / architecture / C4 diagrams | [02](docs/phase3/02-architecture-and-diagrams.md) |
| 3.3 — GPX snap & dynamic ETA | [03](docs/phase3/03-gpx-snap-and-eta-algorithm.md) |
| 3.4 — Project structure, security & roadmap | [04](docs/phase3/04-project-structure-and-roadmap.md) |

## Repository

```
packages/protocol/   wire schema (telemetry.proto), codec, AES-GCM frame E2EE
packages/geo/        GPX preprocessing, route snapping, grade-adjusted ETA
docs/                feasibility audit + architecture specification
```

Planned (see the [roadmap](docs/phase3/04-project-structure-and-roadmap.md#3-execution-roadmap-infrastructure--deploy)):
`apps/signaling` (Workers + Durable Objects), `apps/mobile` (KMP), `apps/web`
(Next.js + MapLibre), `suuntoplus-app` (on-watch display).

## Development

Requires **Node 22+** and **pnpm 10+**.

```sh
pnpm install
pnpm codegen     # generate protobuf bindings from telemetry.proto
pnpm typecheck
pnpm test
```

## Status

| Phase | State |
|---|---|
| P0 — Monorepo foundations, CI | ✅ done |
| P1 — Protocol package (schema, codec, E2EE, golden vectors) | ✅ done |
| P2 — Edge signaling (Worker + Durable Object) | next |
| P3 — WebRTC core + transport fallback | planned |
| P4 — Mobile relay (KMP) | planned |
| P5 — Web viewer | planned |
| P6 — Suunto integration (OAuth, routes, FIT reconciliation) | planned |
| P7 — Hardening, load test, deploy | planned |
