package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.zlinkcarplay.ProjectionModePolicy
import com.andrerinas.openheadunit.utils.Settings.ProjectionAvailability
import com.andrerinas.openheadunit.utils.Settings.ProjectionMode

/**
 * CoD OS home, the behavioural copy of Z-Link (owner, 24.11): three buttons, Android Auto, CarPlay and Connect, and nothing else
 * on the home. Everything the person used to choose lives in the developer section, reached by seven taps on the version label.
 * Pure and Android-free, so it is unit tested. Design: `cod/OHU_COD_UI_DESIGN.md`.
 */
object CodosHomePolicy {

    /**
     * CarPlay in OHU runs through Z-Link's daemon and needs the redirect of its connections (design `PROJECTION_SWITCH_DESIGN.md`
     * sections 11 and 14). The redirect needs root in the CoD build today, and its replacement (an init service of the image, or
     * OHU serving the ports itself) is not proven on a unit. **This is the one switch that turns the CarPlay tile on.** Until it
     * is true the tile is not shown: the home never carries a button that cannot work.
     */
    const val CARPLAY_PATH_READY = false

    data class Tiles(
        val androidAutoVisible: Boolean,
        val carPlayVisible: Boolean,
        val androidAutoSelected: Boolean,
        val carPlaySelected: Boolean
    )

    /**
     * @param carPlayPathReady whether the CarPlay path works on this unit ([CARPLAY_PATH_READY] and Z-Link's side being there)
     */
    fun tiles(availability: ProjectionAvailability, mode: ProjectionMode, carPlayPathReady: Boolean): Tiles {
        val aa = ProjectionModePolicy.allows(availability, ProjectionMode.ANDROID_AUTO)
        val cp = ProjectionModePolicy.allows(availability, ProjectionMode.CARPLAY_ZLINK) && carPlayPathReady
        return Tiles(
            androidAutoVisible = aa,
            carPlayVisible = cp,
            androidAutoSelected = aa && mode == ProjectionMode.ANDROID_AUTO,
            carPlaySelected = cp && mode == ProjectionMode.CARPLAY_ZLINK
        )
    }

    /** Seven taps within [TAP_WINDOW_MS] of each other open the developer section. */
    const val TAPS_TO_OPEN = 7
    const val TAP_WINDOW_MS = 2_000L

    data class Taps(val count: Int, val openDeveloper: Boolean)

    fun nextTaps(count: Int, lastTapAtMs: Long, nowMs: Long): Taps {
        val c = if (count > 0 && nowMs - lastTapAtMs <= TAP_WINDOW_MS) count + 1 else 1
        return Taps(c, c >= TAPS_TO_OPEN)
    }
}
