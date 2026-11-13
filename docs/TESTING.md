# Manual test checklists

Install with `./gradlew installFullDebug` (phone connected, USB debugging on). Have ready: an ordinary multi-page PDF, a scanned PDF (pictures of pages), and a fillable PDF form.

After any save, open the saved copy in another PDF app as well: that is the real test.

## Core

- [ ] Home screen → **Open a PDF** → pick a file. Pages appear; the title is the file name.
- [ ] From Files, Gmail or Downloads: **Open with** → Loose Leaf opens the PDF.
- [ ] From another app: **Share** a PDF → Loose Leaf opens it.
- [ ] Scroll with one finger and fling. The page counter (top right) follows.
- [ ] Pinch to zoom in and out; text turns sharp a moment after you stop. Double-tap zooms in, double-tap again zooms out.
- [ ] Rotate the phone: you stay on the same page.
- [ ] **Save** icon → the system picker suggests `<name>-edited.pdf`; change the name, save. A "Saved as…" message appears with **Share**.
- [ ] In the picker, choose the original file itself and confirm "replace": Loose Leaf refuses and the original is unchanged.
- [ ] **Share** a PDF to Loose Leaf from another app, edit, **Save**, pick that same file and confirm "replace": refused as well.
- [ ] Make an edit, then swipe Loose Leaf away from the recent-apps list (or Settings → Apps → Force stop). Open it again: the home screen offers **Unsaved edits found**. **Restore** brings the document back with the edit; **Discard** removes the offer.
- [ ] Save a copy, close the document, swipe the app away, open it again: nothing is offered.
- [ ] Make an edit, press **X**: you are asked before losing it. With no edits, X closes at once.
- [ ] Open a long PDF (100 pages or more): the first page shows within a second or two. If it ever takes longer, the "Opening…" box adds that long documents can take a while.
- [ ] A password-protected PDF asks for the password; a wrong one asks again.
- [ ] A file that is not a PDF gives a plain error, not a crash.
- [ ] Android Settings → Apps → Loose Leaf → Permissions: none. Turn on aeroplane mode and repeat anything above.

## Annotate

- [ ] **Highlight**: drag across a line. The text stays readable through the colour. Change colour, highlight again.
- [ ] **Pen**: draw; change colour and width; a tap leaves a dot. Two fingers still scroll and zoom while the pen is active.
- [ ] **Shapes**: pick line, arrow, rectangle and oval in turn and drag each out. Change colour and width.
- [ ] Tap a line or arrow: it shows a handle on each end; drag one to re-aim it, drag the middle to move it.
- [ ] **White-out**: drag a box over something; it is hidden.
- [ ] Tap the active tool again (or Back) to leave it. Tap a mark to select it: drag to move, drag corners to resize.
- [ ] Long-press a mark → Duplicate / Delete. The bin in the bottom bar also deletes.
- [ ] Undo and redo step through all of the above; one drag is one step.
- [ ] Save, reopen the copy: everything is where you put it.

## Text

- [ ] **Text** → tap the page → type → Done. The box appears selected.
- [ ] Drag to move; drag the side handles to change the width (text re-wraps).
- [ ] Bottom bar: − / + change the size, dots change the colour, pencil edits the words. Tapping a selected box also edits it.
- [ ] Clearing all the text in the editor removes the box.
- [ ] Save, reopen: text is in the same place, and can be selected or searched in another viewer.

## Stamps

- [ ] **Stamp** → tap a tick box on the page: a check mark appears there. Tap more boxes without picking the tool again.
- [ ] Switch to the cross, then to the date. Tapping the date in the option row changes how it is written; − / + change the size.
- [ ] Leave the tool, tap a mark: move it, resize it by a corner, change its colour.
- [ ] On a page you rotated in Pages, marks and the date still come out upright.
- [ ] Save, reopen: marks are where you put them; the date can be found by searching in another viewer.

## Signatures

- [ ] **Sign** → **Draw** → sign → **Save and use**: it lands in the middle of the screen, selected.
- [ ] Drag to move, drag a corner to resize (shape is kept).
- [ ] **Sign** again: the saved signature is listed; tap it to place another copy, on another page too.
- [ ] **Sign** → **Type** → name → pick a style → **Save and use**.
- [ ] Delete a saved signature with its bin; copies already on pages stay.
- [ ] Close and reopen the app: saved signatures are still there.
- [ ] Save, reopen: signatures are part of the page, with a clean transparent background.

## Forms

- [ ] Open a fillable form: a message says how many fields it has.
- [ ] **Fill form**: fields are tinted blue. Tap a text field → type → Done.
- [ ] Tap a checkbox to tick and untick; radio buttons switch within their group; a drop-down shows its options.
- [ ] Undo works on field values.
- [ ] Save, reopen in another app: values are there and the fields can still be edited.
- [ ] On a PDF without fields, **Fill form** says so and suggests Text.

