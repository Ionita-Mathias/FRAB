/**
 * LiveTrackSuunto — wire <-> domain codec.
 *
 * The wire format (generated from telemetry.proto) uses scaled integers and
 * session-relative timestamps for compactness; the domain model
 * (`./types.ts`) uses human units and explicit `null` for "unknown". This
 * module is the single place those two representations meet.
 *
 * Sentinel conventions (proto3 has no field presence for scalars, so the
 * zero-value doubles as "absent"):
 *   hAccM/vAccM  0  -> null      speedMps  < 0 -> null
 *   courseDeg   < 0 -> null      hrBpm     0   -> null
 *   cadenceRpm   0  -> null      powerW    0   -> null
 *   paceSPerKm  <=0 -> null      battPct   0   -> null
 */
import { livetrack } from './gen/telemetry.js';
import type {
  ChannelMessage,
  LiveMetrics,
  RouteMeta,
  RouteProgress,
  RouteVertex,
  SessionEnd,
  SessionHello,
  SessionState,
  Source,
  TelemetrySources,
  TransportMode,
} from './types.js';

const V1 = livetrack.v1;
type PbEnvelope = livetrack.v1.Envelope.$Properties;

const E7 = 1e7;
const DM = 10; // decimeters per meter

export const PROTOCOL_VERSION = 1;

// ───────────────────────────── enum mapping ────────────────────────────────

const SOURCE_TO_PB: Record<Source, number> = {
  unknown: V1.Source.SOURCE_UNKNOWN,
  'phone-gps': V1.Source.SOURCE_PHONE_GPS,
  'watch-broadcast': V1.Source.SOURCE_WATCH_BROADCAST,
  'ble-sensor': V1.Source.SOURCE_BLE_SENSOR,
  healthkit: V1.Source.SOURCE_HEALTHKIT,
  'health-services': V1.Source.SOURCE_HEALTH_SERVICES,
  derived: V1.Source.SOURCE_DERIVED,
};
const PB_TO_SOURCE = invert(SOURCE_TO_PB, 'unknown' as Source);

const TRANSPORT_TO_PB: Record<TransportMode, number> = {
  unknown: V1.TransportMode.TRANSPORT_UNKNOWN,
  'p2p-direct': V1.TransportMode.TRANSPORT_P2P_DIRECT,
  'p2p-turn': V1.TransportMode.TRANSPORT_P2P_TURN,
  'edge-fanout': V1.TransportMode.TRANSPORT_EDGE_FANOUT,
};
const PB_TO_TRANSPORT = invert(TRANSPORT_TO_PB, 'unknown' as TransportMode);

const END_TO_PB: Record<SessionEnd['reason'], number> = {
  unknown: V1.EndReason.END_UNKNOWN,
  completed: V1.EndReason.END_COMPLETED,
  revoked: V1.EndReason.END_REVOKED,
  expired: V1.EndReason.END_EXPIRED,
  error: V1.EndReason.END_ERROR,
};
const PB_TO_END = invert(END_TO_PB, 'unknown' as SessionEnd['reason']);

function invert<K extends string>(map: Record<K, number>, fallback: K) {
  const out = new Map<number, K>();
  for (const k of Object.keys(map) as K[]) out.set(map[k], k);
  return (v: number | null | undefined): K => out.get(v ?? -1) ?? fallback;
}

// ───────────────────────────── encoding ────────────────────────────────────

/**
 * Encodes domain messages into serialized `Envelope` bytes.
 *
 * Bound to a session `t0` so telemetry timestamps ride the wire as small
 * session-relative varints instead of 13-digit epoch values.
 */
export class Encoder {
  constructor(private readonly t0EpochMs: number) {}

  hello(h: SessionHello): Uint8Array {
    return envelope({
      hello: {
        sessionId: h.sessionId,
        t0EpochMs: h.t0EpochMs,
        sport: h.sport,
        emitterAgent: h.emitterAgent,
        hasRoute: h.hasRoute,
        sampleHz: h.sampleHz,
      },
    });
  }

