/**
 * LiveTrace — reference implementation of GPX route-snapping and
 * grade-adjusted dynamic ETA. Dependency-free; runs identically in the browser
 * viewer and in a Cloudflare Durable Object (both are V8 isolates).
 *
 * The math is specified in docs/phase3/03-gpx-snap-and-eta-algorithm.md; this
 * file is the executable source of truth for that spec.
 */

// ───────────────────────────── geometry primitives ─────────────────────────
const R_EARTH = 6371008.8; // IUGG mean Earth radius, meters
const d2r = (d: number) => (d * Math.PI) / 180;

export interface LatLon { lat: number; lon: number; }
interface XY { x: number; y: number; }

/** Great-circle distance (meters). Used for segment lengths (accuracy > speed). */
export function haversine(a: LatLon, b: LatLon): number {
  const dLat = d2r(b.lat - a.lat);
  const dLon = d2r(b.lon - a.lon);
  const s =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(d2r(a.lat)) * Math.cos(d2r(b.lat)) * Math.sin(dLon / 2) ** 2;
  return 2 * R_EARTH * Math.asin(Math.min(1, Math.sqrt(s)));
}

/**
 * Local equirectangular projection about `ref` (meters). Exact enough for the
 * ~tens-of-meters point-to-segment projection used by snapping.
 */
function toLocal(ref: LatLon, p: LatLon): XY {
  return {
    x: d2r(p.lon - ref.lon) * Math.cos(d2r(ref.lat)) * R_EARTH,
    y: d2r(p.lat - ref.lat) * R_EARTH,
  };
}

// ───────────────────────────── route preprocessing ─────────────────────────
export interface RoutePoint extends LatLon { eleM: number; }

export interface PreparedRoute {
  pts: RoutePoint[];
  /** segLen[i] = ground length of segment i (pts[i] -> pts[i+1]) */
  segLen: number[];
  /** cumDist[i] = distance from start to pts[i] */
  cumDist: number[];
  /** cumAscent[i] / cumDescent[i] = cumulative +/- elevation to pts[i] */
  cumAscent: number[];
  cumDescent: number[];
  /** signed grade of segment i (rise/run), clamped to Minetti-valid range */
  grade: number[];
  totalDist: number;
  totalAscent: number;
  totalDescent: number;
}

/**
 * Build cumulative distance/elevation arrays once per route. `hysteresisM`
 * suppresses elevation noise before accumulating gain/loss (barometer jitter).
 */
export function prepareRoute(raw: RoutePoint[], hysteresisM = 1.0): PreparedRoute {
  const pts = dedupe(raw);
  const n = pts.length;
  const segLen = new Array(Math.max(0, n - 1)).fill(0);
  const grade = new Array(Math.max(0, n - 1)).fill(0);
  const cumDist = new Array(n).fill(0);
  const cumAscent = new Array(n).fill(0);
  const cumDescent = new Array(n).fill(0);

  let eleRef = pts.length ? pts[0].eleM : 0; // hysteresis reference
  for (let i = 0; i < n - 1; i++) {
    const horiz = haversine(pts[i], pts[i + 1]);
    segLen[i] = horiz;
    cumDist[i + 1] = cumDist[i] + horiz;

    const dEle = pts[i + 1].eleM - pts[i].eleM;
    // grade uses raw segment rise/run (clamped); accumulation uses hysteresis.
    grade[i] = horiz > 0.5 ? clamp(dEle / horiz, -0.45, 0.45) : 0;

    let asc = 0;
    let desc = 0;
    if (pts[i + 1].eleM - eleRef > hysteresisM) {
      asc = pts[i + 1].eleM - eleRef;
      eleRef = pts[i + 1].eleM;
    } else if (eleRef - pts[i + 1].eleM > hysteresisM) {
      desc = eleRef - pts[i + 1].eleM;
      eleRef = pts[i + 1].eleM;
    }
    cumAscent[i + 1] = cumAscent[i] + asc;
    cumDescent[i + 1] = cumDescent[i] + desc;
  }

  return {
    pts,
    segLen,
    cumDist,
    cumAscent,
    cumDescent,
    grade,
    totalDist: cumDist[n - 1] ?? 0,
    totalAscent: cumAscent[n - 1] ?? 0,
    totalDescent: cumDescent[n - 1] ?? 0,
  };
}

