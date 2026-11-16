// swift-tools-version: 6.0
//
// Zynclave Ship OTA client: fetches signed release manifests, verifies them
// against pinned Ed25519 keys, downloads content-addressed assets and swaps
// them in atomically. Content and config only; never code.

import PackageDescription

let package = Package(
    name: "ZynclaveShip",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "ZynclaveShip", targets: ["ZynclaveShip"]),
        .library(name: "ZynclaveShipUI", targets: ["ZynclaveShipUI"]),
    ],
    targets: [
        .target(name: "ZynclaveShip", path: "ios/Sources/ZynclaveShip"),
        .testTarget(name: "ZynclaveShipTests", dependencies: ["ZynclaveShip"], path: "ios/Tests/ZynclaveShipTests"),
        .target(name: "ZynclaveShipUI", dependencies: ["ZynclaveShip"], path: "ios/Sources/ZynclaveShipUI"),
        .testTarget(name: "ZynclaveShipUITests", dependencies: ["ZynclaveShipUI"], path: "ios/Tests/ZynclaveShipUITests"),
    ]
)
