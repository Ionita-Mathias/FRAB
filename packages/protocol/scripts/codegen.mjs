/**
 * Generates TypeScript bindings from telemetry.proto.
 *
 * Uses protobufjs-cli (pbjs/pbts), which parses .proto in pure JavaScript — no
 * `protoc` toolchain required, so `pnpm codegen` works on any machine and in CI
 * without a native install step.
 *
 * The mobile targets use the protoc-based generators (swift-protobuf, wire)
 * against this SAME .proto; cross-language agreement is enforced by the golden
 * vectors in test/golden-vectors.json rather than by a shared generator.
 */
import { mkdirSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import pbjs from 'protobufjs-cli/pbjs.js';
import pbts from 'protobufjs-cli/pbts.js';

const here = dirname(fileURLToPath(import.meta.url));
const pkg = resolve(here, '..');
const proto = resolve(pkg, 'telemetry.proto');
const outDir = resolve(pkg, 'src/gen');
const outJs = resolve(outDir, 'telemetry.js');
const outDts = resolve(outDir, 'telemetry.d.ts');

mkdirSync(outDir, { recursive: true });

const run = (tool, args) =>
  new Promise((res, rej) =>
    tool.main(args, (err) => (err ? rej(err) : res())),
  );

await run(pbjs, [
  '--target', 'static-module',
  '--wrap', 'es6',
  '--force-number',       // int64 -> number (our values are well within 2^53)
  '--no-create',
  '--no-verify',
  '--no-convert',
  '--no-delimited',
  '--out', outJs,
  proto,
]);

await run(pbts, ['--out', outDts, outJs]);

console.log(`[codegen] wrote ${outJs}`);
console.log(`[codegen] wrote ${outDts}`);
