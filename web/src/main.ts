import './styles.css';
import { MicCapture, SampleRing, decodeAudioFile, SAMPLE_RATE, FRAME } from './audio';
import { SileroVad, UtteranceTracker, type Span } from './vad';
import { SpeechEngine, MODEL, webgpuAvailable, type Device } from './asr';
import { noteStore, newNote, noteText, type Note, type Segment } from './notes';
import { encodeWav, toInt16, formatDuration, formatDate } from './wav';

// ---------------------------------------------------------------- elements

const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const el = {
  app: $('app'),
  statusDot: $('statusDot'),
  statusLine: $('statusLine'),
  modelStrip: $('modelStrip'),
  modelBar: $('modelBar'),
  modelLabel: $('modelLabel'),
  introWrap: $('introWrap'),
  introText: $('introText'),
  introFoot: $('introFoot'),
  storeLink: $<HTMLAnchorElement>('storeLink'),
  sampleBtn: $<HTMLButtonElement>('sampleBtn'),
  bubbles: $('bubbles'),
  transcript: $('transcript'),
  liveBubble: $('liveBubble'),
  recordBtn: $<HTMLButtonElement>('recordBtn'),
  recordGlyph: $('recordGlyph'),
  splitBtn: $<HTMLButtonElement>('splitBtn'),
  importBtn: $<HTMLButtonElement>('importBtn'),
  recStatus: $('recStatus'),
  recMeta: $('recMeta'),
  notesBtn: $('notesBtn'),
  settingsBtn: $('settingsBtn'),
  notesSheet: $('notesSheet'),
  notesList: $('notesList'),
  notesCount: $('notesCount'),
  noteSheet: $('noteSheet'),
  noteTitle: $('noteTitle'),
  noteBody: $('noteBody'),
  noteActions: $('noteActions'),
  settingsSheet: $('settingsSheet'),
  settingsBody: $('settingsBody'),
  modelName: $('modelName'),
  modelDetail: $('modelDetail'),
  deviceName: $('deviceName'),
  deviceDetail: $('deviceDetail'),
  storageDetail: $('storageDetail'),
  clearAllBtn: $<HTMLButtonElement>('clearAllBtn'),
  scrim: $('scrim'),
  toast: $('toast'),
  fileInput: $<HTMLInputElement>('fileInput'),
  installBtn: $<HTMLButtonElement>('installBtn'),
};

const VAD_URL = '/silero_vad.onnx';

/**
 * Copy for the introduction bubble — the one message the demo "says" to a new
 * visitor. Keep it in one place so it can be edited without touching markup.
 */
const INTRO_TEXT = `VoicePad turns speech into notes without sending a word off the device: tap record and talk, pauses become new bubbles, and every note is saved with its audio. The app adds translation, bigger models and background recording — this is the browser demo.`;

/**
 * Store listing for the full app. Leave empty until it is live: the intro then
 * says "in the app stores soon" instead of showing a dead link.
 */
const STORE_URL = '';
const SAMPLE = SAMPLE_RATE;

// ---------------------------------------------------------------- state

type Phase = 'boot' | 'loading' | 'ready' | 'recording' | 'importing' | 'error';

interface UiState {
  phase: Phase;
  progress: number;
  device: Device;
  error: string | null;
  liveText: string;
  elapsed: number;
  busyCount: number;
}

const ui: UiState = {
  phase: 'boot',
  progress: -1,
  device: 'wasm',
  error: null,
  liveText: '',
  elapsed: 0,
  busyCount: 0,
};

let engine: SpeechEngine | null = null;
let enginePromise: Promise<SpeechEngine> | null = null;
let vad: SileroVad | null = null;
let notes: Note[] = [];
let displayed: Note | null = null;

// Serialises model calls: Whisper decoding is one job at a time.
let engineChain: Promise<unknown> = Promise.resolve();
let engineJobs = 0;

function runEngine<T>(fn: () => Promise<T>): Promise<T> {
  engineJobs++;
  const next = engineChain.then(fn, fn);
  engineChain = next.catch(() => {});
  return next.finally(() => {
    engineJobs--;
  });
}

