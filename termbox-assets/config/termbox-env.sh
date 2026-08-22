#!/bin/bash
# TermBox Environment Configuration
# Sets up the runtime environment variables for TermBox
#
# Copyright (c) TermBox Contributors
# SPDX-License-Identifier: MIT

# TermBox paths
export TERMBOX_APP_DATA_DIR="/data/data/com.qali.termbox"
export TERMBOX_PREFIX="${TERMBOX_APP_DATA_DIR}/files/usr"
export TERMBOX_HOME="${TERMBOX_APP_DATA_DIR}/files/home"
export TERMBOX_UROOT="${TERMBOX_APP_DATA_DIR}/files/ubuntu-root"

# TermBox version info
export TERMBOX_VERSION="0.1.0"
export TERMBOX_ENV_VERSION="1"

# Termux compatibility - many tools expect these
export PREFIX="${TERMBOX_PREFIX}"
export HOME="${TERMBOX_HOME}"
export TMPDIR="${TERMBOX_PREFIX}/tmp"
export TEMP="${TMPDIR}"
export TMP="${TMPDIR}"

# Box64 configuration
export BOX64_PATH="${TERMBOX_PREFIX}/bin/box64"
export BOX64_DYNAREC=1
export BOX64_DYNAREC_STRONGMEM=1
export BOX64_DYNAREC_BIGBLOCK=1
export BOX64_DYNAREC_SAFEFLAGS=1
export BOX64_DYNAREC_BLEEDING_EDGE=1
export BOX64_ENV=0
export BOX64_LOG=0

# Box86 configuration (optional)
export BOX86_PATH="${TERMBOX_PREFIX}/bin/box86"
export BOX86_DYNAREC=1
export BOX86_DYNAREC_STRONGMEM=1
export BOX86_DYNAREC_BIGBLOCK=1

# Android-specific environment
export ANDROID_DATA="${TERMBOX_APP_DATA_DIR}"
export ANDROID_ROOT="/system"
export ANDROID_DNS="1"

# Locale
export LANG="en_US.UTF-8"
export LC_ALL="en_US.UTF-8"

# Standard paths
export PATH="${TERMBOX_PREFIX}/bin:${TERMBOX_PREFIX}/bin/applets:${PATH}"
export LD_LIBRARY_PATH="${TERMBOX_PREFIX}/lib:${LD_LIBRARY_PATH:-}"

# Man pages
export MANPATH="${TERMBOX_PREFIX}/share/man:${MANPATH:-}"

# Build tools (if Android SDK is bundled)
if [[ -d "${TERMBOX_PREFIX}/share/android-sdk" ]]; then
    export ANDROID_HOME="${TERMBOX_PREFIX}/share/android-sdk"
    export ANDROID_SDK_ROOT="${ANDROID_HOME}"
    export PATH="${ANDROID_HOME}/build-tools/$(ls "${ANDROID_HOME}/build-tools" 2>/dev/null | sort -V | tail -1):${PATH}"
fi
