// The scrolling, zoomable list of pages. It draws page bitmaps and the model's
// elements, and turns touches and clicks into page-space points. What a touch
// means is decided by the editor (see the `on` callbacks).

import { h } from './ui.js';
import { renderPage, Cancelled } from './pdfsource.js';
import { paintElements, rotMatrix, invert, apply, multiply, shownSize, mapRect } from './painter.js';

const GAP = 12;
const MAX_PIXELS = 4_000_000; // per page canvas; phones run out of canvas memory quickly
const SLOP = 6; // CSS px a finger may wander and still count as a tap

export class Viewer {
  /**
   * on: {
   *   wants(pi, pt) -> bool   should a touch that starts here be handled instead of scrolling?
   *   down(pi, pt), move(pi, pt), up(pi, pt, moved), cancel()   a handled touch
   *   tap(pi, pt)             a tap that was not handled
   *   overlay(ctx, pi, unit)  draw extras in page space; `unit` is one CSS pixel in points
   * }
   */
  constructor(session, on) {
    this.session = session;
    this.on = on;
    this.zoom = 1;
    this.views = [];
    this.structure = '';
    this.pages = h('div.pages');
    this.root = h('div.viewer', this.pages);
    this.observer = new IntersectionObserver((entries) => {
      for (const e of entries) {
        const view = this.views.find((v) => v.el === e.target);
        if (!view) continue;
        view.visible = e.isIntersecting;
        if (view.visible) this.ensureBase(view); else this.dropBase(view);
      }
    }, { root: this.root, rootMargin: '600px 0px' });
    this.resize = new ResizeObserver(() => { this.layout(); this.rerenderSoon(); });
    this.resize.observe(this.root);
    this.bindInput();
  }

  destroy() {
    this.observer.disconnect();
    this.resize.disconnect();
    for (const v of this.views) this.dropBase(v);
  }

  // -------------------------------------------------------------- layout

  /** CSS pixels per point. */
  get ppp() {
    const widest = Math.max(1, ...this.views.map((v) => v.shown.w));
    return (Math.max(200, this.root.clientWidth - 16) / widest) * this.zoom;
  }

  /** Call after the model changed. */
  refresh() {
    const m = this.session.model;
    const structure = m.pages.map((p) => `${p.src}:${p.index}:${p.rot}`).join(',');
    if (structure !== this.structure) {
      this.structure = structure;
      for (const v of this.views) { this.dropBase(v); this.observer.unobserve(v.el); }
      this.views = m.pages.map((p, i) => {
        const info = this.session.pageSize(i);
        const canvas = h('canvas');
        const el = h('div.page', { 'data-page': i }, canvas);
        return { el, canvas, index: i, info, rot: p.rot, shown: shownSize(info.w, info.h, p.rot), visible: false, base: null };
      });
      this.pages.replaceChildren(...this.views.map((v) => v.el));
      this.layout();
      for (const v of this.views) this.observer.observe(v.el);
    } else this.redraw();
  }

  layout() {
    const ppp = this.ppp;
    for (const v of this.views) {
      v.el.style.width = `${v.shown.w * ppp}px`;
      v.el.style.height = `${v.shown.h * ppp}px`;
    }
    this.pages.style.gap = `${GAP}px`;
    this.redraw();
  }

  /** Pixel size of a page's canvas at the current zoom. */
  pixelSize(v) {
    const dpr = Math.min(window.devicePixelRatio || 1, 3);
    let s = this.ppp * dpr;
    const area = v.shown.w * v.shown.h * s * s;
    if (area > MAX_PIXELS) s *= Math.sqrt(MAX_PIXELS / area);
    return { w: Math.max(1, Math.round(v.shown.w * s)), h: Math.max(1, Math.round(v.shown.h * s)) };
  }

  ensureBase(v) {
    const size = this.pixelSize(v);
    const key = `${size.w}x${size.h}`;
    if (v.base?.key === key || v.pending?.key === key) return;
    v.pending?.job.cancel();
    const entry = this.session.model.pages[v.index];
    const job = renderPage(this.session.sources[entry.src], entry.index, entry.rot, size.w / v.shown.w);
    v.pending = { key, job };
    job.promise.then((canvas) => {
      if (v.pending?.job !== job) return;
      if (v.base) v.base.canvas.width = v.base.canvas.height = 0;
      v.base = { key, canvas };
      v.pending = null;
      this.draw(v);
    }).catch((e) => {
      if (v.pending?.job === job) v.pending = null;
      if (!(e instanceof Cancelled)) console.error('render failed', e);
    });
  }

