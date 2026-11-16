package com.zynclave.ship.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.annotation.DrawableRes
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.exifinterface.media.ExifInterface
import com.airbnb.lottie.compose.LottieCompositionResult
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.zynclave.ship.ShipContent
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/*
 * Images and Lottie animations a Zynclave Ship release can replace. Every
 * accessor falls back to the bundled resource: no release, no file for this
 * name, or a file that fails to decode all mean exactly what the app ships.
 *
 * Release layout:
 *   images/<drawable name>.png|.jpg   e.g. images/ic_coin.png
 *   lottie/<name>.json                e.g. lottie/hand_touch.json
 */

/**
 * Where a release's files are: the applied Ship release, or the folder a
 * built-in popup was unpacked to. [releaseId] says which release, so a cache
 * never serves one release's file for another's.
 */
internal class ReleaseFiles(val releaseId: String?, private val lookup: (String) -> File?) {
    fun file(asset: String): File? = lookup(asset)
}

internal fun ShipContent.files() = ReleaseFiles(state?.releaseId) { file(it) }

/** Drop-in for `painterResource(id)`: the release's `images/<name of id>.png|.jpg` if it has one. */
@Composable
internal fun otaPainterResource(@DrawableRes id: Int, content: ShipContent?): Painter {
    val name = LocalResources.current.getResourceEntryName(id)
    val ota = OtaImage.bitmap(name, content)
    return if (ota != null) remember(ota) { BitmapPainter(ota) } else painterResource(id)
}

internal object OtaImage {
    /** Larger than this on either side is refused: a release must not be able to exhaust memory. */
    internal const val MAX_SIDE = 4096

    private class Box(val image: ImageBitmap?)

    private val cache = ConcurrentHashMap<String, Box>()

    /** Decoded once per release + name; a file that fails to decode, or is too large, is a miss. */
    internal fun bitmap(name: String, content: ShipContent?): ImageBitmap? {
        val c = content ?: return null
        val release = c.state?.releaseId ?: return null
        val key = "$release|$name"
        cache[key]?.let { return it.image }
        val file = sequenceOf("png", "jpg").mapNotNull { c.file("images/$name.$it") }.firstOrNull()
        val box = Box(file?.let(::decode))
        // Only the current release's images are kept.
        cache.keys.removeIf { !it.startsWith("$release|") }
        return (cache.putIfAbsent(key, box) ?: box).image
    }

    /**
     * Decodes [file], refusing anything over [MAX_SIDE] on a side, and drawn
     * upright: a photo's EXIF orientation (a camera held sideways, a mirrored
     * selfie) is applied. With [maxWidth], it decodes at the largest
     * power-of-two reduction whose WIDTH, as drawn, is still at least
     * [maxWidth] - sharp when drawn that wide, never the full-resolution
     * bitmap (a 4096 px square is 64 MB). Width, not the longer side: a card
     * image is as wide as the card, so a tall image reduced by its height
     * would be stretched, and blurred, to fill it.
     */
    internal fun decode(file: File, maxWidth: Int = Int.MAX_VALUE): ImageBitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth !in 1..MAX_SIDE || bounds.outHeight !in 1..MAX_SIDE) return null
        val orientation = orientationOf(file)
        // A quarter turn swaps width and height: the width as drawn is the stored height.
        val drawnWidth = if (orientation.swapsAxes) bounds.outHeight else bounds.outWidth
        var sample = 1
        while (drawnWidth / (sample * 2) >= maxWidth) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        return upright(decoded, orientation).asImageBitmap()
    }

    private val Int.swapsAxes: Boolean
        get() = this == ExifInterface.ORIENTATION_TRANSPOSE || this == ExifInterface.ORIENTATION_ROTATE_90 ||
            this == ExifInterface.ORIENTATION_TRANSVERSE || this == ExifInterface.ORIENTATION_ROTATE_270

    private fun orientationOf(file: File): Int = try {
        ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}

internal object OtaLottie {
    /** The release's `lottie/<name>.json`, if it has one. */
    internal fun file(name: String, content: ShipContent?): File? = content?.file("lottie/$name.json")
}

/**
 * Drop-in for `rememberLottieComposition(LottieCompositionSpec.RawRes(fallback))`:
 * the release's `lottie/<name>.json` if it has one that parses, else the
 * bundled raw resource. Each release lives in its own directory, so Lottie's
 * path-keyed cache never serves one release's animation for another.
 */
@Composable
internal fun rememberOtaLottieComposition(name: String, @RawRes fallback: Int, content: ShipContent?): LottieCompositionResult {
    val file = OtaLottie.file(name, content)
    if (file != null) {
        val ota = rememberLottieComposition(LottieCompositionSpec.File(file.path))
        if (!ota.isFailure) return ota
    }
    return rememberLottieComposition(LottieCompositionSpec.RawRes(fallback))
}
