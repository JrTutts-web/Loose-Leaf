// Redact: black boxes whose content is really removed from the saved copy.
// The module only adds the box; the writer sees `erase` and saves that page
// as a picture (core/writer.js).

import { newId, rect } from '../core/model.js';
import { hint } from '../core/ui.js';

const ID = 'redact';
let start = null;
const make = (a, b) => (Math.abs(a[0] - b[0]) > 3 && Math.abs(a[1] - b[1]) > 3) && {
  id: newId(), owner: ID, type: 'rect', bounds: rect(a[0], a[1], b[0], b[1]), color: '#000000', alpha: 1, blend: 'normal', erase: true,
};

const tool = {
  id: 'redact', label: 'Redact', icon: 'M3 9h18v6H3zM7 12h10',
  hint: 'Drag over what must go. In the saved copy this page becomes a picture and what is under the box is removed.',
  down(host, pi, pt) { start = pt; },
  move(host, pi, pt) { if (start) host.setDraft(pi, make(start, pt)); },
  up(host, pi, pt) {
    const el = start && make(start, pt);
    start = null;
    host.setDraft(pi, null);
    if (el) host.add(pi, el);
  },
  cancel(host) { start = null; host.setDraft(0, null); },
};

export default {
  id: ID,
  title: 'Redact',
  description: 'Black boxes that really remove what is under them from the saved copy.',
  tools: [tool],
  selectionOptions: () => [hint('Redaction box. Its page is saved as a picture, so text on it can no longer be selected.')],
};
