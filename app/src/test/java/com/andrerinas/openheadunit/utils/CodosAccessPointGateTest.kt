package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CoD OS: the access point and the Wi-Fi client belong to the system and Z-Link (head lead, owner 27.11). This app never
 * switches the AP on, never takes one down and never turns the Wi-Fi client off. The gates are source lines, so a merge
 * of upstream code that moves or drops one fails here: in each function the `BuildConfig.CODOS` check has to come
 * before the first call that touches the radio. See cod/ROOT_TO_SYSTEM.md and contract section 10.
 */
class CodosAccessPointGateTest {

    private val root = File("src/main/java/com/andrerinas/openheadunit")

    private fun gateBefore(file: String, header: String, firstRadioCall: String) {
        val text = File(root, file).readText().replace("\r\n", "\n")
        val h = text.indexOf(header)
        assertTrue("$file: $header not found", h >= 0)
        val gate = text.indexOf("BuildConfig.CODOS", h)
        val call = text.indexOf(firstRadioCall, h)
        assertTrue("$file: no CODOS gate in $header", gate >= 0)
        assertTrue("$file: $firstRadioCall not found in $header", call >= 0)
        assertTrue("$file: the CODOS gate in $header must come before $firstRadioCall", gate < call)
    }

    @Test fun hotspotManagerNeverSwitchesTheApOrTheClientInCodos() {
        gateBefore("utils/HotspotManager.kt", "fun setHotspotEnabled(", "wm.isWifiEnabled = false")
        gateBefore("utils/HotspotManager.kt", "fun restart(", "setHotspotEnabled(context, false)")
        gateBefore("utils/HotspotManager.kt", "fun disableAndAwaitDown(", "setHotspotEnabled(context, false)")
    }

    @Test fun processStartAsksForNoHotspotInCodos() {
        gateBefore("connection/wifi/modes/nativeaa/EarlyHotspot.kt", "fun start(", "HotspotManager.setHotspotEnabled(app, true)")
    }

    @Test fun theWifiClientIsNeverTakenDownInCodos() {
        gateBefore("connection/wifi/direct/StationStandDown.kt", "fun standDown(", "wm.disconnect()")
        gateBefore("utils/WifiStackRecovery.kt", "suspend fun restartWifi(", "P2pStackWedgePolicy.RESTART_COMMAND")
    }

    @Test fun autoEnableHotspotIsOffByDefaultInCodos() {
        val text = File(root, "utils/Settings.kt").readText()
        assertTrue(text.contains("prefs.getBoolean(\"auto-enable-hotspot\", !com.andrerinas.openheadunit.BuildConfig.CODOS)"))
    }
}
