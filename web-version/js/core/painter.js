// Drawing page elements on a canvas, and the geometry that the screen and the
// PDF writer must agree on (line breaking, arrow heads, check marks, turns).

import { rw, rh } from './model.js';

export const FONT = 'LooseLeaf Sans';
export const ASCENT = 1.069; // Noto Sans, as fractions of the font size
export const LINE = 1.362;

let fontReady;
/** Loads the bundled text font. Text boxes are measured with it, so wait for this before laying out text. */
export function loadFonts() {
  fontReady ??= (async () => {
    const face = new FontFace(FONT, `url(${new URL('../../fonts/NotoSans.ttf', import.meta.url).href})`);
    document.fonts.add(await face.load());
  })();
  return fontReady;
}

// ---------------------------------------------------------------- turns

/** Matrix [a, b, c, d, e, f] taking page space to the page as shown after a clockwise turn of `rot` degrees. */
export function rotMatrix(w, h, rot) {
  switch (((rot % 360) + 360) % 360) {
    case 90: return [0, 1, -1, 0, h, 0];
    case 180: return [-1, 0, 0, -1, w, h];
    case 270: return [0, -1, 1, 0, 0, w];
    default: return [1, 0, 0, 1, 0, 0];
  }
}
export const shownSize = (w, h, rot) => (rot % 180 ? { w: h, h: w } : { w, h });
export const apply = (m, x, y) => [m[0] * x + m[2] * y + m[4], m[1] * x + m[3] * y + m[5]];
export function invert(m) {
  const det = m[0] * m[3] - m[1] * m[2];
  return [m[3] / det, -m[1] / det, -m[2] / det, m[0] / det,
    (m[2] * m[5] - m[3] * m[4]) / det, (m[1] * m[4] - m[0] * m[5]) / det];
}
export const multiply = (m, n) => [ // first n, then m
  m[0] * n[0] + m[2] * n[1], m[1] * n[0] + m[3] * n[1],
  m[0] * n[2] + m[2] * n[3], m[1] * n[2] + m[3] * n[3],
  m[0] * n[4] + m[2] * n[5] + m[4], m[1] * n[4] + m[3] * n[5] + m[5]];

/** A rect in page space as its bounding box on the turned page, and back. */
export function mapRect(m, r) {
  const [x1, y1] = apply(m, r.l, r.t), [x2, y2] = apply(m, r.r, r.b);
  return { l: Math.min(x1, x2), t: Math.min(y1, y2), r: Math.max(x1, x2), b: Math.max(y1, y2) };
}

// ---------------------------------------------------------------- text

let measureCtx;
function measure(text, fontSize) {
  measureCtx ??= document.createElement('canvas').getContext('2d');
  measureCtx.font = `${fontSize}px "${FONT}"`;
  return measureCtx.measureText(text).width;
}

/** Breaks text into lines no wider than `width` points. */
export function layoutText(text, fontSize, width) {
  const lines = [];
  for (const para of text.split('\n')) {
    let line = '';
    for (const word of para.split(' ')) {
      const trial = line ? line + ' ' + word : word;
      if (measure(trial, fontSize) <= width || !line) {
        line = trial;
      } else {
        lines.push(line);
        line = word;
      }
      while (measure(line, fontSize) > width && line.length > 1) { // one word wider than the box
        let n = line.length - 1;
        while (n > 1 && measure(line.slice(0, n), fontSize) > width) n--;
        lines.push(line.slice(0, n));
        line = line.slice(n);
      }
    }
    lines.push(line);
  }
  return lines;
}

/** The content frame of a turned element: its centre and the width and height the content sees. */
export function frameOf(el) {
  const b = el.bounds, odd = (el.turns || 0) % 2 === 1;
  return { cx: (b.l + b.r) / 2, cy: (b.t + b.b) / 2, fw: odd ? rh(b) : rw(b), fh: odd ? rw(b) : rh(b), turns: el.turns || 0 };
}

/**
 * A text box works out its own height. Returns the element with its box made
 * just tall enough for its lines, growing away from where the text starts.
 */
export function fitText(el) {
  const { fw } = frameOf(el);
  const need = Math.max(1, layoutText(el.text, el.fontSize, fw).length) * el.fontSize * LINE;
  const b = { ...el.bounds };
  switch (el.turns || 0) {
    case 0: b.b = b.t + need; break;
    case 1: b.l = b.r - need; break;
    case 2: b.t = b.b - need; break;
    default: b.r = b.l + need;
  }
  return { ...el, bounds: b };
}

/**
 * A new text element whose first letter starts at `pt`, reading upright on a
 * page the user turned (`turns`), and no wider than the room left on the page.
 */
export function textAt(pt, info, props) {
  const [x, y] = pt, turns = props.turns || 0;
  const room = [info.w - x, info.h - y, x, y][turns];
  const w = Math.max(40, Math.min(naturalWidth(props.text, props.fontSize) + 2, room - 4));
  const bounds = [
    { l: x, t: y, r: x + w, b: y + 1 },
    { l: x - 1, t: y, r: x, b: y + w },
    { l: x - w, t: y - 1, r: x, b: y },
    { l: x, t: y - w, r: x + 1, b: y },
  ][turns];
  return fitText({ type: 'text', ...props, turns, bounds });
}

/** Width of the widest line if the text is not wrapped at all. */
export function naturalWidth(text, fontSize) {
  return Math.max(...text.split('\n').map((l) => measure(l, fontSize)), fontSize);
}

// ---------------------------------------------------------------- shared shapes

