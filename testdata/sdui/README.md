# SDUI definitions corpus

Shared by the iOS and Android parser tests, so both platforms accept and reject exactly
the same definitions. Every file in `accept/` must parse; every file in `reject/` must be
refused. A file name starting `card-` is parsed as a card, `popup-` as a popup.

The tests parse with deep-link scheme `shipsample`, one registered custom type `tile`,
and an asset check under which every `images/...` and `lottie/...` path exists except one
whose name contains `missing`.