  route(r: RouteMeta): Uint8Array {
    return envelope({ route: encodeRouteMeta(r) });
  }

  telemetry(m: LiveMetrics): Uint8Array {
    return envelope({ telemetry: encodeTelemetry(m, this.t0EpochMs) });
  }

  state(s: SessionState): Uint8Array {
    return envelope({
      state: {
        transport: TRANSPORT_TO_PB[s.transport],
        viewerCount: s.viewerCount,
        emitterLive: s.emitterLive,
      },
    });
  }

  heartbeat(tMs: number): Uint8Array {
    return envelope({ heartbeat: { tMs } });
  }

  end(e: SessionEnd): Uint8Array {
    return envelope({ end: { reason: END_TO_PB[e.reason], detail: e.detail } });
  }
}

/**
 * Serializes an Envelope into a standalone, exactly-sized Uint8Array.
 *
 * protobufjs' `finish()` hands back a view into a shared pool buffer (a Node
 * `Buffer` when available), so its `.buffer` spans far more than the message.
 * Callers routinely pass these bytes straight to WebCrypto / a DataChannel,
 * both of which read the underlying buffer — so we detach with an explicit copy
 * exactly once, here, rather than trusting every call site to remember.
 */
function envelope(body: Omit<PbEnvelope, 'v'>): Uint8Array {
  const pooled = V1.Envelope.encode({ v: PROTOCOL_VERSION, ...body }).finish();
  const out = new Uint8Array(pooled.byteLength);
  out.set(pooled);
  return out;
}

function encodeTelemetry(m: LiveMetrics, t0: number): livetrack.v1.TelemetryFrame.$Properties {
  return {
    seq: m.seq,
    tMs: Math.max(0, Math.round(m.timestamp - t0)),
    latE7: Math.round(m.position.lat * E7),
    lonE7: Math.round(m.position.lon * E7),
    altM: m.position.altM,
    hAccM: m.position.hAccM ?? 0,
    vAccM: 0,
    speedMps: m.speedMps ?? -1,
    courseDeg: m.courseDeg ?? -1,
    hrBpm: m.heartRateBpm ?? 0,
    cadenceRpm: m.cadenceRpm ?? 0,
    powerW: m.powerW ?? 0,
    paceSPerKm: m.paceSecPerKm ?? 0,
    gainM: m.elevationGainM,
    lossM: m.elevationLossM,
    route: m.route ? encodeRouteProgress(m.route) : null,
    battPct: m.batteryPct ?? 0,
    src: {
      position: SOURCE_TO_PB[m.sources.position],
      heartRate: SOURCE_TO_PB[m.sources.heartRate],
      cadence: SOURCE_TO_PB[m.sources.cadence],
      power: SOURCE_TO_PB[m.sources.power],
    },
  };
}

function encodeRouteProgress(r: RouteProgress): livetrack.v1.RouteProgress.$Properties {
  return {
    distDoneM: r.distanceDoneM,
    distRemainingM: r.distanceRemainingM,
    ascentRemainingM: r.ascentRemainingM,
    descentRemainingM: r.descentRemainingM,
    etaEpochMs: r.etaEpochMs ?? 0,
    etaRemainingS: r.etaRemainingSec ?? 0,
    fraction: r.fraction,
    segIndex: r.segIndex,
    offRoute: r.offRoute,
    crossTrackM: r.crossTrackM,
  };
}

