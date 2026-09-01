/**
 * RateLimiterDurableObject — one instance per limiter key (e.g. client IP).
 *
 * Gives a single global counter with smooth bursts and a real retry-after,
 * which the native per-colo fixed-window binding cannot (see ratelimit.ts).
 *
 * State is a single small record; an alarm evicts it once the bucket has had
 * time to refill completely, so idle keys do not accumulate storage.
 */

import { DurableObject } from 'cloudflare:workers';
import { consume, type BucketConfig, type BucketState } from './ratelimit.js';

export class RateLimiterDurableObject extends DurableObject {
  async limit(cfg: BucketConfig, cost = 1): Promise<{ allowed: boolean; retryAfterSec: number }> {
    const now = Date.now();
    const prev = await this.ctx.storage.get<BucketState>('bucket');
    const decision = consume(prev, cfg, now, cost);
    await this.ctx.storage.put('bucket', decision.state);

    // Once a full refill has elapsed the bucket is indistinguishable from
    // absent, so schedule its removal rather than retaining a row forever.
    const fullRefillMs = cfg.refillPerSec > 0 ? (cfg.capacity / cfg.refillPerSec) * 1000 : 60_000;
    await this.ctx.storage.setAlarm(now + Math.ceil(fullRefillMs) + 1000);

    return { allowed: decision.allowed, retryAfterSec: decision.retryAfterSec };
  }

  async alarm(): Promise<void> {
    await this.ctx.storage.deleteAll();
  }
}