// ───────────────────────────── snapping ────────────────────────────────────
export interface SnapResult {
  segIndex: number;
  /** fractional position within the segment, 0..1 */
  t: number;
  /** distance from route start to the snapped foot point (meters) */
  distDone: number;
  /** perpendicular distance from the fix to the route (meters) */
  crossTrackM: number;
  foot: LatLon;
}

/**
 * Snap a live fix to the route. To survive self-intersecting / out-and-back
 * routes we search a MONOTONIC window ahead of (and slightly behind) the last
 * known segment first, only falling back to a global scan when the fix is far
 * from the whole window (e.g. after a GPS gap).
 *
 * @param lastSeg  previously matched segment (0 for first fix)
 * @param backWin  segments to look back (guards against brief backtracking)
 * @param fwdWin   segments to look ahead (bounds cost; ~200 m of route)
 */
export function snapToRoute(
  route: PreparedRoute,
  fix: LatLon,
  lastSeg = 0,
  backWin = 3,
  fwdWin = 40,
): SnapResult {
  const nSeg = route.segLen.length;
  if (nSeg === 0) {
    return { segIndex: 0, t: 0, distDone: 0, crossTrackM: 0, foot: fix };
  }
  const lo = Math.max(0, lastSeg - backWin);
  const hi = Math.min(nSeg - 1, lastSeg + fwdWin);

  let best = projectWindow(route, fix, lo, hi);
  // If the windowed match is implausibly far, the athlete likely jumped
  // (GPS gap, restart). Pay for one global scan to re-acquire.
  if (best.crossTrackM > 60 && (lo > 0 || hi < nSeg - 1)) {
    const global = projectWindow(route, fix, 0, nSeg - 1);
    if (global.crossTrackM < best.crossTrackM) best = global;
  }
  return best;
}

function projectWindow(
  route: PreparedRoute,
  fix: LatLon,
  lo: number,
  hi: number,
): SnapResult {
  let bestD2 = Infinity;
  let bestSeg = lo;
  let bestT = 0;
  let bestFoot: LatLon = route.pts[lo];

  for (let i = lo; i <= hi; i++) {
    const a = route.pts[i];
    const b = route.pts[i + 1];
    // project into local meters about the segment start
    const A = { x: 0, y: 0 };
    const B = toLocal(a, b);
    const P = toLocal(a, fix);
    const abx = B.x - A.x;
    const aby = B.y - A.y;
    const len2 = abx * abx + aby * aby;
    const t = len2 > 0 ? clamp((P.x * abx + P.y * aby) / len2, 0, 1) : 0;
    const fx = A.x + t * abx;
    const fy = A.y + t * aby;
    const dx = P.x - fx;
    const dy = P.y - fy;
    const d2 = dx * dx + dy * dy;
    if (d2 < bestD2) {
      bestD2 = d2;
      bestSeg = i;
      bestT = t;
      // convert foot back to lat/lon (inverse of toLocal about a)
      bestFoot = {
        lat: a.lat + (fy / R_EARTH) * (180 / Math.PI),
        lon:
          a.lon +
          (fx / (R_EARTH * Math.cos(d2r(a.lat)))) * (180 / Math.PI),
      };
    }
  }

  const distDone = route.cumDist[bestSeg] + bestT * route.segLen[bestSeg];
  return {
    segIndex: bestSeg,
    t: bestT,
    distDone,
    crossTrackM: Math.sqrt(bestD2),
    foot: bestFoot,
  };
}

// ───────────────────────────── remaining + ETA ─────────────────────────────
/** Minetti (2002) energy cost of running vs gradient, J·kg⁻¹·m⁻¹. */
export function minettiCost(i: number): number {
  const g = clamp(i, -0.45, 0.45);
  return 155.4 * g ** 5 - 30.4 * g ** 4 - 43.3 * g ** 3 + 46.3 * g ** 2 + 19.5 * g + 3.6;
}
const C_FLAT = 3.6; // minettiCost(0)
/** Grade-adjustment multiplier: >1 means the grade costs more than flat. */
export function gapFactor(i: number): number {
  return minettiCost(i) / C_FLAT;
}

