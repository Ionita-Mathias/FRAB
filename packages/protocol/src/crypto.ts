/**
 * LiveTrace — application-layer E2EE for telemetry frames.
 *
 * Every frame is sealed with AES-256-GCM ABOVE the transport, so the identical
 * ciphertext travels over a direct DataChannel, a TURN relay, or the Durable
 * Object fan-out. The edge therefore relays bytes it provably cannot read.
 *
 * Frame layout (matches docs/phase3/01 §1.2):
 *
 *   ┌────────┬──────────────────────────────┬──────────────────────────┐
 *   │ ver(1) │ nonce(12) = salt(4)‖ctr(8)    │ AES-256-GCM ct + tag(16) │
 *   └────────┴──────────────────────────────┴──────────────────────────┘
 *
 * Nonce construction: a 4-byte random per-session salt concatenated with a
 * 64-bit big-endian counter. This is DETERMINISTIC rather than random, which:
 *   1. makes nonce reuse structurally impossible within a session (a random
 *      96-bit nonce would carry a birthday risk over a multi-hour stream),
 *   2. lets the receiver read the counter and reject replays/duplicates
 *      BEFORE attempting decryption.
 *
 * AAD = ver ‖ sessionId(16 raw UUID bytes), binding each frame to the protocol
 * version and its session so frames cannot be spliced across sessions.
 *
 * Uses WebCrypto only, so this exact module runs unmodified in the browser
 * viewer, in a Cloudflare Worker/Durable Object, and in Node >= 22 tests.
 */

export const FRAME_VERSION = 1;
const NONCE_LEN = 12;
const SALT_LEN = 4;
const TAG_LEN = 16; // GCM tag, appended to ciphertext by WebCrypto
const KEY_LEN = 32; // AES-256

const subtle = (): SubtleCrypto => {
  const c = globalThis.crypto;
  if (!c?.subtle) throw new Error('WebCrypto unavailable: crypto.subtle is required');
  return c.subtle;
};

// ───────────────────────────── key management ──────────────────────────────

/** Generate a fresh 256-bit session key. Never leaves the client. */
export async function generateSessionKey(): Promise<CryptoKey> {
  return subtle().generateKey({ name: 'AES-GCM', length: 256 }, true, [
    'encrypt',
    'decrypt',
  ]);
}

export async function importSessionKey(raw: Uint8Array): Promise<CryptoKey> {
  if (raw.byteLength !== KEY_LEN) {
    throw new Error(`session key must be ${KEY_LEN} bytes, got ${raw.byteLength}`);
  }
  return subtle().importKey('raw', toArrayBuffer(raw), { name: 'AES-GCM' }, true, [
    'encrypt',
    'decrypt',
  ]);
}

export async function exportSessionKey(key: CryptoKey): Promise<Uint8Array> {
  return new Uint8Array(await subtle().exportKey('raw', key));
}

/** Encode a key for the share-link fragment (`https://…/s/{id}#k=…`). */
export async function exportSessionKeyB64u(key: CryptoKey): Promise<string> {
  return base64urlEncode(await exportSessionKey(key));
}

export async function importSessionKeyB64u(b64u: string): Promise<CryptoKey> {
  return importSessionKey(base64urlDecode(b64u));
}

// ───────────────────────────── sealing (emitter) ───────────────────────────

export class FrameSealer {
  private readonly salt: Uint8Array;
  private counter = 0n;

  private constructor(
    private readonly key: CryptoKey,
    private readonly aad: Uint8Array,
    salt: Uint8Array,
  ) {
    this.salt = salt;
  }

  /**
   * @param sessionId UUIDv4 of the session (bound into the AAD)
   * @param salt      optional fixed salt — supply ONLY for deterministic tests
   */
  static create(key: CryptoKey, sessionId: string, salt?: Uint8Array): FrameSealer {
    const s = salt ?? randomBytes(SALT_LEN);
    if (s.byteLength !== SALT_LEN) throw new Error(`salt must be ${SALT_LEN} bytes`);
    return new FrameSealer(key, buildAad(sessionId), s);
  }

  /** Seal one serialized Envelope into a wire frame. */
  async seal(plaintext: Uint8Array): Promise<Uint8Array> {
    const ctr = this.counter++;
    const nonce = buildNonce(this.salt, ctr);
    const ct = new Uint8Array(
      await subtle().encrypt(
        { name: 'AES-GCM', iv: toArrayBuffer(nonce), additionalData: toArrayBuffer(this.aad), tagLength: TAG_LEN * 8 },
        this.key,
        toArrayBuffer(plaintext),
      ),
    );
    const frame = new Uint8Array(1 + NONCE_LEN + ct.byteLength);
    frame[0] = FRAME_VERSION;
    frame.set(nonce, 1);
    frame.set(ct, 1 + NONCE_LEN);
    return frame;
  }

  /** Frames sealed so far (== next counter value). */
  get framesSealed(): bigint {
    return this.counter;
  }
}

// ───────────────────────────── opening (viewer) ────────────────────────────

export class ReplayError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ReplayError';
  }
}

/**
 * Opens frames and enforces an anti-replay window (IPsec-style sliding bitmap).
 * Out-of-order delivery is normal on an unordered DataChannel, so frames within
 * `windowSize` of the highest counter are accepted once; duplicates and frames
 * older than the window are rejected.
 */
