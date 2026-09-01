/**
 * SessionDurableObject — one instance per live session.
 *
 * Responsibilities: hold session metadata and token hashes, authenticate
 * WebSocket upgrades, track presence, and relay SDP/ICE between the emitter
 * and its viewers. It is deliberately NOT a data store: no signaling payload
 * and no telemetry is ever written to storage.
 *
 * Uses the WebSocket HIBERNATION API (`ctx.acceptWebSocket` +
 * `webSocketMessage`/`webSocketClose`), not `ws.accept()`. With `accept()` the
 * object is pinned in memory and billed for the entire connection lifetime;
 * hibernation lets an idle session cost nothing while keeping sockets open —
 * roughly a 7x difference on Cloudflare's own worked example. Two consequences
 * shape this file:
 *   - in-memory state does not survive hibernation, so per-socket identity
 *     lives in `serializeAttachment` and the socket list comes from
 *     `ctx.getWebSockets()` rather than a Map;
 *   - `setInterval`/`setTimeout` would permanently prevent hibernation, so
 *     expiry uses the Alarms API instead.
 */

import { DurableObject } from 'cloudflare:workers';
import {
  encode,
  parseClientMessage,
  type PeerInfo,
  type ServerMessage,
  type TransportMode,
} from './protocol.js';
import { consume, type BucketState } from './ratelimit.js';
import { verifyToken, type Role } from './tokens.js';

export interface SessionMeta {
  sessionId: string;
  createdAtMs: number;
  expiresAtMs: number;
  emitterTokenHash: string;
  viewerTokenHash: string;
  revoked: boolean;
}

/** Per-socket identity. Kept small — attachments are capped at 16 KiB. */
interface SocketAttachment {
  peerId: string;
  role: Role;
}

/** Close codes. 1000-1003/1007-1011 are valid; 1004/1005/1006/1015 throw. */
const CLOSE_REVOKED = 4001;
const CLOSE_EXPIRED = 4002;
const CLOSE_GOING_AWAY = 1001;

/** Per-socket message budget: generous for negotiation, hostile to floods. */
const MSG_BUCKET = { capacity: 60, refillPerSec: 10 };

export class SessionDurableObject extends DurableObject {
  /**
   * Per-socket message buckets, keyed by peerId.
   *
   * Intentionally in-memory: hibernation clears it, which can only make the
   * limit more lenient — and a hibernating object was by definition receiving
   * no messages. Persisting a counter per message would defeat the point of
   * hibernation for a purely abuse-control signal.
   */
  private buckets = new Map<string, BucketState>();

  /** Last transport mode reported by the emitter, for `state` broadcasts. */
  private transport: TransportMode | null = null;

  // ──────────────────────────── lifecycle ─────────────────────────────────

  /** Create the session. Returns false if it already exists (id collision). */
  async init(meta: SessionMeta): Promise<boolean> {
    const existing = await this.ctx.storage.get<SessionMeta>('meta');
    if (existing) return false;
    await this.ctx.storage.put('meta', meta);
    await this.ctx.storage.setAlarm(meta.expiresAtMs);
    return true;
  }

  async getMeta(): Promise<SessionMeta | undefined> {
    return this.ctx.storage.get<SessionMeta>('meta');
  }

  /** Public view of a session — never leaks token hashes. */
  async describe(): Promise<{ sessionId: string; expiresAtMs: number; viewerCount: number; emitterLive: boolean } | null> {
    const meta = await this.getMeta();
    if (!meta || meta.revoked) return null;
    return {
      sessionId: meta.sessionId,
      expiresAtMs: meta.expiresAtMs,
      viewerCount: this.peers().filter((p) => p.role === 'viewer').length,
      emitterLive: this.peers().some((p) => p.role === 'emitter'),
    };
  }

  /** Revoke: close every socket and drop all state. */
  async revoke(): Promise<boolean> {
    const meta = await this.getMeta();
    if (!meta) return false;
    this.broadcast({ t: 'closing', reason: 'revoked' });
    this.closeAll(CLOSE_REVOKED, 'session revoked');
    await this.ctx.storage.deleteAlarm();
    await this.ctx.storage.deleteAll();
    return true;
  }

