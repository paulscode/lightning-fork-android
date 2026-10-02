# Release process

Lightning Fork ships two ways from one build:

- **Google Play** — an `.aab` (App Bundle), signed with your **upload key**.
- **Sideload** — a universal `.apk` on the GitHub release, self-signed with the
  same key, plus a **PGP-signed `SHA256SUMS`** for folks who avoid Play.

Signing is done **offline on the air-gapped machine**; the networked build
machine only ever produces *unsigned* artifacts.

---

## Signing model (read once)

- **Play App Signing** is on. Google holds the real **app signing key** and
  re-signs every download. You hold only the **upload key** (`lf-upload.jks`).
  Its private half **never leaves your control** — Google only gets the upload
  *certificate* (public) and verifies your uploads against it. If the upload key
  is ever lost/compromised, you reset it with Google; it's recoverable.
- The **sideload APK** is signed directly with that same upload key, so sideload
  updates are self-consistent (a sideloaded install can be updated by a later
  sideloaded APK).
- **Caveat:** Play-delivered installs are signed by *Google's* app signing key,
  while sideload APKs are signed by *your* key — the two signatures differ, so a
  user can't cross-grade (Play↔sideload) without uninstalling. That's expected;
  treat Play users and sideload users as separate populations.
- Your **PGP key** (the one you use for Start9 `SHA256SUMS`) is **not** an Android
  signing key and can't sign the APK itself. It signs the sideload `SHA256SUMS`
  for out-of-band provenance — exactly like the `.s9pk` flow.

---

## One-time setup

1. **Upload keystore** — already created on the air-gapped machine and backed up:
   ```
   keytool -genkeypair -v -keystore lf-upload.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias lf-upload
   ```
2. **Export the upload certificate** (public) to register with Play:
   ```
   keytool -export -rfc -keystore lf-upload.jks -alias lf-upload -file upload_certificate.pem
   ```
3. **Play Console** — create the app, enroll in **Play App Signing** (let Google
   generate the app signing key), and register `upload_certificate.pem` as the
   upload key.
4. **Tools** — build machine: Rust + `cargo-ndk` + NDK (see `rust/build-android.sh`),
   JDK 17, Android SDK. Air-gapped machine: a **JDK** (provides `jarsigner` and runs
   `apksigner.jar`), `sha256sum`, `gpg` (with your key), plus **`apksigner.jar`**
   copied over once (see below).

### Getting `apksigner.jar` onto the air-gapped machine
`apksigner` is just a self-contained ~1 MB fat jar from the Android SDK
build-tools — no install, only a JDK to run it. Copy the single file across your
air-gap medium and verify its checksum so the tool that touches your signing key
is known-good:
```
# on the build machine
apksigner_jar="$ANDROID_HOME/build-tools/<ver>/lib/apksigner.jar"
sha256sum "$apksigner_jar"          # record this
cp "$apksigner_jar" /path/to/usb/
# on the air-gapped machine, after copying
sha256sum apksigner.jar             # must match
```

`keystore.properties` is **not** used for this flow — leave it absent so builds
come out unsigned. (It exists only for optional on-machine signing; see
`keystore.properties.example`.)

---

## Per-release runbook

### 1. Version bump
In `app/build.gradle.kts` raise `versionCode` (must strictly increase for Play)
and set `versionName`. Commit.

### 2. Build unsigned artifacts (build machine)
```
release/build-artifacts.sh
```
Builds all four Arti ABIs, then the AAB + a universal APK, and stages them under
`release/staging/` as `lightning-fork-<ver>.aab` and
`lightning-fork-<ver>-unsigned.apk`.

### 3. Transfer to the air-gapped machine
Copy `release/staging/` across your usual air-gap medium (USB, etc.).

### 4. Sign + checksum (air-gapped machine)
```
APKSIGNER_JAR=/path/to/apksigner.jar \
KEYSTORE=~/keys/lf-upload.jks KEY_ALIAS=lf-upload \
release/sign-and-checksum.sh release/staging
```
(Or, if you copied the whole build-tools dir, `APKSIGNER=.../build-tools/<ver>/apksigner`.)
This apksigner-signs the APK (v2/v3), jarsigner-signs the AAB (upload key),
writes `SHA256SUMS` over the sideload APK, and PGP-signs it to `SHA256SUMS.asc`.

### 5. Transfer signed artifacts back, then smoke-test
Bring back the signed `.apk`, `.aab`, `SHA256SUMS`, `SHA256SUMS.asc`.
**Install the signed release APK on a device and smoke-test** (pair, balances,
receive and send a small amount on each layer, the app lock, and the same over
Tor with the phone off the home network) — the release build is R8-minified,
so exercise it before publishing.

### 6. Publish
- **Play:** upload the `.aab` to an **internal testing** track first; promote once
  verified.
- **GitHub release:** attach the `.apk`, `SHA256SUMS`, and `SHA256SUMS.asc`.
- **Tag** the release commit (e.g. `git tag -s v<ver>`).

---

## How sideloaders verify

```
gpg --verify SHA256SUMS.asc SHA256SUMS      # provenance (import the maintainer key first)
sha256sum -c SHA256SUMS                      # integrity of the .apk
```

---

## Notes

- **Multi-ABI Arti** is built by step 2; if a 32-bit ABI ever fails to
  cross-compile, drop it from `ABIS` in `release/build-artifacts.sh` and note the
  reduced device coverage.
- **`versionCode`** must increase on every Play upload, even for re-tries.
- Keep the `.jks`, its passwords, and the PGP secret key on the air-gapped machine
  only. Nothing secret is ever committed (`keystore.properties`, `*.jks`, and the
  staging dirs are gitignored).
