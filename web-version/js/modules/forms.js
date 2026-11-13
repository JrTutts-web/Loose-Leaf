// Forms: fill text, checkbox, radio and choice fields of a real (AcroForm)
// PDF form. The module reads the fields with pdf-lib and stores new values in
// model.form; the writer puts them into the real fields when saving.

import * as L from '../../vendor/pdf-lib/pdf-lib.esm.min.js';
import { inRect, rw, rh } from '../core/model.js';
import { mapRect, FONT } from '../core/painter.js';
import { h, dialog, promptBox, toast } from '../core/ui.js';

const ID = 'forms';
const cache = new WeakMap(); // session -> Promise<[{name, kind, value, options, multiline, widgets: [{page, rect, option}]}]>

function readFields(session) {
  if (!cache.has(session)) cache.set(session, read(session).catch((e) => { console.warn('form reading failed', e); return []; }));
  return cache.get(session);
}

async function read(session) {
  const source = session.sources[0];
  const doc = await L.PDFDocument.load(source.bytes, { password: source.password ?? undefined });
  const pages = doc.getPages();
  const pageOf = (widget) => {
    const ref = doc.context.getObjectRef(widget.dict);
    const p = widget.P();
    let i = p ? pages.findIndex((pg) => pg.ref === p) : -1;
    if (i < 0 && ref) i = pages.findIndex((pg) => (pg.node.Annots()?.asArray() || []).includes(ref));
    return i;
  };
  const fields = [];
  for (const f of doc.getForm().getFields()) {
    let kind, value, options = [], multiline = false;
    try {
      if (f.isReadOnly()) continue;
      if (f instanceof L.PDFTextField) { kind = 'text'; value = f.getText() || ''; multiline = f.isMultiline(); }
      else if (f instanceof L.PDFCheckBox) { kind = 'check'; value = f.isChecked(); }
      else if (f instanceof L.PDFRadioGroup) { kind = 'radio'; value = f.getSelected() || ''; options = f.getOptions(); }
      else if (f instanceof L.PDFDropdown || f instanceof L.PDFOptionList) { kind = 'choice'; value = f.getSelected()[0] || ''; options = f.getOptions(); }
      else continue;
    } catch { continue; }
    const widgets = [];
    f.acroField.getWidgets().forEach((w, i) => {
      const page = pageOf(w);
      if (page < 0) return;
      const r = w.getRectangle();
      const rect = mapRect(source.pages[page].transform, { l: r.x, t: r.y, r: r.x + r.width, b: r.y + r.height });
      widgets.push({ page, rect, option: options[i] });
    });
    if (widgets.length) fields.push({ name: f.getName(), kind, value, options, multiline, widgets });
  }
  return fields;
}

let fields = []; // of the document whose form tool was last switched on
const valueOf = (host, f) => (f.name in host.session.model.form ? host.session.model.form[f.name] : f.value);
const setValue = (host, f, value) => host.session.edit((m) => ({ ...m, form: { ...m.form, [f.name]: value } }));

const tool = {
  id: 'forms', label: 'Fill form', icon: 'M4 5h16v14H4zM8 10h8M8 14h5',
  hint: 'Tap a marked field to fill it in.',
  activate(host) {
    readFields(host.session).then((list) => {
      fields = list;
      if (host.tool !== tool) return;
      if (!list.length) { toast('This PDF has no fields that can be filled in. Use Text and Stamp instead.'); host.setTool(tool); }
      else host.redraw();
    });
  },
  async tap(host, pi, pt) {
    const entry = host.session.model.pages[pi];
    if (entry.src !== 0) return;
    for (const f of fields) {
      const w = f.widgets.find((x) => x.page === entry.index && inRect(x.rect, pt[0], pt[1], 3));
      if (!w) continue;
      const now = valueOf(host, f);
      if (f.kind === 'check') setValue(host, f, !now);
      else if (f.kind === 'radio') setValue(host, f, w.option);
      else if (f.kind === 'text') {
        const v = await promptBox({ title: f.name, value: now, multiline: f.multiline });
        if (v != null) setValue(host, f, v);
      } else {
        const v = await dialog({
          title: f.name,
          body: (done) => h('div.menu', [...f.options, ''].map((o) =>
            h('button', { type: 'button', onclick: () => done(o) }, o === '' ? '(nothing)' : (o === now ? `${o} ✓` : o)))),
        });
        if (v !== undefined) setValue(host, f, v);
      }
      return;
    }
  },
};

export default {
  id: ID,
  title: 'Forms',
  description: 'Fill in the fields of a real PDF form.',
  tools: [tool],
  /** Marks the fields while the tool is on, and shows values that were changed but are not saved yet. */
  overlay(host, ctx, pi, unit) {
    const entry = host.session.model.pages[pi];
    if (entry.src !== 0 || !fields.length || cache.get(host.session) == null) return;
    const active = host.tool === tool;
    const changed = host.session.model.form;
    ctx.save();
    for (const f of fields) {
      const touched = f.name in changed;
      if (!active && !touched) continue;
      for (const w of f.widgets) {
        if (w.page !== entry.index) continue;
        const r = w.rect;
        if (touched) {
          const v = changed[f.name];
          ctx.fillStyle = '#ffffff';
          ctx.fillRect(r.l, r.t, rw(r), rh(r));
          ctx.fillStyle = ctx.strokeStyle = '#111111';
          if (f.kind === 'check' || f.kind === 'radio') {
            const on = f.kind === 'check' ? v : v === w.option;
            ctx.lineWidth = Math.max(0.8, Math.min(rw(r), rh(r)) / 9);
            ctx.strokeRect(r.l + 0.5, r.t + 0.5, rw(r) - 1, rh(r) - 1);
            if (on) {
              ctx.beginPath();
              ctx.moveTo(r.l + rw(r) * 0.2, r.t + rh(r) * 0.55);
              ctx.lineTo(r.l + rw(r) * 0.42, r.t + rh(r) * 0.78);
              ctx.lineTo(r.l + rw(r) * 0.82, r.t + rh(r) * 0.22);
              ctx.stroke();
            }
          } else {
            const size = Math.min(12, rh(r) * 0.7);
            ctx.save();
            ctx.beginPath();
            ctx.rect(r.l, r.t, rw(r), rh(r));
            ctx.clip();
            ctx.font = `${size}px "${FONT}"`;
            String(v).split('\n').forEach((line, i) => ctx.fillText(line, r.l + 2, r.t + size * 1.05 + i * size * 1.3 + (f.multiline ? 0 : Math.max(0, (rh(r) - size * 1.4) / 2))));
            ctx.restore();
          }
        }
        if (active) {
          ctx.fillStyle = 'rgba(31, 111, 235, 0.14)';
          ctx.fillRect(r.l, r.t, rw(r), rh(r));
          ctx.strokeStyle = 'rgba(31, 111, 235, 0.8)';
          ctx.lineWidth = unit;
          ctx.strokeRect(r.l, r.t, rw(r), rh(r));
        }
      }
    }
    ctx.restore();
  },
};