/** Where the shaft of an arrow stops and the three corners of its head. */
export function arrowGeometry(el) {
  const [x1, y1] = el.start, [x2, y2] = el.end;
  const len = Math.hypot(x2 - x1, y2 - y1) || 1;
  const ux = (x2 - x1) / len, uy = (y2 - y1) / len;
  const head = Math.min(len, Math.max(9, el.width * 4.5));
  const half = head * 0.45;
  const bx = x2 - ux * head, by = y2 - uy * head;
  return {
    shaftEnd: [x2 - ux * head * 0.8, y2 - uy * head * 0.8],
    head: [[x2, y2], [bx - uy * half, by + ux * half], [bx + uy * half, by - ux * half]],
  };
}

const UNIT_STROKES = {
  check: [[[0.04, 0.56], [0.36, 0.9], [0.96, 0.1]]],
  cross: [[[0.08, 0.08], [0.92, 0.92]], [[0.92, 0.08], [0.08, 0.92]]],
};

/** The strokes of a check mark or cross in page space, turned as the element says. */
export function shapeStrokes(el) {
  const b = el.bounds;
  return (UNIT_STROKES[el.kind] || []).map((stroke) => stroke.map(([x, y]) => {
    for (let i = 0; i < (el.turns || 0); i++) [x, y] = [1 - y, x];
    return [b.l + x * rw(b), b.t + y * rh(b)];
  }));
}

// ---------------------------------------------------------------- pictures

const images = new Map();
let onImageReady = () => {};
export function setImageReadyHandler(fn) { onImageReady = fn; }

/** The decoded picture for a data URL, or null while it is still loading. */
export function imageFor(src) {
  let entry = images.get(src);
  if (!entry) {
    entry = { img: new Image(), ready: false };
    entry.img.onload = () => { entry.ready = true; onImageReady(); };
    entry.img.src = src;
    images.set(src, entry);
  }
  return entry.ready ? entry.img : null;
}

/** Resolves when every picture used by these elements is decoded. */
export function imagesLoaded(elements) {
  return Promise.all(elements.filter((e) => e.type === 'image').map((e) => new Promise((resolve) => {
    const tick = () => (imageFor(e.src) ? resolve() : setTimeout(tick, 20));
    tick();
  })));
}

// ---------------------------------------------------------------- painting

/** Draws elements. The context must already be in page space (1 unit = 1 point). */
export function paintElements(ctx, elements) {
  for (const el of elements) paintElement(ctx, el);
}

export function paintElement(ctx, el) {
  ctx.save();
  switch (el.type) {
    case 'rect':
      ctx.globalAlpha = el.alpha ?? 1;
      ctx.globalCompositeOperation = el.blend === 'multiply' ? 'multiply' : 'source-over';
      ctx.fillStyle = el.color;
      ctx.fillRect(el.bounds.l, el.bounds.t, rw(el.bounds), rh(el.bounds));
      break;
    case 'ink':
      strokePath(ctx, [el.points], el.color, el.width);
      break;
    case 'line':
      if (el.arrow) {
        const g = arrowGeometry(el);
        strokePath(ctx, [[el.start, g.shaftEnd]], el.color, el.width, 'butt');
        ctx.fillStyle = el.color;
        ctx.beginPath();
        g.head.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
        ctx.fill();
      } else strokePath(ctx, [[el.start, el.end]], el.color, el.width);
      break;
    case 'shape': {
      const b = el.bounds;
      ctx.strokeStyle = el.color;
      ctx.lineWidth = el.width;
      if (el.kind === 'rect') {
        ctx.lineJoin = 'miter';
        ctx.strokeRect(b.l, b.t, rw(b), rh(b));
      } else if (el.kind === 'oval') {
        ctx.beginPath();
        ctx.ellipse((b.l + b.r) / 2, (b.t + b.b) / 2, rw(b) / 2, rh(b) / 2, 0, 0, Math.PI * 2);
        ctx.stroke();
      } else strokePath(ctx, shapeStrokes(el), el.color, el.width);
      break;
    }
    case 'text': {
      const f = frameOf(el);
      ctx.translate(f.cx, f.cy);
      ctx.rotate((f.turns * Math.PI) / 2);
      ctx.font = `${el.fontSize}px "${FONT}"`;
      ctx.fillStyle = el.color;
      ctx.textBaseline = 'alphabetic';
      layoutText(el.text, el.fontSize, f.fw).forEach((line, i) => {
        ctx.fillText(line, -f.fw / 2, -f.fh / 2 + i * el.fontSize * LINE + el.fontSize * ASCENT);
      });
      break;
    }
    case 'image': {
      const img = imageFor(el.src);
      if (!img) break;
      const f = frameOf(el);
      ctx.translate(f.cx, f.cy);
      ctx.rotate((f.turns * Math.PI) / 2);
      ctx.drawImage(img, -f.fw / 2, -f.fh / 2, f.fw, f.fh);
      break;
    }
    default: // hidden text is not drawn
  }
  ctx.restore();
}

function strokePath(ctx, strokes, color, width, cap = 'round') {
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = width;
  ctx.lineCap = cap;
  ctx.lineJoin = 'round';
  for (const pts of strokes) {
    if (!pts.length) continue;
    ctx.beginPath();
    if (pts.length === 1) {
      ctx.arc(pts[0][0], pts[0][1], width / 2, 0, Math.PI * 2);
      ctx.fill();
      continue;
    }
    pts.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
    ctx.stroke();
  }
}
