import { describe, expect, it } from 'vitest';
import {
  FRAME_VERSION,
  FrameOpener,
  FrameSealer,
  ReplayError,
  base64urlDecode,
  base64urlEncode,
  exportSessionKeyB64u,
  generateAccessToken,
  generateSessionKey,
  importSessionKeyB64u,
  uuidToBytes,
} from '../src/crypto.js';
import { Decoder, Encoder } from '../src/codec.js';

const SID = '3f2504e0-4f89-41d3-9a0c-0305e82c3301';
const OTHER_SID = '9c858901-8a57-4791-81fe-4c455b099bc9';
const T0 = 1_760_000_000_000;

const utf8 = (s: string) => new TextEncoder().encode(s);

describe('frame sealing', () => {
  it('seals and opens a payload', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, SID);

    const plain = utf8('hello telemetry');
    const frame = await sealer.seal(plain);

    expect(frame[0]).toBe(FRAME_VERSION);
    // ver(1) + nonce(12) + ct(15) + tag(16)
    expect(frame.byteLength).toBe(1 + 12 + plain.byteLength + 16);
    expect(new TextDecoder().decode(await opener.open(frame))).toBe('hello telemetry');
  });

  it('never emits the plaintext on the wire', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const secret = 'SECRET-GPS-46.6204';
    const frame = await sealer.seal(utf8(secret));
    const asLatin = Array.from(frame, (b) => String.fromCharCode(b)).join('');
    expect(asLatin).not.toContain(secret);
  });

  it('uses a unique, monotonically increasing nonce per frame', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const nonces = new Set<string>();
    let prev = -1n;
    for (let i = 0; i < 200; i++) {
      const f = await sealer.seal(utf8(`f${i}`));
      const nonce = f.subarray(1, 13);
      nonces.add(base64urlEncode(nonce));
      const ctr = new DataView(nonce.buffer, nonce.byteOffset, 12).getBigUint64(4, false);
      expect(ctr).toBeGreaterThan(prev);
      prev = ctr;
    }
    expect(nonces.size).toBe(200);
    expect(sealer.framesSealed).toBe(200n);
  });

  it('rejects a tampered ciphertext', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, SID);
    const frame = await sealer.seal(utf8('integrity matters'));
    frame[frame.byteLength - 1] ^= 0x01; // flip a tag bit
    await expect(opener.open(frame)).rejects.toThrow();
  });

  it('rejects a tampered nonce', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, SID);
    const frame = await sealer.seal(utf8('nonce binding'));
    frame[3] ^= 0xff; // corrupt the salt portion
    await expect(opener.open(frame)).rejects.toThrow();
  });

  it('rejects a frame from a different session (AAD binding)', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, OTHER_SID);
    const frame = await sealer.seal(utf8('cross-session splice'));
    await expect(opener.open(frame)).rejects.toThrow();
  });

  it('rejects a frame sealed with a different key', async () => {
    const sealer = FrameSealer.create(await generateSessionKey(), SID);
    const opener = FrameOpener.create(await generateSessionKey(), SID);
    await expect(opener.open(await sealer.seal(utf8('wrong key')))).rejects.toThrow();
  });

  it('rejects an unknown frame version', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, SID);
    const frame = await sealer.seal(utf8('v'));
    frame[0] = 99;
    await expect(opener.open(frame)).rejects.toThrow(/unsupported frame version/);
  });

  it('rejects a truncated frame', async () => {
    const key = await generateSessionKey();
    const opener = FrameOpener.create(key, SID);
    await expect(opener.open(new Uint8Array(10))).rejects.toThrow(/too short/);
  });
});

describe('anti-replay window', () => {
  it('rejects an exact replay', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, SID);
    const frame = await sealer.seal(utf8('once'));
    await opener.open(frame);
    await expect(opener.open(frame)).rejects.toThrow(ReplayError);
  });

  it('accepts out-of-order frames inside the window', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, SID);
    const frames = [];
    for (let i = 0; i < 5; i++) frames.push(await sealer.seal(utf8(`f${i}`)));

    // deliver 4, 2, 0, 3, 1 — an unordered DataChannel is normal
    for (const i of [4, 2, 0, 3, 1]) {
      await expect(opener.open(frames[i]!)).resolves.toBeInstanceOf(Uint8Array);
    }
    // every one is now a duplicate
    for (const f of frames) await expect(opener.open(f)).rejects.toThrow(ReplayError);
  });

  it('rejects frames older than the window', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, SID, 8);
    const first = await sealer.seal(utf8('old'));
    for (let i = 0; i < 20; i++) await opener.open(await sealer.seal(utf8(`f${i}`)));
    await expect(opener.open(first)).rejects.toThrow(ReplayError);
  });

  it('does not advance replay state on a failed authentication', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, SID);

    const good = await sealer.seal(utf8('good'));
    const forged = await sealer.seal(utf8('forged'));
    forged[forged.byteLength - 1] ^= 0x80;

    await expect(opener.open(forged)).rejects.toThrow(); // must not mark seen
    await expect(opener.open(good)).resolves.toBeInstanceOf(Uint8Array);
  });
});

