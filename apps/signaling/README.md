# @livetrace/signaling

Ephemeral WebRTC signaling edge on Cloudflare Workers + Durable Objects.

Mints session credentials, brokers the WebRTC handshake, and gets out of the
way. **Telemetry never transits this Worker in readable form** — it goes
peer-to-peer, or as opaque ciphertext on the fan-out fallback.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `POST` | `/v1/sessions` | — (rate limited per IP) | Create a session, mint both tokens |
| `GET` | `/v1/sessions/:id` | — | Public status; never returns secrets |
| `DELETE` | `/v1/sessions/:id` | `Bearer <emitterToken>` | Revoke; closes every socket |
| `GET` | `/v1/sessions/:id/ws` | subprotocol | WebSocket upgrade |
| `POST` | `/v1/webhooks/suunto` | HMAC-SHA256 | Post-workout sink |
| `GET` | `/health` | — | Liveness |

## WebSocket authentication

Browsers **cannot** set headers on a WebSocket upgrade — there is no
`Authorization` at any layer. A token in the query string would work but leaks
into access logs and referrers, so the token travels in `Sec-WebSocket-Protocol`:

```js
new WebSocket(`wss://…/v1/sessions/${id}/ws`, [
  'livetrace.v1',
  `livetrace.tok.${token}`,
]);
```

The server echoes back **exactly one** offered token — `livetrace.v1`, never the
secret. Omitting the echo makes Chrome fail the handshake outright; echoing more
than one, or one the client never offered, violates RFC 6455. Tokens are
unpadded base64url precisely because workerd rejects `=` in a subprotocol token.

The Durable Object name is derived from the validated path UUID, never from a
client-supplied room name — otherwise any credential holder could address
another session's object.

## Message protocol

Client → server: `offer` / `answer` / `ice` (each addressed `to` a peer),
`transport`, `bye`.
Server → client: `welcome`, `peer-joined`, `peer-left`, `offer`, `answer`,
`ice`, `state`, `closing`, `error`.

Authorization rules enforced server-side:
- one emitter per session — a second attempt gets `409`, not a silent takeover;
- viewers may signal **only** the emitter, so the hub cannot be used as a
  viewer-to-viewer side channel;
- only the emitter may set the transport mode.

## Design notes

**Hibernation, not `accept()`.** The DO uses `ctx.acceptWebSocket()` with
`webSocketMessage`/`webSocketClose` handlers. `ws.accept()` pins the object in
memory and bills duration for the entire connection lifetime — roughly a 7×
difference on Cloudflare's own worked example. Consequences that shape the code:

- in-memory state does not survive hibernation, so per-socket identity lives in
  `serializeAttachment()` and the peer list comes from `ctx.getWebSockets()`;
- `setInterval`/`setTimeout` would permanently prevent hibernation, so session
  expiry uses the **Alarms API**;
- `getWebSockets()` can return sockets already in `CLOSING`, and `send()` on a
  dead socket throws — which would abort a fan-out loop midway. Every send is
  guarded on `readyState` and wrapped.

**Rate limiting is a Durable Object, not the native binding.** The native
`ratelimit` binding is enforced *per Cloudflare location* (300+ colos), is a
wall-clock fixed window (so a caller can push 2× the limit across a boundary),
and returns only `{ success }` — no `Retry-After` can be built from it. A DO
gives one global counter, smooth bursts, and a real retry-after.

**Constant-time comparison** uses `crypto.subtle.timingSafeEqual` — a workerd
extension needing no compatibility flag. It is *not* on the global `crypto`, it
throws if destructured, and it throws on unequal-length inputs. Both sides are
therefore always fixed-size SHA-256 digests; an early length check would itself
leak the secret's length through timing.

**Only token hashes are persisted**, so a storage dump yields no usable
credential. No signaling payload is ever written to storage.

## Configuration

`wrangler.jsonc` uses the legacy `migrations` array rather than the newer
declarative `exports` map. That is deliberate: the `migrations` → `exports`
transition is **one-way**, the two are mutually exclusive, and `exports` support
in the test harness is unverified. P2 only needs local + CI testing, so we stay
on the reversible path and revisit at deploy time (P7).

`compatibility_date` is `2026-08-04`, at which point `nodejs_compat` is on by
default — so `compatibility_flags` is deliberately absent rather than redundant.

The webhook secret is a **secret**, not a var: `wrangler secret put WEBHOOK_SECRET`.
Tests inject a fake one through the Vitest plugin instead.

## Development

```sh
pnpm dev         # wrangler dev
pnpm test        # vitest inside workerd
pnpm typecheck
```

Tests run in real workerd via `@cloudflare/vitest-plugin`. Note for future
edits: the pre-2026 API (`defineWorkersConfig`, `poolOptions.workers`,
`isolatedStorage`, `singleWorker`, the `/config` import subpath) does **not**
work on the current plugin — `cloudflareTest()` is a plain Vite plugin.

Opening a WebSocket in a test only works via `SELF.fetch(...)` →
`response.webSocket` → `ws.accept()`. A bare `new WebSocket()` reaches the real
network instead of the Worker.
