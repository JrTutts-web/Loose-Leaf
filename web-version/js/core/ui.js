// Small DOM helpers shared by the core and by modules: elements, dialogs,
// full-screen module screens, and the widgets used in option rows.

/** h('div.cls', {onclick}, child, 'text', [more]) */
export function h(spec, ...rest) {
  const [tag, ...classes] = spec.split('.');
  const el = document.createElement(tag || 'div');
  if (classes.length) el.className = classes.join(' ');
  for (const item of rest.flat(Infinity)) {
    if (item == null || item === false) continue;
    if (item instanceof Node) el.append(item);
    else if (typeof item === 'object') {
      for (const [k, v] of Object.entries(item)) {
        if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
        else if (k === 'style') Object.assign(el.style, v);
        else if (v === true) el.setAttribute(k, '');
        else if (v !== false && v != null) el.setAttribute(k, v);
      }
    } else el.append(String(item));
  }
  return el;
}

/** A 24x24 line icon from path data. */
export function icon(d, size = 24) {
  const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('width', size);
  svg.setAttribute('height', size);
  svg.setAttribute('aria-hidden', 'true');
  const p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
  p.setAttribute('d', d);
  svg.append(p);
  return svg;
}

export const icons = {
  back: 'M15 5l-7 7 7 7',
  undo: 'M9 7L4 12l5 5M4 12h10a6 6 0 010 12h-2',
  redo: 'M15 7l5 5-5 5M20 12H10a6 6 0 000 12h2',
  more: 'M12 5v.01M12 12v.01M12 19v.01',
  save: 'M12 3v12m0 0l-5-5m5 5l5-5M4 20h16',
  close: 'M6 6l12 12M18 6L6 18',
  trash: 'M5 7h14M10 7V4h4v3M7 7l1 13h8l1-13',
  plus: 'M12 5v14M5 12h14',
  minus: 'M5 12h14',
  settings: 'M4 7h10M18 7h2M4 17h2M10 17h10M16 4v6M8 14v6',
  hand: 'M5 3l5 17 3-7 7-3z',
};

export function iconButton(d, label, onclick, extra = {}) {
  return h('button.icon-btn', { type: 'button', title: label, 'aria-label': label, onclick, ...extra }, icon(d));
}

export function button(label, onclick, kind = '') {
  return h('button.btn' + (kind ? '.' + kind : ''), { type: 'button', onclick }, label);
}

// ---------------------------------------------------------------- dialogs

/**
 * Shows a dialog. `build(close)` returns the body nodes; buttons is a list of
 * {label, kind, value | onclick}. Resolves with the value of the button that
 * was pressed, or undefined when dismissed.
 */
export function dialog({ title, body, buttons = [], dismissable = true }) {
  return new Promise((resolve) => {
    let done = false;
    const close = (value) => {
      if (done) return;
      done = true;
      back.remove();
      resolve(value);
    };
    const content = typeof body === 'function' ? body(close) : body;
    const box = h('div.dialog', { role: 'dialog', 'aria-modal': 'true' },
      title && h('h2', title),
      h('div.dialog-body', content),
      buttons.length ? h('div.dialog-buttons', buttons.map((b) =>
        button(b.label, async () => {
          if (b.onclick) {
            const r = await b.onclick();
            if (r === false) return;
            close(r);
          } else close(b.value);
        }, b.kind))) : null);
    const back = h('div.backdrop', {
      onpointerdown: (e) => { if (e.target === back && dismissable) close(undefined); },
    }, box);
    document.body.append(back);
    const first = box.querySelector('input, textarea');
    if (first) setTimeout(() => first.focus(), 30);
  });
}

export function alertBox(title, text) {
  return dialog({ title, body: h('p', text), buttons: [{ label: 'OK', kind: 'primary', value: true }] });
}

export function confirmBox(title, text, yes = 'OK', no = 'Cancel') {
  return dialog({
    title, body: h('p', text),
    buttons: [{ label: no, value: false }, { label: yes, kind: 'primary', value: true }],
  }).then((v) => v === true);
}

/** Asks for one line (or, with multiline, several lines) of text. Resolves null when cancelled. */
export function promptBox({ title, text, value = '', label = '', multiline = false, type = 'text', ok = 'OK', inputmode }) {
  const input = multiline
    ? h('textarea', { rows: 4, 'aria-label': label || title })
    : h('input', { type, 'aria-label': label || title, inputmode, autocomplete: 'off' });
  input.value = value;
  let closer;
  if (!multiline) input.addEventListener('keydown', (e) => { if (e.key === 'Enter') closer(input.value); });
  return dialog({
    title,
    body: (close) => { closer = close; return [text && h('p', text), input]; },
    buttons: [{ label: 'Cancel', value: null }, { label: ok, kind: 'primary', onclick: () => input.value }],
  }).then((v) => (v == null ? null : v));
}

