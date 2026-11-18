package com.zynclave.ship.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * OTA images and Lottie: a release file wins when it exists and decodes;
 * anything else - no release, no file, a broken file, an image too large to
 * hold - is the bundled resource.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShipAssetsTest {
    @get:Rule val temp = TemporaryFolder()

    private fun content(id: String?, vararg files: Pair<String, ByteArray>) =
        testClient(temp.newFolder(), id, files.toMap()).content

    @Test
    fun `decodes png or jpg from the release, once per release`() {
        val content = content(
            "rel-png",
            "images/ic_coin.png" to image(8, 6),
            "images/banner.jpg" to image(4, 4, Bitmap.CompressFormat.JPEG),
        )
        val coin = OtaImage.bitmap("ic_coin", content)
        assertEquals(8, coin!!.width)
        assertEquals(6, coin.height)
        assertNotNull("jpg is the second choice", OtaImage.bitmap("banner", content))
        assertSame("decoded once per release + name", coin, OtaImage.bitmap("ic_coin", content))
    }

    @Test
    fun `missing, broken or huge images fall back to the bundle`() {
        val content = content(
            "rel-bad",
            "images/broken.png" to "not a png".toByteArray(),
            "images/huge.png" to image(OtaImage.MAX_SIDE + 1, 1),
        )
        assertNull(OtaImage.bitmap("absent", content))
        assertNull(OtaImage.bitmap("broken", content))
        assertNull(OtaImage.bitmap("huge", content))
        assertNull("no release", OtaImage.bitmap("ic_coin", null))
        assertNull("nothing applied", OtaImage.bitmap("ic_coin", content(null)))
    }

    @Test
    fun `a width limit decodes at the smallest power-of-two reduction still that wide`() {
        val wide = temp.newFile("wide.png").apply { writeBytes(image(2000, 1000)) }
        assertEquals(2000, OtaImage.decode(wide)!!.width)
        val small = OtaImage.decode(wide, maxWidth = 500)!!
        assertEquals("2000 / 4 = 500 is still at least 500; / 8 would be under", 500, small.width)
        assertEquals(250, small.height)
        assertEquals("never upscaled", 2000, OtaImage.decode(wide, maxWidth = 4000)!!.width)

        // Tall images are limited by width too: reducing by the longer side would leave it too narrow.
        val tall = temp.newFile("tall.png").apply { writeBytes(image(1000, 2000)) }
        val portrait = OtaImage.decode(tall, maxWidth = 500)!!
        assertEquals(500, portrait.width)
        assertEquals(1000, portrait.height)
    }

    @Test
    fun `lottie comes from the release only when it has that file`() {
        val content = content("rel-lottie", "lottie/hand_touch.json" to "{}".toByteArray())
        assertEquals("hand_touch.json", OtaLottie.file("hand_touch", content)!!.name)
        assertNull(OtaLottie.file("wave", content))
        assertNull(OtaLottie.file("hand_touch", null))
    }

    // ----- EXIF orientation -----

    /** A JPEG [width] x [height], left half red and right half blue, tagged with [orientation]. */
    private fun jpeg(width: Int, height: Int, orientation: Int): File {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint()
        paint.color = Color.RED
        canvas.drawRect(0f, 0f, width / 2f, height.toFloat(), paint)
        paint.color = Color.BLUE
        canvas.drawRect(width / 2f, 0f, width.toFloat(), height.toFloat(), paint)
        val file = temp.newFile()
        file.writeBytes(ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }.toByteArray())
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return file
    }

    private fun androidx.compose.ui.graphics.ImageBitmap.pixel(x: Int, y: Int) = asAndroidBitmap().getPixel(x, y)

    private fun isRed(p: Int) = Color.red(p) > 200 && Color.blue(p) < 60
    private fun isBlue(p: Int) = Color.blue(p) > 200 && Color.red(p) < 60

    @Test
    fun `a rotated photo is drawn upright`() {
        val rotated = OtaImage.decode(jpeg(200, 100, ExifInterface.ORIENTATION_ROTATE_90))!!
        assertEquals("a sideways camera frame is turned upright: 200x100 becomes 100x200", 100, rotated.width)
        assertEquals(200, rotated.height)
        // Rotated 90 clockwise, the left (red) half is now the top half.
        assertTrue(isRed(rotated.pixel(50, 20)))
        assertTrue(isBlue(rotated.pixel(50, 180)))

        val upsideDown = OtaImage.decode(jpeg(200, 100, ExifInterface.ORIENTATION_ROTATE_180))!!
        assertEquals(200, upsideDown.width)
        assertTrue("rotated half a turn, red is on the right", isRed(upsideDown.pixel(180, 50)))
    }

    @Test
    fun `a mirrored photo is flipped, and an untagged one left alone`() {
        val flipped = OtaImage.decode(jpeg(200, 100, ExifInterface.ORIENTATION_FLIP_HORIZONTAL))!!
        assertEquals(200, flipped.width)
        assertTrue(isBlue(flipped.pixel(20, 50)))
        val normal = OtaImage.decode(jpeg(200, 100, ExifInterface.ORIENTATION_NORMAL))!!
        assertTrue(isRed(normal.pixel(20, 50)))
    }

    @Test
    fun `the width limit counts the width as drawn, after the turn`() {
        // 2000 x 1000 turned upright is 1000 wide: reducing to 500 wide halves it once.
        val turned = OtaImage.decode(jpeg(2000, 1000, ExifInterface.ORIENTATION_ROTATE_90), maxWidth = 500)!!
        assertEquals(500, turned.width)
        assertEquals(1000, turned.height)
    }
}