## Redact

- [ ] **Redact** → drag over a line of text. A black box covers it.
- [ ] Save, open the copy in another PDF app: try to select or search for the covered words. They are gone, and the rest of that page is now a picture (no selectable text). Other pages are unchanged.
- [ ] Add a text box after the redaction, on the same page: in the saved copy that text can still be selected.
- [ ] Delete the box before saving: the page is saved as normal text again.

## Find

- [ ] **Find** → the keyboard opens and the toolbar sits above it. Type a word: matches are marked yellow, the current one orange, with "1 of N".
- [ ] ∧ / ∨ (or the keyboard's search key) step through matches and scroll to them, also onto other pages.
- [ ] A word that is not there says "No matches". ✕ clears the field.
- [ ] Add a text box, then search for a word in it: it is found. After OCR on a scan, words on the scan are found too.
- [ ] ⋮ → **Go to page** → type a number: the view jumps there. A number past the last page is not accepted.

## Pages

- [ ] ⋮ → **Pages**: thumbnails with numbers, showing your annotations.
- [ ] Tap to select several; the title counts them. Select all / none (top right).
- [ ] **Rotate left / right** turns the selected pages (their annotations turn with them).
- [ ] Hold a page and drag it to a new position.
- [ ] **Delete** removes the selection; deleting every page is refused.
- [ ] **Add PDF** → pick another file: its pages appear after the selection (or at the end).
- [ ] **Extract** → `1-3, 5` (prefilled from the selection) → save: the new file has just those pages; the open document is unchanged.
- [ ] Back, then add text to a rotated page: it reads upright.
- [ ] Undo steps back through page changes. Save, reopen: order, rotation and merged pages are right.

## Compress

- [ ] ⋮ → **Compress** on a scanned or photo-heavy PDF. Pick a level → **Compress**: Before and After sizes and the percentage appear.
- [ ] Try each level: After gets smaller from Light to Strong.
- [ ] **Save compressed copy** suggests `<name>-compressed.pdf`. Open it: readable, visibly rougher at Strong.
- [ ] On a text-only PDF it says there is nothing to shrink and offers nothing to save.
- [ ] Edits made before compressing are in the compressed copy.

## Password

- [ ] ⋮ → **Save with a password**. The button stays grey until both fields match.
- [ ] Save, open the copy in another PDF app: it asks for the password and opens with it.
- [ ] Open that copy in Loose Leaf: it asks for the password, and an ordinary **Save** of it is not protected.

## Library (switch it on in Settings first)

- [ ] Home screen now has a **Library** button. Open it: it explains the library and offers **Choose a folder**. Pick or create one (for example Documents → new folder "Loose Leaf") and allow access.
- [ ] Edit a PDF, **Save**: a "Save to Library" box shows the name (changeable) and the folder. **Save** puts it there; the Files app shows the same file.
- [ ] In that box, **Change** lets you walk into folders and make a new one; the next save starts in the folder used last.
- [ ] **Save somewhere else…** opens the usual system picker instead.
- [ ] Save twice under the same name: the second file gets a number; nothing is replaced.
- [ ] In the Library: **New folder**; a file's ⋮ → Rename, Move to…, Share, Delete (asks first). Tapping a file opens it in the editor; closing the editor returns to the same folder.
- [ ] Library options (top right) → **Only PDFs I signed**. The home button now says **Signed PDFs**. Saving an unsigned edit goes straight to the system picker; after placing a signature, Save offers "Save to Signed PDFs" with a `-signed.pdf` name.
- [ ] Switch the module off in Settings: the home button is gone and Save uses the system picker again. Files in the folder are untouched.

## OCR (full flavour)

- [ ] Open a scanned PDF. ⋮ → **Recognise text** → All pages → **Recognise text**. Progress counts pages; Cancel stops it.
- [ ] When done a message says how many pages got a text layer. The pages look the same.
- [ ] Save, open the copy elsewhere: search finds words, and selecting text follows the scanned lines.
- [ ] A sideways scan: rotate it upright in Pages first, then run OCR on it.
- [ ] On an ordinary (digital) PDF with "Skip pages that already have text" ticked, it reports there was nothing to do.
- [ ] **Add a language from a file**: download e.g. `deu.traineddata` (Tesseract "tessdata_fast") with a browser, pick it. "German (deu)" appears ticked; the bin next to it removes it. Picking any other kind of file is refused with a reason.
- [ ] With two languages ticked, a page in the second language is recognised; unticking the last language is not possible.
- [ ] Install the lite build (`./gradlew installLiteDebug`): no "Recognise text" entry and no OCR in Settings.

## Module switches

- [ ] Settings → switch a module off → back in the editor its buttons or menu entry are gone, with a document open.
- [ ] Switch everything off: the app is a plain viewer that can still open and save a copy.
- [ ] Switch them back on: everything returns.
