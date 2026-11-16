# Signing test vector

Made once with the Ship server's own manifest signing and envelope code (`manifest.Sign`
with a local Ed25519 signer, then checked with the server's `manifest.Verify`), from a
throwaway key pair generated for this purpose. The private key was discarded and is not
in this repo.

- `envelope-ios.json`, `envelope-android.json`: signed envelopes for app `ship-sample`,
  channel `testflight`, runtimes `ios-1.0.0` and `android-1.0.0`, one asset each.
- `asset-strings-en.json`: the asset both manifests name (`strings/en.json`).
- `public-key.b64`: the raw 32-byte Ed25519 public key, base64.
- `kid.txt`: the key id, `test-vector-1`.

Both SDK test suites load these files: each envelope must verify, and flipping one byte of
its payload must fail with a bad signature.
