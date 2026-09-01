/**
 * Control-plane message protocol (JSON over the signaling WebSocket).
 *
 * This carries ONLY WebRTC negotiation and presence. Telemetry never travels
 * here — it goes peer-to-peer over the DataChannel, or, on the fan-out
 * fallback, as opaque ciphertext the edge cannot read. Keeping the two planes
 * separate is what makes the zero-retention claim checkable.
 */

import type { Role } from './tokens.js';

export type TransportMode = 'p2p-direct' | 'p2p-turn' | 'edge-fanout';

/** Client → server. */
export type ClientMessage =
  | { t: 'offer'; to: string; sdp: string }
  | { t: 'answer'; to: string; sdp: string }
  | { t: 'ice'; to: string; candidate: unknown }
  | { t: 'transport'; mode: TransportMode }
  | { t: 'bye' };

/** Server → client. */
export type ServerMessage =
  | { t: 'welcome'; peerId: string; role: Role; peers: PeerInfo[] }
  | { t: 'peer-joined'; peerId: string; role: Role }
  | { t: 'peer-left'; peerId: string }
  | { t: 'offer'; from: string; sdp: string }
  | { t: 'answer'; from: string; sdp: string }
  | { t: 'ice'; from: string; candidate: unknown }
  | { t: 'state'; viewerCount: number; emitterLive: boolean; transport: TransportMode | null }
  | { t: 'closing'; reason: 'revoked' | 'expired' | 'emitter-left' }
  | { t: 'error'; code: ErrorCode; message: string };

export interface PeerInfo {
  peerId: string;
  role: Role;
}

export type ErrorCode =
  | 'bad_message'
  | 'unknown_peer'
  | 'not_permitted'
  | 'rate_limited'
  | 'payload_too_large';

/** Signaling payloads are small; anything larger is abuse or a bug. */
export const MAX_SDP_BYTES = 64 * 1024;
export const MAX_MESSAGE_BYTES = 96 * 1024;

export interface ParseOk {
  ok: true;
  msg: ClientMessage;
}
export interface ParseErr {
  ok: false;
  code: ErrorCode;
  message: string;
}

const TRANSPORTS: readonly TransportMode[] = ['p2p-direct', 'p2p-turn', 'edge-fanout'];

/**
 * Parse and validate a client frame.
 *
 * Everything arriving here is attacker-controlled, so this is a strict
 * allow-list: unknown `t` values, missing fields, wrong types and oversized
 * SDP are all rejected rather than forwarded to a peer.
 */
export function parseClientMessage(raw: string | ArrayBuffer): ParseOk | ParseErr {
  if (typeof raw !== 'string') {
    return { ok: false, code: 'bad_message', message: 'expected a text frame' };
  }
  if (raw.length > MAX_MESSAGE_BYTES) {
    return { ok: false, code: 'payload_too_large', message: 'message too large' };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, code: 'bad_message', message: 'malformed JSON' };
  }
  if (typeof parsed !== 'object' || parsed === null) {
    return { ok: false, code: 'bad_message', message: 'expected an object' };
  }

  const m = parsed as Record<string, unknown>;
  switch (m['t']) {
    case 'offer':
    case 'answer': {
      const to = m['to'];
      const sdp = m['sdp'];
      if (typeof to !== 'string' || !to) {
        return { ok: false, code: 'bad_message', message: '`to` must be a peer id' };
      }
      if (typeof sdp !== 'string' || !sdp) {
        return { ok: false, code: 'bad_message', message: '`sdp` must be a string' };
      }
      if (sdp.length > MAX_SDP_BYTES) {
        return { ok: false, code: 'payload_too_large', message: 'sdp too large' };
      }
      return { ok: true, msg: { t: m['t'], to, sdp } as ClientMessage };
    }
    case 'ice': {
      const to = m['to'];
      if (typeof to !== 'string' || !to) {
        return { ok: false, code: 'bad_message', message: '`to` must be a peer id' };
      }
      if (!('candidate' in m)) {
        return { ok: false, code: 'bad_message', message: '`candidate` is required' };
      }
      return { ok: true, msg: { t: 'ice', to, candidate: m['candidate'] } };
    }
    case 'transport': {
      const mode = m['mode'];
      if (typeof mode !== 'string' || !TRANSPORTS.includes(mode as TransportMode)) {
        return { ok: false, code: 'bad_message', message: 'unknown transport mode' };
      }
      return { ok: true, msg: { t: 'transport', mode: mode as TransportMode } };
    }
    case 'bye':
      return { ok: true, msg: { t: 'bye' } };
    default:
      return { ok: false, code: 'bad_message', message: 'unknown message type' };
  }
}

export function encode(msg: ServerMessage): string {
  return JSON.stringify(msg);
}
