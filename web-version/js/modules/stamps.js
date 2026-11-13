// Stamps: one tap places a check mark, a cross or today's date, for forms
// that cannot be filled in directly.

import { newId, replaceElement, uprightTurns } from '../core/model.js';
import { textAt, fitText } from '../core/painter.js';
import { swatches, stepper, chips, INK_COLORS } from '../core/ui.js';

const ID = 'stamps';
const KINDS = [{ label: 'Check mark', value: 'check' }, { label: 'Cross', value: 'cross' }, { label: 'Today\'s date', value: 'date' }];
const state = { kind: 'check', size: 16, color: '#111111' };

const tool = {
  id: 'stamp', label: 'Stamp', icon: 'M4 13l5 5L20 6',
  hint: 'Tap the page to place one. Tap again for the next.',
  options: (host) => [
    chips(KINDS, state.kind, (k) => { state.kind = k; host.refreshOptions(); }),
    stepper('Size', state.size, 8, 60, 2, (s) => { state.size = s; host.refreshOptions(); }),
    swatches(INK_COLORS, state.color, (c) => { state.color = c; host.refreshOptions(); }),
  ],
  tap(host, pi, pt) {
    const turns = uprightTurns(host.session.model.pages[pi]);
    const base = { id: newId(), owner: ID, color: state.color, turns };
    if (state.kind === 'date') {
      const text = new Date().toLocaleDateString();
      const size = Math.round(state.size * 0.75);
      // the tap marks the middle of the first line's left edge, like writing on a line
      const off = [[0, -size * 0.7], [size * 0.7, 0], [0, size * 0.7], [-size * 0.7, 0]][turns];
      host.add(pi, textAt([pt[0] + off[0], pt[1] + off[1]], host.session.pageSize(pi), { ...base, text, fontSize: size }), false);
    } else {
      const half = state.size / 2;
      host.add(pi, {
        ...base, type: 'shape', kind: state.kind, width: Math.max(1.2, state.size / 9),
        bounds: { l: pt[0] - half, t: pt[1] - half, r: pt[0] + half, b: pt[1] + half },
      }, false);
    }
  },
};

export default {
  id: ID,
  title: 'Stamps',
  description: 'Check marks, crosses and today\'s date, for forms that cannot be filled in directly.',
  tools: [tool],
  selectionOptions(host, el, pi) {
    return [swatches(INK_COLORS, el.color, (color) =>
      host.session.edit((m) => replaceElement(m, pi, el.type === 'text' ? fitText({ ...el, color }) : { ...el, color })))];
  },
};
