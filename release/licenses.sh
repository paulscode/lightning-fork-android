#!/usr/bin/env bash
# Writes app/src/main/assets/licenses.txt: the notices and license texts of
# everything built into the app, shown under Settings > About > Open-source
# licenses and attached to a release. Needs cargo-about
# (cargo install cargo-about --features cli). Run before a release build.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
texts="$(rustc --print sysroot)/share/doc/rust/licenses"
out="$here/app/src/main/assets/licenses.txt"
mkdir -p "$(dirname "$out")"
{
  cat <<'HEAD'
Lightning Fork for Android includes the following software, under the
licenses that follow. Source code for every component is available from the
project named with it; the Rust crates from crates.io and the repositories
listed.

================================================================================
Inter typeface
Copyright (c) 2020 The Inter Project Authors (https://github.com/rsms/inter)
Licensed under the SIL Open Font License, Version 1.1.

HEAD
  cat "$texts/OFL-1.1.txt"
  cat <<'ANDROID'

================================================================================
Android libraries, under the Apache License, Version 2.0 (text below):

  AndroidX (Activity, Biometric, Camera, Compose, Core, Lifecycle)
    Copyright The Android Open Source Project
  Kotlin standard library, kotlinx.coroutines, kotlinx.serialization
    Copyright JetBrains s.r.o. and Kotlin Programming Language contributors
  OkHttp, Okio
    Copyright Square, Inc.
  ZXing ("Zebra Crossing") core
    Copyright ZXing authors

ANDROID
  cat "$texts/Apache-2.0.txt"
  cat <<'MLKIT'

================================================================================
ML Kit barcode scanning
Copyright Google LLC. Used under the ML Kit Terms of Service,
https://developers.google.com/ml-kit/terms

================================================================================
The embedded Tor client (Arti, by The Tor Project) and its Rust dependencies:
MLKIT
  ( cd "$here/rust/lf-arti" && cargo about generate about.hbs 2>/dev/null )
} > "$out"
echo "Wrote $out ($(wc -l < "$out") lines)"
