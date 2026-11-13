// The editor screen: top bar, pages, option row, toolbar. It also implements
// the "host" that modules talk to, and everything about the selection
// (select, move, resize, delete) so that no module has to.
//
// The module API (see also modules/registry.js):
//
//   export default {
//     id, title, description, enabledByDefault (default true),
//     tools: [{
//       id, label, icon (path data), hint,
//       options(host) -> nodes for the option row while the tool is active,
//       down(host, pi, pt), move(host, pi, pt), up(host, pi, pt), cancel(host)   a drag, in page space
//       tap(host, pi, pt)                                                        a tap
//       activate(host)   called when the tool is switched on; return false to stay off
//     }],
//     menuActions: [{label, run(host)}],                 entries in the ⋮ menu
//     selectionOptions(host, el, pi) -> nodes            option row for a selected element this module made
//     editElement(host, el, pi)                          a tap on an element that is already selected
//     overlay(host, ctx, pi, unit)                       temporary drawing over a page, in page space
//     saveOption(host) -> {node, after(bytes, name)}     an extra choice in the Save dialog; `after` gets the
//                                                        finished file and resolves {note, kept}
//     homeActions: [{label(), run(app)}]                 buttons on the home screen; app is {open(file)}
//   }
//
// Modules change the document only through host.session.edit(model => newModel).

import {
  h, icon, icons, iconButton, button, dialog, alertBox, confirmBox, promptBox, busy, toast, showScreen, offerDownload,
} from './ui.js';
import { Viewer } from './viewer.js';
import { writePdf } from './writer.js';
import { openSource } from './pdfsource.js';
import { fitText, paintElement, setImageReadyHandler } from './painter.js';
import {
  boundsOf, withBounds, moved, hits, resizeMode, rw, rh, replaceElement, removeElement, addElement,
} from './model.js';
import { storeSessionFiles, storeSessionState, clearStoredSession } from './store.js';
import { enabledModules, allModules } from '../modules/registry.js';
import { showSettings } from './settings.js';

const HANDLE = 6; // radius in CSS px
const REACH = 18; // how close a finger must be to a handle, CSS px

