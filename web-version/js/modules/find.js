// Find: search the text of the document (the PDF's own text, text boxes and
// OCR text) and mark the matches. Also "Go to page" in the menu.

import { overlaps, rw, rh } from '../core/model.js';
import { pageText, pieceRect } from '../core/pdfsource.js';
import { rotMatrix, invert, mapRect } from '../core/painter.js';
import { h, iconButton, promptBox, toast } from '../core/ui.js';

let matches = []; // [{page, rect}]
let current = -1;
let run = 0;
let timer;

const input = h('input', { type: 'search', placeholder: 'Find in document', 'aria-label': 'Find in document', enterkeyhint: 'search' });
const count = h('span.hint', { role: 'status' });
let hostRef = null;
const prev = iconButton('M15 5l-7 7 7 7', 'Previous match', () => step(-1));
const next = iconButton('M9 5l7 7-7 7', 'Next match', () => step(1));

function step(d) {
  if (!matches.length) return;
  current = (current + d + matches.length) % matches.length;
  show();
}
function show() {
  count.textContent = matches.length ? `${current + 1} of ${matches.length}` : (input.value.trim() ? 'No matches' : '');
  prev.disabled = next.disabled = matches.length < 2;
  if (matches[current]) hostRef.viewer.scrollTo(matches[current].page, matches[current].rect);
  hostRef.redraw();
}

function occurrences(text, needle, each) {
  const hay = text.toLowerCase();
  for (let i = hay.indexOf(needle); i >= 0; i = hay.indexOf(needle, i + needle.length)) each(i, i + needle.length);
}

async function search() {
  const host = hostRef, session = host.session;
  const needle = input.value.trim().toLowerCase();
  const mine = ++run;
  const found = [];
  if (needle) {
    for (let pi = 0; pi < session.model.pages.length; pi++) {
      const entry = session.model.pages[pi];
      const info = session.pageSize(pi);
      const hidden = entry.elements.filter((e) => e.type === 'rect' && e.erase).map((e) => e.bounds);
      const add = (rect) => { if (!hidden.some((b) => overlaps(b, rect))) found.push({ page: pi, rect }); };
      for (const item of await pageText(session.sources[entry.src], entry.index)) {
        occurrences(item.str, needle, (a, b) => add(pieceRect(item, a, b)));
      }
      if (mine !== run) return;
      for (const el of entry.elements) {
        if (el.type === 'text') occurrences(el.text, needle, () => add(el.bounds));
        if (el.type === 'hidden') {
          const back = invert(rotMatrix(info.w, info.h, el.frameRotation || 0));
          for (const w of el.words) occurrences(w.text, needle, () => add(mapRect(back, w.rect)));
        }
      }
    }
  }
  matches = found;
  current = found.length ? 0 : -1;
  show();
}

input.addEventListener('input', () => { clearTimeout(timer); timer = setTimeout(search, 250); });
input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); if (matches.length) step(e.shiftKey ? -1 : 1); else search(); } });

const tool = {
  id: 'find', label: 'Find', icon: 'M11 4a7 7 0 100 14 7 7 0 000-14zM16 16l5 5',
  activate(host) {
    hostRef = host;
    setTimeout(() => { input.focus(); if (input.value) search(); }, 50);
  },
  options: () => [input, count, prev, next],
  cancel() { // the tool was switched off
    run++;
    matches = [];
    current = -1;
  },
};

async function goToPage(host) {
  const total = host.session.model.pages.length;
  const v = await promptBox({ title: 'Go to page', text: `Page number, 1 to ${total}:`, value: String(host.viewer.currentPage() + 1), inputmode: 'numeric', ok: 'Go' });
  if (v == null) return;
  const n = parseInt(v, 10);
  if (!(n >= 1 && n <= total)) return toast(`There is no page ${v}.`);
  host.viewer.scrollTo(n - 1);
}

export default {
  id: 'find',
  title: 'Find',
  description: 'Search the text of the document and jump to a page.',
  tools: [tool],
  menuActions: [{ label: 'Go to page', run: goToPage }],
  overlay(host, ctx, pi) {
    if (host.tool !== tool || !matches.length) return;
    ctx.save();
    ctx.globalCompositeOperation = 'multiply';
    matches.forEach((m, i) => {
      if (m.page !== pi) return;
      ctx.fillStyle = i === current ? '#ff9a3c' : '#ffe14d';
      ctx.fillRect(m.rect.l, m.rect.t, rw(m.rect), rh(m.rect));
    });
    ctx.restore();
  },
};
