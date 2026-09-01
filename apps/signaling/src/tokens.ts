/**
 * Session tokens.
 *
 * Two 256-bit tokens are minted per session — one for the emitter (publish),
 * one for viewers (subscribe). Only their SHA-256 hashes are persisted, so a
 * dump of Durable Object storage never yields a usable credential.
 *
 * Comparison uses `crypto.subtle.timingSafeEqual`, a workerd extension that
 * needs no compatibility flag. Two of its sharp edges are load-bearing here:
 *   - it THROWS on unequal-length inputs, so we always compare fixed-size
 *     32-byte digests. Guarding with an early length check would itself leak
 *     the secret's length through response timing.
 *   - it must be called as a method on `crypto.subtle`; destructuring it
 *     throws "Illegal invocation".
 */

export type Role = 'emitter' | 'viewer';

const TOKEN_BYTES = 32; // 256-bit

/** Mint a 256-bit token, base64url-encoded (URL- and subprotocol-safe). */
export function mintToken(): string {
  const raw = new Uint8Array(TOKEN_BYTES);
  crypto.getRandomValues(raw);
  return base64urlEncode(raw);
}

/** SHA-256 of the token's UTF-8 bytes, hex-encoded for storage. */
export async function hashToken(token: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(token));
  return hex(new Uint8Array(digest));
}

/**
 * Constant-time check of a presented token against a stored hash.
 *
 * Both sides are 32-byte digests, so lengths always match and the throw-on-
 * mismatch behaviour of timingSafeEqual can never fire on a well-formed hash.
 */
export async function verifyToken(presented: string, storedHashHex: string): Promise<boolean> {
  if (!presented || !storedHashHex) return false;
  const presentedHash = await crypto.subtle.digest(
    'SHA-256',
    new TextEncoder().encode(presented),
  );
  const stored = unhex(storedHashHex);
  // A malformed stored hash is a bug, not an auth path — fail closed.
  if (stored.byteLength !== presentedHash.byteLength) return false;
  return crypto.subtle.timingSafeEqual(presentedHash, toArrayBuffer(stored));
}

/** Verify an HMAC-SHA256 webhook signature (hex or `sha256=`-prefixed hex). */
export async function verifyHmacSignature(
  rawBody: string,
  signatureHeader: string,
  secret: string,
): Promise<boolean> {
  if (!signatureHeader || !secret) return false;
  const provided = signatureHeader.startsWith('sha256=')
    ? signatureHeader.slice('sha256='.length)
    : signatureHeader;

  const key = await crypto.subtle.importKey(
    'raw',
    new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const expected = await crypto.subtle.sign('HMAC', key, new TextEncoder().encode(rawBody));

  let providedBytes: Uint8Array;
  try {
    providedBytes = unhex(provided);
  } catch {
    return false;
  }
  if (providedBytes.byteLength !== expected.byteLength) return false;
  return crypto.subtle.timingSafeEqual(toArrayBuffer(providedBytes), expected);
}

// ───────────────────────────── encoding helpers ────────────────────────────

export function base64urlEncode(bytes: Uint8Array): string {
  let bin = '';
  for (const b of bytes) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function hex(bytes: Uint8Array): string {
  let out = '';
  for (const b of bytes) out += b.toString(16).padStart(2, '0');
  return out;
}

export function unhex(s: string): Uint8Array {
  if (s.length % 2 !== 0 || /[^0-9a-fA-F]/.test(s)) throw new Error('invalid hex');
  const out = new Uint8Array(s.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(s.slice(i * 2, i * 2 + 2), 16);
  return out;
}

function toArrayBuffer(u8: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(u8.byteLength);
  copy.set(u8);
  return copy.buffer;
}
