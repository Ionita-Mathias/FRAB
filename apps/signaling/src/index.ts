/**
 * LiveTrace signaling Worker.
 *
 * Ephemeral control plane only: mints session credentials, brokers the WebRTC
 * handshake, and gets out of the way. Telemetry never transits this Worker in
 * a readable form — it is peer-to-peer, or opaque ciphertext on the fan-out
 * fallback.
 *
 * Routes
 *   POST   /v1/sessions              → create a session, mint both tokens
 *   GET    /v1/sessions/:id          → public status (no secrets)
 *   DELETE /v1/sessions/:id          → revoke; closes every socket
 *   GET    /v1/sessions/:id/ws       → WebSocket upgrade (subprotocol auth)
 *   POST   /v1/webhooks/suunto       → HMAC-verified post-workout sink
 *   GET    /health
 */

import { RateLimiterDurableObject } from './ratelimit-do.js';
import { BASE_PROTO, SessionDurableObject, type SessionMeta } from './session-do.js';
import { hashToken, mintToken, verifyHmacSignature } from './tokens.js';

export { RateLimiterDurableObject, SessionDurableObject };

export interface Env {
  SESSION: DurableObjectNamespace<SessionDurableObject>;
  RATE_LIMITER: DurableObjectNamespace<RateLimiterDurableObject>;
  /** Shared secret for inbound partner webhooks. */
  WEBHOOK_SECRET?: string;
  /** Default session lifetime; clamped to MAX_TTL_SEC. */
  SESSION_TTL_SEC?: string;
}

const DEFAULT_TTL_SEC = 6 * 3600; // a long ultra still fits
const MAX_TTL_SEC = 24 * 3600;
const MIN_TTL_SEC = 60;

/** Session creation is the expensive, abusable endpoint. */
const CREATE_BUCKET = { capacity: 10, refillPerSec: 10 / 600 }; // ~10 per 10 min, burst 10

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    try {
      if (path === '/health') return json({ ok: true });

      if (path === '/v1/sessions' && request.method === 'POST') {
        return await createSession(request, env);
      }

      const wsMatch = path.match(/^\/v1\/sessions\/([^/]+)\/ws$/);
      if (wsMatch) return await upgradeWebSocket(request, env, wsMatch[1]!);

      const idMatch = path.match(/^\/v1\/sessions\/([^/]+)$/);
      if (idMatch) {
        const id = idMatch[1]!;
        if (request.method === 'GET') return await describeSession(env, id);
        if (request.method === 'DELETE') return await revokeSession(request, env, id);
        return methodNotAllowed(['GET', 'DELETE']);
      }

      if (path === '/v1/webhooks/suunto' && request.method === 'POST') {
        return await handleWebhook(request, env);
      }

      return problem(404, 'not_found', 'no such route');
    } catch (err) {
      // Never leak internals to the caller.
      console.error('unhandled', err);
      return problem(500, 'internal_error', 'unexpected error');
    }
  },
} satisfies ExportedHandler<Env>;

// ───────────────────────────── handlers ────────────────────────────────────

async function createSession(request: Request, env: Env): Promise<Response> {
  const limited = await enforceRateLimit(request, env);
  if (limited) return limited;

  const body = await readJson(request);
  if (body === undefined) return problem(400, 'bad_request', 'body must be JSON');

  const ttlSec = clampTtl(
    numberOr((body as Record<string, unknown>)['ttlSec'], Number(env.SESSION_TTL_SEC) || DEFAULT_TTL_SEC),
  );

  const sessionId = crypto.randomUUID();
  const emitterToken = mintToken();
  const viewerToken = mintToken();
  const now = Date.now();

  const meta: SessionMeta = {
    sessionId,
    createdAtMs: now,
    expiresAtMs: now + ttlSec * 1000,
    emitterTokenHash: await hashToken(emitterToken),
    viewerTokenHash: await hashToken(viewerToken),
    revoked: false,
  };

  const created = await stub(env, sessionId).init(meta);
  if (!created) return problem(500, 'internal_error', 'session id collision');

  // The only time the plaintext tokens exist outside the caller. Storage holds
  // hashes only, so they cannot be recovered from here on.
  return json(
    {
      sessionId,
      emitterToken,
      viewerToken,
      expiresAtMs: meta.expiresAtMs,
      signalingPath: `/v1/sessions/${sessionId}/ws`,
      subprotocol: BASE_PROTO,
    },
    201,
    { 'Cache-Control': 'no-store' },
  );
}

