import { describe, expect, it } from 'vitest';
import {
  OffRouteDetector,
  PaceEstimator,
  computeEta,
  computeRemaining,
  gapFactor,
  haversine,
  minettiCost,
  prepareRoute,
  snapToRoute,
  type RoutePoint,
} from '../src/routeSnap.js';

/** ~78 m of easting per 0.001 deg lon at this latitude. */
const LAT = 46.62;

/** Flat for `flatPts` points, then a constant climb. */
function ridgeRoute(n = 20, flatPts = 10, risePerStep = 8): RoutePoint[] {
  return Array.from({ length: n }, (_, k) => ({
    lat: LAT,
    lon: 6.6 + k * 0.001,
    eleM: k < flatPts ? 1000 : 1000 + (k - flatPts + 1) * risePerStep,
  }));
}

describe('haversine', () => {
  it('measures a known short distance', () => {
    const d = haversine({ lat: LAT, lon: 6.6 }, { lat: LAT, lon: 6.601 });
    expect(d).toBeGreaterThan(70);
    expect(d).toBeLessThan(80);
  });

  it('is symmetric and zero for identical points', () => {
    const a = { lat: 46.1, lon: 6.1 };
    const b = { lat: 46.2, lon: 6.3 };
    expect(haversine(a, b)).toBeCloseTo(haversine(b, a), 9);
    expect(haversine(a, a)).toBeCloseTo(0, 9);
  });

  it('matches a known long-distance pair (Paris–Lyon ≈ 392 km)', () => {
    const d = haversine({ lat: 48.8566, lon: 2.3522 }, { lat: 45.764, lon: 4.8357 });
    expect(d / 1000).toBeGreaterThan(385);
    expect(d / 1000).toBeLessThan(400);
  });
});

describe('prepareRoute', () => {
  it('builds monotonic cumulative distance', () => {
    const r = prepareRoute(ridgeRoute());
    for (let i = 1; i < r.cumDist.length; i++) {
      expect(r.cumDist[i]!).toBeGreaterThanOrEqual(r.cumDist[i - 1]!);
    }
    expect(r.totalDist).toBeCloseTo(r.cumDist[r.cumDist.length - 1]!, 6);
  });

  it('accumulates ascent only on the climbing section', () => {
    const r = prepareRoute(ridgeRoute(20, 10, 8));
    // 10 climbing steps of 8 m
    expect(r.totalAscent).toBeCloseTo(80, 1);
    expect(r.totalDescent).toBeCloseTo(0, 6);
  });

  it('suppresses sub-hysteresis elevation noise', () => {
    const noisy: RoutePoint[] = Array.from({ length: 50 }, (_, k) => ({
      lat: LAT,
      lon: 6.6 + k * 0.001,
      eleM: 1000 + (k % 2 === 0 ? 0.4 : -0.4), // ±0.4 m jitter, below the 1 m default
    }));
    const r = prepareRoute(noisy);
    expect(r.totalAscent).toBeCloseTo(0, 6);
    expect(r.totalDescent).toBeCloseTo(0, 6);
  });

  it('drops duplicate points', () => {
    const dup: RoutePoint[] = [
      { lat: LAT, lon: 6.6, eleM: 1000 },
      { lat: LAT, lon: 6.6, eleM: 1000 },
      { lat: LAT, lon: 6.601, eleM: 1000 },
    ];
    expect(prepareRoute(dup).pts).toHaveLength(2);
  });

  it('clamps grade into the Minetti-valid range', () => {
    const cliff: RoutePoint[] = [
      { lat: LAT, lon: 6.6, eleM: 1000 },
      { lat: LAT, lon: 6.6005, eleM: 1400 }, // absurd ~1000% grade
    ];
    const r = prepareRoute(cliff);
    expect(r.grade[0]!).toBeLessThanOrEqual(0.45);
  });

  it('handles degenerate routes without throwing', () => {
    expect(prepareRoute([]).totalDist).toBe(0);
    expect(prepareRoute([{ lat: LAT, lon: 6.6, eleM: 1000 }]).totalDist).toBe(0);
  });
});

