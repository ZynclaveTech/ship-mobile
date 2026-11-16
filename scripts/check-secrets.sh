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
# Everything after these came from the denylist.
generic_count=${#patterns[@]}

denylist="${SHIP_DENYLIST:-$HOME/.config/ship-mobile/denylist}"
if [ "$generic_only" = 0 ]; then
  if [ ! -r "$denylist" ]; then
    echo "check-secrets: private denylist not found at $denylist" >&2
    echo "  Set SHIP_DENYLIST to its path, or pass --generic-only to run only the generic checks." >&2
    exit 2
  fi
  lineno=0
  while IFS= read -r line || [ -n "$line" ]; do
    lineno=$((lineno + 1))
    line="${line%$'\r'}"
    # Skip blank lines and comments.
    case "$line" in
      ''|'#'*) continue ;;
    esac
    [ -z "${line//[[:space:]]/}" ] && continue
    # grep exits 2 on a bad regex: a pattern that can never match must not
    # pass silently as "nothing found". Named by line, not by text.
    rc=0
    grep -E -i -q -e "$line" </dev/null 2>/dev/null || rc=$?
    if [ "$rc" -ge 2 ]; then
      echo "check-secrets: invalid regex on line $lineno of the private denylist ($denylist)" >&2
      exit 2
    fi
    patterns+=("$line")
  done < "$denylist"
fi

# Tracked files that must not exist at all, wherever they sit.
forbidden_paths='(^|/)(local\.properties|\.netrc|[^/]*\.(keystore|jks))$'
placeholder='REPLACE_WITH_SHIP_SAMPLE_PUBLIC_KEY'

# This script and its test spell the generic patterns, so they are excluded
# from the GENERIC checks only. Denylist patterns run over everything: these
# two files must never hold a private value either.
excludes=(':(exclude)scripts/check-secrets.sh' ':(exclude)scripts/test-check-secrets.sh')

bad=0
has_commits=0
git rev-parse --verify -q HEAD >/dev/null && has_commits=1

# Every ref (all branches, tags, stash), not only the checked-out branch. Read
# once into variables and matched with here-strings, never `printf | grep -q`:
# grep -q exits at the first match, printf dies of SIGPIPE, and pipefail would
# turn that match into a miss.
history_generic=""
history_all=""
messages=""
if [ "$has_commits" = 1 ]; then
  history_generic="$(git log --all -p --format= -- . "${excludes[@]}")"
  history_all="$(git log --all -p --format=)"
  messages="$(git log --all --format=%B)"
fi

# Denylist patterns are reported by number, never by text, so the output of a
# failing run does not itself spell the private value.
i=0
for p in "${patterns[@]}"; do
  i=$((i + 1))
  if [ "$i" -le "$generic_count" ]; then
    label="pattern '$p'"
    scope=(-- . "${excludes[@]}")
    history="$history_generic"
  else
    label="private denylist entry #$((i - generic_count))"
    scope=(-- .)
    history="$history_all"
  fi
  hits="$(git grep -I -E -i -n -e "$p" "${scope[@]}" | cut -d: -f1,2 || true)"
  if [ -n "$hits" ]; then
    echo "FOUND in tracked files, $label:"
    echo "$hits" | sed 's/^/  /'
    bad=1
  fi
  if [ "$has_commits" = 1 ]; then
    if grep -E -i -q -e "$p" <<<"$history"; then
      echo "FOUND in git history, $label"
      bad=1
    fi
    if grep -E -i -q -e "$p" <<<"$messages"; then
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
