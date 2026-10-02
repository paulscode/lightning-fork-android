#!/usr/bin/env bash
# STEP 1 (networked build machine): produce UNSIGNED release artifacts to hand to
# the air-gapped signer. Builds every Arti ABI, then the AAB (Play) and a
# universal APK (sideload). See RELEASE.md for the full runbook.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
root="$here/.."
staging="$here/staging"

ver="$(grep -oE 'versionName = "[^"]+"' "$root/app/build.gradle.kts" | head -1 | cut -d'"' -f2)"
[ -n "$ver" ] || { echo "could not read versionName" >&2; exit 1; }
echo "Building Lightning Fork v$ver (unsigned)…"

# Fails fast if keystore.properties is present (that path signs during build and
# is not the air-gapped flow this script is for).
if [ -f "$root/keystore.properties" ]; then
  echo "keystore.properties present — remove it for the unsigned/air-gapped flow." >&2
  exit 1
fi

# The notices shipped in the app, current with its dependencies.
"$here/licenses.sh"

# All ABIs so the universal APK and the AAB cover every device.
ABIS="arm64-v8a armeabi-v7a x86_64 x86" "$root/rust/build-android.sh"


# SIDELOAD_ONLY=1: the APK alone (testers sideload it; no Play listing yet).
if [ -n "${SIDELOAD_ONLY:-}" ]; then
  ( cd "$root" && ./gradlew --console=plain clean assembleRelease )
else
  ( cd "$root" && ./gradlew --console=plain clean bundleRelease assembleRelease )
fi

rm -rf "$staging"
mkdir -p "$staging"
apk="$(find "$root/app/build/outputs/apk/release" -name '*-release-unsigned.apk' | head -1)"
cp "$apk" "$staging/lightning-fork-$ver-unsigned.apk"
if [ -z "${SIDELOAD_ONLY:-}" ]; then
  aab="$(find "$root/app/build/outputs/bundle/release" -name '*-release.aab' | head -1)"
  cp "$aab" "$staging/lightning-fork-$ver.aab"
fi

echo
echo "Unsigned artifacts staged in release/staging/:"
ls -la "$staging"
echo
echo "Next: transfer release/staging/ to the air-gapped machine and run"
echo "  release/sign-and-checksum.sh  (see RELEASE.md)."
