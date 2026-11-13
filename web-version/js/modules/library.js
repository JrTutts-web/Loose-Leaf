// Library: keeps a copy of what you save in a folder on the computer that
// serves this page (for example on a NAS), and lets you browse that folder,
// sort it into subfolders, rename, move, delete and reopen.
//
// This is the one module that sends files over the network, which is why it
// is off until switched on. It talks only to the site the page came from
// (serve.py started with --library); on a plain static server it finds no
// library and says so. The download on the device still happens as usual.

import { prefs } from '../core/store.js';
import { h, button, iconButton, icons, dialog, promptBox, confirmBox, alertBox, toast, busy, showScreen, chips, formatSize } from '../core/ui.js';

const ID = 'library';
const SIGNATURES = 'signatures'; // elements with this owner make a document count as signed
const MODES = [{ label: 'Every copy I save', value: 'all' }, { label: 'Only PDFs I signed', value: 'signed' }];
const mode = () => prefs.get('library.mode', 'all');
const title = () => (mode() === 'signed' ? 'Signed PDFs' : 'Library');
const join = (...parts) => parts.filter(Boolean).join('/');
const shown = (path) => (path ? `Library / ${path.split('/').join(' / ')}` : 'Library');

// ---------------------------------------------------------------- talking to the server

class Unavailable extends Error {}

async function call(method, action, params = {}, body) {
  const url = new URL(`api/library${action ? '/' + action : ''}`, document.baseURI);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  for (let attempt = 0; ; attempt++) {
    let res;
    try {
      res = await fetch(url, { method, body, headers: { 'X-Loose-Leaf': '1', 'X-Library-Key': prefs.get('library.key', '') } });
    } catch {
      throw new Unavailable('The library cannot be reached right now. Check the connection to the server.');
    }
    if (res.status === 401 && attempt < 3) {
      const key = await promptBox({
        title: 'Library passphrase', type: 'password', ok: 'Continue',
        text: attempt ? 'That passphrase was not accepted. Try again:' : 'This library is protected. Enter its passphrase (it is remembered on this device):',
      });
      if (key == null) throw new Unavailable('No passphrase was given.');
      prefs.set('library.key', key);
      continue;
    }
    if (!res.ok) {
      let message = `The server answered ${res.status}.`;
      try { message = (await res.json()).error || message; } catch { /* not JSON: a plain static server */ }
      throw new (res.status === 404 && !action ? Unavailable : Error)(message);
    }
    return res;
  }
}

let probe;
/** Resolves true if the site this page came from has a library. Asked once per visit. */
function available() {
  probe ??= call('GET', '').then(async (r) => (await r.json()).library === true).catch(() => false);
  return probe;
}

const list = async (path) => (await call('GET', 'list', { path })).json();

// ---------------------------------------------------------------- choosing a folder

/** Lets the user walk the library's folders and pick one. Resolves with its path, or null. */
function chooseFolder(start, heading = 'Choose a folder') {
  let path = start;
  return dialog({
    title: heading,
    body: (close) => {
      const where = h('p'), rows = h('div.menu');
      const show = async () => {
        where.textContent = shown(path);
        let folders = [];
        try {
          folders = (await list(path)).folders;
        } catch (e) {
          if (path) { path = ''; return show(); } // the remembered folder is gone
          rows.replaceChildren(h('p.hint', String(e.message)));
          return;
        }
        rows.replaceChildren(
          path && h('button', { type: 'button', onclick: () => { path = path.split('/').slice(0, -1).join('/'); show(); } }, '↑ Up'),
          ...folders.map((f) => h('button', { type: 'button', onclick: () => { path = join(path, f); show(); } }, `📁 ${f}`)),
          !folders.length && h('p.hint', 'No folders in here.'));
      };
      show();
      return [where, rows, h('div.actions',
        button('New folder', async () => { if (await newFolder(path)) show(); }),
        button('Use this folder', () => close(path), 'primary'))];
    },
    buttons: [{ label: 'Cancel', value: null }],
  }).then((v) => (typeof v === 'string' ? v : null));
}

async function newFolder(parent) {
  const name = await promptBox({ title: 'New folder', text: `In ${shown(parent)}:`, ok: 'Create' });
  if (!name?.trim()) return false;
  try {
    await call('POST', 'mkdir', { path: join(parent, name.trim()) });
    return true;
  } catch (e) {
    await alertBox('Could not make the folder', e.message);
    return false;
  }
}

// ---------------------------------------------------------------- the choice in the Save dialog

function saveOption(host) {
  const signed = host.session.model.pages.some((p) => p.elements.some((e) => e.owner === SIGNATURES));
  let folder = prefs.get('library.folder', '');
  const box = h('input', { type: 'checkbox', id: 'lib-keep' });
  box.checked = mode() === 'all' || signed;
  box.disabled = true;
  const where = h('span.hint', 'Looking for the library…');
  const change = button('Change', async () => {
    const picked = await chooseFolder(folder);
    if (picked != null) { folder = picked; where.textContent = shown(folder); }
  });
  change.hidden = true;
  available().then((yes) => {
    if (!yes) { box.checked = false; where.textContent = 'This copy of Loose Leaf has no library folder set up, so the copy is only downloaded.'; return; }
    box.disabled = false;
    change.hidden = false;
    where.textContent = shown(folder);
  });
  return {
    node: h('div.save-extra',
      h('label', { for: 'lib-keep' }, box, h('span', mode() === 'signed' ? 'Also keep a copy in Signed PDFs' : 'Also keep a copy in the library')),
      h('div.where', where, change)),
    async after(bytes, name) {
      if (!box.checked || box.disabled) return null;
      const working = busy('Putting a copy in the library…');
      try {
        let res;
        try {
          res = await call('PUT', 'file', { path: join(folder, name) }, bytes);
        } catch (e) {
          if (!folder || e instanceof Unavailable) throw e;
          res = await call('PUT', 'file', { path: name }, bytes); // the remembered folder is gone: use the top
        }
        const { path } = await res.json();
        prefs.set('library.folder', path.split('/').slice(0, -1).join('/'));
        return { kept: true, note: `A copy is in the library as “${path}”.` };
      } catch (e) {
        return { note: `The copy could NOT be put in the library (${e.message}) Download it instead.` };
      } finally {
        working.close();
      }
    },
  };
}