const toastTimer = { id: 0 };
function toast(message: string) {
  el.toast.textContent = message;
  el.toast.hidden = false;
  clearTimeout(toastTimer.id);
  toastTimer.id = window.setTimeout(() => (el.toast.hidden = true), 2600);
}

function setPhase(phase: Phase) {
  ui.phase = phase;
  el.app.dataset.state = phase;
  renderStatus();
}

// ---------------------------------------------------------------- engine

async function ensureEngine(): Promise<SpeechEngine> {
  if (engine) return engine;
  if (!enginePromise) {
    const pref = (localStorage.getItem('vp.device') as Device | null) ?? 'wasm';
    const progress = ({ progress }: { progress: number }) => {
      ui.progress = progress;
      renderStatus();
    };
    enginePromise = SpeechEngine.load(progress, pref)
      .catch(async (err) => {
        // WebGPU is present but unusable (no fp16, driver bugs): fall back.
        if (pref !== 'wasm') {
          localStorage.setItem('vp.device', 'wasm');
          ui.progress = 0;
          return SpeechEngine.load(progress, 'wasm');
        }
        throw err;
      })
      .then((e) => {
        engine = e;
        ui.device = e.device;
        ui.progress = 1;
        el.modelStrip.hidden = true;
        renderSettings();
        if (ui.phase !== 'recording' && ui.phase !== 'importing') setPhase('ready');
        renderStatus();
        return e;
      })
      .catch((err) => {
        enginePromise = null;
        ui.error = err?.message ?? String(err);
        setPhase('error');
        throw err;
      });
  }
  setPhase('loading');
  renderStatus();
  return enginePromise;
}

async function ensureVad(): Promise<SileroVad> {
  if (vad) return vad;
  vad = await SileroVad.create(VAD_URL);
  return vad;
}

// ---------------------------------------------------------------- session

interface Session {
  capture: MicCapture | null;
  ring: SampleRing;
  tracker: UtteranceTracker;
  vad: SileroVad;
  note: Note;
  noteStart: number; // absolute sample index where the current note begins
  chunks: Int16Array[];
  chains: Promise<void>;
  previewTimer: number;
  stopped: boolean;
}

let session: Session | null = null;

function segmentsOf(note: Note): Segment[] {
  return [...note.segments].sort((a, b) => a.start - b.start);
}

async function startRecording() {
  if (ui.phase === 'recording' || ui.phase === 'importing') return;
  try {
    void ensureEngine().catch(() => {});
    const detector = await ensureVad();
    const capture = await MicCapture.start((frame) => onFrame(frame));
    const note = newNote('mic');
    session = {
      capture,
      ring: new SampleRing(60),
      tracker: new UtteranceTracker(),
      vad: detector,
      note,
      noteStart: 0,
      chunks: [],
      chains: Promise.resolve(),
      previewTimer: 0,
      stopped: false,
    };
    displayed = note;
    detector.reset();
    ui.liveText = '';
    ui.elapsed = 0;
    ui.error = null;
    setPhase('recording');
    renderBubbles();
    renderLive();
    startPreviewLoop();
    startTimer();
    await noteStore.put(note);
  } catch (err: any) {
    const name = err?.name ?? '';
    if (name === 'NotAllowedError' || name === 'SecurityError') {
      toast('Microphone blocked — allow it in the address bar, over HTTPS.');
    } else if (name === 'NotFoundError') {
      toast('No microphone found on this device.');
    } else {
      toast(err?.message ?? 'Could not start the microphone.');
    }
  }
}

function onFrame(frame: Float32Array) {
  const s = session;
  if (!s) return;
  s.ring.push(frame);
  s.chunks.push(toInt16(frame));
  let energy = 0;
  for (let i = 0; i < frame.length; i++) energy += frame[i] * frame[i];
  s.capture?.pushLevel(Math.sqrt(energy / frame.length));
  el.recordBtn.style.setProperty('--level', s.capture!.level.toFixed(3));

  s.chains = s.chains
    .then(async () => {
      const p = await s.vad.prob(frame);
      const spans = s.tracker.push(p);
      for (const span of spans) queueCommit(s, span);
    })
    .catch(() => {});
}

