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
  };
}
