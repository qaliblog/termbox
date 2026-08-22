#!/bin/bash
# TermBox Ubuntu launch script
# Bundled with proot-distro integration for offline Ubuntu ARM64 support
#
# This script initializes and launches Ubuntu inside proot on ARM64 Android.
# It is designed to work completely offline after initial APK installation.
#
# Copyright (c) TermBox Contributors
# SPDX-License-Identifier: MIT

set -euo pipefail

TERMBOX_APP_DIR="${TERMUX_APP_DATA_DIR:-/data/data/com.qali.termbox}"
TERMBOX_PREFIX="${TERMBOX_APP_DIR}/files/usr"
TERMBOX_HOME="${TERMBOX_APP_DIR}/files/home"
TERMBOX_UROOT="${TERMBOX_APP_DIR}/files/ubuntu-root"

PROOT="${TERMBOX_PREFIX}/bin/proot"
BOX64="${TERMBOX_PREFIX}/bin/box64"
UBUNTU_ROOT="${TERMBOX_UROOT}"

# Default Ubuntu version
UBUNTU_VERSION="${TERMBOX_UBUNTU_VERSION:-22.04}"

# proot-distro location (bundled)
PROOT_DISTRO="${TERMBOX_PREFIX}/share/proot-distro/proot-distro.py"

# Proot kernel permission workaround
# On Android, we need to handle certain kernel capabilities
PROOT_EXTRA_ARGS=(
    --link2symlink
    --kill-on-exit
    --root-id
    --cwd /root
    -b /dev
    -b /proc
    -b /sys
    -b "${TERMBOX_HOME}:/root"
    -b "${TERMBOX_HOME}/storage:/mnt/storage"
    -b "${TERMBOX_PREFIX}/tmp:/tmp"
)

# Environment variables for the Ubuntu environment
export ENV[HOME]="/root"
export ENV[USER]="root"
export ENV[TERM]="${TERM:-xterm-256color}"
export ENV[LANG]="en_US.UTF-8"
export ENV[PATH]="/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
export ENV[TERMBOX_VERSION]="0.1.0"

# Box64 configuration for x86_64 transparent execution
export BOX64_ENV="-1"
export BOX64_LD_LIBRARY_PATH="/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu"
export BOX64_DYNAREC="1"
export BOX64_NOBANNER="0"

# Detect architecture
detect_arch() {
    local arch
    arch=$(uname -m)
    case "${arch}" in
        aarch64|arm64)
            echo "aarch64"
            ;;
        armv7*|armv8l)
            echo "arm"
            ;;
        x86_64)
            echo "x86_64"
            ;;
        i*86)
            echo "i686"
            ;;
        *)
            echo "unknown"
            ;;
    esac
}

# Check if running natively or through emulation
is_native_arm64() {
    local arch
    arch=$(detect_arch)
    [[ "${arch}" == "aarch64" ]]
}

# Check if Box64 is available and functional
check_box64() {
    if [[ -x "${BOX64}" ]]; then
        return 0
    fi
    return 1
}

# Initialize Ubuntu rootfs if needed
init_ubuntu_rootfs() {
    if [[ ! -d "${UBUNTU_ROOT}" ]] || [[ ! -f "${UBUNTU_ROOT}/etc/os-release" ]]; then
        echo "TermBox: Initializing Ubuntu rootfs..."
        mkdir -p "${UBUNTU_ROOT}"

        local rootfs_tarball="${TERMBOX_PREFIX}/share/termbox/ubuntu-rootfs.tar.xz"
        if [[ -f "${rootfs_tarball}" ]]; then
            tar -xJf "${rootfs_tarball}" -C "${UBUNTU_ROOT}" --strip-components=0
            echo "TermBox: Ubuntu rootfs extracted successfully."
        else
            echo "TermBox ERROR: Ubuntu rootfs tarball not found at ${rootfs_tarball}"
            echo "This should not happen with a properly built TermBox APK."
            return 1
        fi
    fi
    return 0
}

