// Password: save a copy that needs a password to open (AES-256).

import { h, dialog } from '../core/ui.js';

async function run(host) {
  const first = h('input', { type: 'password', autocomplete: 'new-password', 'aria-label': 'Password' });
  const second = h('input', { type: 'password', autocomplete: 'new-password', 'aria-label': 'Repeat the password' });
  const problem = h('p.hint', { role: 'alert' });
  const password = await dialog({
    title: 'Save with a password',
    body: [
      h('p', 'The copy can only be opened with this password. There is no way to recover it if it is forgotten.'),
      h('label.field', 'Password', first), h('label.field', 'Repeat the password', second), problem,
    ],
    buttons: [{ label: 'Cancel', value: null }, {
      label: 'Continue', kind: 'primary',
      onclick: () => {
        if (!first.value) { problem.textContent = 'Enter a password.'; return false; }
        if (first.value !== second.value) { problem.textContent = 'The two passwords are not the same.'; return false; }
        return first.value;
      },
    }],
  });
  if (!password) return;
  host.save({ password, suggested: `${host.session.name.replace(/\.pdf$/i, '')}-protected.pdf`, title: 'Save protected copy' });
}

export default {
  id: 'protect',
  title: 'Password',
  description: 'Save a copy that needs a password to open.',
  menuActions: [{ label: 'Save with a password', run }],
};
