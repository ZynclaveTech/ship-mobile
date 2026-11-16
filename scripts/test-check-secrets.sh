#!/usr/bin/env bash
# Tests scripts/check-secrets.sh against throwaway git repos.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
check="$here/check-secrets.sh"
[ -x "$check" ] || { echo "FAIL: $check is missing or not executable"; exit 1; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# A stand-in private denylist. The real one lives outside the repo; these
# neutral values exercise the same code path without naming anything private.
export SHIP_DENYLIST="$work/denylist"
cat > "$SHIP_DENYLIST" <<'DENY'
# comment lines and blank lines are ignored

fakeapp
live\.fakeapp
test-kid-2099-01
QkFTRTY0UFJFRklY
alias/fakeorg
DENY

failures=0
n=0

new_repo() {
  n=$((n + 1))
  repo="$work/r$n"
  mkdir -p "$repo"
  git -C "$repo" init -q
  git -C "$repo" config user.email t@example.invalid
  git -C "$repo" config user.name t
  echo "clean" > "$repo/README.md"
  git -C "$repo" add -A
  git -C "$repo" commit -q -m "initial"
}

commit_file() { # repo path content
  mkdir -p "$(dirname "$1/$2")"
  printf '%s\n' "$3" > "$1/$2"
  git -C "$1" add -A
  git -C "$1" commit -q -m "add $2"
}

expect_fail() { # label
  if "$check" "$repo" >/dev/null 2>&1; then
    echo "FAIL: $1 was not flagged"; failures=$((failures + 1))
  else
    echo "ok:   $1 flagged"
  fi
}

expect_pass() { # label
  if "$check" "$repo" >/dev/null 2>&1; then
    echo "ok:   $1"
  else
    echo "FAIL: $1 exited non-zero"; failures=$((failures + 1))
  fi
}

# Each planted line must be flagged. Built from pieces so this file does not
# itself spell a secret a scanner could trip on.
patterns=(
  "kid = test-kid-2099-01"
  "key = QkFTRTY0UFJFRklYabcdef"
  "the FakeApp app"
  "FAKEAPP"
  "fakeapp"
  "https://live.fakeapp.example"
  "SHIP_API_KEY=abc123"
  "-----BEGIN PGP PRIVATE KEY BLOCK-----"
  "signing.password=hunter2"
  "signing.keyId=ABCD"
  "signingInMemoryKey=abc"
  "mavenCentralUsername = u"
  "mavenCentralPassword=p"
  "arn:aws:iam::123456789012:role/x"
  "alias/fakeorg-prod"
)
for p in "${patterns[@]}"; do
  new_repo
  commit_file "$repo" "notes/file.txt" "$p"
  expect_fail "tracked content: $p"
done

new_repo; commit_file "$repo" "local.properties" "sdk.dir=/x"
expect_fail "tracked path local.properties"
new_repo; commit_file "$repo" "android/local.properties" "sdk.dir=/x"
expect_fail "tracked path android/local.properties"
new_repo; commit_file "$repo" ".netrc" "x"
expect_fail "tracked path .netrc"
new_repo; commit_file "$repo" "keys/release.keystore" "x"
expect_fail "tracked path *.keystore"
new_repo; commit_file "$repo" "upload.jks" "x"
expect_fail "tracked path *.jks"

# A denylist entry in a commit message is flagged, and the output names it
# only by number.
new_repo
echo x > "$repo/y.txt"; git -C "$repo" add -A
git -C "$repo" commit -q -m "ship it for FakeApp"
expect_fail "denylist entry in commit message"
out="$("$check" "$repo" 2>&1 || true)"
if printf '%s\n' "$out" | grep -q -i "fakeapp"; then
  echo "FAIL: output spelled the denylist value"; failures=$((failures + 1))
else
  echo "ok:   output names the denylist entry by number only"
fi

# Missing denylist: fails with a clear message, unless --generic-only.
new_repo
out="$(SHIP_DENYLIST="$work/missing" "$check" "$repo" 2>&1)" && rc=0 || rc=$?
if [ "$rc" -ne 0 ] && printf '%s\n' "$out" | grep -q "denylist not found"; then
  echo "ok:   missing denylist fails clearly"
else
  echo "FAIL: missing denylist gave rc=$rc"; failures=$((failures + 1))
fi
if SHIP_DENYLIST="$work/missing" "$check" --generic-only "$repo" >/dev/null 2>&1; then
  echo "ok:   --generic-only runs without a denylist"
else
  echo "FAIL: --generic-only failed on a clean repo"; failures=$((failures + 1))
fi
commit_file "$repo" "notes/a.txt" "fakeapp"
if SHIP_DENYLIST="$work/missing" "$check" --generic-only "$repo" >/dev/null 2>&1; then
  echo "ok:   --generic-only ignores private patterns"
else
  echo "FAIL: --generic-only applied a private pattern"; failures=$((failures + 1))
fi
commit_file "$repo" "notes/b.txt" "arn:aws:iam::1:role/x"
if SHIP_DENYLIST="$work/missing" "$check" --generic-only "$repo" >/dev/null 2>&1; then
  echo "FAIL: --generic-only missed a generic pattern"; failures=$((failures + 1))
else
  echo "ok:   --generic-only still flags generic patterns"
fi

# A secret committed and then deleted is still in history.
new_repo
commit_file "$repo" "tmp.txt" "SHIP_API_KEY=abc123"
git -C "$repo" rm -q tmp.txt
git -C "$repo" commit -q -m "remove it"
expect_fail "secret only in history"

# A secret in a commit message.
new_repo
echo x > "$repo/x.txt"; git -C "$repo" add -A
git -C "$repo" commit -q -m "uses arn:aws:iam::1:role/x"
expect_fail "secret in commit message"

# The script's own two files are the only exclusions.
new_repo
mkdir -p "$repo/scripts"
cp "$here/check-secrets.sh" "$here/test-check-secrets.sh" "$repo/scripts/"
git -C "$repo" add -A
git -C "$repo" commit -q -m "add scripts"
expect_pass "repo holding only the two script files"
commit_file "$repo" "scripts/other.sh" "test-kid-2099-01"
expect_fail "another file under scripts/"

# Clean repo passes; the placeholder only warns.
new_repo
expect_pass "clean repo"
new_repo
commit_file "$repo" "Key.swift" 'let k = "REPLACE_WITH_SHIP_SAMPLE_PUBLIC_KEY"'
expect_pass "placeholder is not an error"
out="$("$check" "$repo" 2>&1 || true)"
if printf '%s\n' "$out" | grep -q "WARN"; then
  echo "ok:   placeholder warns"
else
  echo "FAIL: placeholder gave no warning"; failures=$((failures + 1))
fi

if [ "$failures" -ne 0 ]; then
  echo "$failures failure(s)"; exit 1
fi
echo "all passed"
