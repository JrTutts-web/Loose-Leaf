// The document model: plain data, no drawing and no PDF library.
//
// Page space: points, origin at the top-left of the page as the PDF shows it
// (before any turn the user adds), y pointing down. A rect is {l, t, r, b}.
//
// Elements (the only things the painter and the writer know about):
//   rect   {bounds, color, alpha, blend: 'multiply' | 'normal', erase}   highlight, white-out, redaction
//   ink    {points: [[x, y], ...], color, width}                        pen strokes
//   line   {start: [x, y], end: [x, y], color, width, arrow}
//   shape  {bounds, kind: 'rect' | 'oval' | 'check' | 'cross', color, width, turns}
//   text   {bounds, text, fontSize, color, turns}
//   image  {bounds, src (PNG data URL), turns}                          e.g. a signature
//   hidden {words: [{rect, text}], frameRotation}                       invisible text from OCR
// Every element also has `id` and `owner` (the id of the module that made it).
// `turns` is the number of clockwise quarter turns of the content in its box.
//
// A model is {pages: [{src, index, rot, elements}], form: {fieldName: value}}.
// It is never changed in place: every edit makes a new one, which is all that
// undo and redo need.

let nextId = 1;
export const newId = () => nextId++;
export function ensureIdsAbove(model) {
  for (const p of model.pages) for (const e of p.elements) if (e.id >= nextId) nextId = e.id + 1;
}

export const rect = (l, t, r, b) => ({ l: Math.min(l, r), t: Math.min(t, b), r: Math.max(l, r), b: Math.max(t, b) });
export const rw = (r) => r.r - r.l;
export const rh = (r) => r.b - r.t;
export const inRect = (r, x, y, slop = 0) => x >= r.l - slop && x <= r.r + slop && y >= r.t - slop && y <= r.b + slop;
export const overlaps = (a, b) => a.l < b.r && a.r > b.l && a.t < b.b && a.b > b.t;

export function boundsOf(el) {
  switch (el.type) {
    case 'ink': {
      let l = Infinity, t = Infinity, r = -Infinity, b = -Infinity;
      for (const [x, y] of el.points) { l = Math.min(l, x); r = Math.max(r, x); t = Math.min(t, y); b = Math.max(b, y); }
      return { l, t, r, b };
    }
    case 'line': return rect(el.start[0], el.start[1], el.end[0], el.end[1]);
    case 'hidden': return { l: 0, t: 0, r: 0, b: 0 };
    default: return el.bounds;
  }
}

/** 'none' | 'free' | 'aspect' | 'extent' (text: two handles along the writing direction) | 'ends' */
export function resizeMode(el) {
  return { rect: 'free', ink: 'free', shape: 'free', image: 'aspect', text: 'extent', line: 'ends', hidden: 'none' }[el.type];
}

export function withBounds(el, nb) {
  if (el.type === 'ink' || el.type === 'line') {
    const ob = boundsOf(el);
    const sx = rw(ob) > 0.01 ? rw(nb) / rw(ob) : 1;
    const sy = rh(ob) > 0.01 ? rh(nb) / rh(ob) : 1;
    const map = ([x, y]) => [nb.l + (x - ob.l) * sx, nb.t + (y - ob.t) * sy];
    return el.type === 'ink' ? { ...el, points: el.points.map(map) } : { ...el, start: map(el.start), end: map(el.end) };
  }
  if (el.type === 'hidden') return el;
  return { ...el, bounds: nb };
}

export function moved(el, dx, dy) {
  const b = boundsOf(el);
  return withBounds(el, { l: b.l + dx, t: b.t + dy, r: b.r + dx, b: b.b + dy });
}

/** True if the point is on the element (lines and pen strokes need to be hit near the stroke). */
export function hits(el, x, y, slop) {
  if (el.type === 'hidden') return false;
  if (el.type === 'line') return segDist(x, y, el.start, el.end) <= slop + el.width / 2;
  if (el.type === 'ink') {
    if (!inRect(boundsOf(el), x, y, slop)) return false;
    if (el.points.length === 1) return true;
    for (let i = 1; i < el.points.length; i++) {
      if (segDist(x, y, el.points[i - 1], el.points[i]) <= slop + el.width / 2) return true;
    }
    return false;
  }
  return inRect(el.bounds, x, y, slop / 2);
}

function segDist(px, py, [ax, ay], [bx, by]) {
  const dx = bx - ax, dy = by - ay;
  const len2 = dx * dx + dy * dy;
  const t = len2 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / len2)) : 0;
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

// ---------------------------------------------------------------- model edits

export function updatePage(model, pageIndex, fn) {
  return { ...model, pages: model.pages.map((p, i) => (i === pageIndex ? fn(p) : p)) };
}
export function addElement(model, pageIndex, el) {
  return updatePage(model, pageIndex, (p) => ({ ...p, elements: [...p.elements, el] }));
}
export function replaceElement(model, pageIndex, el) {
  return updatePage(model, pageIndex, (p) => ({ ...p, elements: p.elements.map((e) => (e.id === el.id ? el : e)) }));
}
export function removeElement(model, pageIndex, id) {
  return updatePage(model, pageIndex, (p) => ({ ...p, elements: p.elements.filter((e) => e.id !== id) }));
}

// ---------------------------------------------------------------- session

/**
 * One open document: the PDFs it is made from, the current model, undo and
 * redo, and the selection. `sources` are {name, bytes, password, pdf, pages}
 * where `pages[i]` is {w, h, rotate, transform} for page i of that file.
 */
export class Session {
  constructor(name, sources, model, dirty = false) {
    this.name = name;
    this.sources = sources;
    this.model = model;
    this.undoStack = [];
    this.redoStack = [];
    this.selection = null; // {page, id}
    this.dirty = dirty;
    this.listeners = new Set();
    ensureIdsAbove(model);
  }

  onChange(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  emit(what) { for (const fn of this.listeners) fn(what); }

  /** The only way to change the document. `fn` gets the model and returns a new one. */
  edit(fn) {
    const next = fn(this.model);
    if (!next || next === this.model) return;
    this.undoStack.push(this.model);
    if (this.undoStack.length > 100) this.undoStack.shift();
    this.redoStack = [];
    this.model = next;
    this.dirty = true;
    this.checkSelection();
    this.emit('model');
  }

  /** Like edit, but folds into the previous undo step (used while dragging). */
  editLive(fn, first) {
    if (first) return this.edit(fn);
    const next = fn(this.model);
    if (!next) return;
    this.model = next;
    this.emit('model');
  }

  undo() { this.step(this.undoStack, this.redoStack); }
  redo() { this.step(this.redoStack, this.undoStack); }
  step(from, to) {
    if (!from.length) return;
    to.push(this.model);
    this.model = from.pop();
    this.dirty = true;
    this.checkSelection();
    this.emit('model');
  }

  select(page, id) {
    this.selection = id == null ? null : { page, id };
    this.emit('selection');
  }
  checkSelection() {
    if (this.selection && !this.selected()) this.selection = null;
  }
  selected() {
    const s = this.selection;
    return s ? this.model.pages[s.page]?.elements.find((e) => e.id === s.id) || null : null;
  }

  /** Size of a page as the PDF shows it, before the user's own turn. */
  pageSize(pageIndex) {
    const p = this.model.pages[pageIndex];
    return this.sources[p.src].pages[p.index];
  }
}

/** Quarter turns that make content read upright on a page the user has turned. */
export const uprightTurns = (page) => (4 - page.rot / 90) % 4;
