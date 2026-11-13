# Bundled libraries

Copied unchanged from their npm packages so that the app never loads code from another site.

| Folder | Package | Version | Licence |
|---|---|---|---|
| `pdfjs/` | `pdfjs-dist` (legacy build, with cmaps, standard fonts, wasm decoders, ICC profiles) | 6.5.438 | Apache-2.0 (`pdfjs/LICENSE`; third-party notices in `pdfjs/wasm/`) |
| `pdf-lib/` | `@cantoo/pdf-lib` (pdf-lib with encryption) | 2.11.1 | MIT (`pdf-lib/LICENSE.md`) |
| `fontkit/` | `@pdf-lib/fontkit` | 1.1.1 | MIT |
| `tesseract/` | `tesseract.js` and `tesseract.js-core` (LSTM builds only) | 7.0.0 | Apache-2.0 (`tesseract/LICENSE.md`) |
| `tesseract/lang/eng.traineddata` | Tesseract `tessdata_fast` English | | Apache-2.0 |

To update one: `npm pack <package>@<version>`, copy the same files over these, run the checklist in `../README.md`.
