// Model -> new PDF, with pdf-lib (vendor/pdf-lib). The original bytes are only
// read. Page elements are flattened into the page content, form values are
// written into the real fields, and pages with a redaction box are replaced by
// a picture of themselves so that what was under the box is not in the file.

import * as L from '../../vendor/pdf-lib/pdf-lib.esm.min.js';
import { renderPage } from './pdfsource.js';
import {
  paintElements, imagesLoaded, rotMatrix, invert, mapRect, layoutText, frameOf,
  arrowGeometry, shapeStrokes, ASCENT, LINE,
} from './painter.js';
import { rw, rh, overlaps } from './model.js';

const { PDFDocument, PDFName, PDFNumber, PDFDict, PDFArray, PDFRef, PDFStream, degrees } = L;

let fontBytes;
async function textFont(doc) {
  fontBytes ??= new Uint8Array(await (await fetch(new URL('../../fonts/NotoSans.ttf', import.meta.url))).arrayBuffer());
  doc.registerFontkit(window.fontkit);
  // The whole font goes in (about 0.3 MB once compressed). fontkit's subsetter fails on this font.
  return doc.embedFont(fontBytes, { subset: false });
}

async function loadForWriting(source) {
  try {
    return await PDFDocument.load(source.bytes, { password: source.password ?? undefined });
  } catch (e) {
    if (source.password == null && /encrypt/i.test(e?.name + e?.message)) {
      return PDFDocument.load(source.bytes, { password: '' }); // protected against changes only
    }
    throw e;
  }
}

/**
 * Writes the document and resolves with the bytes of the new file.
 *   model        defaults to the session's (Pages passes a part of it to extract pages)
 *   password     encrypt the copy (AES-256)
 *   postProcess  async (pdfDoc, lib) => void, a last step on the finished document (Compress)
 *   progress     (text) => void
 */
export async function writePdf(session, { model = session.model, password, postProcess, progress = () => {} } = {}) {
  const docs = new Map();
  const out = await loadForWriting(session.sources[0]);
  docs.set(0, out);
  const originals = out.getPages();
  let font = null;
  const getFont = async () => (font ??= await textFont(out));

  if (Object.keys(model.form || {}).length) await applyFormValues(out, model.form, getFont);

  const used = new Set();
  const order = [];
  for (let i = 0; i < model.pages.length; i++) {
    const entry = model.pages[i];
    progress(`Writing page ${i + 1} of ${model.pages.length}`);
    const info = session.sources[entry.src].pages[entry.index];
    const raster = needsRaster(entry) ? await rasterize(session, entry) : null;
    let page, toPage;
    if (raster) {
      if (entry.src === 0) dropFieldsOn(out, originals[entry.index]);
      page = out.addPage([info.w, info.h]);
      const jpg = await out.embedJpg(raster.bytes);
      page.drawImage(jpg, { x: 0, y: 0, width: info.w, height: info.h });
      page.setRotation(degrees(entry.rot));
      toPage = [1, 0, 0, -1, 0, info.h];
    } else {
      const key = `${entry.src}:${entry.index}`;
      if (entry.src === 0 && !used.has(key)) {
        page = originals[entry.index];
      } else {
        let from = entry.src === 0 ? docs.get('again') : docs.get(entry.src);
        if (!from) {
          from = await loadForWriting(session.sources[entry.src]);
          docs.set(entry.src === 0 ? 'again' : entry.src, from);
        }
        [page] = await out.copyPages(from, [entry.index]);
      }
      used.add(key);
      page.setRotation(degrees((info.rotate + entry.rot) % 360));
      toPage = invert(info.transform);
    }
    const elements = raster ? raster.remaining : entry.elements;
    if (elements.length) await drawElements(out, page, toPage, info, elements, getFont);
    order.push(page);
  }

  // Put the pages in the new order. Pages that were removed lose their content.
  const rootRef = out.catalog.get(PDFName.of('Pages'));
  const root = out.catalog.Pages();
  for (const p of order) {
    for (const k of ['Resources', 'MediaBox', 'CropBox', 'Rotate']) {
      const name = PDFName.of(k);
      const inherited = p.node.getInheritableAttribute(name);
      if (inherited && !p.node.get(name)) p.node.set(name, inherited);
    }
  }
  const kept = new Set(order.map((p) => p.ref.tag));
  for (const p of originals) {
    if (kept.has(p.ref.tag)) continue;
    for (const k of ['Contents', 'Resources', 'Annots', 'Thumb', 'B']) p.node.delete(PDFName.of(k));
  }
  root.set(PDFName.of('Kids'), out.context.obj(order.map((p) => p.ref)));
  root.set(PDFName.of('Count'), PDFNumber.of(order.length));
  for (const p of order) p.node.setParent(rootRef);
  out.pageCache?.invalidate?.();

  if (postProcess) {
    progress('Finishing');
    await postProcess(out, L);
  }
  await out.flush();
  sweep(out);
  if (password) {
    out.encrypt({
      userPassword: password, ownerPassword: password,
      permissions: { printing: 'highResolution', modifying: true, copying: true, annotating: true, fillingForms: true, contentAccessibility: true, documentAssembly: true },
    });
  }
  progress('Finishing');
  return out.save();
}

