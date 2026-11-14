package com.andrerinas.openheadunit.thirdparty

import com.andrerinas.openheadunit.aap.AapVersionNegotiation

/**
 * What the head unit announces when the user asks for the full navigation data (the "Full navigation data" switch on the
 * forwarding screen). Pure logic, no Android types.
 *
 * The phone sends one of two navigation formats per session, never both:
 *  - legacy: NEXTTURNDETAILS (0x8004) and NEXTTURNDISTANCEANDTIME (0x8005): one icon code, a road name, a distance;
 *  - current: INSTRUMENT_CLUSTER_NAVIGATION_STATE (0x8006) and ..._CURRENT_POSITION (0x8007): the full step with the
 *    maneuver type, lanes, cue text, destination, distances and ETA.
 *
 * Which one it picks follows the protocol version the head unit asks for. Zlink 6.0.38 asks for 1.4 together with a
 * 500 ms navigation service and receives only the current format (verified in recordings of thousands of messages, not
 * one legacy message among them); this app asks for 1.2 and receives only the legacy format. Everything else in the
 * navigation service is identical. With the switch on, this app announces what Zlink announces; with it off, nothing
 * changes.
 */
object FullNavigationProfile {

    /** the minor version Zlink 6.0.38 asks for when it announces the navigation service */
    const val FULL_NAVIGATION_MINOR = 4

    /** the release a Google Desktop Head Unit reports, as Zlink announces it */
    const val DHU_SOFTWARE_BUILD = "2022-03-30-438482292"
    const val DHU_SOFTWARE_VERSION = "2.0-linux"

    private const val DEFAULT_SOFTWARE_BUILD = "1"
    private const val DEFAULT_SOFTWARE_VERSION = "0.1.0"
    private const val DEFAULT_NAVIGATION_INTERVAL_MS = 1000
    private const val FULL_NAVIGATION_INTERVAL_MS = 500

    fun versionMinor(full: Boolean): Int = if (full) FULL_NAVIGATION_MINOR else AapVersionNegotiation.ANNOUNCED_MINOR

    fun navigationIntervalMs(full: Boolean): Int = if (full) FULL_NAVIGATION_INTERVAL_MS else DEFAULT_NAVIGATION_INTERVAL_MS

    fun softwareBuild(full: Boolean): String = if (full) DHU_SOFTWARE_BUILD else DEFAULT_SOFTWARE_BUILD

    fun softwareVersion(full: Boolean): String = if (full) DHU_SOFTWARE_VERSION else DEFAULT_SOFTWARE_VERSION
}
