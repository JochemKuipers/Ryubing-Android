#!/usr/bin/env bash
#
# Apply the Android patch queue to the upstream/ryubing submodule.
#
# Patches live in patches/NNNN-*.patch and are applied in lexical order with
# `git am`. They are the ONLY sanctioned way to modify upstream source; day-to-day
# Android work belongs in src/LibRyubing or src/RyubingAndroid instead.
#
# Usage: scripts/apply-patches.sh [--check]
#   --check   Dry-run: verify patches apply cleanly without committing.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="$REPO_ROOT/upstream/ryubing"
PATCH_DIR="$REPO_ROOT/patches"

CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

if [[ ! -d "$UPSTREAM/.git" && ! -f "$UPSTREAM/.git" ]]; then
  echo "error: submodule not initialized. Run: git submodule update --init --recursive" >&2
  exit 1
fi

shopt -s nullglob
patches=("$PATCH_DIR"/[0-9]*.patch)
shopt -u nullglob

if [[ ${#patches[@]} -eq 0 ]]; then
  echo "No patches in $PATCH_DIR — upstream left pristine."
  exit 0
fi

echo "Found ${#patches[@]} patch(es)."

if [[ $CHECK_ONLY -eq 1 ]]; then
  for p in "${patches[@]}"; do
    if git -C "$UPSTREAM" apply --check "$p" 2>/dev/null; then
      echo "  ok    $(basename "$p")"
    else
      echo "  FAIL  $(basename "$p")" >&2
      exit 1
    fi
  done
  echo "All patches apply cleanly."
  exit 0
fi

for p in "${patches[@]}"; do
  echo "Applying $(basename "$p")"
  if ! git -C "$UPSTREAM" am --3way "$p"; then
    echo "error: failed to apply $(basename "$p")." >&2
    echo "Resolve conflicts in $UPSTREAM, then run 'git am --continue' or regenerate the patch." >&2
    exit 1
  fi
done

echo "Applied ${#patches[@]} patch(es) to upstream/ryubing."