// ---------------------------------------------------------------- forms

async function applyFormValues(doc, values, getFont) {
  const form = doc.getForm();
  let any = false;
  for (const [name, value] of Object.entries(values)) {
    try {
      const field = form.getField(name);
      const kind = field.constructor.name;
      if (typeof field.setText === 'function') field.setText(value === '' ? undefined : String(value));
      else if (typeof field.check === 'function') (value ? field.check() : field.uncheck());
      else if (typeof field.select === 'function') {
        if (value === '' || value == null) field.clear?.();
        else field.select(value);
      } else console.warn('unsupported field', name, kind);
      any = true;
    } catch (e) {
      console.warn('could not set field', name, e);
    }
  }
  if (!any) return;
  try {
    form.updateFieldAppearances(); // the built-in font: nothing to embed
  } catch {
    try { form.updateFieldAppearances(await getFont()); } catch (e) { console.warn('field appearances', e); } // letters it lacks
  }
}

/** A page that is saved as a picture must not keep live fields that still hold what was redacted. */
function dropFieldsOn(doc, page) {
  try {
    const onPage = new Set((page.node.Annots()?.asArray() || []).filter((a) => a instanceof PDFRef).map((a) => a.tag));
    if (!onPage.size) return;
    const form = doc.getForm();
    for (const field of form.getFields()) {
      const refs = field.acroField.getWidgets().map((w) => doc.context.getObjectRef(w.dict)?.tag);
      if (refs.some((t) => t && onPage.has(t))) {
        try { form.removeField(field); } catch (e) { console.warn('could not remove field', e); }
      }
    }
  } catch (e) {
    console.warn('form pruning', e);
  }
}

// ---------------------------------------------------------------- redaction

const lastErase = (entry) => entry.elements.findLastIndex((e) => e.type === 'rect' && e.erase);
export const needsRaster = (entry) => lastErase(entry) >= 0;

/**
 * Turns a page into a 200 dpi picture with everything up to its last
 * redaction box painted in. Returns the JPEG and the elements that still have
 * to be drawn on top (later ones, and OCR text that is not under a box).
 */
export async function rasterize(session, entry) {
  const source = session.sources[entry.src];
  const info = source.pages[entry.index];
  const cut = lastErase(entry);
  const scale = Math.min(200 / 72, 3000 / Math.max(info.w, info.h));
  const canvas = await renderPage(source, entry.index, 0, scale).promise;
  const burned = entry.elements.slice(0, cut + 1);
  await imagesLoaded(burned);
  const ctx = canvas.getContext('2d');
  ctx.setTransform(canvas.width / info.w, 0, 0, canvas.height / info.h, 0, 0);
  paintElements(ctx, burned);
  const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.9));
  const bytes = new Uint8Array(await blob.arrayBuffer());
  canvas.width = canvas.height = 0;

  const boxes = entry.elements.filter((e) => e.type === 'rect' && e.erase).map((e) => e.bounds);
  const remaining = [];
  entry.elements.forEach((el, i) => {
    if (el.type === 'hidden') {
      const m = rotMatrix(info.w, info.h, el.frameRotation || 0);
      const shown = boxes.map((b) => mapRect(m, b));
      const words = el.words.filter((w) => !shown.some((b) => overlaps(b, w.rect)));
      if (words.length) remaining.push({ ...el, words });
    } else if (i > cut) remaining.push(el);
  });
  return { bytes, remaining };
}

