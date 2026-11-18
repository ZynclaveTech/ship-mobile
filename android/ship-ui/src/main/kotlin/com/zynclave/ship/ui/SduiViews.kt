package com.zynclave.ship.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Draws a parsed definition's node tree. Everything app-specific (strings,
 * colours, deep links, the app's own components) comes from the ShipUiOptions
 * in LocalShipRuntime.
 */

internal object SduiActions {
    fun perform(context: Context, options: ShipUiOptions, action: Sdui.Action, dismiss: () -> Unit) {
        when (action) {
            // Through the app's own routing, so only routes it already supports do anything.
            is Sdui.Action.Deeplink -> options.openDeeplink(Uri.parse(action.url))
            is Sdui.Action.Web -> openWeb(context, action.url)
            Sdui.Action.Dismiss -> dismiss()
        }
    }

    private fun openWeb(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        // Not an Activity (an application context, say): needs a task of its own.
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // No browser: nothing to open.
        }
    }
}

/**
 * How a node behaves in a horizontal stack, as views do in a SwiftUI HStack.
 * [Fill]: takes the full width it is given (vertical stack, image, Lottie,
 * filled button), so it shares what fixed siblings leave. [Shrink]: takes
 * what it needs up to that share (text, which wraps to any width). [None]:
 * its own size. Unweighted, a Fill or Shrink node measured first would take
 * the whole row and leave later siblings nothing.
 */
internal enum class RowFlex { None, Shrink, Fill }

internal val Sdui.Node.rowFlex: RowFlex
    get() = when (this) {
        // A horizontal stack is as flexible as its most flexible child: one
        // holding an image fills, one holding only text and badges shrinks.
        is Sdui.Node.Stack ->
            if (axis == Sdui.Node.Axis.Vertical) RowFlex.Fill else children.maxOf { it.rowFlex }
        is Sdui.Node.Image, is Sdui.Node.Lottie -> RowFlex.Fill
        is Sdui.Node.Button -> if (style != "text") RowFlex.Fill else RowFlex.None
        is Sdui.Node.Text -> RowFlex.Shrink
        else -> RowFlex.None
    }