  /** TTL reached — same teardown as revocation. */
  async alarm(): Promise<void> {
    this.broadcast({ t: 'closing', reason: 'expired' });
    this.closeAll(CLOSE_EXPIRED, 'session expired');
    await this.ctx.storage.deleteAll();
  }

  // ──────────────────────────── websocket ─────────────────────────────────

  /**
   * Authenticate and accept a WebSocket upgrade.
   *
   * The token arrives via `Sec-WebSocket-Protocol`, not the query string:
   * browsers cannot set headers on a WebSocket upgrade, and a URL token leaks
   * into access logs and referrers. We echo back only the plain `livetrace.v1`
   * token — never the secret — and exactly one value, as RFC 6455 requires.
   */
  async fetch(request: Request): Promise<Response> {
    if ((request.headers.get('Upgrade') ?? '').toLowerCase() !== 'websocket') {
      return new Response('expected Upgrade: websocket', { status: 426 });
    }

    const offered = (request.headers.get('Sec-WebSocket-Protocol') ?? '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean);

    const tokenProto = offered.find((p) => p.startsWith(TOKEN_PROTO_PREFIX));
    const token = tokenProto ? tokenProto.slice(TOKEN_PROTO_PREFIX.length) : '';
    if (!offered.includes(BASE_PROTO) || !token) {
      return new Response('missing signaling subprotocol', { status: 401 });
    }

    const meta = await this.getMeta();
    if (!meta || meta.revoked) return new Response('no such session', { status: 404 });
    if (Date.now() >= meta.expiresAtMs) return new Response('session expired', { status: 410 });

    // Verify against BOTH hashes so the work done is independent of which role
    // (or neither) matched — no early return on the emitter check.
    const isEmitter = await verifyToken(token, meta.emitterTokenHash);
    const isViewer = await verifyToken(token, meta.viewerTokenHash);
    if (!isEmitter && !isViewer) return new Response('unauthorized', { status: 401 });
    const role: Role = isEmitter ? 'emitter' : 'viewer';

    // One emitter per session; a second publish attempt is a conflict, not a
    // silent takeover of someone else's live feed.
    if (role === 'emitter' && this.peers().some((p) => p.role === 'emitter')) {
      return new Response('emitter already connected', { status: 409 });
    }

    const peerId = crypto.randomUUID();
    const [client, server] = Object.values(new WebSocketPair()) as [WebSocket, WebSocket];

    this.ctx.acceptWebSocket(server, [`role:${role}`, `peer:${peerId}`]);
    const attachment: SocketAttachment = { peerId, role };
    server.serializeAttachment(attachment);

    const peersBefore = this.peers().filter((p) => p.peerId !== peerId);
    this.send(server, { t: 'welcome', peerId, role, peers: peersBefore });
    this.broadcast({ t: 'peer-joined', peerId, role }, server);
    this.broadcastState();

    return new Response(null, {
      status: 101,
      webSocket: client,
      // Echo exactly one offered token, and never the secret. Omitting this
      // makes Chrome fail the handshake outright.
      headers: { 'Sec-WebSocket-Protocol': BASE_PROTO },
    });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const me = this.attachmentOf(ws);
    if (!me) return; // socket we do not recognise; ignore rather than trust

    const decision = consume(this.buckets.get(me.peerId), MSG_BUCKET, Date.now());
    this.buckets.set(me.peerId, decision.state);
    if (!decision.allowed) {
      this.send(ws, { t: 'error', code: 'rate_limited', message: 'slow down' });
      return;
    }

    const parsed = parseClientMessage(raw);
    if (!parsed.ok) {
      this.send(ws, { t: 'error', code: parsed.code, message: parsed.message });
      return;
    }
    const msg = parsed.msg;

    if (msg.t === 'bye') {
      ws.close(CLOSE_GOING_AWAY, 'bye');
      return;
    }

    if (msg.t === 'transport') {
      // Only the emitter describes the session's transport.
      if (me.role !== 'emitter') {
        this.send(ws, { t: 'error', code: 'not_permitted', message: 'emitter only' });
        return;
      }
      this.transport = msg.mode;
      this.broadcastState();
      return;
    }

    // offer / answer / ice — routed to exactly one addressed peer.
    const target = this.socketOf(msg.to);
    if (!target) {
      this.send(ws, { t: 'error', code: 'unknown_peer', message: 'no such peer' });
      return;
    }
    // Viewers may only talk to the emitter; without this any viewer could
    // signal any other viewer and use the hub as a side channel.
    const targetAttachment = this.attachmentOf(target);
    if (me.role === 'viewer' && targetAttachment?.role !== 'emitter') {
      this.send(ws, { t: 'error', code: 'not_permitted', message: 'viewers may only signal the emitter' });
      return;
    }

    if (msg.t === 'ice') {
      this.send(target, { t: 'ice', from: me.peerId, candidate: msg.candidate });
    } else {
      this.send(target, { t: msg.t, from: me.peerId, sdp: msg.sdp });
    }
  }

  async webSocketClose(ws: WebSocket): Promise<void> {
    const me = this.attachmentOf(ws);
    if (!me) return;
    this.buckets.delete(me.peerId);
    this.broadcast({ t: 'peer-left', peerId: me.peerId }, ws);

    // The emitter leaving ends the session for everyone — viewers have nothing
    // left to receive, and lingering sockets would keep the object resident.
    if (me.role === 'emitter') {
      this.broadcast({ t: 'closing', reason: 'emitter-left' }, ws);
      this.closeAll(CLOSE_GOING_AWAY, 'emitter left', ws);
      this.transport = null;
      return;
    }
    this.broadcastState(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    await this.webSocketClose(ws);
  }

  // ──────────────────────────── internals ─────────────────────────────────

  private peers(exclude?: WebSocket): PeerInfo[] {
    const out: PeerInfo[] = [];
    for (const ws of this.ctx.getWebSockets()) {
      if (ws === exclude) continue;
      const a = this.attachmentOf(ws);
      if (a) out.push({ peerId: a.peerId, role: a.role });
    }
    return out;
  }

  private socketOf(peerId: string): WebSocket | undefined {
    // Tag lookup avoids scanning every socket's attachment.
    return this.ctx.getWebSockets(`peer:${peerId}`)[0];
  }

  private attachmentOf(ws: WebSocket): SocketAttachment | null {
    try {
      return (ws.deserializeAttachment() as SocketAttachment | null) ?? null;
    } catch {
      return null;
    }
  }

  /**
   * `getWebSockets()` can return sockets already in CLOSING, and `send()` on a
   * dead socket throws — which would abort the rest of a fan-out loop. Guard on
   * readyState and swallow per-socket failures.
   */
  private send(ws: WebSocket, msg: ServerMessage): void {
    if (ws.readyState !== WebSocket.OPEN) return;
    try {
      ws.send(encode(msg));
    } catch {
      /* raced with a disconnect */
    }
  }

  private broadcast(msg: ServerMessage, except?: WebSocket): void {
    for (const ws of this.ctx.getWebSockets()) {
      if (ws === except) continue;
      this.send(ws, msg);
    }
  }

  private broadcastState(exclude?: WebSocket): void {
    const peers = this.peers(exclude);
    this.broadcast({
      t: 'state',
      viewerCount: peers.filter((p) => p.role === 'viewer').length,
      emitterLive: peers.some((p) => p.role === 'emitter'),
      transport: this.transport,
    });
  }

  private closeAll(code: number, reason: string, except?: WebSocket): void {
    for (const ws of this.ctx.getWebSockets()) {
      if (ws === except) continue;
      try {
        ws.close(code, reason);
      } catch {
        /* already closing */
      }
    }
  }
}

export const BASE_PROTO = 'livetrace.v1';
export const TOKEN_PROTO_PREFIX = 'livetrace.tok.';
