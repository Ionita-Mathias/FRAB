# @livetrack/geo

GPX route preprocessing, live-fix snapping, and grade-adjusted dynamic ETA.

Dependency-free and V8-only, so the **identical code runs in the browser viewer
and in a Cloudflare Durable Object** — a viewer sees the same numbers whether the
frame arrived peer-to-peer or through the edge fan-out.

Full mathematical specification:
[`docs/phase3/03-gpx-snap-and-eta-algorithm.md`](../../docs/phase3/03-gpx-snap-and-eta-algorithm.md).

## Pipeline

```ts
import {
  prepareRoute, snapToRoute, computeRemaining,
  computeEta, PaceEstimator, OffRouteDetector,
} from '@livetrack/geo';

const route = prepareRoute(gpxPoints);        // once: cumulative distance + elevation profile
const pace = new PaceEstimator(30);           // EWMA of flat-equivalent speed
const off = new OffRouteDetector(30, 3);      // 30 m corridor, 3 consecutive fixes

let seg = 0;
for (const fix of liveFixes) {
  const snap = snapToRoute(route, fix, seg);  // windowed search, global re-acquire on gaps
  seg = snap.segIndex;
  const rem = computeRemaining(route, snap);
  const isOff = off.update(snap.crossTrackM);
  pace.update(fix.speedMps, route.grade[seg] ?? 0, dtSec);
  const eta = computeEta(rem.effectiveRemainingM, pace, Date.now(), isOff);
}
```

## Design notes

- **Snapping** projects onto segments in a local equirectangular frame (exact at
  tens of meters, far cheaper than repeated haversine); segment lengths use
  haversine because they accumulate over the whole route.
- **Windowed search** looks a bounded distance ahead of the last match, so
  out-and-back and self-crossing routes do not teleport the athlete to the wrong
  leg. A global rescan happens only when the best local match exceeds 60 m.
- **ETA is effort-normalized** via the Minetti (2002) cost-of-running curve:
  remaining distance is converted to flat-equivalent meters, and pace is tracked
  as flat-equivalent speed. The ETA therefore slows for climbs *still ahead*
  rather than reacting only after the athlete hits them.
- **ETA is suppressed off-route**, mirroring the watch's own behavior instead of
  emitting a confidently wrong number.
- **Elevation hysteresis** (1 m default) keeps barometer noise out of cumulative
  ascent.

## Scripts

```sh
pnpm typecheck
pnpm test
```