describe('snapToRoute', () => {
  const route = prepareRoute(ridgeRoute());

  it('snaps a fix beside the line and reports cross-track distance', () => {
    const fix = { lat: LAT + 0.0002, lon: 6.6 + 5 * 0.001 }; // ~22 m north
    const snap = snapToRoute(route, fix, 0);
    expect(snap.crossTrackM).toBeGreaterThan(15);
    expect(snap.crossTrackM).toBeLessThan(30);
    expect(snap.segIndex).toBeGreaterThanOrEqual(4);
    expect(snap.segIndex).toBeLessThanOrEqual(5);
    expect(snap.distDone).toBeGreaterThan(300);
    expect(snap.distDone).toBeLessThan(460);
  });

  it('reports ~0 cross-track for a fix exactly on a vertex', () => {
    const snap = snapToRoute(route, { lat: LAT, lon: 6.6 + 7 * 0.001 }, 6);
    expect(snap.crossTrackM).toBeLessThan(0.5);
    expect(snap.distDone).toBeCloseTo(route.cumDist[7]!, 0);
  });

  it('advances distDone monotonically along a forward traversal', () => {
    let last = -1;
    let seg = 0;
    for (let k = 0; k < 20; k++) {
      const snap = snapToRoute(route, { lat: LAT, lon: 6.6 + k * 0.001 }, seg);
      expect(snap.distDone).toBeGreaterThanOrEqual(last);
      last = snap.distDone;
      seg = snap.segIndex;
    }
    expect(last).toBeCloseTo(route.totalDist, 0);
  });

  it('re-acquires globally after a large GPS gap', () => {
    // lastSeg says we are at the start, but the fix is near the far end
    const snap = snapToRoute(route, { lat: LAT, lon: 6.6 + 18 * 0.001 }, 0);
    expect(snap.segIndex).toBeGreaterThan(15);
    expect(snap.crossTrackM).toBeLessThan(5);
  });

  it('prefers the nearby pass on an out-and-back route', () => {
    // out along the ridge, then back over the same ground
    const out = ridgeRoute(10, 10, 0);
    const back = out
      .slice(0, 9)
      .reverse()
      .map((p) => ({ ...p, lat: p.lat + 0.00002 })); // ~2 m offset
    const r = prepareRoute([...out, ...back]);
    const fix = { lat: LAT, lon: 6.6 + 4 * 0.001 };

    const outbound = snapToRoute(r, fix, 3);
    expect(outbound.segIndex).toBeLessThan(9); // stays on the outbound leg

    const returning = snapToRoute(r, fix, 13);
    expect(returning.segIndex).toBeGreaterThanOrEqual(9); // stays on the return leg
  });

  it('is safe on an empty route', () => {
    const snap = snapToRoute(prepareRoute([]), { lat: LAT, lon: 6.6 });
    expect(snap.distDone).toBe(0);
    expect(snap.segIndex).toBe(0);
  });
});

describe('Minetti grade adjustment', () => {
  it('is 1.0 on the flat', () => {
    expect(gapFactor(0)).toBeCloseTo(1, 9);
    expect(minettiCost(0)).toBeCloseTo(3.6, 9);
  });

  it('costs more uphill and less on a gentle descent', () => {
    expect(gapFactor(0.1)).toBeCloseTo(1.6578, 3);
    expect(gapFactor(0.2)).toBeCloseTo(2.5019, 3);
    expect(gapFactor(-0.1)).toBeCloseTo(0.5977, 3);
    expect(gapFactor(0.1)).toBeGreaterThan(1);
    expect(gapFactor(-0.1)).toBeLessThan(1);
  });

  it('is monotonic across the uphill range', () => {
    for (let g = 0; g < 0.4; g += 0.05) {
      expect(gapFactor(g + 0.05)).toBeGreaterThan(gapFactor(g));
    }
  });
});

describe('computeRemaining', () => {
  const route = prepareRoute(ridgeRoute());

  it('splits the route into done and remaining', () => {
    const snap = snapToRoute(route, { lat: LAT, lon: 6.6 + 5 * 0.001 }, 4);
    const rem = computeRemaining(route, snap);
    expect(snap.distDone + rem.distRemainingM).toBeCloseTo(route.totalDist, 3);
    expect(rem.fraction).toBeGreaterThan(0);
    expect(rem.fraction).toBeLessThan(1);
  });

  it('inflates effective distance when a climb is still ahead', () => {
    const snap = snapToRoute(route, { lat: LAT, lon: 6.6 + 5 * 0.001 }, 4);
    const rem = computeRemaining(route, snap);
    expect(rem.effectiveRemainingM).toBeGreaterThan(rem.distRemainingM);
    expect(rem.ascentRemainingM).toBeCloseTo(80, 0); // whole climb still to come
  });

  it('reports no remaining ascent once the climb is behind', () => {
    const snap = snapToRoute(route, { lat: LAT, lon: 6.6 + 19 * 0.001 }, 18);
    const rem = computeRemaining(route, snap);
    expect(rem.ascentRemainingM).toBeCloseTo(0, 1);
    expect(rem.distRemainingM).toBeCloseTo(0, 1);
    expect(rem.fraction).toBeCloseTo(1, 3);
  });
});

