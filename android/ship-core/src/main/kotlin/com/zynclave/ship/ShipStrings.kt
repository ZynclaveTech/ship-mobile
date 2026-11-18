package com.zynclave.ship

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.text.Spanned
import android.util.DisplayMetrics
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/*
 * OTA strings from Zynclave Ship: `strings/<language>.json` in the applied
 * release, a flat JSON object of resource name -> text, overrides
 * `R.string.<name>`.
 *
 * Android reads every string through `Resources` (`getString`,
 * `stringResource`, XML layouts), so the override is a `Resources` wrapper
 * on the activity's base context: install it with [ShipStrings.wrap] from
 * `attachBaseContext`.
 *
 * Never changes:
 *  - a string whose placeholders differ from the bundled one (callers pass
 *    arguments typed for the bundled string: an OTA `%d` where the code passes
 *    a String throws IllegalFormatConversionException), or that Formatter
 *    would refuse (a stray `%`, `%.2d`);
 *  - styled strings (`<b>`, `<u>`): replacing one with plain text would
 *    silently drop its styling;
 *  - plurals and string arrays.
 */
public object ShipStrings {
    /**
     * Wraps [context] so its resources read OTA strings first. [content]
     * is read on every lookup, so it may return null (Ship off, or no client
     * yet) and later start returning content.
     */
    public fun wrap(context: Context, content: () -> ShipContent?): Context = ShipContext(context, content)

    /**
     * The arguments a java.util.Formatter format reads: argument index ->
     * the conversions applied to it (`s`, `d`, `tY`), with Formatter's own
     * indexing - `%s` takes the next ordinary index, `%2$s` names one, `%<s`
     * reuses the previous. Flags, width and precision change layout, not
     * which argument is read or as what. `%%` and `%n` read nothing.
     *
     * Null if any `%` does not start a valid specifier: a stray `%` makes
     * String.format throw, so such a text must never be formatted.
     */
    internal fun formatArgs(format: String): Map<Int, Set<String>>? {
        val args = mutableMapOf<Int, MutableSet<String>>()
        var ordinary = 0
        var last = 0
        var i = 0
        while (true) {
            val at = format.indexOf('%', i)
            if (at < 0) return args
            val m = SPECIFIER.matchAt(format, at) ?: return null
            i = m.range.last + 1
            val conversion = m.groupValues[2]
            if (conversion == "%" || conversion == "n") continue
            val index = when (val given = m.groupValues[1]) {
                "" -> ++ordinary
                "<" -> last.takeIf { it > 0 } ?: return null
                else -> given.dropLast(1).toIntOrNull()?.takeIf { it > 0 } ?: return null
            }
            last = index
            args.getOrPut(index) { mutableSetOf() } += conversion
        }
    }

    // %[index$ | <][flags][width][.precision]conversion, conversions as java.util.Formatter defines them.
    private val SPECIFIER =
        Regex("""%(\d+\$|<)?(?:[-#+ 0,(]*\d*(?:\.\d+)?)([bBhHsScCdoxXeEfgGaA%n]|[tT][HIklMSLNpzZsQBbhAaCYyjmdeRTrDFc])""")

    /**
     * Whether [ota] may stand in for [bundled] wherever the code formats it:
     * the same arguments read the same way, and it actually formats with
     * values of those types (Formatter also refuses combinations like `%.2d`
     * or `%#d`). A bundled text with a stray `%` is never formatted, so an
     * OTA text with one may replace only that.
     */
    internal fun formatsLike(ota: String, bundled: String): Boolean {
        val args = formatArgs(bundled)
        if (formatArgs(ota) != args) return false
        if (args.isNullOrEmpty()) return true
        return verdicts.getOrPut(ota to bundled) {
            val samples = Array(args.keys.max()) { sample(args[it + 1]) }
            try {
                String.format(Locale.ROOT, bundled, *samples)
                String.format(Locale.ROOT, ota, *samples)
                true
            } catch (_: java.util.IllegalFormatException) {
                false
            }
        }
    }