/** Delta-encodes the planned polyline (the single largest message we send). */
function encodeRouteMeta(r: RouteMeta): livetrack.v1.RouteMeta.$Properties {
  const pts = r.points;
  const first = pts[0] ?? { lat: 0, lon: 0, eleM: 0 };
  const firstLatE7 = Math.round(first.lat * E7);
  const firstLonE7 = Math.round(first.lon * E7);
  const firstEleDm = Math.round(first.eleM * DM);

  const dlat: number[] = [];
  const dlon: number[] = [];
  const dele: number[] = [];
  let pLat = firstLatE7;
  let pLon = firstLonE7;
  let pEle = firstEleDm;
  for (let i = 1; i < pts.length; i++) {
    const p = pts[i]!;
    const la = Math.round(p.lat * E7);
    const lo = Math.round(p.lon * E7);
    const el = Math.round(p.eleM * DM);
    dlat.push(la - pLat);
    dlon.push(lo - pLon);
    dele.push(el - pEle);
    pLat = la;
    pLon = lo;
    pEle = el;
  }

  return {
    routeId: r.routeId,
    name: r.name,
    totalDistM: r.totalDistM,
    totalAscentM: r.totalAscentM,
    totalDescentM: r.totalDescentM,
    firstLatE7: firstLatE7,
    firstLonE7: firstLonE7,
    firstEleDm: firstEleDm,
    dlatE7: dlat,
    dlonE7: dlon,
    deleDm: dele,
    bbox: {
      minLatE7: Math.round(r.bbox.minLat * E7),
      minLonE7: Math.round(r.bbox.minLon * E7),
      maxLatE7: Math.round(r.bbox.maxLat * E7),
      maxLonE7: Math.round(r.bbox.maxLon * E7),
    },
  };
}

// ───────────────────────────── decoding ────────────────────────────────────

/**
 * Decodes serialized `Envelope` bytes into a discriminated domain message.
 *
 * `t0` is learned from the `hello` message, so a decoder can be constructed
 * before it is known and telemetry timestamps still resolve to absolute epochs.
 */
export class Decoder {
  private t0 = 0;

  constructor(t0EpochMs?: number) {
    if (t0EpochMs !== undefined) this.t0 = t0EpochMs;
  }

  get t0EpochMs(): number {
    return this.t0;
  }

  decode(bytes: Uint8Array): ChannelMessage {
    const env = V1.Envelope.decode(bytes);

    if (env.hello) {
      const h = env.hello;
      const hello: SessionHello = {
        sessionId: h.sessionId ?? '',
        t0EpochMs: num(h.t0EpochMs),
        sport: h.sport ?? '',
        emitterAgent: h.emitterAgent ?? '',
        hasRoute: !!h.hasRoute,
        sampleHz: num(h.sampleHz),
      };
      this.t0 = hello.t0EpochMs; // subsequent telemetry is relative to this
      return { kind: 'hello', hello };
    }

    if (env.route) return { kind: 'route', route: decodeRouteMeta(env.route) };

    if (env.telemetry) {
      return { kind: 'telemetry', metrics: decodeTelemetry(env.telemetry, this.t0) };
    }

    if (env.state) {
      const s = env.state;
      const state: SessionState = {
        transport: PB_TO_TRANSPORT(s.transport),
        viewerCount: num(s.viewerCount),
        emitterLive: !!s.emitterLive,
      };
      return { kind: 'state', state };
    }

    if (env.heartbeat) return { kind: 'heartbeat', tMs: num(env.heartbeat.tMs) };

    if (env.end) {
      const end: SessionEnd = {
        reason: PB_TO_END(env.end.reason),
        detail: env.end.detail ?? '',
      };
      return { kind: 'end', end };
    }

    throw new Error('Envelope has no recognized body — unsupported message type');
  }
}

function decodeTelemetry(
  f: livetrack.v1.TelemetryFrame.$Properties,
  t0: number,
): LiveMetrics {
  const hAcc = num(f.hAccM);
  const speed = num(f.speedMps, -1);
  const course = num(f.courseDeg, -1);
  const pace = num(f.paceSPerKm);
  const src = f.src;

  return {
    seq: num(f.seq),
    timestamp: t0 + num(f.tMs),
    position: {
      lat: num(f.latE7) / E7,
      lon: num(f.lonE7) / E7,
      altM: num(f.altM),
      hAccM: hAcc > 0 ? hAcc : null,
    },
    speedMps: speed >= 0 ? speed : null,
    courseDeg: course >= 0 ? course : null,
    heartRateBpm: nz(f.hrBpm),
    cadenceRpm: nz(f.cadenceRpm),
    powerW: nz(f.powerW),
    paceSecPerKm: pace > 0 ? pace : null,
    elevationGainM: num(f.gainM),
    elevationLossM: num(f.lossM),
    route: f.route ? decodeRouteProgress(f.route) : null,
    batteryPct: nz(f.battPct),
    sources: decodeSources(src),
  };
}

