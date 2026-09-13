#!/bin/sh
# TermBox ADB bridge - guest adb client build.
#
# Produces a static ARM64 `adb` binary from
# app/src/main/cpp/adb/adb_client.c for installation into the Ubuntu/PRoot
# guest rootfs as /usr/bin/adb.
#
# Usage:
#   sh ./termbox-assets/prebuilt/adb/build-adb-client.sh [output-path]
#
# Prefers the Android NDK (aarch64-linux-android target, statically linked
# against bionic so it runs in the guest without extra libs); falls back to a
# native gcc build for host testing when the NDK is not available.
set -e

OUT="${1:-$(pwd)/termbox-assets/prebuilt/adb/adb}"
SRC="$(dirname "$0")/../../../app/src/main/cpp/adb/adb_client.c"
SRC="$(cd "$(dirname "$SRC")" && pwd)/$(basename "$SRC")"

mkdir -p "$(dirname "$OUT")"

# Find an NDK toolchain if one is configured.
NDK_BUILD=""
if [ -n "$ANDROID_NDK_HOME" ] && [ -x "$ANDROID_NDK_HOME/ndk-build" ]; then
    NDK_BUILD="$ANDROID_NDK_HOME"
elif [ -n "$ANDROID_NDK" ] && [ -x "$ANDROID_NDK/ndk-build" ]; then
    NDK_BUILD="$ANDROID_NDK"
elif [ -x "$ANDROID_HOME/ndk-bundle/ndk-build" ]; then
    NDK_BUILD="$ANDROID_HOME/ndk-bundle"
fi

if [ -n "$NDK_BUILD" ]; then
    # Locate the NDK's clang wrapper for this project's NDK version.
    TOOLCHAIN="$(ls -d "$NDK_BUILD"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -n 1 || true)"
    if [ -n "$TOOLCHAIN" ] && [ -x "$TOOLCHAIN/aarch64-linux-android21-clang" ]; then
        echo "[adb] Cross-building with NDK: $TOOLCHAIN/aarch64-linux-android21-clang"
        "$TOOLCHAIN/aarch64-linux-android21-clang" -std=c11 -O2 -Wall -Wextra \
            -static -o "$OUT" "$SRC"
        exit 0
    fi
    echo "[adb] NDK found at $NDK_BUILD but no aarch64 clang wrapper; falling back to host gcc."
fi

echo "[adb] Building with host gcc (testing build, not device-ready):"
gcc -std=c11 -O2 -Wall -Wextra -static -o "$OUT" "$SRC"
echo "[adb] Built $OUT"
