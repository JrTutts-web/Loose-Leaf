# Loose Leaf

A small, modular, offline PDF editor for Android. Kotlin and Jetpack Compose, Android 8.0 (API 26) and up, GPL-3.0-or-later, meant for F-Droid.

- **Offline only.** The app requests no permissions at all. The build fails if the INTERNET permission (or any other network permission) ever shows up in a merged manifest; see [No network](#no-network).
- **The original is never changed.** A PDF is copied into the app's private storage when it is opened and only the copy is read. "Save a copy" always writes a new file through the system file picker, with a suggested name you can change. If you pick the original there and confirm "replace", the app still refuses.
- **Unsaved edits survive.** Edits are kept on the device as you work. If Android closes the app or it crashes, the home screen offers to restore them.
- **Modules.** Every feature beyond open / view / save is a module that can be switched off in Settings.

There is also a **web version** for devices the app cannot be installed on (an iPhone, a laptop): the same editor as a static web page, in [`web-version/`](web-version/README.md). It shares the design and the fonts but none of the code. Unlike the app it can, if its optional Library module is switched on, upload saved copies to the server that hosts it.

## Build

Needs JDK 17 and the Android SDK (platform 35, build-tools 35). Put the SDK path in `local.properties` (`sdk.dir=/path/to/Android/Sdk`) or set `ANDROID_HOME`.

```sh
./gradlew assembleFullDebug        # everything, including OCR
./gradlew assembleLiteDebug        # without the OCR module
./gradlew installFullDebug         # build and install on a connected phone
./gradlew testFullDebugUnitTest    # unit tests
./gradlew check                    # tests, lint and the no-network check
```

APKs land in `app/build/outputs/apk/<flavour>/<type>/`. Debug builds install as `io.github.jrtutts_web.looseleaf.debug`, next to a release build. Release builds are minified and unsigned (F-Droid signs its own builds).

| Flavour | What it is | Release size |
|---|---|---|
| `full` | All modules. Adds Tesseract and English language data. | about 38 MB (four ABIs) |
| `lite` | No OCR: none of its code, native libraries or data. | about 9 MB |

## Where everything lives

```
app/src/main/kotlin/io/github/jrtutts_web/looseleaf/
  MainActivity.kt        receives "Open with" and "Share" intents
  AppViewModel.kt        opens files (and asks for passwords), owns the open document, offers unsaved edits again
  LooseLeafApp.kt        start-up: pdfbox resources, font
  core/
    model/               the document model. Pure data, no Android drawing, no PDF library
      Geometry.kt          page space: points, origin top-left, y down
      Elements.kt          the things that can be on a page (see below)
      DocumentModel.kt     pages + form values, as one immutable value
      EditorSession.kt     current model, undo/redo, selection, working files
    pdf/                 files in and out
      PdfRenderEngine.kt   the rendering layer: platform PdfRenderer -> bitmaps
      PdfWriter.kt         model -> new PDF (page order, rotation, form values)
      ElementWriter.kt     flattens page elements into page content
      PageRasterizer.kt    redaction: turns a page into a picture so covered content is gone
      PageGeometry.kt      page space <-> screen <-> PDF user space
      PdfFiles.kt          pdfbox loading, password removal
    render/              drawing the model on screen
      ElementPainter.kt    the on-screen twin of ElementWriter
      TextLayout.kt        line breaking shared by screen and file; the bundled font
      LineGeometry.kt      line and arrow outlines shared by screen and file
    session/             keeping unsaved edits on the device
      SessionStore.kt      one directory per session: working copies + session.json
      ModelJson.kt         the document model as JSON
    module/EditorModule.kt   the module API: EditorModule, Tool, MenuAction, EditorHost
    settings/Settings.kt     which modules are on (SharedPreferences)
    ui/                  screens
      AppRoot.kt           home screen, dialogs, picks the screen
      EditorScreen.kt      top bar, toolbar, option rows, menus
      PageViewer.kt        the zoomable page view: drawing and all gestures
      ViewerState.kt       scroll, zoom and page layout
      EditorController.kt  implements EditorHost; selection, move, resize, save flow
      SettingsScreen.kt, Theme.kt, Widgets.kt
  modules/
    ModuleRegistry.kt    THE list of modules
    annotate/ text/ stamps/ signatures/ forms/ redact/ find/ pages/ compress/ protect/ library/
app/src/full/            OCR module (modules/ocr), its strings and tessdata
app/src/lite/            the "no optional modules" stub
app/src/main/res/values/strings.xml            core strings
app/src/main/res/values/strings_<module>.xml   one file per module
docs/TESTING.md          manual test checklists, one per module
docs/TRANSLATING.md      how to add a language
docs/RELEASING.md        how to publish a version (F-Droid, IzzyOnDroid, donations)
fastlane/metadata/       F-Droid listing text and screenshots
web-version/             the editor as a static web page (its own README)
licenses/                licences of bundled fonts and libraries
```

### The three layers

1. **Document model** (`core/model`). A `DocumentModel` is a list of `PageEntry` (which source page, added rotation, the elements on it) plus form values. It is immutable: every edit makes a new model, which is all undo and redo need. Page elements are a small fixed set:

   | Element | Used for |
   |---|---|
   | `RectMark` | highlight (multiplied onto the page), white-out (opaque) and, with `erase` set, redaction |
   | `InkStroke` | pen strokes |
   | `LineMark` | straight lines and arrows |
   | `ShapeMark` | outlined rectangle, oval, check mark, cross |
   | `TextBox` | text in the bundled font |
   | `ImageStamp` | a transparent PNG, e.g. a signature |
   | `HiddenText` | invisible searchable text from OCR |

2. **Rendering** (`core/pdf/PdfRenderEngine`, `core/render`). Page bitmaps come from Android's own `android.graphics.pdf.PdfRenderer`; elements are drawn over them as vectors by `ElementPainter`.

3. **UI** (`core/ui`, and each module's screens).

Modules sit on top and **only change the model** (through `host.session.edit { ... }`). They never draw page elements or write PDF bytes; the core does both from the model. A module may draw a temporary overlay (Find marks its matches, Forms shows field values). Three modules also *read* PDFs through pdfbox (Forms to find fields, Find to read the text, OCR to check for existing text), and two pass a last step to the core writer (Compress re-encodes images, Password encrypts).

Saving flattens edits into the page content, so they look the same in every viewer and cannot be dragged off by the recipient. They cannot be edited again after saving. Form fields are the exception: they stay real, fillable fields with the new values.

**Redaction.** A page with a redaction box is saved as a 200 dpi picture of itself with the box painted in, and its links, form fields and old resources are dropped, so what was under the box is not in the file. Everything the user drew below the box goes into the picture; text boxes added above it and OCR text outside it stay real text. Other pages are untouched.

**The library.** It is an ordinary folder that the user picks once with the system folder picker; the app keeps the access grant for that folder and needs no storage permission. Files in it are plain PDFs that show up in the Files app and survive an uninstall. A save into the library always creates a new file; if the name is taken the system adds a number.

**Opening.** Only the platform renderer is used to open a document and measure its pages, which takes a fraction of a second even for long files. pdfbox, which is much slower to read a large file, is first needed when saving, searching or looking for form fields. Page sizes from the renderer are whole points, so when saving, page space is stretched onto the real page box (a fraction of a percent at most).

**Unsaved edits.** Each session has a directory under the app's `files/sessions/` with the working copies and a `session.json` (the model, rewritten a moment after every change). It is deleted when the document is closed. On start-up the newest session that still has unsaved edits is offered on the home screen and the rest are deleted.

## Modules

| Module | Package | What it adds |
|---|---|---|
| Annotate | `modules/annotate` | Toolbar: Highlight, Pen (colour, width), Shapes (line, arrow, rectangle, oval), White-out |
| Text | `modules/text` | Toolbar: Text. Tap to place; size, colour, move, resize width, edit, delete |
| Stamps | `modules/stamps` | Toolbar: Stamp. One tap places a check mark, a cross or today's date |
| Signatures | `modules/signatures` | Toolbar: Sign. Draw or type (four handwriting fonts), saved in app-private storage, place / move / resize |
| Forms | `modules/forms` | Toolbar: Fill form. Text, checkbox, radio and choice AcroForm fields |
| Redact | `modules/redact` | Toolbar: Redact. Black boxes whose content is really removed from the saved copy |
| Find | `modules/find` | Toolbar: Find (text search with marked matches). Menu: Go to page |
| Pages | `modules/pages` | Menu: Pages. Thumbnails; drag to reorder; rotate, delete, extract ranges, merge another PDF |
| Compress | `modules/compress` | Menu: Compress. Three levels, before and after sizes, saves a separate copy |
| Password | `modules/protect` | Menu: Save with a password (AES-256) |
| Library | `modules/library` | Off by default. "Save a copy" offers a library folder (every copy, or only signed PDFs); a home-screen button opens the library to browse, sort into folders, rename, move, share and reopen |
| OCR | `src/full/.../modules/ocr` | Menu: Recognise text. Offline Tesseract; English included, more languages can be added from a file. `full` flavour only |

### Turning modules on and off

- **As a user:** Settings (home screen, or the ⋮ menu in the editor) → Modules. Switching one off removes its toolbar buttons and menu entries at once. Things it already put on the page stay there, can still be moved or deleted, and are saved.
- **Default state:** override `enabledByDefault` in the module object.
- **Out of a build:** delete its line in `modules/ModuleRegistry.kt` (and its package and `strings_<module>.xml` if you want it gone for good). Nothing else refers to a module. OCR is left out by building the `lite` flavour.

### Adding a module

1. Make a package under `modules/` with an `object` that implements `EditorModule` (`core/module/EditorModule.kt` documents every hook).
2. Give it `tools` (toolbar buttons that receive taps and drags in page coordinates) and/or `menuActions` (entries in the ⋮ menu that usually call `host.showScreen { ... }`).
3. Change the document only with `host.session.edit { model -> ... }`.
4. Add it to the list in `ModuleRegistry.kt`.

A module can also put a button on the home screen (`homeActions`, with a screen of its own that gets an `AppHost`) and offer its own destination when the user saves (`onSave`). The Library module uses both.

Modules must not import each other. Shared widgets live in `core/ui/Widgets.kt`. The one soft link is that Library recognises a signed document by elements whose owner id is `"signatures"`; with Signatures off, nothing counts as signed.

## No network

- `AndroidManifest.xml` declares no permissions and explicitly removes `INTERNET` and `ACCESS_NETWORK_STATE` in case a library ever tries to add them.
- `./gradlew checkNoNetwork` (also run automatically after every manifest merge and by `check`) reads every merged manifest and fails the build on any network permission.
- To check a built APK yourself: `aapt2 dump permissions app-full-release-unsigned.apk`. The only entry is AndroidX's internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which is not a network permission.
- No Google Play Services, Firebase, analytics or crash reporting. Backups and device transfer are switched off, so saved signatures stay on the device.

## Dependencies

| What | Licence | From |
|---|---|---|
| AndroidX, Jetpack Compose, Material 3 | Apache-2.0 | Google Maven |
| pdfbox-android (`com.tom-roush:pdfbox-android`) | Apache-2.0 | Maven Central |
| Reorderable (`sh.calvin.reorderable`) | Apache-2.0 | Maven Central |
| tesseract4android, `full` only | Apache-2.0 | JitPack (the repository is restricted to this one group in `settings.gradle.kts`) |
| Tesseract `eng.traineddata` (tessdata_fast), `full` only | Apache-2.0 | bundled in `app/src/full/assets/tessdata` |
| Noto Sans; Great Vibes, Allura, Sacramento, Kalam | SIL OFL 1.1 | bundled in `app/src/main/res/font`, licences in `licenses/` |

## Translating

All user-visible text is in string resources; see [docs/TRANSLATING.md](docs/TRANSLATING.md). New `values-xx` folders are picked up by the build on their own.

## Known limits (v1)

- Existing text in a PDF cannot be edited. White-out covers content; it does not remove the text underneath from the file. Redact does, at the price of saving that page as a picture.
- Redaction removes page content. It does not touch document-wide data such as the title, bookmarks or attachments. On a page that also has form fields, the fields are saved as they looked when the file was opened; fill and save first, then redact the copy.
- Text boxes use one font (Noto Sans: Latin, Greek, Cyrillic). Characters it lacks, such as emoji or CJK, are saved as `?`.
- Highlighting is a rectangle you drag, not a text selection.
- An element cannot be dragged from one page to another.
- OCR ships with English. Other languages are Tesseract `.traineddata` files the user adds from the OCR screen; the app cannot download them. Recognised text in scripts the bundled font lacks (anything but Latin, Greek and Cyrillic) is not saved.
- Find matches within a line of text; a phrase that is split across two lines is found only if the PDF stores it in reading order.
- Password-protected PDFs open after the password is entered; an ordinary saved copy is not protected. Use "Save with a password" to protect a copy.
- Deleting pages blanks them in the saved file so their content is gone, but a bookmark that pointed at a deleted page remains as a dead entry.
- "Never overwrite the original" is enforced by refusing to save to the address the file was opened from, and to any file whose content is identical to a PDF opened or merged in this session. An older, different version of the file under the same name can still be replaced if you confirm the system's "replace?" prompt.
- Restoring unsaved edits brings back the document, not the undo history.
- Saving, Find and Compress read the whole file with pdfbox, so on a long document (150 pages and up) they can take from several seconds to half a minute, longer in a debug build than in a release build.
- The library shows PDFs and folders only, has no thumbnails or search, and changing the library folder does not move files already saved.
- Both flavours use the same application ID, so only one of them can be installed at a time.
