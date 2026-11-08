#!/usr/bin/env bash
# Fetch the pinned llama.cpp source used by the native inference engine.
# The source is intentionally not committed; this script makes the build reproducible.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/third_party/llama.cpp"
PINNED_COMMIT="cd959bae99ff4bed22bef2604f45241c09bbb8c8" # tag v0.9.0

mkdir -p "$ROOT/third_party"

if [ -d "$DEST/.git" ]; then
    echo "llama.cpp already present: $DEST"
else
    echo "Cloning llama.cpp (v0.9.0)..."
    rm -rf "$DEST"
    git clone --filter=blob:none --no-checkout https://github.com/ggml-org/llama.cpp.git "$DEST"
    git -C "$DEST" fetch --depth 1 origin "$PINNED_COMMIT"
    git -C "$DEST" checkout --detach "$PINNED_COMMIT"
fi

ACTUAL="$(git -C "$DEST" rev-parse HEAD)"
if [ "$ACTUAL" != "$PINNED_COMMIT" ]; then
    echo "ERROR: llama.cpp commit mismatch: $ACTUAL != $PINNED_COMMIT" >&2
    exit 1
fi
echo "llama.cpp ready at $ACTUAL"
