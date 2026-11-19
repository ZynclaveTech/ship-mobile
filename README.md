# Zynclave Ship mobile SDKs

Ship sends signed text, images, flags, popups and feed cards to apps that are
already in the store, without a store release. It changes content and
configuration only, never code. The app checks the signature and the fingerprint
of every file before it uses it. Full docs: <https://ship.zynclave.com/docs>.

This repo holds the iOS and Android SDKs, one sample app for each, and the sample
release both apps show.

| Path | What it is |
| --- | --- |
| `Package.swift`, `ios/` | Swift package: `ZynclaveShip` (core) and `ZynclaveShipUI` (SwiftUI popups and cards) |
| `android/ship-core`, `android/ship-ui` | Android libraries: core, and the Compose popups and cards |
| `samples/ios`, `samples/android` | Sample apps. Their `Ship.swift` and `Ship.kt` are the setup code to copy |
| `content/` | The sample release: `ship.yaml` and `ota/<platform>` |

## Install the `ship` command line

```sh
curl -fsSL https://ship.zynclave.com/install.sh | sh
ship version
```

This README needs `ship` **v0.1.2 or newer**. That release is the first whose
validator rejects component types only a private app can render, so a release that
passes `ship validate` is one an outside app can show.

## Run the iOS sample

1. Open `samples/ios/ShipSample.xcodeproj` in Xcode and run it on a simulator or phone.
2. The sample ships with a placeholder public key, so until you replace it the app
   shows **"Ship is not configured"**. The key is one constant,
   `SampleKey.publicKey` in `samples/ios/ShipSample/Ship.swift`. Paste the public
   key of the Ship app that publishes your sample release.
3. Run again and tap Check now. The app shows the sample release: a welcome popup,
   a hello card, strings, flags and a theme colour.

The welcome popup is held back on the first launch after install, so you see it from the
second launch on, and it shows once per id per device. To see it again, use **Reset popup
memory** on the Debug screen.

A debug build reads the `testflight` channel and a store build reads `production`.

## Run the Android sample

1. Open `android/` in Android Studio, or run `./gradlew :sample:installDebug` from `android/`.
2. As on iOS, the sample shows **"Ship is not configured"** until you replace its
   placeholder key. The key is one constant, `SAMPLE_PUBLIC_KEY` in
   `samples/android/src/main/kotlin/com/zynclave/shipsample/Ship.kt`.
3. Run it and tap Check now to see the same sample release. The welcome popup follows the
   same rules as on iOS: not on the first launch after install, once per id per device.

Debug builds read `testflight`, release builds read `production`.

## Add the SDK to your app

Both are available once 0.1.0 is released.

**iOS (SwiftPM).** Add the package `https://github.com/ZynclaveTech/ship-mobile`, from `"0.1.0"`:

```swift
.package(url: "https://github.com/ZynclaveTech/ship-mobile", from: "0.1.0")
// products: "ZynclaveShip" (core), "ZynclaveShipUI" (popups and cards)
```

**Android (Gradle).**

```kotlin
implementation("com.zynclave.ship:ship-ui:0.1.0")
```

`ship-ui` pulls in `ship-core`. Depend on `com.zynclave.ship:ship-core` alone if you
only want strings, flags and theme and no popups or cards.

### Minimal setup

Taken from the samples (`Ship.swift`, `Ship.kt`). Create the client once, then the
UI helpers on top of it.

```swift
let config = ShipConfig(
    cdn: URL(string: "https://ota.zynclave.com/")!,
    app: "ship-sample",
    channel: channel,        // "testflight" in debug, "production" otherwise
    runtime: runtime,        // "ios-" + CFBundleShortVersionString
    pinned: [key],           // PinnedKey(kid:base64:)
    bucket: ShipConfig.stableBucket()
)
let client = ShipClient(config: config, root: URL.applicationSupportDirectory.appending(path: "ship"))
let options = ShipUIOptions(deeplinkScheme: "shipsample", openDeeplink: { router.open($0) })
let cards = ShipCards(client: client, options: options)
let popups = ShipPopups(client: client, options: options)
```

```kotlin
val config = ShipConfig(
    cdn = "https://ota.zynclave.com/".toHttpUrl(),
    app = "ship-sample",
    channel = channel,       // "testflight" in debug, "production" otherwise
    runtime = runtime,       // "android-" + versionName
    pinned = listOf(key),    // PinnedKey.of(kid, base64)
    bucket = ShipConfig.stableBucket(context),
)
val client = ShipClient(config, File(context.filesDir, "ship"))
val options = ShipUiOptions(deeplinkScheme = "shipsample", openDeeplink = ::open)
val cards = ShipCards(context, client, options)
val popups = ShipPopups(context, client, options)
```

Your host app has a few jobs:

- Call `client.sync()` when you want fresh content (for example at launch and on foreground).
- After every sync, call `popups.armSoon()` and `cards.refresh()` (the client has no
  change signal of its own).
- Create **one** `ShipPopups` per process. Two would each keep their own state and show the same popup twice.
- When the signed-in user's eligibility changes, call `cards.refresh()`.
- Pass what only your app knows through `ShipUIOptions` / `ShipUiOptions`: `eligibility`,
  `popupGate`, `appString`, `personalize`, `analytics`, `color` and `language`.
