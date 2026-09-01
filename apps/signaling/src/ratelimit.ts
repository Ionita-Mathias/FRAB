/**
 * Token-bucket rate limiting.
 *
 * Deliberately NOT Cloudflare's native `ratelimit` binding, for three reasons
 * that matter here:
 *   - it is enforced PER CLOUDFLARE LOCATION, so a configured 100/min is
 *     ~100/min per colo and an attacker spread across regions gets a multiple
 *     of the intended budget;
 *   - it is a wall-clock-aligned FIXED window, so a caller can push 2x the
 *     limit across a boundary (N at t=59s, N more at t=61s);
 *   - `limit()` returns only `{ success }`, so a correct `Retry-After` header
 *     cannot be built from it.
 * A Durable Object gives one global counter, smooth bursts, and a real
 * retry-after. The cost is one extra round trip per limited request.
 *
 * The bucket itself is a pure function of (state, now) so it can be unit
 * tested without any Workers runtime.
 */

export interface BucketState {
  /** Tokens available at `updatedAtMs`. */
  tokens: number;
  updatedAtMs: number;
}

export interface BucketConfig {
  /** Maximum tokens (burst size). */
  capacity: number;
  /** Tokens refilled per second. */
  refillPerSec: number;
}

export interface BucketDecision {
  allowed: boolean;
  state: BucketState;
  /** Seconds until one token is available; 0 when allowed. */
  retryAfterSec: number;
}

/** Consume one token, refilling by elapsed time first. */
export function consume(
  state: BucketState | undefined,
  cfg: BucketConfig,
  nowMs: number,
  cost = 1,
): BucketDecision {
  const prev: BucketState = state ?? { tokens: cfg.capacity, updatedAtMs: nowMs };

  // Refill for elapsed time. Clock skew (nowMs < updatedAtMs) must not mint
  // tokens, so elapsed is floored at zero.
  const elapsedSec = Math.max(0, (nowMs - prev.updatedAtMs) / 1000);
  const tokens = Math.min(cfg.capacity, prev.tokens + elapsedSec * cfg.refillPerSec);

  if (tokens >= cost) {
    return {
      allowed: true,
      state: { tokens: tokens - cost, updatedAtMs: nowMs },
      retryAfterSec: 0,
    };
  }

  const deficit = cost - tokens;
  const retryAfterSec = cfg.refillPerSec > 0 ? Math.ceil(deficit / cfg.refillPerSec) : 3600;
  return {
    allowed: false,
    state: { tokens, updatedAtMs: nowMs },
    retryAfterSec: Math.max(1, retryAfterSec),
  };
}
