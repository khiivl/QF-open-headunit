package com.andrerinas.openheadunit.main

import android.content.Intent

/**
 * Whether a start of the main screen is somebody asking for the projection: the app's icon, a key of the unit set to
 * open the app, or a navigation request.
 *
 * Not Home, which asks for the home screen; not the app's own starts, which name no action or carry a launch source;
 * and not a task brought back from Recents, which repeats the intent that first made it.
 */
object ProjectionLaunchPolicy {

    private const val ACTION_NAVIGATE = "android.intent.action.NAVIGATE"

    fun asksForProjection(action: String?, isHome: Boolean, hasLaunchSource: Boolean, fromHistory: Boolean): Boolean =
        !isHome && !hasLaunchSource && !fromHistory && (action == Intent.ACTION_MAIN || action == ACTION_NAVIGATE)
}
