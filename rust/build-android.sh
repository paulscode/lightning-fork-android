#!/usr/bin/env bash
# Cross-compile the embedded Arti (Tor) client to Android jniLibs.
#
# Prereqs: rustup, cargo-ndk (`cargo install cargo-ndk`), an installed NDK, and
# the Rust Android target(s): `rustup target add aarch64-linux-android`.
#
# Usage:
#   ./build-android.sh                 # arm64-v8a only (fast; real devices)
#   ABIS="arm64-v8a armeabi-v7a x86_64 x86" ./build-android.sh   # release set
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../app/src/main/jniLibs"
: "${ANDROID_NDK_HOME:=$HOME/Android/Sdk/ndk/26.1.10909125}"
: "${ABIS:=arm64-v8a}"
: "${API:=26}"
export ANDROID_NDK_HOME
# 16 KB pages: newer devices load only native code aligned to them.
export RUSTFLAGS="${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384"

declare -A RUST_TARGET=(
  [arm64-v8a]=aarch64-linux-android
  [armeabi-v7a]=armv7-linux-androideabi
  [x86_64]=x86_64-linux-android
  [x86]=i686-linux-android
)

targets=()
for abi in $ABIS; do
  rt="${RUST_TARGET[$abi]:-}"
  [ -n "$rt" ] || { echo "unknown ABI: $abi" >&2; exit 1; }
  rustup target add "$rt" >/dev/null 2>&1 || true
  targets+=(-t "$abi")
done

cd "$here/lf-arti"
ANDROID_NDK_HOME="$ANDROID_NDK_HOME" cargo ndk "${targets[@]}" -p "$API" -o "$out" build --release
echo "Built $ABIS into $out"
