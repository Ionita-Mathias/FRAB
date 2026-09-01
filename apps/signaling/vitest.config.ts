import { cloudflareTest } from '@cloudflare/vitest-plugin';
import { defineConfig } from 'vitest/config';

/**
 * Tests run inside workerd via @cloudflare/vitest-plugin, so Durable Objects,
 * WebSockets and alarms behave as they do in production.
 *
 * NOTE for anyone updating this: the pre-2026 API (`defineWorkersConfig`,
 * `poolOptions.workers`, `isolatedStorage`, `singleWorker`, and the
 * `@cloudflare/vitest-pool-workers/config` import path) does NOT work on the
 * current plugin — `cloudflareTest()` is a plain Vite plugin and the helper
 * exports live at the package root.
 */
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: './wrangler.jsonc' },
      // Test-only binding. Deliberately NOT in wrangler.jsonc: a webhook secret
      // belongs in `wrangler secret`, never in committed config.
      miniflare: { bindings: { WEBHOOK_SECRET: 'test-webhook-secret' } },
    }),
  ],
});
