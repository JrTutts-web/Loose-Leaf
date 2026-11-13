// Text: tap to place a text box; change size and colour, move, resize, edit, delete.

import { newId, replaceElement, removeElement, uprightTurns } from '../core/model.js';
import { textAt, fitText } from '../core/painter.js';
import { swatches, stepper, button, promptBox, INK_COLORS } from '../core/ui.js';

const ID = 'text';
const state = { size: 12, color: '#111111' };

async function edit(host, el, pi) {
  const text = await promptBox({ title: 'Edit text', value: el.text, multiline: true });
  if (text == null) return;
  if (!text.trim()) return host.session.edit((m) => removeElement(m, pi, el.id));
  host.session.edit((m) => replaceElement(m, pi, fitText({ ...el, text })));
}

const tool = {
  id: 'text', label: 'Text', icon: 'M5 7V4h14v3M12 4v16M9 20h6',
  hint: 'Tap where the text should start.',
  options: (host) => [
    stepper('Size', state.size, 6, 72, 1, (s) => { state.size = s; host.refreshOptions(); }),
    swatches(INK_COLORS, state.color, (c) => { state.color = c; host.refreshOptions(); }),
  ],
  async tap(host, pi, pt) {
    const text = await promptBox({ title: 'Add text', multiline: true, ok: 'Add' });
    if (!text || !text.trim()) return;
    host.add(pi, textAt(pt, host.session.pageSize(pi), {
      id: newId(), owner: ID, text, fontSize: state.size, color: state.color, turns: uprightTurns(host.session.model.pages[pi]),
    }));
  },
};

export default {
  id: ID,
  title: 'Text',
  description: 'Type text anywhere on a page.',
  tools: [tool],
  editElement: edit,
  selectionOptions(host, el, pi) {
    const set = (change) => host.session.edit((m) => replaceElement(m, pi, fitText({ ...el, ...change })));
    const resize = (fontSize) => { // the box grows with the letters, as far as the page allows
      const info = host.session.pageSize(pi), b = { ...el.bounds }, k = fontSize / el.fontSize;
      if (el.turns === 1) b.b = Math.min(info.h, b.t + (b.b - b.t) * k);
      else if (el.turns === 2) b.l = Math.max(0, b.r - (b.r - b.l) * k);
      else if (el.turns === 3) b.t = Math.max(0, b.b - (b.b - b.t) * k);
      else b.r = Math.min(info.w, b.l + (b.r - b.l) * k);
      set({ fontSize, bounds: b });
    };
    return [
      button('Edit', () => edit(host, el, pi)),
      stepper('Size', el.fontSize, 6, 72, 1, resize),
      swatches(INK_COLORS, el.color, (color) => set({ color })),
    ];
  },
};