function queueCommit(s: Session, span: Span) {
  const note = s.note;
  const offset = s.noteStart;
  s.chains = s.chains
    .then(async () => {
      const audio = s.ring.slice(span.start, Math.min(span.end, s.ring.total));
      if (audio.length < 0.35 * SAMPLE) return;
      const text = await runEngine(async () => {
        const e = await ensureEngine();
        return e.transcribe(audio);
      });
      if (!text) return;
      note.segments.push({
        id: crypto.randomUUID(),
        start: Math.max(0, (span.start - offset) / SAMPLE),
        end: Math.max(0, (span.end - offset) / SAMPLE),
        text,
      });
      await noteStore.put(note);
      if (displayed?.id === note.id) {
        renderBubbles();
        renderLive();
      }
    })
    .catch(() => {});
}

function startPreviewLoop() {
  const s = session;
  if (!s) return;
  s.previewTimer = window.setInterval(async () => {
    if (!session || session !== s || ui.phase !== 'recording') return;
    if (!s.tracker.inSpeech || engineJobs > 0 || !engine) return;
    const from = Math.max(s.tracker.openFrom! - 0.25 * SAMPLE, s.tracker.position - 20 * SAMPLE, s.noteStart);
    const audio = s.ring.slice(from, s.tracker.position);
    if (audio.length < 0.6 * SAMPLE) return;
    try {
      const text = await runEngine(() => engine!.transcribe(audio));
      if (session === s && ui.phase === 'recording') {
        ui.liveText = text;
        renderLive();
      }
    } catch {
      /* previews are best effort */
    }
  }, 1200);
}