describe('key & token handling', () => {
  it('exports/imports a session key via the link fragment encoding', async () => {
    const key = await generateSessionKey();
    const b64u = await exportSessionKeyB64u(key);
    expect(b64u).not.toMatch(/[+/=]/); // URL-fragment safe
    expect(base64urlDecode(b64u).byteLength).toBe(32); // 256-bit

    const reimported = await importSessionKeyB64u(b64u);
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(reimported, SID);
    await expect(opener.open(await sealer.seal(utf8('shared')))).resolves.toBeInstanceOf(
      Uint8Array,
    );
  });

  it('mints 256-bit access tokens that are unique', () => {
    const tokens = new Set<string>();
    for (let i = 0; i < 1000; i++) {
      const t = generateAccessToken();
      expect(base64urlDecode(t).byteLength).toBe(32);
      tokens.add(t);
    }
    expect(tokens.size).toBe(1000);
  });

  it('base64url round-trips arbitrary bytes', () => {
    for (let n = 0; n < 40; n++) {
      const bytes = new Uint8Array(n);
      crypto.getRandomValues(bytes);
      expect(Array.from(base64urlDecode(base64urlEncode(bytes)))).toEqual(Array.from(bytes));
    }
  });

  it('parses UUIDs to 16 bytes and rejects malformed input', () => {
    expect(uuidToBytes(SID).byteLength).toBe(16);
    expect(uuidToBytes(SID)[0]).toBe(0x3f);
    expect(() => uuidToBytes('not-a-uuid')).toThrow();
    expect(() => uuidToBytes('3f2504e0-4f89-41d3-9a0c-0305e82c33')).toThrow();
  });
});

describe('end-to-end: encode -> seal -> open -> decode', () => {
  it('survives the full emitter->viewer pipeline', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const opener = FrameOpener.create(key, SID);
    const enc = new Encoder(T0);
    const dec = new Decoder();

    // hello, then a run of telemetry
    await opener.open(await sealer.seal(enc.hello({
      sessionId: SID,
      t0EpochMs: T0,
      sport: 'trail_running',
      emitterAgent: 'livetrace-ios/1.0.0',
      hasRoute: false,
      sampleHz: 1,
    }))).then((pt) => dec.decode(pt));

    for (let i = 0; i < 50; i++) {
      const plain = enc.telemetry({
        seq: i,
        timestamp: T0 + i * 1000,
        position: { lat: 46.62 + i * 1e-4, lon: 6.6, altM: 1400 + i, hAccM: 5 },
        speedMps: 3.3,
        courseDeg: 90,
        heartRateBpm: 150 + (i % 5),
        cadenceRpm: 172,
        powerW: null,
        paceSecPerKm: 300,
        elevationGainM: i * 2,
        elevationLossM: 0,
        route: null,
        batteryPct: 80,
        sources: {
          position: 'phone-gps',
          heartRate: 'watch-broadcast',
          cadence: 'ble-sensor',
          power: 'unknown',
        },
      });
      const msg = dec.decode(await opener.open(await sealer.seal(plain)));
      if (msg.kind !== 'telemetry') throw new Error('expected telemetry');
      expect(msg.metrics.seq).toBe(i);
      expect(msg.metrics.timestamp).toBe(T0 + i * 1000);
      expect(msg.metrics.heartRateBpm).toBe(150 + (i % 5));
      expect(msg.metrics.powerW).toBeNull();
    }
  });

  it('a sealed telemetry frame stays small enough for 1 Hz mobile streaming', async () => {
    const key = await generateSessionKey();
    const sealer = FrameSealer.create(key, SID);
    const plain = new Encoder(T0).telemetry({
      seq: 1,
      timestamp: T0 + 1000,
      position: { lat: 46.62, lon: 6.6, altM: 1400, hAccM: 5 },
      speedMps: 3.3,
      courseDeg: 90,
      heartRateBpm: 150,
      cadenceRpm: 172,
      powerW: 240,
      paceSecPerKm: 300,
      elevationGainM: 10,
      elevationLossM: 2,
      route: null,
      batteryPct: 80,
      sources: {
        position: 'phone-gps',
        heartRate: 'watch-broadcast',
        cadence: 'ble-sensor',
        power: 'ble-sensor',
      },
    });
    const frame = await sealer.seal(plain);
    // 29 bytes of framing overhead (ver+nonce+tag) on top of the protobuf body
    expect(frame.byteLength).toBe(plain.byteLength + 29);
    expect(frame.byteLength).toBeLessThan(140);
  });
});
