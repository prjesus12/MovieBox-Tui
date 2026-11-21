#!/usr/bin/env bash
# Build the Rust core for Android and (re)generate the UniFFI Kotlin bindings.
#
# Produces:
#   android/app/src/main/jniLibs/<abi>/libmoviebox_tui.so   -- one shared library per ABI
#   android/app/src/main/java/uniffi/moviebox_tui/          -- Kotlin bindings
#
# The library is built as a `cdylib` with `cargo rustc --crate-type`, so
# Cargo.toml keeps `crate-type = ["rlib", "staticlib"]` and the terminal and iOS
# builds are untouched.
#
# Environment:
#   ANDROID_NDK_HOME  NDK root (default: newest under $ANDROID_HOME/ndk)
#   ANDROID_ABIS      space-separated ABIs (default: "arm64-v8a x86_64")
#   ANDROID_API       minimum API level for the NDK linker (default: 26)
#   PROFILE           release (default) or debug
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if [[ -z "${ANDROID_NDK_HOME:-}" ]]; then
    ANDROID_NDK_HOME="$(ls -d "$SDK"/ndk/* 2>/dev/null | sort -V | tail -1 || true)"
fi
if [[ -z "$ANDROID_NDK_HOME" || ! -d "$ANDROID_NDK_HOME" ]]; then
    echo "error: Android NDK not found; install it with: sdkmanager 'ndk;<version>'" >&2
    exit 1
fi

case "$(uname -s)" in
    Darwin) HOST_TAG="darwin-x86_64" ;;
    Linux) HOST_TAG="linux-x86_64" ;;
    *) echo "error: unsupported host $(uname -s)" >&2; exit 1 ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"

API="${ANDROID_API:-26}"
ABIS="${ANDROID_ABIS:-arm64-v8a x86_64}"
PROFILE="${PROFILE:-release}"
FEATURES="mobile"
JNI_DIR="$ROOT/android/app/src/main/jniLibs"
KOTLIN_OUT="$ROOT/android/app/src/main/java"

profile_flag=()
[[ "$PROFILE" == "release" ]] && profile_flag=(--release)

target_for_abi() {
    case "$1" in
        arm64-v8a) echo "aarch64-linux-android" ;;
        armeabi-v7a) echo "armv7-linux-androideabi" ;;
        x86_64) echo "x86_64-linux-android" ;;
        x86) echo "i686-linux-android" ;;
        *) echo "error: unknown ABI $1" >&2; exit 1 ;;
    esac
}

clang_prefix_for_target() {
    case "$1" in
        armv7-linux-androideabi) echo "armv7a-linux-androideabi" ;;
        *) echo "$1" ;;
    esac
}

last_lib=""
for abi in $ABIS; do
    target="$(target_for_abi "$abi")"
    rustup target add "$target" >/dev/null

    clang="$TOOLCHAIN/$(clang_prefix_for_target "$target")$API-clang"
    env_target="$(echo "$target" | tr '[:lower:]-' '[:upper:]_')"
    cc_target="$(echo "$target" | tr '-' '_')"

    echo "==> build $abi ($target)"
    env \
        "CARGO_TARGET_${env_target}_LINKER=$clang" \
        "CC_${cc_target}=$clang" \
        "AR_${cc_target}=$TOOLCHAIN/llvm-ar" \
        cargo rustc --lib "${profile_flag[@]}" --features "$FEATURES" \
            --target "$target" --crate-type cdylib

    lib="$ROOT/target/$target/$PROFILE/libmoviebox_tui.so"
    mkdir -p "$JNI_DIR/$abi"
    cp "$lib" "$JNI_DIR/$abi/"
    last_lib="$lib"
done

echo "==> generate Kotlin bindings"
cargo run --quiet --features "$FEATURES" --bin uniffi-bindgen -- generate \
    --library "$last_lib" \
    --language kotlin \
    --no-format \
    --out-dir "$KOTLIN_OUT"

echo "done:"
echo "  libraries -> $JNI_DIR"
echo "  bindings  -> $KOTLIN_OUT/uniffi/moviebox_tui"