export function openEditor(session, onClosed) {
  let tool = null;
  let draft = null; // {page, element}: something a tool is still drawing
  let manip = null; // a move or resize in progress

  const options = h('div.options');
  const toolbar = h('nav.toolbar', { 'aria-label': 'Tools' });
  const undoBtn = iconButton(icons.undo, 'Undo', () => session.undo());
  const redoBtn = iconButton(icons.redo, 'Redo', () => session.redo());

  const host = {
    session,
    viewer: null,
    get tool() { return tool; },
    setTool,
    refreshOptions: renderOptions,
    redraw: (pi) => viewer.redraw(pi),
    /** Shows an element that is not in the document yet (while a tool draws it). */
    setDraft(page, element) { draft = element ? { page, element } : null; viewer.redraw(page); },
    select: (pi, id) => session.select(pi, id),
    /** Adds an element as one undo step and selects it. */
    add(pi, element, select = true) {
      session.edit((m) => addElement(m, pi, element));
      if (select) session.select(pi, element.id);
    },
    ui: { h, dialog, alertBox, confirmBox, promptBox, busy, toast, showScreen, button, iconButton, icon, icons },
    write,
    save,
    addSource,
    isEnabled: (id) => enabledModules().some((m) => m.id === id),
  };

  const viewer = new Viewer(session, {
    wants(pi, pt) {
      return !!grab(pi, pt) || !!(tool && (tool.down || tool.tap));
    },
    down(pi, pt) {
      const what = grab(pi, pt);
      if (what) { manip = { ...what, pi, start: pt, original: session.selected(), first: true }; return; }
      tool?.down?.(host, pi, pt);
    },
    move(pi, pt) {
      if (manip) return drag(pt);
      tool?.move?.(host, pi, pt);
    },
    up(pi, pt, wasMoved) {
      if (manip) {
        const m = manip;
        manip = null;
        if (!wasMoved) {
          const el = session.selected();
          if (el) ownerOf(el)?.editElement?.(host, el, pi);
        } else if (m.original.type === 'text' && m.kind !== 'move') {
          session.editLive((model) => replaceElement(model, pi, fitText(session.selected())), false);
        }
        return;
      }
      if (wasMoved) tool?.up?.(host, pi, pt);
      else { tool?.cancel?.(host); tool?.tap?.(host, pi, pt); }
    },
    cancel() {
      if (manip) {
        if (!manip.first) session.undo();
        manip = null;
      }
      tool?.cancel?.(host);
      host.setDraft(0, null);
    },
    tap(pi, pt) {
      if (pi < 0) return session.select(0, null);
      const slop = REACH * 0.6 / viewer.ppp;
      const els = session.model.pages[pi].elements;
      const hit = els.findLast((e) => hits(e, pt[0], pt[1], slop));
      session.select(pi, hit ? hit.id : null);
    },
    overlay(ctx, pi, unit) {
      for (const m of enabledModules()) m.overlay?.(host, ctx, pi, unit);
      if (draft?.page === pi) paintElement(ctx, draft.element);
      const sel = session.selection;
      if (sel?.page === pi) paintSelection(ctx, session.selected(), unit);
    },
  });
  host.viewer = viewer;

  // ------------------------------------------------------------ selection

  const ownerOf = (el) => enabledModules().find((m) => m.id === el.owner);

  function handles(el) {
    const b = boundsOf(el);
    switch (resizeMode(el)) {
      case 'ends': return [{ kind: 'start', at: el.start }, { kind: 'end', at: el.end }];
      case 'extent':
        return (el.turns || 0) % 2
          ? [{ kind: 't', at: [(b.l + b.r) / 2, b.t] }, { kind: 'b', at: [(b.l + b.r) / 2, b.b] }]
          : [{ kind: 'l', at: [b.l, (b.t + b.b) / 2] }, { kind: 'r', at: [b.r, (b.t + b.b) / 2] }];
      case 'free': case 'aspect':
        return [{ kind: 'tl', at: [b.l, b.t] }, { kind: 'tr', at: [b.r, b.t] }, { kind: 'bl', at: [b.l, b.b] }, { kind: 'br', at: [b.r, b.b] }];
      default: return [];
    }
  }

  /** What a touch at this point would take hold of: a handle of the selection, or the selection itself. */
  function grab(pi, pt) {
    const sel = session.selection;
    if (!sel || sel.page !== pi) return null;
    const el = session.selected();
    if (!el) return null;
    const reach = REACH / viewer.ppp;
    let best = null, bestDist = reach;
    for (const hd of handles(el)) {
      const d = Math.hypot(pt[0] - hd.at[0], pt[1] - hd.at[1]);
      if (d <= bestDist) { best = hd; bestDist = d; }
    }
    if (best) return { kind: best.kind };
    return hits(el, pt[0], pt[1], reach * 0.6) ? { kind: 'move' } : null;
  }

  function drag(pt) {
    const m = manip, el = m.original, b = boundsOf(el);
    const info = session.pageSize(m.pi);
    let next;
    if (m.kind === 'move') {
      const dx = Math.min(info.w - b.r, Math.max(-b.l, pt[0] - m.start[0]));
      const dy = Math.min(info.h - b.b, Math.max(-b.t, pt[1] - m.start[1]));
      next = moved(el, dx, dy);
    } else if (m.kind === 'start' || m.kind === 'end') {
      next = { ...el, [m.kind]: pt };
    } else if (resizeMode(el) === 'extent') {
      const nb = { ...b };
      if (m.kind === 'l') nb.l = Math.min(pt[0], b.r - 24);
      if (m.kind === 'r') nb.r = Math.max(pt[0], b.l + 24);
      if (m.kind === 't') nb.t = Math.min(pt[1], b.b - 24);
      if (m.kind === 'b') nb.b = Math.max(pt[1], b.t + 24);
      next = fitText({ ...el, bounds: nb });
    } else {
      const fx = m.kind.includes('l') ? b.r : b.l, fy = m.kind.includes('t') ? b.b : b.t; // the corner that stays put
      let w = Math.max(6, Math.abs(pt[0] - fx)), hgt = Math.max(6, Math.abs(pt[1] - fy));
      if (resizeMode(el) === 'aspect') {
        const s = Math.max(w / rw(b), hgt / rh(b));
        w = rw(b) * s; hgt = rh(b) * s;
      }
      const l = m.kind.includes('l') ? fx - w : fx, t = m.kind.includes('t') ? fy - hgt : fy;
      next = withBounds(el, { l, t, r: l + w, b: t + hgt });
    }
    session.editLive((model) => replaceElement(model, m.pi, next), m.first);
    m.first = false;
  }

  function paintSelection(ctx, el, unit) {
    if (!el) return;
    const b = boundsOf(el);
    ctx.save();
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';
    ctx.strokeStyle = '#1f6feb';
    ctx.lineWidth = 1.5 * unit;
    if (resizeMode(el) !== 'ends') {
      ctx.setLineDash([5 * unit, 4 * unit]);
      ctx.strokeRect(b.l - 2 * unit, b.t - 2 * unit, rw(b) + 4 * unit, rh(b) + 4 * unit);
      ctx.setLineDash([]);
    }
    ctx.fillStyle = '#fff';
    ctx.lineWidth = 2 * unit;
    for (const hd of handles(el)) {
      ctx.beginPath();
      ctx.arc(hd.at[0], hd.at[1], HANDLE * unit, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
    }
    ctx.restore();
  }

  function deleteSelection() {
    const sel = session.selection;
    if (sel) session.edit((m) => removeElement(m, sel.page, sel.id));
  }

  // ------------------------------------------------------------ bars

  function setTool(next) {
    if (next && next === tool) next = null;
    if (next && next.activate?.(host) === false) next = null;
    tool?.cancel?.(host);
    tool = next;
    draft = null;
    if (session.selection) session.selection = null; // the option row now belongs to the tool
    viewer.root.classList.toggle('drawing', !!(tool && (tool.down || tool.tap)));
    renderToolbar();
    renderOptions();
    viewer.redraw();
  }

  function renderToolbar() {
    const tools = enabledModules().flatMap((m) => m.tools || []);
    if (tool && !tools.includes(tool)) tool = null;
    toolbar.replaceChildren(...tools.map((t) =>
      h('button.tool' + (t === tool ? '.on' : ''), {
        type: 'button', 'aria-pressed': String(t === tool), onclick: () => setTool(t),
      }, icon(t.icon), h('span', t.label))));
    toolbar.hidden = tools.length === 0;
  }

  function renderOptions() {
    const el = session.selected();
    let nodes = [];
    if (el) {
      nodes = [
        iconButton(icons.trash, 'Delete', deleteSelection),
        ...(ownerOf(el)?.selectionOptions?.(host, el, session.selection.page) || []),
      ];
    } else if (tool) {
      nodes = [...(tool.options?.(host) || []), tool.hint && h('span.hint', tool.hint)];
    }
    nodes = nodes.filter(Boolean);
    const now = [...options.childNodes];
    options.hidden = !nodes.length;
    if (nodes.length === now.length && nodes.every((n, i) => n === now[i])) return; // keeps focus in a module's own input
    options.replaceChildren(...nodes);
  }

  function showMenu() {
    const items = [
      ...enabledModules().flatMap((m) => m.menuActions || []),
      { label: 'Zoom in', run: () => viewer.setZoom(viewer.zoom * 1.25) },
      { label: 'Zoom out', run: () => viewer.setZoom(viewer.zoom / 1.25) },
      { label: 'Fit to width', run: () => viewer.setZoom(1) },
      { label: 'Settings', run: () => showSettings(() => { renderToolbar(); renderOptions(); viewer.redraw(); }) },
      { label: 'Close document', run: close },
    ];
    dialog({
      body: (done) => h('div.menu', { role: 'menu' }, items.map((it) =>
        h('button', { type: 'button', role: 'menuitem', onclick: () => { done(); it.run(host); } }, it.label))),
    });
  }

  // ------------------------------------------------------------ saving

  /** Writes the document with a "working" box and reports errors. Resolves null if it failed. */
  async function write(opts = {}) {
    const box = busy('Preparing your copy…');
    try {
      return await writePdf(session, { ...opts, progress: (t) => box.set(t) });
    } catch (e) {
      console.error(e);
      box.close();
      await alertBox('Could not save', String(e?.message || e));
      return null;
    } finally {
      box.close();
    }
  }

  const baseName = () => session.name.replace(/\.pdf$/i, '');

  /**
   * The save flow: confirm a name, write a new file, hand it to the user.
   * The file that was opened is never touched; a browser can only write a
   * new download. Resolves true if the copy was handed over.
   */
  async function save({ suggested, title = 'Save a copy', note, ...opts } = {}) {
    const input = h('input', { type: 'text', 'aria-label': 'File name', autocomplete: 'off' });
    input.value = suggested || `${baseName()}-edited.pdf`;
    // A module may add a choice to this dialog and do something with the finished file (Library does).
    const extras = enabledModules().map((m) => m.saveOption?.(host)).filter(Boolean);
    let closer;
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') closer(true); });
    const go = await dialog({
      title,
      body: (close) => { closer = close; return [h('p', 'The original file is not changed. Name for the new copy:'), input, ...extras.map((x) => x.node)]; },
      buttons: [{ label: 'Cancel', value: false }, { label: 'Save', kind: 'primary', value: true }],
    });
    if (go !== true) return false;
    let name = input.value.trim().replace(/[\\/:*?"<>|]/g, '-') || `${baseName()}-edited.pdf`;
    if (!/\.pdf$/i.test(name)) name += '.pdf';
    const bytes = await write(opts);
    if (!bytes) return false;
    const notes = [note];
    let kept = false;
    for (const x of extras) {
      const result = await x.after(bytes, name);
      if (result?.note) notes.push(result.note);
      if (result?.kept) kept = true;
    }
    const done = (await offerDownload(bytes, name, notes.filter(Boolean).join(' '))) || kept;
    if (done && !opts.model && !opts.postProcess) {
      session.dirty = false;
      storeSessionState(session);
    }
    return done;
  }

  /** Opens another PDF as an extra source (for merging). Resolves with its index, or null. */
  async function addSource(file) {
    const bytes = new Uint8Array(await file.arrayBuffer());
    const source = await openSource(file.name, bytes, (wrong) => promptBox({
      title: 'Password needed', text: wrong ? 'That password did not work. Try again:' : `${file.name} is protected. Password:`, type: 'password',
    }));
    session.sources.push(source);
    storeSessionFiles(session);
    return session.sources.length - 1;
  }

  async function close() {
    if (session.dirty && !(await confirmBox('Close without saving?', 'Edits you have not saved as a copy will be lost.', 'Close', 'Keep editing'))) return;
    stop();
    await clearStoredSession();
    onClosed();
  }

  // ------------------------------------------------------------ wiring

  let saveTimer;
  const unlisten = session.onChange((what) => {
    if (what === 'model') {
      viewer.refresh();
      clearTimeout(saveTimer);
      saveTimer = setTimeout(() => storeSessionState(session), 400);
    } else viewer.redraw();
    undoBtn.disabled = !session.undoStack.length;
    redoBtn.disabled = !session.redoStack.length;
    renderOptions();
  });

  const onKey = (e) => {
    if (e.target.closest('input, textarea, [contenteditable]') || document.querySelector('.backdrop, .screen')) return;
    const mod = e.ctrlKey || e.metaKey;
    if (mod && e.key.toLowerCase() === 'z') { e.preventDefault(); (e.shiftKey ? session.redo() : session.undo()); }
    else if (mod && e.key.toLowerCase() === 'y') { e.preventDefault(); session.redo(); }
    else if ((e.key === 'Delete' || e.key === 'Backspace') && session.selection) { e.preventDefault(); deleteSelection(); }
    else if (e.key === 'Escape') { if (session.selection) session.select(0, null); else if (tool) setTool(null); }
  };
  document.addEventListener('keydown', onKey);
  setImageReadyHandler(() => viewer.redraw());

  function stop() {
    unlisten();
    clearTimeout(saveTimer);
    document.removeEventListener('keydown', onKey);
    viewer.destroy();
    el.remove();
  }

  const el = h('div.editor',
    h('header.bar',
      iconButton(icons.back, 'Close document', close),
      h('h1', { title: session.name }, session.name),
      undoBtn, redoBtn,
      h('button.btn.primary.save', { type: 'button', onclick: () => save() }, 'Save'),
      iconButton(icons.more, 'More', showMenu)),
    viewer.root, options, toolbar);
  document.body.append(el);
  undoBtn.disabled = redoBtn.disabled = true;
  renderToolbar();
  renderOptions();
  viewer.refresh();
  return { host, stop };
}
