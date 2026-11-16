import CoreGraphics
import Foundation
import ImageIO
import Testing
import ZynclaveShip
@testable import ZynclaveShipUI

@Suite struct ShipTextTests {
    private func runtime(language: String, app: [String: String] = [:]) -> ShipRuntime {
        let files = ["strings/hi.json": try! JSONSerialization.data(withJSONObject: ["greet": "नमस्ते {first_name}"]),
                     "strings/en.json": try! JSONSerialization.data(withJSONObject: ["greet": "Hello"])]
        var o = ShipUIOptions(deeplinkScheme: "shipsample")
        o.language = { language }
        o.appString = { app[$0] }
        return ShipRuntime(options: o, content: client(root: scratch(), files: files).content)
    }

    private let greet = SDUI.TextValue(key: "greet", fallback: "Fallback")

    @Test func otaStringUsesAppLanguage() {
        #expect(runtime(language: "hi", app: ["greet": "App"]).text(greet) == "नमस्ते")
        #expect(runtime(language: "en", app: ["greet": "App"]).text(greet) == "Hello")
    }

    @Test func appStringBeatsOtaInAnotherLanguage() {
        // No fr OTA file: no English OTA fallback either.
        #expect(runtime(language: "fr", app: ["greet": "Salut"]).text(greet) == "Salut")
        #expect(runtime(language: "fr").text(greet) == "Fallback")
    }

    @Test func defaultPersonalizeStripsThePlaceholder() {
        let o = ShipUIOptions(deeplinkScheme: "shipsample")
        #expect(o.personalize("Happy Diwali, {first_name}!") == "Happy Diwali!")
    }

    @Test func imageDecodingHonoursExifOrientation() throws {
        let ctx = CGContext(data: nil, width: 20, height: 10, bitsPerComponent: 8, bytesPerRow: 0,
                            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
        ctx.setFillColor(CGColor(red: 1, green: 0, blue: 0, alpha: 1))
        ctx.fill(CGRect(x: 0, y: 0, width: 20, height: 10))
        let url = scratch().appending(path: "rot.jpg")
        let dest = CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil)!
        CGImageDestinationAddImage(dest, ctx.makeImage()!, [kCGImagePropertyOrientation: 6] as CFDictionary)
        #expect(CGImageDestinationFinalize(dest))
        let image = try #require(ShipImage.load(url))
        #expect(image.width == 10 && image.height == 20, "rotated a quarter turn, as UIImage would draw it")
    }
}
