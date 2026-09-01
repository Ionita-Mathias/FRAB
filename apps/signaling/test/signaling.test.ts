/**
 * Integration tests for the signaling edge, running inside workerd.
 *
 * Note on opening WebSockets here: `new WebSocket("ws://...")` inside a test
 * reaches the REAL network, not the Worker, and a bare global `fetch` with an
 * Upgrade header returns `webSocket: null`. The only path that works is
 * `SELF.fetch(...)` followed by `response.webSocket` + `ws.accept()` — the
 * client half arrives unaccepted and is silently inert until accepted.
 */
import { SELF } from 'cloudflare:test';
import { beforeEach, describe, expect, it } from 'vitest';
import { BASE_PROTO, TOKEN_PROTO_PREFIX } from '../src/session-do.js';

const ORIGIN = 'https://signaling.test';

interface CreatedSession {
  sessionId: string;
  emitterToken: string;
  viewerToken: string;
  expiresAtMs: number;
  signalingPath: string;
  subprotocol: string;
}

/**
 * Session creation is rate limited per client IP. Tests therefore each present
 * a distinct IP, exactly as distinct real clients would — otherwise the whole
 * suite shares one bucket and starts 429-ing partway through. The limiter
 * itself is exercised deliberately in its own test below, with a fixed IP.
 */
let ipCounter = 0;
function uniqueIp(): string {
  ipCounter += 1;
  return `203.0.113.${ipCounter % 250}:${ipCounter}`;
}

async function rawCreate(body: unknown, ip: string): Promise<Response> {
  return SELF.fetch(`${ORIGIN}/v1/sessions`, {
    method: 'POST',
    body: typeof body === 'string' ? body : JSON.stringify(body),
    headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': ip },
  });
}

async function createSession(body: unknown = {}): Promise<CreatedSession> {
  const res = await rawCreate(body, uniqueIp());
  expect(res.status).toBe(201);
  return (await res.json()) as CreatedSession;
}

/** Open an authenticated signaling socket and return the accepted client half. */
async function connect(
  sessionId: string,
  token: string,
): Promise<{ ws: WebSocket; res: Response }> {
  const res = await SELF.fetch(`${ORIGIN}/v1/sessions/${sessionId}/ws`, {
    headers: {
      Upgrade: 'websocket',
      'Sec-WebSocket-Protocol': `${BASE_PROTO}, ${TOKEN_PROTO_PREFIX}${token}`,
    },
  });
  const ws = res.webSocket;
  if (!ws) throw new Error(`no webSocket on response (status ${res.status})`);
  ws.accept();
  return { ws, res };
}

/** Collect the next message matching `pred`. Register BEFORE sending. */
function next<T = any>(ws: WebSocket, pred: (m: any) => boolean, timeoutMs = 2000): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timed out waiting for message')), timeoutMs);
    const onMessage = (event: MessageEvent) => {
      const msg = JSON.parse(event.data as string);
      if (!pred(msg)) return;
      clearTimeout(timer);
      ws.removeEventListener('message', onMessage as EventListener);
      resolve(msg as T);
    };
    ws.addEventListener('message', onMessage as EventListener);
  });
}

