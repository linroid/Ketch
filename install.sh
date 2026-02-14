#!/usr/bin/env bash
#
# KDown CLI installer
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/linroid/KDown/main/install.sh | bash
#
# Options (via environment variables):
#   KDOWN_VERSION   - specific version to install (default: latest)
#   KDOWN_INSTALL   - installation directory (default: /usr/local/bin)
#
set -euo pipefail

REPO="linroid/KDown"
BINARY_NAME="kdown"
INSTALL_DIR="${KDOWN_INSTALL:-/usr/local/bin}"

# --- Helpers ---------------------------------------------------------------

info() { printf "\033[1;34m==>\033[0m %s\n" "$*"; }
warn() { printf "\033[1;33mWarning:\033[0m %s\n" "$*" >&2; }
error() {
  printf "\033[1;31mError:\033[0m %s\n" "$*" >&2
  exit 1
}

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    error "Required command '$1' not found. Please install it and try again."
  fi
}

# --- Detect OS and architecture --------------------------------------------

detect_os() {
  local os
  os="$(uname -s)"
  case "$os" in
    Linux*)  echo "linux" ;;
    Darwin*) echo "macos" ;;
    MINGW*|MSYS*|CYGWIN*) echo "windows" ;;
    *) error "Unsupported operating system: $os" ;;
  esac
}

detect_arch() {
  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64)  echo "x64" ;;
    aarch64|arm64) echo "aarch64" ;;
    *) error "Unsupported architecture: $arch" ;;
  esac
}

# --- Resolve version -------------------------------------------------------

resolve_version() {
  if [ -n "${KDOWN_VERSION:-}" ]; then
    # Strip leading 'v' if present
    echo "${KDOWN_VERSION#v}"
    return
  fi

  info "Fetching latest release..."
  local tag
  tag="$(curl -fsSL \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/${REPO}/releases/latest" \
    | grep '"tag_name"' | head -1 | sed 's/.*"tag_name": *"//;s/".*//')"

  if [ -z "$tag" ]; then
    error "Failed to determine the latest release version."
  fi
  echo "${tag#v}"
}

# --- Download and install ---------------------------------------------------

main() {
  need_cmd curl
  need_cmd uname

  local os arch version
  os="$(detect_os)"
  arch="$(detect_arch)"
  version="$(resolve_version)"

  info "Installing kdown v${version} (${os}/${arch})"

  # macOS CLI is only available for arm64
  if [ "$os" = "macos" ] && [ "$arch" = "x64" ]; then
    error "macOS x64 (Intel) builds are not available. Only arm64 (Apple Silicon) is supported."
  fi

  # Windows via this script is best-effort
  if [ "$os" = "windows" ]; then
    warn "For Windows, consider downloading the .zip manually from:"
    warn "  https://github.com/${REPO}/releases/latest"
  fi

  local ext="tar.gz"
  if [ "$os" = "windows" ]; then
    ext="zip"
  fi

  local filename="kdown-cli-${version}-${os}-${arch}.${ext}"
  local url="https://github.com/${REPO}/releases/download/v${version}/${filename}"
  local tmpdir
  tmpdir="$(mktemp -d)"
  trap 'rm -rf "$tmpdir"' EXIT

  info "Downloading ${url}"
  if ! curl -fSL --progress-bar -o "${tmpdir}/${filename}" "$url"; then
    error "Download failed. Check that version v${version} exists and has a CLI build for ${os}/${arch}."
  fi

  info "Extracting..."
  case "$ext" in
    tar.gz)
      tar -xzf "${tmpdir}/${filename}" -C "$tmpdir"
      ;;
    zip)
      need_cmd unzip
      unzip -q "${tmpdir}/${filename}" -d "$tmpdir"
      ;;
  esac

  local binary="${tmpdir}/${BINARY_NAME}"
  if [ "$os" = "windows" ]; then
    binary="${binary}.exe"
  fi

  if [ ! -f "$binary" ]; then
    error "Expected binary not found after extraction."
  fi

  chmod +x "$binary"

  # Install to target directory
  if [ -w "$INSTALL_DIR" ]; then
    mv "$binary" "${INSTALL_DIR}/${BINARY_NAME}"
  else
    info "Installing to ${INSTALL_DIR} (requires sudo)..."
    sudo mv "$binary" "${INSTALL_DIR}/${BINARY_NAME}"
  fi

  info "Installed kdown to ${INSTALL_DIR}/${BINARY_NAME}"

  # Verify installation
  if command -v kdown >/dev/null 2>&1; then
    info "Done! Run 'kdown --help' to get started."
  else
    warn "${INSTALL_DIR} is not in your PATH."
    warn "Add it with: export PATH=\"${INSTALL_DIR}:\$PATH\""
  fi
}

main "$@"