  dropBase(v) {
    v.pending?.job.cancel();
    v.pending = null;
    if (v.base) { v.base.canvas.width = v.base.canvas.height = 0; v.base = null; }
    v.canvas.width = v.canvas.height = 0;
  }

  rerenderSoon() {
    clearTimeout(this.rerenderTimer);
    this.rerenderTimer = setTimeout(() => { for (const v of this.views) if (v.visible) this.ensureBase(v); }, 180);
  }

  redraw(pageIndex) {
    for (const v of this.views) if (v.visible && (pageIndex == null || v.index === pageIndex)) this.draw(v);
  }

  draw(v) {
    const entry = this.session.model.pages[v.index];
    if (!entry) return;
    const size = this.pixelSize(v);
    if (v.canvas.width !== size.w || v.canvas.height !== size.h) { v.canvas.width = size.w; v.canvas.height = size.h; }
    const ctx = v.canvas.getContext('2d');
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.globalAlpha = 1;
    ctx.globalCompositeOperation = 'source-over';
    if (v.base) ctx.drawImage(v.base.canvas, 0, 0, size.w, size.h);
    else { ctx.fillStyle = '#fff'; ctx.fillRect(0, 0, size.w, size.h); }
    const s = size.w / v.shown.w;
    ctx.setTransform(...multiply([s, 0, 0, s, 0, 0], rotMatrix(v.info.w, v.info.h, entry.rot)));
    paintElements(ctx, entry.elements);
    this.on.overlay(ctx, v.index, 1 / this.ppp);
  }

  // -------------------------------------------------------------- zoom and scroll

  setZoom(zoom, clientX, clientY) {
    zoom = Math.min(6, Math.max(0.5, zoom));
    if (zoom === this.zoom) return;
    const box = this.root.getBoundingClientRect();
    const ax = (clientX ?? box.left + box.width / 2) - box.left;
    const ay = (clientY ?? box.top + box.height / 2) - box.top;
    const ratio = zoom / this.zoom;
    const cx = this.root.scrollLeft + ax, cy = this.root.scrollTop + ay;
    this.zoom = zoom;
    this.layout();
    this.root.scrollLeft = cx * ratio - ax;
    this.root.scrollTop = cy * ratio - ay;
    this.rerenderSoon();
  }

  /** Scrolls so that a place on a page (a rect in page space, or the top of the page) can be seen. */
  scrollTo(pageIndex, r) {
    const v = this.views[pageIndex];
    if (!v) return;
    let top = v.el.offsetTop;
    if (r) {
      const entry = this.session.model.pages[pageIndex];
      const shown = mapRect(rotMatrix(v.info.w, v.info.h, entry.rot), r);
      top += shown.t * this.ppp - this.root.clientHeight / 3;
      const left = v.el.offsetLeft + shown.l * this.ppp;
      if (left < this.root.scrollLeft || left > this.root.scrollLeft + this.root.clientWidth - 40) {
        this.root.scrollLeft = Math.max(0, left - 40);
      }
    } else top -= 8;
    this.root.scrollTop = Math.max(0, top);
  }

  /** The page that fills most of the screen. */
  currentPage() {
    const mid = this.root.scrollTop + this.root.clientHeight / 2;
    let best = 0, bestDist = Infinity;
    for (const v of this.views) {
      const c = v.el.offsetTop + v.el.offsetHeight / 2;
      const d = Math.abs(c - mid);
      if (d < bestDist) { bestDist = d; best = v.index; }
    }
    return best;
  }

  /** The middle of the visible part of a page, in page space. */
  visibleCentre(pageIndex) {
    const v = this.views[pageIndex];
    const box = this.root.getBoundingClientRect(), pr = v.canvas.getBoundingClientRect();
    const x = (Math.max(box.left, pr.left) + Math.min(box.right, pr.right)) / 2;
    const y = (Math.max(box.top, pr.top) + Math.min(box.bottom, pr.bottom)) / 2;
    return this.toPage(v, x, y);
  }

