import CoreGraphics
import Testing
@testable import ZynclaveShipUI

/// A Lottie animation is fitted from its own proportions into the full width
/// by `height`, never drawn into a square.
@Suite struct LottieFitTests {
    @Test func aWideLottieFillsTheWidthNotASquare() {
        // 6:1 animation in a 300 x 150 box: width-limited, the full 300 wide.
        let wide = SDUILottie.fitted(CGSize(width: 600, height: 100), in: CGSize(width: 300, height: 150))
        #expect(abs(wide.width - 300) < 0.01)
        #expect(abs(wide.height - 50) < 0.01)
    }

    @Test func aNearSquareLottieIsHeightLimited() {
        let square = SDUILottie.fitted(CGSize(width: 266, height: 231), in: CGSize(width: 300, height: 150))
        #expect(abs(square.height - 150) < 0.01)
        #expect(abs(square.width - 266.0 * 150 / 231) < 0.01)
    }

    @Test func degenerateSizesFitNothing() {
        #expect(SDUILottie.fitted(.zero, in: CGSize(width: 300, height: 150)) == .zero)
        #expect(SDUILottie.fitted(CGSize(width: 10, height: 10), in: .zero) == .zero)
    }
}
