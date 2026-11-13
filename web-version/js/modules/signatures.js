// Signatures: draw or type a signature once, keep it in this browser, and
// place it on any page. Signatures are stored in IndexedDB on this device.

import { newId, uprightTurns } from '../core/model.js';
import { kv } from '../core/store.js';
import { h, button, iconButton, icons, dialog, chips, showScreen, toast } from '../core/ui.js';

const ID = 'signatures';
const FONTS = [['Great Vibes', 'GreatVibes'], ['Allura', 'Allura'], ['Sacramento', 'Sacramento'], ['Kalam', 'Kalam']];
const INKS = [{ label: 'Black', value: '#111111' }, { label: 'Blue', value: '#1546b0' }];

const load = async () => (await kv.get('signatures')) || [];
const store = (list) => kv.set('signatures', list);

let fontsReady;
function loadFonts() {
  fontsReady ??= Promise.all(FONTS.map(async ([family, file]) => {
    const face = new FontFace(family, `url(${new URL(`../../fonts/${file}.ttf`, import.meta.url).href})`);
    document.fonts.add(await face.load());
  }));
  return fontsReady;
}

/** Cuts the empty border off a canvas. Returns {src, w, h} or null if nothing was drawn. */
function trimmed(canvas) {
  const { width, height } = canvas;
  const data = canvas.getContext('2d').getImageData(0, 0, width, height).data;
  let l = width, t = height, r = -1, b = -1;
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      if (data[(y * width + x) * 4 + 3] > 8) {
        if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y;
      }
    }
  }
  if (r < 0) return null;
  const pad = 4;
  l = Math.max(0, l - pad); t = Math.max(0, t - pad); r = Math.min(width - 1, r + pad); b = Math.min(height - 1, b + pad);
  const out = document.createElement('canvas');
  out.width = r - l + 1; out.height = b - t + 1;
  out.getContext('2d').drawImage(canvas, l, t, out.width, out.height, 0, 0, out.width, out.height);
  return { src: out.toDataURL('image/png'), w: out.width, h: out.height };
}

function drawDialog() {
  let color = INKS[0].value;
  const strokes = [];
  const pad = h('canvas.pad', { 'aria-label': 'Signature drawing area' });
  const inkRow = h('div');
  const paint = () => {
    const dpr = window.devicePixelRatio || 1;
    const box = pad.getBoundingClientRect();
    if (pad.width !== Math.round(box.width * dpr)) { pad.width = Math.round(box.width * dpr); pad.height = Math.round(box.height * dpr); }
    const ctx = pad.getContext('2d');
    ctx.clearRect(0, 0, pad.width, pad.height);
    ctx.strokeStyle = ctx.fillStyle = color;
    ctx.lineWidth = 2.6 * dpr; ctx.lineCap = ctx.lineJoin = 'round';
    for (const pts of strokes) {
      ctx.beginPath();
      if (pts.length === 1) { ctx.arc(pts[0][0] * dpr, pts[0][1] * dpr, 1.3 * dpr, 0, 7); ctx.fill(); continue; }
      ctx.moveTo(pts[0][0] * dpr, pts[0][1] * dpr);
      for (let i = 1; i < pts.length - 1; i++) { // smooth through the midpoints
        const mx = (pts[i][0] + pts[i + 1][0]) / 2, my = (pts[i][1] + pts[i + 1][1]) / 2;
        ctx.quadraticCurveTo(pts[i][0] * dpr, pts[i][1] * dpr, mx * dpr, my * dpr);
      }
      const last = pts[pts.length - 1];
      ctx.lineTo(last[0] * dpr, last[1] * dpr);
      ctx.stroke();
    }
  };
  const at = (e) => { const r = pad.getBoundingClientRect(); return [e.clientX - r.left, e.clientY - r.top]; };
  let current = null;
  pad.addEventListener('pointerdown', (e) => { e.preventDefault(); pad.setPointerCapture(e.pointerId); current = [at(e)]; strokes.push(current); paint(); });
  pad.addEventListener('pointermove', (e) => { if (current) { current.push(at(e)); paint(); } });
  const end = () => { current = null; };
  pad.addEventListener('pointerup', end);
  pad.addEventListener('pointercancel', end);
  const inks = () => inkRow.replaceChildren(chips(INKS, color, (c) => { color = c; inks(); paint(); }));
  inks();
  return dialog({
    title: 'Draw your signature',
    body: [inkRow, pad, h('p.hint', 'Sign with a finger, a pen or the mouse.')],
    buttons: [
      { label: 'Clear', onclick: () => { strokes.length = 0; paint(); return false; } },
      { label: 'Cancel', value: null },
      { label: 'Save', kind: 'primary', onclick: () => trimmed(pad) || (toast('Draw something first.'), false) },
    ],
    dismissable: false,
  });
}

