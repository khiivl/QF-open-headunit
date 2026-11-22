package com.andrerinas.openheadunit.connection.zlinkcarplay

import com.andrerinas.openheadunit.utils.Settings.ProjectionMode

/** What the chosen projection mode allows. Kept free of Android types so it can be tested directly. */
object ProjectionModePolicy {
    fun androidAutoEnabled(mode: ProjectionMode?): Boolean = mode == null || mode == ProjectionMode.ANDROID_AUTO

    fun carPlayEnabled(mode: ProjectionMode?): Boolean = mode == ProjectionMode.CARPLAY_ZLINK

    /**
     * Whether the Android Auto service may act on a start command. In CarPlay mode it handles only the
     * stop command, so a running session can still be torn down and nothing else brings Android Auto up.
     */
    fun androidAutoServiceMayHandle(mode: ProjectionMode?, action: String?, stopAction: String): Boolean =
        androidAutoEnabled(mode) || action == stopAction
}
