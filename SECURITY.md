# Security policy

If you find a security vulnerability in these SDKs, the sample apps or the release
format, please report it to **ship@zynclave.com**.

Please do not open a public issue or pull request for it. Include what you found,
how to reproduce it, and which version or commit you tested. We will reply to
confirm we have it, and tell you when it is fixed.

Never put a Ship API key, a signing key or any other secret in an issue, a pull
request or a commit. Run `scripts/check-secrets.sh --generic-only` before you push.
Maintainers run it without the flag, with a private denylist file outside the repo
(`$SHIP_DENYLIST`, default `~/.config/ship-mobile/denylist`).
