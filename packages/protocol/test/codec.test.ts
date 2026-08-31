import { describe, expect, it } from 'vitest';
import { Decoder, Encoder } from '../src/codec.js';
import type { LiveMetrics, RouteMeta, SessionHello } from '../src/types.js';

const T0 = 1_760_000_000_000;

function baseMetrics(over: Partial<LiveMetrics> = {}): LiveMetrics {
  return {
    seq: 42,
    timestamp: T0 + 42_000,
    position: { lat: 46.6204190, lon: 6.2143550, altM: 1402.5, hAccM: 4.2 },
    speedMps: 3.35,
    courseDeg: 187.4,
    heartRateBpm: 151,
    cadenceRpm: 172,
    powerW: 288,
    paceSecPerKm: 298.5,
    elevationGainM: 412.0,
    elevationLossM: 118.5,
    route: {
      distanceDoneM: 5321.4,
      distanceRemainingM: 12678.6,
      ascentRemainingM: 640.2,
      descentRemainingM: 810.7,
      etaEpochMs: T0 + 4_500_000,
      etaRemainingSec: 4458,
      fraction: 0.2956,
      segIndex: 137,
      offRoute: false,
      crossTrackM: 6.8,
    },
    batteryPct: 78,
    sources: {
      position: 'phone-gps',
      heartRate: 'watch-broadcast',
      cadence: 'ble-sensor',
      power: 'ble-sensor',
    },
    ...over,
  };
}

/** Position survives the e7 quantization to ~1.1 cm; compare with tolerance. */
function expectMetricsRoundTrip(input: LiveMetrics) {
  const enc = new Encoder(T0);
  const dec = new Decoder(T0);
  const msg = dec.decode(enc.telemetry(input));

  expect(msg.kind).toBe('telemetry');
  if (msg.kind !== 'telemetry') throw new Error('unreachable');
  const out = msg.metrics;

  expect(out.seq).toBe(input.seq);
  expect(out.timestamp).toBe(input.timestamp);
  expect(out.position.lat).toBeCloseTo(input.position.lat, 6);
  expect(out.position.lon).toBeCloseTo(input.position.lon, 6);
  expect(out.position.altM).toBeCloseTo(input.position.altM, 2);
  expect(out.heartRateBpm).toBe(input.heartRateBpm);
  expect(out.powerW).toBe(input.powerW);
  expect(out.batteryPct).toBe(input.batteryPct);
  expect(out.sources).toEqual(input.sources);
  if (input.speedMps === null) expect(out.speedMps).toBeNull();
  else expect(out.speedMps!).toBeCloseTo(input.speedMps, 3);
  if (input.route === null) expect(out.route).toBeNull();
  else {
    expect(out.route!.offRoute).toBe(input.route.offRoute);
    expect(out.route!.segIndex).toBe(input.route.segIndex);
    expect(out.route!.distanceRemainingM).toBeCloseTo(input.route.distanceRemainingM, 2);
    expect(out.route!.etaEpochMs).toBe(input.route.etaEpochMs);
  }
  return out;
}