describe('PaceEstimator', () => {
  it('is not ready before it sees movement', () => {
    expect(new PaceEstimator().ready).toBe(false);
  });

  it('converges to the flat-equivalent speed', () => {
    const p = new PaceEstimator(10);
    for (let i = 0; i < 100; i++) p.update(3.0, 0, 1);
    expect(p.flatSpeed).toBeCloseTo(3.0, 2);
    expect(p.ready).toBe(true);
  });

  it('normalizes uphill effort to a higher flat-equivalent speed', () => {
    const uphill = new PaceEstimator(10);
    for (let i = 0; i < 100; i++) uphill.update(2.0, 0.1, 1); // slow but climbing
    // 2.0 m/s at +10% is worth ~2.0 * 1.658 flat
    expect(uphill.flatSpeed).toBeCloseTo(2.0 * gapFactor(0.1), 1);
  });

  it('ignores stops so pauses do not poison the estimate', () => {
    const p = new PaceEstimator(10);
    for (let i = 0; i < 100; i++) p.update(3.0, 0, 1);
    const before = p.flatSpeed;
    for (let i = 0; i < 60; i++) p.update(0.0, 0, 1); // standing still
    expect(p.flatSpeed).toBeCloseTo(before, 6);
  });
});

describe('computeEta', () => {
  const route = prepareRoute(ridgeRoute());
  const primed = () => {
    const p = new PaceEstimator(10);
    for (let i = 0; i < 100; i++) p.update(3.0, 0, 1);
    return p;
  };

  it('returns null until the pace estimator is primed', () => {
    const eta = computeEta(1000, new PaceEstimator(), 1_000_000, false);
    expect(eta.etaRemainingSec).toBeNull();
    expect(eta.etaEpochMs).toBeNull();
  });

  it('suppresses ETA while off-route', () => {
    const eta = computeEta(1000, primed(), 1_000_000, true);
    expect(eta.etaRemainingSec).toBeNull();
    expect(eta.etaEpochMs).toBeNull();
  });

  it('predicts a finish time from effective distance and flat pace', () => {
    const now = 1_000_000;
    const eta = computeEta(1800, primed(), now, false);
    expect(eta.etaRemainingSec).toBe(600); // 1800 m / 3 m/s
    expect(eta.etaEpochMs).toBe(now + 600_000);
  });

  it('predicts a later finish than the naive estimate when a climb remains', () => {
    const snap = snapToRoute(route, { lat: LAT, lon: 6.6 + 5 * 0.001 }, 4);
    const rem = computeRemaining(route, snap);
    const pace = primed();
    const eta = computeEta(rem.effectiveRemainingM, pace, 0, false);
    const naive = Math.round(rem.distRemainingM / pace.flatSpeed);
    expect(eta.etaRemainingSec!).toBeGreaterThan(naive);
  });
});

describe('OffRouteDetector', () => {
  it('requires consecutive excursions before tripping', () => {
    const d = new OffRouteDetector(30, 3);
    expect(d.update(50)).toBe(false);
    expect(d.update(50)).toBe(false);
    expect(d.update(50)).toBe(true); // third strike
    expect(d.offRoute).toBe(true);
  });

  it('resets immediately on a single in-corridor fix', () => {
    const d = new OffRouteDetector(30, 3);
    d.update(50);
    d.update(50);
    expect(d.update(5)).toBe(false);
    expect(d.update(50)).toBe(false); // streak restarted
  });

  it('never trips while inside the corridor', () => {
    const d = new OffRouteDetector(30, 3);
    for (let i = 0; i < 50; i++) expect(d.update(Math.random() * 29)).toBe(false);
  });
});

describe('end-to-end tracking simulation', () => {
  it('tracks a full traversal: progress rises, ascent falls, ETA shrinks', () => {
    const route = prepareRoute(ridgeRoute(40, 20, 5));
    const pace = new PaceEstimator(15);
    const off = new OffRouteDetector();
    let seg = 0;
    let lastFraction = -1;
    let lastEta = Number.POSITIVE_INFINITY;
    let etaSamples = 0;

    for (let k = 0; k < 40; k++) {
      const fix = {
        lat: LAT + (Math.random() - 0.5) * 0.00004, // ±~2 m of GPS jitter
        lon: 6.6 + k * 0.001,
      };
      const snap = snapToRoute(route, fix, seg);
      seg = snap.segIndex;
      const rem = computeRemaining(route, snap);
      const isOff = off.update(snap.crossTrackM);
      pace.update(3.0, route.grade[seg] ?? 0, 1);
      const eta = computeEta(rem.effectiveRemainingM, pace, k * 1000, isOff);

      expect(isOff).toBe(false); // jitter must never look like going off-route
      expect(rem.fraction).toBeGreaterThanOrEqual(lastFraction - 1e-9);
      lastFraction = rem.fraction;

      if (eta.etaRemainingSec !== null) {
        expect(eta.etaRemainingSec).toBeLessThanOrEqual(lastEta + 30);
        lastEta = eta.etaRemainingSec;
        etaSamples++;
      }
    }

    expect(etaSamples).toBeGreaterThan(30);
    expect(lastFraction).toBeCloseTo(1, 2);
    expect(lastEta).toBeLessThan(60); // essentially arrived
  });
});
