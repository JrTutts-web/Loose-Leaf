// Reading PDFs for the screen: pdf.js, loaded from this folder (vendor/pdfjs).
// Nothing here writes a PDF; see writer.js for that.

import * as pdfjs from '../../vendor/pdfjs/pdf.min.mjs';

const base = new URL('../../vendor/pdfjs/', import.meta.url).href;
pdfjs.GlobalWorkerOptions.workerSrc = base + 'pdf.worker.min.mjs';

export class Cancelled extends Error {}

/**
 * Opens a PDF. `askPassword(wrong)` is called if one is needed and resolves
 * with the password, or null to give up. Returns a source for a Session.
 */
export async function openSource(name, bytes, askPassword, knownPassword) {
  let password = knownPassword;
  const task = pdfjs.getDocument({
    data: bytes.slice(), // pdf.js takes the buffer away; keep ours for saving
    password,
    cMapUrl: base + 'cmaps/',
    cMapPacked: true,
    standardFontDataUrl: base + 'standard_fonts/',
    wasmUrl: base + 'wasm/',
    iccUrl: base + 'iccs/',
    isEvalSupported: false,
    enableXfa: false,
    stopAtErrors: false,
  });
  task.onPassword = async (update, reason) => {
    const answer = await askPassword(reason === pdfjs.PasswordResponses.INCORRECT_PASSWORD);
    if (answer == null) { task.destroy(); return; }
    password = answer;
    update(answer);
  };
  let pdf;
  try {
    pdf = await task.promise;
  } catch (e) {
    if (task.destroyed || /destroyed|password/i.test(e?.name + e?.message)) throw new Cancelled();
    throw e;
  }
  const pages = [];
  for (let i = 1; i <= pdf.numPages; i++) {
    const page = await pdf.getPage(i);
    const vp = page.getViewport({ scale: 1 });
    pages.push({ w: vp.width, h: vp.height, rotate: page.rotate, transform: vp.transform });
  }
  return { name, bytes, password, pdf, pages, text: new Map() };
}

/**
 * Draws one page into a new canvas. `rot` is the user's extra turn in degrees,
 * `scale` is pixels per point. Returns {canvas, promise, cancel}.
 */
export function renderPage(source, index, rot, scale) {
  const canvas = document.createElement('canvas');
  let task;
  let cancelled = false;
  const promise = (async () => {
    const page = await source.pdf.getPage(index + 1);
    if (cancelled) throw new Cancelled();
    const viewport = page.getViewport({ scale, rotation: (page.rotate + rot) % 360 });
    canvas.width = Math.max(1, Math.round(viewport.width));
    canvas.height = Math.max(1, Math.round(viewport.height));
    const ctx = canvas.getContext('2d', { alpha: false });
    ctx.fillStyle = '#fff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    task = page.render({ canvasContext: ctx, canvas, viewport });
    try {
      await task.promise;
    } catch (e) {
      if (e?.name === 'RenderingCancelledException') throw new Cancelled();
      throw e;
    }
    return canvas;
  })();
  return { canvas, promise, cancel: () => { cancelled = true; task?.cancel(); } };
}

/**
 * The text of a page as pieces with their place in page space:
 * [{str, origin: [x, y], dir: [ux, uy], width, height}], where origin is the
 * start of the baseline, dir the writing direction, and height is measured
 * "up" from the baseline. Cached per page.
 */
export async function pageText(source, index) {
  if (source.text.has(index)) return source.text.get(index);
  const page = await source.pdf.getPage(index + 1);
  const vp = page.getViewport({ scale: 1 });
  const content = await page.getTextContent();
  const items = [];
  for (const it of content.items) {
    if (!it.str || !it.str.trim()) continue;
    const m = pdfjs.Util.transform(vp.transform, it.transform);
    const len = Math.hypot(m[0], m[1]) || 1;
    items.push({
      str: it.str,
      origin: [m[4], m[5]],
      dir: [m[0] / len, m[1] / len],
      width: it.width,
      height: it.height || len,
    });
  }
  source.text.set(index, items);
  return items;
}

/** The box (in page space) of characters [from, to) of a text piece. */
export function pieceRect(item, from, to) {
  const n = item.str.length || 1;
  const a = (item.width * from) / n, b = (item.width * to) / n;
  const [ux, uy] = item.dir;
  const up = [uy, -ux]; // a quarter turn from the writing direction, towards the top of the letters
  const lo = -item.height * 0.22, hi = item.height * 0.85;
  const pts = [[a, lo], [b, lo], [a, hi], [b, hi]].map(([s, v]) =>
    [item.origin[0] + ux * s + up[0] * v, item.origin[1] + uy * s + up[1] * v]);
  const xs = pts.map((p) => p[0]), ys = pts.map((p) => p[1]);
  return { l: Math.min(...xs), t: Math.min(...ys), r: Math.max(...xs), b: Math.max(...ys) };
}