describe('telemetry round-trip', () => {
  it('preserves a fully-populated frame', () => {
    expectMetricsRoundTrip(baseMetrics());
  });

  it('maps absent optional metrics to null (proto3 zero-value semantics)', () => {
    const out = expectMetricsRoundTrip(
      baseMetrics({
        heartRateBpm: null,
        cadenceRpm: null,
        powerW: null,
        paceSecPerKm: null,
        speedMps: null,
        courseDeg: null,
        batteryPct: null,
        route: null,
        position: { lat: 46.5, lon: 6.6, altM: 500, hAccM: null },
        sources: {
          position: 'phone-gps',
          heartRate: 'unknown',
          cadence: 'unknown',
          power: 'unknown',
        },
      }),
    );
    expect(out.heartRateBpm).toBeNull();
    expect(out.cadenceRpm).toBeNull();
    expect(out.powerW).toBeNull();
    expect(out.paceSecPerKm).toBeNull();
    expect(out.speedMps).toBeNull();
    expect(out.courseDeg).toBeNull();
    expect(out.batteryPct).toBeNull();
    expect(out.route).toBeNull();
    expect(out.position.hAccM).toBeNull();
  });

  it('handles southern/western hemispheres and negative elevation', () => {
    expectMetricsRoundTrip(
      baseMetrics({
        position: { lat: -33.8688, lon: -151.2093, altM: -12.4, hAccM: 9.5 },
      }),
    );
  });

  it('handles an off-route frame with suppressed ETA', () => {
    const out = expectMetricsRoundTrip(
      baseMetrics({
        route: {
          distanceDoneM: 100,
          distanceRemainingM: 900,
          ascentRemainingM: 10,
          descentRemainingM: 5,
          etaEpochMs: null,
          etaRemainingSec: null,
          fraction: 0.1,
          segIndex: 3,
          offRoute: true,
          crossTrackM: 74.3,
        },
      }),
    );
    expect(out.route!.offRoute).toBe(true);
    expect(out.route!.etaEpochMs).toBeNull();
    expect(out.route!.etaRemainingSec).toBeNull();
  });

  // Measured sizes (protobuf body, before the 29-byte E2EE frame overhead):
  //   minimal (position + HR)                  48 B
  //   typical (position + HR + cadence + power) 83 B
  //   full    (+ route progress + all sources) ~129 B
  // Equivalent JSON for the typical frame is 394 B, so protobuf is ~4.7x smaller.
  // These bounds are regression guards — a jump means an encoding regression.
  it('is compact: a minimal position+HR frame fits in 60 bytes', () => {
    const minimal = baseMetrics({
      speedMps: null,
      courseDeg: null,
      cadenceRpm: null,
      powerW: null,
      paceSecPerKm: null,
      elevationGainM: 0,
      elevationLossM: 0,
      batteryPct: null,
      route: null,
      position: { lat: 46.62, lon: 6.21, altM: 1402.5, hAccM: null },
      sources: {
        position: 'phone-gps',
        heartRate: 'watch-broadcast',
        cadence: 'unknown',
        power: 'unknown',
      },
    });
    expect(new Encoder(T0).telemetry(minimal).byteLength).toBeLessThan(60);
  });

  it('is compact: a fully-populated frame with route progress stays under 160 bytes', () => {
    expect(new Encoder(T0).telemetry(baseMetrics()).byteLength).toBeLessThan(160);
  });

  it('beats JSON by a wide margin on the same payload', () => {
    const m = baseMetrics({ route: null });
    const pb = new Encoder(T0).telemetry(m).byteLength;
    const json = new TextEncoder().encode(JSON.stringify(m)).byteLength;
    expect(pb * 3).toBeLessThan(json); // >3x smaller
  });

  it('property: 500 randomized frames round-trip within quantization tolerance', () => {
    const enc = new Encoder(T0);
    const dec = new Decoder(T0);
    for (let i = 0; i < 500; i++) {
      const lat = Math.random() * 180 - 90;
      const lon = Math.random() * 360 - 180;
      const m = baseMetrics({
        seq: i,
        timestamp: T0 + i * 1000,
        position: {
          lat,
          lon,
          altM: Math.random() * 9000 - 400,
          hAccM: Math.random() * 50,
        },
        heartRateBpm: 1 + Math.floor(Math.random() * 220),
      });
      const msg = dec.decode(enc.telemetry(m));
      if (msg.kind !== 'telemetry') throw new Error('expected telemetry');
      // e7 scaling => worst-case error is half a unit, ~5.6e-8 deg
      expect(Math.abs(msg.metrics.position.lat - lat)).toBeLessThan(1e-7);
      expect(Math.abs(msg.metrics.position.lon - lon)).toBeLessThan(1e-7);
      expect(msg.metrics.seq).toBe(i);
    }
  });
});

