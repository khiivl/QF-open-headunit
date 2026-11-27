package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R

/**
 * wDSP Pro's Toaster in its Classic look: a rounded card in the app's day or night colors, low on the screen, one at a
 * time. Call it through [ToastUtils], which decides whether a message is shown at all.
 */
object Toaster {

    private val main = Handler(Looper.getMainLooper())
    private var toast: Toast? = null

    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val app = context.applicationContext
        main.post {
            toast?.cancel()
            val themed = dayOrNight(app)
            val density = app.resources.displayMetrics.density
            fun px(dp: Float) = (dp * density).toInt()
            val card = GradientDrawable().apply {
                setColor(ContextCompat.getColor(themed, R.color.toaster_background))
                cornerRadius = 14f * density
                setStroke(maxOf(1, px(1.2f)), ContextCompat.getColor(themed, R.color.toaster_stroke))
            }
            val text = TextView(app).apply {
                this.text = message
                setTextColor(ContextCompat.getColor(themed, R.color.toaster_text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, Typeface.BOLD)
                setPadding(px(20f), px(12f), px(20f), px(12f))
                gravity = Gravity.CENTER
                background = card
            }
            @Suppress("DEPRECATION")
            toast = Toast(app).also {
                it.duration = duration
                it.view = text
                it.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, px(48f))
                it.show()
            }
        }
    }

    /** Resources in the day or night the app's screens wear, which the application's own may not be in. */
    private fun dayOrNight(app: Context): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1) return app
        val config = Configuration(app.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or AppThemeManager.nightNow()
        return app.createConfigurationContext(config)
    }
}
