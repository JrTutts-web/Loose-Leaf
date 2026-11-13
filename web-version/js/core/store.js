// Everything the app keeps between visits. All of it stays in this browser:
// settings in localStorage, larger things (unsaved work, signatures) in
// IndexedDB. Both can be unavailable (private windows), so every call copes.

const DB = 'looseleaf';
const STORE = 'kv';
let dbPromise;

function db() {
  dbPromise ??= new Promise((resolve, reject) => {
    const req = indexedDB.open(DB, 1);
    req.onupgradeneeded = () => req.result.createObjectStore(STORE);
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  return dbPromise;
}

async function tx(mode, fn) {
  try {
    const d = await db();
    return await new Promise((resolve, reject) => {
      const t = d.transaction(STORE, mode);
      const req = fn(t.objectStore(STORE));
      t.oncomplete = () => resolve(req?.result);
      t.onerror = t.onabort = () => reject(t.error);
    });
  } catch (e) {
    console.warn('storage unavailable', e);
    return undefined;
  }
}

export const kv = {
  get: (key) => tx('readonly', (s) => s.get(key)),
  set: (key, value) => tx('readwrite', (s) => s.put(value, key)),
  delete: (key) => tx('readwrite', (s) => s.delete(key)),
};

export const prefs = {
  get(key, fallback) {
    try {
      const v = localStorage.getItem('looseleaf.' + key);
      return v == null ? fallback : JSON.parse(v);
    } catch { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem('looseleaf.' + key, JSON.stringify(value)); } catch { /* private window */ }
  },
};

// ---------------------------------------------------------------- unsaved work

// 'session.files' holds the PDFs (written when a file is opened or merged),
// 'session.state' holds the small part that changes with every edit.

export async function storeSessionFiles(session) {
  await kv.set('session.files', session.sources.map((s) => ({ name: s.name, bytes: s.bytes, password: s.password })));
}

export async function storeSessionState(session) {
  await kv.set('session.state', { name: session.name, model: session.model, dirty: session.dirty, at: Date.now() });
}

export async function loadStoredSession() {
  const [files, state] = await Promise.all([kv.get('session.files'), kv.get('session.state')]);
  if (!files?.length || !state?.model) return null;
  return { files, state };
}

export async function clearStoredSession() {
  await Promise.all([kv.delete('session.files'), kv.delete('session.state')]);
}
