/**
 * Note storage in IndexedDB. The shape is the web subset of Note.kt: a note
 * has a created time, a duration, a status and ordered transcript segments,
 * plus its 16 kHz WAV. A full note in the Android app is one text with a
 * revision history; the demo keeps the per-utterance bubbles the user sees
 * while recording.
 */

export interface Segment {
  id: string;
  /** Seconds from the start of the note. */
  start: number;
  end: number;
  text: string;
}

export type NoteStatus = 'recording' | 'done' | 'failed';

export interface Note {
  id: string;
  createdAt: number;
  durationSeconds: number;
  segments: Segment[];
  status: NoteStatus;
  source: 'mic' | 'file';
  audio?: Blob;
  /** Pinned notes are listed first (newest pin first). */
  pinned?: boolean;
  pinnedAt?: number;
}

const DB_NAME = 'voicepad';
const DB_VERSION = 1;
const STORE = 'notes';

function open(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE)) {
        const store = db.createObjectStore(STORE, { keyPath: 'id' });
        store.createIndex('createdAt', 'createdAt');
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

let db: Promise<IDBDatabase> | null = null;
function connection(): Promise<IDBDatabase> {
  if (!db) db = open();
  return db;
}

function tx<T>(mode: IDBTransactionMode, fn: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  return connection().then(
    (database) =>
      new Promise<T>((resolve, reject) => {
        const t = database.transaction(STORE, mode);
        const req = fn(t.objectStore(STORE));
        req.onsuccess = () => resolve(req.result);
        req.onerror = () => reject(req.error);
      }),
  );
}

export const noteStore = {
  async list(): Promise<Note[]> {
    const all = await tx<Note[]>('readonly', (s) => s.getAll() as IDBRequest<Note[]>);
    return all.sort((a, b) => b.createdAt - a.createdAt);
  },
  async put(note: Note): Promise<void> {
    await tx('readwrite', (s) => s.put(note));
  },
  async get(id: string): Promise<Note | undefined> {
    return tx<Note | undefined>('readonly', (s) => s.get(id) as IDBRequest<Note | undefined>);
  },
  async remove(id: string): Promise<void> {
    await tx('readwrite', (s) => s.delete(id));
  },
  async clear(): Promise<void> {
    await tx('readwrite', (s) => s.clear());
  },
  async estimate(): Promise<{ usage: number; quota: number } | null> {
    if (!navigator.storage?.estimate) return null;
    const e = await navigator.storage.estimate();
    return { usage: e.usage ?? 0, quota: e.quota ?? 0 };
  },
};

/** Pinned first, newest pin first, then newest note first. */
export function sortNotes(list: Note[]): Note[] {
  return [...list].sort((a, b) => {
    const pa = a.pinned ? (a.pinnedAt ?? 0) : -1;
    const pb = b.pinned ? (b.pinnedAt ?? 0) : -1;
    if (pa !== pb) return pb - pa;
    return b.createdAt - a.createdAt;
  });
}

/** One JSON document for one note or all of them — the shape the app can import. */
export function notesToJson(notes: Note[]): Blob {
  const payload = {
    app: 'VoicePad',
    format: 'voicepad-notes',
    version: 1,
    exportedAt: new Date().toISOString(),
    includesAudio: false,
    notes: notes.map((n) => ({
      id: n.id,
      createdAt: n.createdAt,
      durationSeconds: n.durationSeconds,
      pinned: !!n.pinned,
      pinnedAt: n.pinnedAt ?? null,
      status: n.status,
      source: n.source,
      text: noteText(n),
      segments: n.segments.map((s) => ({ start: s.start, end: s.end, text: s.text })),
    })),
  };
  return new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
}

export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 2000);
}

export function noteText(note: Note): string {
  return note.segments.map((s) => s.text).join(' ').replace(/\s+/g, ' ').trim();
}

export function newNote(source: 'mic' | 'file'): Note {
  return {
    id: crypto.randomUUID(),
    createdAt: Date.now(),
    durationSeconds: 0,
    segments: [],
    status: 'recording',
    source,
    pinned: false,
  };
}
