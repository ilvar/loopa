# Publishing Looking Glass

This guide covers releasing Looking Glass to **Google Play** and **F-Droid**. The repo is
already prepared for both: it is fully open-source (MIT, no proprietary
dependencies), has a release signing hook, a privacy policy, and Fastlane store
metadata.

## Prerequisites

- Android SDK Platform 34 + Build-Tools 34.x
- A machine with normal network access (the Android Gradle Plugin and AndroidX
  come from Google's Maven repo)
- Before each release, bump `versionCode` (integer, +1) and `versionName` in
  [`app/build.gradle.kts`](app/build.gradle.kts) and add a matching changelog at
  `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.

---

## 1. Create a signing key (one time)

```bash
keytool -genkey -v -keystore looking-glass-release.jks -alias lookingglass \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep `looking-glass-release.jks` safe and **out of git** (`*.jks` is git-ignored). If you
lose it and are not on Play App Signing, you can never update the Play listing.

Then copy the sample and fill in your values:

```bash
cp keystore.properties.sample keystore.properties
# edit keystore.properties — it is git-ignored
```

`app/build.gradle.kts` picks this up automatically and signs the release build.
If `keystore.properties` is absent (CI, F-Droid servers), the release build is
produced unsigned, which is exactly what F-Droid wants.

---

## 2. Google Play

1. **Developer account** — register at <https://play.google.com/console> ($25 one
   time) and complete identity verification.
2. **Build the bundle** (Play requires `.aab`):
   ```bash
   ./gradlew bundleRelease
   # → app/build/outputs/bundle/release/app-release.aab
   ```
3. **Enroll in Play App Signing** when prompted (recommended).
4. **Store listing** — Fastlane metadata in `fastlane/metadata/android/en-US/`
   already has the title, descriptions, and changelog. Add the images described
   in `.../images/README.md` (icon, feature graphic, 2+ screenshots).
5. **Privacy policy URL** — publish `docs/privacy-policy.md` (e.g. enable GitHub
   Pages on the repo → `https://<user>.github.io/loopa/privacy-policy`) and paste
   the URL into the console.
6. **Data safety form** — declare *no data collected, no data shared* (the app is
   fully on-device with no network permission).
7. **Permissions** — justify `CAMERA`: "shows the live magnified preview".
8. Complete the content rating questionnaire and roll out via Internal testing →
   Production.

Optionally automate uploads with `fastlane supply`, which reads the same
metadata folder.

---

## 3. F-Droid

The repo is FOSS with no proprietary blobs, so it qualifies for the main repo.

1. **Tag the release** so the build recipe has a commit to pin:
   ```bash
   git tag v1.0
   git push origin v1.0
   ```
2. **Submit the recipe** — the template at
   [`fdroid/com.ilvar.lookingglass.yml`](fdroid/com.ilvar.lookingglass.yml) goes into the
   [fdroiddata](https://gitlab.com/fdroid/fdroiddata) repo at
   `metadata/com.ilvar.lookingglass.yml`. Either open a merge request there, or file a
   [Request For Packaging](https://gitlab.com/fdroid/rfp/-/issues) issue and let a
   maintainer add it.
3. F-Droid builds from source on their servers and **signs with their own key** —
   you do not upload an APK and do not need `keystore.properties`.
4. The Fastlane metadata folder is read automatically for the listing text,
   changelog, and images.

### Faster alternative: your own F-Droid repo

Use [Repomaker](https://f-droid.org/docs/Repomaker/) or `fdroidserver` to host a
personal repo; users add your repo URL to their F-Droid client. Full control, no
review queue.

---

## Release checklist

- [ ] `versionCode` / `versionName` bumped
- [ ] `changelogs/<versionCode>.txt` added
- [ ] `./gradlew bundleRelease` (Play) / clean `assembleRelease` builds (F-Droid)
- [ ] Store images present
- [ ] Privacy policy URL live (Play)
- [ ] Git tag `v<versionName>` pushed (F-Droid)
