// Pages: thumbnails; reorder, rotate, delete, extract a selection as its own
// PDF, and add the pages of another PDF.

import { renderPage } from '../core/pdfsource.js';
import { paintElements, rotMatrix, multiply, shownSize } from '../core/painter.js';
import { h, button, showScreen, pickFile, toast, confirmBox, busy, alertBox } from '../core/ui.js';

function openScreen(host) {
  const session = host.session;
  let selected = new Set();

  showScreen('Pages', (body) => {
    const grid = h('div.thumbs');
    const status = h('p.hint', { role: 'status' });
    const jobs = new Map();
    const watcher = new IntersectionObserver((entries) => {
      for (const e of entries) if (e.isIntersecting) { watcher.unobserve(e.target); jobs.get(e.target)?.(); }
    }, { root: body, rootMargin: '300px' });

    const sorted = () => [...selected].sort((a, b) => a - b);
    const change = (fn, reselect) => {
      selected = new Set(reselect || []); // before the edit: the grid redraws as soon as the model changes
      session.selection = null;
      session.edit((m) => ({ ...m, pages: fn(m.pages) }));
    };

    const actions = {
      earlier() {
        const s = sorted();
        if (!s.length || s[0] === 0) return;
        change((pages) => { const p = [...pages]; for (const i of s) [p[i - 1], p[i]] = [p[i], p[i - 1]]; return p; }, s.map((i) => i - 1));
      },
      later() {
        const s = sorted().reverse();
        if (!s.length || s[0] === session.model.pages.length - 1) return;
        change((pages) => { const p = [...pages]; for (const i of s) [p[i + 1], p[i]] = [p[i], p[i + 1]]; return p; }, s.map((i) => i + 1));
      },
      turn(by) {
        const which = new Set(selected);
        change((pages) => pages.map((p, i) => (which.has(i) ? { ...p, rot: (p.rot + by + 360) % 360 } : p)), [...which]);
      },
      async remove() {
        if (selected.size >= session.model.pages.length) return toast('A document needs at least one page.');
        const n = selected.size;
        if (!(await confirmBox(n === 1 ? 'Delete this page?' : `Delete these ${n} pages?`, 'You can undo this in the editor until you close the document.', 'Delete'))) return;
        const gone = new Set(selected);
        change((pages) => pages.filter((_, i) => !gone.has(i)));
      },
      extract() {
        const m = session.model;
        host.save({
          model: { ...m, pages: sorted().map((i) => m.pages[i]) },
          suggested: `${session.name.replace(/\.pdf$/i, '')}-pages.pdf`, title: 'Save these pages as a PDF',
        });
      },
      async merge() {
        const file = await pickFile('application/pdf,.pdf');
        if (!file) return;
        const box = busy('Adding pages…');
        try {
          const src = await host.addSource(file);
          const added = session.sources[src].pages.map((_, index) => ({ src, index, rot: 0, elements: [] }));
          const at = session.model.pages.length;
          change((pages) => [...pages, ...added], added.map((_, i) => at + i));
          toast(`${added.length} ${added.length === 1 ? 'page' : 'pages'} added at the end.`);
        } catch (e) {
          if (e?.constructor?.name !== 'Cancelled') alertBox('Could not add this file', String(e?.message || e));
        } finally {
          box.close();
        }
      },
    };

    const needSel = [];
    const act = (label, fn, kind) => { const b = button(label, fn, kind); needSel.push(b); return b; };
    const bar = h('div.actions',
      act('Move earlier', actions.earlier), act('Move later', actions.later),
      act('Turn left', () => actions.turn(-90)), act('Turn right', () => actions.turn(90)),
      act('Save as PDF', actions.extract), act('Delete', actions.remove, 'danger'));

    function render() {
      watcher.disconnect();
      jobs.clear();
      const pages = session.model.pages;
      grid.replaceChildren(...pages.map((entry, i) => {
        const canvas = h('canvas');
        const cell = h('button.thumb' + (selected.has(i) ? '.on' : ''), {
          type: 'button', 'aria-pressed': String(selected.has(i)), 'aria-label': `Page ${i + 1}`,
          onclick: () => { if (selected.has(i)) selected.delete(i); else selected.add(i); update(); cell.classList.toggle('on', selected.has(i)); cell.setAttribute('aria-pressed', String(selected.has(i))); },
        }, canvas, String(i + 1));
        jobs.set(cell, async () => {
          const info = session.pageSize(i), shown = shownSize(info.w, info.h, entry.rot);
          const s = 240 / Math.max(shown.w, shown.h);
          try {
            const bmp = await renderPage(session.sources[entry.src], entry.index, entry.rot, s).promise;
            canvas.width = bmp.width; canvas.height = bmp.height;
            const ctx = canvas.getContext('2d');
            ctx.drawImage(bmp, 0, 0);
            ctx.setTransform(...multiply([s, 0, 0, s, 0, 0], rotMatrix(info.w, info.h, entry.rot)));
            paintElements(ctx, entry.elements);
            bmp.width = bmp.height = 0;
          } catch { /* cancelled */ }
        });
        watcher.observe(cell);
        return cell;
      }));
      update();
    }
    function update() {
      for (const b of needSel) b.disabled = selected.size === 0;
      const n = session.model.pages.length;
      status.textContent = selected.size ? `${selected.size} of ${n} selected` : `${n} ${n === 1 ? 'page' : 'pages'}. Tap pages to select them.`;
    }

    const unlisten = session.onChange((what) => {
      if (!body.isConnected) return unlisten();
      if (what === 'model') render();
    });
    body.append(status, bar, h('div.actions', button('Add pages from another PDF', actions.merge)), grid);
    render();
  });
}

export default {
  id: 'pages',
  title: 'Pages',
  description: 'Reorder, turn, delete and extract pages, or add another PDF.',
  menuActions: [{ label: 'Pages', run: openScreen }],
};
