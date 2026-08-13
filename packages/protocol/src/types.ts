/**
 * LiveTrackSuunto — shared DOMAIN model (decoded, human units).
 *
 * These interfaces are the ergonomic runtime shape used by the web viewer and
 * (mirrored) by the mobile app. They are DISTINCT from the generated protobuf
 * wire types (packages/protocol/src/gen), which use scaled integers for size.
 * `codec.ts` maps wire <-> domain. Keep this file 1:1 with telemetry.proto.
 */

export type Source =
  | 'unknown'
  | 'phone-gps'
  | 'watch-broadcast'
  | 'ble-sensor'
  | 'healthkit'
  | 'health-services'
  | 'derived';

export type TransportMode =
  | 'unknown'
  | 'p2p-direct'
  | 'p2p-turn'
  | 'edge-fanout';

export type EndReason = 'unknown' | 'completed' | 'revoked' | 'expired' | 'error';

export interface GeoPoint {
  /** decimal degrees, WGS84 */
  lat: number;
  lon: number;
  /** meters, fused baro/GPS */
  altM: number;
  /** horizontal accuracy, meters; null if unknown */
  hAccM: number | null;
}

export interface TelemetrySources {
  position: Source;
  heartRate: Source;
  cadence: Source;
  power: Source;
}

export interface RouteProgress {
  distanceDoneM: number;
  distanceRemainingM: number;
  ascentRemainingM: number;
  descentRemainingM: number;
  /** absolute predicted finish (epoch ms); null when off-route / unknown */
  etaEpochMs: number | null;
  /** seconds remaining; null when unknown */
  etaRemainingSec: number | null;
  /** 0..1 progress along the planned route */
  fraction: number;
  /** snapped polyline segment index */
  segIndex: number;
  offRoute: boolean;
  /** perpendicular distance to the route, meters */
  crossTrackM: number;
}

/** One decoded ~1 Hz sample — the core payload the metrics card renders. */
export interface LiveMetrics {
  seq: number;
  /** absolute capture time, epoch ms (t0 + t_ms) */
  timestamp: number;
  position: GeoPoint;
  /** ground speed, m/s; null if unknown */
  speedMps: number | null;
  /** heading, 0..360 true north; null if unknown */
  courseDeg: number | null;
  heartRateBpm: number | null;
  /** spm (run) or rpm (bike) */
  cadenceRpm: number | null;
  powerW: number | null;
  /** smoothed pace, seconds per km; null when stopped/unknown */
  paceSecPerKm: number | null;
  /** cumulative session ascent/descent, meters */
  elevationGainM: number;
  elevationLossM: number;
  /** null when no route is loaded */
  route: RouteProgress | null;
  batteryPct: number | null;
  sources: TelemetrySources;
}

/** Sent once when the DataChannel opens. */
export interface SessionHello {
  sessionId: string; // UUIDv4
  t0EpochMs: number;
  sport: string;
  emitterAgent: string;
  hasRoute: boolean;
  sampleHz: number;
}

/**
 * A single planned-route vertex, reconstructed from the delta arrays.
 *
 * This is the RAW decoded geometry. Cumulative distance/ascent profiles are not
 * transmitted — the receiver derives them by passing these points through
 * `prepareRoute()` in `@livetrack/geo`, which yields a `PreparedRoute`. Keeping
 * them out of the wire format saves bandwidth and guarantees the emitter and
 * every viewer compute the profile with identical code.
 */
export interface RouteVertex {
  lat: number;
  lon: number;
  eleM: number;
}

export interface RouteMeta {
  routeId: string;
  name: string;
  totalDistM: number;
  totalAscentM: number;
  totalDescentM: number;
  /** decoded, absolute planned polyline */
  points: RouteVertex[];
  bbox: { minLat: number; minLon: number; maxLat: number; maxLon: number };
}

export interface SessionState {
  transport: TransportMode;
  viewerCount: number;
  emitterLive: boolean;
}

export interface SessionEnd {
  reason: EndReason;
  detail: string;
}

/** Discriminated union of everything that can arrive on the channel. */
export type ChannelMessage =
  | { kind: 'hello'; hello: SessionHello }
  | { kind: 'route'; route: RouteMeta }
  | { kind: 'telemetry'; metrics: LiveMetrics }
  | { kind: 'state'; state: SessionState }
  | { kind: 'heartbeat'; tMs: number }
  | { kind: 'end'; end: SessionEnd };
