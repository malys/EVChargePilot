#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ALLOWLIST="$PROJECT_ROOT/.github/security/permission-allowlist.txt"

mapfile -t allowed < <(grep -vE '^\s*(#|$)' "$ALLOWLIST" | tr -d '\r' | sed 's/[[:space:]]*$//')
declare -A allow_set=()
for permission in "${allowed[@]}"; do allow_set["$permission"]=1; done

failure=0
while IFS= read -r -d '' manifest; do
  while read -r permission; do
    [ -z "$permission" ] && continue
    if [ -z "${allow_set[$permission]:-}" ]; then
      echo "::error file=$manifest::Disallowed permission: $permission"
      failure=1
    fi
  done < <(grep -oE 'android:name="[^"]+"' "$manifest" \
    | sed -E 's/.*"(.*)"/\1/' \
    | grep -E '^(android|com)\.' \
    | grep -iE 'permission')
done < <(find "$PROJECT_ROOT/app/src" -name AndroidManifest.xml -print0)

if [ "$failure" -ne 0 ]; then
  echo "Permission-drift gate failed."
  exit 1
fi
echo "Permission-drift gate passed."
