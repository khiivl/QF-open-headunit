package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckedTextView
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.widget.CompoundButtonCompat
import com.andrerinas.openheadunit.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout

/**
 * Puts [AccentColor] on the widgets that take their color from the theme or from a layout, which cannot be changed
 * there at run time. Screens that paint themselves from a palette take the accent in the palette instead.
 */
object AccentTint {

    private const val TRACK_ALPHA = 0x80

    private val checked = intArrayOf(android.R.attr.state_checked)
    private val disabled = intArrayOf(-android.R.attr.state_enabled)
    private val otherwise = intArrayOf()

    /**
     * Inflates as the activity would and tints what comes out. Has to be set on the activity's inflater before
     * AppCompat sets its own, that is before `super.onCreate`; dialogs and lists inflate through it too.
     */
    class Inflater(private val activity: AppCompatActivity) : LayoutInflater.Factory2 {
        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
            var view = activity.delegate.createView(parent, name, context, attrs)
            // Views named in full are left to the inflater by AppCompat. Fragment containers stay with it.
            if (view == null && '.' in name && !name.startsWith("androidx.fragment") &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            ) {
                view = try {
                    LayoutInflater.from(context).createView(context, name, null, attrs)
                } catch (_: Exception) {
                    null
                }
            }
            view?.let { apply(it) }
            return view
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? =
            onCreateView(null, name, context, attrs)
    }

    fun apply(view: View) {
        val accent = AccentColor.current ?: return
        val context = view.context
        val ink = AccentColor.primary(context)
        // The colors that stand for "the accent" in layouts and themes.
        val own = setOfNotNull(
            ContextCompat.getColor(context, R.color.brand_primary),
            ContextCompat.getColor(context, R.color.md_primary),
            ContextCompat.getColor(context, R.color.home_hint_button),
            AccentColor.themeColor(context, com.google.android.material.R.attr.colorPrimary),
        )

        fun isOwn(colors: ColorStateList?) = colors != null && colors.defaultColor in own

        fun filled(before: ColorStateList): ColorStateList {
            val off = before.getColorForState(disabled, before.defaultColor)
            val offColor = if (off == before.defaultColor) ColorUtils.setAlphaComponent(accent, TRACK_ALPHA) else off
            return ColorStateList(arrayOf(disabled, otherwise), intArrayOf(offColor, accent))
        }

        fun whenChecked(@ColorInt on: Int, before: ColorStateList?, @ColorInt fallback: Int) = ColorStateList(
            arrayOf(checked, otherwise),
            intArrayOf(on, before?.getColorForState(otherwise, fallback) ?: fallback)
        )

        // A fill of the accent, whether it is a tint or a shape of its own.
        var onFill = false
        val tint = ViewCompat.getBackgroundTintList(view)
        if (tint != null && isOwn(tint)) {
            ViewCompat.setBackgroundTintList(view, filled(tint))
            onFill = true
        }
        val shape = view.background as? GradientDrawable
        if (shape != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isOwn(shape.color)) {
            (shape.mutate() as GradientDrawable).setColor(accent)
            onFill = true
        }

        when (view) {
            is SwitchCompat -> {
                view.thumbTintList = whenChecked(ink, view.thumbTintList, Color.LTGRAY)
                view.trackTintList = whenChecked(
                    ColorUtils.setAlphaComponent(ink, TRACK_ALPHA), view.trackTintList, Color.GRAY
                )
            }
            is CompoundButton -> CompoundButtonCompat.setButtonTintList(
                view, whenChecked(ink, CompoundButtonCompat.getButtonTintList(view), Color.GRAY)
            )
            is MaterialButton -> {
                if (onFill) {
                    view.setTextColor(AccentColor.on(accent))
                    view.iconTint = ColorStateList.valueOf(AccentColor.on(accent))
                } else {
                    if (isOwn(view.textColors)) view.setTextColor(ink)
                    if (isOwn(view.iconTint)) view.iconTint = ColorStateList.valueOf(ink)
                    if (isOwn(view.strokeColor)) view.strokeColor = ColorStateList.valueOf(ink)
                }
            }
            is Slider -> {
                view.thumbTintList = ColorStateList.valueOf(ink)
                view.trackActiveTintList = ColorStateList.valueOf(ink)
                view.haloTintList = ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, TRACK_ALPHA))
            }
            is SeekBar -> {
                view.thumbTintList = ColorStateList.valueOf(ink)
                view.progressTintList = ColorStateList.valueOf(ink)
            }
            is ProgressBar -> {
                view.indeterminateTintList = ColorStateList.valueOf(ink)
                view.progressTintList = ColorStateList.valueOf(ink)
            }
            is TextInputLayout -> {
                view.boxStrokeColor = ink
                view.hintTextColor = ColorStateList.valueOf(ink)
            }
            is CheckedTextView -> view.checkMarkTintList = whenChecked(ink, view.checkMarkTintList, Color.GRAY)
            is TextView -> {
                if (onFill) {
                    view.setTextColor(AccentColor.on(accent))
                } else if (isOwn(view.textColors)) {
                    view.setTextColor(ink)
                }
                if (isOwn(view.linkTextColors)) view.setLinkTextColor(ink)
            }
            is ImageView -> if (isOwn(view.imageTintList)) view.imageTintList = ColorStateList.valueOf(ink)
        }
    }

    /**
     * The splash logo with its blues turned into [accent]: each keeps its place between black, the logo's main blue and
     * white, which becomes its place between black, the accent and white. White parts stay white.
     */
    fun logo(source: Bitmap, @ColorInt accent: Int): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        // The disc just below its top edge is the main blue.
        val main = FloatArray(3)
        ColorUtils.colorToHSL(source.getPixel(width / 2, height / 12), main)
        val target = FloatArray(3)
        // A very pale accent would swallow the logo's white parts.
        ColorUtils.colorToHSL(AccentColor.readable(accent, Color.WHITE, minContrast = 1.8), target)

        val color = FloatArray(3)
        val result = FloatArray(3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            ColorUtils.colorToHSL(pixel, color)
            if (Color.alpha(pixel) == 0 || color[0] !in 180f..250f || color[1] < 0.1f) continue
            val light = color[2]
            result[0] = target[0]
            result[1] = (target[1] * color[1] / main[1].coerceAtLeast(0.1f)).coerceIn(0f, 1f)
            result[2] = if (light <= main[2]) {
                light * target[2] / main[2].coerceAtLeast(0.01f)
            } else {
                1f - (1f - light) * (1f - target[2]) / (1f - main[2]).coerceAtLeast(0.01f)
            }.coerceIn(0f, 1f)
            pixels[i] = ColorUtils.setAlphaComponent(ColorUtils.HSLToColor(result), Color.alpha(pixel))
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).also { it.density = source.density }
    }
}