async function typeDialog() {
  await loadFonts();
  let font = FONTS[0][0], color = INKS[0].value;
  const input = h('input', { type: 'text', 'aria-label': 'Your name', autocomplete: 'name', placeholder: 'Your name' });
  const choices = h('div'), inkRow = h('div');
  const show = () => {
    choices.replaceChildren(...FONTS.map(([family]) => h('button.font-choice' + (family === font ? '.on' : ''), {
      type: 'button', 'aria-pressed': String(family === font), style: { fontFamily: `"${family}"`, color }, onclick: () => { font = family; show(); },
    }, input.value || 'Your name')));
    inkRow.replaceChildren(chips(INKS, color, (c) => { color = c; show(); }));
  };
  input.addEventListener('input', show);
  show();
  const render = () => {
    const text = input.value.trim();
    if (!text) { toast('Type your name first.'); return false; }
    const size = 120, c = document.createElement('canvas'), ctx = c.getContext('2d');
    ctx.font = `${size}px "${font}"`;
    c.width = Math.ceil(ctx.measureText(text).width + size * 1.2);
    c.height = size * 2.4;
    ctx.font = `${size}px "${font}"`;
    ctx.fillStyle = color;
    ctx.textBaseline = 'alphabetic';
    ctx.fillText(text, size * 0.5, size * 1.5);
    return trimmed(c) || false;
  };
  return dialog({
    title: 'Type your signature',
    body: [input, inkRow, choices],
    buttons: [{ label: 'Cancel', value: null }, { label: 'Save', kind: 'primary', onclick: render }],
  });
}

function place(host, sig) {
  const pi = host.viewer.currentPage();
  const page = host.session.model.pages[pi], info = host.session.pageSize(pi);
  const turns = uprightTurns(page);
  const [cx, cy] = host.viewer.visibleCentre(pi);
  let w = Math.min(170, (turns % 2 ? info.h : info.w) * 0.4), hgt = (w * sig.h) / sig.w;
  if (turns % 2) [w, hgt] = [hgt, w];
  const l = Math.min(info.w - w, Math.max(0, cx - w / 2)), t = Math.min(info.h - hgt, Math.max(0, cy - hgt / 2));
  host.add(pi, { id: newId(), owner: ID, type: 'image', src: sig.src, turns, bounds: { l, t, r: l + w, b: t + hgt } });
  toast('Drag the signature into place. Drag a corner to resize it.');
}

function openScreen(host) {
  showScreen('Signatures', async (body, close) => {
    const list = h('div.sig-list');
    const empty = h('p.hint', 'No saved signatures yet. Draw one or type your name.');
    const add = async (make) => {
      const sig = await make();
      if (!sig) return;
      await store([...(await load()), { id: Date.now(), ...sig }]);
      show();
    };
    const show = async () => {
      const sigs = await load();
      empty.hidden = sigs.length > 0;
      list.replaceChildren(...sigs.map((s) => h('div.sig', {
        role: 'button', tabindex: 0, 'aria-label': 'Place this signature',
        onclick: () => { close(); place(host, s); },
        onkeydown: (e) => { if (e.key === 'Enter') { close(); place(host, s); } },
      }, h('img', { src: s.src, alt: 'Saved signature' }),
      iconButton(icons.trash, 'Delete this signature', async (e) => {
        e.stopPropagation();
        await store((await load()).filter((x) => x.id !== s.id));
        show();
      }))));
    };
    body.append(
      h('p', 'Tap a signature to put it on the page you were looking at.'),
      h('div.actions', button('Draw', () => add(drawDialog), 'primary'), button('Type', () => add(typeDialog))),
      empty, list,
      h('p.hint', 'Signatures are kept in this browser on this device only.'));
    show();
  });
}

export default {
  id: ID,
  title: 'Signatures',
  description: 'Draw or type a signature, keep it on this device, and place it on a page.',
  tools: [{
    id: 'sign', label: 'Sign', icon: 'M3 16c3-7 5-10 7-10s0 9 2 9 3-4 5-4 2 2 4 2M3 21h18',
    activate(host) { openScreen(host); return false; },
  }],
  selectionOptions: () => [h('span.hint', 'Signature. Drag to move, drag a corner to resize.')],
};