export interface RemainingResult {
  distRemainingM: number;
  ascentRemainingM: number;
  descentRemainingM: number;
  /** flat-equivalent remaining distance (Σ segLen·gapFactor), meters */
  effectiveRemainingM: number;
  fraction: number;
}

export function computeRemaining(route: PreparedRoute, snap: SnapResult): RemainingResult {
  const total = route.totalDist;
  const distRemainingM = Math.max(0, total - snap.distDone);

  const i = snap.segIndex;
  // ascent/descent still to come: remainder of current segment + all following.
  // approximate cumulative ascent at the foot by linear interpolation
  const cumAscFoot =
    route.cumAscent[i] +
    snap.t * (route.cumAscent[i + 1] - route.cumAscent[i]);
  const cumDescFoot =
    route.cumDescent[i] +
    snap.t * (route.cumDescent[i + 1] - route.cumDescent[i]);
  const ascentRemainingM = Math.max(0, route.totalAscent - cumAscFoot);
  const descentRemainingM = Math.max(0, route.totalDescent - cumDescFoot);

  // effective (flat-equivalent) remaining distance
  let eff = (1 - snap.t) * route.segLen[i] * gapFactor(route.grade[i]);
  for (let j = i + 1; j < route.segLen.length; j++) {
    eff += route.segLen[j] * gapFactor(route.grade[j]);
  }

  return {
    distRemainingM,
    ascentRemainingM,
    descentRemainingM,
    effectiveRemainingM: eff,
    fraction: total > 0 ? clamp(snap.distDone / total, 0, 1) : 0,
  };
}

/**
 * Online flat-equivalent-speed estimator. Each fix contributes
 * v_flat = v_instant · gapFactor(currentGrade); we EWMA those so ETA reflects
 * the athlete's *effort-normalized* pace rather than raw downhill/uphill speed.
 */
export class PaceEstimator {
  private vFlat = 0;
  private primed = false;
  /** @param tauSec EWMA time constant (larger = smoother, laggier) */
  constructor(private readonly tauSec = 30, private readonly minSpeed = 0.5) {}

  update(speedMps: number, currentGrade: number, dtSec: number): number {
    if (speedMps < this.minSpeed || dtSec <= 0) return this.vFlat; // ignore stops
    const sample = speedMps * gapFactor(currentGrade);
    if (!this.primed) {
      this.vFlat = sample;
      this.primed = true;
    } else {
      const alpha = 1 - Math.exp(-dtSec / this.tauSec);
      this.vFlat += alpha * (sample - this.vFlat);
    }
    return this.vFlat;
  }
  get flatSpeed(): number { return this.vFlat; }
  get ready(): boolean { return this.primed && this.vFlat > this.minSpeed; }
}

export interface EtaResult {
  etaRemainingSec: number | null; // null until pace is primed / when off-route
  etaEpochMs: number | null;
}

export function computeEta(
  effectiveRemainingM: number,
  pace: PaceEstimator,
  nowEpochMs: number,
  offRoute: boolean,
): EtaResult {
  if (offRoute || !pace.ready) return { etaRemainingSec: null, etaEpochMs: null };
  const sec = Math.round(effectiveRemainingM / pace.flatSpeed);
  return { etaRemainingSec: sec, etaEpochMs: nowEpochMs + sec * 1000 };
}

// ───────────────────────────── off-route detector ──────────────────────────
/** Debounced off-route detector: trips after `k` consecutive fixes beyond `thresholdM`. */
export class OffRouteDetector {
  private streak = 0;
  private state = false;
  constructor(private readonly thresholdM = 30, private readonly k = 3) {}
  update(crossTrackM: number): boolean {
    if (crossTrackM > this.thresholdM) {
      this.streak++;
      if (this.streak >= this.k) this.state = true;
    } else {
      this.streak = 0;
      this.state = false;
    }
    return this.state;
  }
  get offRoute(): boolean { return this.state; }
}

// ───────────────────────────── helpers ─────────────────────────────────────
function clamp(v: number, lo: number, hi: number): number {
  return v < lo ? lo : v > hi ? hi : v;
}
function dedupe(pts: RoutePoint[]): RoutePoint[] {
  const out: RoutePoint[] = [];
  for (const p of pts) {
    const last = out[out.length - 1];
    if (!last || haversine(last, p) > 0.5) out.push(p);
  }
  return out;
}
