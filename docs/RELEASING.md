# Releasing Loose Leaf

What has to happen to get a version to users. Steps marked **once** are only
needed for the first release.

## 1. Before every release

- Raise `versionCode` (by one) and `versionName` in `app/build.gradle.kts`.
- Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (a few
  lines, plain text).
- `./gradlew check` must pass, and the checklists in `docs/TESTING.md` should
  have been run on a real phone.
- Commit, then tag: `git tag v<versionName> && git push --tags`.

## 2. Public repository (once)

The application ID is `io.github.jrtutts.looseleaf`, which by convention means
the code lives at `github.com/JrTutts-web/Loose-Leaf`. Create that repository and
push. If the code is going to live somewhere else, change the ID **before**
the first release; it cannot be changed afterwards without users having to
reinstall.

## 3. Signing key (once)

F-Droid builds and signs the app itself, so it needs nothing from you. The
APKs you attach to GitHub releases (which IzzyOnDroid and Obtainium use) need
your own key:

```sh
keytool -genkeypair -v -keystore ~/looseleaf-release.jks -alias looseleaf \
    -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and its password out of the repository and back them up
somewhere safe. Whoever loses the key can never update the app for existing
users.

## 4. Build and sign the APKs

```sh
./gradlew assembleFullRelease assembleLiteRelease
for f in full lite; do
  apksigner sign --ks ~/looseleaf-release.jks --ks-key-alias looseleaf \
      --out looseleaf-$f-<versionName>.apk \
      app/build/outputs/apk/$f/release/app-$f-release-unsigned.apk
done
```

`apksigner` is in the Android SDK under `build-tools/<version>/`. Attach both
files to a GitHub release made from the tag.

## 5. IzzyOnDroid (once, days)

IzzyOnDroid is a third-party F-Droid repository that takes the APK from your
GitHub releases, so it is quick. Open an issue at
`gitlab.com/IzzyOnDroid/repo` asking for inclusion, with a link to the
repository. It lists one APK per app; offer the `lite` one if the `full`
one is over its size limit.

## 6. F-Droid (once, weeks)

F-Droid builds from source. Fork `gitlab.com/fdroid/fdroiddata`, add
`metadata/io.github.jrtutts.looseleaf.yml` and open a merge request. A
starting point:

```yaml
Categories:
  - Reading
  - Writing
License: GPL-3.0-or-later
AuthorName: <your name or handle>
SourceCode: https://github.com/JrTutts-web/Loose-Leaf
IssueTracker: https://github.com/JrTutts-web/Loose-Leaf/issues
# Donate: https://...          (see below)
# Liberapay: <account name>

AutoName: Loose Leaf

RepoType: git
Repo: https://github.com/JrTutts-web/Loose-Leaf.git

Builds:
  - versionName: 0.1.0
    versionCode: 1
    commit: v0.1.0
    subdir: app
    gradle:
      - full

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0
CurrentVersionCode: 1
```

The title, descriptions, changelogs and screenshots are read from
`fastlane/metadata/android/` in this repository, so they are not repeated
there. Things a reviewer may ask about:

- **JitPack.** The OCR engine (`tesseract4android`) comes from JitPack, and
  `settings.gradle.kts` restricts JitPack to that one group. If it is not
  accepted, change `full` to `lite` in the metadata; that build has no OCR and
  no JitPack dependency.
- **Bundled data.** `eng.traineddata` (Apache-2.0) and the fonts (OFL) are in
  the repository with their licences in `licenses/`.

After the first version is in, new tags are picked up automatically.

## 7. Donations (once)

- **Liberapay** (recurring, no platform fee) or **GitHub Sponsors**: create
  the account, then un-comment `Liberapay:` / `Donate:` in the F-Droid
  metadata so the app's page shows the link.
- Add `.github/FUNDING.yml` to the repository so GitHub shows a Sponsor
  button, for example `liberapay: <account name>`.
- A "Support this app" row in Settings can open the donation page in the
  browser. That needs no network permission, because the browser makes the
  request, not the app.

## 8. Telling people

One post each, after the app is installable: r/fossdroid, r/androidapps,
Fosstodon, and a listing on AlternativeTo. Say plainly what it is (offline,
no permissions, fill / sign / redact / merge) and that it was built with AI
assistance.
