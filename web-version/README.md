# Loose Leaf, web version

The same PDF editor as the Android app, as a web page, for devices the app cannot be installed on (an iPhone, a laptop). Plain HTML, CSS and JavaScript modules: no build step, no server-side code, nothing to install.

- **Files never leave the device, unless you switch on the Library.** A PDF is opened and changed inside the browser tab. The page talks to the network only to fetch its own files from wherever it is hosted. A Content-Security-Policy in `index.html` forbids connections to any other site, and every code library is in `vendor/`. The one exception is the optional Library module (off by default), which sends copies you save to the server the page came from; see [The library](#the-library).
- **The original is never changed.** A browser cannot write to the file it opened; "Save" always produces a new download, with a name you confirm first.
- **Unsaved edits survive** a reload or a closed tab: they are kept in the browser's storage (IndexedDB) and offered again on the home screen.
- **Modules** as in the app: each one is a file in `js/modules/`, listed in `js/modules/registry.js`, and can be switched off in Settings.

## Running it

It is a folder of static files. Any web server will do, and `serve.py` (Python standard library only) is included:

```sh
python3 web-version/serve.py                                  # the app only, port 8377
python3 web-version/serve.py --library /path/to/a/folder      # plus storage for the Library module
```

Then open `http://<address of this computer>:8377/` on the phone or laptop. Opening `index.html` straight from disk does not work (browsers block modules on `file://`).

To move it to a server, copy this whole folder and run `serve.py` there, or serve it as static files (Caddy `file_server`, nginx `root`, anything) if you do not want the library. Two things are worth having there:

- **https.** Over plain http everything works except the two things browsers reserve for https: starting the app with no connection to the server (the offline cache in `sw.js`), and the "Share…" button after saving. With https, "Add to Home Screen" on an iPhone gives an app-like icon that opens even when the server is unreachable.
- The folder is about 24 MB, most of it OCR (`vendor/tesseract`, 16 MB). A browser only downloads the OCR part when "Recognise text" is used.

## The library

The Android app keeps a library in a folder on the phone. A web page cannot do that, so here the library is a folder on the machine that runs `serve.py`, which can be a share from a NAS mounted on that machine.

- Switch on **Library** in Settings on each device that should use it. "Save" then has a tick box "Also keep a copy in the library" with a folder you can change; the download to the device still happens. The home screen gets a **Library** button to browse, make folders, rename, move, download, delete and reopen.
- In the Library screen, "Only PDFs I signed" makes the tick box start off unless the document has a signature on it (the button is then called "Signed PDFs").
- A copy never replaces a file: a taken name gets " (2)". Only PDFs are accepted, names starting with a dot are refused, and nothing outside the folder can be reached.
- **Who can use it:** everyone who can reach the port. To require a passphrase, start the server with `LOOSELEAF_KEY='something long' python3 serve.py --library ...`; each device asks for it once. Over plain http the passphrase and the PDFs cross the network unencrypted, which is acceptable on a home network or a VPN such as Tailscale and nowhere else.
- **On a NAS:** mount the share on the machine that runs `serve.py` (for example an SMB share at `/mnt/loose-leaf`), make sure the user running the server can write there, and pass that path to `--library`. The server refuses to start if the folder does not exist, so a share that failed to mount is noticed instead of files landing on the local disk.
- Without `--library` (or on any other static server) the module says that there is no library and saving works as before.

## Where everything lives

```
serve.py                   the web server: static files, and the library's storage if started with --library
index.html, style.css      the page shell and all styling
sw.js, manifest.webmanifest, icon*   offline cache and home-screen icon
js/app.js                  start-up, home screen, opening and restoring
js/core/
  model.js       the document model (pages, elements, form values), undo/redo. Pure data
  pdfsource.js   reading PDFs for the screen (pdf.js): open, render a page, page text
  painter.js     drawing elements on a canvas; geometry shared with the writer
  writer.js      model -> new PDF (pdf-lib): flattening, page order, forms, redaction, password
  viewer.js      the scrolling, zoomable pages; touch and mouse input
  editor.js      the editor screen, the selection, the save flow, and the module API (documented at the top)
  store.js       what is kept in the browser: settings, unsaved work
  settings.js    the Settings screen
  ui.js          dialogs, buttons, option-row widgets, file picking and download
js/modules/
  registry.js    THE list of modules
  annotate.js text.js stamps.js signatures.js forms.js redact.js find.js pages.js compress.js protect.js ocr.js library.js
fonts/           Noto Sans (text boxes) and four handwriting fonts (typed signatures), with their licences
vendor/          pdf.js, pdf-lib, fontkit, Tesseract.js; versions and licences in vendor/README.md
```

The design is the Android app's: an immutable model, a small fixed set of element types, and modules that only change the model through `host.session.edit(...)`. The core draws the elements on screen (`painter.js`) and into the file (`writer.js`). To add a module, write a file in `js/modules/` that exports an object as described at the top of `js/core/editor.js`, and add it to `registry.js`. Modules do not import each other.

## Differences from the Android app

| | Android | Web |
|---|---|---|
| Saving | system file picker, or the Library folder | a download (the browser picks the folder, usually Downloads / Files) |
| Library module | a folder on the phone, nothing leaves it | a folder on the server (e.g. a NAS); copies are uploaded to it, and it only works while the server can be reached |
| Reordering pages | drag | "Move earlier" / "Move later" buttons |
| Compress | all picture types | JPEG pictures only (which is what scans and photos are) |
| OCR languages | English, more can be imported | English only |
| "Open with" / Share into the app | yes | no; open the page and choose the file |
| Languages of the interface | prepared for translation | English only |
| Saved signatures and unsaved edits | app-private storage | this browser's storage for this address; another browser, or the same page under a new address, starts empty |

## Known limits

- Tested in Chromium and Firefox on a desktop, including with emulated touch. **Not yet tested on a real iPhone**; Safari needs iOS 16.4 or newer.
- A saved copy that contains typed text is about 0.3 MB larger, because the whole text font is embedded (the library's font subsetter fails on it). OCR text and form values in Western European letters do not have this cost.
- Text boxes use one font (Noto Sans: Latin, Greek, Cyrillic). Other scripts and emoji are not saved correctly.
- Form fields of a PDF that is added with "Add pages from another PDF" are not fillable in the merged copy; fill and save that file first.
- Everything happens in the tab's memory. Very large files (hundreds of MB) can make a phone browser reload the page; unsaved edits are offered again afterwards.
- Existing text cannot be edited; white-out covers, Redact removes (and saves that page as a picture). Redaction does not touch document-wide data such as the title or bookmarks.
- Find matches within one run of text; a phrase split over two lines may not be found.
- Restoring unsaved edits brings back the document, not the undo history.

## Test checklist

Core
- [ ] Open a PDF. Scroll with one finger, zoom with two. The ⋮ menu has Zoom in / out / Fit to width.
- [ ] Draw something, reload the page: the home screen offers "Unsaved edits found"; Restore brings it back.
- [ ] Save: a name is asked, then "Your copy is ready" with a Download button. The original file is unchanged.
- [ ] Open a password-protected PDF: a wrong password asks again, the right one opens it.
- [ ] Undo and Redo work; the back arrow warns before closing with unsaved edits.

Annotate, Text, Stamps
- [ ] Highlight, Pen, each shape, White-out: drag on the page. With a drawing tool on, two fingers still scroll and zoom.
- [ ] Tap the active tool again to switch it off, tap a drawing to select it; drag it, drag a handle, change its colour, delete it.
- [ ] Text: tap, type two lines, Add. Move it, change Size, tap it again to edit.
- [ ] Stamp: place a check mark, a cross and today's date.

Signatures
- [ ] Sign → Draw: sign with a finger, Save. Sign → Type: type a name, pick a font, Save.
- [ ] Tap a saved signature: it lands on the page you were looking at; move and resize it. Reload: signatures are still there.

Forms, Redact, Find
- [ ] On a fillable form, Fill form marks the fields; fill a text field, a checkbox, a radio button and a list. The saved copy shows them in another PDF viewer.
- [ ] Redact a line, save, open the copy elsewhere: the text under the box cannot be selected or searched.
- [ ] Find a word: matches are marked, the arrows step through them. ⋮ → Go to page jumps.

Pages, Compress, Password, OCR
- [ ] ⋮ → Pages: select pages, move, turn, delete; "Save as PDF" saves only the selection; add another PDF at the end.
- [ ] ⋮ → Compress on a scan: before and after sizes are shown; save the smaller copy.
- [ ] ⋮ → Save with a password: the copy asks for the password in another viewer.
- [ ] ⋮ → Recognise text on a scan, then Find a word from it.

Library (server started with `--library`)
- [ ] Settings → Library on: the home screen gets a Library button. Save a PDF with the box ticked: the "ready" box says where the copy went, and the file is in the folder on the server.
- [ ] Save again under the same name: the second copy is "name (2).pdf", the first is untouched.
- [ ] Library: make a folder, move a file into it, rename it, download it, tap it to reopen it, delete it.
- [ ] Switch to "Only PDFs I signed": the tick box starts off for an unsigned PDF and on for a signed one.
- [ ] With a passphrase set on the server, a new device asks for it once; a wrong one is refused.
- [ ] Stop the server and save: the download still works and the box says the library copy failed.

Settings
- [ ] Switch a module off: its toolbar button or menu entry disappears; what it drew stays and is saved.
