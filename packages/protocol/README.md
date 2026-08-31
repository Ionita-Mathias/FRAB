# @livetrace/protocol

Canonical wire schema, codec, and end-to-end encryption for LiveTrace
telemetry. Shared by the mobile emitter, the web viewer, and the Cloudflare edge.

## Layout

| Path | Role |
|---|---|
| `telemetry.proto` | **Single source of truth** for the wire format |
| `src/types.ts` | Domain model (human units, explicit `null` for unknown) |
| `src/codec.ts` | `Encoder` / `Decoder` — wire ↔ domain |
| `src/crypto.ts` | `FrameSealer` / `FrameOpener` — AES-256-GCM frame E2EE |
| `src/gen/` | Generated bindings (git-ignored; `pnpm codegen`) |
| `test/golden-vectors.json` | Cross-language conformance contract |

## Usage

```ts
import { Encoder, Decoder, FrameSealer, FrameOpener, generateSessionKey } from '@livetrace/protocol';

// ── emitter ──
const key = await generateSessionKey();          // stays on the device; shared via link #fragment
const sealer = FrameSealer.create(key, sessionId);
const enc = new Encoder(t0EpochMs);
channel.send(await sealer.seal(enc.telemetry(metrics)));

// ── viewer ──
const opener = FrameOpener.create(key, sessionId);
const dec = new Decoder();
const msg = dec.decode(await opener.open(frame));
if (msg.kind === 'telemetry') render(msg.metrics);
```

## Frame format

```
┌────────┬───────────────────────────┬──────────────────────────┐
│ ver(1) │ nonce(12) = salt(4)‖ctr(8)│ AES-256-GCM ct + tag(16) │
└────────┴───────────────────────────┴──────────────────────────┘
```

The nonce is **deterministic** (per-session random salt ‖ 64-bit counter) rather
than random. This makes nonce reuse structurally impossible within a session and
lets the receiver reject replays *before* spending any crypto work. AAD binds
`version ‖ sessionId`, so frames cannot be spliced between sessions.

Because encryption sits **above** the transport, the identical ciphertext travels
over a direct DataChannel, a TURN relay, or the Durable Object fan-out — the edge
relays bytes it cannot read.

## Measured sizes

| Frame | Protobuf | Sealed (+29 B) | At 1 Hz |
|---|---|---|---|
| Minimal (position + HR) | 48 B | 77 B | ~271 KB/h |
| Typical (+ cadence, power, pace) | 83 B | 112 B | ~394 KB/h |
| Full (+ route progress) | ~129 B | ~158 B | ~555 KB/h |

The same typical payload as JSON is **394 B** — protobuf is ~4.7× smaller.

## Golden vectors

`test/golden-vectors.json` holds hex-encoded canonical encodings. TypeScript,
Swift, and Kotlin bindings are produced by three different compilers, so nothing
structurally forces them to agree — these vectors are the contract, asserted by
every language's test suite.

Regenerate **only** for an intentional protocol change:

```sh
UPDATE_GOLDEN=1 pnpm --filter @livetrace/protocol test
```

CI fails if the vectors change without being committed deliberately. A vector
diff without a `protocolVersion` bump is a breaking-change bug.

## Scripts

```sh
pnpm codegen     # regenerate bindings from telemetry.proto
pnpm typecheck
pnpm test
```

`codegen` uses `protobufjs-cli` (pure JS — no `protoc` toolchain needed). The
mobile targets generate from the same `.proto` with `swift-protobuf` / `wire`.