    private val verdicts = ConcurrentHashMap<Pair<String, String>, Boolean>()

    /** A value each conversion in [conversions] accepts; an argument no conversion reads gets a String. */
    private fun sample(conversions: Set<String>?): Any = when (conversions?.firstOrNull()?.first()) {
        'd', 'o', 'x', 'X' -> 0
        'e', 'E', 'f', 'g', 'G', 'a', 'A' -> 0.0
        'c', 'C' -> 'x'
        't', 'T' -> 0L
        'b', 'B' -> false
        else -> "x"
    }

    /**
     * The release's language code for [language] from `Locale.getLanguage`.
     * Android still reports the legacy ISO codes for Indonesian, Hebrew and
     * Yiddish; releases (like the app's own resources and iOS) use the current ones.
     */
    public fun releaseLanguage(language: String): String = when (language) {
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        else -> language
    }

    /** The OTA text for [name] in [language], if it may replace [bundled]. */
    internal fun override(content: ShipContent?, name: String, language: String, bundled: CharSequence): String? {
        val ota = content?.string(name, language) ?: return null
        if (bundled is Spanned && bundled.getSpans(0, bundled.length, Any::class.java).isNotEmpty()) return null
        if (!formatsLike(ota, bundled.toString())) return null
        return ota
    }
}

/** Hands out [ShipResources]; re-wraps contexts made from it so a configuration context keeps the override. */
internal class ShipContext(base: Context, private val content: () -> ShipContent?) : ContextWrapper(base) {
    private var wrapped: ShipResources? = null

    override fun getResources(): Resources {
        val res = baseContext.resources
        wrapped?.takeIf { it.base === res }?.let { return it }
        return ShipResources(res, content).also { wrapped = it }
    }

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
        ShipContext(super.createConfigurationContext(overrideConfiguration), content)
}

/**
 * Same assets, metrics and configuration as [base]; only string text lookups
 * differ.
 *
 * It owns its own ResourcesImpl, which the framework does not update on a
 * configuration change. That is safe only because every activity that
 * installs it handles its configuration changes itself and pushes the new
 * configuration onto `activity.resources` - this object, which the
 * activity's ContextThemeWrapper caches. An activity that installs it must
 * do the same. `Resources.getString(id)` and `getString(id, args)` both go
 * through [getText], so overriding it covers them, `Context.getString` and
 * Compose `stringResource`.
 */
@Suppress("DEPRECATION") // The public Resources constructor is the only way to wrap one.
internal class ShipResources(
    val base: Resources,
    private val content: () -> ShipContent?,
) : Resources(base.assets, base.displayMetrics, base.configuration) {

    /**
     * Takes the screen metrics from [base], whatever the caller passes.
     * A host that pushes configuration changes hands back `resources.displayMetrics` -
     * this object's own, which nothing else updates - so after a rotation the
     * activity handles itself, widthPixels and heightPixels would stay those
     * of the old orientation. [base] is the framework's, updated by then.
     */
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun updateConfiguration(config: Configuration?, metrics: DisplayMetrics?) {
        // Null while the superclass constructor runs, if it ever calls this.
        @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
        val fresh = if (base != null) base.displayMetrics else metrics
        super.updateConfiguration(config, fresh)
    }

    override fun getText(id: Int): CharSequence {
        val bundled = super.getText(id)
        return lookup(id, bundled) ?: bundled
    }

    override fun getText(id: Int, def: CharSequence?): CharSequence? {
        val bundled = super.getText(id, def) ?: return null
        return lookup(id, bundled) ?: bundled
    }

    private fun lookup(id: Int, bundled: CharSequence): String? {
        val c = content() ?: return null
        val language = configuration.locales[0]?.language?.let(ShipStrings::releaseLanguage) ?: return null
        // Every string read in the app passes here: skip the name lookup
        // unless this release has strings for this language at all.
        if (c.file("strings/$language.json") == null) return null
        val name = try {
            getResourceEntryName(id)
        } catch (_: NotFoundException) {
            return null
        }
        return ShipStrings.override(c, name, language, bundled)
    }
}