// ---------------------------------------------------------------- the Library screen

function openScreen(app) {
  let path = '';
  showScreen(title(), async (body, close) => {
    if (!(await available())) {
      body.append(
        h('p', 'This copy of Loose Leaf has no library folder set up.'),
        h('p.hint', 'The library lives on the computer that serves this page. Whoever runs it starts it with “python3 serve.py --library <folder>”, where the folder can be on a NAS. Until then, saved copies are only downloaded to this device.'));
      return;
    }
    const crumbs = h('div.crumbs'), rows = h('div.list'), empty = h('p.hint');
    const modeRow = h('div');
    const showMode = () => modeRow.replaceChildren(h('p.hint', 'When saving, offer to keep:'),
      chips(MODES, mode(), (m) => { prefs.set('library.mode', m); showMode(); }));

    const act = async (work, failTitle) => {
      try { await work(); } catch (e) { await alertBox(failTitle, e.message); }
      show();
    };
    const open = async (name) => {
      const working = busy('Opening…');
      try {
        const blob = await (await call('GET', 'file', { path: join(path, name) })).blob();
        working.close();
        close();
        app.open(new File([blob], name, { type: 'application/pdf' }));
      } catch (e) {
        working.close();
        alertBox('Could not open it', e.message);
      }
    };
    const download = async (name) => {
      try {
        const blob = await (await call('GET', 'file', { path: join(path, name) })).blob();
        const url = URL.createObjectURL(blob);
        await dialog({
          title: name,
          body: (done) => h('a.btn.primary.wide', { href: url, download: name, onclick: () => setTimeout(done, 300) }, 'Download to this device'),
          buttons: [{ label: 'Close' }],
        });
        setTimeout(() => URL.revokeObjectURL(url), 60000);
      } catch (e) { alertBox('Could not get it', e.message); }
    };
    const menu = (name, isFolder) => dialog({
      title: name,
      body: (done) => h('div.menu', [
        ['Rename', async () => {
          const next = await promptBox({ title: 'Rename', value: name, ok: 'Rename' });
          if (!next?.trim() || next.trim() === name) return;
          const fixed = isFolder || /\.pdf$/i.test(next.trim()) ? next.trim() : `${next.trim()}.pdf`;
          act(() => call('POST', 'move', { from: join(path, name), to: join(path, fixed) }), 'Could not rename');
        }],
        ['Move to…', async () => {
          const to = await chooseFolder(path, `Move “${name}” to`);
          if (to == null || to === path) return;
          act(() => call('POST', 'move', { from: join(path, name), to: join(to, name) }), 'Could not move');
        }],
        !isFolder && ['Download', () => download(name)],
        ['Delete', async () => {
          if (!(await confirmBox(`Delete “${name}”?`, isFolder ? 'Only an empty folder can be deleted.' : 'It is removed from the library for good. Copies downloaded to a device are not touched.', 'Delete'))) return;
          act(() => call('DELETE', 'file', { path: join(path, name) }), 'Could not delete');
        }],
      ].filter(Boolean).map(([label, run]) => h('button', { type: 'button', onclick: () => { done(); run(); } }, label))),
    });

    async function show() {
      const parts = path ? path.split('/') : [];
      crumbs.replaceChildren(
        h('button', { type: 'button', onclick: () => { path = ''; show(); } }, 'Library'),
        ...parts.flatMap((p, i) => ['/', h('button', { type: 'button', onclick: () => { path = parts.slice(0, i + 1).join('/'); show(); } }, p)]));
      let data;
      try {
        data = await list(path);
      } catch (e) {
        if (path) { path = ''; return show(); }
        rows.replaceChildren();
        empty.textContent = e.message;
        return;
      }
      empty.textContent = data.folders.length + data.files.length ? '' : (path ? 'This folder is empty.' : 'Nothing here yet. Save a PDF and tick “Also keep a copy”.');
      rows.replaceChildren(
        ...data.folders.map((f) => h('div.lib-row.folder',
          h('button.name', { type: 'button', onclick: () => { path = join(path, f); show(); } }, `📁 ${f}`),
          iconButton(icons.more, `More for ${f}`, () => menu(f, true)))),
        ...data.files.map((f) => h('div.lib-row',
          h('button.name', { type: 'button', onclick: () => open(f.name) }, f.name,
            h('small', `${formatSize(f.size)}, ${new Date(f.modified).toLocaleDateString()}`)),
          iconButton(icons.more, `More for ${f.name}`, () => menu(f.name, false)))));
    }

    body.append(crumbs,
      h('div.actions', button('New folder', async () => { if (await newFolder(path)) show(); })),
      rows, empty, h('h2', 'Options'), modeRow,
      h('p.hint', 'The library is a folder on the computer this page comes from. Files in it are ordinary PDFs.'));
    showMode();
    show();
  });
}

export default {
  id: ID,
  title: 'Library',
  description: 'Keep a copy of what you save on the home server (for example a NAS) and browse it from here. Off by default because it sends your PDFs to that server.',
  enabledByDefault: false,
  homeActions: [{ label: title, run: openScreen }],
  saveOption,
};
