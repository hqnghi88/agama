#!/bin/bash
set -euo pipefail

# Upload the rootfs archive to the GitHub 'rootfs' release asset that the app
# downloads on first launch (see AGENTS.md). Run after rebuilding the rootfs
# with build-rootfs.sh so the store APK always ships the latest tested rootfs.
# The publish APK itself must NOT embed res/raw/rootfs_tar_gz (Play size limit).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MOBILE_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

ARCHIVE="${1:-${MOBILE_ROOT}/android/app/src/main/res/raw/rootfs_tar_gz}"
REPO="${REPO:-hqnghi88/agama}"
TAG="rootfs"

if [ ! -f "$ARCHIVE" ]; then
  echo "Archive not found: $ARCHIVE" >&2
  echo "Build it first: ./scripts/build-rootfs.sh" >&2
  exit 1
fi

echo "Uploading $(du -h "$ARCHIVE" | cut -f1) rootfs archive to $REPO @ $TAG ..."
gh release upload "$TAG" "$ARCHIVE" --clobber --repo "$REPO"
echo "Verify:"
gh release view "$TAG" --repo "$REPO" --json assets \
  | python3 -c "import json,sys; [print(a['name'], a['size'], a['digest']) for a in json.load(sys.stdin)['assets'] if a['name']=='$(basename "$ARCHIVE")']"