import Lottie
import SwiftUI

/// A release Lottie animation. Respects Reduce Motion (parks on the first
/// frame). Decoded per view without Lottie's path-keyed cache: every release
/// lives at the same path, so the cache would keep serving the first
/// release's animation for the rest of the session.
struct SDUILottie: View {
    let asset: String
    let height: CGFloat
    let loop: Bool
    @Environment(\.sduiAssetURL) private var assetURL
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        if let url = assetURL(asset),
           let animation = LottieAnimation.filepath(url.path, animationCache: nil) {
            // Fitted from the animation's own size into the full width x
            // `height`. `.scaledToFit()` alone used LottieView's ideal size and
            // drew every animation into a square as tall as `height`, so a
            // wide one came out tiny.
            GeometryReader { geo in
                let size = Self.fitted(animation.size, in: geo.size)
                LottieView(animation: animation)
                    .playbackMode(reduceMotion
                        ? .paused(at: .progress(0))
                        : .playing(.fromProgress(0, toProgress: 1, loopMode: loop ? .loop : .playOnce)))
                    .resizable()
                    .frame(width: size.width, height: size.height)
                    .frame(width: geo.size.width, height: geo.size.height)
            }
            .frame(maxWidth: .infinity)
            .frame(height: height)
            .accessibilityHidden(true)
        }
    }

    /// The largest size with the animation's proportions that fits `box`.
    nonisolated static func fitted(_ animation: CGSize, in box: CGSize) -> CGSize {
        guard animation.width > 0, animation.height > 0, box.width > 0, box.height > 0 else { return .zero }
        let scale = min(box.width / animation.width, box.height / animation.height)
        return CGSize(width: animation.width * scale, height: animation.height * scale)
    }
}
