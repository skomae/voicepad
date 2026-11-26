import { createReadStream, existsSync, statSync, mkdirSync, copyFileSync, readdirSync } from 'node:fs';
import { join, extname } from 'node:path';
import { defineConfig } from 'vite';
import { VitePWA } from 'vite-plugin-pwa';

const ROOT = process.cwd();
const ORT_DIR = join(ROOT, 'ort');

const MIME: Record<string, string> = {
  '.wasm': 'application/wasm',
  '.mjs': 'text/javascript',
  '.js': 'text/javascript',
};

/**
 * Serves onnxruntime-web's WASM runtime from /ort instead of public/.
 * Vite refuses to let source code import files that live in public/, and
 * onnxruntime loads its .mjs glue with a dynamic import — so the files are
 * kept in a plain directory, served by middleware in dev and copied into
 * dist/ort at build time.
 */
function ortRuntime() {
  const serve = (req: any, res: any, next: any) => {
    const url: string = req.url ?? '';
    if (!url.startsWith('/ort/')) return next();
    const file = join(ORT_DIR, url.slice(5).split('?')[0]);
    if (!existsSync(file) || !statSync(file).isFile()) return next();
    res.setHeader('Content-Type', MIME[extname(file)] ?? 'application/octet-stream');
    res.setHeader('Cache-Control', 'public, max-age=31536000, immutable');
    createReadStream(file).pipe(res);
  };
  return {
    name: 'voicepad-ort-runtime',
    configureServer(server: any) {
      server.middlewares.use(serve);
    },
    configurePreviewServer(server: any) {
      server.middlewares.use(serve);
    },
    closeBundle() {
      if (!existsSync(ORT_DIR)) return;
      const dest = join(ROOT, 'dist/ort');
      mkdirSync(dest, { recursive: true });
      for (const f of readdirSync(ORT_DIR)) copyFileSync(join(ORT_DIR, f), join(dest, f));
    },
  };
}

export default defineConfig({
  server: { host: true, port: 5173 },
  preview: { host: true, port: 4173 },
  build: { target: 'es2022' },
  plugins: [
    ortRuntime(),
    VitePWA({
      registerType: 'autoUpdate',
      includeAssets: ['icons/icon-192.png', 'icons/icon-512.png', 'silero_vad.onnx'],
      manifest: {
        name: 'VoicePad',
        short_name: 'VoicePad',
        description: 'On-device voice notes — recording starts, text follows.',
        start_url: '/',
        scope: '/',
        display: 'standalone',
        orientation: 'portrait',
        background_color: '#000000',
        theme_color: '#000000',
        icons: [
          { src: '/icons/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icons/icon-512.png', sizes: '512x512', type: 'image/png' },
          { src: '/icons/icon-maskable-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      },
      workbox: {
        globPatterns: ['**/*.{js,css,html,png,svg,onnx,json,webmanifest,wav}'],
        globIgnores: ['**/ort-wasm-*', '**/sw.js', '**/workbox-*.js'],
        navigateFallback: '/index.html',
        runtimeCaching: [
          {
            // onnxruntime's WASM runtime (ours under /ort, plus the copy the
            // transformers bundle references). Big, immutable, needed offline.
            urlPattern: /\/(ort|assets)\/ort-wasm-[^/]+\.(wasm|mjs)$/,
            handler: 'CacheFirst',
            options: {
              cacheName: 'ort-runtime',
              expiration: { maxEntries: 12 },
              cacheableResponse: { statuses: [200] },
            },
          },
        ],
      },
    }),
  ],
});