function startTimer() {
  const tick = () => {
    const s = session;
    if (!s) return;
    ui.elapsed = (s.tracker.position - s.noteStart) / SAMPLE;
    el.recMeta.textContent = formatDuration(ui.elapsed);
    renderStatus();
    if (ui.phase === 'recording') requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}

async function stopRecording() {
  const s = session;
  if (!s) return;
  const stoppedAt = s.tracker.position;
  setPhase('ready');
  el.recordBtn.style.setProperty('--level', '0');
  el.recStatus.textContent = 'Finishing transcript…';
  window.clearInterval(s.previewTimer);
  await s.capture?.stop();
  await s.chains.catch(() => {});
  const rest = s.tracker.flush();
  for (const span of rest) await commitAndWait(s, span);
  await finishNote(s, stoppedAt);
  session = null;
  ui.liveText = '';
  ui.elapsed = 0;
  el.recMeta.textContent = '';
  el.recStatus.textContent = 'Not recording';
  renderLive();
  renderBubbles();
  renderNotes();
}

async function commitAndWait(s: Session, span: Span) {
  const audio = s.ring.slice(span.start, Math.min(span.end, s.ring.total));
  if (audio.length < 0.35 * SAMPLE) return;
  try {
    const text = await runEngine(async () => (await ensureEngine()).transcribe(audio));
    if (!text) return;
    s.note.segments.push({
      id: crypto.randomUUID(),
      start: Math.max(0, (span.start - s.noteStart) / SAMPLE),
      end: Math.max(0, (span.end - s.noteStart) / SAMPLE),
      text,
    });
  } catch {
    /* keep the audio; the note is still saved */
  }
}

async function finishNote(s: Session, endAt?: number) {
  const note = s.note;
  const end = endAt ?? s.tracker.position;
  note.durationSeconds = Math.max(0, (end - s.noteStart) / SAMPLE);
  note.status = note.segments.length > 0 ? 'done' : 'failed';
  if (s.chunks.length > 0) note.audio = encodeWav(s.chunks);
  await noteStore.put(note);
  if (!notes.some((n) => n.id === note.id)) notes.unshift(note);
  else notes = notes.map((n) => (n.id === note.id ? note : n));
  notes.sort((a, b) => b.createdAt - a.createdAt);
  renderNotes();
  if (note.segments.length > 0) {
    toast(`Saved — ${formatDuration(note.durationSeconds)}`);
  } else {
    toast('Nothing speech-like was heard. Audio kept in Notes.');
  }
}

async function splitNote() {
  const s = session;
  if (!s || ui.phase !== 'recording') return;
  // The split takes effect at the instant of the tap, not after the audio
  // already queued for transcription has been processed.
  const at = s.tracker.position;
  window.clearInterval(s.previewTimer);
  await s.chains.catch(() => {});
  const rest = s.tracker.flush();
  for (const span of rest) await commitAndWait(s, span);
  await finishNote(s, at);
  const next = newNote('mic');
  s.note = next;
  s.noteStart = at;
  s.chunks = [];
  ui.liveText = '';
  displayed = next;
  await noteStore.put(next);
  renderBubbles();
  renderLive();
  startPreviewLoop();
  toast('Split — new note started');
}

// ---------------------------------------------------------------- import

async function importFile(file: File) {
  if (ui.phase === 'recording' || ui.phase === 'importing') return;
  const name = file.name || 'audio';
  setPhase('importing');
  el.recStatus.textContent = `Transcribing ${name}…`;
  try {
    const detector = await ensureVad();
    detector.reset();
    const samples = await decodeAudioFile(file);
    if (samples.length === 0) throw new Error('No audio in that file.');

    const note = newNote('file');
    displayed = note;
    renderBubbles();
    renderLive();
    await noteStore.put(note);

    const ring = new SampleRing(60);
    const tracker = new UtteranceTracker();
    const pause = () => new Promise((r) => setTimeout(r, 0));

    for (let at = 0; at < samples.length; at += FRAME) {
      const chunk = samples.subarray(at, Math.min(at + FRAME, samples.length));
      const frame = new Float32Array(FRAME);
      frame.set(chunk);
      ring.push(chunk);
      const p = await detector.prob(frame);
      const spans = tracker.push(p);
      for (const span of spans) {
        const audio = ring.slice(span.start, Math.min(span.end, ring.total));
        const text = audio.length < 0.35 * SAMPLE ? '' : await runEngine(() => engine!.transcribe(audio));
        if (text) {
          note.segments.push({
            id: crypto.randomUUID(),
            start: Math.max(0, span.start / SAMPLE),
            end: Math.max(0, span.end / SAMPLE),
            text,
          });
        }
      }
      if (at % (FRAME * 160) === 0) {
        const pct = Math.round((at / samples.length) * 100);
        el.recStatus.textContent = `Transcribing ${name}… ${pct}%`;
        await pause();
      }
    }
    for (const span of tracker.flush()) {
      const audio = ring.slice(span.start, Math.min(span.end, ring.total));
      const text = audio.length < 0.35 * SAMPLE ? '' : await runEngine(() => engine!.transcribe(audio));
      if (text) {
        note.segments.push({ id: crypto.randomUUID(), start: span.start / SAMPLE, end: span.end / SAMPLE, text });
      }
    }

    note.durationSeconds = samples.length / SAMPLE;
    note.status = note.segments.length ? 'done' : 'failed';
    note.audio = encodeWav([toInt16(samples)]);
    await noteStore.put(note);
    notes = [note, ...notes.filter((n) => n.id !== note.id)];
    setPhase('ready');
    el.recStatus.textContent = 'Not recording';
    renderBubbles();
    renderNotes();
    toast(note.segments.length ? `Transcribed ${name}` : 'Nothing speech-like in that file.');
  } catch (err: any) {
    setPhase('ready');
    el.recStatus.textContent = 'Not recording';
    toast(err?.message ?? 'Could not read that file.');
  }
}

// ---------------------------------------------------------------- render

function renderStatus() {
  const dot = el.statusDot;
  dot.className = 'dot';
  el.recordBtn.classList.toggle('recording', ui.phase === 'recording');

  switch (ui.phase) {
    case 'boot':
      el.statusLine.textContent = 'Starting…';
      break;
    case 'loading':
      dot.classList.add('busy');
      el.statusLine.textContent =
        ui.progress >= 0 ? `Downloading model… ${Math.round(ui.progress * 100)}%` : 'Loading model…';
      break;
    case 'ready':
      dot.classList.add('ready');
      el.statusLine.textContent = `Ready · on-device · ${ui.device === 'webgpu' ? 'WebGPU' : 'WASM'}`;
      break;
    case 'recording':
      dot.classList.add('rec');
      el.statusLine.textContent = `Recording · ${formatDuration(ui.elapsed)}`;
      break;
    case 'importing':
      dot.classList.add('busy');
      el.statusLine.textContent = 'Transcribing file…';
      break;
    case 'error':
      dot.classList.add('err');
      el.statusLine.textContent = ui.error ? `Model failed — ${ui.error.slice(0, 60)}` : 'Model failed';
      break;
  }

  el.recStatus.textContent =
    ui.phase === 'recording'
      ? 'Recording — tap ■ to stop'
      : ui.phase === 'importing'
        ? el.recStatus.textContent
        : 'Not recording';

  if (ui.phase === 'loading' && ui.progress >= 0) {
    el.modelStrip.hidden = false;
    el.modelBar.style.width = `${Math.round(ui.progress * 100)}%`;
    el.modelLabel.textContent = `Downloading ${MODEL.label} · ${Math.round(ui.progress * 100)}%`;
  } else if (ui.phase !== 'loading') {
    el.modelStrip.hidden = true;
  }

  el.recordGlyph.innerHTML =
    ui.phase === 'recording'
      ? '<span class="stopglyph"></span>'
      : '<span class="reddot"></span>';
  el.recordBtn.setAttribute('aria-label', ui.phase === 'recording' ? 'Stop recording' : 'Start recording');
  el.splitBtn.disabled = ui.phase !== 'recording';
  el.importBtn.disabled = ui.phase === 'recording' || ui.phase === 'importing';
}

function renderBubbles() {
  const note = displayed;
  const segs = note ? segmentsOf(note) : [];
  el.introWrap.hidden = segs.length > 0;
  el.bubbles.innerHTML = '';
  for (const seg of segs) {
    const b = document.createElement('div');
    b.className = 'bubble';
    const meta = document.createElement('span');
    meta.className = 'bubble-meta';
    meta.textContent = formatDuration(seg.start);
    const p = document.createElement('p');
    p.textContent = seg.text;
    p.addEventListener('click', () => selectText(p));
    b.append(meta, p);
    el.bubbles.append(b);
  }
  if (segs.length > 0) {
    el.bubbles.scrollTop = el.bubbles.scrollHeight;
    el.transcript.scrollTop = el.transcript.scrollHeight;
  }
}

function renderLive() {
  const show = ui.phase === 'recording' || ui.phase === 'importing';
  if (!show || !ui.liveText) {
    el.liveBubble.hidden = true;
    el.liveBubble.textContent = '';
    return;
  }
  el.liveBubble.hidden = false;
  el.liveBubble.innerHTML = '<span class="caret"></span>';
  el.liveBubble.append(document.createTextNode(ui.liveText));
  el.liveBubble.scrollIntoView({ block: 'end', behavior: 'smooth' });
}

function selectText(node: HTMLElement) {
  const range = document.createRange();
  range.selectNodeContents(node);
  const sel = window.getSelection();
  sel?.removeAllRanges();
  sel?.addRange(range);
}

function renderNotes() {
  el.notesCount.textContent = notes.length === 0 ? '' : `${notes.length} note${notes.length === 1 ? '' : 's'}`;
  el.notesList.innerHTML = '';
  if (notes.length === 0) {
    el.notesList.innerHTML = '<p class="subtle pad">Nothing here yet. Record something and it will be saved on this device.</p>';
    return;
  }
  for (const note of notes) {
    const row = document.createElement('button');
    row.className = 'note-row';
    const text = noteText(note);
    row.innerHTML = `
      <div class="note-row-top">
        <span class="note-date">${formatDate(note.createdAt)}</span>
        <span class="note-meta">${formatDuration(note.durationSeconds)}${note.status === 'failed' ? ' · no speech' : ''}</span>
      </div>
      <p class="note-excerpt">${escapeHtml(text || 'No text')}</p>`;
    row.addEventListener('click', () => openNote(note));
    el.notesList.append(row);
  }
}

function openNote(note: Note) {
  el.noteTitle.textContent = formatDate(note.createdAt);
  el.noteBody.innerHTML = '';
  const segs = segmentsOf(note);
  if (segs.length === 0) {
    el.noteBody.innerHTML = '<p class="subtle pad">This recording has no transcript.</p>';
  }
  for (const seg of segs) {
    const b = document.createElement('div');
    b.className = 'bubble';
    b.innerHTML = `<span class="bubble-meta">${formatDuration(seg.start)}</span><p>${escapeHtml(seg.text)}</p>`;
    el.noteBody.append(b);
  }
  el.noteActions.innerHTML = '';
  const copy = document.createElement('button');
  copy.className = 'text-btn';
  copy.textContent = 'Copy text';
  copy.onclick = async () => {
    await navigator.clipboard.writeText(noteText(note));
    toast('Copied');
  };
  el.noteActions.append(copy);

  if (note.audio) {
    const dl = document.createElement('a');
    dl.className = 'text-btn';
    dl.textContent = 'Download WAV';
    dl.href = URL.createObjectURL(note.audio);
    dl.download = `voicepad-${new Date(note.createdAt).toISOString().replace(/[:.]/g, '-')}.wav`;
    el.noteActions.append(dl);
  }

  const del = document.createElement('button');
  del.className = 'text-btn danger';
  del.textContent = 'Delete';
  del.onclick = async () => {
    await noteStore.remove(note.id);
    notes = notes.filter((n) => n.id !== note.id);
    if (displayed?.id === note.id) {
      displayed = notes[0] ?? null;
      renderBubbles();
    }
    renderNotes();
    closeSheet('noteSheet');
    toast('Deleted');
  };
  el.noteActions.append(del);

  openSheet('noteSheet');
}

function renderSettings() {
  el.modelName.textContent = MODEL.label;
  el.modelDetail.textContent = `~${MODEL.sizeMB} MB one-time download · ${MODEL.note}`;
  el.deviceName.textContent = ui.device === 'webgpu' ? 'WebGPU' : 'WASM (CPU)';
  el.deviceDetail.textContent =
    ui.device === 'webgpu'
      ? 'Running on the GPU. Faster, but a larger model download.'
      : webgpuAvailable()
        ? 'WebGPU is available on this device — it makes transcription noticeably faster at the cost of a bigger download.'
        : 'Detected automatically. WebGPU makes transcription faster where the browser supports it.';
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

function renderStorage() {
  noteStore.estimate().then((est) => {
    if (!est) return;
    const mb = (n: number) => `${(n / 1024 / 1024).toFixed(1)} MB`;
    el.storageDetail.textContent = `${mb(est.usage)} used of ${mb(est.quota)} available. Notes and audio stay in this browser.`;
  });
}

// ---------------------------------------------------------------- sheets

function openSheet(id: string) {
  const sheet = $(id);
  sheet.hidden = false;
  el.scrim.hidden = false;
  requestAnimationFrame(() => sheet.classList.add('open'));
}

function closeSheet(id: string) {
  const sheet = $(id);
  sheet.classList.remove('open');
  el.scrim.hidden = true;
  window.setTimeout(() => (sheet.hidden = true), 180);
}

function wireSheets() {
  document.querySelectorAll('[data-close]').forEach((b) =>
    b.addEventListener('click', () => closeSheet((b as HTMLElement).dataset.close!)),
  );
  el.scrim.addEventListener('click', () => {
    for (const id of ['notesSheet', 'noteSheet', 'settingsSheet']) {
      if (!$(id).hidden) closeSheet(id);
    }
  });
}

// ---------------------------------------------------------------- boot

async function boot() {
  wireSheets();
  el.recordBtn.addEventListener('click', () => {
    if (ui.phase === 'recording') void stopRecording();
    else void startRecording();
  });
  el.splitBtn.addEventListener('click', () => void splitNote());
  el.importBtn.addEventListener('click', () => el.fileInput.click());
  el.sampleBtn.addEventListener('click', async () => {
    const res = await fetch('/fixtures/speech.wav');
    if (!res.ok) return;
    const blob = await res.blob();
    void importFile(new File([blob], 'sample.wav', { type: 'audio/wav' }));
  });
  el.fileInput.addEventListener('change', () => {
    const file = el.fileInput.files?.[0];
    if (file) void importFile(file);
    el.fileInput.value = '';
  });
  el.notesBtn.addEventListener('click', async () => {
    notes = await noteStore.list();
    renderNotes();
    openSheet('notesSheet');
  });
  el.settingsBtn.addEventListener('click', () => {
    renderSettings();
    renderStorage();
    openSheet('settingsSheet');
  });
  el.clearAllBtn.addEventListener('click', async () => {
    await noteStore.clear();
    notes = [];
    displayed = null;
    renderNotes();
    renderBubbles();
    toast('All notes deleted');
  });

  el.introText.textContent = INTRO_TEXT;
  el.transcript.scrollTop = 0;
  if (STORE_URL) {
    el.storeLink.href = STORE_URL;
    el.storeLink.textContent = 'Get the app';
    el.storeLink.hidden = false;
    el.introFoot.hidden = true;
  }
  el.modelName.textContent = MODEL.label;
  setPhase('boot');
  renderBubbles();

  notes = await noteStore.list().catch(() => []);
  displayed = notes[0] ?? null;
  renderBubbles();

  ensureVad().catch(() => {});
  void ensureEngine().catch(() => {});
  registerServiceWorker();

  if (window.matchMedia('(display-mode: standalone)').matches) el.installBtn.hidden = true;
}

function registerServiceWorker() {
  if (!('serviceWorker' in navigator) || !import.meta.env.PROD) return;
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  });
}

