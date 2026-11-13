// Compress: save a smaller copy by re-encoding the pictures inside the PDF.
// The module hands the writer a last step that works on the finished
// document; text and drawings are not touched.

import { h, button, showScreen, formatSize, busy } from '../core/ui.js';

const LEVELS = [
  { id: 'light', label: 'Light', text: 'Best quality. Good for printing.', side: 2400, quality: 0.8 },
  { id: 'balanced', label: 'Balanced', text: 'Smaller, still easy to read on screen.', side: 1600, quality: 0.6 },
  { id: 'strong', label: 'Strong', text: 'Smallest file. Pictures and scans look noticeably rougher.', side: 1100, quality: 0.4 },
];

/** Re-encodes JPEG pictures that get smaller at this level. Returns how many were replaced. */
async function recompress(doc, L, level) {
  const { PDFName, PDFRawStream, PDFArray, PDFNumber } = L;
  const name = (v) => (v instanceof PDFName ? v.decodeText() : null);
  let replaced = 0;
  for (const [ref, obj] of doc.context.enumerateIndirectObjects()) {
    if (!(obj instanceof PDFRawStream)) continue;
    const d = obj.dict;
    if (name(d.lookup(PDFName.of('Subtype'))) !== 'Image') continue;
    let filter = d.lookup(PDFName.of('Filter'));
    if (filter instanceof PDFArray) filter = filter.size() === 1 ? filter.lookup(0) : null;
    if (name(filter) !== 'DCTDecode') continue;
    if (d.has(PDFName.of('Decode')) || d.has(PDFName.of('ImageMask'))) continue;
    let space = d.lookup(PDFName.of('ColorSpace'));
    if (space instanceof PDFArray) {
      const kind = name(space.lookup(0));
      const n = kind === 'ICCBased' ? space.lookup(1)?.dict?.lookup(PDFName.of('N'))?.asNumber?.() : null;
      space = kind === 'CalRGB' || n === 3 ? 'DeviceRGB' : (kind === 'CalGray' || n === 1 ? 'DeviceGray' : null);
    } else space = name(space);
    if (space !== 'DeviceRGB' && space !== 'DeviceGray') continue; // CMYK and the like are left alone
    try {
      const bitmap = await createImageBitmap(new Blob([obj.contents], { type: 'image/jpeg' }));
      const scale = Math.min(1, level.side / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(bitmap.width * scale));
      canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      const ctx = canvas.getContext('2d', { alpha: false });
      ctx.imageSmoothingQuality = 'high';
      ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      bitmap.close?.();
      const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', level.quality));
      const bytes = new Uint8Array(await blob.arrayBuffer());
      const size = [canvas.width, canvas.height];
      canvas.width = canvas.height = 0;
      if (bytes.length >= obj.contents.length * 0.9) continue;
      d.set(PDFName.of('Width'), PDFNumber.of(size[0]));
      d.set(PDFName.of('Height'), PDFNumber.of(size[1]));
      d.set(PDFName.of('ColorSpace'), PDFName.of('DeviceRGB'));
      d.set(PDFName.of('BitsPerComponent'), PDFNumber.of(8));
      d.set(PDFName.of('Filter'), PDFName.of('DCTDecode'));
      d.delete(PDFName.of('DecodeParms'));
      doc.context.assign(ref, PDFRawStream.of(d, bytes));
      replaced++;
    } catch (e) {
      console.warn('picture left as it is', e);
    }
  }
  return replaced;
}

function openScreen(host) {
  let level = LEVELS[1];
  showScreen('Compress', (body) => {
    const result = h('div.result');
    const run = async () => {
      result.replaceChildren();
      const plain = await host.write();
      if (!plain) return;
      let count = 0;
      const small = await host.write({ postProcess: async (doc, L) => { count = await recompress(doc, L, level); } });
      if (!small) return;
      const gain = Math.round((1 - small.length / plain.length) * 100);
      const lines = [h('p', h('strong', 'Before: '), formatSize(plain.length)), h('p', h('strong', 'After: '), formatSize(small.length))];
      if (!count) lines.push(h('p', 'Nothing to shrink: this PDF has no pictures that would get smaller at this level.'));
      else if (gain < 1) lines.push(h('p', 'This level does not make the file smaller. Try a stronger one.'));
      else {
        lines.push(h('p', `${gain}% smaller. ${count} ${count === 1 ? 'picture was' : 'pictures were'} re-encoded.`),
          button('Save compressed copy', () => host.save({
            postProcess: (doc, L) => recompress(doc, L, level),
            suggested: `${host.session.name.replace(/\.pdf$/i, '')}-compressed.pdf`, title: 'Save compressed copy',
          }), 'primary'));
      }
      result.replaceChildren(...lines);
    };
    body.append(
      h('p', 'Makes a smaller copy by shrinking the pictures inside the PDF. Text stays sharp. The document you are editing is not changed.'),
      h('div.levels', { role: 'radiogroup', 'aria-label': 'Level' }, LEVELS.map((l) => {
        const radio = h('input', { type: 'radio', name: 'level', id: `lvl-${l.id}` });
        radio.checked = l === level;
        radio.addEventListener('change', () => { level = l; result.replaceChildren(); });
        return h('label', { for: `lvl-${l.id}` }, radio, h('div', h('strong', l.label), h('p.hint', l.text)));
      })),
      h('div.actions', button('Compress', run, 'primary')),
      result);
  });
}

export default {
  id: 'compress',
  title: 'Compress',
  description: 'Save a smaller copy by reducing picture quality.',
  menuActions: [{ label: 'Compress', run: openScreen }],
};