/** A modal that cannot be dismissed, for long work. Returns {set(text), close()}. */
export function busy(text) {
  const label = h('p', text);
  const back = h('div.backdrop', h('div.dialog.busy', h('div.spinner'), label));
  document.body.append(back);
  return { set: (t) => { label.textContent = t; }, close: () => back.remove(), box: back.firstChild };
}

let toastTimer;
export function toast(text) {
  document.querySelector('.toast')?.remove();
  const t = h('div.toast', { role: 'status' }, text);
  document.body.append(t);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.remove(), 3200);
}

/**
 * A full-screen page on top of the editor (Pages, Signatures, Settings...).
 * `build(body, close)` fills it in. Returns a function that closes it.
 */
export function showScreen(title, build, actions = []) {
  const body = h('div.screen-body');
  const close = () => screen.remove();
  const screen = h('div.screen',
    h('header.bar', iconButton(icons.back, 'Back', close), h('h1', title), h('div.spacer'), actions),
    body);
  document.body.append(screen);
  build(body, close);
  return close;
}

// ---------------------------------------------------------------- option row widgets

export const INK_COLORS = [
  { name: 'Black', value: '#111111' },
  { name: 'Blue', value: '#1546b0' },
  { name: 'Red', value: '#c62828' },
  { name: 'Green', value: '#2e7d32' },
];

export function swatches(colors, current, onPick) {
  return h('div.swatches', { role: 'group', 'aria-label': 'Colour' }, colors.map((c) =>
    h('button.swatch' + (c.value === current ? '.on' : ''), {
      type: 'button', title: c.name, 'aria-label': c.name, 'aria-pressed': String(c.value === current),
      style: { background: c.value }, onclick: () => onPick(c.value),
    })));
}

export function stepper(label, value, min, max, step, onChange) {
  return h('div.stepper',
    iconButton(icons.minus, `${label}: decrease`, () => onChange(Math.max(min, value - step))),
    h('span', `${label} ${value}`),
    iconButton(icons.plus, `${label}: increase`, () => onChange(Math.min(max, value + step))));
}

export function chips(items, current, onPick) {
  return h('div.chips', items.map((it) =>
    h('button.chip' + (it.value === current ? '.on' : ''), {
      type: 'button', 'aria-pressed': String(it.value === current), onclick: () => onPick(it.value),
    }, it.label)));
}

export function hint(text) {
  return h('span.hint', text);
}

// ---------------------------------------------------------------- files

/** Opens the browser's file chooser. Resolves null when nothing was chosen. */
export function pickFile(accept) {
  return new Promise((resolve) => {
    const input = h('input', { type: 'file', accept, style: { display: 'none' } });
    input.addEventListener('change', () => { resolve(input.files[0] || null); input.remove(); });
    input.addEventListener('cancel', () => { resolve(null); input.remove(); });
    document.body.append(input);
    input.click();
  });
}

export function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} kB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/**
 * Hands a finished file to the user. Nothing leaves the device: the link
 * points at memory in this tab. A separate tap is used on purpose, because
 * phones only allow a download that follows directly from a tap.
 */
export function offerDownload(bytes, name, note) {
  const blob = new Blob([bytes], { type: 'application/pdf' });
  const url = URL.createObjectURL(blob);
  const file = typeof File === 'function' ? new File([blob], name, { type: 'application/pdf' }) : null;
  const canShare = file && navigator.canShare && navigator.canShare({ files: [file] });
  return dialog({
    title: 'Your copy is ready',
    body: (close) => [
      h('p', `${name} (${formatSize(bytes.length)})`),
      note && h('p.hint', note),
      h('a.btn.primary.wide', { href: url, download: name, onclick: () => setTimeout(() => close(true), 300) }, 'Download'),
      canShare && h('button.btn.wide', {
        type: 'button',
        onclick: async () => { try { await navigator.share({ files: [file] }); close(true); } catch { /* cancelled */ } },
      }, 'Share…'),
    ],
    buttons: [{ label: 'Close', value: false }],
  }).then((saved) => { setTimeout(() => URL.revokeObjectURL(url), 60000); return saved === true; });
}
