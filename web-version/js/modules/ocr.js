// OCR: recognise the text of scanned pages so they can be searched and the
// text can be selected in the saved copy. Tesseract runs in this browser
// (vendor/tesseract, with the English data file); nothing is sent anywhere.

import { newId, addElement } from '../core/model.js';
import { renderPage, pageText } from '../core/pdfsource.js';
import { h, dialog, confirmBox, alertBox, toast } from '../core/ui.js';

const ID = 'ocr';
const base = new URL('../../vendor/tesseract/', import.meta.url).href;

async function run(host) {
  const session = host.session;
  if (!(await confirmBox('Recognise text',
    'Reads the text on scanned pages so that Find works on them and the saved copy can be searched. Pages that already have text are skipped. English only. This can take a while on long documents.', 'Start'))) return;

  let stop = false;
  const label = h('p', 'Getting ready…');
  let closeBox;
  dialog({
    title: 'Recognising text', dismissable: false,
    body: (close) => { closeBox = close; return [h('div.spinner'), label]; },
    buttons: [{ label: 'Stop', onclick: () => { stop = true; label.textContent = 'Stopping…'; return false; } }],
  });

  let worker;
  const results = []; // [{page, element}]
  let skipped = 0;
  try {
    const T = await import('../../vendor/tesseract/tesseract.esm.min.js');
    const createWorker = T.createWorker || T.default.createWorker;
    worker = await createWorker('eng', 1, {
      workerPath: base + 'worker.min.js', corePath: base, langPath: base + 'lang/',
      gzip: false, workerBlobURL: false, cacheMethod: 'none',
    });
    const pages = session.model.pages;
    for (let pi = 0; pi < pages.length && !stop; pi++) {
      const entry = pages[pi];
      label.textContent = `Page ${pi + 1} of ${pages.length}`;
      const source = session.sources[entry.src];
      const existing = (await pageText(source, entry.index)).reduce((n, it) => n + it.str.trim().length, 0);
      if (existing > 20 || entry.elements.some((e) => e.type === 'hidden')) { skipped++; continue; }
      const info = session.pageSize(pi);
      const scale = Math.min(200 / 72, 2600 / Math.max(info.w, info.h));
      const canvas = await renderPage(source, entry.index, entry.rot, scale).promise;
      const { data } = await worker.recognize(canvas, {}, { blocks: true, text: false });
      canvas.width = canvas.height = 0;
      const words = [];
      for (const block of data.blocks || []) {
        for (const para of block.paragraphs || []) {
          for (const line of para.lines || []) {
            for (const w of line.words || []) {
              const text = (w.text || '').trim();
              if (!text || w.confidence < 30) continue;
              const b = w.bbox;
              words.push({ text, rect: { l: b.x0 / scale, t: b.y0 / scale, r: b.x1 / scale, b: b.y1 / scale } });
            }
          }
        }
      }
      if (words.length) results.push({ page: pi, element: { id: newId(), owner: ID, type: 'hidden', words, frameRotation: entry.rot } });
    }
  } catch (e) {
    console.error(e);
    closeBox();
    await worker?.terminate();
    return alertBox('Could not recognise text', String(e?.message || e));
  }
  await worker.terminate();
  closeBox();
  if (results.length) session.edit((m) => results.reduce((acc, r) => addElement(acc, r.page, r.element), m));
  const n = results.length;
  const found = results.reduce((sum, r) => sum + r.element.words.length, 0);
  toast(n
    ? `Text recognised on ${n} ${n === 1 ? 'page' : 'pages'} (${found} words). Use Find to search it.`
    : (skipped ? 'Every page already has text.' : 'No text was found.'));
}

export default {
  id: ID,
  title: 'OCR',
  description: 'Recognise the text of scanned pages (English), on this device.',
  menuActions: [{ label: 'Recognise text', run }],
};
