package com.andrerinas.openheadunit.connection.zlinkcarplay

import com.andrerinas.openheadunit.utils.Settings.ProjectionAvailability
import com.andrerinas.openheadunit.utils.Settings.ProjectionMode

/** What the chosen projection mode allows. Kept free of Android types so it can be tested directly. */
object ProjectionModePolicy {
    /** Whether [mode] may be used under [availability]. */
    fun allows(availability: ProjectionAvailability, mode: ProjectionMode): Boolean = when (availability) {
        ProjectionAvailability.BOTH -> true
        ProjectionAvailability.ANDROID_AUTO_ONLY -> mode == ProjectionMode.ANDROID_AUTO
        ProjectionAvailability.CARPLAY_ONLY -> mode == ProjectionMode.CARPLAY_ZLINK
    }

    /** The mode to run under [availability] when [current] is not allowed: the one it allows, else [current]. */
    fun mustSwitchTo(availability: ProjectionAvailability, current: ProjectionMode): ProjectionMode? = when {
        allows(availability, current) -> null
        availability == ProjectionAvailability.ANDROID_AUTO_ONLY -> ProjectionMode.ANDROID_AUTO
        else -> ProjectionMode.CARPLAY_ZLINK
    }

    fun androidAutoEnabled(mode: ProjectionMode?): Boolean = mode == null || mode == ProjectionMode.ANDROID_AUTO

    fun carPlayEnabled(mode: ProjectionMode?): Boolean = mode == ProjectionMode.CARPLAY_ZLINK

    /**
     * Whether the Android Auto service may act on a start command. In CarPlay mode it handles only the
     * stop command, so a running session can still be torn down and nothing else brings Android Auto up.
     */
    fun androidAutoServiceMayHandle(mode: ProjectionMode?, action: String?, stopAction: String): Boolean =
        androidAutoEnabled(mode) || action == stopAction
}