function decodeSources(
  s: livetrack.v1.SourceFlags.$Properties | null | undefined,
): TelemetrySources {
  return {
    position: PB_TO_SOURCE(s?.position),
    heartRate: PB_TO_SOURCE(s?.heartRate),
    cadence: PB_TO_SOURCE(s?.cadence),
    power: PB_TO_SOURCE(s?.power),
  };
}

function decodeRouteProgress(
  r: livetrack.v1.RouteProgress.$Properties,
): RouteProgress {
  const eta = num(r.etaEpochMs);
  const etaS = num(r.etaRemainingS);
  return {
    distanceDoneM: num(r.distDoneM),
    distanceRemainingM: num(r.distRemainingM),
    ascentRemainingM: num(r.ascentRemainingM),
    descentRemainingM: num(r.descentRemainingM),
    etaEpochMs: eta > 0 ? eta : null,
    etaRemainingSec: etaS > 0 ? etaS : null,
    fraction: num(r.fraction),
    segIndex: num(r.segIndex),
    offRoute: !!r.offRoute,
    crossTrackM: num(r.crossTrackM),
  };
}

function decodeRouteMeta(r: livetrack.v1.RouteMeta.$Properties): RouteMeta {
  const points: RouteVertex[] = [];
  let lat = num(r.firstLatE7);
  let lon = num(r.firstLonE7);
  let ele = num(r.firstEleDm);
  const dlat = r.dlatE7 ?? [];
  const dlon = r.dlonE7 ?? [];
  const dele = r.deleDm ?? [];

  points.push({ lat: lat / E7, lon: lon / E7, eleM: ele / DM });
  const n = Math.min(dlat.length, dlon.length, dele.length);
  for (let i = 0; i < n; i++) {
    lat += dlat[i]!;
    lon += dlon[i]!;
    ele += dele[i]!;
    points.push({ lat: lat / E7, lon: lon / E7, eleM: ele / DM });
  }

  return {
    routeId: r.routeId ?? '',
    name: r.name ?? '',
    totalDistM: num(r.totalDistM),
    totalAscentM: num(r.totalAscentM),
    totalDescentM: num(r.totalDescentM),
    points,
    bbox: {
      minLat: num(r.bbox?.minLatE7) / E7,
      minLon: num(r.bbox?.minLonE7) / E7,
      maxLat: num(r.bbox?.maxLatE7) / E7,
      maxLon: num(r.bbox?.maxLonE7) / E7,
    },
  };
}

// ───────────────────────────── helpers ─────────────────────────────────────

/** protobuf.js decodes 64-bit fields into this shape when `long` is installed. */
interface LongLike {
  low: number;
  high: number;
  unsigned?: boolean;
}

function isLongLike(v: unknown): v is LongLike {
  return typeof v === 'object' && v !== null && 'low' in v && 'high' in v;
}

/**
 * Coerces a decoded protobuf scalar to a JS number.
 *
 * `uint64` fields (t_ms, t0_epoch_ms, eta_epoch_ms) come back as `Long`
 * objects whenever protobuf.js finds the `long` library — even though the
 * generated .d.ts declares them as `number`. Accepting `unknown` keeps this
 * honest about the runtime shape instead of trusting that declaration.
 * All our 64-bit values are epoch-ms or durations, comfortably below 2^53.
 */
function num(v: unknown, dflt = 0): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : dflt;
  if (isLongLike(v)) {
    const lo = v.low >>> 0;
    // unsigned (the default for our uint64 fields) must not sign-extend `high`
    return v.unsigned === false ? v.high * 4294967296 + lo : (v.high >>> 0) * 4294967296 + lo;
  }
  return dflt;
}

/** proto3 zero-value means "unknown" for these optional scalars. */
function nz(v: unknown): number | null {
  const n = num(v);
  return n !== 0 ? n : null;
}