export class FrameOpener {
  private highest = -1n;
  private bitmap = 0n;
  private readonly windowSize: bigint;

  private constructor(
    private readonly key: CryptoKey,
    private readonly aad: Uint8Array,
    windowSize: number,
  ) {
    this.windowSize = BigInt(windowSize);
  }

  static create(key: CryptoKey, sessionId: string, windowSize = 64): FrameOpener {
    if (windowSize < 1 || windowSize > 64) throw new Error('windowSize must be 1..64');
    return new FrameOpener(key, buildAad(sessionId), windowSize);
  }

  /** Verify + decrypt a wire frame, returning the serialized Envelope. */
  async open(frame: Uint8Array): Promise<Uint8Array> {
    if (frame.byteLength < 1 + NONCE_LEN + TAG_LEN) {
      throw new Error('frame too short');
    }
    const ver = frame[0];
    if (ver !== FRAME_VERSION) {
      throw new Error(`unsupported frame version ${ver}`);
    }
    const nonce = frame.subarray(1, 1 + NONCE_LEN);
    const ct = frame.subarray(1 + NONCE_LEN);
    const ctr = readCounter(nonce);

    this.checkReplay(ctr); // cheap rejection before any crypto work

    const pt = new Uint8Array(
      await subtle().decrypt(
        { name: 'AES-GCM', iv: toArrayBuffer(nonce), additionalData: toArrayBuffer(this.aad), tagLength: TAG_LEN * 8 },
        this.key,
        toArrayBuffer(ct),
      ),
    );

    this.markSeen(ctr); // only after authentication succeeds
    return pt;
  }

  private checkReplay(ctr: bigint): void {
    if (this.highest < 0n) return; // first frame
    if (ctr > this.highest) return; // newest
    const age = this.highest - ctr;
    if (age >= this.windowSize) {
      throw new ReplayError(`frame ${ctr} is outside the replay window`);
    }
    if ((this.bitmap >> age) & 1n) {
      throw new ReplayError(`frame ${ctr} already seen`);
    }
  }

  private markSeen(ctr: bigint): void {
    if (this.highest < 0n) {
      this.highest = ctr;
      this.bitmap = 1n;
      return;
    }
    if (ctr > this.highest) {
      const shift = ctr - this.highest;
      this.bitmap = shift >= 64n ? 1n : ((this.bitmap << shift) | 1n) & MASK64;
      this.highest = ctr;
    } else {
      this.bitmap |= 1n << (this.highest - ctr);
    }
  }

  get highestCounter(): bigint {
    return this.highest;
  }
}

// ───────────────────────────── helpers ─────────────────────────────────────

const MASK64 = (1n << 64n) - 1n;

function randomBytes(n: number): Uint8Array {
  const b = new Uint8Array(n);
  globalThis.crypto.getRandomValues(b);
  return b;
}

/** Cryptographically strong 256-bit access token, base64url encoded. */
export function generateAccessToken(): string {
  return base64urlEncode(randomBytes(32));
}

function buildNonce(salt: Uint8Array, counter: bigint): Uint8Array {
  const nonce = new Uint8Array(NONCE_LEN);
  nonce.set(salt, 0);
  new DataView(nonce.buffer).setBigUint64(SALT_LEN, counter, false); // big-endian
  return nonce;
}

function readCounter(nonce: Uint8Array): bigint {
  const dv = new DataView(nonce.buffer, nonce.byteOffset, nonce.byteLength);
  return dv.getBigUint64(SALT_LEN, false);
}

/** AAD = version byte ‖ 16 raw UUID bytes. */
function buildAad(sessionId: string): Uint8Array {
  const id = uuidToBytes(sessionId);
  const aad = new Uint8Array(1 + id.byteLength);
  aad[0] = FRAME_VERSION;
  aad.set(id, 1);
  return aad;
}

export function uuidToBytes(uuid: string): Uint8Array {
  const hex = uuid.replace(/-/g, '');
  if (hex.length !== 32 || /[^0-9a-fA-F]/.test(hex)) {
    throw new Error(`invalid UUID: ${uuid}`);
  }
  const out = new Uint8Array(16);
  for (let i = 0; i < 16; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

/**
 * Copy into a standalone, exactly-sized ArrayBuffer.
 *
 * Deliberately does NOT use `u8.slice().buffer`: in Node a protobufjs writer
 * returns a pooled `Buffer`, and `Buffer.prototype.slice` is an alias for
 * `subarray` (a VIEW, not a copy). Reading `.buffer` off such a view yields the
 * whole 8 KB pool, which would silently encrypt ~8 KB per frame instead of ~80
 * bytes. Allocating and `set`-ing is correct for Buffer and Uint8Array alike.
 */
function toArrayBuffer(u8: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(u8.byteLength);
  copy.set(u8);
  return copy.buffer;
}

export function base64urlEncode(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function base64urlDecode(s: string): Uint8Array {
  const b64 = s.replace(/-/g, '+').replace(/_/g, '/');
  const bin = atob(b64.padEnd(Math.ceil(b64.length / 4) * 4, '='));
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}
