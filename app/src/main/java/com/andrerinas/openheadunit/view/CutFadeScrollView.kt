package com.andrerinas.openheadunit.view

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.ScrollView

/**
 * A scrolling column whose entries fade away as they are cut off: each child of its content is as opaque as the share of
 * it that is in view, so a half hidden one is half transparent. With the fading edge set in the layout, the cut itself
 * is soft too.
 */
class CutFadeScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.scrollViewStyle,
) : ScrollView(context, attrs, defStyleAttr) {

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        fadeCutChildren()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        fadeCutChildren()
    }

    private fun fadeCutChildren() {
        val content = getChildAt(0) as? ViewGroup ?: return
        val viewTop = scrollY + paddingTop
        val viewBottom = scrollY + height - paddingBottom
        for (i in 0 until content.childCount) {
            val child = content.getChildAt(i)
            if (child.height <= 0) continue
            val top = content.top + child.top
            val shown = minOf(top + child.height, viewBottom) - maxOf(top, viewTop)
            child.alpha = (shown.toFloat() / child.height).coerceIn(0f, 1f)
        }
    }
}
