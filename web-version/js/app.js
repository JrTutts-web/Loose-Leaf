// Start-up and the home screen.

import { h, button, pickFile, promptBox, alertBox, busy, confirmBox } from './core/ui.js';
import { Session } from './core/model.js';
import { openSource, Cancelled } from './core/pdfsource.js';
import { loadFonts } from './core/painter.js';
import { openEditor } from './core/editor.js';
import { showSettings } from './core/settings.js';
import { enabledModules } from './modules/registry.js';
import { storeSessionFiles, storeSessionState, loadStoredSession, clearStoredSession } from './core/store.js';

const askPassword = (name) => (wrong) => promptBox({
  title: 'Password needed',
  text: wrong ? 'That password did not work. Try again:' : `${name} is protected. Enter its password to open it:`,
  type: 'password', ok: 'Open',
});

/** Shows "Opening…" and, if it takes a while, says that this is normal. */
async function whileOpening(work) {
  const box = busy('Opening…');
  const timer = setTimeout(() => box.set('Opening… Long documents can take a little while.'), 2500);
  try {
    return await work();
  } finally {
    clearTimeout(timer);
    box.close();
  }
}

async function openFile(file) {
  if (!file) return;
  try {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const session = await whileOpening(async () => {
      await loadFonts();
      const source = await openSource(file.name, bytes, askPassword(file.name));
      const model = { pages: source.pages.map((_, index) => ({ src: 0, index, rot: 0, elements: [] })), form: {} };
      return new Session(file.name, [source], model);
    });
    await storeSessionFiles(session);
    await storeSessionState(session);
    start(session);
  } catch (e) {
    if (e instanceof Cancelled) return;
    console.error(e);
    alertBox('Could not open this file', /pdf/i.test(e?.name || '') ? 'It does not look like a PDF, or it is damaged.' : String(e?.message || e));
  }
}

async function restore(stored) {
  try {
    const session = await whileOpening(async () => {
      await loadFonts();
      const sources = [];
      for (const f of stored.files) sources.push(await openSource(f.name, f.bytes, askPassword(f.name), f.password));
      return new Session(stored.state.name, sources, stored.state.model, true);
    });
    start(session);
  } catch (e) {
    if (e instanceof Cancelled) return;
    console.error(e);
    alertBox('Could not restore', String(e?.message || e));
  }
}

function start(session) {
  document.querySelector('.home')?.remove();
  openEditor(session, showHome);
}

async function showHome() {
  document.querySelector('.home')?.remove();
  const recover = h('div');
  const home = h('main.home', h('div.home-inner',
    h('header', h('img', { src: 'icon.svg', alt: '' }), h('div', h('h1', 'Loose Leaf'), h('p.hint', 'A small PDF editor that keeps your files to yourself.'))),
    recover,
    button('Open a PDF', async () => openFile(await pickFile('application/pdf,.pdf')), 'primary'),
    enabledModules().flatMap((m) => m.homeActions || []).map((a) => button(a.label(), () => a.run({ open: openFile }))),
    button('Settings', () => showSettings(showHome)),
    h('ul.facts',
      h('li', 'Your PDF is opened inside this page. It is not uploaded anywhere, unless you switch on the Library.'),
      h('li', 'The original file is never changed. Saving always makes a new copy.'),
      h('li', 'Unsaved edits are kept in this browser until you save or close the document.'))));
  home.addEventListener('dragover', (e) => { e.preventDefault(); home.classList.add('drop'); });
  home.addEventListener('dragleave', () => home.classList.remove('drop'));
  home.addEventListener('drop', (e) => {
    e.preventDefault();
    home.classList.remove('drop');
    openFile([...e.dataTransfer.files].find((f) => /pdf$/i.test(f.type) || /\.pdf$/i.test(f.name)));
  });
  document.body.append(home);

  const stored = await loadStoredSession();
  if (!stored) return;
  if (!stored.state.dirty) { clearStoredSession(); return; }
  recover.replaceWith(h('section.card',
    h('strong', 'Unsaved edits found'),
    h('p', `${stored.state.name}, last changed ${new Date(stored.state.at).toLocaleString()}.`),
    h('div.dialog-buttons',
      button('Discard', async (e) => {
        if (await confirmBox('Discard these edits?', 'They cannot be brought back.', 'Discard')) {
          await clearStoredSession();
          showHome();
        }
      }, 'danger'),
      button('Restore', () => restore(stored), 'primary'))));
}

if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
showHome();
