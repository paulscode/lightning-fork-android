#!/usr/bin/env bash
# STEP 2 (air-gapped machine): sign the artifacts with the upload key, then make
# a SHA256SUMS for the sideload APK. Never touches the network.
#
#   APKSIGNER_JAR=/path/to/apksigner.jar \      # recommended (one file + a JDK)
#   KEYSTORE=~/keys/lf-upload.jks KEY_ALIAS=lf-upload \
#   ./sign-and-checksum.sh <dir-with-unsigned-artifacts>
#
# Requires: apksigner for the APK (either APKSIGNER_JAR=apksigner.jar run via the
# JDK, or APKSIGNER=/path/to/apksigner wrapper), jarsigner (JDK) for an AAB if
# one is staged (a sideload-only release has none), sha256sum, and gpg. See
# RELEASE.md.
set -euo pipefail

dir="${1:-$(cd "$(dirname "$0")" && pwd)/staging}"
: "${KEYSTORE:?set KEYSTORE=/path/to/lf-upload.jks}"
: "${KEY_ALIAS:=lf-upload}"


# apksigner: prefer the self-contained jar (portable to an air-gapped box that
# only has a JDK); else an executable wrapper on PATH or via APKSIGNER.
if [ -n "${APKSIGNER_JAR:-}" ]; then
  command -v java >/dev/null || { echo "java (JDK) required to run apksigner.jar" >&2; exit 1; }
  apksigner() { java -jar "$APKSIGNER_JAR" "$@"; }
else
  bin="${APKSIGNER:-apksigner}"
  command -v "$bin" >/dev/null || {
    echo "apksigner not found: set APKSIGNER_JAR=/path/to/apksigner.jar (recommended) or APKSIGNER=/path/to/apksigner" >&2
    exit 1
  }
  apksigner() { "$bin" "$@"; }
fi

unsigned_apk="$(find "$dir" -name '*-unsigned.apk' | head -1)"
aab="$(find "$dir" -name '*.aab' | head -1)"
[ -n "$unsigned_apk" ] || { echo "no *-unsigned.apk in $dir" >&2; exit 1; }
signed_apk="${unsigned_apk/-unsigned/}"

echo "Signing sideload APK with the upload key…"
apksigner sign --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" --v4-signing-enabled false \
  --out "$signed_apk" "$unsigned_apk"
apksigner verify --verbose "$signed_apk" | sed 's/^/  /'
rm -f "$unsigned_apk" "${unsigned_apk}.idsig"

if [ -n "$aab" ]; then
  command -v jarsigner >/dev/null || { echo "jarsigner not found (install a JDK)" >&2; exit 1; }
  echo "Signing AAB (Play upload key)…"
  jarsigner -keystore "$KEYSTORE" -sigalg SHA256withRSA -digestalg SHA-256 \
    "$aab" "$KEY_ALIAS"
fi

echo "Writing SHA256SUMS (sideload APK)…"
( cd "$dir" && sha256sum "$(basename "$signed_apk")" > SHA256SUMS && cat SHA256SUMS )

echo "PGP-signing SHA256SUMS…"
( cd "$dir" && gpg --armor --detach-sign --output SHA256SUMS.asc SHA256SUMS )

echo
echo "Ready to publish from $dir:"
[ -n "$aab" ] && echo "  • $(basename "$aab")            -> Google Play (internal testing first)"
echo "  • $(basename "$signed_apk")     -> GitHub release (sideload)"
echo "  • SHA256SUMS + SHA256SUMS.asc   -> GitHub release"