describe('control messages', () => {
  const hello: SessionHello = {
    sessionId: '3f2504e0-4f89-41d3-9a0c-0305e82c3301',
    t0EpochMs: T0,
    sport: 'trail_running',
    emitterAgent: 'livetrace-ios/1.0.0',
    hasRoute: true,
    sampleHz: 1,
  };

  it('round-trips hello and teaches the decoder t0', () => {
    const enc = new Encoder(T0);
    const dec = new Decoder(); // deliberately unaware of t0
    const msg = dec.decode(enc.hello(hello));
    expect(msg).toEqual({ kind: 'hello', hello });
    expect(dec.t0EpochMs).toBe(T0);

    // telemetry decoded afterwards resolves to an absolute timestamp
    const t = dec.decode(enc.telemetry(baseMetrics({ seq: 1, timestamp: T0 + 1000 })));
    if (t.kind !== 'telemetry') throw new Error('expected telemetry');
    expect(t.metrics.timestamp).toBe(T0 + 1000);
  });

  it('round-trips state, heartbeat and end', () => {
    const enc = new Encoder(T0);
    const dec = new Decoder(T0);

    expect(
      dec.decode(enc.state({ transport: 'edge-fanout', viewerCount: 12, emitterLive: true })),
    ).toEqual({
      kind: 'state',
      state: { transport: 'edge-fanout', viewerCount: 12, emitterLive: true },
    });

    expect(dec.decode(enc.heartbeat(5000))).toEqual({ kind: 'heartbeat', tMs: 5000 });

    expect(dec.decode(enc.end({ reason: 'revoked', detail: 'owner revoked' }))).toEqual({
      kind: 'end',
      end: { reason: 'revoked', detail: 'owner revoked' },
    });
  });

  it('round-trips every transport mode and end reason', () => {
    const enc = new Encoder(T0);
    const dec = new Decoder(T0);
    for (const transport of ['unknown', 'p2p-direct', 'p2p-turn', 'edge-fanout'] as const) {
      const m = dec.decode(enc.state({ transport, viewerCount: 1, emitterLive: true }));
      if (m.kind !== 'state') throw new Error('expected state');
      expect(m.state.transport).toBe(transport);
    }
    for (const reason of ['unknown', 'completed', 'revoked', 'expired', 'error'] as const) {
      const m = dec.decode(enc.end({ reason, detail: '' }));
      if (m.kind !== 'end') throw new Error('expected end');
      expect(m.end.reason).toBe(reason);
    }
  });
});

describe('route meta delta encoding', () => {
  function makeRoute(n: number): RouteMeta {
    const points = Array.from({ length: n }, (_, i) => ({
      lat: 46.62 + i * 0.0001,
      lon: 6.6 + i * 0.00012,
      eleM: 1000 + Math.sin(i / 20) * 150,
    }));
    return {
      routeId: 'route-1',
      name: 'Col de Balme',
      totalDistM: 18000,
      totalAscentM: 1200,
      totalDescentM: 1150,
      points,
      bbox: { minLat: 46.62, minLon: 6.6, maxLat: 46.62 + n * 0.0001, maxLon: 6.6 + n * 0.00012 },
    };
  }

  it('reconstructs the polyline by prefix-summing deltas', () => {
    const route = makeRoute(500);
    const dec = new Decoder(T0);
    const msg = dec.decode(new Encoder(T0).route(route));
    if (msg.kind !== 'route') throw new Error('expected route');

    expect(msg.route.points).toHaveLength(route.points.length);
    expect(msg.route.name).toBe('Col de Balme');
    route.points.forEach((p, i) => {
      expect(msg.route.points[i]!.lat).toBeCloseTo(p.lat, 6);
      expect(msg.route.points[i]!.lon).toBeCloseTo(p.lon, 6);
      expect(msg.route.points[i]!.eleM).toBeCloseTo(p.eleM, 1);
    });
    expect(msg.route.bbox.minLat).toBeCloseTo(route.bbox.minLat, 6);
  });

  it('delta encoding keeps a 3000-point route small', () => {
    const bytes = new Encoder(T0).route(makeRoute(3000));
    // naive float64 lat/lon/ele would be ~72 KB; deltas must beat 25 KB
    expect(bytes.byteLength).toBeLessThan(25_000);
  });

  it('handles a single-point route without emitting deltas', () => {
    const route = makeRoute(1);
    const dec = new Decoder(T0);
    const msg = dec.decode(new Encoder(T0).route(route));
    if (msg.kind !== 'route') throw new Error('expected route');
    expect(msg.route.points).toHaveLength(1);
    expect(msg.route.points[0]!.lat).toBeCloseTo(46.62, 6);
  });
});
