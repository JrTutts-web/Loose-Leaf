// Annotate: highlighter, pen, shapes (line, arrow, rectangle, oval), white-out.

import { newId, rect, rw, rh, replaceElement } from '../core/model.js';
import { swatches, stepper, chips, hint, INK_COLORS } from '../core/ui.js';

const ID = 'annotate';
const HIGHLIGHTS = [
  { name: 'Yellow', value: '#ffe14d' },
  { name: 'Green', value: '#9be37a' },
  { name: 'Pink', value: '#ff9ecb' },
  { name: 'Blue', value: '#8fd0ff' },
];
const SHAPES = [
  { label: 'Line', value: 'line' }, { label: 'Arrow', value: 'arrow' },
  { label: 'Rectangle', value: 'rect' }, { label: 'Oval', value: 'oval' },
];
const state = { highlight: '#ffe14d', pen: { color: '#111111', width: 2 }, shape: { kind: 'arrow', color: '#c62828', width: 2 } };

/** A tool that drags out something between two points. `make(a, b)` returns the element, or null if too small. */
function dragTool(make, select) {
  let start = null;
  return {
    down(host, pi, pt) { start = pt; },
    move(host, pi, pt) { if (start) host.setDraft(pi, make(start, pt)); },
    up(host, pi, pt) {
      const el = start && make(start, pt);
      start = null;
      host.setDraft(pi, null);
      if (el) host.add(pi, el, select);
    },
    cancel(host) { start = null; host.setDraft(0, null); },
  };
}
const big = (a, b) => Math.abs(a[0] - b[0]) > 3 || Math.abs(a[1] - b[1]) > 3;

const highlight = {
  id: 'highlight', label: 'Highlight', icon: 'M4 21h16M8 16l-2-2 9-10 4 4-9 10z',
  hint: 'Drag across what you want to highlight.',
  options: (host) => [swatches(HIGHLIGHTS, state.highlight, (c) => { state.highlight = c; host.refreshOptions(); })],
  ...dragTool((a, b) => big(a, b) && {
    id: newId(), owner: ID, type: 'rect', bounds: rect(a[0], a[1], b[0], b[1]), color: state.highlight, alpha: 0.6, blend: 'multiply',
  }, false),
};

let stroke = null;
const pen = {
  id: 'pen', label: 'Pen', icon: 'M4 20l4-1L19 8l-3-3L5 16z',
  hint: 'Use two fingers to scroll and zoom while drawing.',
  options: (host) => [
    swatches(INK_COLORS, state.pen.color, (c) => { state.pen.color = c; host.refreshOptions(); }),
    stepper('Width', state.pen.width, 1, 12, 1, (w) => { state.pen.width = w; host.refreshOptions(); }),
  ],
  make: (points) => ({ id: newId(), owner: ID, type: 'ink', points, color: state.pen.color, width: state.pen.width }),
  down(host, pi, pt) { stroke = [pt]; },
  move(host, pi, pt) { if (stroke) { stroke.push(pt); host.setDraft(pi, pen.make(stroke)); } },
  up(host, pi, pt) {
    if (stroke) { host.setDraft(pi, null); host.add(pi, pen.make(stroke), false); }
    stroke = null;
  },
  tap(host, pi, pt) { host.add(pi, pen.make([pt]), false); },
  cancel(host) { stroke = null; host.setDraft(0, null); },
};

const shapes = {
  id: 'shapes', label: 'Shapes', icon: 'M4 13h8v7H4zM16 4a4 4 0 100 8 4 4 0 000-8z',
  hint: 'Drag on the page to draw.',
  options: (host) => [
    chips(SHAPES, state.shape.kind, (k) => { state.shape.kind = k; host.refreshOptions(); }),
    swatches(INK_COLORS, state.shape.color, (c) => { state.shape.color = c; host.refreshOptions(); }),
    stepper('Width', state.shape.width, 1, 12, 1, (w) => { state.shape.width = w; host.refreshOptions(); }),
  ],
  ...dragTool((a, b) => {
    if (!big(a, b)) return null;
    const { kind, color, width } = state.shape;
    const base = { id: newId(), owner: ID, color, width };
    return kind === 'line' || kind === 'arrow'
      ? { ...base, type: 'line', start: a, end: b, arrow: kind === 'arrow' }
      : { ...base, type: 'shape', kind, bounds: rect(a[0], a[1], b[0], b[1]) };
  }, false),
};

const whiteout = {
  id: 'whiteout', label: 'White-out', icon: 'M8 20h12M4 15l9-9 5 5-8 8H7z',
  hint: 'Drag over the area to cover. White-out hides what is underneath but does not remove it from the file.',
  ...dragTool((a, b) => big(a, b) && {
    id: newId(), owner: ID, type: 'rect', bounds: rect(a[0], a[1], b[0], b[1]), color: '#ffffff', alpha: 1, blend: 'normal',
  }, true),
};

export default {
  id: ID,
  title: 'Annotate',
  description: 'Highlighter, pen, shapes, arrows and white-out boxes.',
  tools: [highlight, pen, shapes, whiteout],
  selectionOptions(host, el, pi) {
    const set = (change) => host.session.edit((m) => replaceElement(m, pi, { ...el, ...change }));
    if (el.type === 'rect') {
      return el.blend === 'multiply'
        ? [swatches(HIGHLIGHTS, el.color, (color) => set({ color }))]
        : [hint('White-out box. Drag it to move, drag a corner to resize.')];
    }
    return [
      swatches(INK_COLORS, el.color, (color) => set({ color })),
      stepper('Width', el.width, 1, 12, 1, (width) => set({ width })),
    ];
  },
};