  // -------------------------------------------------------------- input

  toPage(v, clientX, clientY) {
    const r = v.canvas.getBoundingClientRect();
    const entry = this.session.model.pages[v.index];
    const sx = Math.min(v.shown.w, Math.max(0, ((clientX - r.left) / r.width) * v.shown.w));
    const sy = Math.min(v.shown.h, Math.max(0, ((clientY - r.top) / r.height) * v.shown.h));
    return apply(invert(rotMatrix(v.info.w, v.info.h, entry.rot)), sx, sy);
  }

  viewAt(target) {
    const el = target.closest?.('.page');
    return el ? this.views[Number(el.dataset.page)] : null;
  }

  bindInput() {
    const root = this.root;
    let g = null; // the touch or click in progress
    let pinch = null;

    root.addEventListener('pointerdown', (e) => {
      if (!e.isPrimary || e.button !== 0 || pinch) return;
      const v = this.viewAt(e.target);
      if (!v) { this.on.tap(-1, null); return; }
      const pt = this.toPage(v, e.clientX, e.clientY);
      g = { v, x: e.clientX, y: e.clientY, moved: false, wanted: this.on.wants(v.index, pt), id: e.pointerId };
      if (g.wanted) {
        try { root.setPointerCapture(e.pointerId); } catch { /* already gone */ }
        this.on.down(v.index, pt);
      }
    });
    root.addEventListener('pointermove', (e) => {
      if (!g || e.pointerId !== g.id) return;
      if (!g.moved && Math.hypot(e.clientX - g.x, e.clientY - g.y) > SLOP) g.moved = true;
      if (g.wanted && g.moved) this.on.move(g.v.index, this.toPage(g.v, e.clientX, e.clientY));
    });
    root.addEventListener('pointerup', (e) => {
      if (!g || e.pointerId !== g.id) return;
      const done = g;
      g = null;
      const pt = this.toPage(done.v, e.clientX, e.clientY);
      if (done.wanted) this.on.up(done.v.index, pt, done.moved);
      else if (!done.moved) this.on.tap(done.v.index, pt);
    });
    root.addEventListener('pointercancel', (e) => {
      if (!g || e.pointerId !== g.id) return;
      if (g.wanted) this.on.cancel();
      g = null;
    });

    // One finger on something the editor wants: stop the browser from scrolling.
    // Two fingers: scroll and zoom, whatever tool is active.
    const span = (t) => Math.hypot(t[0].clientX - t[1].clientX, t[0].clientY - t[1].clientY);
    const mid = (t) => [(t[0].clientX + t[1].clientX) / 2, (t[0].clientY + t[1].clientY) / 2];
    root.addEventListener('touchstart', (e) => {
      if (e.touches.length === 1 && g?.wanted) e.preventDefault();
      if (e.touches.length === 2) {
        e.preventDefault();
        if (g?.wanted) this.on.cancel();
        g = null;
        pinch = { span: span(e.touches), zoom: this.zoom, mid: mid(e.touches) };
      }
    }, { passive: false });
    root.addEventListener('touchmove', (e) => {
      if (pinch && e.touches.length === 2) {
        e.preventDefault();
        const [mx, my] = mid(e.touches);
        root.scrollLeft -= mx - pinch.mid[0];
        root.scrollTop -= my - pinch.mid[1];
        pinch.mid = [mx, my];
        this.setZoom(pinch.zoom * (span(e.touches) / pinch.span), mx, my);
      } else if (g?.wanted) e.preventDefault();
    }, { passive: false });
    const endTouch = (e) => { if (e.touches.length === 0) pinch = null; };
    root.addEventListener('touchend', endTouch);
    root.addEventListener('touchcancel', endTouch);

    root.addEventListener('wheel', (e) => {
      if (!e.ctrlKey) return;
      e.preventDefault();
      this.setZoom(this.zoom * (e.deltaY < 0 ? 1.15 : 1 / 1.15), e.clientX, e.clientY);
    }, { passive: false });
  }
}
