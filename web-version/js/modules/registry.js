// THE list of modules. To leave one out of the app, delete its two lines
// here (and its file). Nothing else refers to a module, and modules do not
// import each other.

import { prefs } from '../core/store.js';
import annotate from './annotate.js';
import text from './text.js';
import stamps from './stamps.js';
import signatures from './signatures.js';
import forms from './forms.js';
import redact from './redact.js';
import find from './find.js';
import pages from './pages.js';
import compress from './compress.js';
import protect from './protect.js';
import ocr from './ocr.js';
import library from './library.js';

export const allModules = [annotate, text, stamps, signatures, forms, redact, find, pages, compress, protect, ocr, library];

export function isEnabled(module) {
  return prefs.get('modules', {})[module.id] ?? module.enabledByDefault ?? true;
}
export function setEnabled(module, on) {
  prefs.set('modules', { ...prefs.get('modules', {}), [module.id]: on });
}
export const enabledModules = () => allModules.filter(isEnabled);
