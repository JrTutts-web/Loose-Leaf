# Translating Loose Leaf

The app is written in English. Every piece of text the user sees is a string
resource, so a translation is a set of XML files and needs no code changes.

## Where the text lives

| What | Where |
| --- | --- |
| Core app text | `app/src/main/res/values/strings.xml` |
| One file per module | `app/src/main/res/values/strings_<module>.xml` |
| OCR module (full flavour only) | `app/src/full/res/values/strings_ocr.xml` |
| Store listing (F-Droid) | `fastlane/metadata/android/en-US/` |

## Adding a language by hand

1. Create `app/src/main/res/values-<code>/` (for example `values-de`, or
   `values-pt-rBR` for a regional variant) and, for the OCR text,
   `app/src/full/res/values-<code>/`.
2. Copy the `strings*.xml` files into it and translate the text between the
   tags. Leave the `name="…"` attributes, the placeholders (`%1$s`, `%1$d`) and
   anything marked `translatable="false"` alone.
3. Plurals (`<plurals>`) need every quantity your language uses (`one`, `few`,
   `many`, `other`, …).
4. For the store listing, copy `fastlane/metadata/android/en-US/` to a folder
   named after the locale (`de-DE`) and translate the `.txt` files.
5. Build and run. Nothing else has to be registered: the build collects the
   `values-xx` folders into the app's language list by itself
   (`generateLocaleConfig` in `app/build.gradle.kts`), so the new language also
   shows up under Android's per-app language setting.

A translation does not have to be complete. Strings that are missing fall back
to English, and the build does not fail on them.

## Using Weblate

The layout above is the standard Android one, so a hosted translation service
can work on it directly. To set up [Weblate](https://hosted.weblate.org) (free
for free software) once the repository is public:

1. Add a project for Loose Leaf and one component per strings file, with file
   format "Android String Resource", file mask
   `app/src/main/res/values-*/strings.xml` (and likewise for each
   `strings_<module>.xml`), and the English file as the base.
2. Add one more component for `fastlane/metadata/android/*/` with the
   "fastlane metadata" format.
3. Let Weblate push translations back as pull requests.

## Notes for translators

- Keep button labels short; the toolbar under the page has little room.
- "Save a copy" is deliberate. The app never changes the original file, and
  the wording should keep saying so.
- Dates placed by the Stamps module follow the phone's language automatically.