async function describeSession(env: Env, sessionId: string): Promise<Response> {
  if (!isUuid(sessionId)) return problem(404, 'not_found', 'no such session');
  const info = await stub(env, sessionId).describe();
  if (!info) return problem(404, 'not_found', 'no such session');
  return json(info, 200, { 'Cache-Control': 'no-store' });
}

/**
 * Revocation requires the emitter token — the session owner's credential.
 * Without that check, knowing a session id (which viewers necessarily do)
 * would be enough to kill someone else's broadcast.
 */
async function revokeSession(request: Request, env: Env, sessionId: string): Promise<Response> {
  if (!isUuid(sessionId)) return problem(404, 'not_found', 'no such session');

  const token = bearer(request);
  if (!token) return problem(401, 'unauthorized', 'emitter token required');

  const s = stub(env, sessionId);
  const meta = await s.getMeta();
  if (!meta || meta.revoked) return problem(404, 'not_found', 'no such session');

  const { verifyToken } = await import('./tokens.js');
  if (!(await verifyToken(token, meta.emitterTokenHash))) {
    return problem(403, 'forbidden', 'not the session owner');
  }

  await s.revoke();
  return new Response(null, { status: 204 });
}

async function upgradeWebSocket(request: Request, env: Env, sessionId: string): Promise<Response> {
  // Reject non-upgrades in the Worker so an invalid request never bills a DO.
  if ((request.headers.get('Upgrade') ?? '').toLowerCase() !== 'websocket') {
    return problem(426, 'upgrade_required', 'expected Upgrade: websocket');
  }
  // The DO name is derived from the path and validated as a UUID; it is never
  // taken from a client-supplied room/name field, which would let any holder
  // of one credential address another session's object.
  if (!isUuid(sessionId)) return problem(404, 'not_found', 'no such session');

  return stub(env, sessionId).fetch(request);
}

async function handleWebhook(request: Request, env: Env): Promise<Response> {
  const secret = env.WEBHOOK_SECRET;
  if (!secret) return problem(503, 'not_configured', 'webhook secret unset');

  const signature =
    request.headers.get('X-HMAC-SHA256-Signature') ?? request.headers.get('x-hmac-sha256-signature');
  if (!signature) return problem(401, 'unauthorized', 'missing signature');

  // Signature covers the exact bytes, so verify before parsing.
  const raw = await request.text();
  if (!(await verifyHmacSignature(raw, signature, secret))) {
    return problem(401, 'unauthorized', 'bad signature');
  }

  // Acknowledge immediately; partners expect a fast 2XX and retry otherwise.
  // Post-workout reconciliation (fetching the FIT) lands in P6.
  return json({ ok: true });
}

// ───────────────────────────── helpers ─────────────────────────────────────

function stub(env: Env, sessionId: string): DurableObjectStub<SessionDurableObject> {
  return env.SESSION.get(env.SESSION.idFromName(sessionId));
}

async function enforceRateLimit(request: Request, env: Env): Promise<Response | null> {
  const ip = request.headers.get('CF-Connecting-IP') ?? 'unknown';
  const limiter = env.RATE_LIMITER.get(env.RATE_LIMITER.idFromName(`create:${ip}`));
  const { allowed, retryAfterSec } = await limiter.limit(CREATE_BUCKET);
  if (allowed) return null;
  return problem(429, 'rate_limited', 'too many sessions created', {
    'Retry-After': String(retryAfterSec),
  });
}

function clampTtl(value: number): number {
  if (!Number.isFinite(value)) return DEFAULT_TTL_SEC;
  return Math.min(MAX_TTL_SEC, Math.max(MIN_TTL_SEC, Math.floor(value)));
}

function numberOr(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function isUuid(s: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(s);
}

function bearer(request: Request): string | null {
  const h = request.headers.get('Authorization') ?? '';
  return h.startsWith('Bearer ') ? h.slice(7).trim() || null : null;
}

/** Returns undefined when the body is not valid JSON; {} when empty. */
async function readJson(request: Request): Promise<unknown> {
  const text = await request.text();
  if (!text.trim()) return {};
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

function problem(
  status: number,
  code: string,
  message: string,
  headers: Record<string, string> = {},
): Response {
  return json({ error: code, message }, status, headers);
}

function methodNotAllowed(allow: string[]): Response {
  return problem(405, 'method_not_allowed', 'unsupported method', { Allow: allow.join(', ') });
}
