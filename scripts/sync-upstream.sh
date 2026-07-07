#!/usr/bin/env bash
#
# Bump the upstream/ryubing submodule to a new tag/commit, re-apply the Android
# patch queue, and run a sanity build of libryubing.so.
#
# Usage: scripts/sync-upstream.sh <tag-or-commit> [--no-build]
#
# On success it prints the new pin so you can update compat/pins.json.
set -euo pipefail

REF="${1:-}"
if [[ -z "$REF" ]]; then
  echo "usage: $0 <tag-or-commit> [--no-build]" >&2
  exit 1
fi
NO_BUILD=0
[[ "${2:-}" == "--no-build" ]] && NO_BUILD=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="$REPO_ROOT/upstream/ryubing"

echo "==> Fetching upstream"
git -C "$UPSTREAM" fetch --all --tags --prune

echo "==> Resetting submodule to $REF (discarding any applied patches)"
git -C "$UPSTREAM" reset --hard
git -C "$UPSTREAM" checkout "$REF"

echo "==> Re-applying Android patch queue"
"$REPO_ROOT/scripts/apply-patches.sh"

if [[ $NO_BUILD -eq 0 ]]; then
  echo "==> Sanity: publishing libryubing.so"
  "$REPO_ROOT/scripts/publish-libryubing.sh" || {
    echo "warning: libryubing publish failed against $REF — record as a breakage." >&2
    exit 2
  }
fi

COMMIT="$(git -C "$UPSTREAM" rev-parse HEAD)"
DESCRIBE="$(git -C "$UPSTREAM" describe --tags 2>/dev/null || echo "$COMMIT")"

cat <<EOF

==> Sync complete.
    upstream_tag:    $DESCRIBE
    upstream_commit: $COMMIT

Update compat/pins.json and commit the submodule bump:
    git add upstream/ryubing compat/pins.json
    git commit -m "chore: bump upstream to $DESCRIBE"
EOF
