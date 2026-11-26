/**
 * Copies the browser assets the demo serves itself (so it works offline and
 * needs no CDN): the Silero VAD model from the Android app and onnxruntime's
 * WASM runtime files. Run automatically by `pnpm install`/`pnpm build`.
 */
import { copyFileSync, mkdirSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = dirname(fileURLToPath(import.meta.url)) + '/..';
const pub = join(root, 'public');
const ortDir = join(root, 'ort');
mkdirSync(join(pub), { recursive: true });
mkdirSync(ortDir, { recursive: true });

// 1. Silero VAD — the exact file the Android app bundles.
const vadSrc = join(root, '../android/app/src/main/assets/silero_vad.onnx');
if (existsSync(vadSrc)) {
  copyFileSync(vadSrc, join(pub, 'silero_vad.onnx'));
  console.log('copied silero_vad.onnx from the Android app');
} else {
  console.warn('Android silero_vad.onnx not found — leaving existing copy');
}

// 2. Bergamot translator worker (model files come from scripts/fetch-models.mjs).
const bergamotSrc = join(root, 'node_modules/@browsermt/bergamot-translator/worker');
if (existsSync(bergamotSrc)) {
  const dst = join(root, 'bergamot');
  mkdirSync(dst, { recursive: true });
  for (const f of readdirSync(bergamotSrc)) copyFileSync(join(bergamotSrc, f), join(dst, f));
  console.log('copied bergamot worker to bergamot/');
}

// 3. onnxruntime-web WASM runtime (glue + binaries).
const ortDist = join(root, 'node_modules/onnxruntime-web/dist');
if (existsSync(ortDist)) {
  let n = 0;
  for (const f of readdirSync(ortDist)) {
    if (/^ort-wasm.*\.(wasm|mjs|js)$/.test(f)) {
      copyFileSync(join(ortDist, f), join(ortDir, f));
      n++;
    }
  }
  console.log(`copied ${n} onnxruntime-web files to ort/`);
} else {
  console.warn('onnxruntime-web not installed yet');
}