/**
 * Dev harness: drives a decoded audio file through the exact live-recording
 * path (same frame stream, detector, commits and note), which is how the
 * pipeline is tested on machines without a microphone.
 */
if (import.meta.env.DEV) {
  (window as any).__vp = {
    async feed(url: string) {
      const res = await fetch(url);
      const samples = await decodeAudioFile(await res.blob());
      const detector = await ensureVad();
      await ensureEngine();
      detector.reset();
      const note = newNote('mic');
      const s: Session = {
        capture: null,
        ring: new SampleRing(60),
        tracker: new UtteranceTracker(),
        vad: detector,
        note,
        noteStart: 0,
        chunks: [],
        chains: Promise.resolve(),
        previewTimer: 0,
        stopped: false,
      };
      session = s;
      displayed = note;
      setPhase('recording');
      renderBubbles();
      for (let at = 0; at < samples.length; at += FRAME) {
        const chunk = samples.subarray(at, Math.min(at + FRAME, samples.length));
        const frame = new Float32Array(FRAME);
        frame.set(chunk);
        s.ring.push(frame);
        s.chunks.push(toInt16(frame));
        const p = await detector.prob(frame);
        for (const span of s.tracker.push(p)) await commitAndWait(s, span);
        if (at % (FRAME * 64) === 0) {
          ui.elapsed = s.tracker.position / SAMPLE;
          el.recMeta.textContent = formatDuration(ui.elapsed);
          await new Promise((r) => setTimeout(r, 0));
        }
      }
      for (const span of s.tracker.flush()) await commitAndWait(s, span);
      await finishNote(s);
      session = null;
      ui.liveText = '';
      ui.elapsed = 0;
      el.recMeta.textContent = '';
      el.recStatus.textContent = 'Not recording';
      setPhase('ready');
      renderLive();
      renderBubbles();
      return note;
    },
  };
}

let installEvent: any = null;
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  installEvent = e;
  el.installBtn.hidden = false;
});
el.installBtn.addEventListener('click', async () => {
  if (!installEvent) return;
  installEvent.prompt();
  await installEvent.userChoice.catch(() => {});
  installEvent = null;
  el.installBtn.hidden = true;
});

void boot();
