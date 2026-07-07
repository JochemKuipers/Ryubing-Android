#!/usr/bin/env bash
#
# Update compat/pins.json to reflect the current upstream/ryubing checkout and
# the number of patches in the queue. Intended to run after a successful sync.
#
# Usage: scripts/pin-upstream.sh [app_version]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="$REPO_ROOT/upstream/ryubing"
PINS="$REPO_ROOT/compat/pins.json"

APP_VERSION="${1:-$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["app_version"])' "$PINS" 2>/dev/null || echo "0.1.0-dev")}"
COMMIT="$(git -C "$UPSTREAM" rev-parse HEAD)"
DESCRIBE="$(git -C "$UPSTREAM" describe --tags 2>/dev/null || echo "$COMMIT")"
shopt -s nullglob
PATCH_COUNT=$(ls "$REPO_ROOT"/patches/[0-9]*.patch 2>/dev/null | wc -l | tr -d ' ')
shopt -u nullglob

python3 - "$PINS" "$APP_VERSION" "$DESCRIBE" "$COMMIT" "$PATCH_COUNT" <<'PY'
import json, sys
pins_path, app_version, tag, commit, patch_count = sys.argv[1:6]
try:
    with open(pins_path) as f:
        data = json.load(f)
except FileNotFoundError:
    data = {}
data.update({
    "app_version": app_version,
    "upstream_repo": data.get("upstream_repo", "ssh://forgejo@git.ryujinx.app/projects/Ryubing.git"),
    "upstream_branch": data.get("upstream_branch", "master"),
    "upstream_tag": tag,
    "upstream_commit": commit,
    "patches_applied": int(patch_count),
})
data.setdefault("known_breakages", [])
data.setdefault("smoke_tests_passed", [])
with open(pins_path, "w") as f:
    json.dump(data, f, indent=2)
    f.write("\n")
print(f"Pinned {app_version} -> {tag} ({commit[:12]}), {patch_count} patch(es).")
PY