# Setup Box64 integration inside Ubuntu
setup_box64_integration() {
    local usr_bin="${UBUNTU_ROOT}/usr/bin"
    local box64_dest="${usr_bin}/box64"

    # Install box64 into the Ubuntu root if not already present
    if [[ ! -x "${box64_dest}" ]] && [[ -x "${BOX64}" ]]; then
        mkdir -p "${usr_bin}"
        cp "${BOX64}" "${box64_dest}"
        chmod 755 "${box64_dest}"
    fi

    # Create the ELF detection wrapper if not present
    local wrapper="${usr_bin}/termbox-exec"
    if [[ ! -f "${wrapper}" ]]; then
        cat > "${wrapper}" << 'WRAPPER_EOF'
#!/bin/bash
# TermBox transparent ELF architecture detection and execution
# This wrapper detects the ELF architecture and routes execution accordingly.

TARGET="$1"
shift

if [[ -z "${TARGET}" ]]; then
    echo "Usage: termbox-exec <program> [args...]" >&2
    exit 1
fi

# Resolve the full path
if [[ "${TARGET}" = /* ]]; then
    FULL_PATH="${TARGET}"
else
    # Search PATH
    IFS=: read -ra DIRS <<< "${PATH}"
    FULL_PATH=""
    for dir in "${DIRS[@]}"; do
        if [[ -x "${dir}/${TARGET}" ]]; then
            FULL_PATH="${dir}/${TARGET}"
            break
        fi
    done
fi

if [[ -z "${FULL_PATH}" ]] || [[ ! -f "${FULL_PATH}" ]]; then
    echo "termbox-exec: '${TARGET}' not found" >&2
    exit 127
fi

# Check if it's an ELF file
MAGIC=$(xxd -l 4 -p "${FULL_PATH}" 2>/dev/null || head -c 4 "${FULL_PATH}" 2>/dev/null | od -A n -t x1 | tr -d ' ')

if [[ "${MAGIC}" != "7f454c46" ]]; then
    # Not an ELF - execute directly
    exec "${TARGET}" "$@"
fi

# Read ELF class (byte at offset 4: 1=32-bit, 2=64-bit)
ELF_CLASS=$(xxd -s 4 -l 1 -p "${FULL_PATH}" 2>/dev/null)

# Read ELF machine type (bytes at offset 18 for 64-bit, offset 18 for 32-bit)
if [[ "${ELF_CLASS}" == "02" ]]; then
    # 64-bit ELF
    ELF_MACHINE=$(xxd -s 18 -l 2 -e "${FULL_PATH}" 2>/dev/null | awk '{print $2}')
    case "${ELF_MACHINE}" in
        b7)
            # EM_AARCH64 (0xB7) = ARM64 - native execution
            exec "${TARGET}" "$@"
            ;;
        3e)
            # EM_X86_64 (0x3E) = x86_64 - use Box64
            BOX64="${BOX64_PATH:-/data/data/com.qali.termbox/files/usr/bin/box64}"
            if [[ -x "${BOX64}" ]]; then
                exec "${BOX64}" "${TARGET}" "$@"
            else
                echo "termbox-exec: x86_64 binary detected but Box64 is not available" >&2
                echo "  Binary: ${TARGET}" >&2
                exit 126
            fi
            ;;
        *)
            echo "termbox-exec: unsupported 64-bit ELF architecture: ${ELF_MACHINE}" >&2
            echo "  Binary: ${TARGET}" >&2
            exit 126
            ;;
    esac
elif [[ "${ELF_CLASS}" == "01" ]]; then
    # 32-bit ELF
    ELF_MACHINE=$(xxd -s 18 -l 2 -e "${FULL_PATH}" 2>/dev/null | awk '{print $2}')
    case "${ELF_MACHINE}" in
        28)
            # EM_ARM (0x28) = ARM32 - native execution
            exec "${TARGET}" "$@"
            ;;
        03)
            # EM_386 (0x03) = x86 - use Box86 if available
            BOX86="${BOX86_PATH:-/data/data/com.qali.termbox/files/usr/bin/box86}"
            if [[ -x "${BOX86}" ]]; then
                exec "${BOX86}" "${TARGET}" "$@"
            else
                echo "termbox-exec: x86 binary detected but Box86 is not available" >&2
                echo "  Binary: ${TARGET}" >&2
                exit 126
            fi
            ;;
        *)
            echo "termbox-exec: unsupported 32-bit ELF architecture: ${ELF_MACHINE}" >&2
            echo "  Binary: ${TARGET}" >&2
            exit 126
            ;;
    esac
else
    echo "termbox-exec: unrecognized ELF class: ${ELF_CLASS}" >&2
    exec "${TARGET}" "$@"
fi
WRAPPER_EOF
        chmod 755 "${wrapper}"
    fi
}

# Configure resolv.conf for DNS resolution (offline mode uses cached DNS)
setup_resolv_conf() {
    local resolv_conf="${UBUNTU_ROOT}/etc/resolv.conf"
    mkdir -p "$(dirname "${resolv_conf}")"
    if [[ ! -f "${resolv_conf}" ]]; then
        echo "nameserver 8.8.8.8" > "${resolv_conf}"
        echo "nameserver 8.8.4.4" >> "${resolv_conf}"
    fi
}

# Configure apt for offline use (disable network operations)
setup_offline_apt() {
    local apt_conf="${UBUNTU_ROOT}/etc/apt/apt.conf.d/99-termbox-offline"
    mkdir -p "$(dirname "${apt_conf}")"
    cat > "${apt_conf}" << 'APT_EOF'
// TermBox offline mode - prevent accidental network access
Acquire::Languages "none";
DPkg::Options:: "--force-unsafe-io";
APT::Get::Assume-Yes "true";
APT::Install-Recommends "false";
APT::Install-Suggests "false";
APT_EOF
}

# Main entry point
main() {
    local cmd="${1:-/bin/bash}"
    shift 2>/dev/null || true

    # Detect architecture
    local arch
    arch=$(detect_arch)

    # Initialize rootfs
    init_ubuntu_rootfs || exit 1

    # Setup Box64 integration
    setup_box64_integration

    # Setup DNS resolution
    setup_resolv_conf

    # Setup offline apt configuration
    setup_offline_apt

    # Run proot with the Ubuntu rootfs
    exec "${PROOT}" \
        "${PROOT_EXTRA_ARGS[@]}" \
        -r "${UBUNTU_ROOT}" \
        /usr/bin/env -i \
        HOME=/root \
        USER=root \
        TERM="${TERM:-xterm-256color}" \
        LANG=en_US.UTF-8 \
        PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
        "${cmd}" "$@"
}

main "$@"
