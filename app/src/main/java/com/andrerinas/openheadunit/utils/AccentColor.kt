package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

/**
 * One accent for every button, taken from the unit when another app publishes one there: wRight writes its highlight
 * colour to persist.sys.wright.highlight_color as "#RRGGBB". While the property is empty the app keeps its own colors,
 * and [current] is null.
 */
object AccentColor {

    private const val PROPERTY = "persist.sys.wright.highlight_color"
    private val FORMAT = Regex("#[0-9A-Fa-f]{6}")
    private const val NEAR_BLACK = 0xFF14100C.toInt()

    // As on the home screen's main button: by night black text from where it contrasts better than white, by day white
    // for as long as it keeps 3:1, the least for large bold text.
    private const val NIGHT_DARK_TEXT_LUMINANCE = 0.179
    private const val DAY_WHITE_TEXT_LUMINANCE = 0.3

    /** The least contrast for accent-colored text and controls against what is behind them. */
    private const val MIN_CONTRAST = 3.0
    private const val STEP = 0.05f

    @ColorInt
    @Volatile
    var current: Int? = null
        private set

    /** Reads the property again. */
    fun refresh() {
        current = parse(readProperty())
    }

    fun parse(value: String): Int? {
        val text = value.trim()
        return if (FORMAT.matches(text)) Color.parseColor(text) else null
    }

    /** Text and icons on a fill of [fill]: white or near black, so that it never disappears into the fill. */
    @ColorInt
    fun on(@ColorInt fill: Int): Int {
        val night = AppThemeManager.nightNow() == Configuration.UI_MODE_NIGHT_YES
        val limit = if (night) NIGHT_DARK_TEXT_LUMINANCE else DAY_WHITE_TEXT_LUMINANCE
        return if (ColorUtils.calculateLuminance(fill) <= limit) Color.WHITE else NEAR_BLACK
    }

    /**
     * [color] for text, icons and thin controls over [surface]: itself where it stands out enough, otherwise taken
     * toward black or white until it does.
     */
    @ColorInt
    fun readable(@ColorInt color: Int, @ColorInt surface: Int, minContrast: Double = MIN_CONTRAST): Int {
        val behind = ColorUtils.setAlphaComponent(surface, 0xFF)
        val toward = if (ColorUtils.calculateContrast(Color.BLACK, behind) >
            ColorUtils.calculateContrast(Color.WHITE, behind)
        ) Color.BLACK else Color.WHITE
        var result = color
        var share = 0f
        while (ColorUtils.calculateContrast(result, behind) < minContrast && share < 1f) {
            share = (share + STEP).coerceAtMost(1f)
            result = ColorUtils.blendARGB(color, toward, share)
        }
        return result
    }

    /** The accent for text over the screen's surface, or the theme's own primary color while there is none. */
    @ColorInt
    fun primary(context: Context): Int {
        val accent = current ?: return themeColor(context, com.google.android.material.R.attr.colorPrimary)
            ?: ContextCompat.getColor(context, com.andrerinas.openheadunit.R.color.brand_primary)
        return readable(accent, surface(context))
    }

    /** What the screen's content sits on. */
    @ColorInt
    fun surface(context: Context): Int = themeColor(context, com.google.android.material.R.attr.colorSurface)
        ?: ContextCompat.getColor(context, com.andrerinas.openheadunit.R.color.app_background)

    fun themeColor(context: Context, attribute: Int): Int? {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return null
        return when {
            value.resourceId != 0 -> ContextCompat.getColor(context, value.resourceId)
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
            else -> null
        }
    }

    private fun readProperty(): String {
        try {
            val properties = Class.forName("android.os.SystemProperties")
            val value = properties.getMethod("get", String::class.java, String::class.java)
                .invoke(null, PROPERTY, "") as? String
            if (value != null) return value
        } catch (_: Throwable) {
        }
        // The reflective read can be refused on a unit that enforces the hidden-API list; getprop is open to every app.
        return try {
            Runtime.getRuntime().exec(arrayOf("getprop", PROPERTY)).inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            ""
        }
    }
}