// ---------------------------------------------------------------- page elements

const builtInFonts = new WeakMap(); // one Helvetica per written document
const hex = (c) => [1, 3, 5].map((i) => parseInt(c.slice(i, i + 2), 16) / 255);

async function drawElements(doc, page, toPage, info, elements, getFont) {
  const ops = [L.pushGraphicsState(), L.concatTransformationMatrix(...toPage)];
  const names = new Map();
  const gs = (blend, alpha) => {
    const key = `gs:${blend}:${alpha}`;
    if (!names.has(key)) {
      names.set(key, page.node.newExtGState('LL', doc.context.obj({
        Type: 'ExtGState', ca: alpha, CA: alpha, BM: blend === 'multiply' ? 'Multiply' : 'Normal',
      })));
    }
    return names.get(key);
  };
  let font = null, fontName = null;
  const useFont = async () => {
    if (!font) {
      font = await getFont();
      fontName = page.node.newFontDictionary('LLF', font.ref);
    }
  };
  const path = (pts) => pts.map(([x, y], i) => (i ? L.lineTo(x, y) : L.moveTo(x, y)));
  const strokes = (lists, color, width, cap = 1) => [
    L.setStrokingRgbColor(...hex(color)), L.setLineWidth(width), L.setLineCap(cap), L.setLineJoin(1),
    ...lists.flatMap((pts) => (pts.length === 1 ? [...path([pts[0], pts[0]]), L.stroke()] : [...path(pts), L.stroke()])),
  ];
  const turn = (f) => {
    const a = (f.turns * Math.PI) / 2, c = Math.round(Math.cos(a)), s = Math.round(Math.sin(a));
    return L.concatTransformationMatrix(c, s, -s, c, f.cx, f.cy);
  };

  for (const el of elements) {
    ops.push(L.pushGraphicsState());
    switch (el.type) {
      case 'rect':
        ops.push(L.setGraphicsState(gs(el.blend, el.alpha ?? 1)), L.setFillingRgbColor(...hex(el.color)),
          L.rectangle(el.bounds.l, el.bounds.t, rw(el.bounds), rh(el.bounds)), L.fill());
        break;
      case 'ink':
        ops.push(...strokes([el.points], el.color, el.width));
        break;
      case 'line':
        if (el.arrow) {
          const g = arrowGeometry(el);
          ops.push(...strokes([[el.start, g.shaftEnd]], el.color, el.width, 0),
            L.setFillingRgbColor(...hex(el.color)), ...path(g.head), L.closePath(), L.fill());
        } else ops.push(...strokes([[el.start, el.end]], el.color, el.width));
        break;
      case 'shape': {
        const b = el.bounds;
        if (el.kind === 'rect') {
          ops.push(L.setStrokingRgbColor(...hex(el.color)), L.setLineWidth(el.width), L.setLineJoin(0),
            L.rectangle(b.l, b.t, rw(b), rh(b)), L.stroke());
        } else if (el.kind === 'oval') {
          const cx = (b.l + b.r) / 2, cy = (b.t + b.b) / 2, rx = rw(b) / 2, ry = rh(b) / 2, k = 0.5523;
          ops.push(L.setStrokingRgbColor(...hex(el.color)), L.setLineWidth(el.width),
            L.moveTo(cx + rx, cy),
            L.appendBezierCurve(cx + rx, cy + ry * k, cx + rx * k, cy + ry, cx, cy + ry),
            L.appendBezierCurve(cx - rx * k, cy + ry, cx - rx, cy + ry * k, cx - rx, cy),
            L.appendBezierCurve(cx - rx, cy - ry * k, cx - rx * k, cy - ry, cx, cy - ry),
            L.appendBezierCurve(cx + rx * k, cy - ry, cx + rx, cy - ry * k, cx + rx, cy),
            L.closePath(), L.stroke());
        } else ops.push(...strokes(shapeStrokes(el), el.color, el.width));
        break;
      }
      case 'text': {
        await useFont();
        const f = frameOf(el);
        ops.push(turn(f), L.setFillingRgbColor(...hex(el.color)), L.beginText(), L.setFontAndSize(fontName, el.fontSize));
        layoutText(el.text, el.fontSize, f.fw).forEach((line, i) => {
          if (!line) return;
          ops.push(L.setTextMatrix(1, 0, 0, -1, -f.fw / 2, -f.fh / 2 + i * el.fontSize * LINE + el.fontSize * ASCENT),
            L.showText(font.encodeText(line)));
        });
        ops.push(L.endText());
        break;
      }
      case 'image': {
        if (!names.has(el.src)) {
          const png = await doc.embedPng(el.src);
          names.set(el.src, page.node.newXObject('LLI', png.ref));
        }
        const f = frameOf(el);
        ops.push(turn(f), L.concatTransformationMatrix(f.fw, 0, 0, -f.fh, -f.fw / 2, f.fh / 2), L.drawObject(names.get(el.src)));
        break;
      }
      case 'hidden': {
        // Words a built-in PDF font can write use that one, which costs nothing in the file;
        // only the others need the bundled font.
        if (!names.has('helv')) {
          if (!builtInFonts.has(doc)) builtInFonts.set(doc, await doc.embedFont(L.StandardFonts.Helvetica));
          const helv = builtInFonts.get(doc);
          names.set('helv', { font: helv, name: page.node.newFontDictionary('LLH', helv.ref) });
        }
        const builtIn = names.get('helv');
        ops.push(L.concatTransformationMatrix(...invert(rotMatrix(info.w, info.h, el.frameRotation || 0))),
          L.beginText(), L.setTextRenderingMode(3));
        for (const w of el.words) {
          const size = Math.max(1, rh(w.rect) * 0.85);
          let f = builtIn.font, name = builtIn.name, encoded;
          try {
            encoded = f.encodeText(w.text);
          } catch {
            await useFont();
            f = font; name = fontName;
            encoded = f.encodeText(w.text);
          }
          const natural = f.widthOfTextAtSize(w.text, size);
          if (!(natural > 0)) continue;
          ops.push(L.setFontAndSize(name, size),
            L.setTextMatrix(rw(w.rect) / natural, 0, 0, -1, w.rect.l, w.rect.b - rh(w.rect) * 0.2),
            L.showText(encoded));
        }
        ops.push(L.endText());
        break;
      }
      default:
    }
    ops.push(L.popGraphicsState());
  }
  ops.push(L.popGraphicsState());
  page.pushOperators(...ops);
}

// ---------------------------------------------------------------- clean-up

/** Deletes every object that nothing refers to any more (content of removed or redacted pages). */
function sweep(doc) {
  const ctx = doc.context;
  const seen = new Set();
  const stack = [];
  const visit = (o) => {
    if (o instanceof PDFRef) {
      if (seen.has(o.tag)) return;
      seen.add(o.tag);
      const target = ctx.lookup(o);
      if (target) stack.push(target);
    } else if (o) stack.push(o);
  };
  for (const key of ['Root', 'Info', 'Encrypt', 'ID']) visit(ctx.trailerInfo[key]);
  while (stack.length) {
    const o = stack.pop();
    if (o instanceof PDFDict) for (const [, v] of o.entries()) visit(v);
    else if (o instanceof PDFArray) for (const v of o.asArray()) visit(v);
    else if (o instanceof PDFStream) stack.push(o.dict);
  }
  for (const [ref] of ctx.enumerateIndirectObjects()) if (!seen.has(ref.tag)) ctx.delete(ref);
}
