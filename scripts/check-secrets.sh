#!/usr/bin/env bash
# Fails when anything that must not be public is in the tracked files, in the
# git history, or in a commit message.
#
# Usage: check-secrets.sh [--generic-only] [repo-dir]
#
# The generic patterns below ship with the repo. Patterns that would themselves
# reveal something private (names, key ids, key prefixes) live in an untracked
# denylist file: ${SHIP_DENYLIST:-$HOME/.config/ship-mobile/denylist}, one
# case-insensitive extended regex per line, '#' starts a comment. The script
# fails when that file is missing, unless --generic-only is passed (outside
# contributors have no denylist and only need the generic checks).
set -euo pipefail

generic_only=0
if [ "${1:-}" = "--generic-only" ]; then
  generic_only=1
  shift
fi

root="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
cd "$root"

# Case-insensitive extended regexes, matched against content.
patterns=(
  'SHIP_API_KEY=[^[:space:]]'
  'BEGIN PGP'
  # Only an assignment: build files legitimately name the property signing.keyId.
  'signing\.(password|keyId)[[:space:]]*[=:]'
  'signingInMemoryKey(Password|Id)?[[:space:]]*='
  'mavenCentral(Username|Password)[[:space:]]*='
  'arn:aws:'
)

denylist="${SHIP_DENYLIST:-$HOME/.config/ship-mobile/denylist}"
if [ "$generic_only" = 0 ]; then
  if [ ! -r "$denylist" ]; then
    echo "check-secrets: private denylist not found at $denylist" >&2
    echo "  Set SHIP_DENYLIST to its path, or pass --generic-only to run only the generic checks." >&2
    exit 2
  fi
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    # Skip blank lines and comments.
    case "$line" in
      ''|'#'*) continue ;;
    esac
    [ -z "${line//[[:space:]]/}" ] && continue
    patterns+=("$line")
  done < "$denylist"
fi

# Tracked files that must not exist at all, wherever they sit.
forbidden_paths='(^|/)(local\.properties|\.netrc|[^/]*\.(keystore|jks))$'
placeholder='REPLACE_WITH_SHIP_SAMPLE_PUBLIC_KEY'

# This script and its test name the generic patterns; they are the only exclusions.
excludes=(':(exclude)scripts/check-secrets.sh' ':(exclude)scripts/test-check-secrets.sh')

bad=0
has_commits=0
git rev-parse --verify -q HEAD >/dev/null && has_commits=1

# Read once into variables: `grep -q` on a live pipe would SIGPIPE git, and
# pipefail would then report the match as a miss.
history=""
messages=""
if [ "$has_commits" = 1 ]; then
  history="$(git log -p --format= -- . "${excludes[@]}")"
  messages="$(git log --format=%B)"
fi

# Denylist patterns are reported by number, never by text, so the output of a
# failing run does not itself spell the private value.
generic_count=6
i=0
for p in "${patterns[@]}"; do
  i=$((i + 1))
  if [ "$i" -le "$generic_count" ]; then
    label="pattern '$p'"
  else
    label="private denylist entry #$((i - generic_count))"
  fi
  hits="$(git grep -I -E -i -n -e "$p" -- . "${excludes[@]}" | cut -d: -f1,2 || true)"
  if [ -n "$hits" ]; then
    echo "FOUND in tracked files, $label:"
    echo "$hits" | sed 's/^/  /'
    bad=1
  fi
  if [ "$has_commits" = 1 ]; then
    if printf '%s\n' "$history" | grep -E -i -q -e "$p"; then
      echo "FOUND in git history, $label"
      bad=1
    fi
    if printf '%s\n' "$messages" | grep -E -i -q -e "$p"; then
      echo "FOUND in a commit message, $label"
      bad=1
    fi
  fi
done

paths="$(git ls-files | grep -E -e "$forbidden_paths" || true)"
if [ -n "$paths" ]; then
  echo "FOUND forbidden tracked path:"
  echo "$paths" | sed 's/^/  /'
  bad=1
fi

warn="$(git grep -I -F -n -e "$placeholder" -- . "${excludes[@]}" | cut -d: -f1,2 || true)"
if [ -n "$warn" ]; then
  echo "WARN: placeholder public key still present (fine for the repo, replace it in your own app):"
  echo "$warn" | sed 's/^/  /'
fi

if [ "$bad" -ne 0 ]; then
  echo "check-secrets: FAILED"
  exit 1
fi
if [ "$generic_only" = 1 ]; then
  echo "check-secrets: ok (generic checks only)"
else
  echo "check-secrets: ok"
fi