@Composable
internal fun SduiNode(node: Sdui.Node, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val runtime = LocalShipRuntime.current
    val actionWillRun = LocalShipComponentWillAct.current
    when (node) {
        is Sdui.Node.Stack -> {
            val background = node.background?.let { tokenColor(it) }
            val shape = RoundedCornerShape(node.cornerRadius.dp)
            val base = modifier.then(if (node.axis == Sdui.Node.Axis.Vertical) Modifier.fillMaxWidth() else Modifier)
                .then(if (background != null) Modifier.clip(shape).background(background) else Modifier)
                .padding(node.padding.dp)
            if (node.axis == Sdui.Node.Axis.Vertical) {
                Column(base, verticalArrangement = Arrangement.spacedBy(node.spacing.dp)) {
                    node.children.forEach { SduiNode(it, onDismiss) }
                }
            } else {
                Row(
                    base,
                    horizontalArrangement = Arrangement.spacedBy(node.spacing.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    node.children.forEach { child ->
                        val share = when (child.rowFlex) {
                            RowFlex.Fill -> Modifier.weight(1f)
                            // Measured after the fixed siblings, taking only what it needs of its share.
                            RowFlex.Shrink -> Modifier.weight(1f, fill = false)
                            RowFlex.None -> Modifier
                        }
                        SduiNode(child, onDismiss, share)
                    }
                }
            }
        }
        is Sdui.Node.Text -> Text(
            text = runtime.text(node.value),
            style = textStyle(node.style),
            color = tokenColor(node.color ?: "onSurface"),
            maxLines = node.maxLines ?: Int.MAX_VALUE,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        is Sdui.Node.Image -> {
            val image = releaseImage(node.asset, runtime.files) ?: return
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = modifier
                    .fillMaxWidth()
                    .aspectRatio(node.aspect ?: (image.width.toFloat() / image.height.coerceAtLeast(1)))
                    .clip(RoundedCornerShape(node.cornerRadius.dp)),
            )
        }
        is Sdui.Node.Lottie -> SduiLottie(node, modifier)
        is Sdui.Node.Button -> {
            val primary = tokenColor("primary")
            val (fill, ink) = when (node.style) {
                "primary" -> primary to tokenColor("onPrimary")
                "secondary" -> tokenColor("surfaceVariant") to primary
                else -> Color.Transparent to primary
            }
            val text = node.style == "text"
            Box(
                modifier.then(if (text) Modifier else Modifier.fillMaxWidth())
                    .clip(CircleShape)
                    .background(fill)
                    .clickable(role = Role.Button) {
                        actionWillRun?.invoke()
                        SduiActions.perform(context, runtime.options, node.action, onDismiss)
                    }
                    .padding(vertical = if (text) 0.dp else 10.dp, horizontal = if (text) 0.dp else 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    runtime.text(node.label),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = ink,
                )
            }
        }
        is Sdui.Node.Badge -> Text(
            runtime.text(node.text),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = tokenColor("onPrimary"),
            modifier = modifier
                .clip(CircleShape)
                .background(tokenColor(node.color ?: "primary"))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        is Sdui.Node.Spacer -> Spacer(modifier.height(node.height.dp))
        // The app's own component: drawn from its fields, or nothing if it registered no such type.
        is Sdui.Node.Custom -> Box(modifier) { runtime.options.components[node.type]?.invoke(node) }
    }
}

@Composable
private fun textStyle(style: String) = when (style) {
    "title" -> MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
    "headline" -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
    "caption" -> MaterialTheme.typography.bodySmall
    else -> MaterialTheme.typography.bodyMedium
}

/** The app's colour for [token], else the release's theme, else a default (Material theme colours for surfaces and text). */
@Composable
internal fun tokenColor(token: String): Color {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    LocalShipRuntime.current.color(token, dark)?.let { return it }
    return when (token) {
        "primary" -> if (dark) Color(0xFF8B7CFF) else Color(0xFF5B4BDB)
        "accent" -> if (dark) Color(0xFFFF9A3D) else Color(0xFFE86F00)
        "surface" -> scheme.surface
        "surfaceVariant" -> scheme.surfaceVariant
        "onPrimary" -> Color.White
        "onSurfaceVariant" -> scheme.onSurfaceVariant
        else -> scheme.onSurface
    }
}

/** A release image, decoded off the main thread with the same size cap as OTA images; null until ready or if it fails. */
@Composable
internal fun releaseImage(
    asset: String,
    files: ReleaseFiles?,
    // A card fills the feed's width: decoding wider than the window is wasted memory.
    // The window's measured size, not resource metrics, which a rotation the activity
    // handles itself does not refresh.
    maxWidth: Int = LocalWindowInfo.current.containerSize.width.takeIf { it > 0 }
        ?: LocalResources.current.displayMetrics.widthPixels, // before the first layout
): ImageBitmap? {
    val image = "${files?.releaseId}|$asset"
    // The width is in the key, so a wider window decodes again...
    val key = "$image|$maxWidth"
    val cached = remember(key) { files?.let { SduiImages.cached(it, asset, maxWidth) } }
    // ...but the value is tagged with the IMAGE (release + asset), not the width:
    // produceState keeps its state across key changes, so while a wider decode
    // runs the narrower one stays on screen (a card must not collapse and pop
    // back), and a different release's image is never shown.
    val loaded by produceState<Pair<String, ImageBitmap?>?>(null, key) {
        if (cached == null && files != null) {
            value = image to withContext(Dispatchers.IO) { SduiImages.load(files, asset, maxWidth) }
        }
    }
    return cached ?: loaded?.takeIf { it.first == image }?.second
}

/**
 * Card images, decoded once per release and asset (scrolling a card out of
 * the feed and back must not decode it again) at screen width. A decode is
 * reused for any width up to the one it was made for; a wider screen (a
 * rotation) decodes it again. Only the current release's images are kept.
 */
internal object SduiImages {
    private class Entry(val release: String, val image: ImageBitmap?, val forWidth: Int)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    /** Tests only: how many images are held. */
    internal val size: Int get() = cache.size

    fun cached(files: ReleaseFiles, asset: String, maxWidth: Int): ImageBitmap? {
        val release = files.releaseId ?: return null
        return cache[asset]?.takeIf { it.release == release && it.forWidth >= maxWidth }?.image
    }

    fun load(files: ReleaseFiles, asset: String, maxWidth: Int): ImageBitmap? {
        val release = files.releaseId ?: return null
        cache[asset]?.takeIf { it.release == release && it.forWidth >= maxWidth }?.let { return it.image }
        val image = files.file(asset)?.let { OtaImage.decode(it, maxWidth) }
        cache.values.removeIf { it.release != release }
        cache[asset] = Entry(release, image, maxWidth)
        return image
    }
}

/** A release Lottie. With animations off (developer option or accessibility) it rests on its first frame. */
@Composable
private fun SduiLottie(node: Sdui.Node.Lottie, modifier: Modifier) {
    val file = LocalShipRuntime.current.files?.file(node.asset) ?: return
    // Each release lives in its own directory, so Lottie's path-keyed cache never mixes releases.
    val composition by rememberLottieComposition(LottieCompositionSpec.File(file.path))
    val context = LocalContext.current
    val animationsOff = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val size = modifier.fillMaxWidth().height(node.height.dp)
    if (animationsOff) {
        LottieAnimation(composition, progress = { 0f }, modifier = size)
    } else {
        LottieAnimation(
            composition,
            iterations = if (node.loop) LottieConstants.IterateForever else 1,
            modifier = size,
        )
    }
}
