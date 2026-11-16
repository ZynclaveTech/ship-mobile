package com.zynclave.ship.ui

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * Server-driven popups that ship INSIDE the app: they show without a Ship
 * release (offline, or for users on a version older than any release), and
 * after a revert. Ship's popups win a shared id. Only popups are embedded.
 *
 * Layout in the app's assets, under the folder `ShipUiOptions.embeddedPopupsAssetDir` names:
 *
 *     <dir>/sdui/popups/<id>.json
 *     <dir>/lottie/<name>.json     <- assets, at their release paths
 *     <dir>/images/<name>.png
 *
 * The renderer reads files from disk, so the folder is copied out of the APK
 * once per app version, into a directory named after that version.
 */
internal class ShipEmbedded private constructor(
    private val root: File,
    private val present: Set<String>,
    val popups: List<Sdui.Popup>,
) {
    /** The unpacked folder, as a release's files. The id says it is not a Ship release. */
    val files = ReleaseFiles("embedded") { asset ->
        if (".." in asset || asset !in present) null else File(root, asset)
    }

    companion object {
        /** Reads the APK and disk: call off the main thread. Null when the app carries no such folder. */
        fun load(context: Context, assetDir: String, scheme: String, customTypes: Set<String>): ShipEmbedded? {
            val base = assetDir.trim('/')
            val names = try {
                list(context, base, "")
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Embedded popups: could not list the bundled files", e)
                return null
            }
            if (names.isEmpty()) return null
            val dir = File(context.noBackupFilesDir, "ZynclaveShipEmbedded/${installStamp(context)}")
            try {
                for (name in names) {
                    val target = File(dir, name)
                    if (target.exists()) continue
                    target.parentFile?.mkdirs()
                    // Written whole, then renamed: a crash never leaves a half file under the real name.
                    val partial = File(target.path + ".part")
                    context.assets.open("$base/$name").use { input ->
                        partial.outputStream().use { input.copyTo(it) }
                    }
                    if (!partial.renameTo(target)) throw IOException("could not place $name")
                }
            } catch (e: IOException) {
                Log.w(LOG_TAG, "Embedded popups: could not unpack the bundled files", e)
                return null
            }
            // Earlier versions' copies are dead weight once this one is in place.
            dir.parentFile?.listFiles()?.filter { it != dir }?.forEach { it.deleteRecursively() }

            val present = names.toSet()
            val popups = mutableListOf<Sdui.Popup>()
            for (name in names.filter { it.startsWith(Sdui.POPUPS_DIR) && it.endsWith(".json") }.sorted()) {
                val data = try {
                    File(dir, name).readText()
                } catch (_: IOException) {
                    continue
                }
                try {
                    val popup = Sdui.parsePopup(data, scheme, customTypes, present::contains)
                    if (popups.any { it.id == popup.id }) continue
                    popups += popup
                } catch (e: Sdui.Invalid) {
                    Log.w(LOG_TAG, "Embedded popup $name skipped: ${e.reason}")
                }
            }
            return ShipEmbedded(dir, present, popups)
        }

        /** Changes with each app version, and each reinstall of the same one (a debug build), so unpacked files never go stale. */
        private fun installStamp(context: Context): String = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.longVersionCode}-${info.lastUpdateTime}"
        } catch (_: PackageManager.NameNotFoundException) {
            "0"
        }

        /** Every file under assets/[root]/[path], as paths relative to [root]. */
        private fun list(context: Context, root: String, path: String): List<String> {
            val full = if (path.isEmpty()) root else "$root/$path"
            val children = context.assets.list(full).orEmpty()
            if (children.isEmpty()) return if (path.isEmpty()) emptyList() else listOf(path)
            return children.flatMap { list(context, root, if (path.isEmpty()) it else "$path/$it") }
        }
    }
}