- OTA strings are read in your app's language by default, not the device's: on iOS the
  bundle's preferred localization, on Android the locale of the resources the popup or card
  is drawn with (so a per-app language or an in-app picker applies). Set `language` to
  override it; on Android, `options.currentLanguage()` gives the same code for your own text.

## Get your own workspace

Ship is in early access and keys are issued by hand. Request access at
<https://ship.zynclave.com/start>. You get a workspace, an API key and the public key
your app pins.

## Make it yours

Copy `content/` as a starting point and change:

- **App id.** `app:` in `ship.yaml`, and `app` in `ShipConfig`. They must match.
- **Public key.** The key your workspace gives you, in `PinnedKey`. A release signed
  by anything else is ignored.
- **Scheme.** `deeplink_scheme:` in `ship.yaml` and `deeplinkScheme` in
  `ShipUIOptions` / `ShipUiOptions`, the same value in both. A `deeplink` button must use it.
- **Runtime.** `runtime` must be your real app version: `ios-<CFBundleShortVersionString>`
  and `android-<versionName>`, for example `ios-1.4.0`. Use your real version so a payload never reaches a build that cannot render it.
  `"*"` is reserved for `ship revert` and is not a value to write here.

```yaml
api: https://api.ship.zynclave.com
app: ship-sample
deeplink_scheme: shipsample
platforms:
  ios:
    runtime: ios-1.0.0
    assets: ota/ios
  android:
    runtime: android-1.0.0
    assets: ota/android
```

## Write content

A publish replaces that platform's whole release, so `content/ota/<platform>` is the
complete release: everything you want live must be in it.

```
ota/<platform>/
  sdui/popups/<id>.json    one home popup
  sdui/cards/<id>.json     one feed card
  strings/<lang>.json      translated text
  config/flags.json        flags
  config/theme.json        theme colour overrides
  lottie/<name>.json       animations used by popups and cards
  images/<name>.png        images used by popups and cards
```

Ids are lowercase letters, digits and hyphens, up to 64 characters. Devices remember
which ids they showed, so changed content needs a new id.

**Components.** `vstack`, `hstack`, `text`, `image`, `lottie`, `button`, `badge`,
`spacer`. A definition may have at most 40 nodes, nested at most 6 deep, and text is
limited to 500 characters. Colours are theme tokens, never hex values.

**Custom components.** The SDK lets an app register its own component types, but
`ship validate` accepts only the built-ins for workspaces outside Zynclave. Custom
components are not yet available to outside workspaces. A registered component that acts
on a tap must first call `@Environment(\.shipComponentWillAct)` (iOS) or
`LocalShipComponentWillAct.current` (Android), when it is not null: that closes the popup
and logs the tap, as a built-in button does.

**Theme.** `config/theme.json` overrides the colour tokens `primary`, `accent`,
`surface`, `surfaceVariant`, `onSurface`, `onSurfaceVariant` and `onPrimary`:

```json
{"primary": "#3B6CF6"}
```

A colour resolves as: the release override, then the app's own `color` option, then
the SDK default. An app with its own palette can pass its own `themeTokens` to choose
which names a release may override.

**Cards.** A card's `placement.tabs` names the tabs it appears on: `discover`,
`following`. Your app tells the SDK which tab a list is by asking `cards(tab:)` for it.

**Popups.** A popup is held back on the first launch after install, and shows once per id
per device, at most one per app session. `ShipPopups.resetShownForDebug()` forgets what
was shown; it is for debug screens only.

**Lottie.** Text inside a Lottie file is not translated: OTA strings reach `text`, `button`
and `badge` components only.

## Publish and see it

```sh
ship validate
ship publish --platform ios --channel testflight
ship rollout --platform ios --channel testflight --pct 100
```

Publish to `testflight` first, look at it on a real phone, then repeat for `production`
(`ship promote RELEASE_ID --channel production --rollout 10` copies a release over).
Then open the app and sync: the new content shows without a store release.

## Revert and rollback

- `ship rollback --platform ios --channel production` points the channel back at the
  previous release for all devices.
- `ship revert --platform ios --channel production --yes` is the kill switch: devices
  drop all over-the-air content and go back to what is built into the app.
  `ship rollback` undoes a revert.

## Known differences between iOS and Android

- First launch: Android treats the first launch after an app update as not a first launch,
  so an update that adds the SDK can show a popup at once. iOS counts it as a first launch
  and holds popups back until the next one.
- Integer flags: `ShipFlags.int` reads 32-bit integers on Android and 64-bit on iOS. A
  value outside the 32-bit range is no override on Android.

## Store rules

Ship changes content and configuration only. It never downloads or runs code, and every
component is drawn by the native views already in your app. You remain responsible for
following the App Store and Google Play rules for your app, including what your popups
say and where their buttons lead.

## Security

Report vulnerabilities as described in [SECURITY.md](SECURITY.md). `scripts/check-secrets.sh`
checks tracked files, history and commit messages for things that must not be public.
Pass `--generic-only` if you do not have the maintainers' private denylist file.

## Licence

Apache License 2.0. See [LICENSE](LICENSE).