describe('session lifecycle', () => {
  it('creates a session with two distinct 256-bit tokens', async () => {
    const s = await createSession();
    expect(s.sessionId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
    expect(s.emitterToken).not.toBe(s.viewerToken);
    // base64url of 32 bytes, unpadded
    for (const t of [s.emitterToken, s.viewerToken]) {
      expect(t).toMatch(/^[A-Za-z0-9_-]{43}$/);
    }
    expect(s.subprotocol).toBe(BASE_PROTO);
    expect(s.expiresAtMs).toBeGreaterThan(Date.now());
  });

  it('clamps a silly TTL into range', async () => {
    const tiny = await createSession({ ttlSec: 1 });
    expect(tiny.expiresAtMs - Date.now()).toBeGreaterThanOrEqual(59_000);

    const huge = await createSession({ ttlSec: 999_999_999 });
    expect(huge.expiresAtMs - Date.now()).toBeLessThanOrEqual(24 * 3600 * 1000 + 1000);
  });

  it('describes a session without leaking secrets', async () => {
    const s = await createSession();
    const res = await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}`);
    expect(res.status).toBe(200);
    const body = (await res.json()) as Record<string, unknown>;
    expect(body['sessionId']).toBe(s.sessionId);
    expect(body['viewerCount']).toBe(0);
    expect(body['emitterLive']).toBe(false);
    const serialized = JSON.stringify(body);
    expect(serialized).not.toContain(s.emitterToken);
    expect(serialized).not.toContain(s.viewerToken);
    expect(serialized.toLowerCase()).not.toContain('hash');
  });

  it('404s unknown and malformed session ids alike', async () => {
    expect((await SELF.fetch(`${ORIGIN}/v1/sessions/not-a-uuid`)).status).toBe(404);
    const res = await SELF.fetch(`${ORIGIN}/v1/sessions/3f2504e0-4f89-41d3-9a0c-0305e82c3301`);
    expect(res.status).toBe(404);
  });

  it('rejects a non-JSON body', async () => {
    const res = await rawCreate('{oops', uniqueIp());
    expect(res.status).toBe(400);
  });

  it('rate-limits session creation per IP and returns a usable Retry-After', async () => {
    const ip = '198.51.100.42'; // one fixed client, deliberately hammering
    let sawLimit = false;

    for (let i = 0; i < 15; i++) {
      const res = await rawCreate({}, ip);
      if (res.status === 429) {
        sawLimit = true;
        const retryAfter = Number(res.headers.get('Retry-After'));
        // The native per-colo binding cannot produce this header at all; the
        // DO limiter can, and it must be a sane positive number of seconds.
        expect(Number.isFinite(retryAfter)).toBe(true);
        expect(retryAfter).toBeGreaterThan(0);
        break;
      }
      expect(res.status).toBe(201);
    }
    expect(sawLimit).toBe(true);

    // A different client is unaffected — the bucket is per key, not global.
    expect((await rawCreate({}, uniqueIp())).status).toBe(201);
  });

  it('serves health and 404s unknown routes', async () => {
    expect((await SELF.fetch(`${ORIGIN}/health`)).status).toBe(200);
    expect((await SELF.fetch(`${ORIGIN}/nope`)).status).toBe(404);
  });
});

describe('websocket authentication', () => {
  it('accepts the emitter and echoes exactly the base subprotocol', async () => {
    const s = await createSession();
    const { ws, res } = await connect(s.sessionId, s.emitterToken);
    expect(res.status).toBe(101);
    // Must echo one offered token, and never the secret.
    expect(res.headers.get('Sec-WebSocket-Protocol')).toBe(BASE_PROTO);
    expect(res.headers.get('Sec-WebSocket-Protocol')).not.toContain(s.emitterToken);

    const welcome = await next(ws, (m) => m.t === 'welcome');
    expect(welcome.role).toBe('emitter');
    ws.close();
  });

  it('assigns the viewer role to the viewer token', async () => {
    const s = await createSession();
    const { ws } = await connect(s.sessionId, s.viewerToken);
    const welcome = await next(ws, (m) => m.t === 'welcome');
    expect(welcome.role).toBe('viewer');
    ws.close();
  });

  it('rejects a wrong, empty or foreign token', async () => {
    const s = await createSession();
    const other = await createSession();

    for (const token of ['', 'not-a-real-token', other.emitterToken]) {
      const res = await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}/ws`, {
        headers: {
          Upgrade: 'websocket',
          'Sec-WebSocket-Protocol': `${BASE_PROTO}, ${TOKEN_PROTO_PREFIX}${token}`,
        },
      });
      expect(res.status).toBe(401);
      expect(res.webSocket).toBeFalsy();
    }
  });

  it('rejects an upgrade with no subprotocol at all', async () => {
    const s = await createSession();
    const res = await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}/ws`, {
      headers: { Upgrade: 'websocket' },
    });
    expect(res.status).toBe(401);
  });

  it('426s a plain GET to the websocket route', async () => {
    const s = await createSession();
    const res = await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}/ws`);
    expect(res.status).toBe(426);
  });

  it('refuses a second emitter rather than hijacking the feed', async () => {
    const s = await createSession();
    const { ws } = await connect(s.sessionId, s.emitterToken);
    await next(ws, (m) => m.t === 'welcome');

    const res = await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}/ws`, {
      headers: {
        Upgrade: 'websocket',
        'Sec-WebSocket-Protocol': `${BASE_PROTO}, ${TOKEN_PROTO_PREFIX}${s.emitterToken}`,
      },
    });
    expect(res.status).toBe(409);
    ws.close();
  });
});

describe('presence and signaling relay', () => {
  it('EXIT CRITERION: two peers exchange offer/answer/ICE through the DO', async () => {
    const s = await createSession();

    const { ws: emitter } = await connect(s.sessionId, s.emitterToken);
    const emitterWelcome = await next(emitter, (m) => m.t === 'welcome');
    const emitterId: string = emitterWelcome.peerId;

    // Emitter must learn about the viewer joining.
    const joined = next(emitter, (m) => m.t === 'peer-joined');
    const { ws: viewer } = await connect(s.sessionId, s.viewerToken);
    const viewerWelcome = await next(viewer, (m) => m.t === 'welcome');
    const viewerId: string = viewerWelcome.peerId;

    const joinEvent = await joined;
    expect(joinEvent.peerId).toBe(viewerId);
    expect(joinEvent.role).toBe('viewer');
    // The viewer sees the emitter already present.
    expect(viewerWelcome.peers).toEqual([{ peerId: emitterId, role: 'emitter' }]);

    // offer: emitter -> viewer
    const offerAtViewer = next(viewer, (m) => m.t === 'offer');
    emitter.send(JSON.stringify({ t: 'offer', to: viewerId, sdp: 'v=0 fake-offer' }));
    const offer = await offerAtViewer;
    expect(offer.from).toBe(emitterId);
    expect(offer.sdp).toBe('v=0 fake-offer');

    // answer: viewer -> emitter
    const answerAtEmitter = next(emitter, (m) => m.t === 'answer');
    viewer.send(JSON.stringify({ t: 'answer', to: emitterId, sdp: 'v=0 fake-answer' }));
    const answer = await answerAtEmitter;
    expect(answer.from).toBe(viewerId);
    expect(answer.sdp).toBe('v=0 fake-answer');

    // trickled ICE, both directions
    const iceAtViewer = next(viewer, (m) => m.t === 'ice');
    emitter.send(
      JSON.stringify({ t: 'ice', to: viewerId, candidate: { candidate: 'candidate:1 udp', sdpMLineIndex: 0 } }),
    );
    const ice = await iceAtViewer;
    expect(ice.from).toBe(emitterId);
    expect(ice.candidate.candidate).toBe('candidate:1 udp');

    const iceAtEmitter = next(emitter, (m) => m.t === 'ice');
    viewer.send(JSON.stringify({ t: 'ice', to: emitterId, candidate: { candidate: 'candidate:2 tcp' } }));
    expect((await iceAtEmitter).candidate.candidate).toBe('candidate:2 tcp');

    emitter.close();
    viewer.close();
  });

  it('reports viewer count and transport in state broadcasts', async () => {
    const s = await createSession();
    const { ws: emitter } = await connect(s.sessionId, s.emitterToken);
    await next(emitter, (m) => m.t === 'welcome');

    const stateAfterJoin = next(emitter, (m) => m.t === 'state' && m.viewerCount === 1);
    const { ws: viewer } = await connect(s.sessionId, s.viewerToken);
    await next(viewer, (m) => m.t === 'welcome');
    const state = await stateAfterJoin;
    expect(state.emitterLive).toBe(true);

    const transportSeen = next(viewer, (m) => m.t === 'state' && m.transport === 'edge-fanout');
    emitter.send(JSON.stringify({ t: 'transport', mode: 'edge-fanout' }));
    expect((await transportSeen).transport).toBe('edge-fanout');

    emitter.close();
    viewer.close();
  });

  it('tells viewers when the emitter leaves', async () => {
    const s = await createSession();
    const { ws: emitter } = await connect(s.sessionId, s.emitterToken);
    await next(emitter, (m) => m.t === 'welcome');
    const { ws: viewer } = await connect(s.sessionId, s.viewerToken);
    await next(viewer, (m) => m.t === 'welcome');

    const closing = next(viewer, (m) => m.t === 'closing');
    emitter.close();
    expect((await closing).reason).toBe('emitter-left');
    viewer.close();
  });
});

describe('signaling authorization and validation', () => {
  it('stops a viewer from signaling another viewer', async () => {
    const s = await createSession();
    const { ws: emitter } = await connect(s.sessionId, s.emitterToken);
    await next(emitter, (m) => m.t === 'welcome');

    const { ws: v1 } = await connect(s.sessionId, s.viewerToken);
    await next(v1, (m) => m.t === 'welcome');
    const { ws: v2 } = await connect(s.sessionId, s.viewerToken);
    const w2 = await next(v2, (m) => m.t === 'welcome');

    const err = next(v1, (m) => m.t === 'error');
    v1.send(JSON.stringify({ t: 'offer', to: w2.peerId, sdp: 'v=0 sneaky' }));
    expect((await err).code).toBe('not_permitted');

    emitter.close();
    v1.close();
    v2.close();
  });

  it('lets only the emitter set the transport mode', async () => {
    const s = await createSession();
    const { ws: viewer } = await connect(s.sessionId, s.viewerToken);
    await next(viewer, (m) => m.t === 'welcome');

    const err = next(viewer, (m) => m.t === 'error');
    viewer.send(JSON.stringify({ t: 'transport', mode: 'p2p-direct' }));
    expect((await err).code).toBe('not_permitted');
    viewer.close();
  });

  it('rejects malformed frames, unknown types and unknown peers', async () => {
    const s = await createSession();
    const { ws } = await connect(s.sessionId, s.emitterToken);
    await next(ws, (m) => m.t === 'welcome');

    const cases: Array<[string, string]> = [
      ['{not json', 'bad_message'],
      [JSON.stringify({ t: 'nonsense' }), 'bad_message'],
      [JSON.stringify({ t: 'offer', sdp: 'x' }), 'bad_message'],
      [JSON.stringify({ t: 'offer', to: 'p', sdp: 123 }), 'bad_message'],
      [JSON.stringify({ t: 'transport', mode: 'carrier-pigeon' }), 'bad_message'],
      [JSON.stringify({ t: 'offer', to: 'ghost-peer', sdp: 'v=0' }), 'unknown_peer'],
    ];

    for (const [payload, code] of cases) {
      const err = next(ws, (m) => m.t === 'error');
      ws.send(payload);
      expect((await err).code).toBe(code);
    }
    ws.close();
  });

  it('rejects an oversized SDP', async () => {
    const s = await createSession();
    const { ws } = await connect(s.sessionId, s.emitterToken);
    await next(ws, (m) => m.t === 'welcome');

    const err = next(ws, (m) => m.t === 'error');
    ws.send(JSON.stringify({ t: 'offer', to: 'p', sdp: 'x'.repeat(65 * 1024) }));
    expect((await err).code).toBe('payload_too_large');
    ws.close();
  });

  it('rate-limits a message flood without dropping the connection', async () => {
    const s = await createSession();
    const { ws } = await connect(s.sessionId, s.emitterToken);
    await next(ws, (m) => m.t === 'welcome');

    const limited = next(ws, (m) => m.t === 'error' && m.code === 'rate_limited', 5000);
    for (let i = 0; i < 120; i++) {
      ws.send(JSON.stringify({ t: 'offer', to: 'ghost', sdp: 'v=0' }));
    }
    expect((await limited).code).toBe('rate_limited');
    ws.close();
  });
});

describe('revocation', () => {
  it('requires the emitter token and then closes every socket', async () => {
    const s = await createSession();
    const { ws: emitter } = await connect(s.sessionId, s.emitterToken);
    await next(emitter, (m) => m.t === 'welcome');
    const { ws: viewer } = await connect(s.sessionId, s.viewerToken);
    await next(viewer, (m) => m.t === 'welcome');

    // No credential, and the viewer's credential, are both refused.
    expect((await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}`, { method: 'DELETE' })).status).toBe(401);
    const asViewer = await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${s.viewerToken}` },
    });
    expect(asViewer.status).toBe(403);

    const closing = next(viewer, (m) => m.t === 'closing');
    const revoked = await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${s.emitterToken}` },
    });
    expect(revoked.status).toBe(204);
    expect((await closing).reason).toBe('revoked');

    // The session is gone, and its credentials no longer open a socket.
    expect((await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}`)).status).toBe(404);
    const reconnect = await SELF.fetch(`${ORIGIN}/v1/sessions/${s.sessionId}/ws`, {
      headers: {
        Upgrade: 'websocket',
        'Sec-WebSocket-Protocol': `${BASE_PROTO}, ${TOKEN_PROTO_PREFIX}${s.emitterToken}`,
      },
    });
    expect(reconnect.status).toBe(404);
  });
});

describe('webhook sink', () => {
  const SECRET = 'test-webhook-secret';
  const BODY = JSON.stringify({ type: 'WORKOUT_CREATED', username: 'u', workout: { workoutKey: 'k' } });

  async function sign(body: string, secret: string): Promise<string> {
    const key = await crypto.subtle.importKey(
      'raw',
      new TextEncoder().encode(secret),
      { name: 'HMAC', hash: 'SHA-256' },
      false,
      ['sign'],
    );
    const sig = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(body));
    return Array.from(new Uint8Array(sig), (b) => b.toString(16).padStart(2, '0')).join('');
  }

  let signature = '';
  beforeEach(async () => {
    signature = await sign(BODY, SECRET);
  });

  it('accepts a correctly signed webhook', async () => {
    const res = await SELF.fetch(`${ORIGIN}/v1/webhooks/suunto`, {
      method: 'POST',
      body: BODY,
      headers: { 'X-HMAC-SHA256-Signature': signature },
    });
    expect(res.status).toBe(200);
  });

  it('accepts the sha256= prefixed form', async () => {
    const res = await SELF.fetch(`${ORIGIN}/v1/webhooks/suunto`, {
      method: 'POST',
      body: BODY,
      headers: { 'X-HMAC-SHA256-Signature': `sha256=${signature}` },
    });
    expect(res.status).toBe(200);
  });

  it('rejects a missing, malformed or wrong signature', async () => {
    const bad = [undefined, 'zzzz', 'a'.repeat(64), await sign(BODY, 'wrong-secret')];
    for (const sig of bad) {
      const res = await SELF.fetch(`${ORIGIN}/v1/webhooks/suunto`, {
        method: 'POST',
        body: BODY,
        headers: sig ? { 'X-HMAC-SHA256-Signature': sig } : {},
      });
      expect(res.status).toBe(401);
    }
  });

  it('rejects a tampered body under a valid-looking signature', async () => {
    const res = await SELF.fetch(`${ORIGIN}/v1/webhooks/suunto`, {
      method: 'POST',
      body: `${BODY} tampered`,
      headers: { 'X-HMAC-SHA256-Signature': signature },
    });
    expect(res.status).toBe(401);
  });
});
