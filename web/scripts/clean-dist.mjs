/**
 * Post-build tidy: Vite emits both onnxruntime WASM variants because the
 * package references them by URL, but the app only ever loads the jsep build
 * (transformers) and our own /ort copy. Dropping the unused ones keeps the
 * deployable dist small.
 */
import { readdirSync, rmSync } from 'node:fs';
import { join } from 'node:path';

const assets = join(process.cwd(), 'dist/assets');
let removed = 0;
for (const f of readdirSync(assets)) {
  if (/^ort-wasm-simd-threaded\.asyncify-.*\.wasm$/.test(f)) {
    rmSync(join(assets, f));
    removed++;
  }
}
console.log(`clean-dist: removed ${removed} unused wasm asset(s)`);
