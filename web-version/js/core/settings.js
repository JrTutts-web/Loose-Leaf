// The Settings screen: which modules are on, and what the app is.

import { h, showScreen } from './ui.js';
import { allModules, isEnabled, setEnabled } from '../modules/registry.js';

export function showSettings(onChange = () => {}) {
  showScreen('Settings', (body) => {
    body.append(
      h('h2', 'Modules'),
      h('p.hint', 'Switch off what you do not use. Things a module already put on a page stay there and are saved.'),
      h('div.list', allModules.map((m) => {
        const box = h('input', { type: 'checkbox', role: 'switch', id: `mod-${m.id}` });
        box.checked = isEnabled(m);
        box.addEventListener('change', () => { setEnabled(m, box.checked); onChange(); });
        return h('label.row', { for: `mod-${m.id}` }, h('div', h('strong', m.title), h('p.hint', m.description)), box);
      })),
      h('h2', 'About'),
      h('p', 'Loose Leaf, web version. Your PDFs are opened and changed inside this browser tab. Nothing is uploaded, with one exception that is off unless you switch it on: the Library module sends copies you save to the computer this page comes from. There are no accounts and no analytics, and the page never contacts any other site.'),
      h('p', 'Unsaved edits and saved signatures are kept in this browser on this device only. Clearing the browser\'s site data removes them.'),
      h('p.hint', 'GPL-3.0-or-later. Uses pdf.js (Apache-2.0), pdf-lib and fontkit (MIT), Tesseract.js (Apache-2.0), and the fonts Noto Sans, Great Vibes, Allura, Sacramento and Kalam (SIL OFL 1.1).'),
    );
  });
}
